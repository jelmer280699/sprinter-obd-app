package nl.sprinterobd.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Mode 01-PID's van deze ECU (SAE J1979). Port van obd/pids.py. */
object Pids {
    val LEN = mapOf(
        0x01 to 4, 0x04 to 1, 0x05 to 1, 0x0C to 2, 0x0D to 1, 0x11 to 1, 0x13 to 1, 0x1C to 1, 0x1F to 2,
        0x21 to 2, 0x24 to 4, 0x30 to 1, 0x31 to 2, 0x33 to 1, 0x42 to 2, 0x46 to 1, 0x49 to 1, 0x4A to 1,
        0x51 to 1, 0x5C to 1, 0x66 to 5, 0x68 to 7, 0x6C to 5, 0x6D to 11, 0x70 to 10, 0x85 to 10, 0x88 to 13, 0x92 to 2
    )

    /** '41 0C xx xx 0D xx ...' (ook multi-PID) -> pid -> databytes. frame=true voor Mode 02. */
    fun split(data: IntArray?, modeResp: Int = 0x41, frame: Boolean = false): Map<Int, IntArray> {
        val out = HashMap<Int, IntArray>()
        if (data == null || data.isEmpty() || data[0] != modeResp) return out
        var i = 1
        while (i < data.size) {
            val pid = data[i++]
            if (frame) i++
            val n = LEN[pid] ?: break
            if (i + n > data.size) break
            out[pid] = data.copyOfRange(i, i + n)
            i += n
        }
        return out
    }

    private fun u16(a: Int, b: Int) = a * 256 + b
    private fun r1(x: Double) = (x * 10).roundToInt() / 10.0
    private fun r2(x: Double) = (x * 100).roundToInt() / 100.0

    /** Decodeer naar waarden; status (PID 01) vult ook mil/dtc_count/readiness. */
    fun decode(p: Map<Int, IntArray>, v: MutableMap<String, Any>) {
        p[0x04]?.let { v["load"] = r1(it[0] * 100.0 / 255) }
        p[0x05]?.let { v["coolant"] = (it[0] - 40).toDouble() }
        p[0x0C]?.let { v["rpm"] = (u16(it[0], it[1]) / 4.0).roundToInt().toDouble() }
        p[0x0D]?.let { v["speed"] = it[0].toDouble() }
        p[0x11]?.let { v["throttle"] = r1(it[0] * 100.0 / 255) }
        p[0x1F]?.let { v["runtime"] = u16(it[0], it[1]).toDouble() }
        p[0x24]?.let { v["lambda"] = (u16(it[0], it[1]) * 2.0 / 65536 * 1000).roundToInt() / 1000.0 }
        p[0x33]?.let { v["baro"] = it[0].toDouble() }
        p[0x42]?.let { v["voltage"] = u16(it[0], it[1]) / 1000.0 }
        p[0x46]?.let { v["ambient"] = (it[0] - 40).toDouble() }
        p[0x49]?.let { v["pedal"] = r1(it[0] * 100.0 / 255) }
        p[0x5C]?.let { v["oil"] = (it[0] - 40).toDouble() }
        p[0x66]?.let { if (it[0] and 1 == 1) v["maf"] = r2(u16(it[1], it[2]) / 32.0) }
        p[0x68]?.let { if (it[0] and 1 == 1) v["iat"] = (it[1] - 40).toDouble() }
        p[0x6D]?.let { v["rail_cmd"] = (u16(it[1], it[2]) / 10.0).roundToInt().toDouble(); v["rail_act"] = (u16(it[3], it[4]) / 10.0).roundToInt().toDouble() }
        p[0x70]?.let {
            if (it[0] and 1 == 1) v["boost_cmd"] = r1(u16(it[1], it[2]) * 0.03125)
            if (it[0] and 2 == 2) v["boost_act"] = r1(u16(it[3], it[4]) * 0.03125)
        }
        p[0x4A]?.let { v["pedal_e"] = r1(it[0] * 100.0 / 255) }
        p[0x6C]?.let { if (it[0] and 1 == 1) v["throttle_cmd"] = r1(it[1] * 100.0 / 255) }
        p[0x21]?.let { v["dist_mil"] = u16(it[0], it[1]).toDouble() }
        p[0x31]?.let { v["dist_clr"] = u16(it[0], it[1]).toDouble() }
        p[0x30]?.let { v["warmups"] = it[0].toDouble() }
        // [niet geverifieerd]: alleen ruw tonen, geen formule
        for ((pid, key) in listOf(0x85 to "adblue_ruw", 0x88 to "scr_ruw", 0x92 to "brandstofsys_ruw"))
            p[pid]?.let { v[key] = it.joinToString(" ") { b -> "%02X".format(b) } }
        p[0x01]?.let {
            val (a, b, c, e) = listOf(it[0], it[1], it[2], it[3])
            v["mil"] = (a and 0x80) != 0
            v["dtc_count"] = (a and 0x7F).toDouble()
            val names = listOf("NMHC-katalysator", "NOx/SCR", null, "Turbodruk", null, "Uitlaatgassensor", "Roetfilter (DPF)", "EGR/VVT")
            val mon = JSONObject()
            names.forEachIndexed { bit, n -> if (n != null && (c and (1 shl bit)) != 0) mon.put(n, if ((e and (1 shl bit)) != 0) "niet klaar" else "klaar") }
            listOf(0 to "Verbranding/misfire", 1 to "Brandstofsysteem", 2 to "Componenten").forEach { (bit, n) ->
                if ((b and (1 shl bit)) != 0) mon.put(n, if ((b and (1 shl (bit + 4))) != 0) "niet klaar" else "klaar")
            }
            v["readiness"] = mon
        }
    }

