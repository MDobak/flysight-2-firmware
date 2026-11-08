package com.example.flysightstatus

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Executors

/** Key values of one jump, from its track. Heights in m, speeds in km/h. */
data class JumpMeta(
    val startMs: Long,
    val durationS: Double,
    val exitAltitude: Double?,
    val freefallS: Double?,
    val maxVKmh: Double,
    val maxHKmh: Double,
) {
    /** "Exit 4 000 m · Freefall 1:02 · 190 km/h", or a short text if there is no exit. */
    fun summary(): String {
        if (exitAltitude == null) return "No exit found · ${Track.formatDuration(durationS)} recorded"
        return buildList {
            add("Exit ${TrackStore.formatInt(exitAltitude)} m")
            freefallS?.let { add("Freefall ${Track.formatDuration(it)}") }
            add("${TrackStore.formatInt(maxVKmh)} km/h")
        }.joinToString(" · ")
    }

    companion object {
        fun of(track: Track): JumpMeta {
            val from = if (track.hasExit) track.exitIndex else 0
            val to = if (track.deployIndex > 0) track.deployIndex else track.size - 1
            var maxV = 0.0
            var maxH = 0.0
            for (i in from..to) {
                maxV = maxOf(maxV, track.velD[i] * 3.6)
                maxH = maxOf(maxH, track.hSpeed[i] * 3.6)
            }
            return JumpMeta(
                startMs = track.epochMs.first(),
                durationS = (track.epochMs.last() - track.epochMs.first()) / 1000.0,
                exitAltitude = if (track.hasExit) track.altitude[track.exitIndex] else null,
                freefallS = if (track.hasExit && track.deployIndex > 0) track.t[track.deployIndex] else null,
                maxVKmh = maxV,
                maxHKmh = maxH,
            )
        }
    }
}

/** Track files that the app saved on the phone (app storage, tracks/). */
object TrackStore {

    private val SESSION = Regex("""/?(\d\d-\d\d-\d\d)/(\d\d-\d\d-\d\d)/TRACK\.CSV""", RegexOption.IGNORE_CASE)
    private val TEMP = Regex("""/?TEMP/(\d{4})/TRACK\.CSV""", RegexOption.IGNORE_CASE)
    private val LOCAL_SESSION = Regex("""(\d\d)-(\d\d)-(\d\d)_(\d\d)-(\d\d)-(\d\d)""")
    private const val META_VERSION = "2"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val metaCache: MutableMap<String, JumpMeta?> = java.util.Collections.synchronizedMap(mutableMapOf())

    fun dir(context: Context) = File(context.filesDir, "tracks").apply { mkdirs() }

