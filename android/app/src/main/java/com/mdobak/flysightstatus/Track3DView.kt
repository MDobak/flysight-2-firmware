package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The image on the terrain of the 3D view. */
enum class MapStyle(val label: String, val description: String, val credit: String, val hasImage: Boolean = true) {
    SATELLITE("Satellite", "Sentinel-2 cloudless images, 10 m per pixel", "Sentinel-2 cloudless 2025 by EOX · Terrain Tiles"),
    STREETS("Streets", "OpenStreetMap", "© OpenStreetMap contributors · Terrain Tiles"),
    TOPO("Topographic", "OpenTopoMap, with contour lines", "© OpenStreetMap contributors, SRTM · OpenTopoMap · Terrain Tiles"),
    ELEVATION("Elevation", "Colours by height, with contour lines", "Terrain Tiles", hasImage = false),
}

/**
 * The 3D view with its controls: a compass, a map button (the map style and
 * the height scale), the attribution, and a status text. During a replay, a
 * pin button sets if the view follows the cursor.
 */
@SuppressLint("ViewConstructor", "SetTextI18n") // English only
class Track3DPane(context: Context, private val ui: Ui) : FrameLayout(context) {

    val view = Track3DView(context)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val status = label()
    private val scaleLabel = label()
    private val credits = label()
    private val compass = CompassDrawable(ui)
    private val followButton = ui.mapButton(R.drawable.ic_follow_on, "Pin the view to the cursor") { setFollow(!view.follow) }.apply {
        visibility = GONE
    }

    init {
        view.style = MapStyle.entries.getOrElse(prefs.getInt(PREF_STYLE, 0)) { MapStyle.SATELLITE }
        view.heightScale = prefs.getFloat(PREF_SCALE, 1f).takeIf { it in SCALES } ?: 1f
        addView(view, LayoutParams(MATCH, MATCH))

        val compassButton = ui.mapButton(R.drawable.ic_fit, "Turn to north") { view.northUp() }.apply {
            setImageDrawable(compass)
            imageTintList = null
        }
        addView(compassButton, LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(8), ui.dp(8), 0, 0) })
        addView(scaleLabel, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(60), ui.dp(20), 0, 0) })
        addView(status, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(ui.dp(64), ui.dp(64), ui.dp(64), 0) })

        // The same column as on the 2D map: above the attribution in the bottom-right corner
        addView(ui.mapButton(R.drawable.ic_layers, "Map style and height scale") { showMapOptions() },
            LayoutParams(ui.dp(48), ui.dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(8), ui.dp(76)) })
        addView(followButton, LayoutParams(ui.dp(48), ui.dp(48), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, ui.dp(8), ui.dp(24)) })
        credits.setOnClickListener { showCredits() }
        addView(credits, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END))

        view.onStatus = { text ->
            status.text = text
            status.visibility = if (text == null) GONE else VISIBLE
        }
        view.onHeading = { compass.heading = it }
        update()
    }

    /** Show the pin button during a replay. Each replay starts with the view pinned to the cursor. */
    fun setReplay(on: Boolean) {
        followButton.visibility = if (on) VISIBLE else GONE
        setFollow(on)
    }

    private fun setFollow(on: Boolean) {
        if (on && followButton.visibility != VISIBLE) return
        view.follow = on
        followButton.setImageResource(if (on) R.drawable.ic_follow_on else R.drawable.ic_follow_off)
        followButton.imageTintList = android.content.res.ColorStateList.valueOf(if (on) ui.primary else ui.onSurface)
        followButton.contentDescription = if (on) "Unpin the view from the cursor" else "Pin the view to the cursor"
        followButton.tooltipText = followButton.contentDescription
    }

    private fun label() = TextView(context).apply {
        textSize = 11f
        setTextColor(Color.rgb(40, 40, 40))
        background = ui.shape(Color.argb(220, 255, 255, 255), 4f)
        setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(3))
        visibility = GONE
    }

    private fun update() {
        credits.text = view.style.credit
        credits.visibility = VISIBLE
        scaleLabel.text = if (view.heightScale == 0f) "Flat terrain" else "Height ×${view.heightScale.toInt()}"
        scaleLabel.visibility = if (view.heightScale == 1f) GONE else VISIBLE
    }

    /** One dialog for the map style and the height scale. The view changes at once. */
    private fun showMapOptions() {
        val content = ui.column()
        content.addView(heading("Map"))
        val rows = MapStyle.entries.map { style ->
            val radio = ui.icon(R.drawable.ic_radio_off)
            val texts = ui.column().apply {
                addView(ui.text(Ui.Type.BODY_LARGE, style.label))
                addView(ui.text(Ui.Type.BODY_SMALL, style.description, ui.onSurfaceVariant))
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(56)
                setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(4))
                background = ui.ripple(ui.onSurface, null)
                addView(radio, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(16) })
                addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            content.addView(row)
            Triple(style, row, radio)
        }
        fun select() {
            for ((style, _, radio) in rows) {
                val on = style == view.style
                radio.setImageResource(if (on) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
                radio.imageTintList = android.content.res.ColorStateList.valueOf(if (on) ui.primary else ui.onSurfaceVariant)
            }
        }
        for ((style, row, _) in rows) row.setOnClickListener {
            view.style = style
            prefs.edit().putInt(PREF_STYLE, style.ordinal).apply()
            select()
            update()
        }
        select()

        content.addView(heading("Height scale").apply { setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(4)) })
        content.addView(ui.Segmented(SCALES.map { "${it.toInt()}×" }, SCALES.indexOf(view.heightScale)) {
            view.heightScale = SCALES[it]
            prefs.edit().putFloat(PREF_SCALE, SCALES[it]).apply()
            update()
        }.apply { setPadding(ui.dp(24), 0, ui.dp(24), 0) })
        content.addView(ui.note("A larger scale makes the terrain and the jump higher than they are. Use it to see flat terrain. " +
            "At 0×, the terrain is flat at the landing height, and the jump has its true height.")
            .apply { setPadding(ui.dp(24), ui.dp(4), ui.dp(24), 0) })
        ui.dialog("3D view", content = content, positive = Ui.DialogButton("Close"), negative = null, scrollContent = true).show()
    }

    private fun heading(text: String) = ui.text(Ui.Type.TITLE_SMALL, text, ui.primary).apply {
        setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(4))
    }

    private fun showCredits() {
        val text = ui.text(Ui.Type.BODY_MEDIUM, CREDITS, ui.onSurfaceVariant).apply {
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
        }
        ui.dialog("Map data", content = text, positive = Ui.DialogButton("Close"), negative = null, scrollContent = true).show()
    }

    /** A compass needle. The red end points to the north. */
    private class CompassDrawable(ui: Ui) : Drawable() {
        var heading = 0f
            set(value) {
                if (abs(value - field) < 0.5f) return
                field = value
                invalidateSelf()
            }
        private val size = ui.dp(24)
        private val r = ui.dpf(9f)
        private val w = ui.dpf(3.5f)
        private val north = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
        private val south = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.onSurfaceVariant }
        private val northPath = Path()
        private val southPath = Path()

        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            northPath.reset()
            northPath.moveTo(cx, cy - r); northPath.lineTo(cx + w, cy); northPath.lineTo(cx - w, cy); northPath.close()
            southPath.reset()
            southPath.moveTo(cx, cy + r); southPath.lineTo(cx + w, cy); southPath.lineTo(cx - w, cy); southPath.close()
            canvas.save()
            canvas.rotate(heading, cx, cy)
            canvas.drawPath(southPath, south)
            canvas.drawPath(northPath, north)
            canvas.restore()
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in the platform")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val PREFS = "track_viewer"
        private const val PREF_STYLE = "map_3d_style"
        private const val PREF_SCALE = "map_3d_scale"
        private val SCALES = listOf(0f, 1f, 2f, 3f)
        private const val CREDITS =
            "Satellite: Sentinel-2 cloudless 2025, https://cloudless.eox.at, by EOX IT Services GmbH " +
                "(contains modified Copernicus Sentinel data 2025), CC BY-NC-SA 4.0.\n\n" +
                "Streets: © OpenStreetMap contributors, ODbL, https://www.openstreetmap.org/copyright.\n\n" +
                "Topographic: map data © OpenStreetMap contributors, SRTM. Map style © OpenTopoMap, CC BY-SA.\n\n" +
                "Terrain: Terrain Tiles on AWS Open Data, by Mapzen and others, from SRTM, 3DEP, " +
                "ETOPO1, GMTED and other sources.\n\n" +
                "The 3D view shows each track from 10 s before the exit. The terrain data and the GPS heights " +
                "have errors. Where the terrain hides a part of a track, that part shows as a faint line."
    }
}