    val NAMES = mapOf(0x04 to ("Motorbelasting" to "%"), 0x05 to ("Koelvloeistof" to "°C"), 0x0C to ("Toerental" to "rpm"),
        0x0D to ("Snelheid" to "km/u"), 0x11 to ("Smoorklep" to "%"), 0x1F to ("Looptijd motor" to "s"), 0x33 to ("Barometer" to "kPa"),
        0x42 to ("Accuspanning" to "V"), 0x46 to ("Buitentemperatuur" to "°C"), 0x5C to ("Motorolie" to "°C"))
}

/** Foutcodes: decoderen + Nederlandse uitleg (tabel uit assets/www/dtc.json, gegenereerd uit obd/dtc.py). */
object Dtc {
    private var table: JSONObject = JSONObject()
    fun load(json: String) { table = JSONObject(json) }

    fun decode(data: IntArray?, modeResp: Int): List<String> {
        if (data == null || data.isEmpty() || data[0] != modeResp) return emptyList()
        val out = ArrayList<String>()
        var i = 2
        while (i + 1 < data.size) {
            val a = data[i]; val b = data[i + 1]; i += 2
            if (a == 0 && b == 0) continue
            out.add("${"PCBU"[a shr 6]}${(a shr 4) and 3}${"%X".format(a and 0xF)}${"%02X".format(b)}")
        }
        return out
    }

    fun explain(code: String): JSONObject {
        val e = table.optJSONArray(code)
        if (e != null) return JSONObject().put("code", code).put("omschrijving", e.getString(0)).put("tip", e.getString(1))
        val soort = mapOf('P' to "Aandrijving", 'C' to "Chassis", 'B' to "Carrosserie", 'U' to "Netwerk")[code[0]]
        val d = if (code[1] == '1' || code[1] == '3') "$soort: fabrikantspecifieke code (Mercedes). Betekenis opzoeken in Mercedes-documentatie."
        else "$soort: generieke code, niet in de lokale lijst."
        return JSONObject().put("code", code).put("omschrijving", d).put("tip", "")
    }
}

/** Mode 06 (CAN-formaat): ruwe waarden + binnen/buiten grens. */
object Mode06 {
    private val MID_NL = mapOf(0x01 to "Lambdasonde B1S1", 0x02 to "Lambdasonde B1S2", 0x03 to "Lambdasonde B1S3",
        0x21 to "Katalysator B1", 0x31 to "EGR", 0x41 to "Lambdasonde-verwarming B1S1", 0x42 to "Lambdasonde-verwarming B1S2",
        0x81 to "Brandstofsysteem B1", 0x85 to "Turbodruk B1", 0x90 to "NOx-adsorber B1", 0x98 to "NOx-/SCR-katalysator B1",
        0xB0 to "Roetfilter (DPF) B1")

    private fun s16(v: Int, signed: Boolean) = if (signed && (v and 0x8000) != 0) v - 0x10000 else v