    /** All saved tracks, the newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedWith(compareByDescending<File> { sortKey(it) }) ?: emptyList()

    /** Sessions sort by their folder name. Other files sort by the time of the import. */
    fun sortKey(file: File): String =
        if (LOCAL_SESSION.matches(file.nameWithoutExtension)) file.nameWithoutExtension
        else LocalDateTime.ofEpochSecond(file.lastModified() / 1000, 0, ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yy-MM-dd_HH-mm-ss"))

    /**
     * File name on the phone for a track on the FlySight.
     * "/26-11-07/12-34-56/TRACK.CSV" gives "26-11-07_12-34-56.csv".
     */
    fun localName(remotePath: String): String {
        SESSION.matchEntire(remotePath)?.let { return "${it.groupValues[1]}_${it.groupValues[2]}.csv" }
        TEMP.matchEntire(remotePath)?.let { return "TEMP_${it.groupValues[1]}.csv" }
        return remotePath.trim('/').replace('/', '_').replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .removeSuffix(".CSV").removeSuffix(".csv") + ".csv"
    }

    fun file(context: Context, remotePath: String) = File(dir(context), localName(remotePath))

    fun save(context: Context, name: String, data: ByteArray): File {
        val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").let { if (it.endsWith(".csv")) it else "$it.csv" }
        val file = File(dir(context), safe)
        file.writeBytes(data)
        metaFile(file).delete()
        metaCache.remove(file.path)
        return file
    }

    /** Save a track that is already parsed, and its metadata. Call on a background thread. */
    fun saveParsed(context: Context, name: String, data: ByteArray, track: Track): File {
        val file = save(context, name, data)
        val meta = JumpMeta.of(track)
        writeMeta(file, meta)
        main.post { metaCache[file.path] = meta }
        return file
    }

    /** The session key "yy-MM-dd_HH-mm-ss" of the newest saved session, or null. */
    fun newestSession(context: Context): String? =
        list(context).map { it.nameWithoutExtension }.filter { LOCAL_SESSION.matches(it) }.maxOrNull()

    fun delete(file: File) {
        file.delete()
        metaFile(file).delete()
        metaCache.remove(file.path)
    }

    /** "7 Nov 2026, 14:10" from the session folder name, or the file name. */
    fun title(file: File): String {
        val m = LOCAL_SESSION.matchEntire(file.nameWithoutExtension) ?: return file.nameWithoutExtension
        val (y, mo, d, h, mi) = m.destructured
        val month = MONTHS.getOrNull(mo.toInt() - 1) ?: mo
        return "${d.toInt()} $month 20$y, $h:$mi"
    }

    /** Short label for a legend: "14:10", or "7 Nov 14:10" if [withDate]. */
    fun shortLabel(file: File, withDate: Boolean): String {
        val m = LOCAL_SESSION.matchEntire(file.nameWithoutExtension) ?: return file.nameWithoutExtension.take(12)
        val (_, mo, d, h, mi) = m.destructured
        val time = "$h:$mi"
        return if (withDate) "${d.toInt()} ${MONTHS.getOrNull(mo.toInt() - 1) ?: mo} $time" else time
    }

    /** Date part of a session file name, or null. */
    fun dateOf(file: File): String? = LOCAL_SESSION.matchEntire(file.nameWithoutExtension)?.let {
        "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}"
    }

    fun isSession(file: File) = LOCAL_SESSION.matches(file.nameWithoutExtension)

    // ---- Metadata ----

    private fun metaFile(file: File) = File(file.parentFile, file.nameWithoutExtension + ".meta")

    /** The metadata if it is known now, without reading the track. */
    fun cachedMeta(file: File): JumpMeta? = metaCache[file.path] ?: readMeta(file)?.also { metaCache[file.path] = it }

    /** Calculate the metadata on a background thread, then call [done] on the main thread. */
    fun loadMeta(file: File, done: (JumpMeta?) -> Unit) {
        cachedMeta(file)?.let { return done(it) }
        worker.execute {
            val meta = try {
                JumpMeta.of(Track.load(file)).also { writeMeta(file, it) }
            } catch (_: Exception) {
                null
            }
            main.post {
                metaCache[file.path] = meta
                done(meta)
            }
        }
    }

    private fun readMeta(file: File): JumpMeta? {
        val f = metaFile(file)
        if (!f.exists() || f.lastModified() < file.lastModified()) return null
        return try {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            if (p.getProperty("version") != META_VERSION) return null
            JumpMeta(
                startMs = p.getProperty("start").toLong(),
                durationS = p.getProperty("duration").toDouble(),
                exitAltitude = p.getProperty("exit")?.toDoubleOrNull(),
                freefallS = p.getProperty("freefall")?.toDoubleOrNull(),
                maxVKmh = p.getProperty("maxV").toDouble(),
                maxHKmh = p.getProperty("maxH").toDouble(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(file: File, meta: JumpMeta) {
        val p = Properties()
        p["version"] = META_VERSION
        p["start"] = meta.startMs.toString()
        p["duration"] = meta.durationS.toString()
        meta.exitAltitude?.let { p["exit"] = it.toString() }
        meta.freefallS?.let { p["freefall"] = it.toString() }
        p["maxV"] = meta.maxVKmh.toString()
        p["maxH"] = meta.maxHKmh.toString()
        metaFile(file).outputStream().use { p.store(it, null) }
    }

    /** "4 000" with a narrow space between the groups. */
    fun formatInt(value: Double): String = String.format(Locale.US, "%,d", Math.round(value)).replace(',', ' ')

    fun formatUtc(ms: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.UK)
        .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