/**
 * 3D view of the tracks over the terrain.
 *
 * The view loads the tiles when it becomes visible, and keeps them in the cache
 * folder of the app. The gestures are as in Google Earth: one finger moves the
 * map, two fingers zoom and turn it, and two fingers up or down tilt it. A
 * double tap zooms in. A tap with two fingers zooms out. A double tap and a
 * drag up or down zooms with one finger.
 */
@SuppressLint("ViewConstructor")
class Track3DView(context: Context) : GLSurfaceView(context) {

    /** Called with a status text, or with null when there is nothing to show. */
    var onStatus: ((String?) -> Unit)? = null

    /** Called with the heading of the camera (degrees clockwise from north). */
    var onHeading: ((Float) -> Unit)? = null

    /**
     * The height scale: 1 is true scale. At 0, the terrain is flat at the
     * landing height, and the tracks have their true height.
     */
    var heightScale = 1f
        set(value) {
            if (value == field) return
            val old = field
            field = value
            stopAnimation()
            val s = scene
            val p = if (follow) cursorTime?.let { cursorPoint(it) } else null
            when {
                p != null -> camera.targetY = p[1] * trackScale
                old > 0 && value > 0 -> camera.targetY *= value / old
                // The target goes to the ground
                s != null -> camera.targetY = groundY(s, s.groundAt(camera.targetX, camera.targetZ))
            }
            cameraChanged()
        }

    /** The height scale of the tracks. */
    private val trackScale get() = if (heightScale == 0f) 1f else heightScale

    /** The scene height of the terrain with the height [h] (m above the scene base). */
    private fun groundY(s: Scene, h: Float) = if (heightScale == 0f) s.level else h * heightScale

    var style = MapStyle.SATELLITE
        set(value) {
            if (value == field) return
            field = value
            loadImage()
        }

    /**
     * If true, the camera follows the cursor (during the replay). The gestures
     * then change: one finger turns and tilts the view around the cursor. Two
     * fingers zoom, turn and tilt, but do not move the view. When the follow
     * starts, the camera moves to the cursor.
     */
    var follow = false
        set(value) {
            if (value == field) return
            field = value
            if (!value) return
            val p = cursorTime?.let { cursorPoint(it) } ?: return
            val to = camera.copy().apply {
                targetX = p[0]; targetY = p[1] * trackScale; targetZ = p[2]
            }
            animateTo(to, 350)
            followTo = to
        }

    /** The end of the animation to the cursor. [setCursor] moves it while the animation runs. */
    private var followTo: Camera? = null

    private var tracks: List<Track> = emptyList()
    @Volatile private var hidden: Set<Int> = emptySet()
    @Volatile private var cursorTime: Double? = null
    @Volatile private var scene: Scene? = null
    /** The style of the image on the terrain, or [MapStyle.ELEVATION] if there is no image. */
    @Volatile private var shownStyle = MapStyle.ELEVATION
    private var loading = false
    private var fitted = false
    private var imageJob = 0

    private val camera = Camera()
    private val renderer = Renderer()
    private val density = resources.displayMetrics.density

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setTracks(list: List<Track>) {
        tracks = list
        hidden = emptySet()
        scene = null
        fitted = false
        if (isShown) load()
    }

    /** Use the same tracks with different times. The scene and the view stay. */
    fun updateTimes(list: List<Track>) {
        tracks = list
        scene?.paths?.forEach { it.track = list[it.index] }
        cursorTime?.let { setCursor(it) }
        requestRender()
    }

    fun setTrackVisible(index: Int, on: Boolean) {
        hidden = if (on) hidden - index else hidden + index
        requestRender()
    }

    fun setCursor(time: Double) {
        cursorTime = time
        if (follow) {
            val p = cursorPoint(time)
            if (p != null) {
                val to = followTo
                val c = if (to != null && animation != null) to else camera
                c.targetX = p[0]
                c.targetY = p[1] * trackScale
                c.targetZ = p[2]
                if (c === camera) cameraChanged()
            }
        }
        requestRender()
    }

