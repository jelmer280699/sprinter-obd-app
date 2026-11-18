package nl.sprinterobd.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Meetkern (draait in ObdService, ook met scherm uit): verbinding, poller, alarmen, automatische
 * ritdetectie, verbruik, gezondheid, DID-onderzoek en logging. Port van dashboard.py / app.js.
 * ALLEEN LEZEN: alle verkeer gaat via Elm (blokkeert alles behalve AT/lees-modes).
 */
class Core(private val ctx: Context, private val store: Store, private val ui: Listener) {

    interface Listener {
        fun onAlarm(id: Int, title: String, text: String)
        fun onEvent(state: String, msg: String)
        fun onIdleTooLong()
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val VEEPEAK = "66:1E:87:05:07:FA"
        private const val TRIP_END_MS = 5 * 60_000L          // motor 5 min uit -> rit klaar
        private const val IDLE_STOP_MS = 15 * 60_000L        // 15 min geen ECU en geen rit -> dienst stopt
        private val FAST = listOf("0C", "0D", "04"); private val MEDIUM = listOf("70", "6D", "66", "24")
        private val SLOW = listOf("05", "5C", "46", "42"); private val SLOW_SINGLE = listOf("68", "49", "11", "33", "4A", "6C")
        private val VERY_SLOW = listOf("01", "1F")
        private val EXTRA = listOf("21", "31", "30")              // afstanden/opwarmcycli: elke minuut
        private val ONZEKER = listOf("85", "88", "92")            // [niet geverifieerd], alleen als aangezet
        val LOG_KEYS = listOf("rpm", "speed", "load", "coolant", "oil", "iat", "ambient", "voltage", "boost_cmd", "boost_act", "boost_dev",
            "rail_cmd", "rail_act", "rail_dev", "maf", "lambda", "fuel_lph", "l100", "throttle", "pedal", "baro", "runtime",
            "pedal_pct", "pedal_e", "throttle_cmd", "orion_in_v", "orion_in_a", "orion_out_v", "orion_out_a", "orion_state",
            "shunt_soc", "shunt_v", "shunt_a", "shunt_starter_v", "mppt_w", "mppt_a", "mppt_yield_wh")
        val REF = listOf("rpm", "speed", "load", "coolant", "oil", "iat", "ambient", "voltage", "boost_act", "boost_cmd",
            "rail_act", "rail_cmd", "maf", "lambda", "throttle", "pedal", "baro", "runtime")
        private const val DIESEL_AFR = 14.5; private const val DIESEL_KG_L = 0.835
        val DEFAULT_CFG = """{"did_onderzoek":true,"alarms":{"coolant":{"oranje":106,"rood":112},"coolant_koud":{"na_s":1200,"onder":70},
            "oil":{"oranje":120,"rood":130},"iat":{"oranje":60,"rood":75},"boost_dev":{"oranje":20,"rood":40,"duur_s":5},
            "rail_dev":{"oranje":10,"rood":20,"duur_s":2},"voltage":{"laag_oranje":12.3,"laag_rood":12.0,"duur_s":60,"hoog_oranje":14.9,"hoog_rood":15.2},
            "load":{"oranje":85,"duur_s":60},"rpm":{"oranje":4000,"rood":4500}}}"""
    }

    // ---------------- toestand ----------------
    val values = ConcurrentHashMap<String, Any>()
    val updated = ConcurrentHashMap<String, Long>()
    @Volatile var alarms: Map<String, String> = emptyMap()
    @Volatile var status = "niet verbonden"; @Volatile var error = ""
    var info = JSONObject(); @Volatile var multi: Boolean? = null
    var sessies = 0; var onderbrekingen = 0
    private val history = ArrayDeque<JSONObject>()
    var dtcAlert = JSONArray()
    var cfg: JSONObject = loadCfg()
    var tank: JSONObject = JSONObject(store.get("tank", """{"factor":1,"sinds_tank_raw_l":0,"tankbeurten":[]}"""))

    // rit
    @Volatile var tripActive = false
    var trip = JSONObject(); var health = TripHealth()
    private var lastEngine = 0L; private var engineOn = 0L
    /** (tijd, spanning, toerental) van de laatste 90 s, voor rustspanning en startdip. */
    private val volts = ArrayDeque<Triple<Long, Double, Double>>()
    private val kal: JSONObject = try { JSONObject(store.get("kalibratie", "{}")) } catch (_: Exception) { JSONObject() }
    private var pedMin = kal.optDouble("pedaal_min", 5.5)
    private var pedMax = kal.optDouble("pedaal_max", 67.5)
    var logfile = ""; private var resFile = ""
    private val csv = StringBuilder(); private val res = StringBuilder()

    @Volatile private var want = false
    private var worker: Thread? = null
    private var socket: BluetoothSocket? = null
    @Volatile var elm: Elm? = null
    private var lastEcu = System.currentTimeMillis()

