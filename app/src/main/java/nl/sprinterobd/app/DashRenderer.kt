package nl.sprinterobd.app

import android.content.res.AssetManager
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
 *
 * Pakket 1 (voorstel P08/P10/P11): 3 Hz tekenen met dirty-check (i.p.v. 1 Hz), hergebruikte objecten,
 * tekentijd in het meetlog, optioneel hardware-canvas (cfg aa_hw), Barlow Condensed uit assets/fonts (fallback: systeem),
 * grotere labels (≈ 24 dp bij 1280×720 / 160 dpi), grotere terugknop en hoger contrast voor "geen data".
 */
class DashRenderer(private val assets: AssetManager? = null) : SurfaceCallback {
    companion object {
        val PAGES = listOf("Rijden", "Hitte & bergop", "Energie")
        /** Korte namen voor de terugknop (houdt de kopregel vrij van de titel). */
        private val SHORT = listOf("Rijden", "Hitte", "Energie")
        private const val RH = 620f
        private const val GAP = 14f
        private const val HEAD = 108f            // basishoogte kopregel
        private const val OBD_AGE = 10_000L
        private const val VIC_AGE = 90_000L
        private const val TICK_MS = 333L         // 3 Hz; het beeld gaat als 30 fps-video naar de head unit
        private const val FORCE_MS = 1000L       // minstens 1× per seconde tekenen (waarden verouderen)

        private val BG = Color.parseColor("#100F0C")
        private val CARD = Color.parseColor("#1B1915")
        private val CARD2 = Color.parseColor("#262219")
        private val LINE = Color.parseColor("#3A3429")
        private val TXT = Color.parseColor("#EEE7D6")
        private val DIM = Color.parseColor("#A89F8A")
        private val NA = Color.parseColor("#8D8572")   // was #6F6858 (3,2:1); nu 4,8:1 op de tegelkleur
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
    // Referentievlak: hoogte vast (RH), breedte volgt de beeldverhouding van het autoscherm.
    private var RW = 1100f
    // Bovenrand van de tegels; groeit mee met de knop rechtsboven (ActionStrip) zodat die niets afdekt.
    private var TOP = HEAD
    // Omrekening schermpixels -> referentievlak (voor aanraken)
    private var k = 1f; private var ox = 0f; private var oy = 0f
    private val backRect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Dirty-check: alleen tekenen bij nieuwe data, paginawissel of minstens elke FORCE_MS.
    private var lastStamp = -1L
    private var lastDraw = 0L
    private var force = true
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val stamp = core?.dataStamp ?: 0L
            if (force || stamp != lastStamp || now - lastDraw >= FORCE_MS) draw()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun font(file: String, bold: Boolean): Typeface {
        try { if (assets != null) return Typeface.createFromAsset(assets, "fonts/$file") } catch (_: Exception) { }
        return Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private val cond = font("BarlowCondensed-SemiBold.ttf", true)
    private val condN = font("BarlowCondensed-Medium.ttf", false)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nl = Locale("nl", "NL")

    // Hergebruikte objecten (voorheen per frame opnieuw aangemaakt)
    private val rf = RectF()
    private val dashTile = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    private val dashTrend = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    private val nfs = arrayOfNulls<NumberFormat>(4)
    private val serCache = HashMap<String, Pair<Long, List<Pair<Long, Double>>>>()

    // ---------------- SurfaceCallback ----------------
    override fun onSurfaceAvailable(c: SurfaceContainer) {
        surface = c.surface; sw = c.width; sh = c.height
        force = true
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

    /** Tik op de terugknop linksboven (Car API 5+; anders blijft vegen werken). */
    override fun onClick(x: Float, y: Float) {
        val rx = (x - ox) / k; val ry = (y - oy) / k
        if (rx >= backRect.left - 20f && rx <= backRect.right + 20f && ry >= backRect.top - 20f && ry <= backRect.bottom + 20f) prevPage()
    }

    fun nextPage() = setPage(page + 1)
    fun prevPage() = setPage(page - 1)

    private fun setPage(i: Int) {
        page = ((i % PAGES.size) + PAGES.size) % PAGES.size
        force = true
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

    /** Reeks uit de historie, hooguit 1× per seconde opnieuw berekend (filteren van 900 JSON-rijen is duur). */
    private fun ser(co: Core, key: String, ms: Long): List<Pair<Long, Double>> {
        val now = System.currentTimeMillis(); val ck = "$key|$ms"
        serCache[ck]?.let { if (now - it.first < 1000L) return it.second }
        val s = co.series(key, ms)
        serCache[ck] = now to s
        return s
    }

    private fun nf(dec: Int): NumberFormat {
        val i = dec.coerceIn(0, 3)
        return nfs[i] ?: NumberFormat.getNumberInstance(nl).also { it.minimumFractionDigits = i; it.maximumFractionDigits = i; nfs[i] = it }
    }

    private fun fmt(v: Double?, dec: Int = 0): String? {
        v ?: return null
        return nf(dec).format(v)
    }

    private fun st(v: Double?, s: St) = if (v == null) St.NA else s

    // ---------------- tekenen ----------------
    private fun draw() {
        lastStamp = core?.dataStamp ?: 0L; lastDraw = System.currentTimeMillis(); force = false
        val s = surface ?: return
        if (!s.isValid) return
        val t0 = System.nanoTime()
        val hw = core?.cfg?.optBoolean("aa_hw", false) == true
        val c = try { if (hw) s.lockHardwareCanvas() else s.lockCanvas(null) } catch (_: Exception) { return } ?: return
        try {
            c.drawColor(BG)
            // Hele oppervlak gebruiken; alleen de knop rechtsboven (die het zichtbare deel bovenaan inkort)
            // krijgt ruimte via een hogere kopregel.
            val v = visible?.takeIf { it.width() > 0 && it.height() > 0 }
            val a = if (v != null) Rect(v.left, 0, v.right, v.bottom) else Rect(0, 0, sw, sh)
            val pad = 12f
            val aw = a.width() - 2 * pad; val ah = a.height() - 2 * pad
            RW = max(1100f, RH * aw / ah)
            k = min(aw / RW, ah / RH)
            ox = a.left + (a.width() - RW * k) / 2; oy = a.top + (a.height() - RH * k) / 2
            TOP = if (v != null) ((v.top - oy) / k + 8f).coerceIn(HEAD, 156f) else HEAD
            c.save()
            c.translate(ox, oy)
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
            Meet.draw(System.nanoTime() - t0)
        }
    }

    private fun text(c: Canvas, t: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface = cond,
                     align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): Float {
        p.reset(); p.isAntiAlias = true; p.typeface = tf; p.textSize = size; p.color = color; p.textAlign = align
        p.letterSpacing = spacing
        c.drawText(t, x, y, p)
        return p.measureText(t)
    }

    private fun measure(t: String, size: Float, tf: Typeface = cond, spacing: Float = 0f): Float {
        p.reset(); p.isAntiAlias = true; p.typeface = tf; p.textSize = size; p.letterSpacing = spacing
        return p.measureText(t)
    }

    /** Als [text], maar krimpt (tot 70 %) zodat de tekst in [maxW] past. */
    private fun textFit(c: Canvas, t: String, x: Float, y: Float, size: Float, color: Int, maxW: Float, tf: Typeface = cond,
                        align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): Float {
        val w = measure(t, size, tf, spacing)
        val s = if (w > maxW && w > 0f) max(size * 0.7f, size * maxW / w) else size
        return text(c, t, x, y, s, color, tf, align, spacing)
    }

    private fun rrect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, fill: Int, stroke: Int? = null,
                      sw: Float = 0f, dashed: Boolean = false) {
        rf.set(x, y, x + w, y + h)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = fill
        c.drawRoundRect(rf, r, r, p)
        if (stroke != null && sw > 0) {
            p.style = Paint.Style.STROKE; p.color = stroke; p.strokeWidth = sw
            if (dashed) p.pathEffect = dashTile
            rf.inset(sw / 2, sw / 2); c.drawRoundRect(rf, r, r, p)
        }
    }

    private fun header(c: Canvas) {
        val dy = (TOP - HEAD) / 2
        // terugknop linksboven met korte naam van het vorige dashboard (vooruit = knop rechtsboven); 76 hoog (touchdoel)
        val prev = SHORT[(page - 1 + PAGES.size) % PAGES.size]
        val bw = measure("‹  $prev", 30f) + 56f
        val bh = 76f; val by = dy + 4f
        backRect.set(0f, by, bw, by + bh)
        rrect(c, 0f, by, bw, bh, 38f, CARD2, LINE, 2f)
        text(c, "‹  $prev", 28f, by + bh / 2 + 10f, 30f, TXT)
        // titel + paginastippen in het midden
        val title = PAGES[page]
        text(c, title, RW / 2, dy + 50f, 38f, TXT, cond, Paint.Align.CENTER)
        var x = RW / 2 - (28f + (PAGES.size - 1) * 16f) / 2
        for (i in PAGES.indices) {
            val w = if (i == page) 28f else 8f
            rrect(c, x, dy + 66f, w, 8f, 4f, if (i == page) ACCENT else LINE)
            x += w + 8f
        }
        // status-chips naast de terugknop (rechtsboven zit de vooruitknop); alleen als ze de titel niet raken
        val co = core
        val obdOk = co?.status == "verbonden"
        val vKeys = listOf("orion_out_a", "shunt_soc", "mppt_w")
        val vSeen = vKeys.count { co?.values?.containsKey(it) == true }
        val vFresh = vKeys.count { n(it, VIC_AGE) != null }
        val titleLeft = RW / 2 - measure(title, 38f) / 2 - 16f
        var left = bw + 14f
        left = chip(c, left, dy + 20f, "OBD", obdOk) + 10f
        if (vSeen > 0) {
            val full = "Victron $vFresh/$vSeen"; val short = "V $vFresh/$vSeen"
            val label = if (left + chipW(full) <= titleLeft) full else if (left + chipW(short) <= titleLeft) short else null
            if (label != null) chip(c, left, dy + 20f, label, vFresh == vSeen)
        }
        // waarschuwingsbalk
        val reason = when {
            co == null -> null
            !obdOk -> "OBD-dongle niet verbonden · ${co.status}"
            else -> null
        }
        if (reason != null) textFit(c, reason, RW / 2, dy + 100f, 20f, WARN, RW - 40f, condN, Paint.Align.CENTER)
    }

    private fun chipW(label: String) = measure(label, 20f) + 48f

    /** Chip links uitgelijnd op [x], bovenkant [y]; geeft de rechterrand terug. */
    private fun chip(c: Canvas, x: Float, y: Float, label: String, ok: Boolean): Float {
        val w = chipW(label)
        rrect(c, x, y, w, 44f, 22f, CARD2)
        p.reset(); p.isAntiAlias = true
        if (ok) { p.style = Paint.Style.FILL; p.color = OK } else { p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = WARN }
        c.drawCircle(x + 22f, y + 22f, 7f, p)
        text(c, label, x + 36f, y + 30f, 20f, if (ok) TXT else DIM)
        return x + w
    }

    private fun message(c: Canvas, title: String, sub: String) {
        text(c, title, RW / 2, RH / 2 - 10f, 48f, TXT, cond, Paint.Align.CENTER)
        text(c, sub, RW / 2, RH / 2 + 40f, 28f, DIM, condN, Paint.Align.CENTER)
    }

    /** Tegel volgens component 1 (Tile) uit het ontwerp; labels ≥ ~24 dp op 1280×720 / 160 dpi. */
    private fun tile(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, value: String?, unit: String,
                     sub: String, state: St, vs: Float = 72f, valueColor: Int? = null) {
        val s = if (value == null) St.NA else state
        when (s) {
            St.W -> rrect(c, x, y, w, h, 16f, WARNBG, WARN, 3f)
            St.C -> rrect(c, x, y, w, h, 16f, BADBG, BAD, 4f)
            St.NA -> rrect(c, x, y, w, h, 16f, CARD, LINE, 2f, dashed = true)
            St.N -> rrect(c, x, y, w, h, 16f, CARD)
        }
        val tag = when (s) { St.W -> "WAARSCHUWING" to WARN; St.C -> "KRITIEK" to BAD; St.NA -> "GEEN DATA" to NA; else -> null }
        val tagW = if (tag != null) measure(tag.first, 18f, cond, 0.06f) else 0f
        textFit(c, label.uppercase(nl), x + 20f, y + 34f, 21f, DIM, w - 40f - (if (tag != null) tagW + 14f else 0f), cond, spacing = 0.08f)
        if (tag != null) text(c, tag.first, x + w - 20f, y + 34f, 18f, tag.second, cond, Paint.Align.RIGHT, 0.06f)
        val vy = y + 36f + vs * 0.95f + 10f
        val vc = when { value == null -> NA; s == St.C -> BAD; valueColor != null -> valueColor; else -> TXT }
        val vw = text(c, value ?: "–", x + 20f, vy, vs, vc)
        if (value != null && unit.isNotEmpty()) text(c, unit, x + 26f + vw, vy, vs * 0.4f, DIM)
        if (sub.isNotEmpty()) textFit(c, sub, x + 20f, y + h - 18f, 21f, if (s == St.C) BAD else DIM, w - 40f, condN)
    }

    // ---------------- Rijden ----------------
    private fun rijden(c: Canvas) {
        val co = core ?: return
        val colW = max(340f, (RW - 420f) / 2)
        val h3 = (RH - TOP - 2 * GAP) / 3; val rx = RW - colW
        val rpm = n("rpm"); val load = n("load")
        val maxRpm = if (co.tripActive) co.trip.optJSONObject("max")?.optDouble("rpm")?.takeIf { !it.isNaN() } else null
        tile(c, 0f, TOP, colW, h3, "Toerental", fmt(rpm), "rpm", maxRpm?.let { "max deze rit ${fmt(it)}" } ?: "", alarm("rpm"), 60f)
        val avg = ser(co, "load", 300_000).map { it.second }.takeIf { it.isNotEmpty() }?.average()
        tile(c, 0f, TOP + h3 + GAP, colW, h3, "Motorbelasting", fmt(load), "%", avg?.let { "gem. laatste 5 min ${fmt(it)} %" } ?: "", alarm("load"), 60f)
        val oA = n("orion_out_a", VIC_AGE); val oV = n("orion_out_v", VIC_AGE)
        val dcW = if (oA != null && oV != null) oA * oV else null
        tile(c, 0f, TOP + 2 * (h3 + GAP), colW, h3, "Dynamo · DC-DC" + (str("orion_state")?.let { " · $it" } ?: ""), fmt(dcW), "W",
            oA?.let { "${fmt(it, 1)} A naar woonaccu" } ?: "", St.N, 60f)

        val gr = min(175f, (RH - TOP) / 2 - 40f)
        gauge(c, RW / 2, TOP + (RH - TOP) / 2 + 10f, gr, n("speed"))

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
        rf.set(cx - r, cy - r, cx + r, cy + r)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 24f; p.strokeCap = Paint.Cap.ROUND
        p.color = LINE; c.drawArc(rf, 135f, 270f, false, p)
        if (speed != null && speed > 0) {
            p.color = ACCENT; c.drawArc(rf, 135f, 270f * (min(speed.toFloat(), max) / max), false, p)
        }
        // streep bij 120
        val ang = Math.toRadians((135.0 + 270.0 * 120 / max))
        p.strokeCap = Paint.Cap.BUTT; p.strokeWidth = 5f; p.color = TXT
        val r1 = r - 18f; val r2 = r + 18f
        c.drawLine(cx + (r1 * Math.cos(ang)).toFloat(), cy + (r1 * Math.sin(ang)).toFloat(),
            cx + (r2 * Math.cos(ang)).toFloat(), cy + (r2 * Math.sin(ang)).toFloat(), p)
        val vs = 150f
        text(c, fmt(speed) ?: "–", cx, cy + vs * 0.33f, vs, if (speed == null) NA else TXT, cond, Paint.Align.CENTER)
        text(c, "km/u", cx, cy + vs * 0.33f + 46f, 34f, DIM, condN, Paint.Align.CENTER)
        text(c, "0", cx - r * 0.72f, cy + r * 0.92f, 22f, DIM, condN, Paint.Align.CENTER)
        text(c, "140", cx + r * 0.72f, cy + r * 0.92f, 22f, DIM, condN, Paint.Align.CENTER)
        text(c, "120", cx + (r + 36f) * Math.cos(ang).toFloat(), cy + (r + 36f) * Math.sin(ang).toFloat() + 7f, 20f, DIM, condN, Paint.Align.CENTER)
    }

    // ---------------- Hitte & bergop ----------------
    private fun hitte(c: Canvas) {
        val co = core ?: return
        val w2 = (RW - GAP) / 2; val th = ((RH - TOP - GAP) * 0.62f)
        val cool = n("coolant")
        trend(c, 0f, TOP, w2, th, "Koelvloeistof", cool, ser(co, "coolant", 600_000), alarm("coolant"),
            thr("coolant", "oranje", 106.0), thr("coolant", "rood", 112.0), rate(co, "coolant"))
        val iat = n("iat"); val amb = n("ambient")
        trend(c, w2 + GAP, TOP, w2, th, "Inlaatlucht", iat, ser(co, "iat", 600_000), alarm("iat"),
            thr("iat", "oranje", 60.0), thr("iat", "rood", 75.0),
            if (iat != null && amb != null) "${fmt(iat - amb)} °C boven buiten" else "")

        val by = TOP + th + GAP; val bh = RH - by; val w3 = (RW - 2 * GAP) / 3
        val load = n("load")
        val hi = ser(co, "load", 600_000).reversed().takeWhile { it.second >= 80 }
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
        val s = ser(co, k, 120_000); if (s.size < 30) return ""
        val dtMin = (s.last().first - s.first().first) / 60_000.0; if (dtMin < 0.5) return ""
        val r = (s.last().second - s.first().second) / dtMin
        return when { r > 0.2 -> "stijgt +${fmt(r, 1)} °C/min"; r < -0.2 -> "daalt ${fmt(r, 1)} °C/min"; else -> "stabiel" }
    }

    /** TrendChart: 10 min, lijn accent, waarschuwing/kritiek als stippellijnen (dikker: H.264 smeert dunne lijnen uit). */
    private fun trend(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, v: Double?,
                      data: List<Pair<Long, Double>>, state: St, warn: Double, crit: Double, sub: String) {
        val s = st(v, state)
        tile(c, x, y, w, h, label, fmt(v), "°C", "", s, 64f)
        val vy = y + 36f + 64f * 0.95f + 10f
        if (sub.isNotEmpty()) textFit(c, sub, x + w - 20f, vy, 22f, if (s == St.C) BAD else DIM, w * 0.5f, condN, Paint.Align.RIGHT)
        val gx = x + 20f; val gy = y + 138f; val gw = w - 40f; val gh = h - 138f - 50f
        val now = System.currentTimeMillis(); val from = now - 600_000
        val vals = data.map { it.second }
        val lo = min(vals.minOrNull() ?: warn - 30, warn - 30) - 2
        val hi = max(vals.maxOrNull() ?: crit, crit) + 3
        fun py(v: Double) = (gy + gh - (v - lo) / (hi - lo) * gh).toFloat()
        fun px(t: Long) = gx + ((t - from).toFloat() / 600_000f) * gw
        for ((lvl, col, name) in listOf(Triple(warn, WARN, "waarschuwing"), Triple(crit, BAD, "kritiek"))) {
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 3f
            p.pathEffect = dashTrend
            p.color = col; c.drawLine(gx, py(lvl), gx + gw, py(lvl), p)
            text(c, "${fmt(lvl)} $name", gx + 4f, py(lvl) - 7f, 18f, col, condN)
        }
        if (data.size >= 2) {
            val path = Path(); val fill = Path()
            data.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(px(t), py(v)) else path.lineTo(px(t), py(v)) }
            fill.addPath(path); fill.lineTo(px(data.last().first), gy + gh); fill.lineTo(px(data.first().first), gy + gh); fill.close()
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = ACCENT; p.alpha = 28; c.drawPath(fill, p)
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = ACCENT
            p.strokeJoin = Paint.Join.ROUND; c.drawPath(path, p)
        }
        text(c, "−10 min", gx, y + h - 16f, 18f, NA, condN)
        text(c, "nu", gx + gw, y + h - 16f, 18f, NA, condN, Paint.Align.RIGHT)
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
        val socTag = when (socSt) { St.W -> "WAARSCHUWING" to WARN; St.NA -> "GEEN DATA" to NA; else -> null }
        val socTagW = if (socTag != null) measure(socTag.first, 18f, cond, 0.06f) else 0f
        textFit(c, "WOONACCU · LIFEPO4 280 AH", 20f, y2 + 34f, 21f, DIM, ww - 40f - (if (socTag != null) socTagW + 14f else 0f), cond, spacing = 0.08f)
        if (socTag != null) text(c, socTag.first, ww - 20f, y2 + 34f, 18f, socTag.second, cond, Paint.Align.RIGHT, 0.06f)
        // SocRing
        val rcx = 130f; val rcy = y2 + h2 / 2 + 14f; val rr = 88f
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 18f; p.color = LINE
        c.drawCircle(rcx, rcy, rr, p)
        if (soc != null) {
            p.color = if (socSt == St.W) WARN else OK; p.strokeCap = Paint.Cap.ROUND
            rf.set(rcx - rr, rcy - rr, rcx + rr, rcy + rr)
            c.drawArc(rf, -90f, 360f * (soc / 100).toFloat(), false, p)
        }
        text(c, soc?.let { fmt(it) + "%" } ?: "–", rcx, rcy + 18f, 52f, if (soc == null) NA else TXT, cond, Paint.Align.CENTER)
        val laadt = (bA ?: 0.0) > 0.3
        val rows = listOf(
            "spanning" to (fmt(bV, 2)?.let { "$it V" } ?: "–"),
            "stroom" to (bA?.let { (if (it > 0) "+" else "") + fmt(it, 1) + " A" } ?: "–"),
            "vermogen" to (batW?.let { (if (it > 0) "+" else "") + fmt(it) + " W" } ?: "–"),
            "resterend" to (if (laadt) "– laadt" else n("shunt_rest_min", VIC_AGE)?.let { fmtRest(it) } ?: "–"))
        rows.forEachIndexed { i, (k, v) ->
            val ry = y2 + 86f + i * 50f
            text(c, k, 270f, ry, 24f, DIM, condN)
            text(c, v, ww - 24f, ry, 34f, TXT, cond, Paint.Align.RIGHT)
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
