package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** The point of each jump that is time 0 in a comparison. */
enum class SyncMode(val label: String, val description: String) {
    EXIT("Exit", "Time 0 is the exit of each jump."),
    GPS("GPS time", "All jumps use the same clock. Time 0 is the first exit. Use it for jumps of the same group."),
    START("Track start", "Time 0 is the first point of each track."),
}

/**
 * The synchronization of the compared jumps: the time 0 of each jump, and an
 * offset for each jump (s). A positive offset moves a jump later on the time
 * axis.
 */
data class TrackSync(val mode: SyncMode, val offsets: List<Double>) {

    /** True if the jumps use the default: each jump from its exit, with no offsets. */
    val isDefault get() = mode == SyncMode.EXIT && !hasOffsets

    val hasOffsets get() = offsets.any { it != 0.0 }

    /** The time 0 of the jump [i] of [list] (UTC, ms since 1970). */
    fun zeroMs(list: List<Track>, i: Int): Long {
        val track = list[i]
        val zero = when (mode) {
            SyncMode.EXIT -> track.exitMs
            SyncMode.GPS -> list.minOf { it.exitMs }
            SyncMode.START -> track.epochMs.first()
        }
        return zero - (offsets.getOrElse(i) { 0.0 } * 1000).roundToLong()
    }

    /** The tracks with their time 0 for this synchronization. */
    fun align(list: List<Track>): List<Track> = list.indices.map { list[it].withZero(zeroMs(list, it)) }

    /** The text for preferences: "MODE;offset,offset,...". */
    fun encode() = mode.name + ";" + offsets.joinToString(",") { String.format(Locale.US, "%.2f", it) }

    companion object {
        /** The maximum offset (s). */
        const val MAX_OFFSET = 600.0

        fun default(count: Int) = TrackSync(SyncMode.EXIT, List(count) { 0.0 })

        /** Read [encode] text. Text for a different number of jumps, or an old mode, gives the default. */
        fun decode(text: String?, count: Int): TrackSync {
            val parts = text?.split(';') ?: return default(count)
            val mode = SyncMode.entries.firstOrNull { it.name == parts[0] } ?: return default(count)
            val offsets = parts.getOrNull(1)?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: emptyList()
            return TrackSync(mode, if (offsets.size == count) offsets else List(count) { 0.0 })
        }

        /** An offset as text: "+1.2 s", "−0.45 s" or "0.0 s". */
        fun formatOffset(s: Double): String {
            if (abs(s) < 0.005) return "0.0 s"
            val hundredths = (abs(s) * 100).roundToLong()
            val number = String.format(Locale.US, if (hundredths % 10 == 0L) "%.1f" else "%.2f", hundredths / 100.0)
            return (if (s > 0) "+" else "−") + number + " s"
        }
    }
}

/**
 * The page that synchronizes the compared jumps. A chart at the top shows the
 * result at once. Below the chart, the user selects time 0 and moves each jump.
 *
 * When the user moves a jump, the chart shows that jump clearly and the other
 * jumps pale and thin. The chart, the map and the 3D view of the track viewer
 * change when the page closes.
 */
