package com.mdobak.flysightstatus

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Line chart of one or more tracks against time (normally from the exit).
 *
 * With one track, each series has its own colour. With more tracks, each
 * track has its own colour and each series has its own line style.
 *
 * One finger moves the cursor. Two fingers zoom and move the time axis.
 * A double tap shows the full time range.
 */
@SuppressLint("ViewConstructor")
class TrackChartView(context: Context, private val ui: Ui) : View(context) {

    enum class Series(val label: String, val short: String, val paletteIndex: Int, val axis: Axis, val unit: String) {
        H_SPEED("Horizontal", "H", 0, Axis.SPEED, "km/h"),
        V_SPEED("Vertical", "V", 1, Axis.SPEED, "km/h"),
        TOTAL_SPEED("Total", "T", 2, Axis.SPEED, "km/h"),
        ALTITUDE("Altitude", "Alt", 4, Axis.ALTITUDE, "m"),
        GLIDE("Glide ratio", "GR", 3, Axis.GLIDE, ""),
        ;

        fun value(track: Track, i: Int): Double = when (this) {
            H_SPEED -> track.hSpeed[i] * 3.6
            V_SPEED -> track.velD[i] * 3.6
            TOTAL_SPEED -> track.totalSpeed[i] * 3.6
            ALTITUDE -> track.altitude[i]
            GLIDE -> track.glideRatio[i]
        }
    }

    enum class Axis { SPEED, ALTITUDE, GLIDE }

    var onCursorChanged: ((Double) -> Unit)? = null

    private var tracks: List<Track> = emptyList()
    private var labels: List<String> = emptyList()
    private val hidden = mutableSetOf<Int>()
    private val visible = mutableSetOf(Series.H_SPEED, Series.V_SPEED, Series.ALTITUDE)
    private var fullMin = 0.0
    private var fullMax = 1.0
    private var xMin = 0.0
    private var xMax = 1.0
    var cursor = 0.0
        private set

    private val density = resources.displayMetrics.density
    private val padLeft = 44 * density
    private val padRight = 44 * density
    private val padTop = 8 * density
    private val padBottom = 22 * density

