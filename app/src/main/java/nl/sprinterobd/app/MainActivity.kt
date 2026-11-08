package nl.sprinterobd.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Dunne schil: een WebView met de dashboard-interface (assets/www) en een Bluetooth-SPP-brug.
 * ALLEEN LEZEN: write() laat uitsluitend AT-commando's en lees-modes door (zie READ_ONLY).
 * Foutcodes wissen (Mode 04) kan alleen via clearDtc() met bevestigingstoken.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    @Volatile private var reading = false

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val READ_ONLY = Regex("^(AT(?!PP)[A-Z0-9@ .]*|(01|02|03|07|09|0A)[0-9A-F]*|06[0-9A-F]{2}|22[0-9A-F]{4})$")
        private const val REQ_BT = 1
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("file:///android_asset/www/index.html")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        emit("onSerialState", "permissie", if (hasBt()) "ok" else "geweigerd")
    }

    override fun onDestroy() {
        closeSocket()
        super.onDestroy()
    }

    private fun hasBt() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Roep een JS-functie window.<fn>(a, b) aan op de UI-thread. */
    private fun emit(fn: String, a: String, b: String = "") {
        val js = "window.$fn && window.$fn(${JSONObject.quote(a)}, ${JSONObject.quote(b)})"
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun closeSocket() {
        reading = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(address: String): BluetoothSocket {
        val bm = getSystemService(BluetoothManager::class.java)
        val dev = bm.adapter.getRemoteDevice(address)
        // Geen cancelDiscovery(): vereist BLUETOOTH_SCAN en wij scannen nooit (alleen gekoppelde apparaten).
        // Verschillende ELM327-klonen accepteren verschillende varianten; probeer ze na elkaar.
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "onbeveiligd SPP" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            "beveiligd SPP" to { dev.createRfcommSocketToServiceRecord(SPP) },
            "onbeveiligd kanaal 1" to {
                dev.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(dev, 1) as BluetoothSocket
            },
            "beveiligd kanaal 1" to {
                dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(dev, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (round in 1..2) {
            for ((name, make) in attempts) {
                var s: BluetoothSocket? = null
                try {
                    emit("onSerialState", "poging", "$name (ronde $round)")
                    s = make()
                    s.connect()
                    emit("onSerialState", "poging", "gelukt: $name")
                    return s
                } catch (e: Exception) {
                    last = e
                    emit("onSerialState", "poging", "mislukt: $name: ${e.message}")
                    try { s?.close() } catch (_: Exception) {}
                    Thread.sleep(400)
                }
            }
        }
        throw last ?: Exception("Verbinden mislukt")
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(1024)
        while (reading) {
            val n = try { input.read(buf) } catch (e: Exception) { -1 }
            if (n < 0) {
                if (reading) { closeSocket(); emit("onSerialState", "verbroken", "Verbinding verbroken") }
                break
            }
            if (n > 0) emit("onSerialData", String(buf, 0, n, Charsets.US_ASCII))
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun hasPermission(): Boolean = hasBt()

        @JavascriptInterface
        fun requestPermission() {
            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT) }
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun bondedDevices(): String {
            val arr = JSONArray()
            if (!hasBt()) return arr.toString()
            val bm = getSystemService(BluetoothManager::class.java)
            bm.adapter?.bondedDevices?.forEach {
                arr.put(JSONObject().put("name", it.name ?: "?").put("address", it.address))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun connect(address: String) {
            closeSocket()
            thread(name = "bt-connect") {
                try {
                    if (!hasBt()) throw SecurityException("Geen Bluetooth-toestemming")
                    val s = openSocket(address)
                    socket = s
                    out = s.outputStream
                    reading = true
                    emit("onSerialState", "verbonden", address)
                    readLoop(s.inputStream)
                } catch (e: Exception) {
                    closeSocket()
                    emit("onSerialState", "fout", e.message ?: e.javaClass.simpleName)
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            closeSocket()
            emit("onSerialState", "verbroken", "Losgekoppeld")
        }

        /** Stuur één commando (zonder \r). Geeft false als het geblokkeerd is of niet verbonden. */
        @JavascriptInterface
        fun write(cmd: String): Boolean {
            val c = cmd.replace(" ", "").uppercase()
            if (!READ_ONLY.matches(c)) {
                emit("onSerialState", "geblokkeerd", c)
                return false
            }
            return send(c)
        }

        /** Mode 04 (foutcodes wissen): alleen met expliciet bevestigingstoken uit de UI-dialoog. */
        @JavascriptInterface
        fun clearDtc(confirm: String): Boolean = if (confirm == "WISSEN") send("04") else false

        private fun send(c: String): Boolean {
            val o = out ?: return false
            return try {
                o.write((c + "\r").toByteArray(Charsets.US_ASCII)); o.flush(); true
            } catch (e: Exception) {
                closeSocket(); emit("onSerialState", "verbroken", e.message ?: "schrijffout"); false
            }
        }

        /** Sla een tekstbestand op in Downloads/SprinterOBD (geen extra machtiging nodig). */
        @JavascriptInterface
        fun saveFile(name: String, content: String): String {
            return try {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".csv")) "text/csv" else "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SprinterOBD")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: return "fout: kon bestand niet aanmaken"
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                "Downloads/SprinterOBD/$name"
            } catch (e: Exception) {
                "fout: ${e.message}"
            }
        }
    }
}