    fun read(elm: Elm): JSONArray {
        val mids = ArrayList<Int>()
        var base = 0
        while (base <= 0xE0) {
            val d = elm.query("06%02X".format(base))
            if (d == null || d[0] != 0x46 || d.size < 6) break
            val bits = (d[2].toLong() shl 24) or (d[3].toLong() shl 16) or (d[4].toLong() shl 8) or d[5].toLong()
            for (i in 0 until 32) if ((bits shr (31 - i)) and 1L == 1L) mids.add(base + 1 + i)
            if (bits and 1L == 0L) break
            base += 0x20
        }
        val res = JSONArray()
        for (mid in mids.filter { it % 0x20 != 0 }) {
            val d = elm.query("06%02X".format(mid)) ?: continue
            if (d[0] != 0x46) continue
            var i = 1
            while (i + 8 < d.size) {
                val m = d[i]; val tid = d[i + 1]; val uas = d[i + 2]; val sg = uas >= 0x80
                val v = s16((d[i + 3] shl 8) or d[i + 4], sg)
                val lo = s16((d[i + 5] shl 8) or d[i + 6], sg)
                val hi = s16((d[i + 7] shl 8) or d[i + 8], sg)
                res.put(JSONObject().put("mid", "%02X".format(m)).put("naam", MID_NL[m] ?: "MID %02X (fabrikant/onbekend)".format(m))
                    .put("tid", "%02X".format(tid)).put("uas", "%02X".format(uas)).put("waarde", v).put("min", lo).put("max", hi)
                    .put("ok", v in lo..hi).put("uitgevoerd", !(v == 0 && lo == 0 && hi == 0)))
                i += 9
            }
        }
        return res
    }
}

/** Kengetallen per rit voor de trendbewaking (port van obd/health.py; beoordeling gebeurt in de interface). */
class TripHealth(saved: JSONObject? = null) {
    companion object {
        // turbo_stabiel: mediaan van het tekort op stabiele vollastmomenten (vervangt 'turbo', dat door
        // drukdips na het schakelen per rit tussen 9 en 32 kPa schommelde; ritten 8-14 nov).
        // 'koeling' (koelv. - buiten) vervangen door 'koelreserve': de thermostaat regelt de temperatuur,
        // dus alleen tijd boven 100 °C bij zware last zegt iets over de koelcapaciteit.
        val MIN_N = mapOf("koelreserve" to 120, "turbo_stabiel" to 15, "rail" to 30, "intercooler" to 60, "laadspanning" to 60,
            "rustspanning" to 1, "startdip" to 1, "opwarmtijd" to 1, "stationair_maf" to 30, "luchtvulling" to 10, "olie_koel" to 60)
        private const val VD_L = 2.143  // cilinderinhoud OM651
    }
    private val ve = ArrayList<Double>()
    private var heavy = 0; private var heavyHot = 0
    private val sum = HashMap<String, Double>()
    private val n = HashMap<String, Int>()
    val single = HashMap<String, Double>()
    var coldStart: Long = 0
    private val turbo = ArrayList<Double>()
    private var prevTurboT = 0L; private var prevCmd = 0.0

    init {
        saved?.let { s ->
            s.optJSONObject("sum")?.let { o -> o.keys().forEach { sum[it] = o.getDouble(it) } }
            s.optJSONObject("n")?.let { o -> o.keys().forEach { n[it] = o.getInt(it) } }
            s.optJSONObject("single")?.let { o -> o.keys().forEach { single[it] = o.getDouble(it) } }
            s.optJSONArray("turbo")?.let { a -> for (i in 0 until a.length()) turbo.add(a.getDouble(i)) }
            s.optJSONArray("ve")?.let { a -> for (i in 0 until a.length()) ve.add(a.getDouble(i)) }
            heavy = s.optInt("heavy", 0); heavyHot = s.optInt("heavyHot", 0)
            coldStart = s.optLong("coldStart", 0)
        }
    }

    fun toJson(): JSONObject = JSONObject().put("sum", JSONObject(sum as Map<*, *>)).put("n", JSONObject(n as Map<*, *>))
        .put("single", JSONObject(single as Map<*, *>)).put("coldStart", coldStart).put("turbo", org.json.JSONArray(turbo))
        .put("ve", org.json.JSONArray(ve)).put("heavy", heavy).put("heavyHot", heavyHot)