    private val textColor = ui.onSurfaceVariant
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint().apply {
        color = ui.outlineVariant
        strokeWidth = density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics).coerceAtMost(11 * density * 1.3f)
    }
    private val cursorPaint = Paint().apply {
        color = ui.onSurface
        strokeWidth = density
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.surfaceContainerHigh }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ui.outlineVariant
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    private val multi get() = tracks.size > 1

    /** The track that the user changes now, or -1. The other tracks show less. */
    private var focusTrack = -1
    /** 0: no focus. 1: the other tracks are pale and thin. */
    private var focusAmount = 0f
    private var focusAnimator: ValueAnimator? = null

    /** Show the track [index] clearly and the other tracks less. Null shows all tracks equally. */
    fun setFocus(index: Int?) {
        if (index != null) focusTrack = index
        val target = if (index != null) 1f else 0f
        if (focusAmount == target && focusAnimator?.isRunning != true) return
        focusAnimator?.cancel()
        focusAnimator = ValueAnimator.ofFloat(focusAmount, target).apply {
            duration = FOCUS_MS
            addUpdateListener {
                focusAmount = it.animatedValue as Float
                invalidate()
            }
            if (index == null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (focusAmount == 0f) focusTrack = -1
                }
            })
            start()
        }
    }

    /** Show the time range [from] to [to] (s), in the range of the tracks. */
    fun showRange(from: Double, to: Double) {
        val a = from.coerceIn(fullMin, fullMax)
        val b = to.coerceIn(fullMin, fullMax)
        if (b - a < MIN_SPAN_S) {
            resetZoom()
            return
        }
        xMin = a
        xMax = b
        invalidate()
    }

    /** Line colour of a series of a track. */
    fun colorOf(series: Series, track: Int): Int =
        if (multi) trackColor(track) else ui.trackColors[series.paletteIndex]

    fun trackColor(track: Int): Int = ui.trackColors[track % ui.trackColors.size]

    /** Line style of a series. With one track, all lines are solid. */
    fun dashOf(series: Series): DashPathEffect? = if (!multi) null else dashFor(series, density)

    /** New names of the tracks, for the values at the cursor. */
    fun setLabels(names: List<String>) {
        labels = names
        invalidate()
    }

    fun setTracks(list: List<Track>, names: List<String>) {
        tracks = list
        labels = names
        hidden.clear()
        fullMin = list.minOf { it.t.first() }
        fullMax = list.maxOf { it.t.last() }
        if (fullMax <= fullMin) fullMax = fullMin + 1
        resetZoom()
        cursor = list.filter { it.hasExit }.minOfOrNull { it.exitTime } ?: fullMin
    }

    /**
     * Use the same tracks with different times (a new synchronization). The
     * hidden tracks, the cursor and the zoom stay. A chart that shows all
     * of the time shows all of the new time.
     */
    fun updateTimes(list: List<Track>) {
        val all = xMin <= fullMin && xMax >= fullMax
        val span = xMax - xMin
        tracks = list
        fullMin = list.minOf { it.t.first() }
        fullMax = list.maxOf { it.t.last() }
        if (fullMax <= fullMin) fullMax = fullMin + 1
        if (all || span >= fullMax - fullMin) {
            resetZoom()
        } else {
            xMin = xMin.coerceIn(fullMin, fullMax - span)
            xMax = xMin + span
            invalidate()
        }
    }

    /** Move the cursor to [time] (s). If the cursor is not in the visible range, move the range. */
    fun showCursor(time: Double) {
        cursor = time
        val span = xMax - xMin
        if (time < xMin || time > xMax) {
            xMin = (time - span * 0.1).coerceIn(fullMin, max(fullMin, fullMax - span))
            xMax = xMin + span
        }
        invalidate()
    }

    fun setVisible(series: Series, on: Boolean) {
        if (on) visible += series else visible -= series
        invalidate()
    }

    fun isVisible(series: Series) = series in visible

    fun setTrackVisible(index: Int, on: Boolean) {
        if (on) hidden -= index else hidden += index
        invalidate()
    }

    private fun shown() = tracks.withIndex().filter { it.index !in hidden }

    private fun resetZoom() {
        xMin = fullMin
        xMax = fullMax
        invalidate()
    }

    // ---- Coordinates ----

    private val plotWidth get() = width - padLeft - padRight
    private val plotHeight get() = height - padTop - padBottom

    private fun xOf(time: Double) = (padLeft + (time - xMin) / (xMax - xMin) * plotWidth).toFloat()

    private fun timeOf(x: Float) = xMin + (x - padLeft) / plotWidth * (xMax - xMin)

    private fun yOf(value: Double, range: ClosedFloatingPointRange<Double>) =
        (padTop + plotHeight - (value - range.start) / (range.endInclusive - range.start) * plotHeight).toFloat()

    /** Value range of the visible series of one axis in the visible time range. */
    private fun range(axis: Axis): ClosedFloatingPointRange<Double>? {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (s in visible) {
            if (s.axis != axis) continue
            for ((_, track) in shown()) {
                val from = track.indexAt(xMin)
                val to = track.indexAt(xMax)
                for (i in from..to) {
                    val v = s.value(track, i)
                    if (v.isNaN()) continue
                    lo = min(lo, v)
                    hi = max(hi, v)
                }
            }
        }
        if (lo > hi) return null
        if (axis == Axis.GLIDE) {
            lo = 0.0
            hi = min(hi, 6.0)
        }
        lo = min(lo, 0.0)
        if (hi - lo < 1) hi = lo + 1
        val step = niceStep(hi - lo)
        return floor(lo / step) * step..ceil(hi / step) * step
    }

    private fun niceStep(span: Double, targetLines: Int = 5): Double {
        val raw = span / targetLines
        val mag = 10.0.pow(floor(log10(raw)))
        val norm = raw / mag
        return mag * when {
            norm < 1.5 -> 1.0
            norm < 3.5 -> 2.0
            norm < 7.5 -> 5.0
            else -> 10.0
        }
    }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        if (tracks.isEmpty() || plotWidth <= 0 || plotHeight <= 0) return

        val ranges = Axis.entries.associateWith { range(it) }
        val left = ranges[Axis.SPEED] ?: ranges[Axis.ALTITUDE] ?: ranges[Axis.GLIDE]
        val leftAxis = when (left) {
            ranges[Axis.SPEED] -> Axis.SPEED
            ranges[Axis.ALTITUDE] -> Axis.ALTITUDE
            else -> Axis.GLIDE
        }
        val right = if (leftAxis == Axis.SPEED) ranges[Axis.ALTITUDE] else null

        // Horizontal grid and the axis labels
        if (left != null) {
            val step = niceStep(left.endInclusive - left.start)
            var v = left.start
            textPaint.textAlign = Paint.Align.RIGHT
            while (v <= left.endInclusive + step / 2) {
                val y = yOf(v, left)
                canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
                canvas.drawText(formatAxis(v), padLeft - 4 * density, y + 4 * density, textPaint)
                if (right != null) {
                    val rv = right.start + (v - left.start) / (left.endInclusive - left.start) * (right.endInclusive - right.start)
                    textPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(formatAxis(rv), width - padRight + 4 * density, y + 4 * density, textPaint)
                    textPaint.textAlign = Paint.Align.RIGHT
                }
                v += step
            }
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(axisUnit(leftAxis), 2 * density, padTop + 10 * density, textPaint)
            if (right != null) {
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("m", width.toFloat() - 2 * density, padTop + 10 * density, textPaint)
            }
        }

        // Vertical grid and the time labels
        val tStep = niceStep(xMax - xMin, 6)
        var tv = ceil(xMin / tStep) * tStep
        textPaint.textAlign = Paint.Align.CENTER
        while (tv <= xMax) {
            val x = xOf(tv)
            canvas.drawLine(x, padTop, x, padTop + plotHeight, gridPaint)
            canvas.drawText(formatAxis(tv) + " s", x, height - 6 * density, textPaint)
            tv += tStep
        }

        // Lines
        canvas.save()
        canvas.clipRect(padLeft, padTop, width - padRight, padTop + plotHeight)
        val glide = ranges[Axis.GLIDE]
        // The track in focus is on top of the other tracks
        for ((n, track) in shown().sortedBy { it.index == focusTrack }) {
            val dim = if (focusTrack >= 0 && n != focusTrack) focusAmount else 0f
            linePaint.strokeWidth = (2 - dim) * density
            val from = max(0, track.indexAt(xMin) - 1)
            val to = min(track.size - 1, track.indexAt(xMax) + 1)
            val step = max(1, (to - from) / max(1, (plotWidth * 2).toInt()))
            for (s in Series.entries) {
                if (s !in visible) continue
                val range = when (s.axis) {
                    Axis.SPEED -> ranges[Axis.SPEED]
                    Axis.ALTITUDE -> ranges[Axis.ALTITUDE]
                    Axis.GLIDE -> glide
                } ?: continue
                path.reset()
                var penDown = false
                var i = from
                while (i <= to) {
                    val v = s.value(track, i)
                    if (v.isNaN()) {
                        penDown = false
                    } else {
                        val x = xOf(track.t[i])
                        val y = yOf(min(v, range.endInclusive), range)
                        if (penDown) path.lineTo(x, y) else path.moveTo(x, y)
                        penDown = true
                    }
                    i += step
                }
                linePaint.color = colorOf(s, n)
                linePaint.alpha = ((1 - 0.5f * dim) * 255).toInt()
                linePaint.pathEffect = dashOf(s)
                canvas.drawPath(path, linePaint)
            }
        }
        canvas.restore()
        linePaint.strokeWidth = 2 * density

        // Cursor and the values at the cursor
        if (cursor in xMin..xMax) {
            val x = xOf(cursor)
            canvas.drawLine(x, padTop, x, padTop + plotHeight, cursorPaint)
            drawReadout(canvas, x)
        }
    }

    private fun valueText(s: Series, v: Double) = when {
        v.isNaN() -> "–"
        s == Series.GLIDE -> String.format(Locale.US, "%.2f", v)
        else -> TrackStore.formatInt(v)
    }

    private fun drawReadout(canvas: Canvas, cursorX: Float) {
        // Each line: a colour dot (or none) and a text
        val lines = mutableListOf<Pair<String, Int?>>()
        lines += String.format(Locale.US, "%.1f s", cursor) to null
        for ((n, track) in shown()) {
            val i = track.indexAt(cursor)
            if (abs(track.t[i] - cursor) > 2) continue
            if (multi) {
                val values = Series.entries.filter { it in visible }.joinToString("  ") {
                    "${it.short} ${valueText(it, it.value(track, i))}"
                }
                lines += "${labels.getOrElse(n) { "${n + 1}" }}  $values" to trackColor(n)
            } else {
                for (s in Series.entries) {
                    if (s !in visible) continue
                    val unit = if (s.unit.isEmpty()) "" else " ${s.unit}"
                    lines += "${s.label} ${valueText(s, s.value(track, i))}$unit" to colorOf(s, n)
                }
            }
        }
        val lineHeight = textPaint.textSize * 1.45f
        val pad = 8 * density
        val dot = 8 * density
        textPaint.textAlign = Paint.Align.LEFT
        val textWidth = lines.maxOf { textPaint.measureText(it.first) + if (it.second != null) dot + 6 * density else 0f }
        val boxWidth = textWidth + 2 * pad
        val boxHeight = lines.size * lineHeight + pad
        // Show the box on the side of the cursor that has more space
        var left = if (cursorX - padLeft > width - padRight - cursorX) cursorX - boxWidth - 8 * density else cursorX + 8 * density
        left = left.coerceIn(0f, max(0f, width - boxWidth))
        box.set(left, padTop, left + boxWidth, padTop + boxHeight)
        canvas.drawRoundRect(box, 8 * density, 8 * density, boxPaint)
        canvas.drawRoundRect(box, 8 * density, 8 * density, boxStroke)
        for ((k, line) in lines.withIndex()) {
            val baseline = padTop + pad / 2 + (k + 1) * lineHeight - lineHeight * 0.3f
            var x = left + pad
            line.second?.let {
                dotPaint.color = it
                canvas.drawCircle(x + dot / 2, baseline - textPaint.textSize * 0.35f, dot / 2, dotPaint)
                x += dot + 6 * density
            }
            textPaint.color = if (k == 0) textColor else ui.onSurface
            canvas.drawText(line.first, x, baseline, textPaint)
        }
        textPaint.color = textColor
    }

    private fun axisUnit(axis: Axis) = when (axis) {
        Axis.SPEED -> "km/h"
        Axis.ALTITUDE -> "m"
        Axis.GLIDE -> "ratio"
    }

    private fun formatAxis(v: Double) =
        if (abs(v) >= 10 || v == floor(v)) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

    // ---- Touch ----

    private var scaling = false
    private var lastFocusX = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            lastFocusX = detector.focusX
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = if (detector.previousSpanX > 10 * density) detector.previousSpanX / max(1f, detector.currentSpanX) else 1f
            val focusTime = timeOf(detector.focusX)
            val fullSpan = fullMax - fullMin
            var span = ((xMax - xMin) * factor).coerceIn(min(MIN_SPAN_S, fullSpan), fullSpan)
            // Move with the fingers
            val shift = (detector.focusX - lastFocusX) / plotWidth * span
            lastFocusX = detector.focusX
            var newMin = focusTime - (detector.focusX - padLeft) / plotWidth * span - shift
            // Floating-point errors can put fullMax - span a little below fullMin.
            newMin = newMin.coerceIn(fullMin, max(fullMin, fullMax - span))
            span = min(span, fullMax - newMin)
            xMin = newMin
            xMax = newMin + span
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            moveCursor(e.x)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scaling) moveCursor(e2.x)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetZoom()
            return true
        }
    })

    private fun moveCursor(x: Float) {
        cursor = timeOf(x.coerceIn(padLeft, max(padLeft, width - padRight)))
        onCursorChanged?.invoke(cursor)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Do not let a parent view take the gesture
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount == 1 && !scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) scaling = false
        return true
    }

    companion object {
        private const val MIN_SPAN_S = 5.0
        private const val FOCUS_MS = 150L

        /** Line styles for the series when the chart compares tracks. */
        fun dashFor(series: Series, density: Float): DashPathEffect? = when (series) {
            Series.H_SPEED -> null
            Series.V_SPEED -> DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
            Series.TOTAL_SPEED -> DashPathEffect(floatArrayOf(1 * density, 4 * density), 0f)
            Series.ALTITUDE -> DashPathEffect(floatArrayOf(16 * density, 5 * density), 0f)
            Series.GLIDE -> DashPathEffect(floatArrayOf(10 * density, 4 * density, 1 * density, 4 * density), 0f)
        }
    }
}