    private fun loadCfg(): JSONObject {
        val d = JSONObject(DEFAULT_CFG)
        try {
            val s = JSONObject(store.get("cfg", "{}"))
            if (s.has("did_onderzoek")) d.put("did_onderzoek", s.getBoolean("did_onderzoek"))
            if (s.has("dev")) d.put("dev", s.getString("dev"))
            if (s.has("onzeker")) d.put("onzeker", s.getBoolean("onzeker"))
            if (s.has("orion_mac")) d.put("orion_mac", s.getString("orion_mac"))
            s.optJSONObject("alarms")?.let { a -> val da = d.getJSONObject("alarms"); a.keys().forEach { da.put(it, a.get(it)) } }
            // Oude standaard accugrenzen (12,8/12,4 zonder duur) vervangen door de nieuwe; zelf aangepaste blijven staan.
            val v = d.getJSONObject("alarms").getJSONObject("voltage")
            if (v.optDouble("laag_oranje") == 12.8 && v.optDouble("laag_rood") == 12.4 && !v.has("duur_s"))
                d.getJSONObject("alarms").put("voltage", JSONObject(DEFAULT_CFG).getJSONObject("alarms").getJSONObject("voltage"))
            val b = d.getJSONObject("alarms").getJSONObject("boost_dev")
            if (b.optDouble("duur_s") == 3.0) b.put("duur_s", 5)   // oude standaard -> nieuwe
        } catch (_: Exception) {}
        return d
    }

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val tsSec = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
    fun ts(t: Long = System.currentTimeMillis()): String = tsFmt.format(Date(t))
    private fun d(k: String) = values[k] as? Double

    // ---------------- verbinding ----------------
    // ---------------- Victron (Orion XS, SmartShunt, MPPT) via Bluetooth LE, alleen luisteren ----------------
    // Sleutels in eigen SharedPreferences: niet via getData, niet in back-ups, niet in de repository.
    private val geheim = ctx.getSharedPreferences("geheim", Context.MODE_PRIVATE)
    private val victron = Victron(ctx, { mac, m -> victronUpdate(mac, m) }, { mac, msg -> vStatus(mac, msg) })
    private var lastOrion = 0L
    private val vInfo = ConcurrentHashMap<String, JSONObject>()        // MAC -> {type, status, laatst}
    private val mppts = ConcurrentHashMap<String, Map<String, Any>>()  // MAC -> laatste MPPT-waarden (voor optellen)