    /** The cursor point of the first visible track, in scene coordinates (m). */
    private fun cursorPoint(time: Double) = scene?.paths?.firstOrNull { it.index !in hidden }?.positionAt(time)

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && scene == null) load()
    }

    /** Show all visible tracks, from behind the exit, in the flight direction. */
    fun fitTracks(animate: Boolean = true) {
        val s = scene ?: return
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        var heading: Double? = null
        for (p in s.paths) {
            if (p.index in hidden) continue
            for (i in 0 until p.count) {
                val x = p.xyz[3 * i].toDouble()
                val y = p.xyz[3 * i + 1].toDouble() * trackScale
                val z = p.xyz[3 * i + 2].toDouble()
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                minZ = min(minZ, z); maxZ = max(maxZ, z)
            }
            if (heading == null && p.count > 1) {
                // The direction from the exit to the opening, or of the whole path
                val a = p.exit.coerceAtLeast(0)
                val b = if (p.deploy > a) p.deploy else p.count - 1
                val dx = (p.xyz[3 * b] - p.xyz[3 * a]).toDouble()
                val dz = (p.xyz[3 * b + 2] - p.xyz[3 * a + 2]).toDouble()
                if (hypot(dx, dz) > 1) heading = atan2(dx, dz)
            }
        }
        if (minX > maxX) return
        val radius = 0.5 * sqrt((maxX - minX).pow(2) + (maxY - minY).pow(2) + (maxZ - minZ).pow(2))
        val to = Camera().apply {
            targetX = ((minX + maxX) / 2).toFloat()
            targetY = ((minY + maxY) / 2).toFloat()
            targetZ = ((minZ + maxZ) / 2).toFloat()
            distance = (max(radius, 200.0) / sin(Math.toRadians(FOV / 2.0)) * 0.9).toFloat()
            // The camera is behind the flight direction: the jump goes away from it
            yaw = ((heading ?: 0.0) + PI).toFloat()
            pitch = Math.toRadians(30.0).toFloat()
        }
        if (animate) animateTo(to) else {
            camera.set(to)
            cameraChanged()
        }
    }

    /** Turn the map so that the north is at the top. */
    fun northUp() {
        stopAnimation()
        pivotOnGround()
        animateTo(camera.copy().apply { yaw = 0f })
    }

    private fun cameraChanged() {
        requestRender()
        onHeading?.invoke(Math.toDegrees(camera.yaw.toDouble()).toFloat())
    }

    // ---- Loading ----

    private fun load() {
        if (loading || tracks.isEmpty()) return
        loading = true
        onStatus?.invoke("Loading the terrain…")
        val list = tracks
        val style = style
        Thread {
            val result = runCatching { Scene.build(context, list) }
            val image = result.getOrNull()?.let { s ->
                if (style.hasImage) runCatching { Scene.loadImage(context, style, s) }.getOrNull() else null
            }
            post {
                loading = false
                if (list !== tracks) {
                    load()
                    return@post
                }
                result.onSuccess { s ->
                    scene = s
                    queueEvent { renderer.upload(s) }
                    if (!fitted) {
                        fitted = true
                        fitTracks(animate = false)
                    }
                    if (this.style == style) setImage(style, image) else loadImage()
                }.onFailure { onStatus?.invoke("Cannot show the 3D view: ${it.message}") }
            }
        }.start()
    }

    /** Load the image of [style] for the current scene. */
    private fun loadImage() {
        val s = scene ?: return
        val style = style
        val job = ++imageJob
        if (!style.hasImage) {
            setImage(style, null)
            return
        }
        onStatus?.invoke("Loading the map…")
        Thread {
            val image = runCatching { Scene.loadImage(context, style, s) }.getOrNull()
            post { if (job == imageJob && scene === s) setImage(style, image) }
        }.start()
    }

    private fun setImage(style: MapStyle, image: Bitmap?) {
        shownStyle = if (image == null) MapStyle.ELEVATION else style
        queueEvent { renderer.uploadImage(image) }
        val problems = listOfNotNull(
            scene?.problem,
            if (style.hasImage && image == null) "No map images. The view shows the colours by height" else null,
        )
        onStatus?.invoke(if (problems.isEmpty()) null else problems.joinToString(". ") + ". Check the network.")
        requestRender()
    }

    // ---- Camera ----

    /** An orbit camera. The coordinates are in m: x east, y up, z south. */
    private class Camera {
        @Volatile var targetX = 0f
        @Volatile var targetY = 0f
        @Volatile var targetZ = 0f
        @Volatile var distance = 5000f
        /** 0: the camera is south of the target and looks north. Positive: clockwise from above. */
        @Volatile var yaw = 0f
        /** The angle of the camera above the horizon, from [MIN_PITCH] to [MAX_PITCH]. */
        @Volatile var pitch = 0.5f

        fun copy() = Camera().also { it.set(this) }

        fun set(o: Camera) {
            targetX = o.targetX; targetY = o.targetY; targetZ = o.targetZ
            distance = o.distance; yaw = o.yaw; pitch = o.pitch
        }
    }

    /** The position of the eye. The eye stays above the terrain. */
    private fun eye(): FloatArray {
        val d = camera.distance
        val cp = cos(camera.pitch)
        val x = camera.targetX + d * cp * sin(camera.yaw)
        var y = camera.targetY + d * sin(camera.pitch)
        val z = camera.targetZ + d * cp * cos(camera.yaw)
        scene?.let { y = max(y, groundY(it, it.groundAt(x, z)) + MIN_EYE_HEIGHT) }
        return floatArrayOf(x, y, z)
    }

    /** The view and projection matrices of the camera. */
    private fun cameraMatrices(view: FloatArray, projection: FloatArray, w: Int, h: Int) {
        val d = camera.distance
        val e = eye()
        Matrix.setLookAtM(view, 0, e[0], e[1], e[2], camera.targetX, camera.targetY, camera.targetZ, 0f, 1f, 0f)
        val size = scene?.size ?: 10_000f
        // Near the ground, the near plane comes nearer, so that the terrain in front of the eye shows
        val clearance = scene?.let { e[1] - groundY(it, it.groundAt(e[0], e[2])) } ?: d
        val near = max(1f, min(d * 0.01f, clearance * 0.5f))
        Matrix.perspectiveM(projection, 0, FOV.toFloat(), max(w, 1).toFloat() / max(h, 1), near, d + size * 3)
    }

    /** A ray from the eye: the start (on the near plane) and the unit direction. */
    private class Ray(val x: Float, val y: Float, val z: Float, val dx: Float, val dy: Float, val dz: Float) {
        fun at(t: Float) = floatArrayOf(x + dx * t, y + dy * t, z + dz * t)
    }

    /** The ray through the screen point ([sx], [sy]), or null. */
    private fun ray(sx: Float, sy: Float): Ray? {
        if (width == 0 || height == 0) return null
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val mvp = FloatArray(16)
        val inverse = FloatArray(16)
        cameraMatrices(view, projection, width, height)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        if (!Matrix.invertM(inverse, 0, mvp, 0)) return null
        val nx = 2 * sx / width - 1
        val ny = 1 - 2 * sy / height
        fun unproject(z: Float): FloatArray {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, inverse, 0, floatArrayOf(nx, ny, z, 1f), 0)
            return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
        }
        val a = unproject(-1f)
        val b = unproject(1f)
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val dz = b[2] - a[2]
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length == 0f || !length.isFinite()) return null
        return Ray(a[0], a[1], a[2], dx / length, dy / length, dz / length)
    }

    /**
     * The first point of the terrain on the ray [r], or null. The ray goes in
     * steps of half a mesh cell, and a bisection then finds the surface.
     */
    private fun terrainHit(r: Ray): FloatArray? {
        val s = scene ?: return null
        val half = s.size / 2
        // The part of the ray above the square of the terrain
        var t0 = 0f
        var t1 = camera.distance * 2 + s.size * 2
        fun slab(o: Float, d: Float): Boolean {
            if (abs(d) < 1e-6f) return abs(o) <= half
            val a = (-half - o) / d
            val b = (half - o) / d
            t0 = max(t0, min(a, b))
            t1 = min(t1, max(a, b))
            return t0 < t1
        }
        if (!slab(r.x, r.dx) || !slab(r.z, r.dz)) return null
        fun above(t: Float): Float {
            val p = r.at(t)
            return p[1] - groundY(s, s.groundAt(p[0], p[2]))
        }
        // The ray must come from above the terrain
        if (above(t0) <= 0) return null
        val step = s.cellSize * 0.5f
        var a = t0
        while (a < t1) {
            val b = min(a + step, t1)
            if (above(b) <= 0) {
                var lo = a
                var hi = b
                repeat(16) {
                    val m = (lo + hi) / 2
                    if (above(m) > 0) lo = m else hi = m
                }
                return r.at(hi)
            }
            a = b
        }
        return null
    }

    /**
     * The point of the ray [r] on the horizontal plane at height [y]. If the
     * point is more than [maxT] along the ray, or if the ray does not go to the
     * plane, the point below the ray at [maxT] is used. Thus the point moves
     * smoothly near the horizon.
     */
    private fun planeHit(r: Ray, y: Float, maxT: Float): FloatArray {
        val t = (y - r.y) / r.dy
        val u = if (t.isFinite() && t > 0 && t < maxT) t else maxT
        val p = r.at(u)
        p[1] = y
        return p
    }

    /** The farthest point that a pan can use (m along the ray). */
    private fun panLimit(r: Ray, p: FloatArray): Float {
        val d = hypot(hypot(p[0] - r.x, p[1] - r.y), p[2] - r.z)
        return max(d * 2, camera.distance * 6)
    }

    /**
     * The ground point below the screen point ([sx], [sy]), or null. If the
     * point is not on the terrain (for example, in the sky), it is a point on
     * the plane of the camera target, not more than a few camera distances away.
     */
    private fun pick(sx: Float, sy: Float): FloatArray? {
        val r = ray(sx, sy) ?: return null
        return terrainHit(r) ?: planeHit(r, camera.targetY, camera.distance * 6)
    }

    /**
     * Put the camera target on the terrain at the centre of the screen. The
     * view does not change. Thus the zoom, the turn and the tilt go around the
     * ground point at the centre, as in Google Earth. This also removes the
     * difference between the camera and the eye that stays above the terrain.
     */
    private fun pivotOnGround() {
        if (follow) return
        val r = ray(width / 2f, height / 2f) ?: return
        val p = terrainHit(r) ?: return
        val e = eye()
        val ex = e[0] - p[0]
        val ey = e[1] - p[1]
        val ez = e[2] - p[2]
        val horizontal = hypot(ex, ez)
        val d = hypot(horizontal, ey)
        val pitch = atan2(ey, horizontal)
        if (d < MIN_DISTANCE || d > MAX_DISTANCE || pitch < MIN_PITCH || pitch > MAX_PITCH) return
        camera.targetX = p[0]
        camera.targetY = p[1]
        camera.targetZ = p[2]
        camera.distance = d
        camera.pitch = pitch
        if (horizontal > 1e-3f) camera.yaw = atan2(ex, ez)
    }

    /** Move the camera target. The target stays near the terrain. */
    private fun moveTarget(dx: Float, dz: Float) {
        val limit = scene?.size ?: 20_000f
        camera.targetX = (camera.targetX + dx).coerceIn(-limit, limit)
        camera.targetZ = (camera.targetZ + dz).coerceIn(-limit, limit)
    }

    // ---- Animation ----

    private var animation: Runnable? = null

    private fun animateTo(to: Camera, durationMs: Long = 400) {
        stopAnimation()
        val from = camera.copy()
        val dYaw = wrap(to.yaw - from.yaw)
        val start = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (animation !== this) return
                val f = ((SystemClock.uptimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                val e = 1 - (1 - f) * (1 - f) * (1 - f)
                fun mix(a: Float, b: Float) = a + (b - a) * e
                camera.targetX = mix(from.targetX, to.targetX)
                camera.targetY = mix(from.targetY, to.targetY)
                camera.targetZ = mix(from.targetZ, to.targetZ)
                camera.distance = exp(mix(ln(from.distance), ln(to.distance)))
                camera.yaw = from.yaw + dYaw * e
                camera.pitch = mix(from.pitch, to.pitch)
                cameraChanged()
                if (f < 1) postOnAnimation(this) else animation = null
            }
        }
        animation = r
        postOnAnimation(r)
    }

    /** Continue a pan after the finger leaves the screen. */
    private fun fling(vx: Float, vy: Float, x: Float, y: Float) {
        val planeY = anchor?.get(1) ?: return
        stopAnimation()
        var velocityX = vx
        var velocityY = vy
        var last = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (animation !== this) return
                val now = SystemClock.uptimeMillis()
                val dt = (now - last) / 1000f
                last = now
                pan(x, y, x + velocityX * dt, y + velocityY * dt, planeY)
                val decay = exp(-FLING_DECAY * dt)
                velocityX *= decay
                velocityY *= decay
                if (hypot(velocityX, velocityY) > 40) postOnAnimation(this) else animation = null
            }
        }
        animation = r
        postOnAnimation(r)
    }

    private fun stopAnimation() {
        animation?.let { removeCallbacks(it) }
        animation = null
        followTo = null
    }

    /** Zoom by [factor] (2: in, 0.5: out) to the screen point ([x], [y]). */
    private fun zoomBy(factor: Float, x: Float, y: Float) {
        val to = camera.copy()
        to.distance = (camera.distance / factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        // During the follow, the cursor stays in the centre
        val p = if (follow) null else pick(x, y)
        if (p != null) {
            // All points go to the touched point or from it: the touched point stays below the finger
            val k = 1 - to.distance / camera.distance
            to.targetX += (p[0] - camera.targetX) * k
            to.targetY += (p[1] - camera.targetY) * k
            to.targetZ += (p[2] - camera.targetZ) * k
        }
        animateTo(to, 300)
    }

    // ---- Touch ----

    private enum class Touch { NONE, PAN, TWO, TRANSFORM, TILT, QUICK_ZOOM }

    private var touch = Touch.NONE
    private var multiTouch = false
    private var lastX = 0f
    private var lastY = 0f
    private val start0 = FloatArray(2)
    private val start1 = FloatArray(2)
    private var twoDownTime = 0L
    private var prevSpan = 0f
    private var prevAngle = 0f
    private var prevFocusX = 0f
    private var prevFocusY = 0f
    /** The turn of the fingers (rad) before the map starts to turn. */
    private var twist = 0f
    private var rotating = false
    private var quickStartY = 0f
    private var quickLastY = 0f
    private var quickMoved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    /** The ground point that stays below the finger, or below the centre of two fingers. */
    private var anchor: FloatArray? = null
    /** The farthest point along the ray that a move of [anchor] can use (m). */
    private var anchorT = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touch = Touch.QUICK_ZOOM
                    quickStartY = e.y
                    quickLastY = e.y
                    quickMoved = false
                }
                MotionEvent.ACTION_MOVE -> if (touch == Touch.QUICK_ZOOM) {
                    if (abs(e.y - quickStartY) > slop) quickMoved = true
                    if (quickMoved) {
                        // Down zooms in, up zooms out, as in Google Maps
                        camera.distance = (camera.distance * exp(-(e.y - quickLastY) / max(height, 1) * 4)).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
                        cameraChanged()
                    }
                    quickLastY = e.y
                }
                MotionEvent.ACTION_UP -> if (touch == Touch.QUICK_ZOOM && !quickMoved) zoomBy(2f, e.x, e.y)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (touch == Touch.PAN && !multiTouch && !follow) fling(velocityX, velocityY, e2.x, e2.y)
            return true
        }
    })

    /**
     * Turn and tilt the view around the target with one finger. A movement to
     * the side turns: the ground in front of the target moves with the finger,
     * and the full width turns 180°. A movement up or down tilts, as with two
     * fingers.
     */
    private fun orbit(dx: Float, dy: Float) {
        camera.yaw = wrap(camera.yaw - dx / max(width, 1) * PI.toFloat())
        camera.pitch = (camera.pitch + dy / max(height, 1) * PI.toFloat() * 0.8f).coerceIn(MIN_PITCH, MAX_PITCH)
        cameraChanged()
    }

    /**
     * Move the map so that the ground point [anchor] goes below the screen
     * point ([x], [y]). The map moves horizontally, thus the point stays below
     * the finger on the horizontal plane at its height.
     */
    private fun panTo(anchor: FloatArray, anchorT: Float, x: Float, y: Float) {
        val r = ray(x, y) ?: return
        val q = planeHit(r, anchor[1], anchorT)
        moveTarget(anchor[0] - q[0], anchor[2] - q[2])
        cameraChanged()
    }

    /** Move the map so that the point below ([x0], [y0]) on the plane at height [planeY] goes to ([x1], [y1]). */
    private fun pan(x0: Float, y0: Float, x1: Float, y1: Float, planeY: Float) {
        val r0 = ray(x0, y0) ?: return
        val r1 = ray(x1, y1) ?: return
        val limit = camera.distance * 6
        val a = planeHit(r0, planeY, limit)
        val b = planeHit(r1, planeY, limit)
        moveTarget(a[0] - b[0], a[2] - b[2])
        cameraChanged()
    }

    /** Set the ground point that stays below the finger or below the centre of the fingers. */
    private fun grab(x: Float, y: Float) {
        anchor = null
        if (follow) return
        val r = ray(x, y) ?: return
        val p = pick(x, y) ?: return
        anchor = p
        anchorT = panLimit(r, p)
    }

    private fun focusX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun focusY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2
    private fun span(e: MotionEvent) = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
    private fun angle(e: MotionEvent) = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))

    private fun wrap(a: Float): Float {
        var x = a % (2 * PI.toFloat())
        if (x > PI) x -= 2 * PI.toFloat()
        if (x < -PI) x += 2 * PI.toFloat()
        return x
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            stopAnimation()
            pivotOnGround()
            multiTouch = false
            touch = Touch.PAN
        }
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                grab(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                touch = Touch.TWO
                multiTouch = true
                // The one-finger pan moved the target horizontally: put it on the ground again
                pivotOnGround()
                twoDownTime = e.eventTime
                start0[0] = e.getX(0); start0[1] = e.getY(0)
                start1[0] = e.getX(1); start1[1] = e.getY(1)
                prevSpan = span(e)
                prevAngle = angle(e)
                prevFocusX = focusX(e)
                prevFocusY = focusY(e)
            }
            MotionEvent.ACTION_MOVE -> when (touch) {
                Touch.PAN -> {
                    val a = anchor
                    if (follow) orbit(e.x - lastX, e.y - lastY) else if (a != null) panTo(a, anchorT, e.x, e.y)
                    lastX = e.x
                    lastY = e.y
                }
                Touch.TWO -> if (e.pointerCount >= 2) decide(e)
                Touch.TRANSFORM -> if (e.pointerCount >= 2) transform(e)
                Touch.TILT -> if (e.pointerCount >= 2) tilt(e)
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount == 2) {
                // A short tap with two fingers zooms out
                if (touch == Touch.TWO && e.eventTime - twoDownTime < ViewConfiguration.getTapTimeout() * 2) {
                    zoomBy(0.5f, focusX(e), focusY(e))
                }
                // Continue to pan with the other finger
                val other = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(other)
                lastY = e.getY(other)
                grab(lastX, lastY)
                touch = Touch.PAN
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touch = Touch.NONE
        }
        return true
    }

    /**
     * Select the gesture from the first movement of two fingers. Two fingers
     * that move up or down together tilt the map. All other movements zoom,
     * turn and move it.
     *
     * The app compares the vertical movement of the centre point with the
     * change of the finger distance and with the turn. The two fingers do
     * not move at the same time and at the same speed. Thus the app does not
     * examine each finger, and it waits for a clear movement.
     */
    private fun decide(e: MotionEvent) {
        val startSpan = hypot(start1[0] - start0[0], start1[1] - start0[1])
        val dfx = focusX(e) - (start0[0] + start1[0]) / 2
        val dfy = focusY(e) - (start0[1] + start1[1]) / 2
        val zoom = abs(span(e) - startSpan)
        val turn = abs(wrap(angle(e) - prevAngle)) * startSpan / 2
        if (max(max(abs(dfx), abs(dfy)), max(zoom, turn)) < slop * 1.5f) return
        // Not more than 45° from horizontal: the fingers are side by side
        val sideBySide = abs(start1[1] - start0[1]) <= abs(start1[0] - start0[0])
        val tilt = sideBySide && abs(dfy) > 1.5f * abs(dfx) && abs(dfy) > 1.2f * zoom && abs(dfy) > 1.2f * turn
        touch = if (tilt) Touch.TILT else Touch.TRANSFORM
        twist = 0f
        rotating = false
        if (touch == Touch.TRANSFORM) grab(prevFocusX, prevFocusY)
        if (touch == Touch.TILT) tilt(e) else transform(e)
    }

    /**
     * Zoom, turn and move. The ground point below the fingers stays below the
     * fingers. The map turns only after a clear twist of the fingers, so that
     * a zoom does not also turn the map.
     */
    private fun transform(e: MotionEvent) {
        val fx = focusX(e)
        val fy = focusY(e)
        val span = span(e)
        val angle = angle(e)
        if (span > 0 && prevSpan > 0) camera.distance = (camera.distance * prevSpan / span).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        val dAngle = wrap(angle - prevAngle)
        if (rotating) {
            camera.yaw += dAngle
        } else {
            twist += dAngle
            if (abs(twist) > ROTATE_THRESHOLD) rotating = true
        }
        // The ground point below the fingers stays below them. During the follow, the cursor stays in the centre.
        val a = anchor
        val r = if (a != null && !follow) ray(fx, fy) else null
        if (a != null && r != null) {
            val q = planeHit(r, a[1], anchorT)
            moveTarget(a[0] - q[0], a[2] - q[2])
        }
        prevSpan = span
        prevAngle = angle
        prevFocusX = fx
        prevFocusY = fy
        cameraChanged()
    }

    /** Fingers up: look to the horizon. Fingers down: look down. */
    private fun tilt(e: MotionEvent) {
        val fy = focusY(e)
        camera.pitch = (camera.pitch + (fy - prevFocusY) / max(height, 1) * PI.toFloat() * 0.8f).coerceIn(MIN_PITCH, MAX_PITCH)
        prevFocusX = focusX(e)
        prevFocusY = fy
        prevSpan = span(e)
        prevAngle = angle(e)
        cameraChanged()
    }

    // ---- Scene ----

    /** A track path in scene coordinates (m), from 10 s before the exit. */
    private class TrackPath(
        val index: Int,
        /** The track. A new synchronization changes it to the same track with different times. */
        @Volatile var track: Track,
        val first: Int,
        val count: Int,
        val xyz: FloatArray,
        /** Height of the ground below each point (m, scene coordinates). */
        val ground: FloatArray,
        val exit: Int,
        val deploy: Int,
    ) {
        /** The position at [time] (s), between two points, or null if the path has no point near it. */
        fun positionAt(time: Double): FloatArray? {
            val t = track.t
            val k = track.indexAt(time)
            if (abs(t[k] - time) > 2) return null
            var a = k
            var b = k
            if (t[k] > time && k > 0) a = k - 1 else if (t[k] < time && k < track.size - 1) b = k + 1
            val ia = a - first
            val ib = b - first
            if (ia !in 0 until count || ib !in 0 until count) return null
            val f = if (t[b] == t[a]) 0f else ((time - t[a]) / (t[b] - t[a])).toFloat().coerceIn(0f, 1f)
            return FloatArray(3) { c -> xyz[3 * ia + c] + (xyz[3 * ib + c] - xyz[3 * ia + c]) * f }
        }
    }

    /**
     * The terrain mesh and the track paths. Scene coordinates are in m from the
     * center of the area: x east, y up from [base], z south.
     */
    private class Scene(
        val terrain: FloatArray,
        val indices: IntArray,
        private val heights: FloatArray,
        private val half: Float,
        private val step: Float,
        /** The side of the square area (m). */
        val size: Float,
        /** The area in Web Mercator units (256 for the world). */
        val x0: Double,
        val y0: Double,
        val side: Double,
        /** Height MSL of y = 0 (m). */
        val base: Float,
        val maxHeight: Float,
        /** The landing height (m, scene coordinates). At the height scale 0, the terrain is flat at this height. */
        val level: Float,
        /** A problem to show, or null. */
        val problem: String?,
    ) {
        val paths = ArrayList<TrackPath>()

        /** The distance between two points of the terrain mesh (m). */
        val cellSize get() = step

        /**
         * Height of the terrain at a scene point (m, scene coordinates), on the
         * triangles of the mesh.
         */
        fun groundAt(x: Float, z: Float): Float {
            val fx = ((x + half) / step).coerceIn(0f, GRID - 1.001f)
            val fz = ((z + half) / step).coerceIn(0f, GRID - 1.001f)
            val i = fx.toInt()
            val j = fz.toInt()
            val ax = fx - i
            val az = fz - j
            fun h(a: Int, b: Int) = heights[b * GRID + a]
            // The diagonal of each cell goes from (i + 1, j) to (i, j + 1)
            return if (ax + az <= 1) h(i, j) + ax * (h(i + 1, j) - h(i, j)) + az * (h(i, j + 1) - h(i, j))
            else h(i + 1, j + 1) + (1 - ax) * (h(i, j + 1) - h(i + 1, j + 1)) + (1 - az) * (h(i + 1, j) - h(i + 1, j + 1))
        }

        companion object {
            const val GRID = 257
            private const val MAX_IMAGE = 4096
            private const val MAX_TERRAIN_ZOOM = 15
            private const val EARTH = 40075016.686
            private const val PRE_EXIT_S = 10.0
            private const val CACHE_MAX_AGE_MS = 30L * 24 * 3600 * 1000
            private const val USER_AGENT = "FlySight-mdobak/1.0 (Android; private app for FlySight 2)"

            private val tileLoader = Executors.newFixedThreadPool(4)

            fun build(context: Context, tracks: List<Track>): Scene {
                // The parts of the tracks to show
                val firsts = tracks.map { it.indexBeforeExit(PRE_EXIT_S) }
                var minX = Double.MAX_VALUE
                var maxX = -Double.MAX_VALUE
                var minY = Double.MAX_VALUE
                var maxY = -Double.MAX_VALUE
                for ((n, track) in tracks.withIndex()) for (i in firsts[n] until track.size) {
                    val x = lonToX(track.lon[i])
                    val y = latToY(track.lat[i])
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
                val centerX = (minX + maxX) / 2
                val centerY = (minY + maxY) / 2
                val lat0 = yToLat(centerY)
                val metersPerUnit = EARTH * cos(Math.toRadians(lat0)) / 256
                // A square area with a margin around the tracks
                val span = max(maxX - minX, maxY - minY)
                val margin = max(1500 / metersPerUnit, span * 0.3)
                val side = span + 2 * margin
                val x0 = centerX - side / 2
                val y0 = centerY - side / 2

                val elevation = runCatching { loadElevation(context, x0, y0, side) }.getOrNull()

                // Heights of the grid, from the lowest point (m)
                val heights = FloatArray(GRID * GRID)
                val landing = tracks.minOf { it.groundMsl }.toFloat()
                for (j in 0 until GRID) for (i in 0 until GRID) {
                    val wx = x0 + side * i / (GRID - 1)
                    val wy = y0 + side * j / (GRID - 1)
                    heights[j * GRID + i] = elevation?.at(wx, wy) ?: landing
                }
                val step = (side / (GRID - 1) * metersPerUnit).toFloat()
                val base = min(heights.min(), landing)
                for (k in heights.indices) heights[k] -= base
                val half = (side / 2 * metersPerUnit).toFloat()

                // Vertex: x, y, z, u, v, dh/dx, dh/dz
                val terrain = FloatArray(GRID * GRID * 7)
                for (j in 0 until GRID) for (i in 0 until GRID) {
                    val k = (j * GRID + i) * 7
                    fun h(a: Int, b: Int) = heights[b.coerceIn(0, GRID - 1) * GRID + a.coerceIn(0, GRID - 1)]
                    terrain[k] = -half + i * step
                    terrain[k + 1] = h(i, j)
                    terrain[k + 2] = -half + j * step
                    terrain[k + 3] = i / (GRID - 1f)
                    terrain[k + 4] = j / (GRID - 1f)
                    terrain[k + 5] = (h(i + 1, j) - h(i - 1, j)) / (2 * step)
                    terrain[k + 6] = (h(i, j + 1) - h(i, j - 1)) / (2 * step)
                }
                val indices = IntArray((GRID - 1) * (GRID - 1) * 6)
                var n = 0
                for (j in 0 until GRID - 1) for (i in 0 until GRID - 1) {
                    val a = j * GRID + i
                    indices[n++] = a; indices[n++] = a + GRID; indices[n++] = a + 1
                    indices[n++] = a + 1; indices[n++] = a + GRID; indices[n++] = a + GRID + 1
                }

                val scene = Scene(terrain, indices, heights, half, step, (side * metersPerUnit).toFloat(),
                    x0, y0, side, base, heights.max(), landing - base, if (elevation == null) "No terrain data" else null)
                for ((index, track) in tracks.withIndex()) {
                    val first = firsts[index]
                    val count = track.size - first
                    val xyz = FloatArray(count * 3)
                    val ground = FloatArray(count)
                    for (i in 0 until count) {
                        val x = ((lonToX(track.lon[first + i]) - centerX) * metersPerUnit).toFloat()
                        val z = ((latToY(track.lat[first + i]) - centerY) * metersPerUnit).toFloat()
                        xyz[3 * i] = x
                        xyz[3 * i + 1] = (track.hMsl[first + i] - base).toFloat()
                        xyz[3 * i + 2] = z
                        ground[i] = scene.groundAt(x, z)
                    }
                    scene.paths += TrackPath(index, track, first, count, xyz, ground,
                        if (track.hasExit) track.exitIndex - first else -1,
                        if (track.deployIndex >= 0) track.deployIndex - first else -1)
                }
                return scene
            }

            /** Heights from Terrarium tiles: (R × 256 + G + B / 256) − 32768 m. */
            private class Elevation(val zoom: Int, val tx0: Int, val ty0: Int, val cols: Int, val rows: Int, val data: FloatArray) {
                fun at(wx: Double, wy: Double): Float {
                    val scale = 2.0.pow(zoom)
                    val px = (wx / 256 * scale - tx0) * 256 - 0.5
                    val py = (wy / 256 * scale - ty0) * 256 - 0.5
                    val w = cols * 256
                    val h = rows * 256
                    val i = floor(px).toInt().coerceIn(0, w - 2)
                    val j = floor(py).toInt().coerceIn(0, h - 2)
                    val ax = (px - i).toFloat().coerceIn(0f, 1f)
                    val ay = (py - j).toFloat().coerceIn(0f, 1f)
                    fun d(a: Int, b: Int) = data[b * w + a]
                    val top = d(i, j) * (1 - ax) + d(i + 1, j) * ax
                    val bottom = d(i, j + 1) * (1 - ax) + d(i + 1, j + 1) * ax
                    return top * (1 - ay) + bottom * ay
                }
            }

            private fun loadElevation(context: Context, x0: Double, y0: Double, side: Double): Elevation {
                // Not more than 4 tiles across the area
                var zoom = MAX_TERRAIN_ZOOM
                while (zoom > 1 && side * 2.0.pow(zoom) / 256 > 4) zoom--
                val scale = 2.0.pow(zoom) / 256
                val tx0 = floor(x0 * scale).toInt()
                val ty0 = floor(y0 * scale).toInt()
                val cols = floor((x0 + side) * scale).toInt() - tx0 + 1
                val rows = floor((y0 + side) * scale).toInt() - ty0 + 1
                val w = cols * 256
                val data = FloatArray(w * rows * 256)
                val tasks = (0 until rows).flatMap { r -> (0 until cols).map { c -> r to c } }.map { (r, c) ->
                    Callable {
                        val n = 1 shl zoom
                        val x = Math.floorMod(tx0 + c, n)
                        val y = ty0 + r
                        val bitmap = fetch(context, "terrain/$zoom/$x/$y.png",
                            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$zoom/$x/$y.png")
                            ?: throw IllegalStateException("No terrain tile $zoom/$x/$y")
                        val pixels = IntArray(256 * 256)
                        bitmap.getPixels(pixels, 0, 256, 0, 0, 256, 256)
                        for (py in 0 until 256) for (px in 0 until 256) {
                            val p = pixels[py * 256 + px]
                            val v = (Color.red(p) * 256 + Color.green(p) + Color.blue(p) / 256f) - 32768f
                            data[(r * 256 + py) * w + c * 256 + px] = v
                        }
                    }
                }
                tileLoader.invokeAll(tasks).forEach { it.get() }
                return Elevation(zoom, tx0, ty0, cols, rows, data)
            }

            /** The tile source of a style. */
            private class Source(val maxZoom: Int, val cacheName: (Int, Int, Int) -> String, val url: (Int, Int, Int) -> String)

            private fun source(style: MapStyle): Source? = when (style) {
                MapStyle.SATELLITE -> Source(15, { z, x, y -> "s2cloudless/$z/$x/$y.jpg" },
                    { z, x, y -> "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/GoogleMapsCompatible/$z/$y/$x.jpg" })
                // The same cache as the 2D map
                MapStyle.STREETS -> Source(16, { z, x, y -> "tiles/$z/$x/$y.png" }, { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" })
                MapStyle.TOPO -> Source(16, { z, x, y -> "opentopomap/$z/$x/$y.png" }, { z, x, y -> "https://tile.opentopomap.org/$z/$x/$y.png" })
                MapStyle.ELEVATION -> null
            }

            /** One image of the area, not more than [MAX_IMAGE] pixels across. */
            fun loadImage(context: Context, style: MapStyle, scene: Scene): Bitmap {
                val source = source(style) ?: throw IllegalArgumentException("No image")
                var zoom = source.maxZoom
                while (zoom > 1 && scene.side * 2.0.pow(zoom) > MAX_IMAGE) zoom--
                val scale = 2.0.pow(zoom)
                val size = ceil(scene.side * scale).toInt().coerceAtLeast(1)
                val px0 = scene.x0 * scale
                val py0 = scene.y0 * scale
                val image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(image)
                canvas.drawColor(if (style == MapStyle.SATELLITE) Color.rgb(140, 150, 130) else Color.rgb(242, 239, 233))
                val tx0 = floor(px0 / 256).toInt()
                val ty0 = floor(py0 / 256).toInt()
                val tx1 = floor((px0 + size) / 256).toInt()
                val ty1 = floor((py0 + size) / 256).toInt()
                val n = 1 shl zoom
                val tasks = (ty0..ty1).flatMap { ty -> (tx0..tx1).map { tx -> tx to ty } }.map { (tx, ty) ->
                    Callable {
                        val x = Math.floorMod(tx, n)
                        val bitmap = if (ty in 0 until n) fetch(context, source.cacheName(zoom, x, ty), source.url(zoom, x, ty)) else null
                        Triple(tx, ty, bitmap)
                    }
                }
                var missing = 0
                for (f in tileLoader.invokeAll(tasks)) {
                    val (tx, ty, bitmap) = f.get()
                    if (bitmap == null) {
                        missing++
                        continue
                    }
                    canvas.drawBitmap(bitmap, (tx * 256 - px0).toFloat(), (ty * 256 - py0).toFloat(), null)
                }
                if (missing == tasks.size) throw IllegalStateException("No image tiles")
                return image
            }

            /** A tile from the cache, or from the network. An old tile is better than no tile. */
            private fun fetch(context: Context, cacheName: String, url: String): Bitmap? {
                val file = File(context.cacheDir, cacheName)
                if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MAX_AGE_MS) {
                    BitmapFactory.decodeFile(file.path)?.let { return it }
                }
                fun old() = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                try {
                    if (connection.responseCode != 200) return old()
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return old()
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    return bitmap
                } catch (_: Exception) {
                    return old()
                } finally {
                    connection.disconnect()
                }
            }

            private fun lonToX(lon: Double) = (lon + 180.0) / 360.0 * 256

            private fun latToY(lat: Double): Double {
                val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
                return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 256
            }

            private fun yToLat(y: Double) = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * y / 256))))
        }
    }

    // ---- Rendering (GL thread) ----

    private class PathBuffers(val ribbon: Int, val curtain: Int, val ground: Int, val groundCount: Int)

    private inner class Renderer : GLSurfaceView.Renderer {
        private var terrainProgram = 0
        private var lineProgram = 0
        private var flatProgram = 0
        private var pointProgram = 0
        private var terrainBuffer = 0
        private var indexBuffer = 0
        private var indexCount = 0
        private var texture = 0
        private var image: Bitmap? = null
        private var textureImage: Bitmap? = null
        private var maxTexture = 2048
        private var uploaded: Scene? = null
        private val buffers = HashMap<Int, PathBuffers>()
        private var viewWidth = 1
        private var viewHeight = 1
        private val view = FloatArray(16)
        private val projection = FloatArray(16)
        private val mvp = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            terrainProgram = program(TERRAIN_VS, TERRAIN_FS)
            lineProgram = program(LINE_VS, FLAT_FS)
            flatProgram = program(FLAT_VS, FLAT_FS)
            pointProgram = program(POINT_VS, POINT_FS)
            val max = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
            maxTexture = max(max[0], 2048)
            // A new context has no buffers and no textures: upload again
            uploaded = null
            terrainBuffer = 0
            buffers.clear()
            texture = 0
            textureImage = null
            scene?.let { upload(it) }
            uploadImage(image)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = max(width, 1)
            viewHeight = max(height, 1)
            GLES30.glViewport(0, 0, width, height)
        }

        fun upload(s: Scene) {
            if (uploaded === s) return
            if (terrainBuffer != 0) GLES30.glDeleteBuffers(2, intArrayOf(terrainBuffer, indexBuffer), 0)
            for (b in buffers.values) GLES30.glDeleteBuffers(3, intArrayOf(b.ribbon, b.curtain, b.ground), 0)
            buffers.clear()
            val ids = IntArray(2)
            GLES30.glGenBuffers(2, ids, 0)
            terrainBuffer = ids[0]
            indexBuffer = ids[1]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, terrainBuffer)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, s.terrain.size * 4, floats(s.terrain), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, s.indices.size * 4, ints(s.indices), GLES30.GL_STATIC_DRAW)
            indexCount = s.indices.size

            for (p in s.paths) {
                val b = IntArray(3)
                GLES30.glGenBuffers(3, b, 0)
                fun put(id: Int, data: FloatArray) {
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
                    GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floats(data), GLES30.GL_STATIC_DRAW)
                }
                put(b[0], ribbon(p.xyz, p.count))
                put(b[1], curtain(p))
                val ground = groundLine(s, p)
                put(b[2], ribbon(ground, ground.size / 3))
                buffers[p.index] = PathBuffers(b[0], b[1], b[2], ground.size / 3)
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            uploaded = s
        }

        /** Set the image on the terrain, or remove it (null). */
        fun uploadImage(bitmap: Bitmap?) {
            image = bitmap
            if (bitmap === textureImage && (bitmap == null || texture != 0)) return
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
            textureImage = bitmap
            if (bitmap == null) return
            val fitted = if (bitmap.width > maxTexture) Bitmap.createScaledBitmap(bitmap, maxTexture, maxTexture, true) else bitmap
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            texture = t[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, fitted, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            if (GLES30.glGetString(GLES30.GL_EXTENSIONS)?.contains("GL_EXT_texture_filter_anisotropic") == true) {
                GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D, TEXTURE_MAX_ANISOTROPY, 8f)
            }
            if (fitted !== bitmap) fitted.recycle()
        }

        /** Two vertices per point for a line of constant width on the screen: pos, prev, next, side. */
        private fun ribbon(xyz: FloatArray, count: Int): FloatArray {
            val out = FloatArray(count * 2 * 10)
            for (i in 0 until count) {
                val prev = max(i - 1, 0)
                val next = min(i + 1, count - 1)
                for (side in 0..1) {
                    val k = (2 * i + side) * 10
                    for (c in 0..2) {
                        out[k + c] = xyz[3 * i + c]
                        out[k + 3 + c] = xyz[3 * prev + c]
                        out[k + 6 + c] = xyz[3 * next + c]
                    }
                    out[k + 9] = if (side == 0) -1f else 1f
                }
            }
            return out
        }

        /**
         * The path on the terrain. A straight line between two track points can
         * go below a hill of the terrain mesh. Thus the line has points not
         * more than half a mesh cell apart, each on the terrain.
         */
        private fun groundLine(s: Scene, p: TrackPath): FloatArray {
            val out = ArrayList<Float>(p.count * 3)
            val maxStep = s.cellSize / 2
            for (i in 0 until p.count) {
                val x = p.xyz[3 * i]
                val z = p.xyz[3 * i + 2]
                if (i > 0) {
                    val px = p.xyz[3 * i - 3]
                    val pz = p.xyz[3 * i - 1]
                    val parts = ceil(hypot(x - px, z - pz) / maxStep).toInt().coerceIn(1, 256)
                    for (k in 1 until parts) {
                        val f = k.toFloat() / parts
                        val sx = px + (x - px) * f
                        val sz = pz + (z - pz) * f
                        out += sx; out += s.groundAt(sx, sz); out += sz
                    }
                }
                out += x; out += p.ground[i]; out += z
            }
            return out.toFloatArray()
        }

        /** A vertical band from each point down to the ground. */
        private fun curtain(p: TrackPath): FloatArray {
            val out = FloatArray(p.count * 2 * 3)
            for (i in 0 until p.count) {
                val k = i * 6
                out[k] = p.xyz[3 * i]; out[k + 1] = p.xyz[3 * i + 1]; out[k + 2] = p.xyz[3 * i + 2]
                out[k + 3] = p.xyz[3 * i]; out[k + 4] = p.ground[i]; out[k + 5] = p.xyz[3 * i + 2]
            }
            return out
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClearColor(SKY[0], SKY[1], SKY[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            val s = uploaded ?: return
            // y = height × scale + offset. At the height scale 0, the terrain is flat.
            val pathScale = trackScale
            val groundScale = heightScale
            val groundOffset = if (groundScale == 0f) s.level else 0f
            cameraMatrices(view, projection, viewWidth, viewHeight)
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
            val shown = shownStyle
            val palette = if (shown == MapStyle.SATELLITE) Ui.TRACK_COLORS_DARK else Ui.TRACK_COLORS_LIGHT
            fun trackColor(i: Int) = palette[i % palette.size]

            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)

            // Terrain
            val hasTexture = texture != 0 && shown.hasImage
            GLES30.glUseProgram(terrainProgram)
            uniforms(terrainProgram, groundScale, groundOffset)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(terrainProgram, "uView"), 1, false, view, 0)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(terrainProgram, "uFog"), SKY[0], SKY[1], SKY[2])
            GLES30.glUniform1f(GLES30.glGetUniformLocation(terrainProgram, "uFogDensity"), 0.6f / s.size)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(terrainProgram, "uHasTexture"), if (hasTexture) 1f else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(terrainProgram, "uBase"), s.base)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(terrainProgram, "uMaxHeight"), max(s.maxHeight, 1f))
            GLES30.glUniform1f(GLES30.glGetUniformLocation(terrainProgram, "uContour"), contourInterval(s.maxHeight))
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasTexture) texture else 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(terrainProgram, "uTexture"), 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, terrainBuffer)
            attribute(0, 3, 7, 0)
            attribute(1, 2, 7, 3)
            attribute(2, 2, 7, 5)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
            for (a in 0..2) GLES30.glDisableVertexAttribArray(a)

            val hiddenNow = hidden
            val visible = s.paths.filter { it.index !in hiddenNow }
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            // Lines on the ground, a little above it, so that the terrain does not cover them
            GLES30.glUseProgram(lineProgram)
            uniforms(lineProgram, groundScale, groundOffset)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(lineProgram, "uViewport"), viewWidth.toFloat(), viewHeight.toFloat())
            val widthLocation = GLES30.glGetUniformLocation(lineProgram, "uWidth")
            val liftLocation = GLES30.glGetUniformLocation(lineProgram, "uLift")
            GLES30.glUniform1f(liftLocation, 1f + camera.distance * 0.002f)
            // A light colour with a thin dark edge: it shows on dark and on light maps.
            // The edge does not write depth, so that the colour line is not hidden by it.
            for (p in visible) {
                val b = buffers[p.index]!!
                bindRibbon(b.ground)
                GLES30.glDepthMask(false)
                GLES30.glUniform1f(widthLocation, 3.5f * density)
                color(lineProgram, Color.BLACK, 0.35f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, b.groundCount * 2)
                GLES30.glDepthMask(true)
                GLES30.glUniform1f(widthLocation, 2.2f * density)
                color(lineProgram, lighter(trackColor(p.index)), 1f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, b.groundCount * 2)
            }
            for (a in 0..3) GLES30.glDisableVertexAttribArray(a)

            // Curtains: transparent, behind the lines
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(flatProgram)
            uniforms(flatProgram, pathScale, 0f)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(flatProgram, "uGround"), groundScale, groundOffset)
            for (p in visible) {
                color(flatProgram, trackColor(p.index), 0.22f)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[p.index]!!.curtain)
                attribute(0, 3, 3, 0)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, p.count * 2)
            }
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDepthMask(true)

            // Tracks: a light casing, then the colour
            GLES30.glUseProgram(lineProgram)
            uniforms(lineProgram, pathScale, 0f)
            GLES30.glUniform1f(liftLocation, 0f)
            // The casing does not write depth. If it did, the colour line at almost the same
            // depth would fail the depth test at some pixels, and the line would have gaps.
            for (p in visible) {
                bindRibbon(buffers[p.index]!!.ribbon)
                GLES30.glDepthMask(false)
                GLES30.glUniform1f(widthLocation, 5 * density)
                color(lineProgram, Color.WHITE, 0.6f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, p.count * 2)
                GLES30.glDepthMask(true)
                GLES30.glUniform1f(widthLocation, 3 * density)
                color(lineProgram, trackColor(p.index), 1f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, p.count * 2)
            }
            // The parts that the terrain hides: a faint line through the terrain.
            // The terrain data has errors, and a track near the ground can go below it.
            GLES30.glDepthMask(false)
            GLES30.glDepthFunc(GLES30.GL_GREATER)
            GLES30.glUniform1f(widthLocation, 2 * density)
            for (p in visible) {
                bindRibbon(buffers[p.index]!!.ribbon)
                color(lineProgram, trackColor(p.index), 0.4f)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, p.count * 2)
            }
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glDepthMask(true)
            for (a in 0..3) GLES30.glDisableVertexAttribArray(a)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

            // Markers, on top of all: exit (a ring), landing (a dot), cursor
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glUseProgram(pointProgram)
            uniforms(pointProgram, pathScale, 0f)
            val cursor = cursorTime
            for (p in visible) {
                val c = trackColor(p.index)
                if (p.exit >= 0) marker(p.xyz, p.exit, Color.WHITE, c, 12f)
                marker(p.xyz, p.count - 1, c, Color.WHITE, 10f)
                val position = cursor?.let { p.positionAt(it) }
                if (position != null) marker(position, 0, c, Color.WHITE, 16f)
            }
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        /** The colour, half way to white. */
        private fun lighter(c: Int) = Color.rgb(
            (Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)

        private fun bindRibbon(id: Int) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
            attribute(0, 3, 10, 0)
            attribute(1, 3, 10, 3)
            attribute(2, 3, 10, 6)
            attribute(3, 1, 10, 9)
        }

        private fun marker(xyz: FloatArray, i: Int, fill: Int, ring: Int, sizeDp: Float) {
            GLES30.glVertexAttrib3f(0, xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2])
            GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProgram, "uSize"), sizeDp * density)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProgram, "uRing"), 1f - 4f / sizeDp)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(pointProgram, "uFill"), Color.red(fill) / 255f, Color.green(fill) / 255f, Color.blue(fill) / 255f, 1f)
            GLES30.glUniform4f(GLES30.glGetUniformLocation(pointProgram, "uRingColor"), Color.red(ring) / 255f, Color.green(ring) / 255f, Color.blue(ring) / 255f, 1f)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 1)
        }

        /** The matrix, and the vertical scale and offset: y = height × [scale] + [offset]. */
        private fun uniforms(program: Int, scale: Float, offset: Float) {
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uScale"), scale)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uOffset"), offset)
        }

        private fun color(program: Int, c: Int, alpha: Float) {
            GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "uColor"), Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, alpha)
        }

        private fun attribute(index: Int, size: Int, stride: Int, offset: Int) {
            GLES30.glEnableVertexAttribArray(index)
            GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride * 4, offset * 4)
        }

        private fun program(vs: String, fs: String): Int {
            fun shader(type: Int, source: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, source)
                GLES30.glCompileShader(s)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) throw IllegalStateException("Shader: " + GLES30.glGetShaderInfoLog(s))
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("Program: " + GLES30.glGetProgramInfoLog(p))
            return p
        }

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).apply { position(0) }

        private fun ints(a: IntArray): IntBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(a).apply { position(0) }
    }

    companion object {
        private const val FOV = 45.0
        private const val MIN_DISTANCE = 50f
        private const val MAX_DISTANCE = 200_000f
        private const val MIN_PITCH = 0.09f // 5°
        private const val MAX_PITCH = 1.55f // 89°
        private const val MIN_EYE_HEIGHT = 20f
        private const val FLING_DECAY = 4f
        /** The twist of two fingers (rad) that starts a turn of the map: 12°. */
        private const val ROTATE_THRESHOLD = 0.21f
        private const val TEXTURE_MAX_ANISOTROPY = 0x84FE

        /** The sky and the haze in the distance. */
        private val SKY = floatArrayOf(0.70f, 0.80f, 0.90f)

        /** Approximately 10 contour lines from the lowest to the highest point. */
        private fun contourInterval(range: Float): Float {
            val steps = floatArrayOf(5f, 10f, 20f, 25f, 50f, 100f, 200f, 250f, 500f, 1000f)
            return steps.firstOrNull { range / it <= 12 } ?: 1000f
        }

        private const val TERRAIN_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec2 aSlope;
uniform mat4 uMvp;
uniform mat4 uView;
uniform float uScale;
uniform float uOffset;
out vec2 vUv;
out float vShade;
out float vDistance;
out float vHeight;
void main() {
    vec4 p = vec4(aPos.x, aPos.y * uScale + uOffset, aPos.z, 1.0);
    gl_Position = uMvp * p;
    vec3 n = normalize(vec3(-aSlope.x * uScale, 1.0, -aSlope.y * uScale));
    // Light from the north-west, as on relief maps
    vShade = 0.72 + 0.4 * max(dot(n, normalize(vec3(-1.0, 1.4, -1.0))), 0.0);
    vUv = aUv;
    vDistance = length((uView * p).xyz);
    vHeight = aPos.y;
}
"""

        /** The image, or colours by height with contour lines (every fifth line is darker). */
        private const val TERRAIN_FS = """#version 300 es
precision highp float;
in vec2 vUv;
in float vShade;
in float vDistance;
in float vHeight;
uniform sampler2D uTexture;
uniform float uHasTexture;
uniform vec3 uFog;
uniform float uFogDensity;
uniform float uBase;
uniform float uMaxHeight;
uniform float uContour;
out vec4 color;
vec3 ramp(float x) {
    x = clamp(x, 0.0, 1.0) * 4.0;
    vec3 c0 = vec3(0.36, 0.58, 0.36);
    vec3 c1 = vec3(0.70, 0.79, 0.49);
    vec3 c2 = vec3(0.91, 0.84, 0.60);
    vec3 c3 = vec3(0.74, 0.58, 0.44);
    vec3 c4 = vec3(0.95, 0.94, 0.93);
    if (x < 1.0) return mix(c0, c1, x);
    if (x < 2.0) return mix(c1, c2, x - 1.0);
    if (x < 3.0) return mix(c2, c3, x - 2.0);
    return mix(c3, c4, x - 3.0);
}
void main() {
    vec3 c;
    if (uHasTexture > 0.5) {
        c = texture(uTexture, vUv).rgb;
    } else {
        c = ramp(vHeight / uMaxHeight);
        float h = (vHeight + uBase) / uContour;
        float w = max(fwidth(h), 1e-4);
        float minor = 1.0 - smoothstep(0.5, 1.5, abs(fract(h + 0.5) - 0.5) / w);
        float major = 1.0 - smoothstep(0.8, 2.0, abs(fract(h / 5.0 + 0.5) - 0.5) * 5.0 / w);
        float fade = 1.0 - smoothstep(0.2, 0.5, w);
        c *= 1.0 - fade * max(0.22 * minor, 0.45 * major);
    }
    c *= vShade;
    float fog = clamp(1.0 - exp(-vDistance * uFogDensity), 0.0, 0.7);
    color = vec4(mix(c, uFog, fog), 1.0);
}
"""

        /** Moves each vertex sideways on the screen, so that the line has the same width at all distances. */
        private const val LINE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aPrev;
layout(location = 2) in vec3 aNext;
layout(location = 3) in float aSide;
uniform mat4 uMvp;
uniform float uScale;
uniform float uOffset;
uniform float uLift;
uniform vec2 uViewport;
uniform float uWidth;
vec4 clip(vec3 a) { return uMvp * vec4(a.x, a.y * uScale + uOffset + uLift, a.z, 1.0); }
void main() {
    vec4 c = clip(aPos);
    vec4 a = clip(aPrev);
    vec4 b = clip(aNext);
    vec2 d = b.xy / b.w * uViewport - a.xy / a.w * uViewport;
    d = length(d) < 0.0001 ? vec2(1.0, 0.0) : normalize(d);
    c.xy += vec2(-d.y, d.x) * aSide * uWidth / uViewport * c.w;
    gl_Position = c;
}
"""

        /** The curtain: the even vertices are on the track, the odd vertices on the ground ([uGround]: scale, offset). */
        private const val FLAT_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
uniform float uScale;
uniform float uOffset;
uniform vec2 uGround;
void main() {
    float y = gl_VertexID % 2 == 0 ? aPos.y * uScale + uOffset : aPos.y * uGround.x + uGround.y;
    gl_Position = uMvp * vec4(aPos.x, y, aPos.z, 1.0);
}
"""

        private const val FLAT_FS = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 color;
void main() { color = uColor; }
"""

        private const val POINT_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
uniform float uScale;
uniform float uOffset;
uniform float uSize;
void main() {
    gl_Position = uMvp * vec4(aPos.x, aPos.y * uScale + uOffset, aPos.z, 1.0);
    gl_PointSize = uSize;
}
"""

        /** A round marker with a ring. [uRing] is the inner radius of the ring (0 to 1). */
        private const val POINT_FS = """#version 300 es
precision mediump float;
uniform vec4 uFill;
uniform vec4 uRingColor;
uniform float uRing;
out vec4 color;
void main() {
    float r = length(gl_PointCoord - 0.5) * 2.0;
    if (r > 1.0) discard;
    color = r > uRing ? uRingColor : uFill;
}
"""
    }
}
