package nl.sprinterobd.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Victron "Instant Readout" (Bluetooth LE-advertenties) ontvangen en ontsleutelen: Orion XS, SmartShunt
 * (batterijmonitor) en SmartSolar MPPT. ALLEEN LUISTEREN: geen verbinding, niets verzonden.
 *
 * Formaat (reverse-engineered, bron: github.com/keshavdv/victron-ble devices/base.py, orion_xs.py,
 * battery_monitor.py, solar_charger.py; niet officieel door Victron gedocumenteerd):
 *  fabrikantdata Victron (0x02E1): [0-1] prefix, [2-3] model-id, [4] recordtype, [5-6] teller (LE),
 *  [7] eerste sleutelbyte (controle), [8..] AES-128-CTR (tellerblok = teller little-endian in 16 bytes).
 *  Ontsleuteld: velden LSB-eerst achter elkaar (BitReader).
 */
class Victron(private val ctx: Context, private val onData: (String, Map<String, Any>) -> Unit, private val onStatus: (String, String) -> Unit) {

    companion object {
        const val VICTRON_ID = 0x02E1
        const val REC_SOLAR = 0x01
        const val REC_BATTERY = 0x02
        const val REC_ORION_XS = 0x0F

        val STATE = mapOf(0 to "uit", 1 to "laag vermogen", 2 to "storing", 3 to "bulk", 4 to "absorptie", 5 to "float",
            6 to "opslag", 7 to "egaliseren", 9 to "omvormen", 11 to "voeding", 245 to "opstarten")
        val OFF = mapOf(0x0L to "", 0x1L to "geen ingangsvermogen", 0x2L to "uitgeschakeld (schakelaar)",
            0x4L to "uitgeschakeld (instelling)", 0x8L to "remote-ingang", 0x10L to "beveiliging actief", 0x40L to "BMS",
            0x80L to "motor uit (ingangsspanning)", 0x81L to "motor uit + ingangsspanning te laag", 0x100L to "ingangsspanning analyseren")

        /** Bits LSB-eerst lezen, zoals BitReader in victron-ble. */
        class Bits(private val d: ByteArray) {
            private var pos = 0
            fun u(n: Int): Long {
                var v = 0L
                for (i in 0 until n) {
                    val bit = (d[(pos + i) / 8].toInt() shr ((pos + i) % 8)) and 1
                    v = v or (bit.toLong() shl i)
                }
                pos += n
                return v
            }
            fun s(n: Int): Long { val v = u(n); return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v }
        }

        fun type(data: ByteArray): Int = if (data.size > 4) data[4].toInt() and 0xFF else -1

        /** Ontsleutel en ontleed. Null als het recordtype niet ondersteund is; gooit bij een verkeerde sleutel. */
        fun parse(data: ByteArray, key: ByteArray): Map<String, Any>? {
            val rec = type(data)
            if (rec !in setOf(REC_SOLAR, REC_BATTERY, REC_ORION_XS) || data.size < 9) return null
            if (data[7] != key[0]) throw IllegalArgumentException("sleutel past niet")
            val iv = ByteArray(16).also { it[0] = data[5]; it[1] = data[6] }
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            // alleen het eerste blok (max 16 bytes): daar zitten alle velden in; voorbij 16 telt Java's CTR anders door
            val enc = data.copyOfRange(8, minOf(data.size, 24))
            val d = c.doFinal(enc).copyOf(16)
            val b = Bits(d)
            val o = HashMap<String, Any>()
            o["ruw"] = d.take(enc.size).joinToString(" ") { "%02X".format(it) }
            when (rec) {
                REC_ORION_XS -> {
                    o["type"] = "orion"
                    val st = b.u(8).toInt(); val err = b.u(8).toInt()
                    val outV = b.u(16); val outA = b.u(16); val inV = b.u(16); val inA = b.u(16); val off = b.u(32)
                    if (st != 0xFF) o["state"] = STATE[st] ?: "toestand $st"
                    if (err != 0xFF) o["error"] = err.toDouble()
                    if (outV != 0xFFFFL) o["out_v"] = outV / 100.0
                    if (outA != 0xFFFFL) o["out_a"] = outA / 10.0
                    if (inV != 0xFFFFL) o["in_v"] = inV / 100.0
                    if (inA != 0xFFFFL) o["in_a"] = inA / 10.0
                    o["off"] = OFF[off] ?: "code 0x%X".format(off)
                }
                REC_BATTERY -> {
                    o["type"] = "shunt"
                    val rem = b.u(16); val v = b.s(16); b.u(16) /* alarm */; val aux = b.u(16); val auxMode = b.u(2).toInt()
                    val a = b.s(22); val cons = b.u(20); val soc = b.u(10)
                    if (rem != 0xFFFFL) o["rest_min"] = rem.toDouble()
                    if (v != 0x7FFFL) o["v"] = v / 100.0
                    if (a != -1L) o["a"] = a / 1000.0   // alle-enen = n.v.t. (zie bron: -1 na signed lezen)
                    if (cons != 0xFFFFFL) o["verbruikt_ah"] = -cons / 10.0
                    if (soc != 0x3FFL) o["soc"] = soc / 10.0
                    when (auxMode) {
                        0 -> { val sv = if (aux and 0x8000L != 0L) aux - 0x10000 else aux; o["starter_v"] = sv / 100.0 }
                        1 -> o["midpoint_v"] = aux / 100.0
                        2 -> if (aux != 0L) o["temp_c"] = Math.round((aux / 100.0 - 273.15) * 10) / 10.0
                    }
                }
                REC_SOLAR -> {
                    o["type"] = "mppt"
                    val st = b.u(8).toInt(); val err = b.u(8).toInt(); val v = b.s(16); val a = b.s(16)
                    val yld = b.u(16); val pw = b.u(16); val load = b.u(9)
                    if (st != 0xFF) o["state"] = STATE[st] ?: "toestand $st"
                    if (err != 0xFF) o["error"] = err.toDouble()
                    if (v != 0x7FFFL) o["v"] = v / 100.0
                    if (a != 0x7FFFL) o["a"] = a / 10.0
                    if (yld != 0xFFFFL) o["yield_wh"] = yld * 10.0
                    if (pw != 0xFFFFL) o["w"] = pw.toDouble()
                    if (load != 0x1FFL) o["load_a"] = load / 10.0
                }
            }
            return o
        }

        fun hexKey(s: String): ByteArray? {
            val h = s.trim().replace(" ", "").lowercase()
            if (!Regex("^[0-9a-f]{32}$").matches(h)) return null
            return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun normMac(s: String): String? {
            val h = s.trim().replace(":", "").replace("-", "").uppercase()
            if (!Regex("^[0-9A-F]{12}$").matches(h)) return null
            return h.chunked(2).joinToString(":")
        }
    }

    private var cb: ScanCallback? = null

    /** devices: MAC (AA:BB:..) -> 16-byte sleutel. */
    @SuppressLint("MissingPermission")
    fun start(devices: Map<String, ByteArray>): Boolean {
        stop()
        if (devices.isEmpty()) return false
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device?.address?.uppercase() ?: return
                val key = devices[mac] ?: return
                val data = result.scanRecord?.getManufacturerSpecificData(VICTRON_ID) ?: return
                try {
                    val p = parse(data, key)
                    if (p != null) onData(mac, p) else onStatus(mac, "onbekend recordtype 0x%02X".format(type(data)))
                } catch (e: Exception) { onStatus(mac, e.message ?: "fout") }
            }
            override fun onScanFailed(errorCode: Int) { onStatus("", "scannen mislukt (code $errorCode)") }
        }
        // Gefilterd op MAC-adressen: zo mag Android ook met het scherm uit blijven scannen.
        val filters = devices.keys.map { ScanFilter.Builder().setDeviceAddress(it).build() }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        return try { scanner.startScan(filters, settings, callback); cb = callback; true }
        catch (e: SecurityException) { onStatus("", "geen toestemming om te scannen"); false }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val c = cb ?: return
        try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) } catch (_: Exception) {}
        cb = null
    }
}
