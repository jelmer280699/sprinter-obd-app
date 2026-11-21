package nl.sprinterobd.app

import java.util.Locale

/**
 * Meetharnas (voorstel P02): verzamelt per venster van ~60 s
 *  - RTT per ELM-commandosoort (pid = Mode 01, did = Mode 22, at = AT-commando's, overig),
 *  - pollcyclustijd (Core.pollLoop),
 *  - tekentijd per Android Auto-frame (DashRenderer.draw).
 * Core schrijft elke minuut een CSV-rij (meet_*.csv, naast het ritlog) en leegt de vensters met [drain].
 * Alleen tijdsmetingen; geen invloed op het OBD-verkeer.
 */
object Meet {

    private const val CAP = 4096   // max. monsters per venster per reeks (houdt geheugen begrensd)

    private class Series {
        private val v = LongArray(CAP)
        private var n = 0

        @Synchronized fun add(ns: Long) { if (n < CAP) v[n++] = ns }

        /** Geeft "n;p50;p95;p99" (ms, 1 decimaal) en leegt de reeks. */
        @Synchronized fun drain(): String {
            val c = n
            n = 0
            if (c == 0) return "0;;;"
            val a = v.copyOf(c)
            a.sort()
            fun p(q: Double) = String.format(Locale.ROOT, "%.1f", a[((c - 1) * q).toInt()] / 1e6)
            return "$c;${p(0.50)};${p(0.95)};${p(0.99)}"
        }
    }

    private val pid = Series()
    private val did = Series()
    private val at = Series()
    private val other = Series()
    private val cycle = Series()
    private val draw = Series()

    /** Eén ELM-commando: tijd van schrijven tot het '>'-prompt. */
    fun rtt(cmd: String, ns: Long) {
        when {
            cmd.startsWith("AT") -> at.add(ns)
            cmd.startsWith("22") -> did.add(ns)
            cmd.startsWith("01") -> pid.add(ns)
            else -> other.add(ns)
        }
    }

    fun cycle(ns: Long) = cycle.add(ns)
    fun draw(ns: Long) = draw.add(ns)

    const val HEADER = "t;elm;proto;did_aan;did_ms;aa_hw;" +
        "cyc_n;cyc_p50;cyc_p95;cyc_p99;pid_n;pid_p50;pid_p95;pid_p99;did_n;did_p50;did_p95;did_p99;" +
        "at_n;at_p50;at_p95;at_p99;ov_n;ov_p50;ov_p95;ov_p99;draw_n;draw_p50;draw_p95;draw_p99;hersteld;timeouts"

    /** Leegt alle vensters en geeft één CSV-rij (kolommen: zie [HEADER]). Tijden in ms. */
    fun drainRow(t: String, elm: String, proto: String, didOn: Boolean, didMs: Long, hw: Boolean, hersteld: Int, timeouts: Int): String =
        listOf(t, elm.replace(';', ','), proto.replace(';', ','), if (didOn) "1" else "0", didMs.toString(), if (hw) "1" else "0",
            cycle.drain(), pid.drain(), did.drain(), at.drain(), other.drain(), draw.drain(), hersteld.toString(), timeouts.toString())
            .joinToString(";")
}