    private fun keys(): JSONObject {
        val k = try { JSONObject(geheim.getString("victron_keys", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        // Eenmalig: oude Orion-instelling (build 17) overnemen
        val oldMac = Victron.normMac(cfg.optString("orion_mac", "")); val oldKey = geheim.getString("orion_key", "") ?: ""
        if (oldMac != null && oldKey.isNotEmpty() && !k.has(oldMac)) {
            k.put(oldMac, oldKey); geheim.edit().putString("victron_keys", k.toString()).remove("orion_key").apply()
        }
        return k
    }

    private fun vStatus(mac: String, msg: String) {
        if (mac.isEmpty()) { values["victron_status"] = msg; ui.onEvent("victron", msg); return }
        vInfo.getOrPut(mac) { JSONObject() }.put("status", msg)
    }

    fun startVictron() {
        val k = keys(); val devs = HashMap<String, ByteArray>()
        for (mac in k.keys()) Victron.hexKey(k.getString(mac))?.let { devs[mac] = it }
        for (mac in devs.keys) vInfo.getOrPut(mac) { JSONObject().put("status", "luisteren…") }
        values["victron_status"] = if (devs.isEmpty()) "geen apparaten" else if (victron.start(devs)) "luisteren naar ${devs.size} apparaat/apparaten" else "kon niet starten"
    }

    @Synchronized
    private fun victronUpdate(mac: String, m: Map<String, Any>) {
        val now = System.currentTimeMillis()
        val type = m["type"] as? String ?: return
        vInfo.getOrPut(mac) { JSONObject() }.put("type", type).put("status", "ontvangen").put("laatst", now)
        fun put(k: String, v: Any) { values[k] = v; updated[k] = now }
        when (type) {
            "orion" -> {
                for ((k, v) in m) if (k != "type") put(if (k == "ruw") "orion_raw" else "orion_$k", v)
                // Geladen Ah naar de woonaccu tijdens de rit
                val a = m["out_a"] as? Double
                if (tripActive && a != null && lastOrion > 0) {
                    val dt = min((now - lastOrion) / 1000.0, 10.0)
                    trip.put("orion_ah", trip.optDouble("orion_ah", 0.0) + a * dt / 3600)
                }
                lastOrion = now
            }
            "shunt" -> for ((k, v) in m) if (k != "type") put(if (k == "ruw") "shunt_raw" else "shunt_$k", v)
            "mppt" -> {
                mppts[mac] = m
                // Meerdere laadregelaars: vermogen, stroom en dagopbrengst optellen
                val all = mppts.values
                put("mppt_w", all.sumOf { (it["w"] as? Double) ?: 0.0 })
                put("mppt_a", all.sumOf { (it["a"] as? Double) ?: 0.0 })
                put("mppt_yield_wh", all.sumOf { (it["yield_wh"] as? Double) ?: 0.0 })
                (m["v"] as? Double)?.let { put("mppt_v", it) }
                (m["state"] as? String)?.let { put("mppt_state", it) }
                (m["load_a"] as? Double)?.let { put("mppt_load_a", it) }
                (m["ruw"] as? String)?.let { put("mppt_raw", it) }
                put("mppt_aantal", all.size.toDouble())
            }
        }
    }

    /** Apparaat toevoegen/bijwerken (sleutel leeg = bestaande behouden) of verwijderen. */
    fun setVictron(json: String) {
        val s = JSONObject(json)
        val mac = Victron.normMac(s.optString("mac", "")) ?: throw IllegalArgumentException("MAC-adres onjuist (12 tekens hex)")
        val k = keys()
        if (s.optBoolean("verwijderen")) { k.remove(mac); vInfo.remove(mac); mppts.remove(mac) }
        else {
            val keyTxt = s.optString("key", "").trim()
            if (keyTxt.isNotEmpty()) { Victron.hexKey(keyTxt) ?: throw IllegalArgumentException("Sleutel onjuist (32 tekens hex)"); k.put(mac, keyTxt) }
            else if (!k.has(mac)) throw IllegalArgumentException("Sleutel ontbreekt")
        }
        geheim.edit().putString("victron_keys", k.toString()).apply()
        startVictron()
    }

    fun start() {
        if (want) return
        want = true
        startVictron()
        worker = Thread({ runLoop() }, "obd-core").apply { isDaemon = true; start() }
    }

    fun stop() {
        want = false
        victron.stop()
        closeSocket()
        endTrip("gestopt")
        flushLogs()
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; elm = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(): Elm {
        val addr = cfg.optString("dev", "").ifBlank { VEEPEAK }
        val bm = ctx.getSystemService(BluetoothManager::class.java)
        val dev = bm.adapter.getRemoteDevice(addr)
        var last: Exception? = null
        for (i in 1..3) {
            if (!want) throw IOException("gestopt")
            var s: BluetoothSocket? = null
            try {
                ui.onEvent("poging", "beveiligd SPP poging $i")
                s = dev.createRfcommSocketToServiceRecord(SPP)
                s.connect()
                socket = s
                return Elm(s.inputStream, s.outputStream) { if (debugLeft-- > 0) ui.onEvent("debug", it) }
            } catch (e: Exception) {
                last = e
                try { s?.close() } catch (_: Exception) {}
                ui.onEvent("poging", "mislukt: ${e.message}")
                Thread.sleep(2000)
            }
        }
        throw last ?: IOException("Verbinden mislukt")
    }
    @Volatile private var debugLeft = 0

    private fun runLoop() {
        var first = true
        while (want) {
            try {
                status = "verbinden"; error = ""
                debugLeft = 25
                val e = connect()
                elm = e
                ui.onEvent("verbonden", "")
                val (elmV, proto) = e.init(skipAtz = !first)
                info = JSONObject().put("elm", elmV).put("proto", proto)
                if (first) try {
                    e.query("0902", 5000)?.let { vin -> info.put("vin", String(vin.drop(3).filter { it in 33..126 }.map { it.toByte() }.toByteArray())) }
                } catch (_: Exception) {}
                first = false
                status = "verbonden"; sessies++
                lastEcu = System.currentTimeMillis()
                pollLoop(e)
            } catch (ex: Exception) {
                if (status == "verbonden") onderbrekingen++
                status = "niet verbonden"; error = ex.message ?: ex.javaClass.simpleName
                ui.onEvent("fout", error)
            }
            closeSocket()
            flushLogs()
            val now = System.currentTimeMillis()
            if (tripActive && now - lastEngine > TRIP_END_MS) endTrip("geen contact met de auto")
            if (!tripActive && now - lastEcu > IDLE_STOP_MS) { want = false; ui.onIdleTooLong(); break }
            if (want) Thread.sleep(3000)
        }
        status = "niet verbonden"
    }

    // ---------------- poller ----------------
    private fun q(e: Elm, pids: List<String>): Map<Int, IntArray> {
        if (pids.size > 1 && multi != false) {
            val got = Pids.split(e.query("01" + pids.joinToString(""), 2500))
            if (multi == null && got.isNotEmpty()) multi = pids.all { it.toInt(16) in got }
            if (multi == true) return got
        }
        val out = HashMap<Int, IntArray>()
        for (p in pids) out.putAll(Pids.split(e.query("01$p", 2500)))
        return out
    }

    private var researchI = 0
    private val dids: List<String> by lazy {
        try {
            val txt = ctx.assets.open("www/dids.js").bufferedReader().readText()
            Regex("\"([0-9A-F]{4})\"").findAll(txt).map { it.groupValues[1] }.toList()
        } catch (_: Exception) { emptyList() }
    }

    /** Aantal keer dat een gemist/afwijkend antwoord is opgevangen zonder opnieuw te verbinden. */
    @Volatile var hersteld = 0

    private fun pollLoop(e: Elm) {
        var cycle = 0; var nodata = 0; var errors = 0
        var tSlow = 0L; var tVslow = 0L; var tExtra = 0L; var tDtc = System.currentTimeMillis() - 540_000
        while (want) {
            try {
                val got = HashMap(q(e, FAST))
                nodata = if (got.isEmpty()) nodata + 1 else 0
                if (nodata >= 5) throw IOException("ECU antwoordt niet meer (contact uit?)")
                lastEcu = System.currentTimeMillis()
                // 1. startaccu: met motor uit/startend de accuspanning elke cyclus meten (anders 1x per 5 s),
                // zodat de spanningsdip tijdens het starten gevangen wordt.
                if ((d("rpm") ?: 0.0) < 400) got.putAll(q(e, listOf("42")))
                if (cycle % 2 == 0) got.putAll(q(e, MEDIUM))
                val now = System.currentTimeMillis()
                if (now - tSlow > 5000) { got.putAll(q(e, SLOW)); got.putAll(q(e, SLOW_SINGLE)); tSlow = now }
                if (now - tVslow > 10000) {
                    got.putAll(q(e, VERY_SLOW))
                    if (cfg.optBoolean("onzeker", false)) for (p in ONZEKER) try { got.putAll(q(e, listOf(p))) } catch (_: IOException) {}
                    tVslow = now
                }
                if (now - tExtra > 60_000) { got.putAll(q(e, EXTRA)); tExtra = now }
                val nv = HashMap<String, Any>()
                Pids.decode(got, nv)
                boostFallback(e, nv)
                update(nv)
                if (now - tDtc > 600_000) { tDtc = now; autoDtc(e) }
                if (tripActive && !trip.optBoolean("_mode06") && (d("runtime") ?: 0.0) >= 1200 && (d("rpm") ?: 0.0) > 400) {
                    trip.put("_mode06", true)
                    try { storeMode06(Mode06.read(e)) } catch (_: Exception) {}
                }
                if (cfg.optBoolean("did_onderzoek", true) && (d("rpm") ?: 0.0) > 0) research(e)
                errors = 0
            } catch (ex: IOException) {
                // Eén gemist of afwijkend antwoord (time-out, 'STOPPED', 'CAN ERROR') is geen reden om de hele
                // Bluetooth-verbinding opnieuw op te bouwen (kostte ~10 s per keer, ~25 % gaten; ritten 8-9 nov).
                // Alleen opnieuw verbinden als de socket echt weg is of het 3x achter elkaar misgaat.
                if (!e.alive || ex.message?.startsWith("ECU antwoordt niet") == true) throw ex
                errors++
                if (errors >= 3) throw ex
                hersteld++
                Thread.sleep(250)
            }
            cycle++
        }
    }

    /**
     * PID 70 (turbodruk werkelijk) kapt af op 255 kPa (ritten 8-9 nov: 316 van 406 metingen >= 250 waren exact 255,0).
     * Dan de Mercedes-DID 202F lezen (geverifieerd: 0,419 x ruw + 7,6 kPa, r = 0,994, n = 263).
     */
    private fun boostFallback(e: Elm, nv: HashMap<String, Any>) {
        val act = nv["boost_act"] as? Double ?: return
        nv["boost_begrensd"] = false
        if (act < 254.9) { nv["boost_bron"] = "PID 70"; return }
        nv["boost_bron"] = "DID 202F"
        val d = try { e.readDid(0x202F) } catch (_: IOException) { null }
        if (d == null) { nv["boost_begrensd"] = true; return }   // onbekend boven 255: geen tekort berekenen
        var raw = 0L; for (b in d) raw = raw * 256 + b
        nv["boost_act"] = ((0.41901 * raw + 7.588) * 10).roundToInt() / 10.0
    }

    private fun research(e: Elm) {
        if (dids.isEmpty()) return
        val h = dids[researchI++ % dids.size]
        val data = e.readDid(h.toInt(16)) ?: return
        if (resFile.isEmpty()) {
            resFile = "did_onderzoek_${fileFmt.format(Date())}.csv"
            res.append("t;did;ruw;").append(REF.joinToString(";")).append('\n')
        }
        var raw = 0L; for (b in data) raw = raw * 256 + b
        res.append("%.2f".format(Locale.ROOT, System.currentTimeMillis() / 1000.0)).append(';').append(h).append(';').append(raw).append(';')
            .append(REF.joinToString(";") { k -> values[k]?.let { fmtRaw(it) } ?: "" }).append('\n')
    }
    private fun fmtRaw(x: Any) = if (x is Double) (if (x == Math.floor(x)) x.toLong().toString() else x.toString()) else x.toString()

    private fun autoDtc(e: Elm) {
        val found = ArrayList<Pair<String, String>>()
        for ((mode, resp, soort) in listOf(Triple("07", 0x47, "pending"), Triple("03", 0x43, "opgeslagen"))) {
            try { Dtc.decode(e.query(mode), resp).forEach { found.add(it to soort) } } catch (_: Exception) {}
        }
        val known = HashSet<String>()
        for (i in 0 until dtcAlert.length()) dtcAlert.getJSONObject(i).let { known.add(it.getString("code") + "|" + it.getString("soort")) }
        val next = JSONArray()
        for ((code, soort) in found) {
            val ex = Dtc.explain(code)
            val o = JSONObject().put("code", code).put("soort", soort).put("omschrijving", ex.getString("omschrijving")).put("tip", ex.getString("tip")).put("tijd", ts())
            if ("$code|$soort" !in known) {
                logEvent("foutcode", "$soort $code: ${ex.getString("omschrijving")}")
                ui.onAlarm(2000 + code.hashCode() % 1000, "Nieuwe $soort foutcode $code", ex.getString("omschrijving"))
            }
            next.put(o)
        }
        dtcAlert = next
    }

    // ---------------- alarmen ----------------
    private val since = HashMap<String, Long>()
    private val hits = HashMap<String, Int>()
    private val lastSeen = HashMap<String, Long>()

    /**
     * Voorwaarde moet `needS` seconden aanhouden ÉN in minstens 3 nieuwe metingen van `src` gelden.
     * Alleen de klok tellen gaf vals rood: na een korte verbindingshapering bleef een oude meetwaarde staan
     * en telde de tijd door (raildruk 15 nov 10:37, één meting van 27 %).
     */
    private fun dur(key: String, cond: Boolean, needS: Double, src: String): Boolean {
        val now = System.currentTimeMillis()
        if (!cond) { since.remove(key); hits.remove(key); lastSeen.remove(key); return false }
        val s = since.getOrPut(key) { now }
        val upd = updated[src] ?: 0L
        if (lastSeen[key] != upd) { lastSeen[key] = upd; hits[key] = (hits[key] ?: 0) + 1 }
        return now - s >= needS * 1000 && (hits[key] ?: 0) >= 3
    }
    private fun clearDur(vararg keys: String) { for (k in keys) { since.remove(k); hits.remove(k); lastSeen.remove(k) } }

    private fun evaluateAlarms(): Map<String, String> {
        val c = cfg.getJSONObject("alarms"); val a = HashMap<String, String>()
        fun thr(k: String) { val v = d(k) ?: return; val t = c.getJSONObject(k)
            a[k] = if (v >= t.getDouble("rood")) "rood" else if (v >= t.getDouble("oranje")) "oranje" else "ok" }
        thr("coolant"); thr("oil"); thr("iat"); thr("rpm")
        val rpm = d("rpm") ?: 0.0
        if (rpm > 500) { if (engineOn == 0L) engineOn = System.currentTimeMillis() } else engineOn = 0
        val runningMs = if (engineOn == 0L) 0L else System.currentTimeMillis() - engineOn
        val k = c.getJSONObject("coolant_koud"); val cool = d("coolant")
        if (cool != null && (d("runtime") ?: 0.0) > k.getDouble("na_s") && (d("speed") ?: 0.0) > 0 && cool < k.getDouble("onder")) {
            a["coolant"] = "oranje"; a["coolant_reden"] = "Te koud na lang rijden: thermostaat?"
        }
        // Turbo- en raildrukafwijking pas beoordelen als de motor ≥10 s draait: met contact aan en motor uit
        // vraagt de ECU al raildruk terwijl de pomp stilstaat (gaf vals rood alarm, gemeld 2026-11-08).
        for (key in listOf("boost_dev", "rail_dev")) {
            val v = d(key)
            // Turbotekort pas vanaf 1800 rpm: daaronder loopt de turbo bij optrekken normaal achter (rit 8 nov).
            val lowRpm = key == "boost_dev" && rpm < 1800
            if (v == null || runningMs < 10_000 || lowRpm) { clearDur(key + "_r", key + "_o"); continue }
            val t = c.getJSONObject(key); val du = t.getDouble("duur_s"); val src = if (key == "boost_dev") "boost_act" else "rail_act"
            a[key] = if (dur(key + "_r", v >= t.getDouble("rood"), du, src)) "rood" else if (dur(key + "_o", v >= t.getDouble("oranje"), du, src)) "oranje" else "ok"
        }
        // Accuspanning pas 30 s na de start beoordelen (Mercedes laadt pas na enkele seconden; getest 2026-11-08).
        // Te laag pas na 'duur_s' aanhoudend: het Mercedes-laadbeheer laat de spanning tijdens het rijden
        // normaal zakken tot ~12,45 V en laadt in pieken tot 14,6 V (ritten 8-9 nov).
        val u = d("voltage"); val t = c.getJSONObject("voltage"); val vdur = t.optDouble("duur_s", 60.0)
        if (u != null && rpm > 500 && runningMs > 30_000) {
            val hoogR = u >= t.getDouble("hoog_rood"); val hoogO = u >= t.getDouble("hoog_oranje")
            val laagR = dur("u_laag_r", u <= t.getDouble("laag_rood"), vdur, "voltage")
            val laagO = dur("u_laag_o", u <= t.getDouble("laag_oranje"), vdur, "voltage")
            a["voltage"] = if (hoogR || laagR) "rood" else if (hoogO || laagO) "oranje" else "ok"
        } else if (u != null) { a["voltage"] = "ok"; clearDur("u_laag_r", "u_laag_o") }
        d("load")?.let { a["load"] = if (dur("load", it >= c.getJSONObject("load").getDouble("oranje"), c.getJSONObject("load").getDouble("duur_s"), "load")) "oranje" else "ok" }
        (values["mil"] as? Boolean)?.let { a["mil"] = if (it) "rood" else "ok" }
        return a
    }

    private val ALARM_NAMES = mapOf("coolant" to "Koelvloeistof", "oil" to "Motorolie", "iat" to "Inlaatlucht", "rpm" to "Toerental",
        "boost_dev" to "Turbodruk te laag", "rail_dev" to "Raildruk-afwijking", "voltage" to "Accuspanning", "mil" to "Motorlampje")
    private val ALARM_VAL = mapOf("coolant" to ("coolant" to "°C"), "oil" to ("oil" to "°C"), "iat" to ("iat" to "°C"), "rpm" to ("rpm" to "rpm"),
        "boost_dev" to ("boost_dev" to "kPa tekort"), "rail_dev" to ("rail_dev" to "%"), "voltage" to ("voltage" to "V"))

    // ---------------- update ----------------
    private var lastLog = 0L; private var lastSave = 0L

    @Synchronized
    private fun update(nv: Map<String, Any>) {
        val now = System.currentTimeMillis()
        for ((k, v) in nv) { values[k] = v; updated[k] = now }
        // Afwijkingen alleen met draaiende motor (anders zijn ze betekenisloos, zie evaluateAlarms)
        val running = (d("rpm") ?: 0.0) > 500
        val bc = d("boost_cmd"); val ba = d("boost_act")
        if (running && bc != null && ba != null && values["boost_begrensd"] != true) values["boost_dev"] = ((bc - ba) * 10).roundToInt() / 10.0 else values.remove("boost_dev")
        val rc = d("rail_cmd"); val ra = d("rail_act")
        // Raildruk-tekort: alleen te LAAG telt (filter/pomp). Te hoog bij gas los is normaal (druk zakt langzamer
        // dan gevraagd) en gaf valse alarmen (rit 8 nov 19:22-19:31).
        if (running && rc != null && rc > 200 && ra != null) values["rail_dev"] = (max(0.0, rc - ra) / rc * 1000).roundToInt() / 10.0 else values.remove("rail_dev")
        val maf = d("maf"); val lam = d("lambda"); val load = d("load")
        if (maf != null && lam != null && load != null) {
            // λ ≤ 1,05 bestaat niet bij een diesel: sonde koud/niet actief -> rekenen met 2,0 (bovengrens; tank-kalibratie corrigeert)
            val l = if (lam <= 1.05) 2.0 else min(lam, 2.0)
            val raw = if (load < 1) 0.0 else maf / (DIESEL_AFR * l) / DIESEL_KG_L * 3.6
            values["fuel_raw_lph"] = raw
            val f = (raw * tank.optDouble("factor", 1.0) * 100).roundToInt() / 100.0
            values["fuel_lph"] = f
            val spd = d("speed") ?: 0.0
            if (spd >= 5) values["l100"] = (f / spd * 1000).roundToInt() / 10.0 else values.remove("l100")
        }
        // Gaspedaal 0-100 %: de sensor loopt nooit van 0 tot 100 (gemeten 5,5-67,5 %); grenzen leren we zelf.
        d("pedal")?.let { p ->
            if (p > 2 && p < pedMin) pedMin = p
            if (p > pedMax) pedMax = p
            values["pedal_pct"] = (max(0.0, min(100.0, (p - pedMin) / max(1.0, pedMax - pedMin) * 100)) * 10).roundToInt() / 10.0
        }
        // Spanning rond de start bijhouden (voor rustspanning en startdip)
        if ("voltage" in nv) d("voltage")?.let { u ->
            volts.addLast(Triple(now, u, d("rpm") ?: 0.0))
            while (volts.isNotEmpty() && now - volts.first().first > 90_000) volts.removeFirst()
        }
        val prev = alarms
        alarms = evaluateAlarms()
        for ((k, lvl) in alarms) if (lvl == "rood" && prev[k] != "rood" && k in ALARM_NAMES) {
            val vv = ALARM_VAL[k]?.let { (key, unit) -> d(key)?.let { " ${fmtRaw(it)} $unit" } } ?: ""
            ui.onAlarm(1000 + ALARM_NAMES.keys.indexOf(k), "ALARM: ${ALARM_NAMES[k]}", "Nu$vv")
        }

        // automatische ritdetectie
        val rpm = d("rpm")
        if (rpm != null && rpm > 400) { lastEngine = now; if (!tripActive) startTrip(now) }
        if (tripActive && now - lastEngine > TRIP_END_MS) endTrip("motor 5 min uit")

        if (tripActive) accumulateTrip(now)

        if (now - lastLog >= 1000) {
            lastLog = now
            val h = JSONObject().put("t", now); for (k in LOG_KEYS) values[k]?.let { h.put(k, it) }
            synchronized(history) { history.addLast(h); while (history.size > 900) history.removeFirst() }
            if (tripActive && ((rpm ?: 0.0) > 0 || now - lastEngine < 60_000)) {
                val worst = alarms.filterKeys { !it.endsWith("reden") }.values.maxByOrNull { when (it) { "rood" -> 2; "oranje" -> 1; else -> 0 } } ?: "ok"
                csv.append(tsSec.format(Date(now))).append(';')
                    .append(LOG_KEYS.joinToString(";") { k -> values[k]?.let { fmtRaw(it).replace('.', ',') } ?: "" })
                    .append(';').append(worst).append('\n')
                health.sample(values, now)
            }
            if (csv.length > 4000 || res.length > 4000) flushLogs()
        }
        if (now - lastSave > 30_000) {
            lastSave = now
            flushLogs()
            store.set("kalibratie", kal.put("pedaal_min", pedMin).put("pedaal_max", pedMax).toString())
            if (tripActive) { saveTrip(); store.set("trip_live", JSONObject().put("trip", trip).put("health", health.toJson()).put("lastEngine", lastEngine).toString()) }
            store.set("tank", tank.toString())
        }
    }

    private fun accumulateTrip(now: Long) {
        val last = trip.optLong("_last", 0)
        if (last > 0) {
            val dt = min((now - last) / 1000.0, 5.0); val spd = d("speed") ?: 0.0
            trip.put("afstand_km", trip.getDouble("afstand_km") + spd * dt / 3600)
            if (spd > 0) trip.put("rijtijd_s", trip.getDouble("rijtijd_s") + dt)
            else if ((d("rpm") ?: 0.0) > 300) trip.put("stationair_s", trip.getDouble("stationair_s") + dt)
            d("fuel_raw_lph")?.let { raw ->
                val rawL = raw * dt / 3600
                trip.put("brandstof_l", trip.getDouble("brandstof_l") + rawL * tank.optDouble("factor", 1.0))
                tank.put("sinds_tank_raw_l", tank.optDouble("sinds_tank_raw_l", 0.0) + rawL)
            }
        }
        trip.put("_last", now)
        val mx = trip.getJSONObject("max")
        for (k in listOf("coolant", "oil", "iat", "speed", "rpm", "boost_act", "load")) d(k)?.let { mx.put(k, max(mx.optDouble(k, it), it)) }
        val prev = trip.getJSONObject("_prev"); val al = trip.getJSONObject("alarmen")
        for ((k, lvl) in alarms) {
            if ((lvl == "oranje" || lvl == "rood") && prev.optString(k) != lvl) al.put(lvl, al.getInt(lvl) + 1)
            prev.put(k, lvl)
        }
    }

    private fun newTripJson(now: Long) = JSONObject().put("start", now).put("afstand_km", 0.0).put("rijtijd_s", 0.0).put("stationair_s", 0.0)
        .put("brandstof_l", 0.0).put("max", JSONObject()).put("alarmen", JSONObject().put("oranje", 0).put("rood", 0))
        .put("_last", 0).put("_prev", JSONObject()).put("_mode06", false)

    private fun startTrip(now: Long) {
        // Doorgaan met de vorige rit als de motor minder dan 5 min uit stond (bijv. app/dienst herstart)
        try {
            val live = JSONObject(store.get("trip_live", "{}"))
            if (live.has("trip") && now - live.optLong("lastEngine", 0) < TRIP_END_MS) {
                trip = live.getJSONObject("trip").put("_last", 0); health = TripHealth(live.optJSONObject("health"))
                tripActive = true; logEvent("rit", "Rit hervat"); return
            }
        } catch (_: Exception) {}
        trip = newTripJson(now); health = TripHealth()
        // 1. Startaccu. Rustspanning = mediaan van de metingen met motor stil 60 tot 5 s vóór de start;
        // startdip = laagste spanning in de 10 s rond het starten (tijdens het starten elke cyclus gemeten).
        val rest = volts.filter { it.third == 0.0 && now - it.first in 5_000L..60_000L }.map { it.second }.sorted()
        if (rest.size >= 3) health.single["rustspanning"] = rest[rest.size / 2]
        val dip = volts.filter { now - it.first <= 10_000 }.minOfOrNull { it.second }
        if (dip != null && volts.count { now - it.first <= 10_000 } >= 3) health.single["startdip"] = dip
        d("coolant")?.let { if (it <= 40) health.coldStart = now }
        logfile = "rit_${fileFmt.format(Date(now))}.csv"
        csv.append("tijd;").append(LOG_KEYS.joinToString(";")).append(";alarm\n")
        tripActive = true
        logEvent("rit", "Rit gestart")
    }

    @Synchronized
    fun endTrip(reden: String) {
        if (!tripActive) return
        saveTrip(); flushLogs()
        tripActive = false
        logEvent("rit", "Rit beëindigd (${reden}): %.1f km".format(Locale.ROOT, trip.getDouble("afstand_km")))
        store.set("trip_live", "{}")
        store.set("tank", tank.toString())
        // Automatisch exporteren naar Downloads/SprinterOBD: deze rit + alles wat eerder nog niet geëxporteerd was.
        val files = listOf(logfile, resFile).filter { it.isNotEmpty() }
        logfile = ""; resFile = ""
        Thread({
            val r = files.map { store.exportLog(it) } + store.exportPending()
            val ok = r.count { !it.startsWith("fout") }; val fout = r.filter { it.startsWith("fout") }
            logEvent("export", "$ok bestand(en) naar Downloads/SprinterOBD" + if (fout.isNotEmpty()) " · ${fout.size} mislukt: ${fout.first()}" else "")
        }, "export").start()
    }

    fun tripSummary(t: JSONObject): JSONObject {
        val km = t.getDouble("afstand_km"); val l = t.getDouble("brandstof_l")
        return JSONObject().put("start", ts(t.getLong("start"))).put("_start", t.getLong("start"))
            .put("afstand_km", (km * 10).roundToInt() / 10.0).put("rijtijd_s", t.getDouble("rijtijd_s").roundToInt())
            .put("stationair_s", t.getDouble("stationair_s").roundToInt()).put("brandstof_l", (l * 100).roundToInt() / 100.0)
            .put("l100", if (km > 1) (l / km * 1000).roundToInt() / 10.0 else JSONObject.NULL)
            .put("max", t.getJSONObject("max")).put("alarmen", t.getJSONObject("alarmen"))
            .put("orion_ah", if (t.has("orion_ah")) (t.getDouble("orion_ah") * 10).roundToInt() / 10.0 else JSONObject.NULL)
    }

    private fun upsert(name: String, start: Long, obj: JSONObject, keep: Int) {
        val arr = try { JSONArray(store.get(name, "[]")) } catch (_: Exception) { JSONArray() }
        val out = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optLong("_start") != start) out.put(it) }
        out.put(obj)
        val trimmed = JSONArray(); for (i in max(0, out.length() - keep) until out.length()) trimmed.put(out.get(i))
        store.set(name, trimmed.toString())
    }

    private fun saveTrip() {
        val km = trip.optDouble("afstand_km", 0.0); val stat = trip.optDouble("stationair_s", 0.0)
        if (km < 0.1 && stat < 60) return
        val start = trip.getLong("start")
        upsert("ritten", start, tripSummary(trip), 500)
        val f = health.features()
        if (f.length() > 0) upsert("health", start, JSONObject().put("_start", start).put("start", ts(start)).put("features", f), 500)
    }

    fun flushLogs() {
        if (csv.isNotEmpty() && logfile.isNotEmpty()) { store.appendLog(logfile, csv.toString()); csv.setLength(0) }
        if (res.isNotEmpty() && resFile.isNotEmpty()) { store.appendLog(resFile, res.toString()); res.setLength(0) }
    }

    fun logEvent(soort: String, tekst: String) {
        val arr = try { JSONArray(store.get("events", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("tijd", ts()).put("soort", soort).put("tekst", tekst))
        val out = JSONArray(); for (i in max(0, arr.length() - 300) until arr.length()) out.put(arr.get(i))
        store.set("events", out.toString())
        ui.onEvent("logboek", tekst)
    }

    private fun storeMode06(tests: JSONArray) {
        val arr = try { JSONArray(store.get("mode06", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().put("tijd", ts()).put("tests", tests))
        val out = JSONArray(); for (i in max(0, arr.length() - 200) until arr.length()) out.put(arr.get(i))
        store.set("mode06", out.toString())
    }

    // ---------------- toestand voor de interface ----------------
    fun stateJson(): String {
        val v = JSONObject(); for ((k, x) in values) v.put(k, x)
        val age = JSONObject(); val now = System.currentTimeMillis(); for ((k, t) in updated) age.put(k, (now - t) / 1000.0)
        return JSONObject().put("values", v).put("age", age).put("alarms", JSONObject(alarms as Map<*, *>))
            .put("conn", JSONObject().put("status", status).put("error", error).put("info", info).put("multi", multi ?: JSONObject.NULL)
                .put("sessies", sessies).put("onderbrekingen", onderbrekingen).put("hersteld", hersteld))
            .put("tripActive", tripActive).put("trip", if (tripActive) tripSummary(trip) else JSONObject.NULL)
            .put("health_features", health.features()).put("health_progress", health.progress())
            .put("dtcAlert", dtcAlert).put("logfile", logfile).put("tank", tank)
            .put("cfg", cfg)
            .put("victron", JSONArray().also { arr ->
                val k = keys()
                for (mac in k.keys()) {
                    val i = vInfo[mac]
                    arr.put(JSONObject().put("mac", mac).put("type", i?.optString("type", "") ?: "")
                        .put("status", i?.optString("status", "") ?: "")
                        .put("leeftijd_s", i?.optLong("laatst", 0L)?.takeIf { it > 0 }?.let { (now - it) / 1000 } ?: JSONObject.NULL))
                }
            }).toString()
    }

    fun historyJson(): String = synchronized(history) { JSONArray(history.toList()).toString() }

    /** Reeks (tijd, waarde) van één grootheid uit de laatste [sinceMs] ms, voor trendlijnen in Android Auto. */
    fun series(k: String, sinceMs: Long): List<Pair<Long, Double>> {
        val from = System.currentTimeMillis() - sinceMs
        return synchronized(history) {
            history.filter { it.optLong("t") >= from && it.has(k) }.map { it.getLong("t") to it.optDouble(k) }
        }
    }

    // ---------------- acties (vanuit de interface, op een eigen thread) ----------------
    private fun needElm(): Elm = elm ?: throw IOException("Niet verbonden met de auto")

    fun readDtcs(): JSONObject {
        val e = needElm(); val r = JSONObject()
        for ((mode, resp, name) in listOf(Triple("03", 0x43, "opgeslagen"), Triple("07", 0x47, "pending"), Triple("0A", 0x4A, "permanent"))) {
            try {
                val data = e.query(mode)
                r.put(name, JSONArray(Dtc.decode(data, resp).map { Dtc.explain(it) }))
                if (name == "permanent" && data == null) r.put("permanent_nb", true)
            } catch (ex: Exception) { r.put(name, JSONArray()); r.put(name + "_fout", ex.message) }
        }
        val st = HashMap<String, Any>(); Pids.decode(Pids.split(e.query("0101")), st)
        r.put("mil", st["mil"] ?: JSONObject.NULL).put("readiness", st["readiness"] ?: JSONObject()).put("freeze", JSONObject())
        if (r.getJSONArray("opgeslagen").length() > 0) try {
            val d = e.query("020200")
            if (d != null && d.size >= 5) {
                r.put("freeze_code", JSONArray(Dtc.decode(intArrayOf(0x43, 1, d[3], d[4]), 0x43)))
                val ff = HashMap<Int, IntArray>()
                for (p in listOf("04", "05", "0C", "0D", "11", "1F", "33", "42", "46", "5C")) ff.putAll(Pids.split(e.query("02${p}00"), 0x42, true))
                val dec = HashMap<String, Any>(); Pids.decode(ff, dec)
                val fr = JSONObject()
                val keyPid = mapOf("load" to 0x04, "coolant" to 0x05, "rpm" to 0x0C, "speed" to 0x0D, "throttle" to 0x11, "runtime" to 0x1F,
                    "baro" to 0x33, "voltage" to 0x42, "ambient" to 0x46, "oil" to 0x5C)
                for ((k, x) in dec) keyPid[k]?.let { pid -> Pids.NAMES[pid]?.let { (n, u) -> fr.put(n, JSONArray().put(x).put(u)) } }
                r.put("freeze", fr)
            }
        } catch (ex: Exception) { r.put("freeze_fout", ex.message) }
        return r
    }

    fun clearDtcs(confirm: String): Boolean {
        if ((d("rpm") ?: 0.0) > 0) throw IOException("Motor draait. Zet de motor uit (contact aan) en probeer opnieuw.")
        return needElm().clearDtcs(confirm)
    }

    fun mode06(): JSONArray { val t = Mode06.read(needElm()); storeMode06(t); return t }

    @Synchronized
    fun tripReset() {
        if (tripActive) { endTrip("handmatig"); if ((d("rpm") ?: 0.0) > 400) startTrip(System.currentTimeMillis()) }
    }

    @Synchronized
    fun addTank(liters: Double): JSONObject {
        val raw = tank.optDouble("sinds_tank_raw_l", 0.0)
        val e = JSONObject().put("tijd", ts()).put("liters", liters).put("schatting_ruw_l", (raw * 100).roundToInt() / 100.0)
        val b = tank.optJSONArray("tankbeurten") ?: JSONArray().also { tank.put("tankbeurten", it) }
        if (b.length() > 0 && raw > 5) e.put("factor", (liters / raw * 1000).roundToInt() / 1000.0)
        b.put(e)
        var sl = 0.0; var sf = 0.0
        for (i in max(0, b.length() - 5) until b.length()) b.getJSONObject(i).let { if (it.has("factor")) { sl += it.getDouble("liters"); sf += it.getDouble("liters") * it.getDouble("factor") } }
        if (sl > 0) tank.put("factor", (sf / sl * 1000).roundToInt() / 1000.0)
        tank.put("sinds_tank_raw_l", 0.0)
        store.set("tank", tank.toString())
        return e
    }

    fun setCfg(json: String) {
        val s = JSONObject(json)
        if (s.has("did_onderzoek")) cfg.put("did_onderzoek", s.getBoolean("did_onderzoek"))
        if (s.has("onzeker")) cfg.put("onzeker", s.getBoolean("onzeker"))
        s.optJSONObject("alarms")?.let { a -> val ca = cfg.getJSONObject("alarms"); a.keys().forEach { ca.put(it, a.get(it)) } }
        store.set("cfg", cfg.toString())
    }

    fun setDevice(addr: String) = store.set("cfg", cfg.put("dev", addr).toString())
}
