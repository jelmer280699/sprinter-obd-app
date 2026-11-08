package nl.sprinterobd.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Voorgronddienst: houdt de meetkern draaiend met scherm uit of een andere app op de voorgrond.
 * Toont een vaste melding met rit/temperatuur en geeft alarmmeldingen met geluid bij rood.
 * Stopt zichzelf als de auto 15 minuten niet antwoordt en er geen rit loopt.
 */
class ObdService : Service(), Core.Listener {

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_ALARM = "alarm"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "nl.sprinterobd.app.STOP"
        @Volatile var instance: ObdService? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, ObdService::class.java))
        }
    }

    inner class LocalBinder : Binder() { val service get() = this@ObdService }
    private val binder = LocalBinder()

    lateinit var core: Core
    lateinit var store: Store
    var uiListener: Core.Listener? = null
    private var wake: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        try { Dtc.load(assets.open("www/dtc.json").bufferedReader().readText()) } catch (_: Exception) {}
        core = Core(this, store, this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Meting actief", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarmen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rode alarmen (temperatuur, turbodruk, accu) en nieuwe foutcodes"
            enableVibration(true)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, statusNotification("Verbinden met de VEEPEAK…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SprinterOBD:meting")
            wake?.acquire(12 * 60 * 60 * 1000L)  // max 12 uur, wordt bij stoppen vrijgegeven
        }
        core.start()
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, 2000)
        return START_STICKY
    }

    private val refresher = object : Runnable {
        override fun run() {
            val v = core.values
            val txt = when {
                core.status != "verbonden" -> "Wacht op de auto… (${core.status})"
                core.tripActive -> {
                    val t = core.tripSummary(core.trip)
                    "Rit ${t.optDouble("afstand_km")} km · koelv. ${(v["coolant"] as? Double)?.toInt() ?: "–"} °C · olie ${(v["oil"] as? Double)?.toInt() ?: "–"} °C"
                }
                else -> "Verbonden · motor uit · wacht op start"
            }
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, statusNotification(txt))
            handler.postDelayed(this, 5000)
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun statusNotification(text: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, ObdService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Sprinter OBD")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, "Stoppen", stop).build())
            .build()
    }

    fun stopAll() {
        core.stop()
        handler.removeCallbacks(refresher)
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        core.stop()
        handler.removeCallbacks(refresher)
        try { wake?.release() } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // ---- Core.Listener ----
    override fun onAlarm(id: Int, title: String, text: String) {
        val n = Notification.Builder(this, CH_ALARM).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(openApp())
            .setCategory(Notification.CATEGORY_ALARM).build()
        try { getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) {}
        uiListener?.onAlarm(id, title, text)
    }

    override fun onEvent(state: String, msg: String) { uiListener?.onEvent(state, msg) }

    override fun onIdleTooLong() { handler.post { stopAll() } }
}
