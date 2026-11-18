package nl.sprinterobd.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tekent de dashboards Rijden, Hitte & bergop en Energie op het Android Auto-scherm, in de stijl van het
 * ontwerp (design/OVERDRACHT.md). Alles wordt getekend op een referentievlak van 1100×620 en geschaald
 * naar het zichtbare deel van het scherm.
 */
class DashRenderer : SurfaceCallback {
    companion object {
        val PAGES = listOf("Rijden", "Hitte & bergop", "Energie")
        private const val RW = 1100f
        private const val RH = 620f
        private const val TOP = 70f
        private const val GAP = 14f
        private const val OBD_AGE = 10_000L
        private const val VIC_AGE = 90_000L

        private val BG = Color.parseColor("#100F0C")
        private val CARD = Color.parseColor("#1B1915")
        private val CARD2 = Color.parseColor("#262219")
        private val LINE = Color.parseColor("#3A3429")
        private val TXT = Color.parseColor("#EEE7D6")
        private val DIM = Color.parseColor("#A89F8A")
        private val NA = Color.parseColor("#6F6858")
        private val OK = Color.parseColor("#9CAD63")
        private val OKBG = Color.parseColor("#2A3019")
        private val WARN = Color.parseColor("#E2823F")
        private val WARNBG = Color.parseColor("#2A1E12")
        private val BAD = Color.parseColor("#FF4B3A")
        private val BADBG = Color.parseColor("#3A1310")
        private val ACCENT = Color.parseColor("#D9C49A")
        private val SOLAR = Color.parseColor("#DDA645")
    }

    private enum class St { N, W, C, NA }

    var page = 0
        private set
    var onPageChange: (() -> Unit)? = null

    private var surface: Surface? = null
    private var sw = 0; private var sh = 0
    private var visible: Rect? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val tick = object : Runnable {
        override fun run() { draw(); if (running) handler.postDelayed(this, 1000) }
    }

    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val condN = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nl = Locale("nl", "NL")

    // ---------------- SurfaceCallback ----------------
    override fun onSurfaceAvailable(c: SurfaceContainer) {
        surface = c.surface; sw = c.width; sh = c.height
        if (!running) { running = true; handler.post(tick) }
    }

    override fun onSurfaceDestroyed(c: SurfaceContainer) {
        running = false; handler.removeCallbacks(tick); surface = null
    }

    override fun onVisibleAreaChanged(r: Rect) { visible = Rect(r); draw() }
    override fun onStableAreaChanged(r: Rect) { }

    /** Veeg links/rechts over het scherm = volgend/vorig dashboard. */
    override fun onFling(velocityX: Float, velocityY: Float) {
        if (abs(velocityX) > abs(velocityY) && abs(velocityX) > 300) setPage(page + if (velocityX < 0) 1 else -1)
    }

    fun nextPage() = setPage(page + 1)

    private fun setPage(i: Int) {
        page = ((i % PAGES.size) + PAGES.size) % PAGES.size
        onPageChange?.invoke(); draw()
    }

    // ---------------- data ----------------
    private val core: Core? get() = ObdService.instance?.let { s -> try { s.core } catch (_: Exception) { null } }

    private fun n(k: String, age: Long = OBD_AGE): Double? {
        val c = core ?: return null
        val t = c.updated[k] ?: return null
        if (System.currentTimeMillis() - t > age) return null
        return (c.values[k] as? Number)?.toDouble()
    }

    private fun str(k: String, age: Long = VIC_AGE): String? {
        val c = core ?: return null
        val t = c.updated[k] ?: return null
        if (System.currentTimeMillis() - t > age) return null
        return c.values[k] as? String
    }

    private fun alarm(k: String): St = when (core?.alarms?.get(k)) { "rood" -> St.C; "oranje" -> St.W; else -> St.N }

    private fun thr(k: String, lvl: String, def: Double): Double =
        core?.cfg?.optJSONObject("alarms")?.optJSONObject(k)?.optDouble(lvl, def) ?: def

