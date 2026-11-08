package nl.sprinterobd.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Interface: een WebView die de toestand van ObdService toont. Het meten zelf gebeurt in de dienst
 * (ook met scherm uit of een andere app op de voorgrond). ALLEEN LEZEN: zie Elm.kt.
 */
class MainActivity : Activity(), Core.Listener {

    private lateinit var web: WebView
    private var svc: ObdService? = null

    companion object { private const val REQ = 1 }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = (binder as ObdService.LocalBinder).service
            svc?.uiListener = this@MainActivity
            emit("onServiceReady", "")
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
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
        if (!hasPerms()) requestPerms() else startAndBind()
    }

    private fun perms(): Array<String> = if (android.os.Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasBt() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun hasPerms() = perms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPerms() = requestPermissions(perms(), REQ)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        emit("onEvent", "permissie", if (hasBt()) "ok" else "geweigerd")
        if (hasBt()) startAndBind()
    }

    private fun startAndBind() {
        if (!hasBt()) return
        ObdService.start(this)
        bindService(Intent(this, ObdService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        svc?.uiListener = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun emit(fn: String, a: String, b: String = "") {
        val js = "window.$fn && window.$fn(${JSONObject.quote(a)}, ${JSONObject.quote(b)})"
        web.post { web.evaluateJavascript(js, null) }
    }

    // ---- Core.Listener (doorgegeven door de dienst zolang de interface open is) ----
    override fun onAlarm(id: Int, title: String, text: String) = emit("onEvent", "alarm", "$title $text")
    override fun onEvent(state: String, msg: String) = emit("onEvent", state, msg)
    override fun onIdleTooLong() = emit("onEvent", "gestopt", "Geen contact met de auto")

    inner class Bridge {
        private fun core() = svc?.core
        private fun store() = svc?.store

        @JavascriptInterface fun hasPermission(): Boolean = hasBt()
        @JavascriptInterface fun requestPermission() { runOnUiThread { requestPerms() } }
        @JavascriptInterface fun serviceRunning(): Boolean = ObdService.instance != null
        @JavascriptInterface fun startService() { runOnUiThread { startAndBind() } }
        @JavascriptInterface fun stopService() { runOnUiThread { svc?.stopAll() } }

        @JavascriptInterface fun getState(): String = core()?.stateJson() ?: "{}"
        @JavascriptInterface fun getHistory(): String = core()?.historyJson() ?: "[]"
        @JavascriptInterface fun getData(name: String): String = store()?.get(name, "null") ?: "null"

        /** Eenmalige overname van gegevens uit de vorige app-versie (localStorage), alleen als er nog niets is. */
        @JavascriptInterface fun migrate(name: String, json: String): Boolean {
            val s = store() ?: return false
            if (s.exists(name)) return false
            s.set(name, json); return true
        }

        /** Asynchrone actie; resultaat via window.onAction(id, ok, json). */
        @JavascriptInterface
        fun action(id: String, name: String, arg: String) {
            thread(name = "actie-$name") {
                try {
                    val c = core() ?: throw IllegalStateException("Meetdienst draait niet")
                    val res: String = when (name) {
                        "readDtcs" -> c.readDtcs().toString()
                        "clearDtcs" -> JSONObject().put("ok", c.clearDtcs(arg)).toString()
                        "mode06" -> c.mode06().toString()
                        "tripReset" -> { c.tripReset(); "{}" }
                        "addTank" -> c.addTank(arg.replace(',', '.').toDouble()).toString()
                        "setCfg" -> { c.setCfg(arg); "{}" }
                        "setDevice" -> { c.setDevice(arg); "{}" }
                        else -> throw IllegalArgumentException("Onbekende actie $name")
                    }
                    emitResult(id, true, res)
                } catch (e: Exception) {
                    emitResult(id, false, JSONObject().put("fout", e.message ?: e.javaClass.simpleName).toString())
                }
            }
        }

        private fun emitResult(id: String, ok: Boolean, json: String) {
            val js = "window.onAction && window.onAction(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(json)})"
            web.post { web.evaluateJavascript(js, null) }
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun bondedDevices(): String {
            val arr = JSONArray()
            if (!hasBt()) return arr.toString()
            getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.forEach {
                arr.put(JSONObject().put("name", it.name ?: "?").put("address", it.address))
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun batteryOk(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun askBattery() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }

        // ---- logbestanden ----
        private fun logDir() = store()?.logDir ?: File(filesDir, "logs").apply { mkdirs() }
        private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9_.-]"), "_")

        @JavascriptInterface
        fun listFiles(): String {
            svc?.core?.flushLogs()
            val arr = JSONArray()
            logDir().listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
                arr.put(JSONObject().put("name", it.name).put("size", it.length()).put("modified", it.lastModified()))
            }
            return arr.toString()
        }

        @JavascriptInterface fun deleteFile(name: String): Boolean = File(logDir(), safe(name)).delete()

        @JavascriptInterface
        fun exportFile(name: String): String {
            val f = File(logDir(), safe(name))
            if (!f.exists()) return "fout: bestand bestaat niet"
            return saveFile(f.name, f.readText(Charsets.UTF_8))
        }

        /** Sla een tekstbestand op in Downloads/SprinterOBD (geen extra machtiging nodig). */
        @JavascriptInterface
        fun saveFile(name: String, content: String): String = try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".csv")) "text/csv" else "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SprinterOBD")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IllegalStateException("kon bestand niet aanmaken")
            contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            "Downloads/SprinterOBD/$name"
        } catch (e: Exception) { "fout: ${e.message}" }
    }
}
