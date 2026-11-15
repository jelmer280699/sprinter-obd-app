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
 * Victron "Instant Readout" (Bluetooth LE-advertenties) van de Orion XS ontvangen en ontsleutelen.
 * ALLEEN LUISTEREN: er wordt geen verbinding gemaakt en niets naar de Orion gestuurd.
 *
 * Formaat (reverse-engineered, bron: github.com/keshavdv/victron-ble, devices/base.py + orion_xs.py;
 * niet officieel door Victron gedocumenteerd):
 *  fabrikantdata Victron (0x02E1): [0-1] prefix, [2-3] model-id, [4] recordtype (0x0F = Orion XS),
 *  [5-6] teller (LE), [7] eerste byte van de sleutel (controle), [8..] versleuteld (AES-128-CTR,
 *  tellerblok = teller little-endian in 16 bytes).
 *  Ontsleuteld (bits, LSB eerst): toestand 8, fout 8, uit-V 16 (/100), uit-A 16 (/10), in-V 16 (/100),
 *  in-A 16 (/10), uit-reden 32.
 */
class Victron(private val ctx: Context, private val onData: (Map<String, Any>) -> Unit, private val onStatus: (String) -> Unit) {

    companion object {
        const val VICTRON_ID = 0x02E1
        const val RECORD_ORION_XS = 0x0F
        private const val NA16 = 0xFFFF

        val STATE = mapOf(0 to "uit", 1 to "laag vermogen", 2 to "storing", 3 to "bulk", 4 to "absorptie", 5 to "float",
            6 to "opslag", 7 to "egaliseren", 9 to "omvormen", 11 to "voeding", 245 to "opstarten")
        val OFF = mapOf(0x0L to "", 0x1L to "geen ingangsvermogen", 0x2L to "uitgeschakeld (schakelaar)",
            0x4L to "uitgeschakeld (instelling)", 0x8L to "remote-ingang", 0x10L to "beveiliging actief", 0x40L to "BMS",
            0x80L to "motor uit (ingangsspanning)", 0x81L to "motor uit + ingangsspanning te laag", 0x100L to "ingangsspanning analyseren")

        /** Ontsleutel en ontleed één advertentie. Geeft null als het geen Orion XS-record is; gooit bij sleutelfout. */
        fun parse(data: ByteArray, key: ByteArray): Map<String, Any>? {
            if (data.size < 8 + 14) return null
            if ((data[4].toInt() and 0xFF) != RECORD_ORION_XS) return null
            if (data[7] != key[0]) throw IllegalArgumentException("sleutel past niet")
            val iv = ByteArray(16).also { it[0] = data[5]; it[1] = data[6] }
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            // Alleen het eerste blok: daarin zitten de 14 bytes; voorbij 16 bytes telt Java's CTR anders door.
            val d = c.doFinal(data.copyOfRange(8, minOf(data.size, 24)))
            fun u8(i: Int) = d[i].toInt() and 0xFF
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            val out = HashMap<String, Any>()
            out["orion_raw"] = d.take(14).joinToString(" ") { "%02X".format(it) }
            val st = u8(0)
            if (st != 0xFF) { out["orion_state_nr"] = st.toDouble(); out["orion_state"] = STATE[st] ?: "toestand $st" }
            if (u8(1) != 0xFF) out["orion_error"] = u8(1).toDouble()
            if (u16(2) != NA16) out["orion_out_v"] = u16(2) / 100.0
            if (u16(4) != NA16) out["orion_out_a"] = u16(4) / 10.0
            if (u16(6) != NA16) out["orion_in_v"] = u16(6) / 100.0
            if (u16(8) != NA16) out["orion_in_a"] = u16(8) / 10.0
            val off = (u16(10).toLong()) or (u16(12).toLong() shl 16)
            out["orion_off"] = OFF[off] ?: "code 0x%X".format(off)
            return out
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
    @Volatile var lastSeen = 0L
        private set

    @SuppressLint("MissingPermission")
    fun start(mac: String, key: ByteArray): Boolean {
        stop()
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(VICTRON_ID) ?: return
                try {
                    parse(data, key)?.let { lastSeen = System.currentTimeMillis(); onData(it) }
                } catch (e: Exception) {
                    onStatus("Orion: ${e.message}")
                }
            }
            override fun onScanFailed(errorCode: Int) { onStatus("Orion: scannen mislukt (code $errorCode)") }
        }
        // Gefilterd op het MAC-adres: zo mag Android ook met het scherm uit blijven scannen.
        val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        return try { scanner.startScan(listOf(filter), settings, callback); cb = callback; true }
        catch (e: SecurityException) { onStatus("Orion: geen toestemming om te scannen"); false }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val c = cb ?: return
        try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) } catch (_: Exception) {}
        cb = null
    }
}
