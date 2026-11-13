package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** The point of each jump that is time 0 in a comparison. */
enum class SyncMode(val label: String, val short: String, val description: String) {
    EXIT("Exit", "Exit", "Time 0 is the exit of each jump."),
    OPENING("Canopy opening", "Opening", "Time 0 is the canopy opening of each jump. Use it to compare canopy flights."),
    GPS("GPS time", "GPS time", "All jumps use the same clock: time 0 is the first exit. Use it for jumps of the same group."),
    START("Track start", "Start", "Time 0 is the first point of each track."),
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

    /** The tracks with their time 0 for this synchronization. */
    fun align(list: List<Track>): List<Track> {
        val firstExit = list.minOf { it.exitMs }
        return list.mapIndexed { i, track ->
            val zero = when (mode) {
                SyncMode.EXIT -> track.exitMs
                // A jump with no opening uses its exit
                SyncMode.OPENING -> if (track.deployIndex >= 0) track.epochMs[track.deployIndex] else track.exitMs
                SyncMode.GPS -> firstExit
                SyncMode.START -> track.epochMs.first()
            }
            track.withZero(zero - (offsets.getOrElse(i) { 0.0 } * 1000).roundToLong())
        }
    }

    /** The text for preferences: "MODE;offset,offset,...". */
    fun encode() = mode.name + ";" + offsets.joinToString(",") { String.format(Locale.US, "%.2f", it) }

