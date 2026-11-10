package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A replay of the jump in the app: a play button, the time, a slider and the
 * speed. The replay moves the cursor of the chart, the map and the 3D view.
 * It does not send data to the FlySight. The bar shows only during a replay:
 * [open] shows it and starts the replay, [close] stops the replay and hides it.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PlaybackBar(context: Context, private val ui: Ui) : LinearLayout(context) {

    /** Called with the time (s) at each frame of the replay and when the user moves the slider. */
    var onTime: ((Double) -> Unit)? = null

    /** Called when the replay starts or stops. */
    var onPlaying: ((Boolean) -> Unit)? = null

    /** Called when the bar opens or closes. */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    val isOpen get() = visibility == VISIBLE

    /** The time of the cursor (s). */
    var time = 0.0
        private set

    var playing = false
        private set

    /** Stops the replay on the FlySight, or null if the bar shows the replay in the app. */
    private var remoteStop: (() -> Unit)? = null
    private var remoteStatus: String? = null
    private var start = 0.0
    private var end = 1.0
    private var speed = 1.0
    private var lastFrame = 0L

    private val play = ui.iconButton(R.drawable.ic_play, "Play", ui.onSurface) { toggle() }
    private val timeText = ui.text(Ui.Type.LABEL_LARGE, "").apply {
        fontFeatureSettings = "tnum"
        gravity = Gravity.END
        minWidth = ui.dp(56)
    }
    private val slider = SeekBar(context).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(ui.primary)
        thumbTintList = android.content.res.ColorStateList.valueOf(ui.primary)
        contentDescription = "Replay time"
    }
    private val speedButton = ui.text(Ui.Type.LABEL_LARGE, "1×", ui.onSurfaceVariant).apply {
        gravity = Gravity.CENTER
        minWidth = ui.dp(48)
        minHeight = ui.dp(48)
        background = ui.ripple(ui.onSurfaceVariant, null, 24f)
        contentDescription = "Replay speed"
        tooltipText = "Replay speed"
        setOnClickListener { showSpeeds(this) }
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!playing) return
            val now = System.nanoTime()
            val dt = (now - lastFrame) / 1e9
            lastFrame = now
            val t = time + dt * speed
            if (t >= end) {
                setTime(end, notify = true)
                stop()
                return
            }
            setTime(t, notify = true)
            postOnAnimation(this)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        setBackgroundColor(ui.surfaceContainer)
        setPadding(ui.dp(4), 0, ui.dp(8), 0)
        minimumHeight = ui.dp(56)
        addView(play)
        addView(timeText, LayoutParams(WRAP, WRAP))
        addView(slider, LayoutParams(0, WRAP, 1f))
        addView(speedButton, LayoutParams(WRAP, WRAP))
        // During the FlySight replay, the slider only shows the position
        slider.setOnTouchListener { _, _ -> remoteStop != null }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setTime(start + (end - start) * progress / seekBar.max, notify = true, moveSlider = false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    /**
     * Set the range of the replay: from 10 s before the first exit, to the last
     * point. With [keepTime], the replay continues at the same time (for a new
     * synchronization of the same tracks).
     */
    fun setTracks(list: List<Track>, keepTime: Boolean = false) {
        if (!keepTime) stop()
        val exits = list.filter { it.hasExit }
        val first = list.minOf { it.t.first() }
        start = if (exits.isEmpty()) first else max(first, exits.minOf { it.exitTime } - PRE_EXIT_S)
        end = list.maxOf { it.t.last() }
        if (end <= start) end = start + 1
        setTime(if (keepTime) time else exits.minOfOrNull { it.exitTime } ?: start, notify = false)
    }

    /** Show the bar and play from the start. */
    fun open() {
        if (!isOpen) {
            visibility = VISIBLE
            onOpenChanged?.invoke(true)
        }
        setTime(start, notify = true)
        play()
    }

    /** Stop the replay and hide the bar. */
    fun close() {
        stop()
        if (!isOpen) return
        visibility = GONE
        onOpenChanged?.invoke(false)
    }

    /**
     * Show the replay on the FlySight. The slider shows the position, but the
     * user cannot move it: the FlySight cannot go to a different position.
     * The button stops the replay with [onStop].
     */
    fun openRemote(onStop: () -> Unit) {
        stop()
        remoteStop = onStop
        speedButton.visibility = GONE
        play.setImageResource(R.drawable.ic_stop)
        play.contentDescription = "Stop the FlySight replay"
        play.tooltipText = play.contentDescription
        if (!isOpen) {
            visibility = VISIBLE
            onOpenChanged?.invoke(true)
        }
    }

    /** The FlySight replay stopped: hide the bar. */
    fun closeRemote() {
        if (remoteStop == null) return
        remoteStop = null
        speedButton.visibility = VISIBLE
        remoteStatus = null
        updatePlayButton()
        visibility = GONE
        onOpenChanged?.invoke(false)
    }

    /** The bar shows the replay on the FlySight. */
    val remote get() = remoteStop != null

    /** The position of the FlySight replay (s). */
    fun showRemoteTime(t: Double) {
        setTime(t, notify = false)
        remoteStatus?.let { timeText.text = it }
    }

    /** A status of the FlySight replay instead of the time, or null to show the time. */
    fun showRemoteStatus(text: String?) {
        remoteStatus = text
        if (text != null) timeText.text = text else setTime(time, notify = false)
    }

    /** Show [t] (s). Use this when the user moves the cursor on the chart. */
    fun showTime(t: Double) {
        if (playing && kotlin.math.abs(t - time) > 1e-6) stop()
        setTime(t, notify = false)
    }

    fun stop() {
        if (!playing) return
        playing = false
        removeCallbacks(frame)
        updatePlayButton()
        onPlaying?.invoke(false)
    }

    private fun toggle() {
        val remote = remoteStop
        when {
            remote != null -> remote()
            playing -> stop()
            else -> play()
        }
    }

    private fun play() {
        // At the end, or before the start: start again
        if (time >= end - 0.05 || time < start) setTime(start, notify = true)
        playing = true
        lastFrame = System.nanoTime()
        updatePlayButton()
        onPlaying?.invoke(true)
        postOnAnimation(frame)
    }

    private fun setTime(t: Double, notify: Boolean, moveSlider: Boolean = true) {
        time = t.coerceIn(start, end)
        timeText.text = String.format(Locale.US, "%.1f s", time)
        if (moveSlider) slider.progress = ((time - start) / (end - start) * slider.max).roundToInt()
        if (notify) onTime?.invoke(time)
    }

    private fun updatePlayButton() {
        play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = if (playing) "Pause" else "Play"
        play.tooltipText = play.contentDescription
    }

    private fun showSpeeds(anchor: View) {
        ui.popupMenu(anchor, SPEEDS.map { s ->
            "${label(s)} speed" to {
                speed = s
                speedButton.text = label(s)
            }
        }, SPEEDS.indexOf(speed))
    }

    private fun label(s: Double) = "${s.toInt()}×"

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun max(a: Double, b: Double) = kotlin.math.max(a, b)

    companion object {
        private const val PRE_EXIT_S = 10.0
        private val SPEEDS = listOf(1.0, 2.0, 5.0, 10.0, 20.0)
    }
}
