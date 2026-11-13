package com.mdobak.flysightstatus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Export of tracks to a KML file for Google Earth. Each track has the flight
 * path with a curtain to the ground, the path on the ground, and the exit,
 * the canopy opening and the landing.
 */
object KmlExport {

    const val MIME = "application/vnd.google-earth.kml+xml"
    private const val PRE_EXIT_S = 10.0

    /** Write the KML file to the export folder and return its content URI. */
    fun export(context: Context, tracks: List<Track>, names: List<String>, title: String): Uri {
        val dir = File(context.cacheDir, ExportProvider.DIR)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val fileName = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "track" } + ".kml"
        val file = File(dir, fileName)
        file.writeText(kml(tracks, names, title))
        return ExportProvider.uri(context, file)
    }

    fun kml(tracks: List<Track>, names: List<String>, title: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<kml xmlns="http://www.opengis.net/kml/2.2">""").append('\n')
        append("<Document>\n<name>").append(escape(title)).append("</name>\n<open>1</open>\n")
        lookAt(tracks)?.let { append(it) }
        for ((i, track) in tracks.withIndex()) {
            val color = abgr(Ui.TRACK_COLORS_DARK[i % Ui.TRACK_COLORS_DARK.size])
            val first = track.indexBeforeExit(PRE_EXIT_S)
            append("<Folder>\n<name>").append(escape(names.getOrElse(i) { track.name })).append("</name>\n<open>1</open>\n")
            append("<description>").append(escape(track.summary())).append("</description>\n")

            append("<Placemark>\n<name>Flight</name>\n")
            append("<Style><LineStyle><color>ff$color</color><width>3</width></LineStyle>")
            append("<PolyStyle><color>40$color</color><outline>0</outline></PolyStyle></Style>\n")
            append("<LineString><extrude>1</extrude><altitudeMode>absolute</altitudeMode><coordinates>\n")
            coordinates(track, first, withHeight = true)
            append("</coordinates></LineString>\n</Placemark>\n")

            append("<Placemark>\n<name>Ground track</name>\n<visibility>1</visibility>\n")
            append("<Style><LineStyle><color>c0$color</color><width>1.5</width></LineStyle></Style>\n")
            append("<LineString><tessellate>1</tessellate><altitudeMode>clampToGround</altitudeMode><coordinates>\n")
            coordinates(track, first, withHeight = false)
            append("</coordinates></LineString>\n</Placemark>\n")

            if (track.hasExit) point("Exit", track, track.exitIndex, color)
            if (track.deployIndex >= 0) point("Canopy opening", track, track.deployIndex, color)
            point("Landing", track, track.size - 1, color)
            append("</Folder>\n")
        }
        append("</Document>\n</kml>\n")
    }

    private fun StringBuilder.coordinates(track: Track, first: Int, withHeight: Boolean) {
        for (i in first until track.size) {
            if (withHeight) append(String.format(Locale.US, "%.7f,%.7f,%.1f\n", track.lon[i], track.lat[i], track.hMsl[i]))
            else append(String.format(Locale.US, "%.7f,%.7f\n", track.lon[i], track.lat[i]))
        }
    }

    private fun StringBuilder.point(name: String, track: Track, i: Int, color: String) {
        append("<Placemark>\n<name>").append(escape(name)).append("</name>\n")
        append(String.format(Locale.US, "<description>%.1f s · %.0f m above the landing</description>\n", track.t[i], track.altitude[i]))
        append("<Style><IconStyle><color>ff$color</color><scale>0.8</scale>")
        append("<Icon><href>https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png</href></Icon></IconStyle></Style>\n")
        append(String.format(Locale.US, "<Point><altitudeMode>absolute</altitudeMode><coordinates>%.7f,%.7f,%.1f</coordinates></Point>\n",
            track.lon[i], track.lat[i], track.hMsl[i]))
        append("</Placemark>\n")
    }

    /** A view from behind the exit, in the flight direction, from 60° above the vertical. */
    private fun lookAt(tracks: List<Track>): String? {
        val track = tracks.firstOrNull() ?: return null
        val first = if (track.hasExit) track.exitIndex else 0
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var minH = Double.MAX_VALUE
        var maxH = -Double.MAX_VALUE
        for (t in tracks) for (i in (if (t.hasExit) t.exitIndex else 0) until t.size) {
            minLat = minOf(minLat, t.lat[i]); maxLat = maxOf(maxLat, t.lat[i])
            minLon = minOf(minLon, t.lon[i]); maxLon = maxOf(maxLon, t.lon[i])
            minH = minOf(minH, t.hMsl[i]); maxH = maxOf(maxH, t.hMsl[i])
        }
        val lat = (minLat + maxLat) / 2
        val m = 111_320.0
        val dx = (maxLon - minLon) * m * cos(lat * PI / 180)
        val dy = (maxLat - minLat) * m
        val span = sqrt(dx * dx + dy * dy + (maxH - minH) * (maxH - minH))
        val last = if (track.deployIndex > first) track.deployIndex else track.size - 1
        val east = (track.lon[last] - track.lon[first]) * m * cos(lat * PI / 180)
        val north = (track.lat[last] - track.lat[first]) * m
        val heading = (Math.toDegrees(atan2(east, north)) + 360) % 360
        return String.format(Locale.US,
            "<LookAt><longitude>%.7f</longitude><latitude>%.7f</latitude><altitude>%.1f</altitude>" +
                "<heading>%.1f</heading><tilt>60</tilt><range>%.0f</range><altitudeMode>absolute</altitudeMode></LookAt>\n",
            (minLon + maxLon) / 2, lat, (minH + maxH) / 2, heading, max(span * 1.5, 2000.0))
    }

    /** KML colours are aabbggrr. This returns bbggrr. */
    private fun abgr(color: Int): String =
        String.format(Locale.US, "%02x%02x%02x", color and 0xff, (color shr 8) and 0xff, (color shr 16) and 0xff)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * Gives other apps (Google Earth, or the share target) read access to the
 * exported files: KML files and shared tracks. The app gives access to the
 * URIs of one export at a time.
 */
class ExportProvider : ContentProvider() {

    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val context = context ?: throw FileNotFoundException()
        val dir = File(context.cacheDir, DIR).canonicalFile
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val file = File(dir, name).canonicalFile
        if (file.parentFile != dir || !file.exists()) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) =
        if (uri.lastPathSegment?.endsWith(".csv", ignoreCase = true) == true) JumpSharing.CSV_MIME else KmlExport.MIME

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val DIR = "export"

        fun uri(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.export").appendPath(file.name).build()
    }
}
