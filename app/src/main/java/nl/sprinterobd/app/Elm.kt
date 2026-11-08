package nl.sprinterobd.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ELM327-laag over een Bluetooth-socket. ALLEEN LEZEN: send() laat uitsluitend AT-commando's
 * en lees-modes door. Mode 04 (codes wissen) alleen via clearDtcs("WISSEN").
 * Eén commando tegelijk (synchronized); een leesthread zet binnenkomende data in een wachtrij.
 */
class Elm(input: InputStream, private val out: OutputStream, private val onDebug: (String) -> Unit = {}) {

    companion object {
        val READ_ONLY = Regex("^(AT(?!PP)[A-Z0-9@ .]*|(01|02|03|07|09|0A)[0-9A-F]*|06[0-9A-F]{2}|22[0-9A-F]{4})$")
        private const val EOF = "\u0000EOF"
        private val ERRORS = listOf("UNABLE TO CONNECT", "CAN ERROR", "BUS INIT", "BUS ERROR", "STOPPED", "BUFFER FULL", "DATA ERROR")

        /** Antwoordtekst -> bytes (single- en multi-frame); null bij NO DATA. */
        fun parseBytes(text: String): IntArray? {
            val lines = text.replace(">", "").split(Regex("[\\r\\n]+")).map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") }
            if (lines.isEmpty()) return null
            val joined = lines.joinToString(" ").uppercase()
            if (joined.contains("NO DATA")) return null
            for (e in ERRORS) if (joined.contains(e)) throw IOException(joined)
            if (joined.trim() == "?") throw IOException("ELM begrijpt commando niet")
            val multi = lines.any { Regex("^[0-9A-F]:").containsMatchIn(it) }
            var total: Int? = null
            val out = ArrayList<Int>()
            for (raw in lines) {
                var l = raw
                if (multi) {
                    val m = Regex("^([0-9A-F]):\\s*(.*)$").find(l)
                    if (m == null) {
                        if (Regex("^[0-9A-F]{3}$").matches(l)) total = l.toInt(16)
                        continue
                    }
                    l = m.groupValues[2]
                }
                for (b in l.split(Regex("\\s+"))) if (Regex("^[0-9A-F]{2}$").matches(b)) out.add(b.toInt(16))
            }
            val arr = out.toIntArray()
            return if (total != null && total < arr.size) arr.copyOf(total) else arr
        }
    }

    private val queue = LinkedBlockingQueue<String>()
    @Volatile var alive = true
        private set

    init {
        thread(name = "elm-reader", isDaemon = true) {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) queue.put(String(buf, 0, n, Charsets.US_ASCII))
                }
            } catch (_: Exception) {
            }
            alive = false
            queue.put(EOF)
        }
    }

    @Synchronized
    private fun raw(cmd: String, timeoutMs: Long): String {
        if (!alive) throw IOException("Verbinding verbroken")
        queue.clear()
        onDebug("TX $cmd")
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw IOException("Timeout op $cmd")
            val chunk = queue.poll(left, TimeUnit.MILLISECONDS) ?: throw IOException("Timeout op $cmd")
            if (chunk == EOF) throw IOException("Verbinding verbroken")
            sb.append(chunk)
            if (sb.contains('>')) break
        }
        val r = sb.toString().replace(">", "")
        onDebug("RX " + r.replace(Regex("[\\r\\n]+"), " | ").trim())
        return r
    }

    fun send(cmd: String, timeoutMs: Long = 5000): String {
        val c = cmd.replace(" ", "").uppercase()
        if (!READ_ONLY.matches(c)) throw SecurityException("Geblokkeerd (alleen lezen): $c")
        return raw(c, timeoutMs)
    }

    fun query(cmd: String, timeoutMs: Long = 5000): IntArray? = parseBytes(send(cmd, timeoutMs))

    /** Mode 04 alleen met expliciet bevestigingstoken uit de UI-dialoog. */
    fun clearDtcs(confirm: String): Boolean {
        if (confirm != "WISSEN") return false
        return raw("04", 10000).contains("44")
    }

    /** UDS 0x22 ReadDataByIdentifier (alleen lezen). */
    fun readDid(did: Int): IntArray? {
        val d = query("22%04X".format(did), 3000) ?: return null
        return if (d.size >= 3 && d[0] == 0x62 && d[1] == did shr 8 && d[2] == did and 0xFF) d.copyOfRange(3, d.size) else null
    }

    /** Initialisatie zoals getest op deze bus (zie README). */
    fun init(skipAtz: Boolean): Pair<String, String> {
        if (skipAtz) send("ATD", 3000) else send("ATZ", 4000)
        for (c in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1")) send(c, 2000)
        val elm = send("ATI", 2000).trim()
        send("ATSP6", 2000)
        var r = send("0100", 10000)
        if (!r.contains("41 00")) {
            send("ATSP0", 2000)
            r = send("0100", 15000)
            if (!r.contains("41 00")) throw IOException("ECU antwoordt niet (contact aan?)")
        }
        // Getest: Mode 01 alleen functioneel (7DF); alleen antwoorden van de motor-ECU (7E8) accepteren.
        send("ATSH7DF", 2000)
        send("ATCRA7E8", 2000)
        val proto = send("ATDP", 2000).trim()
        return elm to proto
    }
}
