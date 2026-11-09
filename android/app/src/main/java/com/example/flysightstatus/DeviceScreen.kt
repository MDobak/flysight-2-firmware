package com.example.flysightstatus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.flysightstatus.MainActivity.Companion.toHex
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.hypot
import com.example.flysightstatus.FlySightProtocol as P

/** The device tab: the firmware, the debug functions, and the log. */
@SuppressLint("SetTextI18n") // English only
class DeviceScreen(main: MainActivity) : Screen(main) {

    private val bar = ui.TopBar("Device")
    private val connectButton = ui.button(Ui.ButtonStyle.TEXT, "Connect") {
        if (main.connection != null) main.disconnect() else main.connectOrScan()
    }
    private val tabs = ui.Tabs(listOf("Firmware", "Debug", "Log")) { selectTab(it) }
    private val pages = FrameLayout(main)
    private val notConnected = ui.column()
    private var currentTab = TAB_FIRMWARE
    private var wasConnected = false

    override val view: View

    // Firmware
    private val fwVersion = ui.text(Ui.Type.HEADLINE_SMALL, "–")
    private val fwModel = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant)
    private val fwDeviceId = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }
    private val fwFileItem = ui.ListItem(FW_PATH, "–", leading = ui.icon(R.drawable.ic_file))
    private val fwProgress = ui.linearProgress()
    private val fwProgressText = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant)
    private val fwProgressBox = ui.column().apply {
        setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8))
        addView(fwProgress, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(fwProgressText.apply { setPadding(0, ui.dp(4), 0, 0) })
        visibility = View.GONE
    }
    private val fwMessage = ui.text(Ui.Type.BODY_SMALL, "", ui.error).apply {
        setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8))
        visibility = View.GONE
    }
    private val uploadButton = ui.button(Ui.ButtonStyle.TONAL, "Upload a file", R.drawable.ic_upload) { pickFirmware() }
    private val installButton = ui.button(Ui.ButtonStyle.FILLED, "Install") { install() }

    /** The device ID from DS_CMD_GET_DEVICE_ID, or null. */
    private var deviceId: String? = null
    private var deviceIdAsked = false
    /** True if the app read /FW in this connection. Then [fwFile] is the file, or null if there is no file. */
    private var fwKnown = false
    private var fwFile: FileInfo? = null
    private var fwListing = false
    private var uploading = false

    // Debug: status
    private val statusItems = linkedMapOf<String, Ui.ListItem>()

    // Debug: live GNSS data
    private val gnssSwitch = ui.switch(false) { on ->
        main.connection?.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_GNSS_MEASUREMENT, on)
        main.log("GNSS notifications ${if (on) "on" else "off"}")
    }
    /** True if the user turned on the live GNSS data on this screen. */
    val gnssLive get() = gnssSwitch.isChecked
    private val mask = mutableMapOf<Int, Boolean>()
    private val maskItems = mutableMapOf<Int, Ui.ListItem>()
    private val maskResult = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant)
    private val gnssItems = linkedMapOf<String, Ui.ListItem>()
    private var gnssPackets = 0

    // Debug: commands
    private val dsResult = ui.ListItem("Last answer", "–")
    private val spResult = ui.ListItem("Starter pistol", "–")

    // Log
    private val logText = TextView(main).apply {
        ui.style(this, Ui.Type.BODY_SMALL)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16))
    }
    private val logScroll = ScrollView(main).apply { addView(logText) }

    init {
        bar.addAction(connectButton)
        pages.addView(buildFirmware())
        pages.addView(buildDebug())
        pages.addView(buildLog())
        view = ui.column().apply {
            addView(bar)
            addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(notConnected)
            addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        selectTab(TAB_FIRMWARE)
        updateStatus()
    }

    override fun onShow() {
        updateStatus()
        if (currentTab == TAB_LOG) scrollLogToEnd()
    }

    override fun onStateChanged() {
        val connected = main.connected
        if (connected != wasConnected) {
            wasConnected = connected
            if (connected) {
                if (gnssSwitch.isChecked) main.connection?.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_GNSS_MEASUREMENT, true)
            } else {
                clear()
            }
        }
        updateStatus()
    }

    private fun selectTab(index: Int) {
        currentTab = index
        tabs.select(index)
        for (i in 0 until pages.childCount) pages.getChildAt(i).visibility = if (i == index) View.VISIBLE else View.GONE
        if (index == TAB_LOG) scrollLogToEnd()
        updateStatus()
    }

    private fun clear() {
        gnssPackets = 0
        gnssItems.values.forEach { it.setSupporting("–") }
        dsResult.setSupporting("–")
        spResult.setSupporting("–")
        deviceIdAsked = false
        fwKnown = false
        fwFile = null
    }

    private fun updateStatus() {
        bar.title.text = main.deviceName ?: "Device"
        connectButton.text = if (main.connection != null) "Disconnect" else "Connect"
        notConnected.removeAllViews()
        if (main.connection == null && currentTab != TAB_LOG) {
            notConnected.addView(ui.text(Ui.Type.BODY_MEDIUM, "Not connected. The values are from the last connection.", ui.onSurfaceVariant).apply {
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
            })
            notConnected.addView(ui.button(Ui.ButtonStyle.TEXT, "Find a FlySight") { main.findDevice() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(4) })
        }
        fun set(label: String, value: String?) = statusItems[label]?.setSupporting(value ?: "–")
        set("Battery", main.battery?.let { "$it %" })
        set("Mode", main.mode.takeIf { it != DeviceMode.UNKNOWN }?.label)
        set("MTU", main.connection?.mtu?.toString())
        set("Updated", main.updated?.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
        updateFirmware()
    }

    // ---- Firmware ----

    private fun buildFirmware(): View {
        val column = ui.column().apply { setPadding(0, ui.dp(8), 0, ui.dp(24)) }
        val card = ui.card(ui.surfaceContainerHigh, 16f).apply {
            addView(ui.text(Ui.Type.LABEL_MEDIUM, "Installed firmware", ui.onSurfaceVariant))
            addView(fwVersion.apply { setPadding(0, ui.dp(4), 0, 0) })
            addView(fwModel)
            addView(fwDeviceId.apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        column.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0) })

        column.addView(ui.header("Update"))
        column.addView(ui.note(
            "An update has two steps. First, upload an APP.SFB file to the FlySight. Then install it. " +
                "Make the file with Scripts/build.sh --batch, for the hardware batch of this FlySight."))
        fwFileItem.onClick { fwKnown = false; refreshFirmwareFile() }
        column.addView(fwFileItem)
        column.addView(fwProgressBox)
        column.addView(fwMessage)
        column.addView(ui.row(uploadButton, ui.space(0).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(8), 0) }, installButton)
            .apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0) })
        column.addView(ui.note(
            "The upload and the install need idle mode. If the FlySight records a session, the app stops it first.").apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4))
        })
        column.addView(ui.note(
            "During the install, the FlySight restarts and the connection closes. " +
                "The bootloader does not install a file for a different hardware batch. " +
                "The configuration and the files on the microSD card stay."))
        return ui.scroll(column, bar)
    }

    private fun updateFirmware() {
        fwVersion.text = main.info[MainActivity.INFO_FIRMWARE] ?: "–"
        fwModel.text = listOfNotNull(main.info[MainActivity.INFO_MODEL], main.info[MainActivity.INFO_MANUFACTURER])
            .joinToString(" · ").ifEmpty { "Connect to read the firmware version." }
        fwDeviceId.text = deviceId?.let { "Device ID $it" } ?: ""
        fwDeviceId.visibility = if (deviceId == null) View.GONE else View.VISIBLE
        val file = fwFile
        fwFileItem.setSupporting(when {
            uploading -> "Upload…"
            fwListing -> "Reading…"
            !fwKnown && main.connected -> "Available in idle mode. Touch to read again."
            !fwKnown -> "Connect to read."
            file == null -> "No file. Upload a file first."
            else -> "${FileInfo.formatSize(file.size)} · ${file.modified}".trimEnd(' ', '·')
        })
        val busy = uploading || fwListing
        uploadButton.isEnabled = !busy
        installButton.isEnabled = !busy && main.connected && !(fwKnown && file == null)
        if (currentTab == TAB_FIRMWARE && main.connected) {
            if (deviceId == null && !deviceIdAsked) {
                deviceIdAsked = true
                main.sendDs(P.DS_CMD_GET_DEVICE_ID)
            }
            if (!fwKnown) refreshFirmwareFile()
        }
    }

    /** Read /FW to find the uploaded file. The app does this only when the files are free. */
    private fun refreshFirmwareFile() {
        val f = main.files ?: return
        if (fwListing || uploading || !main.connected || f.busy || main.sync.running) return
        if (main.mode != DeviceMode.SLEEP && main.mode != DeviceMode.SENSOR) return
        fwListing = true
        updateFirmware()
        f.list(FW_DIR) { result ->
            fwListing = false
            fwKnown = true
            // With no /FW folder, there is no file.
            fwFile = result.getOrNull()?.firstOrNull { !it.isDirectory && it.name.equals(FW_NAME, ignoreCase = true) }
            updateFirmware()
        }
    }

    private fun pickFirmware() {
        if (main.connection == null) return main.snack("Connect to the FlySight first.", "Connect") { main.connectOrScan() }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        main.startActivityForResult(intent, MainActivity.REQUEST_FIRMWARE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != MainActivity.REQUEST_FIRMWARE) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        val bytes = try {
            main.readUri(uri)
        } catch (e: Exception) {
            main.snack("Phone file error: ${e.message}")
            null
        } ?: return
        upload(bytes)
    }

    private fun upload(data: ByteArray) {
        if (data.size < 512 || data.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) != "SFU1") {
            ui.dialog("This is not an APP.SFB file", "An update file starts with \"SFU1\". Make it with Scripts/build.sh --batch.",
                positive = Ui.DialogButton("OK"), negative = null).show()
            return
        }
        showFirmwareMessage(null)
        main.whenFilesFree {
            val f = main.idleFiles() ?: return@whenFilesFree
            uploading = true
            fwProgress.isIndeterminate = true
            fwProgressText.text = "Upload $FW_PATH…"
            fwProgressBox.visibility = View.VISIBLE
            updateFirmware()
            val started = System.currentTimeMillis()
            // The folder is usually there. A NAK only means that it exists.
            f.mkdir(FW_DIR) { _ ->
                f.write(FW_PATH, data, progress = { sent, total ->
                    fwProgress.isIndeterminate = false
                    fwProgress.progress = if (total > 0) (sent * 1000L / total).toInt() else 0
                    fwProgressText.text = "Upload ${if (total > 0) sent * 100L / total else 0} % · " +
                        "${FileInfo.formatSize(sent.toLong())} of ${FileInfo.formatSize(total.toLong())}"
                }) { result ->
                    uploading = false
                    fwProgressBox.visibility = View.GONE
                    result.onSuccess {
                        val seconds = (System.currentTimeMillis() - started) / 1000.0
                        main.log(String.format(Locale.US, "FT: wrote %s, %d bytes, %.1f s", FW_PATH, data.size, seconds))
                        fwKnown = false
                        updateFirmware()
                        main.confirm("Upload complete", "Install $FW_PATH now?", "Install") { sendInstall() }
                    }.onFailure {
                        fwKnown = false
                        showFirmwareMessage(main.fileError("Upload $FW_PATH", it))
                        updateFirmware()
                    }
                }
            }
        }
    }

    private fun install() {
        main.confirm("Install $FW_PATH?",
            "The FlySight restarts and installs the uploaded file. The connection closes.",
            "Install") { sendInstall() }
    }

    private fun sendInstall() {
        showFirmwareMessage(null)
        main.sendDs(P.DS_CMD_INSTALL_FIRMWARE)
    }

    private fun showFirmwareMessage(text: String?) {
        fwMessage.text = text ?: ""
        fwMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- Debug ----

    private fun buildDebug(): View {
        val column = ui.column().apply { setPadding(0, 0, 0, ui.dp(24)) }
        fun action(label: String, supporting: String? = null, run: () -> Unit) =
            column.addView(ui.ListItem(label, supporting).onClick(run))

        column.addView(ui.note(
            "Functions to test the FlySight and the app. You do not need them for jumps.").apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0)
        })

        column.addView(ui.header("Status"))
        column.addView(ui.note(
            "The values that the app read last. In idle mode, the FlySight measures the battery when the phone reads it. " +
                "The app reads it each 15 s. In active mode, the FlySight sends the level each second. " +
                "MTU is the largest BLE packet of this connection."))
        for (label in listOf("Battery", "Mode", "MTU", "Updated")) {
            val item = ui.ListItem(label, "–")
            statusItems[label] = item
            column.addView(item)
        }
        column.addView(ui.button(Ui.ButtonStyle.TONAL, "Read again", R.drawable.ic_refresh) { main.readStatus() },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(ui.dp(16), ui.dp(8), 0, 0) })

        column.addView(ui.header("Commands"))
        column.addView(ui.note(
            "The app sends a command to the Device State service. The last answer shows the status and the data from the FlySight. " +
                "The log shows all commands and answers."))
        column.addView(dsResult)
        action("Get the firmware version", "The version text of the firmware") { main.sendDs(P.DS_CMD_GET_FW_VERSION) }
        action("Get the device ID", "The unique ID of the processor") { main.sendDs(P.DS_CMD_GET_DEVICE_ID) }

        column.addView(ui.header("Mode"))
        column.addView(ui.note(
            "Change the mode as the button does. The FlySight accepts these requests in idle mode only, except Idle and Reboot."))
        action("Idle", "Stops the current mode. In active mode, the session ends.") { main.sendDs(P.DS_CMD_REQUEST_SLEEP) }
        action("Active", "Records a session with GNSS and sensors, and plays the audio as in a jump") { main.sendDs(P.DS_CMD_REQUEST_ACTIVE) }
        action("Start", "The mode for the starter pistol. Use the commands below.") { main.sendDs(P.DS_CMD_REQUEST_START) }
        action("Config", "Plays the profile names. Push the button to select one.") { main.sendDs(P.DS_CMD_REQUEST_CONFIG) }
        action("Pairing", "Lets a new phone pair. Use it when you change the phone.") { main.sendDs(P.DS_CMD_REQUEST_PAIRING) }
        action("Sensor", "Reads all sensors for the Sensors tab. It records nothing, and it stops when the connection closes.") { main.sendDs(P.DS_CMD_REQUEST_SENSOR) }
        action("Reboot", "Restarts the FlySight. The connection closes.") {
            main.confirm("Reboot the FlySight?", "The connection closes. The FlySight starts again in idle mode.", "Reboot") {
                main.sendDs(P.DS_CMD_REBOOT)
            }
        }

        column.addView(ui.header("Live GNSS data"))
        column.addView(ui.note(
            "In active mode and start mode, the FlySight can send each GNSS measurement to the app. " +
                "The data fields select the values in each packet. Select the fields, then send them to the FlySight."))
        column.addView(ui.ListItem("Receive live GNSS data", "In active mode or start mode only", trailing = gnssSwitch)
            .onClick { gnssSwitch.toggle() })
        column.addView(ui.text(Ui.Type.TITLE_SMALL, "Data fields", ui.onSurfaceVariant).apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4))
        })
        for ((bit, label) in listOf(
            P.GNSS_BIT_TOW to "Time of week",
            P.GNSS_BIT_POSITION to "Position",
            P.GNSS_BIT_VELOCITY to "Velocity",
            P.GNSS_BIT_ACCURACY to "Accuracy",
            P.GNSS_BIT_NUM_SV to "Number of satellites",
        )) {
            val item = ui.ListItem(label).onClick { setMaskBit(bit, mask[bit] != true) }
            maskItems[bit] = item
            column.addView(item)
        }
        showMask(P.GNSS_DEFAULT_MASK)
        column.addView(ui.row(
            ui.button(Ui.ButtonStyle.TONAL, "Send to FlySight") {
                val value = mask.filterValues { it }.keys.fold(0) { a, b -> a or b }
                main.sendControl(P.SENSOR_DATA_SERVICE, P.SD_CONTROL_POINT, "SD", P.SD_COMMAND_NAMES, P.SD_CMD_SET_MASK, value)
            },
            ui.button(Ui.ButtonStyle.TEXT, "Read from FlySight") {
                main.sendControl(P.SENSOR_DATA_SERVICE, P.SD_CONTROL_POINT, "SD", P.SD_COMMAND_NAMES, P.SD_CMD_GET_MASK)
            },
        ).apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) })
        maskResult.setPadding(ui.dp(16), 0, ui.dp(16), 0)
        column.addView(maskResult)
        column.addView(ui.text(Ui.Type.TITLE_SMALL, "Last values", ui.onSurfaceVariant).apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4))
        })
        for (label in GNSS_ROWS) {
            val item = ui.ListItem(label, "–")
            gnssItems[label] = item
            column.addView(item)
        }

        column.addView(ui.header("Starter pistol"))
        column.addView(ui.note(
            "In start mode, the FlySight plays a countdown and keeps the time of the start. " +
                "Use these commands to test the countdown. They work in start mode only."))
        column.addView(spResult)
        fun sp(opcode: Int) = main.sendControl(P.STARTER_PISTOL_SERVICE, P.SP_CONTROL_POINT, "SP", P.SP_COMMAND_NAMES, opcode)
        action("Start the countdown") { sp(P.SP_CMD_START_COUNTDOWN) }
        action("Cancel the countdown") { sp(P.SP_CMD_CANCEL_COUNTDOWN) }
        action("Read the result", "The time of the last start") { main.connection?.read(P.STARTER_PISTOL_SERVICE, P.SP_RESULT) }
        return ui.scroll(column, bar)
    }

    private fun setMaskBit(bit: Int, on: Boolean) {
        mask[bit] = on
        maskItems[bit]?.setLeading(ui.icon(if (on) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off, if (on) ui.primary else ui.onSurfaceVariant))
    }

    private fun showMask(value: Int) {
        for (bit in maskItems.keys) setMaskBit(bit, value and bit != 0)
    }

    private fun showGnss(m: GnssMeasurement) {
        gnssPackets++
        fun set(row: String, v: String?) = gnssItems[row]?.setSupporting(v ?: "–")
        fun f(format: String, v: Double?) = v?.let { String.format(Locale.US, format, it) }
        set(G_TOW, m.iTowMs?.let { String.format(Locale.US, "%.1f s", it / 1000.0) })
        set(G_POSITION, if (m.latDeg != null) String.format(Locale.US, "%.7f°, %.7f°", m.latDeg, m.lonDeg) else null)
        set(G_HMSL, f("%.1f m", m.hMslM))
        set(G_VEL, if (m.velN != null) String.format(Locale.US, "%.2f / %.2f / %.2f m/s", m.velN, m.velE, m.velD) else null)
        set(G_SPEED, if (m.velN != null && m.velE != null && m.velD != null)
            String.format(Locale.US, "H %.1f km/h · V %.1f km/h", hypot(m.velN, m.velE) * 3.6, m.velD * 3.6) else null)
        set(G_ACC, if (m.hAccM != null) String.format(Locale.US, "H %.1f m · V %.1f m · S %.2f m/s", m.hAccM, m.vAccM, m.sAccMs) else null)
        set(G_SV, m.numSv?.toString())
        set(G_PACKETS, "$gnssPackets · mask 0x${String.format(Locale.US, "%02X", m.mask)} · ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.S"))}")
    }

    override fun onValue(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            P.SD_GNSS_MEASUREMENT -> GnssMeasurement.parse(value)?.let { showGnss(it) }
            P.SP_RESULT -> StartResult.parse(value)?.let {
                spResult.setSupporting("Start time: $it")
                main.log("SP result: $it")
            }
            P.DS_CONTROL_POINT -> response("DS", P.DS_COMMAND_NAMES, value)?.let { r ->
                dsResult.setSupporting(describe(P.DS_COMMAND_NAMES, r))
                onDsResponse(r)
            }
            P.SP_CONTROL_POINT -> response("SP", P.SP_COMMAND_NAMES, value)?.let { spResult.setSupporting(describe(P.SP_COMMAND_NAMES, it)) }
            P.SD_CONTROL_POINT -> response("SD", P.SD_COMMAND_NAMES, value)?.let { r ->
                if (r.opcode == P.SD_CMD_GET_MASK && r.success && r.data.isNotEmpty()) showMask(r.data[0].toInt() and 0xff)
                maskResult.text = describe(P.SD_COMMAND_NAMES, r)
            }
        }
    }

    private fun onDsResponse(r: CpResponse) {
        when (r.opcode) {
            P.DS_CMD_GET_DEVICE_ID -> if (r.success && r.data.isNotEmpty()) {
                deviceId = r.dataText
                updateFirmware()
            }
            P.DS_CMD_INSTALL_FIRMWARE -> if (r.success) {
                main.snack("The FlySight installs the firmware. Connect again after it restarts.")
            } else {
                showFirmwareMessage("The FlySight did not start the install: ${r.statusText}.")
            }
        }
    }

    private fun response(service: String, names: Map<Int, String>, value: ByteArray): CpResponse? {
        val r = CpResponse.parse(value)
        if (r == null) {
            main.log("$service: unknown response ${value.toHex()}")
            return null
        }
        main.log("$service ← ${describe(names, r)} [${value.toHex()}]")
        return r
    }

    private fun describe(names: Map<Int, String>, r: CpResponse): String {
        val name = names[r.opcode] ?: String.format(Locale.US, "Command 0x%02X", r.opcode)
        val data = if (r.data.isEmpty()) "" else ": ${r.dataText}"
        return "$name → ${r.statusText}$data"
    }

    // ---- Log ----

    private fun buildLog(): View {
        val actions = ui.row(
            ui.button(Ui.ButtonStyle.TEXT, "Clear", R.drawable.ic_delete) {
                main.logLines.clear()
                logText.text = ""
            },
            ui.button(Ui.ButtonStyle.TEXT, "Share", R.drawable.ic_share) {
                main.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, main.logLines.joinToString("\n"))
                }, "Share the log"))
            },
        ).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }
        logText.text = main.logLines.joinToString("\n")
        return ui.column().apply {
            addView(actions)
            addView(ui.note("The BLE events, the commands and the answers of this app session. Share the log to report a problem.")
                .apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(4)) })
            addView(ui.divider())
            addView(logScroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onLog(line: String) {
        if (main.logLines.size >= 1000) logText.text = main.logLines.joinToString("\n")
        else logText.append(if (logText.length() == 0) line else "\n$line")
        if (currentTab == TAB_LOG) scrollLogToEnd()
    }

    private fun scrollLogToEnd() = logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

    companion object {
        private const val TAB_FIRMWARE = 0
        private const val TAB_LOG = 2

        private const val FW_DIR = "/FW"
        private const val FW_NAME = "APP.SFB"
        private const val FW_PATH = "$FW_DIR/$FW_NAME"

        private const val G_TOW = "Time of week"
        private const val G_POSITION = "Position"
        private const val G_HMSL = "Height above sea level"
        private const val G_VEL = "Velocity N / E / D"
        private const val G_SPEED = "Speed"
        private const val G_ACC = "Accuracy"
        private const val G_SV = "Satellites"
        private const val G_PACKETS = "Packets"
        private val GNSS_ROWS = listOf(G_TOW, G_POSITION, G_HMSL, G_VEL, G_SPEED, G_ACC, G_SV, G_PACKETS)
    }
}

/** A full screen text editor for a small file on the FlySight. */
class TextFileScreen(
    main: MainActivity,
    path: String,
    private val original: String,
    private val onSave: (String, () -> Unit) -> Unit,
) : Screen(main) {

    private var saved = original
    private val bar = ui.TopBar(path.substringAfterLast('/'))
    private val editor = EditText(main).apply {
        ui.style(this, Ui.Type.BODY_MEDIUM)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(true)
        background = null
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setText(original)
    }
    override val view: View

    init {
        bar.setNavigation(R.drawable.ic_back, "Back") { if (!onBack()) main.popScreen() }
        bar.addAction(ui.button(Ui.ButtonStyle.TEXT, "Save") {
            val text = editor.text.toString()
            onSave(text) { saved = text }
        })
        view = ui.column().apply {
            addView(bar)
            addView(editor, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onBack(): Boolean {
        if (editor.text.toString() == saved) return false
        ui.dialog("Discard the changes?", null,
            positive = Ui.DialogButton("Discard") { main.popScreen() },
            negative = Ui.DialogButton("Keep editing")).show()
        return true
    }
}
