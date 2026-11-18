package nl.sprinterobd.app

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator

/**
 * Android Auto: het dashboard als eigen getekend scherm. Het meten gebeurt in ObdService op de telefoon;
 * dit is alleen een tweede venster op dezelfde Core. Via de navigatiecategorie krijgen we een vrij tekenvlak,
 * dus alleen geschikt voor zelf installeren (Android Auto → ontwikkelaarsinstellingen → Onbekende bronnen).
 */
class DashCarService : CarAppService() {
    // Eigen APK, niet via de Play Store: elke Android Auto-host toestaan.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = DashSession()
}

class DashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = DashScreen(carContext)
}

/** Test: zelfde app in een andere Android Auto-categorie (IoT/POI), om te zien welke categorie wordt getoond. */
open class TestCarService(private val naam: String) : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = TestScreen(carContext, naam)
    }
}
class TestIotService : TestCarService("Miles IoT")
class TestPoiService : TestCarService("Miles POI")

class TestScreen(ctx: CarContext, private val naam: String) : Screen(ctx) {
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val refresh = Runnable { invalidate() }

    override fun onGetTemplate(): Template {
        val c = ObdService.instance?.let { try { it.core } catch (_: Exception) { null } }
        fun v(k: String, unit: String, dec: Int = 0): String =
            (c?.values?.get(k) as? Number)?.toDouble()?.let { String.format(java.util.Locale("nl", "NL"), "%.${dec}f %s", it, unit) } ?: "–"
        val pane = androidx.car.app.model.Pane.Builder()
            .addRow(row("Status", c?.status ?: "meetdienst draait niet"))
            .addRow(row("Snelheid · toerental", v("speed", "km/u") + " · " + v("rpm", "rpm")))
            .addRow(row("Koelvloeistof", v("coolant", "°C")))
            .addRow(row("Woonaccu", v("shunt_soc", "%")))
            .build()
        h.removeCallbacks(refresh); h.postDelayed(refresh, 2000)
        return androidx.car.app.model.PaneTemplate.Builder(pane).setTitle(naam).build()
    }

    private fun row(t: String, s: String) = androidx.car.app.model.Row.Builder().setTitle(t).addText(s).build()
}

class DashScreen(ctx: CarContext) : Screen(ctx) {
    private val renderer = DashRenderer()

    init {
        renderer.onPageChange = { invalidate() }
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        // Meetdienst starten als die nog niet draait (bv. telefoon net opgestart).
        if (ObdService.instance == null) try { ObdService.start(ctx) } catch (_: Exception) { }
    }

    override fun onGetTemplate(): Template {
        val next = DashRenderer.PAGES[(renderer.page + 1) % DashRenderer.PAGES.size]
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("$next ›").setOnClickListener { renderer.nextPage() }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }
}
