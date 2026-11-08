package com.example.flysightstatus

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One GNSS track from TRACK.CSV.
 *
 * Time [t] is in seconds from the exit. If the track has no exit, time is in
 * seconds from the first point. Speeds are in m/s. Heights are in m.
 */
class Track(
    val name: String,
    /** UTC time of each point, ms since 1970. */
    val epochMs: LongArray,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val hMsl: DoubleArray,
    val velN: DoubleArray,
    val velE: DoubleArray,
    val velD: DoubleArray,
) {
    val size get() = epochMs.size

    /** Ground height: the height at the last point (the landing). */
    val groundMsl = hMsl.last()

    /** Index of the point at or after the exit, or -1 if no exit was found. */
    val exitIndex: Int

    /** UTC time of the exit (or of the first point), ms since 1970. */
    val zeroMs: Long

    val t: DoubleArray
    val altitude = DoubleArray(size) { hMsl[it] - groundMsl }
    val hSpeed = DoubleArray(size) { hypot(velN[it], velE[it]) }
    val totalSpeed = DoubleArray(size) { sqrt(velN[it] * velN[it] + velE[it] * velE[it] + velD[it] * velD[it]) }

    /** Horizontal speed / vertical speed. NaN when the vertical speed is too small. */
    val glideRatio = DoubleArray(size) { if (velD[it] > MIN_GLIDE_VEL_D) hSpeed[it] / velD[it] else Double.NaN }

    init {
        val exit = findExit()
        exitIndex = exit?.first ?: -1
        zeroMs = exit?.second ?: epochMs.first()
        t = DoubleArray(size) { (epochMs[it] - zeroMs) / 1000.0 }
    }

    /**
     * Find the exit from the aircraft.
     *
     * Find the first point where the vertical speed is more than [EXIT_VEL_D]
     * and stays more than half of it for [EXIT_CONFIRM_S]. At the exit, the
     * vertical speed is approximately 0 and the acceleration is
     * approximately g. Thus the exit occurred velD / g before that point.
     */
    private fun findExit(): Pair<Int, Long>? {
        for (i in 0 until size) {
            if (velD[i] < EXIT_VEL_D || altitude[i] < EXIT_MIN_ALTITUDE) continue
            var confirmed = true
            var j = i
            while (j < size && epochMs[j] - epochMs[i] < EXIT_CONFIRM_S * 1000) {
                if (velD[j] < EXIT_VEL_D / 2) {
                    confirmed = false
                    break
                }
                j++
            }
            if (!confirmed || j == size) continue
            val exitMs = epochMs[i] - (velD[i] / G * 1000).toLong()
            var k = i
            while (k > 0 && epochMs[k - 1] >= exitMs) k--
            return k to exitMs
        }
        return null
    }

    /** Index of the point nearest to time [time] (s). */
    fun indexAt(time: Double): Int {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (t[mid] < time) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(t[lo - 1] - time) < abs(t[lo] - time)) lo--
        return lo
    }

    val hasExit get() = exitIndex >= 0

    /**
     * Index of the canopy opening, or -1. After the exit, find the first point
     * where the vertical speed stays less than [DEPLOY_VEL_D] for
     * [DEPLOY_CONFIRM_S]. Then go back to the start of the deceleration.
     */
    val deployIndex: Int by lazy {
        if (!hasExit) return@lazy -1
        var start = exitIndex
        while (start < size && velD[start] < EXIT_VEL_D) start++
        for (i in start until size) {
            if (velD[i] >= DEPLOY_VEL_D) continue
            var j = i
            var confirmed = true
            while (j < size && epochMs[j] - epochMs[i] < DEPLOY_CONFIRM_S * 1000) {
                if (velD[j] >= DEPLOY_VEL_D) {
                    confirmed = false
                    break
                }
                j++
            }
            if (!confirmed) continue
            var k = i
            while (k > start && velD[k - 1] > velD[k]) k--
            return@lazy k
        }
        -1
    }

    fun startText(): String = DATE_TIME.format(Instant.ofEpochMilli(epochMs.first()))

    fun summary(): String = buildString {
        append("${startText()} UTC · ${formatDuration((epochMs.last() - epochMs.first()) / 1000.0)} · $size points\n")
        if (hasExit) {
            append(String.format(java.util.Locale.US, "Exit: %s UTC at %.0f m above landing", TIME.format(Instant.ofEpochMilli(zeroMs)), altitude[exitIndex]))
        } else {
            append("No exit found. Time is from the first point.")
        }
        val from = if (hasExit) exitIndex else 0
        fun max(a: DoubleArray) = (from until size).maxOfOrNull { a[it] } ?: 0.0
        append(String.format(
            java.util.Locale.US,
            "\nMaximum: horizontal %.0f km/h · vertical %.0f km/h · total %.0f km/h",
            max(hSpeed) * 3.6, max(velD) * 3.6, max(totalSpeed) * 3.6,
        ))
    }

    companion object {
        const val G = 9.80665
        private const val EXIT_VEL_D = 10.0
        private const val EXIT_CONFIRM_S = 10
        private const val EXIT_MIN_ALTITUDE = 300.0
        private const val MIN_GLIDE_VEL_D = 0.5
        private const val DEPLOY_VEL_D = 8.0
        private const val DEPLOY_CONFIRM_S = 5

        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

        fun formatDuration(seconds: Double): String {
            val s = seconds.toLong()
            return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
        }

        fun load(file: File, name: String = file.nameWithoutExtension): Track =
            parse(name, file.readText(Charsets.ISO_8859_1))

        /**
         * Parse TRACK.CSV of FlySight 2 ($GNSS rows) or a CSV of FlySight 1
         * (a header row with time,lat,lon,hMSL,velN,velE,velD,...).
         */
        fun parse(name: String, text: String): Track {
            val rows = ArrayList<List<String>>()
            var columns: List<String>? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val fields = line.split(',').map { it.trim() }
                when {
                    fields[0] == "\$COL" && fields.getOrNull(1) == "GNSS" -> columns = fields.drop(2)
                    fields[0] == "\$GNSS" -> rows += fields.drop(1)
                    fields[0].startsWith("$") -> Unit
                    columns == null && "lat" in fields && "velD" in fields -> columns = fields
                    columns != null && fields[0].firstOrNull()?.isDigit() == true -> rows += fields
                }
            }
            val cols = columns ?: listOf("time", "lat", "lon", "hMSL", "velN", "velE", "velD")
            fun index(name: String) = cols.indexOf(name).also {
                if (it < 0) throw IllegalArgumentException("The file has no $name column")
            }
            val iTime = index("time")
            val iLat = index("lat")
            val iLon = index("lon")
            val iH = index("hMSL")
            val iN = index("velN")
            val iE = index("velE")
            val iD = index("velD")

            val points = rows.mapNotNull { f ->
                try {
                    doubleArrayOf(
                        Instant.parse(f[iTime]).toEpochMilli().toDouble(),
                        f[iLat].toDouble(), f[iLon].toDouble(), f[iH].toDouble(),
                        f[iN].toDouble(), f[iE].toDouble(), f[iD].toDouble(),
                    )
                } catch (_: Exception) {
                    null
                }
            }.sortedBy { it[0] }
            if (points.size < 2) throw IllegalArgumentException("The file has no GNSS data")

            return Track(
                name,
                LongArray(points.size) { points[it][0].toLong() },
                DoubleArray(points.size) { points[it][1] },
                DoubleArray(points.size) { points[it][2] },
                DoubleArray(points.size) { points[it][3] },
                DoubleArray(points.size) { points[it][4] },
                DoubleArray(points.size) { points[it][5] },
                DoubleArray(points.size) { points[it][6] },
            )
        }
    }
}
