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
