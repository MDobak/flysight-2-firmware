package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A Material 3 Expressive slider with discrete values: a thick track, a narrow
 * vertical handle with a gap on each side, and a dot at each stop.
 *
 * A horizontal drag moves the handle. A vertical drag goes to the scroll view.
 * A tap moves the handle to the tapped stop. [onChange] gets each value during
 * a drag; [onCommit] gets the value when the touch stops.
 */
@SuppressLint("ViewConstructor")
class M3Slider(
    private val ui: Ui,
    private val range: IntRange,
    value: Int,
    private val format: (Int) -> String,
) : View(ui.context) {

    var onChange: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null

    var value = value.coerceIn(range)
        private set

    private val steps = range.last - range.first
    private val density = resources.displayMetrics.density
    private val trackHeight = 16 * density
    private val handleHeight = 44 * density
    private val handleWidth = 4 * density
    private val pressedHandleWidth = 2 * density
    private val gap = 6 * density
    private val stopRadius = 2 * density
    private val outer = 8 * density
    private val inner = 2 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.primary }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.secondaryContainer }
    private val onActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.onPrimary }
    private val onInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.onSecondaryContainer }
    private val path = Path()
    private val rect = RectF()

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var pressed = false

    init {
        isFocusable = true
        isClickable = true
        minimumHeight = handleHeight.roundToInt()
        // The ends of the track are inside the padding, so the handle is not cut at the ends.
        setPadding((8 * density).roundToInt(), 0, (8 * density).roundToInt(), 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(handleHeight.roundToInt() + paddingTop + paddingBottom, heightMeasureSpec))
    }

    private val left get() = paddingLeft.toFloat()
    private val right get() = (width - paddingRight).toFloat()

    private fun xOf(v: Int): Float = if (steps == 0) left else left + (right - left) * (v - range.first) / steps

    private fun valueAt(x: Float): Int {
        if (steps == 0 || right <= left) return range.first
        val f = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return range.first + (f * steps).roundToInt()
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val top = cy - trackHeight / 2
        val bottom = cy + trackHeight / 2
        val hx = xOf(value)
        val hw = if (pressed) pressedHandleWidth else handleWidth

        // The active track from the start to the handle, then the inactive track to the end.
        val activeEnd = hx - hw / 2 - gap
        if (activeEnd > left) roundRect(canvas, left, top, activeEnd, bottom, outer, inner, activePaint)
        val inactiveStart = hx + hw / 2 + gap
        if (inactiveStart < right) roundRect(canvas, inactiveStart, top, right, bottom, inner, outer, inactivePaint)

        // Stops. Many stops would be noise, so then only the end stop shows.
        if (steps in 1..12) {
            for (i in 0..steps) {
                val v = range.first + i
                if (v == value) continue
                val x = xOf(v).coerceIn(left + outer, right - outer)
                if (abs(x - hx) < hw / 2 + gap + stopRadius) continue
                canvas.drawCircle(x, cy, stopRadius, if (v < value) onActivePaint else onInactivePaint)
            }
        } else if (value != range.last && right - outer - hx > hw / 2 + gap + stopRadius) {
            canvas.drawCircle(right - outer, cy, stopRadius, onInactivePaint)
        }

        // The handle.
        rect.set(hx - hw / 2, cy - handleHeight / 2, hx + hw / 2, cy + handleHeight / 2)
        canvas.drawRoundRect(rect, hw / 2, hw / 2, activePaint)
    }

    /** A rectangle with radius [l] at the left corners and radius [r] at the right corners. */
    private fun roundRect(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, l: Float, r: Float, paint: Paint) {
        val w = x2 - x1
        val lr = minOf(l, w / 2)
        val rr = minOf(r, w / 2)
        path.reset()
        rect.set(x1, y1, x2, y2)
        path.addRoundRect(rect, floatArrayOf(lr, lr, rr, rr, rr, rr, lr, lr), Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    private fun set(v: Int, fromUser: Boolean) {
        val n = v.coerceIn(range)
        if (n == value) return
        value = n
        if (fromUser) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        stateDescription = format(n)
        onChange?.invoke(n)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx > slop && dx > dy) {
                        dragging = true
                        pressed = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (dragging) set(valueAt(event.x), fromUser = true)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) set(valueAt(event.x), fromUser = true)
                finishTouch()
                onCommit?.invoke(value)
            }
            MotionEvent.ACTION_CANCEL -> finishTouch()
        }
        return true
    }

    private fun finishTouch() {
        dragging = false
        pressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        set(value + delta, fromUser = false)
        onCommit?.invoke(value)
        return true
    }

    // ---- Accessibility ----

    override fun getAccessibilityClassName(): CharSequence = "android.widget.SeekBar"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, range.first.toFloat(), range.last.toFloat(), value.toFloat())
        info.stateDescription = format(value)
        if (value > range.first) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (value < range.last) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val n = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> value + 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> value - 1
            android.R.id.accessibilityActionSetProgress ->
                arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.roundToInt() ?: return false
            else -> return super.performAccessibilityAction(action, arguments)
        }
        set(n, fromUser = false)
        onCommit?.invoke(value)
        return true
    }
}
