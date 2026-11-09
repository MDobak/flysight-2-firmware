package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.io.File
import java.time.Instant
import java.util.UUID
import com.mdobak.flysightstatus.FlySightProtocol as P

/**
 * Replay a saved jump on the FlySight. The FlySight reads TRACK.CSV (and
 * SENSOR.CSV) of the session from its microSD card, and plays the jump through
 * its audio with its current configuration. The panel is in the track viewer.
 * The chart cursor and the map dot show the replay position.
 *
 * The connection is in [MainActivity]. The panel gets its values as a
 * [MainActivity.DeviceListener].
 *
 * Commands: DS_CMD_SET_REPLAY, then DS_CMD_REQUEST_REPLAY. The panel reads
 * DS_CMD_GET_REPLAY each second. Stop sends DS_CMD_REQUEST_SLEEP.
 */
@SuppressLint("SetTextI18n") // English only
class ReplayPanel(
    private val ui: Ui,
    private val main: MainActivity,
    private val file: File,
    private val chart: TrackChartView,
    private val map: TrackMapView,
    private val snack: (String) -> Unit,
) : MainActivity.DeviceListener {

    private val folder = TrackStore.remoteFolder(file)
    private var track: Track? = null
    /** UTC time of the first GNSS row of the file. The firmware counts from this row. */
    private var firstRowMs: Long? = null

    private var start = START_TRACK
    private val prefs = main.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    private var ground = prefs.getInt(PREF_GROUND, GROUND_LANDING).coerceIn(GROUND_LANDING, GROUND_DEVICE)
    private var status: ReplayStatus? = null
    /** [SystemClock.uptimeMillis] when [status] came. */
    private var statusAt = 0L
    private var message: String? = null
    /** True from the Start button until the firmware is in replay mode. */
    private var starting = false
    /** [SystemClock.uptimeMillis] until which the cursor stays where the user put it. */
    private var holdCursorUntil = 0L
    /** True while the panel gets values from the FlySight. */
    private var listening = false
    private var polling = false

    private val startChoice = ui.Segmented(listOf("Track start", "1 min before exit"), START_TRACK, compact = true) {
        start = it
        update()
    }
    private val startRow = settingRow("Play from", startChoice)
    private val groundChoice = ui.Segmented(listOf("Landing", "Track start", "Device"), ground, compact = true) {
        ground = it
        prefs.edit().putInt(PREF_GROUND, it).apply()
        update()
    }
    private val setup = ui.column()
    private val stateText = ui.text(Ui.Type.TITLE_SMALL, "")
    private val detailText = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant).apply { fontFeatureSettings = "tnum" }
    private val messageText = ui.text(Ui.Type.BODY_SMALL, "", ui.error)
    private val progress = ui.linearProgress()
    private val button = ui.button(Ui.ButtonStyle.FILLED, "Start", R.drawable.ic_play) { onButton() }

    val view: View

    /** True if the FlySight is in replay mode. */
    val active get() = main.mode == DeviceMode.REPLAY

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            if (main.connected) main.sendDs(P.DS_CMD_GET_REPLAY)
            main.handler.postDelayed(this, POLL_MS)
        }
    }

    /** Moves the cursor between two status readings. */
    private val tick = object : Runnable {
        override fun run() {
            if (!listening || status?.state != ReplayStatus.PLAYING) return
            update()
            main.handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        setup.apply {
            addView(startRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ui.dp(8) })
            addView(settingRow("Ground", groundChoice), LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val texts = ui.column().apply {
            addView(stateText)
            addView(detailText)
        }
        val header = LinearLayout(ui.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(button, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) })
        }
        val card = ui.card(ui.surfaceContainerHigh, 16f, 0).apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(12), ui.dp(12))
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(messageText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(4) })
            addView(setup, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8); marginEnd = ui.dp(4) })
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)).apply { topMargin = ui.dp(12); marginEnd = ui.dp(4) })
        }
        view = FrameLayout(ui.context).apply {
            setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8))
            addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        Thread {
            val first = runCatching { firstRow(file) }.getOrNull()
            main.runOnUiThread {
                firstRowMs = first
                update()
            }
        }.start()
        update()
    }

    /** A label and a choice in one row. */
    private fun settingRow(label: String, choice: View) = LinearLayout(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.text(Ui.Type.LABEL_MEDIUM, label, ui.onSurfaceVariant), LinearLayout.LayoutParams(ui.dp(64), WRAP))
        addView(choice, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** The track viewer read the track. */
    fun setTrack(t: Track) {
        track = t
        update()
    }

    /** The user moved the chart cursor. Keep it there for a short time. */
    fun onUserCursor() {
        holdCursorUntil = SystemClock.uptimeMillis() + HOLD_CURSOR_MS
    }

    /** Get values from the FlySight. Call this when the panel becomes visible. */
    fun listen() {
        if (listening) return
        listening = true
        main.addListener(this)
        // Show a replay that is on now
        if (main.connected) main.sendDs(P.DS_CMD_GET_REPLAY)
        if (active) startPolling()
        update()
    }

    /** Stop the values from the FlySight. The replay continues on the FlySight. */
    fun stopListening() {
        if (!listening) return
        listening = false
        main.removeListener(this)
        stopPolling()
        main.handler.removeCallbacks(tick)
    }

    override fun onStateChanged() {
        if (!main.connected) {
            starting = false
            stopPolling()
        } else if (active) {
            starting = false
            startPolling()
        }
        update()
    }

    override fun onValue(characteristic: UUID, value: ByteArray) {
        if (characteristic != P.DS_CONTROL_POINT) return
        val r = CpResponse.parse(value) ?: return
        when (r.opcode) {
            P.DS_CMD_SET_REPLAY -> if (r.success) {
                main.sendDs(P.DS_CMD_REQUEST_REPLAY)
            } else {
                fail("The FlySight did not accept the track: ${r.statusText}.")
            }
            P.DS_CMD_REQUEST_REPLAY -> if (r.success) {
                startPolling()
            } else {
                fail("The FlySight did not start the replay: ${r.statusText}.")
            }
            P.DS_CMD_GET_REPLAY -> {
                if (!r.success) {
                    if (r.status == CP_NOT_SUPPORTED) {
                        stopPolling()
                        message = "This firmware cannot replay jumps. Install the firmware with replay mode."
                    }
                    return update()
                }
                status = ReplayStatus.parse(r.data)
                statusAt = SystemClock.uptimeMillis()
                if (status?.running != true && !active && !starting) stopPolling()
                if (status?.state == ReplayStatus.PLAYING) {
                    main.handler.removeCallbacks(tick)
                    main.handler.postDelayed(tick, TICK_MS)
                }
                update()
            }
        }
    }

    private fun onButton() {
        if (main.mode == DeviceMode.REPLAY) {
            main.sendDs(P.DS_CMD_REQUEST_SLEEP)
            return
        }
        if (!main.connected) return snack("Connect to the FlySight first.")
        val f = folder ?: return
        val offsetS = startOffsetS() ?: return
        message = null
        status = null
        starting = true
        update()
        main.whenIdle {
            val value = offsetS or when (ground) {
                GROUND_LANDING -> LANDING_FLAG
                GROUND_START -> ZERO_FLAG
                else -> 0
            }
            val params = intArrayOf(value and 0xff, value shr 8) + f.map { it.code }.toIntArray()
            main.sendControl(P.DEVICE_STATE_SERVICE, P.DS_CONTROL_POINT, "DS", P.DS_COMMAND_NAMES,
                P.DS_CMD_SET_REPLAY, *params)
        }
    }

    /** Start position in s after the first row, or null if it is not known yet. */
    private fun startOffsetS(): Int? {
        if (start == START_TRACK) return 0
        val t = track
        val first = firstRowMs
        if (t == null || first == null) {
            snack("Wait until the app reads the track.")
            return null
        }
        if (!t.hasExit) return 0
        return ((t.zeroMs - first) / 1000 - BEFORE_EXIT_S).coerceIn(0, MAX_OFFSET_S.toLong()).toInt()
    }

    private fun fail(text: String) {
        starting = false
        message = text
        update()
    }

    private fun startPolling() {
        polling = true
        main.handler.removeCallbacks(poll)
        main.handler.postDelayed(poll, POLL_MS)
    }

    private fun stopPolling() {
        polling = false
        main.handler.removeCallbacks(poll)
    }

    /** Replay position in ms after the first row. While it plays, add the time since the last status. */
    private fun positionMs(s: ReplayStatus): Long =
        if (s.state == ReplayStatus.PLAYING) s.positionMs + (SystemClock.uptimeMillis() - statusAt).coerceIn(0, MAX_GUESS_MS)
        else s.positionMs

    private fun update() {
        val t = track
        val s = status
        val active = active
        startRow.visibility = if (t == null || t.hasExit) View.VISIBLE else View.GONE
        setup.visibility = if (active || starting) View.GONE else View.VISIBLE

        stateText.text = when {
            starting -> "Starting…"
            s == null || s.state == ReplayStatus.IDLE -> if (active) "Replay mode" else "Replay on the FlySight"
            s.state == ReplayStatus.SEEKING -> "Going to the start position…"
            s.state == ReplayStatus.PLAYING -> "Playing"
            s.state == ReplayStatus.DONE -> if (active) "End of the track" else "Done"
            s.state == ReplayStatus.STOPPED -> "Stopped"
            else -> "Error"
        }
        val showsPosition = s != null && !starting && s.state != ReplayStatus.IDLE && s.state != ReplayStatus.ERROR
        detailText.text = when {
            s != null && s.state == ReplayStatus.ERROR && !starting -> errorText(s.error)
            showsPosition && s != null -> "${position(positionMs(s))} · ${heightText(s)}"
            !main.connected -> "Connect to the FlySight to start."
            ground == GROUND_LANDING -> "Ground level from the end of the track"
            ground == GROUND_START -> "Ground level from the start of the track"
            else -> "Ground level that is set on the FlySight"
        }

        val first = firstRowMs ?: t?.epochMs?.first()
        val duration = if (t != null && first != null) (t.epochMs.last() - first).coerceAtLeast(1) else null
        progress.visibility = if (starting || s?.running == true) View.VISIBLE else View.GONE
        progress.isIndeterminate = starting || s?.state == ReplayStatus.SEEKING || duration == null
        if (s != null && duration != null) progress.progress = (positionMs(s) * 1000 / duration).toInt().coerceIn(0, 1000)

        if (listening && showsPosition && s != null && t != null && first != null && SystemClock.uptimeMillis() >= holdCursorUntil) {
            val time = (first + positionMs(s) - t.zeroMs) / 1000.0
            chart.showCursor(time)
            map.setCursor(time)
        }

        messageText.text = message ?: ""
        messageText.visibility = if (message == null) View.GONE else View.VISIBLE

        button.text = if (active) "Stop" else "Start"
        button.setCompoundDrawablesRelative(ui.drawable(if (active) R.drawable.ic_stop else R.drawable.ic_play, ui.onPrimary),
            null, null, null)
        button.isEnabled = main.connected && !starting && folder != null
    }

    /** "0:23 before exit", or the time from the first row if the exit is not known. */
    private fun position(ms: Long): String {
        val t = track
        val first = firstRowMs
        if (t == null || first == null || !t.hasExit) return Track.formatDuration(ms / 1000.0)
        val fromExit = (first + ms - t.zeroMs) / 1000.0
        return if (fromExit < 0) "${Track.formatDuration(-fromExit)} before exit" else "${Track.formatDuration(fromExit)} after exit"
    }

    private fun heightText(s: ReplayStatus): String {
        val source = when (s.kind) {
            ReplayStatus.KIND_BARO -> "barometer"
            ReplayStatus.KIND_GNSS -> "GPS"
            else -> "GPS above Ground elevation"
        }
        return "${TrackStore.formatInt(s.heightM)} m ($source)"
    }

    private fun errorText(error: Int) = when (error) {
        ReplayStatus.ERROR_NO_TRACK -> "The FlySight did not get the track."
        ReplayStatus.ERROR_SD -> "The microSD card is not available. Stop the file transfer and try again."
        ReplayStatus.ERROR_OPEN -> "The FlySight cannot open TRACK.CSV in /$folder. Make sure that the session is still on the FlySight."
        ReplayStatus.ERROR_NO_DATA -> "TRACK.CSV in /$folder has no GPS data."
        else -> "Error $error."
    }

    private companion object {
        const val START_TRACK = 0
        const val BEFORE_EXIT_S = 60
        const val MAX_OFFSET_S = 0x3FFF
        const val ZERO_FLAG = 0x8000
        const val LANDING_FLAG = 0x4000
        const val GROUND_LANDING = 0
        const val GROUND_START = 1
        const val GROUND_DEVICE = 2
        const val PREFS = "replay"
        const val PREF_GROUND = "ground"
        const val POLL_MS = 1000L
        const val TICK_MS = 200L
        /** Do not move the cursor more than this far after the last status. */
        const val MAX_GUESS_MS = 1500L
        const val HOLD_CURSOR_MS = 3000L
        const val CP_NOT_SUPPORTED = 0x02

        /** Time of the first $GNSS row with a valid time, as the firmware reads it. */
        fun firstRow(file: File): Long? {
            file.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("\$GNSS,")) continue
                    val time = line.substring(6).substringBefore(',')
                    runCatching { return Instant.parse(time).toEpochMilli() }
                }
            }
            return null
        }
    }
}
