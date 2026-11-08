package com.example.flysightstatus

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.io.File
import java.util.Locale

/** The device card and the recent jumps. */
@android.annotation.SuppressLint("SetTextI18n") // English only
class HomeScreen(main: MainActivity) : Screen(main) {

    private val bar = ui.TopBar("FlySight")
    private val name = ui.text(Ui.Type.TITLE_LARGE, "", ui.onPrimaryContainer)
    private val status = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onPrimaryContainer)
    private val batteryValue = ui.text(Ui.Type.DISPLAY_SMALL, "", ui.onPrimaryContainer).apply { gravity = Gravity.END }
    private val batteryLabel = ui.text(Ui.Type.LABEL_MEDIUM, "Battery", ui.onPrimaryContainer).apply { gravity = Gravity.END }
    private val note = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onPrimaryContainer)
    private val progress: ProgressBar = ui.linearProgress().apply {
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(ui.alpha(ui.onPrimaryContainer, 0.16f))
    }
    private val progressText = ui.text(Ui.Type.BODY_SMALL, "", ui.onPrimaryContainer)
    private val buttons = LinearLayout(main).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var buttonsKey = ""
    private val jumps = ui.column()
    /** Play starts active mode (recording). Stop sets the FlySight to idle mode. */
    private val recordButton = android.widget.ImageView(main).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(ui.primaryContainer)
        background = ui.ripple(ui.primaryContainer, ui.shape(ui.onPrimaryContainer, 28f), 28f)
        setOnClickListener { toggleRecording() }
    }
    /** The mode when the user pushed the record button, until the mode changes. */
    private var recordPendingFrom: DeviceMode? = null
    private val recordTimeout = Runnable {
        recordPendingFrom = null
        updateCard()
    }
    private val tools = ui.column()
    private var elevation: GroundElevation? = null

    override val view: View

    init {
        val battery = ui.column().apply {
            gravity = Gravity.END
            addView(batteryValue)
            addView(batteryLabel)
        }
        val top = LinearLayout(main).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(recordButton, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { marginEnd = ui.dp(16) })
            addView(ui.column().apply {
                addView(name)
                addView(status)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(battery)
        }
        val card = ui.card(ui.primaryContainer, 16f, 20).apply {
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(12))
            addView(top)
            addView(note, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)).apply { topMargin = ui.dp(16) })
            addView(progressText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(6) })
            addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })
        }
        val body = ui.column().apply {
            setPadding(0, ui.dp(8), 0, ui.dp(24))
            addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(8)) })
            addView(tools)
            addView(jumps)
        }
        view = ui.column().apply {
            addView(bar)
            addView(ui.scroll(body, bar), LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        bar.addAction(ui.iconButton(R.drawable.ic_help_outline, "Button and LED") { showModesHelp() })
        tools.addView(ui.ListItem("Ground level", "Set the zero for alarms and altitude calls",
            ui.avatar(R.drawable.ic_s_altitude), ui.icon(R.drawable.ic_chevron_right)).onClick { setGroundElevation() })
        updateCard()
    }

    /** How to start each FlySight mode with the button, and what the LED shows. */
    private fun showModesHelp() {
        val list = ui.column().apply { setPadding(ui.dp(24), 0, ui.dp(24), 0) }
        for (m in MODES) {
            val texts = ui.column().apply {
                addView(ui.text(Ui.Type.TITLE_SMALL, m.title))
                addView(ui.text(Ui.Type.BODY_MEDIUM, m.how).apply { setPadding(0, ui.dp(2), 0, 0) })
                addView(ui.text(Ui.Type.BODY_SMALL, m.led, ui.onSurfaceVariant).apply { setPadding(0, ui.dp(4), 0, 0) })
            }
            list.addView(LinearLayout(main).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, ui.dp(8), 0, ui.dp(8))
                addView(ledIcon(m.color, m.pulse), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply {
                    marginEnd = ui.dp(16)
                })
                addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            })
        }
        ui.dialog("Button and LED",
            "Start from off (no LED). Quickly means less than 1 second.",
            list, positive = Ui.DialogButton("OK"), negative = null, scrollContent = true).show()
    }

    /** A dot in the LED colour. A ring around it shows a pulse. No colour shows an LED that is off. */
    private fun ledIcon(color: Int?, pulse: Boolean): View {
        fun oval(fill: Int, stroke: Int? = null) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
            if (stroke != null) setStroke(ui.dp(2), stroke)
        }
        val dot = if (color == null) oval(0, ui.outline) else oval(color)
        val layers = if (pulse && color != null) {
            android.graphics.drawable.LayerDrawable(arrayOf(oval(ui.alpha(color, 0.3f)), dot)).apply {
                val inset = ui.dp(5)
                setLayerInset(1, inset, inset, inset, inset)
            }
        } else {
            android.graphics.drawable.LayerDrawable(arrayOf(dot)).apply {
                val inset = ui.dp(4)
                setLayerInset(0, inset, inset, inset, inset)
            }
        }
        return View(main).apply {
            background = layers
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    override fun onShow() {
        updateCard()
        updateJumps()
    }

    override fun onStateChanged() {
        updateCard()
        elevation?.onStateChanged()
    }

    override fun onHide() {
        elevation?.dismiss()
    }

    override fun onValue(characteristic: java.util.UUID, value: ByteArray) {
        elevation?.onValue(characteristic, value)
    }

    private fun setGroundElevation() {
        if (elevation != null) return
        val dialog = GroundElevation(main) { elevation = null }
        elevation = dialog
        dialog.show()
        if (!main.connected || main.connection == null) elevation = null
    }

    private fun toggleRecording() {
        if (!main.connected || recordPendingFrom != null || main.sync.running || main.files?.busy == true) return
        val mode = main.mode
        recordPendingFrom = mode
        main.sendDs(if (mode == DeviceMode.SLEEP) FlySightProtocol.DS_CMD_REQUEST_ACTIVE else FlySightProtocol.DS_CMD_REQUEST_SLEEP)
        main.handler.removeCallbacks(recordTimeout)
        main.handler.postDelayed(recordTimeout, RECORD_TIMEOUT_MS)
        updateCard()
    }

    private fun updateRecordButton(ready: Boolean) {
        val mode = main.mode
        if (recordPendingFrom != null && (!ready || mode != recordPendingFrom)) {
            recordPendingFrom = null
            main.handler.removeCallbacks(recordTimeout)
        }
        recordButton.visibility = if (ready && mode != DeviceMode.UNKNOWN && mode != DeviceMode.USB) View.VISIBLE else View.GONE
        val idle = mode == DeviceMode.SLEEP
        recordButton.setImageResource(if (idle) R.drawable.ic_play else R.drawable.ic_stop)
        val label = if (idle) "Start recording (active mode)" else "Stop and set to idle mode"
        recordButton.contentDescription = label
        recordButton.tooltipText = label
        // A mode change during a file transfer stops the transfer
        val blocked = recordPendingFrom != null || main.sync.running || main.files?.busy == true
        recordButton.isEnabled = !blocked
        recordButton.alpha = if (blocked) 0.38f else 1f
    }

    private fun updateCard() {
        val ready = main.connected
        val connecting = main.connection != null && !ready
        val sync = main.sync
        name.text = main.deviceName ?: main.rememberedName ?: "FlySight"
        status.text = when {
            ready && main.mode != DeviceMode.UNKNOWN -> "Connected · ${main.mode.label} mode"
            ready -> "Connected"
            connecting -> "Connecting…"
            else -> "Not connected"
        }
        val level = main.battery
        batteryValue.text = if (level != null) "$level%" else ""
        batteryValue.visibility = if (ready && level != null) View.VISIBLE else View.GONE
        batteryLabel.visibility = batteryValue.visibility
        tools.visibility = if (ready) View.VISIBLE else View.GONE
        updateRecordButton(ready)

        val busyMode = ready && main.mode != DeviceMode.SLEEP && main.mode != DeviceMode.UNKNOWN
        note.text = when {
            !ready && !connecting && main.rememberedName == null ->
                "Connect to your FlySight to get your jumps and change its settings."
            busyMode -> "Push stop to set the FlySight to idle mode. Then you can get jumps and change settings."
            else -> ""
        }
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE

        val p = sync.progress
        progress.visibility = if (sync.running || connecting) View.VISIBLE else View.GONE
        progressText.visibility = if (sync.running) View.VISIBLE else View.GONE
        if (sync.running && p != null && p.size > 0) {
            progress.isIndeterminate = false
            progress.progress = (((p.index + p.done.toDouble() / p.size) / p.count) * 1000).toInt()
            progressText.text = String.format(Locale.US, "Jump %d of %d · %s of %s",
                p.index + 1, p.count, FileInfo.formatSize(p.done), FileInfo.formatSize(p.size))
        } else {
            progress.isIndeterminate = true
            progressText.text = if (p != null) "Jump ${p.index + 1} of ${p.count}" else sync.status
        }

        val key = when {
            sync.running -> "sync"
            connecting -> "connecting"
            busyMode -> "busy"
            ready -> "ready"
            main.rememberedName != null -> "remembered"
            else -> "new"
        }
        if (key != buttonsKey) {
            buttonsKey = key
            buttons.removeAllViews()
            when (key) {
                "sync" -> addButton(Ui.ButtonStyle.OUTLINED, "Cancel") { main.sync.cancel() }
                "connecting" -> addButton(Ui.ButtonStyle.OUTLINED, "Cancel") { main.disconnect() }
                "busy" -> {
                    addButton(Ui.ButtonStyle.OUTLINED, "Config") { main.selectTab(MainActivity.TAB_CONFIG) }
                }
                "ready" -> {
                    addButton(Ui.ButtonStyle.FILLED, "Get new jumps", R.drawable.ic_download) { getNewJumps() }
                    addButton(Ui.ButtonStyle.OUTLINED, "Config") { main.selectTab(MainActivity.TAB_CONFIG) }
                }
                "remembered" -> {
                    addButton(Ui.ButtonStyle.FILLED, "Connect", R.drawable.ic_bluetooth) { main.connectOrScan() }
                    addButton(Ui.ButtonStyle.OUTLINED, "Other FlySight") { main.findDevice() }
                }
                else -> addButton(Ui.ButtonStyle.FILLED, "Connect", R.drawable.ic_bluetooth) { main.connectOrScan() }
            }
        }
    }

    private fun addButton(style: Ui.ButtonStyle, label: String, icon: Int? = null, onClick: () -> Unit) {
        val content = if (style == Ui.ButtonStyle.OUTLINED) ui.onPrimaryContainer else null
        val b = ui.button(style, label, icon, content = content, onClick = onClick)
        buttons.addView(b, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (buttons.childCount > 0) marginStart = ui.dp(8) })
    }

    private fun getNewJumps() {
        if (main.idleFiles() == null) return
        main.sync.getNew { result ->
            updateJumps()
            result.onSuccess { files ->
                when (files.size) {
                    0 -> main.snack("No new jumps on the FlySight.")
                    1 -> main.snack("Got 1 new jump.", "Show") { TrackActivity.open(main, files[0]) }
                    else -> main.snack("Got ${files.size} new jumps.", "Show") { main.selectTab(MainActivity.TAB_JUMPS) }
                }
            }.onFailure {
                if (it.message == "Cancelled") main.snack("Stopped.") else main.snack(main.fileError("Get jumps", it), long = true)
            }
        }
    }

    private fun updateJumps() {
        jumps.removeAllViews()
        val files = TrackStore.list(main)
        if (files.isEmpty()) {
            jumps.addView(ui.emptyState(R.drawable.ic_jumps, "No jumps yet",
                "Your jumps show here after you get them from the FlySight. You can also import a TRACK.CSV file on the Jumps screen."))
            return
        }
        jumps.addView(ui.header("Recent jumps", ui.button(Ui.ButtonStyle.TEXT, "All jumps") {
            main.selectTab(MainActivity.TAB_JUMPS)
        }))
        for (file in files.take(RECENT)) {
            jumps.addView(jumpItem(ui, file, TrackStore.title(file)).onClick { TrackActivity.open(main, file) })
        }
    }

    private class Mode(val title: String, val how: String, val led: String, val color: Int?, val pulse: Boolean = false)

    companion object {
        private const val RECENT = 5
        private const val RECORD_TIMEOUT_MS = 10_000L
        private const val GREEN = 0xFF34A853.toInt()
        private const val RED = 0xFFEA4335.toInt()
        private const val ORANGE = 0xFFFB8C00.toInt()

        private val MODES = listOf(
            Mode("Turn on and record",
                "Push and hold the button for 1 second.",
                "Green. The LED goes off for a short time at each GNSS fix. Red shows a problem. " +
                    "Orange shows the starter pistol (Active_Mode: 1 in STATE.TXT).",
                GREEN),
            Mode("Turn off",
                "Push and hold the button for 1 second. In config and pairing mode, push it one time.",
                "The LED goes off.",
                null),
            Mode("Config mode (select a profile)",
                "Push the button. Then quickly push it again and hold it for 1 second.",
                "Solid green. The FlySight plays the sound of each profile in turn. " +
                    "Push the button to select the profile that plays. " +
                    "After the last profile, it uses only CONFIG.TXT and turns off.",
                GREEN),
            Mode("Pairing mode",
                "Push the button two times quickly.",
                "Green pulses. Pair or connect the phone now. Push the button to stop.",
                GREEN, pulse = true),
            Mode("Sensor mode",
                "Open the Sensors tab in the app while the FlySight is idle.",
                "Orange pulses. Sensor mode stops when you leave the tab or the connection closes.",
                ORANGE, pulse = true),
            Mode("USB disk",
                "Connect a USB cable.",
                "Red while it charges. Green when the battery is full. The LED goes off for a short time during file transfer.",
                RED),
        )

        /** A list item for a saved jump. The summary comes when the metadata is known. */
        fun jumpItem(ui: Ui, file: File, headline: String): Ui.ListItem {
            val item = ui.ListItem(headline, "…", ui.avatar(R.drawable.ic_jumps))
            TrackStore.loadMeta(file) { meta ->
                item.setSupporting(meta?.summary() ?: "Cannot read this track")
            }
            return item
        }
    }
}
