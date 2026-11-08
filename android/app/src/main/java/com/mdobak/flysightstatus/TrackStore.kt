package com.mdobak.flysightstatus

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Key values of one jump, from its track. Heights in m, speeds in km/h. */
data class JumpMeta(
    val startMs: Long,
    val durationS: Double,
    /** UTC time of the exit, ms since 1970, or null if there is no exit. */
    val exitMs: Long?,
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
                exitMs = if (track.hasExit) track.exitMs else null,
                durationS = (track.epochMs.last() - track.epochMs.first()) / 1000.0,
                exitAltitude = if (track.hasExit) track.altitude[track.exitIndex] else null,
                freefallS = if (track.hasExit && track.deployIndex > 0) track.t[track.deployIndex] else null,
                maxVKmh = maxV,
                maxHKmh = maxH,
            )
        }
    }
}

/**
 * The header of a track file: the FlySight that recorded it (DEVICE_ID), the
 * recording (SESSION_ID), and the time of the first point. FlySight 1 files
 * have only the time.
 */
data class TrackHeader(val deviceId: String?, val sessionId: String?, val firstMs: Long?) {
    companion object {
        /** Read the header from the start of a file. */
        fun parse(start: String): TrackHeader {
            var device: String? = null
            var session: String? = null
            for (raw in start.lineSequence()) {
                val f = raw.trim().split(',')
                when {
                    f[0] == "\$VAR" && f.getOrNull(1) == "DEVICE_ID" -> device = f.getOrNull(2)?.trim()?.ifEmpty { null }
                    f[0] == "\$VAR" && f.getOrNull(1) == "SESSION_ID" -> session = f.getOrNull(2)?.trim()?.ifEmpty { null }
                    f[0] == "\$GNSS" || f[0].firstOrNull()?.isDigit() == true -> {
                        val time = runCatching { Instant.parse(f[if (f[0] == "\$GNSS") 1 else 0].trim()).toEpochMilli() }.getOrNull()
                        if (time != null) return TrackHeader(device, session, time)
                    }
                }
            }
            return TrackHeader(device, session, null)
        }
    }
}

/**
 * Track files that the app saved on the phone (app storage, tracks/).
 *
 * Sessions from the FlySight have the name of their folder ("yy-MM-dd_HH-mm-ss").
 * Received and imported tracks have the name "in_yy-MM-dd_HH-mm-ss".
 *
 * Each track can have a name and an owner. Each FlySight (DEVICE_ID) can have a
 * default owner: a new track from that FlySight gets it. The app keeps these
 * names. They are not in the files.
 */
object TrackStore {

