package nl.sprinterobd.app

import android.content.Context
import java.io.File

/** Kleine JSON-bestandsopslag in de app (ritten, gezondheid, mode06, events, tank, cfg, trip_live). */
class Store(ctx: Context) {
    private val dir = File(ctx.filesDir, "data").apply { mkdirs() }
    val logDir = File(ctx.filesDir, "logs").apply { mkdirs() }
    private val allowed = setOf("ritten", "health", "mode06", "events", "tank", "cfg", "trip_live")

    @Synchronized
    fun get(name: String, default: String): String {
        if (name !in allowed) return default
        val f = File(dir, "$name.json")
        return try { if (f.exists()) f.readText() else default } catch (e: Exception) { default }
    }

    @Synchronized
    fun set(name: String, json: String) {
        if (name !in allowed) return
        val tmp = File(dir, "$name.json.tmp")
        tmp.writeText(json)
        tmp.renameTo(File(dir, "$name.json"))
    }

    fun exists(name: String) = File(dir, "$name.json").exists()

    fun appendLog(name: String, text: String) {
        try { File(logDir, name.replace(Regex("[^A-Za-z0-9_.-]"), "_")).appendText(text) } catch (_: Exception) {}
    }
}