    private fun add(k: String, x: Double) { sum[k] = (sum[k] ?: 0.0) + x; n[k] = (n[k] ?: 0) + 1 }

    fun sample(v: Map<String, Any>, now: Long) {
        fun d(k: String) = (v[k] as? Double)
        val rpm = d("rpm") ?: return
        val load = d("load"); val spd = d("speed") ?: 0.0; val cool = d("coolant"); val amb = d("ambient"); val rt = d("runtime") ?: 0.0
        if (coldStart > 0 && cool != null && cool >= 80 && !single.containsKey("opwarmtijd"))
            single["opwarmtijd"] = ((now - coldStart) / 6000.0).roundToInt() / 10.0
        val hot = load != null && load >= 60
        // 4. koelreserve: seconden boven 100 °C koelvloeistof bij zware last (>=70 %), na opwarmen
        if (load != null && load >= 70 && cool != null && rt > 600) { heavy++; if (cool > 100) heavyHot++ }
        // 2. luchtvulling bij vollast (EGR dicht): gemeten luchtmassa / theoretische (p/RT x Vd x rpm/120)
        val maf = d("maf"); val ba = d("boost_act"); val iat = d("iat")
        if (load != null && load >= 85 && rpm >= 1800 && maf != null && ba != null && iat != null && v["boost_begrensd"] != true && ve.size < 5000) {
            val rho = ba * 1000 / (287.05 * (iat + 273.15))
            ve.add(maf / (rho * VD_L / 1000 * rpm / 120 * 1000))
        }
        // 3. olie - koelvloeistof bij last, alleen als beide op temperatuur zijn
        val oil = d("oil")
        if (hot && oil != null && cool != null && cool >= 85 && oil >= 85 && rt > 1200) add("olie_koel", oil - cool)
        val cmd = d("boost_cmd") ?: 0.0
        val turboOk = hot && rpm >= 1800 && cmd >= 140 && d("boost_act") != null && v["boost_begrensd"] != true
        if (turboOk) {
            // alleen stabiel: vorige meting (<=2 s geleden) ook vollast en gevraagde druk <10 kPa veranderd
            if (now - prevTurboT <= 2000 && abs(cmd - prevCmd) < 10 && turbo.size < 5000) turbo.add(cmd - d("boost_act")!!)
            prevTurboT = now; prevCmd = cmd
        } else prevTurboT = 0
        if ((d("rail_cmd") ?: 0.0) >= 600 && d("rail_dev") != null) add("rail", d("rail_dev")!!)
        if (hot && spd >= 60 && d("iat") != null && amb != null) add("intercooler", d("iat")!! - amb)
        if (rpm >= 1200 && rt >= 600 && d("voltage") != null) add("laadspanning", d("voltage")!!)
        if (cool != null && cool >= 80 && rpm < 850 && spd == 0.0 && d("maf") != null) add("stationair_maf", d("maf")!!)
    }

    fun features(): JSONObject {
        val o = JSONObject()
        fun median(l: List<Double>) = l.sorted()[l.size / 2]
        for ((k, minN) in MIN_N) {
            when {
                k == "turbo_stabiel" -> if (turbo.size >= minN) o.put(k, (median(turbo) * 100).roundToInt() / 100.0)
                k == "luchtvulling" -> if (ve.size >= minN) o.put(k, (median(ve) * 1000).roundToInt() / 1000.0)
                k == "koelreserve" -> if (heavy >= minN) o.put(k, (heavyHot * 60.0 / heavy * 10).roundToInt() / 10.0)  // min per uur zware last
                single.containsKey(k) -> o.put(k, single[k])
                (n[k] ?: 0) >= minN -> o.put(k, ((sum[k]!! / n[k]!!) * 100).roundToInt() / 100.0)
            }
        }
        return o
    }

    fun progress(): JSONObject {
        val o = JSONObject()
        for ((k, minN) in MIN_N) {
            val cnt = when (k) { "turbo_stabiel" -> turbo.size; "luchtvulling" -> ve.size; "koelreserve" -> heavy; else -> n[k] ?: 0 }
            o.put(k, if (single.containsKey(k)) 1.0 else min(1.0, cnt.toDouble() / minN))
        }
        return o
    }
}

@Suppress("unused")
private fun absDiff(a: Double, b: Double) = abs(a - b)