    private fun fmt(v: Double?, dec: Int = 0): String? {
        v ?: return null
        val f = NumberFormat.getNumberInstance(nl); f.minimumFractionDigits = dec; f.maximumFractionDigits = dec
        return f.format(v)
    }

    private fun st(v: Double?, s: St) = if (v == null) St.NA else s

    // ---------------- tekenen ----------------
    private fun draw() {
        val s = surface ?: return
        if (!s.isValid) return
        val c = try { s.lockCanvas(null) } catch (_: Exception) { return } ?: return
        try {
            c.drawColor(BG)
            val a = visible?.takeIf { it.width() > 0 && it.height() > 0 } ?: Rect(0, 0, sw, sh)
            val pad = 16f
            val k = min((a.width() - 2 * pad) / RW, (a.height() - 2 * pad) / RH)
            c.save()
            c.translate(a.left + (a.width() - RW * k) / 2, a.top + (a.height() - RH * k) / 2)
            c.scale(k, k)
            header(c)
            val co = core
            when {
                co == null -> message(c, "Meetdienst draait niet", "Open Sprinter OBD op je telefoon.")
                page == 0 -> rijden(c)
                page == 1 -> hitte(c)
                else -> energie(c)
            }
            c.restore()
        } finally {
            try { s.unlockCanvasAndPost(c) } catch (_: Exception) { }
        }
    }

    private fun text(c: Canvas, t: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface = cond,
                     align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): Float {
        p.reset(); p.isAntiAlias = true; p.typeface = tf; p.textSize = size; p.color = color; p.textAlign = align
        p.letterSpacing = spacing
        c.drawText(t, x, y, p)
        return p.measureText(t)
    }