    private val SESSION = Regex("""/?(\d\d-\d\d-\d\d)/(\d\d-\d\d-\d\d)/TRACK\.CSV""", RegexOption.IGNORE_CASE)
    private val TEMP = Regex("""/?TEMP/(\d{4})/TRACK\.CSV""", RegexOption.IGNORE_CASE)
    private val LOCAL_SESSION = Regex("""(\d\d)-(\d\d)-(\d\d)_(\d\d)-(\d\d)-(\d\d)""")
    private const val META_VERSION = "3"
    private const val OWNERS_V2 = "#v2"
    /** The header is near the start of the file. */
    private const val HEADER_BYTES = 32 * 1024
    private val KEY = DateTimeFormatter.ofPattern("yy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val metaCache: MutableMap<String, JumpMeta?> = java.util.Collections.synchronizedMap(mutableMapOf())
    private val headerCache = ConcurrentHashMap<String, Pair<Long, TrackHeader>>()
    private var names: SharedPreferences? = null
    private var trackOwners: SharedPreferences? = null
    private var deviceOwners: SharedPreferences? = null

    /** Call this before the names are used. */
    fun init(context: Context) {
        if (names != null) return
        val app = context.applicationContext
        names = app.getSharedPreferences("track_names", Context.MODE_PRIVATE)
        trackOwners = app.getSharedPreferences("track_owners", Context.MODE_PRIVATE)
        val devices = app.getSharedPreferences("device_owners", Context.MODE_PRIVATE)
        deviceOwners = devices
        // Version 1 kept only the owners of the FlySights. Give their names to the saved tracks one time.
        if (!devices.getBoolean(OWNERS_V2, false)) {
            if (devices.all.size > 0) for (f in list(app)) addDefaultOwner(f)
            devices.edit().putBoolean(OWNERS_V2, true).apply()
        }
    }

    fun dir(context: Context) = File(context.filesDir, "tracks").apply { mkdirs() }

    /** All saved tracks, the newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.sortedWith(compareByDescending<File> { sortKey(it) }) ?: emptyList()

    /**
     * "yy-MM-dd_HH-mm-ss" (UTC). Sessions use their folder name. Other files use the
     * time of their first point, thus a received jump sorts near the jumps of the same day.
     */
    fun sortKey(file: File): String =
        if (LOCAL_SESSION.matches(file.nameWithoutExtension)) file.nameWithoutExtension
        else KEY.format(Instant.ofEpochMilli(header(file).firstMs ?: file.lastModified()))

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
        addDefaultOwner(file)
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

    /**
     * Save a received track with a name that cannot be the same as a session name.
     * Call on a background thread.
     */
    fun saveReceived(context: Context, data: ByteArray, track: Track, header: TrackHeader): File {
        val base = "in_" + KEY.format(Instant.ofEpochMilli(header.firstMs ?: track.epochMs.first()))
        var name = "$base.csv"
        var n = 2
        while (File(dir(context), name).exists()) name = "${base}_${n++}.csv"
        return saveParsed(context, name, data, track)
    }

    /** The saved track of the same recording, or null. */
    fun findSame(context: Context, header: TrackHeader): File? = list(context).firstOrNull { f ->
        val h = header(f)
        if (header.sessionId != null) h.sessionId == header.sessionId && h.deviceId == header.deviceId
        else h.sessionId == null && header.firstMs != null && h.firstMs == header.firstMs
    }

    /** The session key "yy-MM-dd_HH-mm-ss" of the newest saved session, or null. */
    fun newestSession(context: Context): String? =
        list(context).map { it.nameWithoutExtension }.filter { LOCAL_SESSION.matches(it) }.maxOrNull()

    fun delete(file: File) {
        file.delete()
        metaFile(file).delete()
        metaCache.remove(file.path)
        headerCache.remove(file.path)
        names?.edit()?.remove(file.name)?.apply()
        trackOwners?.edit()?.remove(file.name)?.apply()
    }

    /** The name of the jump: the name from the user, or "7 Nov 2026, 14:10". */
    fun title(file: File): String = customName(file) ?: dateTitle(file)

    /** "7 Nov 2026, 14:10" from the time of the jump. */
    fun dateTitle(file: File): String {
        val m = LOCAL_SESSION.matchEntire(sortKey(file)) ?: return file.nameWithoutExtension
        val (y, mo, d, h, mi) = m.destructured
        val month = MONTHS.getOrNull(mo.toInt() - 1) ?: mo
        return "${d.toInt()} $month 20$y, $h:$mi"
    }

    /** The time of the jump: "14:10", or "7 Nov 14:10" if [withDate]. */
    fun timeLabel(file: File, withDate: Boolean): String {
        val m = LOCAL_SESSION.matchEntire(sortKey(file)) ?: return file.nameWithoutExtension.take(12)
        val (_, mo, d, h, mi) = m.destructured
        val time = "$h:$mi"
        return if (withDate) "${d.toInt()} ${MONTHS.getOrNull(mo.toInt() - 1) ?: mo} $time" else time
    }

    /**
     * Short labels for a comparison. A jump with a name uses its name. If the jumps
     * are from different FlySights, a jump uses the name of its owner. Other jumps
     * use their time. Labels that are the same get the time, then a number.
     */
    fun labels(files: List<File>): List<String> {
        val withDate = files.map { dateOf(it) }.distinct().size > 1
        val times = files.map { timeLabel(it, withDate) }
        val ownerNames = files.map { owner(it) }
        val byOwner = ownerNames.distinct().size > 1
        val custom = files.map { customName(it) }
        val first = files.indices.map { custom[it] ?: (if (byOwner) ownerNames[it] else null) ?: times[it] }
        val second = first.mapIndexed { i, label ->
            if (custom[i] == null && label != times[i] && first.count { it == label } > 1) "$label ${times[i]}" else label
        }
        return second.mapIndexed { i, label ->
            val same = second.withIndex().filter { it.value == label }.map { it.index }
            if (same.size > 1) "$label (${same.indexOf(i) + 1})" else label
        }
    }

    /** Date part of the jump time "yy-MM-dd", or null. */
    fun dateOf(file: File): String? = LOCAL_SESSION.matchEntire(sortKey(file))?.let {
        "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}"
    }

    /** True for a session that the app got from the FlySight. */
    fun isSession(file: File) = LOCAL_SESSION.matches(file.nameWithoutExtension)

    private val LOCAL_TEMP = Regex("""TEMP_(\d{4})""")

    /**
     * The log folder on the FlySight, without the leading slash, or null for an
     * imported file. "26-11-07_12-34-56.csv" gives "26-11-07/12-34-56".
     */
    fun remoteFolder(file: File): String? {
        val name = file.nameWithoutExtension
        LOCAL_SESSION.matchEntire(name)?.let { return name.replace('_', '/') }
        LOCAL_TEMP.matchEntire(name)?.let { return "temp/${it.groupValues[1]}" }
        return null
    }

    // ---- Names ----

    /** The name that the user gave to the jump, or null. */
    fun customName(file: File): String? = names?.getString(file.name, null)?.ifBlank { null }

    /** Set the name of the jump. An empty name removes it. */
    fun setCustomName(file: File, name: String?) {
        val edit = names?.edit() ?: return
        if (name.isNullOrBlank()) edit.remove(file.name) else edit.putString(file.name, name.trim())
        edit.apply()
    }

    fun deviceId(file: File): String? = header(file).deviceId

    /** The owner of the jump, or null. */
    fun owner(file: File): String? = trackOwners?.getString(file.name, null)?.ifBlank { null }

    /** Set the owner of one jump. An empty name removes it. */
    fun setOwner(file: File, name: String?) {
        val edit = trackOwners?.edit() ?: return
        if (name.isNullOrBlank()) edit.remove(file.name) else edit.putString(file.name, name.trim())
        edit.apply()
    }

    /** The default owner of a FlySight: new jumps from it get this owner. */
    fun deviceOwner(deviceId: String): String? =
        deviceId.takeIf { it != OWNERS_V2 }?.let { deviceOwners?.getString(it, null)?.ifBlank { null } }

    /** Set the default owner of a FlySight. It does not change the saved jumps. An empty name removes it. */
    fun setDeviceOwner(deviceId: String, name: String?) {
        val edit = deviceOwners?.edit() ?: return
        if (name.isNullOrBlank()) edit.remove(deviceId) else edit.putString(deviceId, name.trim())
        edit.apply()
    }

    /** A jump without an owner gets the default owner of its FlySight. */
    fun addDefaultOwner(file: File) {
        if (trackOwners == null || owner(file) != null) return
        deviceId(file)?.let { deviceOwner(it) }?.let { setOwner(file, it) }
    }

    /** The saved jumps from a FlySight. */
    fun jumpsFrom(context: Context, deviceId: String): List<File> = list(context).filter { deviceId(it) == deviceId }

    /** The FlySights that the app got sessions from: the FlySights of the user. */
    fun ownDevices(context: Context): Set<String> = list(context).filter { isSession(it) }.mapNotNull { deviceId(it) }.toSet()

    /**
     * Who made the jump: the name of the owner, "Received" for a jump from a
     * different FlySight, or null for a jump of the user.
     */
    fun who(file: File, ownDevices: Set<String>): String? {
        owner(file)?.let { return it }
        val id = deviceId(file) ?: return null
        return if (!isSession(file) && id !in ownDevices) "Received" else null
    }

    // ---- Header ----

    /** The header of a saved track. The app reads it one time for each version of the file. */
    fun header(file: File): TrackHeader {
        val stamp = file.lastModified() * 31 + file.length()
        headerCache[file.path]?.let { if (it.first == stamp) return it.second }
        val header = try {
            file.inputStream().use { input ->
                val buf = ByteArray(HEADER_BYTES)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                TrackHeader.parse(String(buf, 0, n, Charsets.ISO_8859_1))
            }
        } catch (_: Exception) {
            TrackHeader(null, null, null)
        }
        headerCache[file.path] = stamp to header
        return header
    }

    // ---- Metadata ----

    private fun metaFile(file: File) = File(file.parentFile, file.nameWithoutExtension + ".meta")

    /** The metadata if it is known now, without reading the track. */
    fun cachedMeta(file: File): JumpMeta? = metaCache[file.path] ?: readMeta(file)?.also { metaCache[file.path] = it }

    /** The metadata. If it is not known, read the track. Call on a background thread. */
    fun meta(file: File): JumpMeta? = cachedMeta(file) ?: try {
        JumpMeta.of(Track.load(file)).also {
            writeMeta(file, it)
            metaCache[file.path] = it
        }
    } catch (_: Exception) {
        null
    }

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
                exitMs = p.getProperty("exitTime")?.toLongOrNull(),
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
        meta.exitMs?.let { p["exitTime"] = it.toString() }
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
