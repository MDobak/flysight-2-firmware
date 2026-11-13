package com.mdobak.flysightstatus

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A slider that sets the speed of a change, not a value. The handle is at the
 * centre. Drag it to the left or to the right: the value changes in that
 * direction, and more quickly when the handle is farther from the centre.
 * When the touch stops, the handle goes back to the centre and the change stops.
 *
 * The drag is relative: the handle moves by the distance of the finger, from the
 * point where the touch started. A vertical drag goes to the scroll view.
 */
@SuppressLint("ViewConstructor")
class JogSlider(private val ui: Ui) : View(ui.context) {

    /** The change of the value (s) in one frame of a drag. */
    var onJog: ((Double) -> Unit)? = null
    /** One small step: -1 or +1 (keyboard and accessibility). */
    var onStep: ((Int) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackHeight = 16 * density
    private val handleHeight = 44 * density
    private val handleWidth = 4 * density
    private val pressedHandleWidth = 2 * density
    private val gap = 6 * density
    private val outer = 8 * density
    private val inner = 2 * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.primary }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.secondaryContainer }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.onSecondaryContainer
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    /** The handle position: -1 (left end) to 1 (right end). 0 is the centre. */
    private var position = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var lastFrameNs = 0L
    private var spring: ValueAnimator? = null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!dragging) return
            val dt = if (lastFrameNs == 0L) 0.0 else (now - lastFrameNs) / 1e9
            lastFrameNs = now
            val rate = rate(position)
            // A long frame (for example, a pause of the app) must not give a large jump
            if (rate != 0.0 && dt > 0) onJog?.invoke(rate * dt.coerceAtMost(0.1))
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        isFocusable = true
        isClickable = true
        minimumHeight = handleHeight.roundToInt()
        setPadding((8 * density).roundToInt(), 0, (8 * density).roundToInt(), 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(handleHeight.roundToInt() + paddingTop + paddingBottom, heightMeasureSpec))
    }

    private val left get() = paddingLeft.toFloat()
    private val right get() = (width - paddingRight).toFloat()
    private val centre get() = (left + right) / 2
    private val half get() = (right - left) / 2

    override fun onDraw(canvas: Canvas) {
        if (right <= left) return
        val cy = height / 2f
        val top = cy - trackHeight / 2
        val bottom = cy + trackHeight / 2
        val hx = centre + position * half
        val hw = if (dragging) pressedHandleWidth else handleWidth
        val handleLeft = hx - hw / 2 - gap
        val handleRight = hx + hw / 2 + gap

        // The inactive track on each side of the handle
        if (handleLeft > left) roundRect(canvas, left, top, handleLeft, bottom, outer, inner, inactivePaint)
        if (handleRight < right) roundRect(canvas, handleRight, top, right, bottom, inner, outer, inactivePaint)
        // The active part, from the centre to the handle
        if (handleLeft > centre + gap) roundRect(canvas, centre, top, handleLeft, bottom, inner, inner, activePaint)
        if (handleRight < centre - gap) roundRect(canvas, handleRight, top, centre, bottom, inner, inner, activePaint)

        // Chevrons at the ends: one near the centre, two at the end (slow and fast)
        val size = 3.5f * density
        for (dir in intArrayOf(-1, 1)) {
            chevron(canvas, centre + dir * half * 0.45f, cy, size, dir)
            chevron(canvas, centre + dir * (half - outer - 6 * density), cy, size, dir)
            chevron(canvas, centre + dir * (half - outer - 10 * density), cy, size, dir)
        }

        rect.set(hx - hw / 2, cy - handleHeight / 2, hx + hw / 2, cy + handleHeight / 2)
        canvas.drawRoundRect(rect, hw / 2, hw / 2, activePaint)
    }

    private fun chevron(canvas: Canvas, x: Float, cy: Float, size: Float, dir: Int) {
        // Not on the handle or the active part
        val hx = centre + position * half
        if (abs(x - hx) < gap + size * 2) return
        if (position != 0f && (x - centre) * position.sign > 0 && abs(x - centre) < abs(hx - centre)) return
        path.reset()
        path.moveTo(x - dir * size / 2, cy - size)
        path.lineTo(x + dir * size / 2, cy)
        path.lineTo(x - dir * size / 2, cy + size)
        canvas.drawPath(path, markPaint)
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = abs(event.x - downX)
                    if (dx > slop && dx > abs(event.y - downY)) startDrag(event.x)
                }
                if (dragging && half > 0) {
                    position = ((event.x - downX) / half).coerceIn(-1f, 1f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) stopDrag()
        }
        return true
    }

    private fun startDrag(x: Float) {
        spring?.cancel()
        dragging = true
        // The drag starts where the finger is now: the first move has no jump
        downX = x - position * half
        lastFrameNs = 0L
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        onStart?.invoke()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stopDrag() {
        dragging = false
        Choreographer.getInstance().removeFrameCallback(frame)
        parent?.requestDisallowInterceptTouchEvent(false)
        performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        onStop?.invoke()
        // The handle goes back to the centre like a spring
        spring = ValueAnimator.ofFloat(position, 0f).apply {
            duration = 260
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener {
                position = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dragging = false
        spring?.cancel()
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        onStep?.invoke(dir)
        return true
    }

    // ---- Accessibility ----

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> onStep?.invoke(1)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> onStep?.invoke(-1)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }

    companion object {
        /** Near the centre, the value does not change. */
        private const val DEAD_ZONE = 0.06f
        /** The speed (s per s) just outside the dead zone, and at the ends. */
        private const val MIN_RATE = 0.1
        private const val MAX_RATE = 30.0

        /**
         * The speed of the change (s per s) at a handle position. The speed
         * increases exponentially: each part of the slider is a step of the same ratio.
         */
        fun rate(position: Float): Double {
            val a = abs(position)
            if (a < DEAD_ZONE) return 0.0
            val f = ((a - DEAD_ZONE) / (1 - DEAD_ZONE)).toDouble()
            return position.sign * MIN_RATE * (MAX_RATE / MIN_RATE).pow(f)
        }
    }
}
