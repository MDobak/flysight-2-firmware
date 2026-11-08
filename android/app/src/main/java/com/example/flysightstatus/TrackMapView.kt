package com.example.flysightstatus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Map with OpenStreetMap tiles, tracks, and an optional target pin.
 *
 * The view keeps the tiles in memory and in the cache folder of the app.
 * One finger moves the map. Two fingers zoom. A double tap zooms in.
 * If [onPick] is set, a tap moves the pin and calls it.
 */
@SuppressLint("ViewConstructor")
class TrackMapView(context: Context) : View(context) {

    private var tracks: List<Track> = emptyList()
    private val hidden = mutableSetOf<Int>()
    private var cursorTime: Double? = null

    /** Target pin (latitude, longitude in degrees). */
    var pin: Pair<Double, Double>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Called with the latitude and longitude of a tap. */
    var onPick: ((Double, Double) -> Unit)? = null

    /** Map center in world pixels at zoom 0 (0..256). */
    private var centerX = 128.0
    private var centerY = 128.0
    private var zoom = 3.0

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val tileDir = File(context.cacheDir, "tiles")
    private val pending = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(150, 255, 255, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val pinDrawable = context.getDrawable(R.drawable.ic_place)!!.mutate().apply { setTint(PIN_COLOR) }
    private val emptyTilePaint = Paint().apply { color = Color.rgb(200, 200, 200) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * density
        color = Color.rgb(40, 40, 40)
    }
    private val labelBack = Paint().apply { color = Color.argb(210, 255, 255, 255) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val src = Rect()
    private val dst = RectF()

    fun setTracks(list: List<Track>) {
        tracks = list
        hidden.clear()
        post { fitTracks() }
    }

    fun setTrackVisible(index: Int, on: Boolean) {
        if (on) hidden -= index else hidden += index
        invalidate()
    }

    /** Move the map to a point. */
    fun setCenter(lat: Double, lon: Double, zoom: Double) {
        centerX = lonToX(lon)
        centerY = latToY(lat)
        this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
    }

    fun setCursor(time: Double) {
        cursorTime = time
        invalidate()
    }

    /** Show all tracks in the view. */
    fun fitTracks() {
        if (tracks.isEmpty() || width == 0) return
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for ((n, track) in tracks.withIndex()) for (i in 0 until track.size) {
            if (n in hidden) break
            val x = lonToX(track.lon[i])
            val y = latToY(track.lat[i])
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }
        centerX = (minX + maxX) / 2
        centerY = (minY + maxY) / 2
        val spanX = max(maxX - minX, 1e-9)
        val spanY = max(maxY - minY, 1e-9)
        val margin = 0.85
        zoom = min(log2(width * margin / spanX), log2(height * margin / spanY)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        invalidate()
    }

    // ---- Projection (Web Mercator, 256-pixel tiles) ----

    private fun lonToX(lon: Double) = (lon + 180.0) / 360.0 * TILE

    private fun latToY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * TILE
    }

    private fun xToLon(x: Double) = x / TILE * 360.0 - 180.0

    private fun yToLat(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / TILE))))

    private val scale get() = 2.0.pow(zoom)

    private fun screenX(worldX: Double) = ((worldX - centerX) * scale + width / 2.0).toFloat()

    private fun screenY(worldY: Double) = ((worldY - centerY) * scale + height / 2.0).toFloat()

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        drawTiles(canvas)
        drawTracks(canvas)
        drawPin(canvas)
        drawAttribution(canvas)
    }

    private fun drawTiles(canvas: Canvas) {
        val z = floor(zoom).toInt().coerceIn(0, MAX_TILE_ZOOM)
        val n = 1 shl z
        val tileSize = TILE * 2.0.pow(zoom - z) // screen pixels for one tile at zoom z
        val worldPerTile = TILE / n
        val left = centerX - width / 2.0 / scale
        val top = centerY - height / 2.0 / scale
        val firstX = floor(left / worldPerTile).toInt()
        val firstY = floor(top / worldPerTile).toInt()
        val countX = (width / tileSize).toInt() + 2
        val countY = (height / tileSize).toInt() + 2
        for (ty in firstY until firstY + countY) {
            if (ty < 0 || ty >= n) continue
            for (tx in firstX until firstX + countX) {
                val wx = Math.floorMod(tx, n)
                val sx = screenX(tx * worldPerTile)
                val sy = screenY(ty * worldPerTile)
                dst.set(sx, sy, (sx + tileSize).toFloat(), (sy + tileSize).toFloat())
                val bitmap = tile(z, wx, ty)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, null, dst, bitmapPaint)
                } else if (!drawParent(canvas, z, wx, ty)) {
                    canvas.drawRect(dst, emptyTilePaint)
                }
            }
        }
    }

    /** Show a scaled part of a lower-zoom tile while the tile loads. */
    private fun drawParent(canvas: Canvas, z: Int, x: Int, y: Int): Boolean {
        for (up in 1..4) {
            val pz = z - up
            if (pz < 0) return false
            val parent = memory.get(key(pz, x shr up, y shr up)) ?: continue
            val part = TILE.toInt() shr up
            val ox = (x and ((1 shl up) - 1)) * part
            val oy = (y and ((1 shl up) - 1)) * part
            src.set(ox, oy, ox + part, oy + part)
            canvas.drawBitmap(parent, src, dst, bitmapPaint)
            return true
        }
        return false
    }

    private fun drawTracks(canvas: Canvas) {
        for ((n, track) in tracks.withIndex()) {
            if (n in hidden) continue
            val color = colorOf(n)
            path.reset()
            val step = max(1, track.size / 4000)
            var i = 0
            while (i < track.size) {
                val x = screenX(lonToX(track.lon[i]))
                val y = screenY(latToY(track.lat[i]))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                i += step
            }
            canvas.drawPath(path, casingPaint)
            trackPaint.color = color
            canvas.drawPath(path, trackPaint)

            // Exit: a ring. Landing: a dot.
            if (track.hasExit) drawDot(canvas, track, track.exitIndex, Color.WHITE, color, 6f)
            drawDot(canvas, track, track.size - 1, color, Color.WHITE, 5f)
            cursorTime?.let { t ->
                val k = track.indexAt(t)
                if (abs(track.t[k] - t) <= 2) drawDot(canvas, track, k, color, Color.WHITE, 8f)
            }
        }
    }

    private fun drawDot(canvas: Canvas, track: Track, i: Int, fill: Int, ring: Int, radiusDp: Float) {
        val x = screenX(lonToX(track.lon[i]))
        val y = screenY(latToY(track.lat[i]))
        dotPaint.color = fill
        ringPaint.color = ring
        canvas.drawCircle(x, y, radiusDp * density, dotPaint)
        canvas.drawCircle(x, y, radiusDp * density, ringPaint)
    }

    private fun drawPin(canvas: Canvas) {
        val (lat, lon) = pin ?: return
        val x = screenX(lonToX(lon)).toInt()
        val y = screenY(latToY(lat)).toInt()
        val size = (40 * density).toInt()
        // The point of the pin is at the bottom center of the icon (y = 22 of 24)
        val top = y - size * 22 / 24
        pinDrawable.setBounds(x - size / 2, top, x + size / 2, top + size)
        pinDrawable.draw(canvas)
    }

    private fun drawAttribution(canvas: Canvas) {
        val text = "© OpenStreetMap contributors"
        val w = textPaint.measureText(text)
        val h = 16 * density
        canvas.drawRect(width - w - 8 * density, height - h, width.toFloat(), height.toFloat(), labelBack)
        canvas.drawText(text, width - w - 4 * density, height - 5 * density, textPaint)
        if (tracks.isEmpty()) return
        // Legend for the markers
        val r = 4 * density
        val legendWidth = textPaint.measureText("Exit") + textPaint.measureText("Landing") + 6 * r + 16 * density
        canvas.drawRect(0f, height - h, legendWidth, height.toFloat(), labelBack)
        val cy = height - h / 2
        var x = 4 * density + r
        dotPaint.color = Color.WHITE
        ringPaint.color = Color.rgb(60, 60, 60)
        canvas.drawCircle(x, cy, r, dotPaint)
        canvas.drawCircle(x, cy, r, ringPaint)
        x += r + 3 * density
        canvas.drawText("Exit", x, height - 5 * density, textPaint)
        x += textPaint.measureText("Exit") + 8 * density + r
        dotPaint.color = Color.rgb(60, 60, 60)
        canvas.drawCircle(x, cy, r, dotPaint)
        x += r + 3 * density
        canvas.drawText("Landing", x, height - 5 * density, textPaint)
    }

    // ---- Tiles ----

    private fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = key(z, x, y)
        memory.get(key)?.let { return it }
        if (key in pending || key in failed) return null
        pending += key
        loader.execute {
            val bitmap = try {
                loadTile(z, x, y)
            } catch (_: Exception) {
                null
            }
            handler.post {
                pending -= key
                if (bitmap != null) memory.put(key, bitmap) else failed += key
                invalidate()
            }
        }
        return null
    }

    private fun loadTile(z: Int, x: Int, y: Int): Bitmap? {
        val file = File(tileDir, "$z/$x/$y.png")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MAX_AGE_MS) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        val connection = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) {
                // Without network, use an old tile if there is one
                return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            connection.disconnect()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }

    // ---- Touch ----

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAround(detector.focusX, detector.focusY, log2(detector.scaleFactor.toDouble()))
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            centerX += dx / scale
            centerY += dy / scale
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAround(e.x, e.y, 1.0)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val pick = onPick ?: return false
            val lat = yToLat(centerY + (e.y - height / 2.0) / scale)
            val worldX = centerX + (e.x - width / 2.0) / scale
            val lon = xToLon(((worldX % TILE) + TILE) % TILE)
            pin = lat to lon
            pick(lat, lon)
            return true
        }
    })

    private fun zoomAround(x: Float, y: Float, delta: Double) {
        val worldX = centerX + (x - width / 2.0) / scale
        val worldY = centerY + (y - height / 2.0) / scale
        zoom = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Keep the point under the fingers at the same place
        centerX = worldX - (x - width / 2.0) / scale
        centerY = worldY - (y - height / 2.0) / scale
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        private const val TILE = 256.0
        private const val MIN_ZOOM = 1.0
        private const val MAX_ZOOM = 20.0
        private const val MAX_TILE_ZOOM = 19
        private const val CACHE_MAX_AGE_MS = 30L * 24 * 3600 * 1000

        /** The OpenStreetMap tile policy requires a User-Agent that identifies the app. */
        private const val USER_AGENT = "FlySightTest/0.1 (Android; private test app for FlySight 2)"

        /** The map is light in both themes, so it uses the light track colours. */
        fun colorOf(track: Int) = Ui.TRACK_COLORS_LIGHT[track % Ui.TRACK_COLORS_LIGHT.size]

        /** The pin colour of Google Maps. */
        private val PIN_COLOR = Color.rgb(0xEA, 0x43, 0x35)

        /** Shared by all map views. The size is in KB. */
        private val memory = object : LruCache<String, Bitmap>(32 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }

        /** OpenStreetMap permits a maximum of 2 connections from one app. */
        private val loader = Executors.newFixedThreadPool(2)
    }
}