    private fun rrect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int, stroke: Int? = null,
                      sw: Float = 0f, dashed: Boolean = false) {
        val rf = RectF(x, y, x + w, y + h)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = fill
        c.drawRoundRect(rf, r, r, p)
        if (stroke != null && sw > 0) {
            p.style = Paint.Style.STROKE; p.color = stroke; p.strokeWidth = sw
            if (dashed) p.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            rf.inset(sw / 2, sw / 2); c.drawRoundRect(rf, r, r, p)
        }
    }

    private fun header(c: Canvas) {
        text(c, PAGES[page], 0f, 42f, 36f, TXT)
        // paginastippen
        var x = RW / 2 - (28f + (PAGES.size - 1) * 16f) / 2
        for (i in PAGES.indices) {
            val w = if (i == page) 28f else 8f
            rrect(c, x, 22f, w, 8f, 4f, if (i == page) ACCENT else LINE)
            x += w + 8f
        }
        // status-chips
        val co = core
        val obdOk = co?.status == "verbonden"
        val vKeys = listOf("orion_out_a", "shunt_soc", "mppt_w")
        val vSeen = vKeys.count { co?.values?.containsKey(it) == true }
        val vFresh = vKeys.count { n(it, VIC_AGE) != null }
        var right = RW
        if (vSeen > 0) right = chip(c, right, "Victron $vFresh/$vSeen", vFresh == vSeen) - 10f
        chip(c, right, "OBD", obdOk)
        // waarschuwingsbalk
        val reason = when {
            co == null -> null
            !obdOk -> "OBD-dongle niet verbonden · ${co.status}"
            else -> null
        }
        if (reason != null) text(c, reason, RW / 2, 60f, 15f, WARN, condN, Paint.Align.CENTER)
    }

    /** Chip rechts uitgelijnd op [right]; geeft de linkerrand terug. */
    private fun chip(c: Canvas, right: Float, label: String, ok: Boolean): Float {
        p.reset(); p.typeface = cond; p.textSize = 17f
        val w = p.measureText(label) + 44f
        val x = right - w
        rrect(c, x, 12f, w, 34f, 17f, CARD2)
        p.reset(); p.isAntiAlias = true
        if (ok) { p.style = Paint.Style.FILL; p.color = OK } else { p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = WARN }
        c.drawCircle(x + 18f, 29f, 6f, p)
        text(c, label, x + 30f, 35f, 17f, if (ok) TXT else DIM)
        return x
    }

    private fun message(c: Canvas, title: String, sub: String) {
        text(c, title, RW / 2, RH / 2 - 10f, 44f, TXT, cond, Paint.Align.CENTER)
        text(c, sub, RW / 2, RH / 2 + 36f, 22f, DIM, condN, Paint.Align.CENTER)
    }

    /** Tegel volgens component 1 (Tile) uit het ontwerp. */
    private fun tile(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, value: String?, unit: String,
                     sub: String, state: St, vs: Float = 72f, valueColor: Int? = null) {
        val s = if (value == null) St.NA else state
        when (s) {
            St.W -> rrect(c, x, y, w, h, 16f, WARNBG, WARN, 3f)
            St.C -> rrect(c, x, y, w, h, 16f, BADBG, BAD, 4f)
            St.NA -> rrect(c, x, y, w, h, 16f, CARD, LINE, 2f, dashed = true)
            St.N -> rrect(c, x, y, w, h, 16f, CARD)
        }
        text(c, label.uppercase(nl), x + 20f, y + 32f, 15f, DIM, cond, spacing = 0.1f)
        val tag = when (s) { St.W -> "WAARSCHUWING" to WARN; St.C -> "KRITIEK" to BAD; St.NA -> "GEEN DATA" to NA; else -> null }
        if (tag != null) text(c, tag.first, x + w - 20f, y + 32f, 13f, tag.second, cond, Paint.Align.RIGHT, 0.08f)
        val vy = y + 32f + vs * 0.95f + 10f
        val vc = when { value == null -> NA; s == St.C -> BAD; valueColor != null -> valueColor; else -> TXT }
        val vw = text(c, value ?: "–", x + 20f, vy, vs, vc)
        if (value != null && unit.isNotEmpty()) text(c, unit, x + 26f + vw, vy, vs * 0.35f, DIM)
        if (sub.isNotEmpty()) text(c, sub, x + 20f, y + h - 18f, 16f, if (s == St.C) BAD else DIM, condN)
    }

    // ---------------- Rijden ----------------
    private fun rijden(c: Canvas) {
        val co = core ?: return
        val colW = 340f; val h2 = (RH - TOP - GAP) / 2
        val rpm = n("rpm"); val load = n("load")
        val maxRpm = if (co.tripActive) co.trip.optJSONObject("max")?.optDouble("rpm")?.takeIf { !it.isNaN() } else null
        tile(c, 0f, TOP, colW, h2, "Toerental", fmt(rpm), "rpm", maxRpm?.let { "max deze rit ${fmt(it)}" } ?: "", alarm("rpm"), 80f)
        val avg = co.series("load", 300_000).map { it.second }.takeIf { it.isNotEmpty() }?.average()
        tile(c, 0f, TOP + h2 + GAP, colW, h2, "Motorbelasting", fmt(load), "%", avg?.let { "gem. laatste 5 min ${fmt(it)} %" } ?: "", alarm("load"), 80f)

        gauge(c, RW / 2, TOP + 290f, 175f, n("speed"))

        val h3 = (RH - TOP - 2 * GAP) / 3; val rx = RW - colW
        val v = n("voltage")
        val vSub = when { v == null -> "bij ECU"; (rpm ?: 0.0) > 400 && v >= 13.2 -> "dynamo laadt · bij ECU"; (rpm ?: 0.0) > 400 -> "laadt niet · bij ECU"; else -> "motor uit · bij ECU" }
        tile(c, rx, TOP, colW, h3, "Accu auto", fmt(v, 1), "V", vSub, alarm("voltage"), 60f)
        val cool = n("coolant")
        val cSub = when {
            alarm("coolant") == St.C -> "stop veilig en laat afkoelen"
            co.alarms["coolant_reden"] != null -> co.alarms["coolant_reden"]!!
            cool != null && cool < 70 -> "nog koud · rustig rijden"
            else -> "normaal · 85–100 °C"
        }
        tile(c, rx, TOP + h3 + GAP, colW, h3, "Koelvloeistof", fmt(cool), "°C", cSub, alarm("coolant"), 60f)
        val ba = n("boost_act"); val bc = n("boost_cmd")
        tile(c, rx, TOP + 2 * (h3 + GAP), colW, h3, "Turbodruk", fmt(ba?.div(100), 2), "bar",
            bc?.let { "gevraagd ${fmt(it / 100, 2)} · absoluut" } ?: "absoluut", alarm("boost_dev"), 60f)
    }

    /** ArcGauge: 270°, baan in lijnkleur, vulling accent, één streep bij 120 km/u. */
    private fun gauge(c: Canvas, cx: Float, cy: Float, r: Float, speed: Double?) {
        val max = 140f
        val rf = RectF(cx - r, cy - r, cx + r, cy + r)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 22f; p.strokeCap = Paint.Cap.ROUND
        p.color = LINE; c.drawArc(rf, 135f, 270f, false, p)
        if (speed != null && speed > 0) {
            p.color = ACCENT; c.drawArc(rf, 135f, 270f * (min(speed.toFloat(), max) / max), false, p)
        }
        // streep bij 120
        val ang = Math.toRadians((135.0 + 270.0 * 120 / max))
        p.strokeCap = Paint.Cap.BUTT; p.strokeWidth = 4f; p.color = TXT
        val r1 = r - 18f; val r2 = r + 18f
        c.drawLine(cx + (r1 * Math.cos(ang)).toFloat(), cy + (r1 * Math.sin(ang)).toFloat(),
            cx + (r2 * Math.cos(ang)).toFloat(), cy + (r2 * Math.sin(ang)).toFloat(), p)
        val vs = 150f
        text(c, fmt(speed) ?: "–", cx, cy + vs * 0.33f, vs, if (speed == null) NA else TXT, cond, Paint.Align.CENTER)
        text(c, "km/u", cx, cy + vs * 0.33f + 44f, 30f, DIM, condN, Paint.Align.CENTER)
        text(c, "0", cx - r * 0.72f, cy + r * 0.92f, 18f, DIM, condN, Paint.Align.CENTER)
        text(c, "140", cx + r * 0.72f, cy + r * 0.92f, 18f, DIM, condN, Paint.Align.CENTER)
        text(c, "120", cx + (r + 34f) * Math.cos(ang).toFloat(), cy + (r + 34f) * Math.sin(ang).toFloat() + 6f, 16f, DIM, condN, Paint.Align.CENTER)
    }

    // ---------------- Hitte & bergop ----------------
    private fun hitte(c: Canvas) {
        val co = core ?: return
        val w2 = (RW - GAP) / 2; val th = 330f
        val cool = n("coolant")
        trend(c, 0f, TOP, w2, th, "Koelvloeistof", cool, co.series("coolant", 600_000), alarm("coolant"),
            thr("coolant", "oranje", 106.0), thr("coolant", "rood", 112.0), rate(co, "coolant"))
        val iat = n("iat"); val amb = n("ambient")
        trend(c, w2 + GAP, TOP, w2, th, "Inlaatlucht", iat, co.series("iat", 600_000), alarm("iat"),
            thr("iat", "oranje", 60.0), thr("iat", "rood", 75.0),
            if (iat != null && amb != null) "${fmt(iat - amb)} °C boven buiten" else "")

        val by = TOP + th + GAP; val bh = RH - by; val w3 = (RW - 2 * GAP) / 3
        val load = n("load")
        val hi = co.series("load", 600_000).reversed().takeWhile { it.second >= 80 }
        val loadSub = if (hi.size >= 60) "al ${(hi.first().first - hi.last().first) / 60_000 + 1} min boven 80 %" else "laatste 10 min"
        tile(c, 0f, by, w3, bh, "Motorbelasting", fmt(load), "%", loadSub, alarm("load"), 64f)
        val ba = n("boost_act"); val bc = n("boost_cmd")
        tile(c, w3 + GAP, by, w3, bh, "Turbodruk", fmt(ba?.div(100), 2), "bar",
            if (ba != null && bc != null) "gevraagd ${fmt(bc / 100, 2)} · tekort ${fmt(max(0.0, bc - ba) / 100, 2)}" else "absoluut",
            alarm("boost_dev"), 64f)
        val baro = n("baro")
        val alt = baro?.let { 44330.0 * (1 - (it / 101.325).pow(0.1903)) }
        tile(c, 2 * (w3 + GAP), by, w3, bh, "Buitentemperatuur", fmt(amb), "°C",
            baro?.let { "luchtdruk ${fmt(it / 100, 2)} bar · ±${fmt(max(0.0, Math.round(alt!! / 50.0) * 50.0))} m" } ?: "", St.N, 64f)
    }

    private fun rate(co: Core, k: String): String {
        val s = co.series(k, 120_000); if (s.size < 30) return ""
        val dtMin = (s.last().first - s.first().first) / 60_000.0; if (dtMin < 0.5) return ""
        val r = (s.last().second - s.first().second) / dtMin
        return when { r > 0.2 -> "stijgt +${fmt(r, 1)} °C/min"; r < -0.2 -> "daalt ${fmt(r, 1)} °C/min"; else -> "stabiel" }
    }

    /** TrendChart: 10 min, lijn accent, waarschuwing/kritiek als stippellijnen. */
    private fun trend(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, v: Double?,
                      data: List<Pair<Long, Double>>, state: St, warn: Double, crit: Double, sub: String) {
        val s = st(v, state)
        tile(c, x, y, w, h, label, fmt(v), "°C", "", s, 64f)
        if (sub.isNotEmpty()) text(c, sub, x + w - 20f, y + 100f, 17f, if (s == St.C) BAD else DIM, condN, Paint.Align.RIGHT)
        val gx = x + 20f; val gy = y + 130f; val gw = w - 40f; val gh = h - 175f
        val now = System.currentTimeMillis(); val from = now - 600_000
        val vals = data.map { it.second }
        val lo = min(vals.minOrNull() ?: warn - 30, warn - 30) - 2
        val hi = max(vals.maxOrNull() ?: crit, crit) + 3
        fun py(v: Double) = (gy + gh - (v - lo) / (hi - lo) * gh).toFloat()
        fun px(t: Long) = gx + ((t - from).toFloat() / 600_000f) * gw
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        p.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        for ((lvl, col, name) in listOf(Triple(warn, WARN, "waarschuwing"), Triple(crit, BAD, "kritiek"))) {
            p.color = col; c.drawLine(gx, py(lvl), gx + gw, py(lvl), p)
            text(c, "${fmt(lvl)} $name", gx + 4f, py(lvl) - 6f, 14f, col, condN)
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            p.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        if (data.size >= 2) {
            val path = Path(); val fill = Path()
            data.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(px(t), py(v)) else path.lineTo(px(t), py(v)) }
            fill.addPath(path); fill.lineTo(px(data.last().first), gy + gh); fill.lineTo(px(data.first().first), gy + gh); fill.close()
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = ACCENT; p.alpha = 28; c.drawPath(fill, p)
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = ACCENT
            p.strokeJoin = Paint.Join.ROUND; c.drawPath(path, p)
        }
        text(c, "−10 min", gx, y + h - 16f, 14f, NA, condN)
        text(c, "nu", gx + gw, y + h - 16f, 14f, NA, condN, Paint.Align.RIGHT)
    }

    // ---------------- Energie ----------------
    private fun energie(c: Canvas) {
        val w3 = (RW - 2 * GAP) / 3; val h1 = 250f
        val sunW = n("mppt_w", VIC_AGE); val sunA = n("mppt_a", VIC_AGE); val yieldWh = n("mppt_yield_wh", VIC_AGE)
        tile(c, 0f, TOP, w3, h1, "Zon" + (str("mppt_state")?.let { " · $it" } ?: ""), fmt(sunW), "W",
            listOfNotNull(yieldWh?.let { "vandaag ${fmt(it)} Wh" }, sunA?.let { "${fmt(it, 1)} A" }).joinToString(" · "),
            St.N, 72f, SOLAR)
        val oA = n("orion_out_a", VIC_AGE); val oV = n("orion_out_v", VIC_AGE)
        val dcW = if (oA != null && oV != null) oA * oV else null
        tile(c, w3 + GAP, TOP, w3, h1, "Dynamo · DC-DC" + (str("orion_state")?.let { " · $it" } ?: ""), fmt(dcW), "W",
            oA?.let { "${fmt(it, 1)} A naar woonaccu" } ?: "", St.N, 72f)
        val bV = n("shunt_v", VIC_AGE); val bA = n("shunt_a", VIC_AGE); val soc = n("shunt_soc", VIC_AGE)
        val batW = if (bV != null && bA != null) bV * bA else null
        val useW = if (batW != null) max(0.0, (sunW ?: 0.0) + (dcW ?: 0.0) - batW) else null
        tile(c, 2 * (w3 + GAP), TOP, w3, h1, "Verbruikers · berekend", fmt(useW), "W", "schatting: zon + DC-DC − accu", St.N, 72f)

        val y2 = TOP + h1 + GAP; val h2 = RH - y2; val ww = 2 * w3 + GAP
        val socSt = when { soc == null -> St.NA; soc < 20 -> St.W; else -> St.N }
        when (socSt) {
            St.W -> rrect(c, 0f, y2, ww, h2, 16f, WARNBG, WARN, 3f)
            St.NA -> rrect(c, 0f, y2, ww, h2, 16f, CARD, LINE, 2f, dashed = true)
            else -> rrect(c, 0f, y2, ww, h2, 16f, CARD)
        }
        text(c, "WOONACCU · LIFEPO4 280 AH", 20f, y2 + 32f, 15f, DIM, cond, spacing = 0.1f)
        if (socSt == St.W) text(c, "WAARSCHUWING", ww - 20f, y2 + 32f, 13f, WARN, cond, Paint.Align.RIGHT, 0.08f)
        if (socSt == St.NA) text(c, "GEEN DATA", ww - 20f, y2 + 32f, 13f, NA, cond, Paint.Align.RIGHT, 0.08f)
        // SocRing
        val rcx = 130f; val rcy = y2 + h2 / 2 + 14f; val rr = 88f
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 18f; p.color = LINE
        c.drawCircle(rcx, rcy, rr, p)
        if (soc != null) {
            p.color = if (socSt == St.W) WARN else OK; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(rcx - rr, rcy - rr, rcx + rr, rcy + rr), -90f, 360f * (soc / 100).toFloat(), false, p)
        }
        text(c, soc?.let { fmt(it) + "%" } ?: "–", rcx, rcy + 18f, 52f, if (soc == null) NA else TXT, cond, Paint.Align.CENTER)
        val laadt = (bA ?: 0.0) > 0.3
        val rows = listOf(
            "spanning" to (fmt(bV, 2)?.let { "$it V" } ?: "–"),
            "stroom" to (bA?.let { (if (it > 0) "+" else "") + fmt(it, 1) + " A" } ?: "–"),
            "vermogen" to (batW?.let { (if (it > 0) "+" else "") + fmt(it) + " W" } ?: "–"),
            "resterend" to (if (laadt) "– laadt" else n("shunt_rest_min", VIC_AGE)?.let { fmtRest(it) } ?: "–"))
        rows.forEachIndexed { i, (k, v) ->
            val ry = y2 + 82f + i * 50f
            text(c, k, 270f, ry, 20f, DIM, condN)
            text(c, v, ww - 24f, ry, 30f, TXT, cond, Paint.Align.RIGHT)
        }
        val sv = n("voltage"); val inV = n("orion_in_v", VIC_AGE)
        val car = sv ?: inV
        tile(c, ww + GAP, y2, w3, h2, "Accu auto", fmt(car, 1), "V",
            if ((n("rpm") ?: 0.0) > 400) "dynamo laadt · voedt DC-DC" else if (sv == null && inV != null) "gemeten bij DC-DC" else "motor uit",
            if (sv != null) alarm("voltage") else St.N, 72f)
    }

    private fun fmtRest(min: Double): String {
        val h = (min / 60).toInt(); val m = (min % 60).toInt()
        return if (h >= 24) "${h / 24} d ${h % 24} u" else "$h u ${m} min"
    }
}
