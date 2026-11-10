package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Two panes with a drag handle between them. [mode] shows the first pane,
 * both panes, or the second pane. Drag the handle to change [ratio].
 * Drag the handle near an edge and release it to show only one pane.
 * A double tap on the handle sets the panes to the same size.
 */
@SuppressLint("ViewConstructor")
class SplitLayout(private val ui: Ui, private val first: View, private val second: View) : ViewGroup(ui.context) {

    enum class Mode { FIRST, BOTH, SECOND }

    var mode = Mode.BOTH
        set(value) {
            field = value
            first.visibility = if (value == Mode.SECOND) GONE else VISIBLE
            second.visibility = if (value == Mode.FIRST) GONE else VISIBLE
            requestLayout()
            invalidate()
        }

    /** Part of the space for the first pane, 0.15 to 0.85. */
    var ratio = 0.5f
        set(value) {
            field = value.coerceIn(MIN_RATIO, MAX_RATIO)
            requestLayout()
        }

    /** True: the first pane is above the second. False: side by side. */
    var vertical = true
        set(value) {
            field = value
            requestLayout()
        }

    var onRatioChanged: ((Float) -> Unit)? = null

    /** Called when a drag to an edge changes the mode. */
    var onModeChanged: ((Mode) -> Unit)? = null

    /** The ratio during a drag. It can go nearer to the edges than [ratio]. */
    private var dragRatio: Float? = null

    private val handleSize = ui.dp(24)
    private val touchSize = ui.dp(48)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    private var dragging = false
    private var lastDownMs = 0L

    init {
        addView(first)
        addView(second)
        setWillNotDraw(false)
        contentDescription = "Drag to change the size of the chart and the map"
    }

    private val total get() = (if (vertical) height else width) - handleSize

    private fun firstSize(totalSize: Int) = when (mode) {
        Mode.FIRST -> totalSize + handleSize
        Mode.SECOND -> 0
        Mode.BOTH -> (totalSize * (dragRatio ?: ratio)).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val totalSize = (if (vertical) h else w) - handleSize
        val a = firstSize(totalSize)
        val b = if (mode == Mode.BOTH) totalSize - a else if (mode == Mode.SECOND) totalSize + handleSize else 0
        fun exact(size: Int) = MeasureSpec.makeMeasureSpec(maxOf(0, size), MeasureSpec.EXACTLY)
        if (vertical) {
            first.measure(exact(w), exact(a))
            second.measure(exact(w), exact(b))
        } else {
            first.measure(exact(a), exact(h))
            second.measure(exact(b), exact(h))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val a = firstSize(total)
        val secondStart = if (mode == Mode.SECOND) 0 else a + handleSize
        if (vertical) {
            first.layout(0, 0, width, a)
            second.layout(0, secondStart, width, secondStart + second.measuredHeight)
        } else {
            first.layout(0, 0, a, height)
            second.layout(secondStart, 0, secondStart + second.measuredWidth, height)
        }
    }

    private fun handleCenter() = firstSize(total) + handleSize / 2f

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (mode != Mode.BOTH) return
        val c = handleCenter()
        // In the snap area the pill is longer: release to show only one pane.
        val snap = dragRatio?.let { it < SNAP || it > 1 - SNAP } ?: false
        val long = ui.dpf(if (snap) 72f else if (dragging) 48f else 32f)
        val short = ui.dpf(4f)
        pillPaint.color = if (dragging) ui.primary else ui.alpha(ui.onSurfaceVariant, 0.6f)
        if (vertical) pill.set(width / 2f - long / 2, c - short / 2, width / 2f + long / 2, c + short / 2)
        else pill.set(c - short / 2, height / 2f - long / 2, c + short / 2, height / 2f + long / 2)
        canvas.drawRoundRect(pill, short / 2, short / 2, pillPaint)
    }

    private fun onHandle(ev: MotionEvent): Boolean {
        if (mode != Mode.BOTH) return false
        val pos = if (vertical) ev.y else ev.x
        return abs(pos - handleCenter()) <= touchSize / 2f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        ev.actionMasked == MotionEvent.ACTION_DOWN && onHandle(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!onHandle(ev)) return false
                if (ev.eventTime - lastDownMs < DOUBLE_TAP_MS) {
                    ratio = 0.5f
                    onRatioChanged?.invoke(ratio)
                }
                lastDownMs = ev.eventTime
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val pos = if (vertical) ev.y else ev.x
                dragRatio = ((pos - handleSize / 2f) / total).coerceIn(DRAG_MIN, 1 - DRAG_MIN)
                requestLayout()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val r = dragRatio
                dragRatio = null
                when {
                    r == null || ev.actionMasked == MotionEvent.ACTION_CANCEL -> requestLayout()
                    r < SNAP -> {
                        mode = Mode.SECOND
                        onModeChanged?.invoke(mode)
                    }
                    r > 1 - SNAP -> {
                        mode = Mode.FIRST
                        onModeChanged?.invoke(mode)
                    }
                    else -> {
                        ratio = r
                        onRatioChanged?.invoke(ratio)
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val MIN_RATIO = 0.15f
        const val MAX_RATIO = 0.85f
        const val DOUBLE_TAP_MS = 300L
        /** Release the handle nearer to an edge than this to show one pane. */
        const val SNAP = 0.1f
        const val DRAG_MIN = 0.04f
    }
}
