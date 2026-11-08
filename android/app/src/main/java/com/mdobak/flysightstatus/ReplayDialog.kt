package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.app.Dialog
import android.view.Gravity
import android.view.View
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
 * its audio with its current configuration.
 *
 * Commands: DS_CMD_SET_REPLAY, then DS_CMD_REQUEST_REPLAY. The dialog reads
 * DS_CMD_GET_REPLAY each second. Stop sends DS_CMD_REQUEST_SLEEP.
 */
@SuppressLint("SetTextI18n") // English only
class ReplayDialog(private val main: MainActivity, private val file: File, private val onClosed: () -> Unit) {

    private val ui = main.ui
    private val folder = TrackStore.remoteFolder(file)
    private var track: Track? = null
    /** UTC time of the first GNSS row of the file. The firmware counts from this row. */
    private var firstRowMs: Long? = null

    private var start = START_TRACK
    private var zeroAtStart = true
    private var status: ReplayStatus? = null
    private var message: String? = null
    /** True from the Start button until the firmware is in replay mode. */
    private var starting = false
    private var closed = false

    private val startChoice = ui.Segmented(listOf("From the start", "1 min before exit"), START_TRACK) { start = it; update() }
    private val zeroSwitch = ui.switch(true) { zeroAtStart = it }
    private val stateText = ui.text(Ui.Type.TITLE_MEDIUM, "")
    private val detailText = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant).apply { fontFeatureSettings = "tnum" }
    private val messageText = ui.text(Ui.Type.BODY_MEDIUM, "", ui.error)
    private val progress = ui.linearProgress()
    private val button = ui.button(Ui.ButtonStyle.FILLED, "Start replay", R.drawable.ic_play) { onButton() }
    private val dialog: Dialog

    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            if (main.connected) main.sendDs(P.DS_CMD_GET_REPLAY)
            main.handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        val zeroRow = ui.row(
            ui.text(Ui.Type.BODY_LARGE, "Ground level from the track start"),
            zeroSwitch,
        ).apply {
            getChildAt(0).layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val content = ui.column().apply {
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
            addView(startChoice, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(zeroRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
            addView(ui.text(Ui.Type.BODY_SMALL,
                "On: the FlySight sets the ground level from the first measurements, as if you set it before " +
                    "the jump. Off: it uses Ground elevation (DZ_Elev) or the automatic ground level.",
                ui.onSurfaceVariant), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(4) })
            addView(stateText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(20) })
            addView(detailText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(2) })
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)).apply { topMargin = ui.dp(12) })
            addView(messageText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })
            addView(button, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = ui.dp(12)
                gravity = Gravity.END
            })
        }
        dialog = ui.dialog("Replay on FlySight",
            "The FlySight plays this jump through its audio, with its current configuration. " +
                "It reads the track from its microSD card. There is no log and no ActiveLook display.",
            content, negative = Ui.DialogButton("Close"), scrollContent = true)
        dialog.setOnDismissListener { close() }
    }

    fun show() {
        if (folder == null) return main.snack("Only jumps from the FlySight can be replayed. This file was imported.")
        dialog.show()
        update()
        Thread {
            val result = runCatching { Track.load(file) to firstRow(file) }
            main.runOnUiThread {
                if (closed) return@runOnUiThread
                result.onSuccess { (t, first) ->
                    track = t
                    firstRowMs = first
                }.onFailure { message = "Cannot read the track: ${it.message}" }
                update()
            }
        }.start()
        // Show a replay that is on now
        if (main.connected) main.sendDs(P.DS_CMD_GET_REPLAY)
        if (main.mode == DeviceMode.REPLAY) startPolling()
    }

    fun dismiss() = dialog.dismiss()

    fun onStateChanged() {
        if (closed) return
        if (!main.connected) {
            starting = false
            stopPolling()
        } else if (main.mode == DeviceMode.REPLAY) {
            starting = false
            startPolling()
        }
        update()
    }

    fun onValue(characteristic: UUID, value: ByteArray) {
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
                if (status?.running != true && main.mode != DeviceMode.REPLAY && !starting) stopPolling()
                update()
            }
        }
    }

    private fun onButton() {
        if (main.mode == DeviceMode.REPLAY) {
            main.sendDs(P.DS_CMD_REQUEST_SLEEP)
            return
        }
        if (!main.connected) return main.snack("Connect to the FlySight first.")
        val f = folder ?: return
        val offsetS = startOffsetS() ?: return
        message = null
        status = null
        starting = true
        update()
        main.whenIdle {
            val value = offsetS or (if (zeroAtStart) ZERO_FLAG else 0)
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
            main.snack("Wait until the app reads the track.")
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
        main.handler.removeCallbacks(poll)
        main.handler.postDelayed(poll, POLL_MS)
    }

    private fun stopPolling() = main.handler.removeCallbacks(poll)

    private fun update() {
        if (closed) return
        val t = track
        val s = status
        val active = main.mode == DeviceMode.REPLAY
        startChoice.visibility = if (t == null || t.hasExit) View.VISIBLE else View.GONE

        stateText.text = when {
            starting -> "Starting…"
            s == null || s.state == ReplayStatus.IDLE -> if (active) "Replay mode" else "Ready"
            s.state == ReplayStatus.SEEKING -> "Going to the start position…"
            s.state == ReplayStatus.PLAYING -> "Playing"
            s.state == ReplayStatus.DONE -> if (active) "End of the track" else "Done"
            s.state == ReplayStatus.STOPPED -> "Stopped"
            else -> "Error"
        }
        detailText.text = when {
            s == null || s.state == ReplayStatus.IDLE || starting -> "Session $folder on the FlySight."
            s.state == ReplayStatus.ERROR -> errorText(s.error)
            else -> "${position(s)} · ${heightText(s)}"
        }

        val duration = t?.let { (it.epochMs.last() - (firstRowMs ?: it.epochMs.first())).coerceAtLeast(1) }
        progress.visibility = if (starting || s?.running == true) View.VISIBLE else View.INVISIBLE
        progress.isIndeterminate = starting || s?.state == ReplayStatus.SEEKING || duration == null
        if (s != null && duration != null) progress.progress = (s.positionMs * 1000 / duration).toInt().coerceIn(0, 1000)

        messageText.text = message ?: ""
        messageText.visibility = if (message == null) View.GONE else View.VISIBLE

        button.text = if (active) "Stop" else "Start replay"
        button.setCompoundDrawablesRelative(ui.drawable(if (active) R.drawable.ic_stop else R.drawable.ic_play, ui.onPrimary),
            null, null, null)
        button.isEnabled = main.connected && !starting
        startChoice.isEnabled = !active
        zeroSwitch.isEnabled = !active
    }

    /** "0:23 before exit", or the time from the first row if the exit is not known. */
    private fun position(s: ReplayStatus): String {
        val t = track
        val first = firstRowMs
        if (t == null || first == null || !t.hasExit) return Track.formatDuration(s.positionMs / 1000.0)
        val fromExit = (first + s.positionMs - t.zeroMs) / 1000.0
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

    private fun close() {
        if (closed) return
        closed = true
        stopPolling()
        onClosed()
    }

    private companion object {
        const val START_TRACK = 0
        const val BEFORE_EXIT_S = 60
        const val MAX_OFFSET_S = 0x7FFF
        const val ZERO_FLAG = 0x8000
        const val POLL_MS = 1000L
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
