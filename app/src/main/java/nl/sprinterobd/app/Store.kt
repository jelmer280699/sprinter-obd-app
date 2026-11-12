package nl.sprinterobd.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File

/**
 * Kleine JSON-bestandsopslag in de app (ritten, gezondheid, mode06, events, tank, cfg, trip_live, export)
 * plus logbestanden en export naar Downloads/SprinterOBD.
 */
class Store(ctx: Context) {
    private val app = ctx.applicationContext
    private val dir = File(app.filesDir, "data").apply { mkdirs() }
    val logDir = File(app.filesDir, "logs").apply { mkdirs() }
    private val allowed = setOf("ritten", "health", "mode06", "events", "tank", "cfg", "trip_live", "export")

    init {
        // Eerste keer met exportbeheer: alle bestaande logs gelden als al (handmatig) geëxporteerd,
        // zodat ze niet dubbel in Downloads komen (gebruiker exporteerde alle ritten t/m 9 nov zelf).
        if (!File(dir, "export.json").exists()) {
            val names = logDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted() ?: emptyList()
            set("export", JSONArray(names).toString())
        }
    }

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

    private fun safe(n: String) = n.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    fun appendLog(name: String, text: String) {
        try { File(logDir, safe(name)).appendText(text) } catch (_: Exception) {}
    }

    /** Namen van logbestanden die al naar Downloads/SprinterOBD zijn geëxporteerd. */
    @Synchronized
    fun exported(): Set<String> = try {
        val a = JSONArray(get("export", "[]")); (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (_: Exception) { emptySet() }

    @Synchronized
    private fun markExported(name: String) {
        val s = exported().toMutableSet(); s.add(name)
        set("export", JSONArray(s.sorted()).toString())
    }

    /** Schrijf een bestand naar Downloads/SprinterOBD (geen extra machtiging nodig). Geeft pad of "fout: ...". */
    fun saveDownload(name: String, bytes: ByteArray): String = try {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (name.endsWith(".csv")) "text/csv" else "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SprinterOBD")
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("kon bestand niet aanmaken")
        app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        "Downloads/SprinterOBD/$name"
    } catch (e: Exception) { "fout: ${e.message}" }

    /** Exporteer een logbestand naar Downloads en onthoud dat het geëxporteerd is. */
    @Synchronized
    fun exportLog(name: String): String {
        val f = File(logDir, safe(name))
        if (!f.exists()) return "fout: bestand bestaat niet"
        val r = saveDownload(f.name, f.readBytes())
        if (!r.startsWith("fout")) markExported(f.name)
        return r
    }

    /** Exporteer alle logbestanden die nog niet geëxporteerd zijn (behalve `skip`, bijv. de lopende rit). */
    fun exportPending(skip: Set<String> = emptySet()): List<String> {
        val done = exported()
        return logDir.listFiles()?.filter { it.isFile && it.name !in done && it.name !in skip && it.length() > 0 }
            ?.sortedBy { it.lastModified() }?.map { exportLog(it.name) } ?: emptyList()
    }
}