    companion object {
        /** The maximum offset (s). */
        const val MAX_OFFSET = 600.0

        fun default(count: Int) = TrackSync(SyncMode.EXIT, List(count) { 0.0 })

        /** Read [encode] text. Text for a different number of jumps gives the default. */
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
 * The dialog that synchronizes the compared jumps. The top part selects time 0,
 * the bottom part sets an offset for each jump. The chart, the map and the 3D
 * view change at once.
 */
@SuppressLint("SetTextI18n") // English only
class SyncDialog(
    private val ui: Ui,
    /** The tracks as loaded (each from its exit). */
    private val tracks: List<Track>,
    private val labels: List<String>,
    private val colors: List<Int>,
    private var sync: TrackSync,
    private val onChange: (TrackSync) -> Unit,
) {
    private val context = ui.context
    private val modeRadios = mutableListOf<Pair<SyncMode, ImageView>>()
    private val offsetTexts = mutableListOf<TextView>()
    private lateinit var resetButton: View
    private lateinit var dialog: Dialog

    fun show() {
        val content = ui.column()
        content.addView(heading("Time 0"))
        for (mode in SyncMode.entries) content.addView(modeRow(mode))

        val offsetsHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(16), ui.dp(12), 0)
            addView(heading("Offsets").apply { setPadding(ui.dp(24), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            resetButton = ui.button(Ui.ButtonStyle.TEXT, "Reset") { setOffsets(List(tracks.size) { 0.0 }) }
            addView(resetButton)
        }
        content.addView(offsetsHeader)
        for (i in tracks.indices) content.addView(offsetRow(i))
        content.addView(ui.note("A positive offset moves the jump later on the time axis. Hold − or + to change the offset quickly. " +
            "Tap the value to type it.").apply { setPadding(ui.dp(24), ui.dp(4), ui.dp(24), 0) })

        update()
        dialog = ui.dialog("Synchronize jumps", content = content, positive = Ui.DialogButton("Done"), negative = null,
            scrollContent = true)
        dialog.show()
    }

    private fun heading(text: String) = ui.text(Ui.Type.TITLE_SMALL, text, ui.primary).apply {
        setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(4))
    }

    // ---- Time 0 ----

    private fun modeRow(mode: SyncMode): View {
        val radio = ui.icon(R.drawable.ic_radio_off)
        modeRadios += mode to radio
        val texts = ui.column().apply {
            addView(ui.text(Ui.Type.BODY_LARGE, mode.label))
            addView(ui.text(Ui.Type.BODY_SMALL, mode.description, ui.onSurfaceVariant))
            note(mode)?.let { (text, warning) ->
                addView(ui.text(Ui.Type.BODY_SMALL, text, if (warning) ui.error else ui.onSurfaceVariant).apply {
                    setPadding(0, ui.dp(2), 0, 0)
                })
            }
        }
        val enabled = mode != SyncMode.OPENING || tracks.any { it.deployIndex >= 0 }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(24), ui.dp(6), ui.dp(24), ui.dp(6))
            background = ui.ripple(ui.onSurface, null)
            addView(radio, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(16) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
            setOnClickListener {
                if (sync.mode == mode) return@setOnClickListener
                sync = sync.copy(mode = mode)
                changed()
            }
        }
    }

    /** An additional line for a mode: a problem of these jumps, and if it is a warning. */
    private fun note(mode: SyncMode): Pair<String, Boolean>? {
        val noExit = tracks.count { !it.hasExit }
        return when (mode) {
            SyncMode.EXIT -> if (noExit > 0) "${jumps(noExit)} no exit. It uses its first point." to false else null
            SyncMode.OPENING -> {
                val noOpening = tracks.count { it.deployIndex < 0 }
                when (noOpening) {
                    0 -> null
                    tracks.size -> "No canopy opening found." to false
                    else -> "${jumps(noOpening)} no canopy opening. It uses its exit." to false
                }
            }
            SyncMode.GPS -> {
                val spread = (tracks.maxOf { it.exitMs } - tracks.minOf { it.exitMs }) / 1000.0
                if (spread > FAR_APART_S) "These jumps are ${duration(spread)} apart. They do not show at the same time." to true else null
            }
            SyncMode.START -> null
        }
    }

    private fun jumps(n: Int) = if (n == 1) "1 jump has" else "$n jumps have"

    private fun duration(s: Double): String {
        val minutes = (s / 60).roundToLong()
        return when {
            minutes >= 48 * 60 -> "${minutes / (24 * 60)} days"
            minutes >= 60 -> if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
            else -> "$minutes min"
        }
    }

    // ---- Offsets ----

    private fun offsetRow(i: Int): View {
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(this@SyncDialog.colors[i])
            }
        }
        val label = ui.text(Ui.Type.BODY_LARGE, labels[i]).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val value = ui.text(Ui.Type.LABEL_LARGE, "").apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            minWidth = ui.dp(76)
            minHeight = ui.dp(40)
            setPadding(ui.dp(8), 0, ui.dp(8), 0)
            background = ui.ripple(ui.onSurface, ui.shape(ui.surfaceContainerHighest, 8f), 8f)
            setOnClickListener { typeOffset(i) }
        }
        offsetTexts += value
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(24), 0, ui.dp(12), 0)
            addView(dot, LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)).apply { marginEnd = ui.dp(12) })
            addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(stepButton(i, R.drawable.ic_remove, "Earlier: ${labels[i]}", -1))
            addView(value, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(stepButton(i, R.drawable.ic_add, "Later: ${labels[i]}", 1))
        }
    }

    /**
     * A button that changes the offset by 0.1 s. When the user holds it, the
     * offset changes repeatedly, and after 1 s it changes by 0.5 s at a time.
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
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) button.removeCallbacks(repeat)
            false
        }
        return button
    }

    private fun step(i: Int, ds: Double) {
        val v = ((sync.offsets[i] + ds) * 10).roundToLong() / 10.0
        setOffset(i, v)
    }

    private fun typeOffset(i: Int) {
        val current = sync.offsets[i]
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
            setOffset(i, (v * 100).roundToLong() / 100.0)
        }.show()
    }

    /** A number, also with a comma as the decimal mark and with a "−" sign. */
    private fun parse(text: String) = text.trim().replace(',', '.').replace('−', '-').toDoubleOrNull()

    private fun setOffset(i: Int, v: Double) {
        val clamped = v.coerceIn(-TrackSync.MAX_OFFSET, TrackSync.MAX_OFFSET)
        if (clamped == sync.offsets[i]) return
        setOffsets(sync.offsets.toMutableList().also { it[i] = clamped })
    }

    private fun setOffsets(offsets: List<Double>) {
        if (offsets == sync.offsets) return
        sync = sync.copy(offsets = offsets)
        changed()
    }

    private fun changed() {
        update()
        onChange(sync)
    }

    private fun update() {
        for ((mode, radio) in modeRadios) {
            val on = mode == sync.mode
            radio.setImageResource(if (on) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
            radio.imageTintList = ColorStateList.valueOf(if (on) ui.primary else ui.onSurfaceVariant)
        }
        for ((i, text) in offsetTexts.withIndex()) {
            val v = sync.offsets[i]
            text.text = TrackSync.formatOffset(v)
            text.setTextColor(if (v == 0.0) ui.onSurfaceVariant else ui.onSurface)
            text.contentDescription = "Offset of ${labels[i]}: ${TrackSync.formatOffset(v)}. Tap to type a value."
        }
        resetButton.isEnabled = sync.hasOffsets
    }

    companion object {
        private const val STEP_S = 0.1
        private const val FAST_STEP_S = 0.5
        private const val REPEAT_MS = 90L
        /** With GPS time, jumps more than this apart (s) do not overlap. */
        private const val FAR_APART_S = 15 * 60.0
    }
}
