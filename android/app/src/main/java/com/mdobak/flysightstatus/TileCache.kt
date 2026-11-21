package com.mdobak.flysightstatus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Map tiles and terrain tiles of the saved jumps, in app storage (map-cache/).
 *
 * A tile stays while a saved jump is near it, thus a new jump at the same place
 * uses the same tiles. The tiles do not expire. When no saved jump is near a
 * tile, [clean] deletes it: after each delete of a jump, and at the start of the app.
 *
 * The name of a tile is "source/z/x/y.ext". If the server does not have a tile
 * (HTTP 404), an empty file "source/z/x/y.ext.none" prevents more requests.
 */
object TileCache {

    private const val USER_AGENT = "FlySight-mdobak/1.0 (Android; private app for FlySight 2)"
    /** Tiles nearer than this to a jump stay (m). The 3D view shows at least 1.5 km around a jump. */
    private const val KEEP_MARGIN_M = 3000.0
    private val TILE = Regex("""(\d+)/(\d+)/(\d+)\.\w+""")
    /** The folders of the old cache in cacheDir. */
    private val OLD_DIRS = listOf("tiles", "terrain", "s2cloudless", "opentopomap")

    private val cleaner = Executors.newSingleThreadExecutor()
    private val cleanQueued = AtomicBoolean(false)

    private const val DIR = "map-cache"
    private const val NONE = ".none"

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** The tile from app storage, or from the network. Call on a background thread. */
    fun bitmap(context: Context, name: String, url: String): Bitmap? {
        val file = File(dir(context), name)
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        val none = File(file.path + NONE)
        if (none.exists()) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        return try {
            val code = connection.responseCode
            if (code == 404) {
                none.parentFile?.mkdirs()
                none.createNewFile()
            }
            if (code != 200) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".part")
            temp.writeBytes(bytes)
            temp.renameTo(file)
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Delete the tiles that no saved jump is near, on a background thread. */
    fun scheduleClean(context: Context) = scheduleClean(context.filesDir, context.cacheDir)

    /** [scheduleClean] with the filesDir and the cacheDir of the app. */
    fun scheduleClean(filesDir: File, cacheDir: File) {
        if (!cleanQueued.compareAndSet(false, true)) return
        cleaner.execute {
            cleanQueued.set(false)
            runCatching { clean(filesDir, cacheDir) }
        }
    }

    /**
     * Delete all the tiles that touch the area, from all the sources. The area is
     * in Web Mercator units (256 for the world). Call on a background thread.
     */
    fun deleteArea(context: Context, x0: Double, y0: Double, x1: Double, y1: Double) {
        val area = Box(x0, y0, x1, y1)
        deleteTiles(dir(context)) { area.intersects(it) }
    }

    /** A box in Web Mercator units (256 for the world). */
    private class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        fun intersects(o: Box) = x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1
    }

    private fun clean(filesDir: File, cacheDir: File) {
        for (name in OLD_DIRS) File(cacheDir, name).deleteRecursively()
        val root = File(filesDir, DIR)
        if (!root.exists()) return
        val areas = TrackStore.list(File(filesDir, "tracks")).mapNotNull { file ->
            val m = TrackStore.meta(file) ?: return@mapNotNull null
            if (m.minLat == null || m.maxLat == null || m.minLon == null || m.maxLon == null) return@mapNotNull null
            val x0 = lonToX(m.minLon)
            val x1 = lonToX(m.maxLon)
            val y0 = latToY(m.maxLat)
            val y1 = latToY(m.minLat)
            val unitM = 40075016.686 * cos(Math.toRadians((m.minLat + m.maxLat) / 2)) / 256
            val margin = max(KEEP_MARGIN_M / unitM, max(x1 - x0, y1 - y0) * 0.5)
            Box(x0 - margin, y0 - margin, x1 + margin, y1 + margin)
        }
        deleteTiles(root) { tile -> areas.none { it.intersects(tile) } }
    }

    /** Delete the tiles in [root] if [delete] is true for their box. Also delete bad files and empty folders. */
    private fun deleteTiles(root: File, delete: (Box) -> Boolean) {
        if (!root.exists()) return
        val rootPath = root.path.length + 1
        root.walkBottomUp().forEach { f ->
            if (f == root) return@forEach
            if (f.isDirectory) {
                if (f.list()?.isEmpty() == true) f.delete()
                return@forEach
            }
            // "source/z/x/y.ext"
            val rel = f.path.substring(rootPath)
            val m = TILE.find(rel.substringAfter('/'))
            if (m == null || f.name.endsWith(".part")) {
                f.delete()
                return@forEach
            }
            val z = m.groupValues[1].toInt()
            val size = 256 / 2.0.pow(z)
            val x = m.groupValues[2].toInt() * size
            val y = m.groupValues[3].toInt() * size
            if (delete(Box(x, y, x + size, y + size))) f.delete()
        }
    }

    private fun lonToX(lon: Double) = (lon + 180.0) / 360.0 * 256

    private fun latToY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 256
    }
}
