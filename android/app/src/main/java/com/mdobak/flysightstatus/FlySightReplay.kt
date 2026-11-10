package com.mdobak.flysightstatus

import android.os.SystemClock
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
 * its audio with its current configuration. The player bar of the track
 * viewer shows the replay position. The FlySight cannot go to a different
 * position during the replay: the user can only stop it.
 *
 * The connection is in [MainActivity]. This class gets its values as a
 * [MainActivity.DeviceListener].
 *
 * Commands: DS_CMD_SET_REPLAY, then DS_CMD_REQUEST_REPLAY. This class reads
 * DS_CMD_GET_REPLAY each second. Stop sends DS_CMD_REQUEST_SLEEP.
 *
 * The status does not identify the session. Thus the app keeps the folder of
 * the last replay that it started. A track viewer of a different session does
 * not show the replay.
 *
 * Between two status readings, a local clock moves the cursor at each frame.
 * The clock goes a little faster or slower until it agrees with the FlySight,
 * thus the cursor does not jump at each reading.
 */
class FlySightReplay(
    private val main: MainActivity,
    private val file: File,
) : MainActivity.DeviceListener {

    /** Called with the replay position (s from the exit) while the FlySight plays. */
    var onPosition: ((Double) -> Unit)? = null

    /** Called when the replay starts or stops. */
    var onRunningChanged: ((Boolean) -> Unit)? = null

    /** Called with a short status, or with null when the FlySight plays. */
    var onStatus: ((String?) -> Unit)? = null

    /** Called with a problem to show to the user. */
    var onError: ((String) -> Unit)? = null

    private val folder = TrackStore.remoteFolder(file)
    private var track: Track? = null
    /** UTC time of the first GNSS row of the file. The firmware counts from this row. */
    private var firstRowMs: Long? = null

    private val prefs = main.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    private var status: ReplayStatus? = null
    /** [SystemClock.uptimeMillis] when [status] came. */
    private var statusAt = 0L
    /** True from the start until the firmware is in replay mode. */
    private var starting = false
    /** [SystemClock.uptimeMillis] until which the cursor stays where the user put it. */
    private var holdCursorUntil = 0L
    /** True while this class gets values from the FlySight. */
    private var listening = false
    private var polling = false
    private var wasRunning = false
    private var lastError = 0

    /** True if the FlySight is in replay mode, with the session of this track. */
    val active get() = main.mode == DeviceMode.REPLAY && prefs.getString(PREF_FOLDER, null) == folder

    /** True if the FlySight replays a different session. */
    private val otherActive get() = main.mode == DeviceMode.REPLAY && !active

    /** True from the start until the FlySight stops the replay. */
    val running get() = starting || active

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            if (main.connected) main.sendDs(P.DS_CMD_GET_REPLAY)
            main.handler.postDelayed(this, POLL_MS)
        }
    }

    /** The position of the local clock (ms after the first row), or null if it does not run. */
    private var clockMs: Double? = null
    private var lastFrameNs = 0L
    private var shownStatus: String? = null

    /** Moves the cursor at each frame while the FlySight plays. */
    private val frame = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!listening || status?.state != ReplayStatus.PLAYING || !active) {
                clockMs = null
                return
            }
            advanceClock(frameTimeNanos)
            update()
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        Thread {
            val first = runCatching { firstRow(file) }.getOrNull()
            main.runOnUiThread {
                firstRowMs = first
                update()
            }
        }.start()
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

    /** Get values from the FlySight. Call this when the track viewer starts. */
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
        stopClock()
    }

    /**
     * A dialog with the replay settings and a Start button. The dialog
     * keeps the settings for the next replay.
     */
    fun showStartDialog(ui: Ui) {
        if (!main.connected) {
            onError?.invoke("Connect to the FlySight first.")
            return
        }
        val t = track ?: run {
            onError?.invoke("Wait until the app reads the track.")
            return
        }
        var start = prefs.getInt(PREF_START, START_TRACK).coerceIn(START_TRACK, START_BEFORE_EXIT)
        var ground = prefs.getInt(PREF_GROUND, GROUND_LANDING).coerceIn(GROUND_LANDING, GROUND_DEVICE)
        val groundNote = ui.note("")
        fun updateNote() {
            groundNote.text = when (ground) {
                GROUND_LANDING -> "The ground level is the height at the end of the track."
                GROUND_START -> "The ground level is the height at the start of the track."
                else -> "The ground level is the one that is set on the FlySight."
            }
        }
        updateNote()
        val content = ui.column().apply { setPadding(ui.dp(24), 0, ui.dp(24), 0) }
        if (t.hasExit) {
            content.addView(ui.text(Ui.Type.LABEL_LARGE, "Play from", ui.onSurfaceVariant))
            content.addView(ui.Segmented(listOf("Track start", "1 min before exit"), start) { start = it },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(16) })
        }
        content.addView(ui.text(Ui.Type.LABEL_LARGE, "Ground", ui.onSurfaceVariant))
        content.addView(ui.Segmented(listOf("Landing", "Track start", "Device"), ground) {
            ground = it
            updateNote()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })
        content.addView(groundNote, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })
        if (otherActive) {
            content.addView(ui.note("The FlySight replays a different jump now. Start stops that replay."),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
        }
        ui.dialog(
            "Replay on FlySight",
            "The FlySight plays the jump through its audio, with its current configuration.",
            content,
            positive = Ui.DialogButton("Start") {
                prefs.edit().putInt(PREF_START, start).putInt(PREF_GROUND, ground).apply()
                start(start, ground)
            },
        ).show()
    }

    /** Stop the replay on the FlySight. */
    fun stop() {
        if (active) main.sendDs(P.DS_CMD_REQUEST_SLEEP)
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
                // A replay of a different session is not for this track viewer
                if (!active && !starting) return update()
                if (!r.success) {
                    if (r.status == CP_NOT_SUPPORTED) {
                        stopPolling()
                        fail("This firmware cannot replay jumps. Install the firmware with replay mode.")
                    }
                    return update()
                }
                status = ReplayStatus.parse(r.data)
                statusAt = SystemClock.uptimeMillis()
                if (status?.running != true && !active && !starting) stopPolling()
                if (status?.state == ReplayStatus.PLAYING && active) startClock() else stopClock()
                update()
            }
        }
    }

    private fun start(start: Int, ground: Int) {
        if (running) return
        if (!main.connected) return fail("Connect to the FlySight first.")
        val f = folder ?: return
        val offsetS = startOffsetS(start) ?: return
        status = null
        lastError = 0
        starting = true
        stopClock()
        // The FlySight does not tell which session it replays. Keep it here.
        prefs.edit().putString(PREF_FOLDER, f).apply()
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
    private fun startOffsetS(start: Int): Int? {
        if (start == START_TRACK) return 0
        val t = track
        val first = firstRowMs
        if (t == null || first == null) {
            fail("Wait until the app reads the track.")
            return null
        }
        if (!t.hasExit) return 0
        return ((t.zeroMs - first) / 1000 - BEFORE_EXIT_S).coerceIn(0, MAX_OFFSET_S.toLong()).toInt()
    }

    private fun fail(text: String) {
        starting = false
        onError?.invoke(text)
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

    /** The position that the last status gives for now (ms after the first row). */
    private fun statusPositionMs(s: ReplayStatus): Long =
        if (s.state == ReplayStatus.PLAYING) s.positionMs + (SystemClock.uptimeMillis() - statusAt).coerceIn(0, MAX_GUESS_MS)
        else s.positionMs

    /** Replay position in ms after the first row. While it plays, this is the local clock. */
    private fun positionMs(s: ReplayStatus): Double =
        clockMs?.takeIf { s.state == ReplayStatus.PLAYING } ?: statusPositionMs(s).toDouble()

    private fun startClock() {
        if (clockMs != null) return
        val s = status ?: return
        clockMs = statusPositionMs(s).toDouble()
        lastFrameNs = System.nanoTime()
        android.view.Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun stopClock() {
        clockMs = null
        android.view.Choreographer.getInstance().removeFrameCallback(frame)
    }

    /**
     * Move the clock by the frame time. The error to the status changes the
     * speed by up to 30%. A large error (a seek, or a pause of the app) sets
     * the clock to the status.
     */
    private fun advanceClock(frameTimeNanos: Long) {
        val s = status ?: return
        val clock = clockMs ?: return
        val dt = ((frameTimeNanos - lastFrameNs) / 1e6).coerceIn(0.0, 100.0)
        lastFrameNs = frameTimeNanos
        val error = statusPositionMs(s) - clock
        clockMs = if (kotlin.math.abs(error) > SNAP_MS) clock + error
        else clock + dt * (1 + (error / CATCH_UP_MS).coerceIn(-0.3, 0.3))
    }

    private fun update() {
        val running = running
        if (running != wasRunning) {
            wasRunning = running
            onRunningChanged?.invoke(running)
        }
        val s = status
        if (s != null && s.state == ReplayStatus.ERROR && !starting && s.error != lastError) {
            lastError = s.error
            onError?.invoke(errorText(s.error))
        }
        if (!running) return
        val text = when {
            starting || s == null || s.state == ReplayStatus.IDLE -> "Starting…"
            s.state == ReplayStatus.SEEKING -> "Going to the start…"
            s.state == ReplayStatus.DONE -> "End of the track"
            s.state == ReplayStatus.STOPPED -> "Stopped"
            s.state == ReplayStatus.ERROR -> "Error"
            else -> null
        }
        if (text != shownStatus) {
            shownStatus = text
            onStatus?.invoke(text)
        }
        val t = track
        val first = firstRowMs
        val showsPosition = s != null && !starting && s.state != ReplayStatus.IDLE && s.state != ReplayStatus.ERROR
        if (listening && showsPosition && s != null && t != null && first != null && SystemClock.uptimeMillis() >= holdCursorUntil) {
            onPosition?.invoke((first + positionMs(s) - t.zeroMs) / 1000.0)
        }
    }

    private fun errorText(error: Int) = when (error) {
        ReplayStatus.ERROR_NO_TRACK -> "The FlySight did not get the track."
        ReplayStatus.ERROR_SD -> "The microSD card is not available. Stop the file transfer and try again."
        ReplayStatus.ERROR_OPEN -> "The FlySight cannot open TRACK.CSV in /$folder. Make sure that the session is still on the FlySight."
        ReplayStatus.ERROR_NO_DATA -> "TRACK.CSV in /$folder has no GPS data."
        else -> "Replay error $error."
    }

    private companion object {
        const val START_TRACK = 0
        const val START_BEFORE_EXIT = 1
        const val BEFORE_EXIT_S = 60
        const val MAX_OFFSET_S = 0x3FFF
        const val ZERO_FLAG = 0x8000
        const val LANDING_FLAG = 0x4000
        const val GROUND_LANDING = 0
        const val GROUND_START = 1
        const val GROUND_DEVICE = 2
        const val PREFS = "replay"
        const val PREF_GROUND = "ground"
        const val PREF_START = "start"
        const val PREF_FOLDER = "folder"
        const val POLL_MS = 1000L
        /** The clock corrects an error of this size (ms) in about 1 s at the maximum speed change. */
        const val CATCH_UP_MS = 1000.0
        /** A larger error (ms) sets the clock to the status. */
        const val SNAP_MS = 2000.0
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