@SuppressLint("SetTextI18n", "ViewConstructor") // English only
class SyncPage(
    private val ui: Ui,
    /** The tracks as loaded (each from its exit). */
    private val tracks: List<Track>,
    private val labels: List<String>,
    private val colors: List<Int>,
    private var sync: TrackSync,
    series: Set<TrackChartView.Series>,
    private val onClose: (TrackSync) -> Unit,
) : LinearLayout(ui.context) {

    private val bar = ui.TopBar("Synchronize jumps")
    private val chart = TrackChartView(context, ui)
    private val resetButton: View
    private val modeGroup: Ui.Segmented
    private val modeDescription = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant)
    private val modeNote = ui.text(Ui.Type.BODY_SMALL, "")
    private val rows = mutableListOf<View>()
    private val values = mutableListOf<TextView>()
    /** The tracks with the times of [sync]. */
    private var aligned = sync.align(tracks)
    private var lastTickMs = 0L

    private val clearFocus = Runnable { focus(null) }

    /** The synchronization on this page now. */
    val value get() = sync

    init {
        orientation = VERTICAL
        setBackgroundColor(ui.surface)
        isClickable = true // The track viewer below must not get the touches

        bar.setNavigation(R.drawable.ic_back, "Back") { onClose(sync) }
        resetButton = ui.button(Ui.ButtonStyle.TEXT, "Reset") { reset() }
        bar.addAction(resetButton)

        chart.setTracks(aligned, labels)
        for (s in TrackChartView.Series.entries) chart.setVisible(s, s in series)
        showExits()

        modeGroup = ui.Segmented(SyncMode.entries.map { it.label }, sync.mode.ordinal) { setMode(SyncMode.entries[it]) }
        val controls = ui.column().apply {
            addView(ui.header("Time 0"))
            addView(modeGroup, LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), 0, ui.dp(16), 0) })
            addView(modeDescription.apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
            addView(modeNote.apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0) })
            addView(ui.header("Offsets"))
            for (i in tracks.indices) addView(offsetRow(i), LayoutParams(MATCH, WRAP).apply {
                setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(4))
            })
            addView(ui.note("Drag a slider to move the jump. Drag it farther to move the jump faster. " +
                "Tap − or + to move it by 0.1 s, or tap the value to type it. A positive offset moves the jump later."))
            addView(ui.space(16))
        }
        val scroll = ui.scroll(controls)
        val chips = seriesChips(series)

        addView(bar, LayoutParams(MATCH, WRAP))
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val left = ui.column().apply {
                addView(chart, LayoutParams(MATCH, 0, 1f))
                addView(chips, LayoutParams(MATCH, WRAP))
            }
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(left, LayoutParams(0, MATCH, 1f))
                addView(scroll, LayoutParams(ui.dp(380), MATCH))
            }, LayoutParams(MATCH, 0, 1f))
        } else {
            addView(chart, LayoutParams(MATCH, 0, 4f))
            addView(chips, LayoutParams(MATCH, WRAP))
            addView(ui.divider())
            addView(scroll, LayoutParams(MATCH, 0, 5f))
        }

        setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // The top bar uses the top inset
            setPadding(sys.left, 0, sys.right, sys.bottom)
            insets
        }
        update()
    }

    /** The chips of the chart lines. They change only the chart of this page. */
    private fun seriesChips(series: Set<TrackChartView.Series>): View {
        val chips = TrackChartView.Series.entries.map { s ->
            val sample = TrackActivity.LineSample(ui, ui.onSurfaceVariant, TrackChartView.dashFor(s, ui.density))
            ui.Chip(s.label, sample, showCheck = false) { on -> chart.setVisible(s, on) }.apply { checked = s in series }
        }
        return ui.chipRow(*chips.toTypedArray())
    }

    /** Show the time from before the first exit to after the last exit. */
    private fun showExits() {
        val exits = aligned.map { it.exitTime }
        chart.showRange(exits.min() - RANGE_BEFORE_S, exits.max() + RANGE_AFTER_S)
    }

    // ---- Time 0 ----

    private fun setMode(mode: SyncMode) {
        if (sync.mode == mode) return
        sync = sync.copy(mode = mode)
        realign()
    }

    private fun reset() {
        sync = TrackSync.default(tracks.size)
        modeGroup.select(sync.mode.ordinal)
        realign()
    }

    private fun realign() {
        aligned = sync.align(tracks)
        chart.updateTimes(aligned)
        showExits()
        update()
    }

    /** An additional line for a mode: a problem of these jumps, and if it is a warning. */
    private fun note(mode: SyncMode): Pair<String, Boolean>? = when (mode) {
        SyncMode.EXIT -> {
            val noExit = tracks.count { !it.hasExit }
            if (noExit > 0) "${if (noExit == 1) "1 jump has" else "$noExit jumps have"} no exit. It uses its first point." to false else null
        }
        SyncMode.GPS -> {
            val spread = (tracks.maxOf { it.exitMs } - tracks.minOf { it.exitMs }) / 1000.0
            if (spread > FAR_APART_S) "These jumps are ${duration(spread)} apart. They do not show at the same time." to true else null
        }
        SyncMode.START -> null
    }

    private fun duration(s: Double): String {
        val minutes = (s / 60).roundToLong()
        return when {
            minutes >= 48 * 60 -> "${minutes / (24 * 60)} days"
            minutes >= 60 -> if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
            else -> "$minutes min"
        }
    }

    // ---- Offsets ----

    /**
     * One jump: the colour, the name and the offset on the first line. The
     * − button, the speed slider and the + button on the second line.
     */
    private fun offsetRow(i: Int): View {
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(this@SyncPage.colors[i])
            }
        }
        val label = ui.text(Ui.Type.BODY_LARGE, labels[i]).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val value = ui.text(Ui.Type.LABEL_LARGE, "").apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            minWidth = ui.dp(84)
            minHeight = ui.dp(40)
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            background = ui.ripple(ui.onSurface, ui.shape(ui.surfaceContainerHighest, 8f), 8f)
            setOnClickListener { typeOffset(i) }
        }
        values += value
        val slider = JogSlider(ui).apply {
            contentDescription = "Move ${labels[i]}"
            onStart = { focus(i) }
            onStop = {
                setOffset(i, round2(sync.offsets[i]))
                releaseFocus()
            }
            onJog = { ds -> jog(this, i, ds) }
            onStep = { dir ->
                step(i, dir * STEP_S)
                focus(i)
                releaseFocus()
            }
        }
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(4), ui.dp(8), 0)
            addView(dot, LayoutParams(ui.dp(12), ui.dp(12)).apply { marginEnd = ui.dp(12) })
            addView(label, LayoutParams(0, WRAP, 1f))
            addView(value, LayoutParams(WRAP, WRAP))
        }
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(4))
            addView(stepButton(i, R.drawable.ic_remove, "Earlier by 0.1 s: ${labels[i]}", -1))
            addView(slider, LayoutParams(0, WRAP, 1f))
            addView(stepButton(i, R.drawable.ic_add, "Later by 0.1 s: ${labels[i]}", 1))
        }
        return ui.column().apply {
            addView(top)
            addView(bottom)
            rows += this
        }
    }

    /** Move the jump during a drag of the slider. A tick each second gives a feel of the speed. */
    private fun jog(slider: View, i: Int, ds: Double) {
        val old = sync.offsets[i]
        setOffset(i, old + ds)
        val now = SystemClock.uptimeMillis()
        if (floor(old) != floor(sync.offsets[i]) && now - lastTickMs > TICK_MIN_MS) {
            lastTickMs = now
            slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /**
     * A button that moves the jump by 0.1 s. When the user holds it, the jump
     * moves repeatedly, and after 1 s by 0.5 s at a time.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun stepButton(i: Int, iconRes: Int, description: String, direction: Int): ImageView {
        val button = ui.iconButton(iconRes, description, ui.onSurfaceVariant) { step(i, direction * STEP_S) }
        var repeats = 0
        val repeat = object : Runnable {
            override fun run() {
                repeats++
                step(i, direction * if (repeats > 10) FAST_STEP_S else STEP_S)
                button.postDelayed(this, REPEAT_MS)
            }
        }
        button.setOnLongClickListener {
            repeats = 0
            button.post(repeat)
            true
        }
        button.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> focus(i)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.removeCallbacks(repeat)
                    releaseFocus()
                }
            }
            false
        }
        return button
    }

    private fun step(i: Int, ds: Double) = setOffset(i, ((sync.offsets[i] + ds) * 10).roundToLong() / 10.0)

    private fun typeOffset(i: Int) {
        val current = sync.offsets[i]
        focus(i)
        ui.inputDialog(
            "Offset: ${labels[i]}",
            if (current == 0.0) "" else String.format(Locale.US, "%.2f", current).trimEnd('0').trimEnd('.'),
            message = "Seconds. A positive offset moves the jump later.",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED,
            suffix = "s",
            hint = { text ->
                val v = parse(text)
                when {
                    text.isBlank() -> "Empty is 0 s."
                    v == null -> "Type a number, for example 1.5 or -0.4."
                    abs(v) > TrackSync.MAX_OFFSET -> "The maximum is ±${TrackSync.MAX_OFFSET.toInt()} s."
                    else -> null
                }
            },
        ) { text ->
            val v = if (text.isBlank()) 0.0 else parse(text) ?: return@inputDialog
            setOffset(i, round2(v))
        }.apply {
            // The chart shows the new position for a short time after the dialog closes
            setOnDismissListener { releaseFocus(TYPED_FOCUS_MS) }
            show()
        }
    }

    /** A number, also with a comma as the decimal mark and with a "−" sign. */
    private fun parse(text: String) = text.trim().replace(',', '.').replace('−', '-').toDoubleOrNull()

    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0

    private fun setOffset(i: Int, v: Double) {
        val clamped = v.coerceIn(-TrackSync.MAX_OFFSET, TrackSync.MAX_OFFSET)
        if (clamped == sync.offsets[i]) return
        sync = sync.copy(offsets = sync.offsets.toMutableList().also { it[i] = clamped })
        // Only this track changes: a drag makes a new track in each frame
        aligned = aligned.toMutableList().also { it[i] = tracks[i].withZero(sync.zeroMs(tracks, i)) }
        chart.updateTimes(aligned)
        update()
    }

    // ---- Focus ----

    /** Show the jump [i] clearly on the chart and in the list. Null shows all jumps equally. */
    private fun focus(i: Int?) {
        removeCallbacks(clearFocus)
        chart.setFocus(i)
        for ((n, row) in rows.withIndex()) row.background = if (n == i) ui.shape(ui.surfaceContainerHigh, 16f) else null
    }

    private fun releaseFocus(delayMs: Long = FOCUS_HOLD_MS) {
        removeCallbacks(clearFocus)
        postDelayed(clearFocus, delayMs)
    }

    private fun update() {
        modeDescription.text = sync.mode.description
        val note = note(sync.mode)
        modeNote.visibility = if (note == null) GONE else VISIBLE
        if (note != null) {
            modeNote.text = note.first
            modeNote.setTextColor(if (note.second) ui.error else ui.onSurfaceVariant)
        }
        for ((i, text) in values.withIndex()) {
            val v = sync.offsets[i]
            text.text = TrackSync.formatOffset(v)
            text.setTextColor(if (round2(v) == 0.0) ui.onSurfaceVariant else ui.onSurface)
            text.contentDescription = "Offset of ${labels[i]}: ${TrackSync.formatOffset(v)}. Tap to type a value."
        }
        resetButton.isEnabled = !sync.isDefault
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(clearFocus)
    }

    companion object {
        private const val STEP_S = 0.1
        private const val FAST_STEP_S = 0.5
        private const val REPEAT_MS = 90L
        /** The chart shows the changed jump for this time after the touch stops (ms). */
        private const val FOCUS_HOLD_MS = 900L
        private const val TYPED_FOCUS_MS = 1500L
        private const val TICK_MIN_MS = 60L
        /** The chart shows this time before the first exit and after the last exit (s). */
        private const val RANGE_BEFORE_S = 10.0
        private const val RANGE_AFTER_S = 50.0
        /** With GPS time, jumps more than this apart (s) do not overlap. */
        private const val FAR_APART_S = 15 * 60.0
    }
}
