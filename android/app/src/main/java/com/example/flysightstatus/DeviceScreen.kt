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

/** Test functions: status, files, live data, control points, and the log. */
@SuppressLint("SetTextI18n") // English only
class DeviceScreen(main: MainActivity) : Screen(main) {

    private val bar = ui.TopBar("Device")
    private val connectButton = ui.button(Ui.ButtonStyle.TEXT, "Connect") {
        if (main.connection != null) main.disconnect() else main.connectOrScan()
    }
    private val tabs = ui.Tabs(listOf("Status", "Files", "Live", "Control", "Log")) { selectTab(it) }
    private val pages = FrameLayout(main)
    private var currentTab = 0
    private var wasConnected = false

    override val view: View

    // Status
    private val notConnected = ui.column()
    private val statusItems = linkedMapOf<String, Ui.ListItem>()

    // Files
    private var currentDir = "/"
    private var listedOnce = false
    private val pathText = ui.text(Ui.Type.TITLE_SMALL, "/").apply { typeface = Typeface.MONOSPACE }
    private val fileList = ui.column()
    private val fileProgress = ui.linearProgress()
    private val fileProgressText = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant)
    private val fileProgressRow = LinearLayout(main)
    private val fileMessage = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant)
    private var pendingSave: ByteArray? = null
    private var pendingReplacePath: String? = null

    // Live
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

    // Control
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
        pages.addView(buildStatus())
        pages.addView(buildFiles())
        pages.addView(buildLive())
        pages.addView(buildControl())
        pages.addView(buildLog())
        view = ui.column().apply {
            addView(bar)
            addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(ui.divider())
            addView(pages, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        selectTab(0)
        updateStatus()
    }

    override fun onShow() {
        updateStatus()
        if (currentTab == TAB_LOG) scrollLogToEnd()
        if (currentTab == TAB_FILES && !listedOnce && main.connected) listDir(currentDir)
    }

    override fun onStateChanged() {
        val connected = main.connected
        if (connected != wasConnected) {
            wasConnected = connected
            if (connected) {
                if (gnssSwitch.isChecked) main.connection?.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_GNSS_MEASUREMENT, true)
                if (currentTab == TAB_FILES && main.currentTab == MainActivity.TAB_DEVICE) listDir(currentDir)
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
        if (index == TAB_FILES && !listedOnce && main.connected) listDir(currentDir)
        if (index == TAB_LOG) scrollLogToEnd()
    }

    private fun clear() {
        gnssPackets = 0
        gnssItems.values.forEach { it.setSupporting("–") }
        dsResult.setSupporting("–")
        spResult.setSupporting("–")
        currentDir = "/"
        pathText.text = currentDir
        listedOnce = false
        fileList.removeAllViews()
        showFileMessage(null)
        setFileIdle()
    }

    // ---- Status ----

    private fun buildStatus(): View {
        val column = ui.column().apply { setPadding(0, 0, 0, ui.dp(24)) }
        column.addView(notConnected)
        for (label in listOf("Battery", "Mode", MainActivity.INFO_FIRMWARE, MainActivity.INFO_MANUFACTURER, MainActivity.INFO_MODEL, "MTU", "Updated")) {
            val item = ui.ListItem(label, "–")
            statusItems[label] = item
            column.addView(item)
        }
        column.addView(ui.button(Ui.ButtonStyle.TONAL, "Read again", R.drawable.ic_refresh) { main.readStatus() },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(ui.dp(16), ui.dp(8), 0, 0) })
        column.addView(ui.note(
            "In idle mode, the FlySight measures the battery when the phone reads it. " +
                "The app reads it each 15 s. In active mode, the FlySight sends the level each second."))
        return ui.scroll(column, bar)
    }

    private fun updateStatus() {
        bar.title.text = main.deviceName ?: "Device"
        connectButton.text = if (main.connection != null) "Disconnect" else "Connect"
        notConnected.removeAllViews()
        if (main.connection == null) {
            notConnected.addView(ui.text(Ui.Type.BODY_MEDIUM, "Not connected. The values are from the last connection.", ui.onSurfaceVariant).apply {
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0)
            })
            notConnected.addView(ui.button(Ui.ButtonStyle.TEXT, "Find a FlySight") { main.findDevice() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(4) })
        }
        fun set(label: String, value: String?) = statusItems[label]?.setSupporting(value ?: "–")
        set("Battery", main.battery?.let { "$it %" })
        set("Mode", main.mode.takeIf { it != DeviceMode.UNKNOWN }?.label)
        for (key in listOf(MainActivity.INFO_FIRMWARE, MainActivity.INFO_MANUFACTURER, MainActivity.INFO_MODEL)) set(key, main.info[key])
        set("MTU", main.connection?.mtu?.toString())
        set("Updated", main.updated?.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
    }

    // ---- Files ----

    private fun buildFiles(): View {
        val toolbar = LinearLayout(main).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
            addView(ui.iconButton(R.drawable.ic_arrow_up, "Parent folder") { if (currentDir != "/") listDir(parentOf(currentDir)) })
            addView(pathText, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(4) })
            addView(ui.iconButton(R.drawable.ic_refresh, "Read the folder again") { listDir(currentDir) })
            addView(ui.iconButton(R.drawable.ic_new_folder, "New folder") { askNewFolder() })
            addView(ui.iconButton(R.drawable.ic_upload, "Upload a file") { pickFile(MainActivity.REQUEST_UPLOAD) })
        }
        fileProgressRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(4), 0)
            addView(fileProgressText, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.button(Ui.ButtonStyle.TEXT, "Cancel") { main.files?.cancel() })
        }
        fileMessage.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        val list = ui.column().apply {
            addView(fileMessage)
            addView(fileList)
            addView(ui.note("Files are available in idle mode only. Tap a file to see the actions. Hold an item to delete it."))
        }
        setFileIdle()
        return ui.column().apply {
            addView(toolbar)
            addView(fileProgress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(fileProgressRow)
            addView(ui.scroll(list), LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    private fun join(dir: String, name: String) = JumpSync.join(dir, name)

    private fun parentOf(path: String): String {
        val i = path.trimEnd('/').lastIndexOf('/')
        return if (i <= 0) "/" else path.substring(0, i)
    }

    private fun listDir(path: String) {
        val f = main.idleFiles() ?: return
        setFileBusy("Reading $path…", indeterminate = true)
        f.list(path) { result ->
            setFileIdle()
            result.onSuccess { entries ->
                listedOnce = true
                currentDir = path
                pathText.text = path
                fileList.removeAllViews()
                val visible = entries.filter { it.name != "." && it.name != ".." }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                for (info in visible) {
                    val item = ui.ListItem(
                        if (info.isDirectory) info.name else info.name,
                        if (info.isDirectory) null else "${FileInfo.formatSize(info.size)} · ${info.modified}",
                        ui.avatar(if (info.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                    )
                    item.onClick { onFileSelected(info) }.onLongClick { confirmDelete(info) }
                    fileList.addView(item)
                }
                showFileMessage(if (visible.isEmpty()) "The folder is empty." else null)
                main.log("FT: listed $path (${entries.size} items)")
            }.onFailure { showFileMessage(main.fileError("Read $path", it)) }
        }
    }

    private fun onFileSelected(info: FileInfo) {
        val path = join(currentDir, info.name)
        if (info.isDirectory) return listDir(path)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (info.size <= MAX_EDIT_SIZE) actions += "Edit as text" to { readFile(path, info.size) { showEditor(path, it) } }
        if (info.name.equals("TRACK.CSV", ignoreCase = true)) {
            actions += "Show in the track viewer" to { readFile(path, info.size) { openTrack(path, it) } }
        }
        actions += "Save on the phone" to { readFile(path, info.size) { saveToPhone(info.name, it) } }
        actions += "Replace with a file from the phone" to {
            pendingReplacePath = path
            pickFile(MainActivity.REQUEST_REPLACE)
        }
        actions += "Delete" to { confirmDelete(info) }
        ui.menuDialog(info.name, actions, "$path · ${FileInfo.formatSize(info.size)}").show()
    }

    private fun readFile(path: String, size: Long, then: (ByteArray) -> Unit) {
        val f = main.idleFiles() ?: return
        val started = System.currentTimeMillis()
        setFileBusy("Download $path…", indeterminate = size <= 0)
        f.read(path, progress = { n -> showProgress(n.toLong(), size, "Download") }) { result ->
            setFileIdle()
            result.onSuccess { data ->
                main.log("FT: read $path, ${data.size} bytes, ${rate(data.size, started)}")
                then(data)
            }.onFailure { showFileMessage(main.fileError("Read $path", it)) }
        }
    }

    private fun writeFile(path: String, data: ByteArray, then: () -> Unit = { listDir(currentDir) }) {
        val f = main.idleFiles() ?: return
        val started = System.currentTimeMillis()
        setFileBusy("Upload $path…", indeterminate = false)
        f.write(path, data, progress = { sent, total -> showProgress(sent.toLong(), total.toLong(), "Upload") }) { result ->
            setFileIdle()
            result.onSuccess {
                main.log("FT: wrote $path, ${data.size} bytes, ${rate(data.size, started)}")
                main.snack("Saved $path.")
                then()
            }.onFailure { showFileMessage(main.fileError("Write $path", it)) }
        }
    }

    private fun confirmDelete(info: FileInfo) {
        val path = join(currentDir, info.name)
        main.confirm("Delete ${info.name}?", if (info.isDirectory) "Only an empty folder can be deleted." else "You cannot undo this.", "Delete") {
            val f = main.idleFiles() ?: return@confirm
            f.delete(path) { result ->
                result.onSuccess { main.log("FT: deleted $path"); listDir(currentDir) }
                    .onFailure { showFileMessage(main.fileError("Delete $path", it)) }
            }
        }
    }

    private fun askNewFolder() {
        ui.inputDialog("New folder", "", "In $currentDir. Maximum 8 characters.",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, okLabel = "Make") { name ->
            if (name.isEmpty()) return@inputDialog
            val f = main.idleFiles() ?: return@inputDialog
            val path = join(currentDir, name)
            f.mkdir(path) { result ->
                result.onSuccess { main.log("FT: made folder $path"); listDir(currentDir) }
                    .onFailure { showFileMessage(main.fileError("Make $path", it)) }
            }
        }.show()
    }

    private fun showEditor(path: String, data: ByteArray) {
        main.push(TextFileScreen(main, path, data.toString(Charsets.ISO_8859_1)) { text, done ->
            writeFile(path, text.toByteArray(Charsets.ISO_8859_1)) { done(); listDir(currentDir) }
        })
    }

    private fun openTrack(remotePath: String, data: ByteArray) {
        try {
            TrackActivity.open(main, TrackStore.save(main, TrackStore.localName(remotePath), data))
        } catch (e: Exception) {
            main.snack("Cannot save the track: ${e.message}")
        }
    }

    private fun setFileBusy(text: String, indeterminate: Boolean) {
        fileProgress.visibility = View.VISIBLE
        fileProgress.isIndeterminate = indeterminate
        fileProgress.progress = 0
        fileProgressRow.visibility = View.VISIBLE
        fileProgressText.text = text
        showFileMessage(null)
    }

    private fun setFileIdle() {
        fileProgress.visibility = View.INVISIBLE
        fileProgressRow.visibility = View.GONE
    }

    private fun showProgress(done: Long, total: Long, label: String) {
        if (total > 0) {
            fileProgress.isIndeterminate = false
            fileProgress.progress = (done * 1000 / total).toInt()
            fileProgressText.text = "$label: ${FileInfo.formatSize(done)} of ${FileInfo.formatSize(total)}"
        } else {
            fileProgressText.text = "$label: ${FileInfo.formatSize(done)}"
        }
    }

    private fun showFileMessage(text: String?) {
        fileMessage.text = text ?: ""
        fileMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun rate(bytes: Int, startedMs: Long): String {
        val seconds = (System.currentTimeMillis() - startedMs) / 1000.0
        return String.format(Locale.US, "%.1f s, %.1f KB/s", seconds, if (seconds > 0) bytes / 1024.0 / seconds else 0.0)
    }

    // ---- Phone files ----

    private fun pickFile(requestCode: Int) {
        if (main.idleFiles() == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        main.startActivityForResult(intent, requestCode)
    }

    private fun saveToPhone(name: String, data: ByteArray) {
        pendingSave = data
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        @Suppress("DEPRECATION")
        main.startActivityForResult(intent, MainActivity.REQUEST_SAVE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode !in listOf(MainActivity.REQUEST_SAVE, MainActivity.REQUEST_UPLOAD, MainActivity.REQUEST_REPLACE, MainActivity.REQUEST_FIRMWARE)) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            pendingSave = null
            return
        }
        try {
            when (requestCode) {
                MainActivity.REQUEST_SAVE -> {
                    val bytes = pendingSave ?: return
                    main.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    main.snack("Saved ${FileInfo.formatSize(bytes.size.toLong())} on the phone.")
                    pendingSave = null
                }
                MainActivity.REQUEST_UPLOAD -> {
                    val bytes = main.readUri(uri) ?: return
                    askUploadName(suggestShortName(main.displayName(uri) ?: "FILE.BIN"), bytes)
                }
                MainActivity.REQUEST_REPLACE -> {
                    val bytes = main.readUri(uri) ?: return
                    writeFile(pendingReplacePath ?: return, bytes)
                }
                MainActivity.REQUEST_FIRMWARE -> uploadFirmware(main.readUri(uri) ?: return)
            }
        } catch (e: Exception) {
            main.snack("Phone file error: ${e.message}")
            main.log("Phone file error: $e")
        }
    }

    private fun askUploadName(suggested: String, data: ByteArray) {
        ui.inputDialog("Upload ${FileInfo.formatSize(data.size.toLong())} to $currentDir", suggested,
            "Use a short name: 8 characters, a dot, 3 characters. A file with the same name is replaced.",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, okLabel = "Upload") { name ->
            if (name.isNotEmpty()) writeFile(join(currentDir, name), data)
        }.show()
    }

    /** The FlySight file system has no long file names. Make an 8.3 name. */
    private fun suggestShortName(name: String): String {
        val clean = { s: String, n: Int -> s.uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(n) }
        val dot = name.lastIndexOf('.')
        val base = clean(if (dot > 0) name.substring(0, dot) else name, 8).ifEmpty { "FILE" }
        val ext = if (dot > 0) clean(name.substring(dot + 1), 3) else ""
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    // ---- Live ----

    private fun buildLive(): View {
        val column = ui.column().apply { setPadding(0, 0, 0, ui.dp(24)) }
        column.addView(ui.ListItem("Receive live GNSS data", "In active mode or start mode only", trailing = gnssSwitch)
            .onClick { gnssSwitch.toggle() })
        column.addView(ui.header("Data fields"))
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
        column.addView(ui.header("Last values"))
        for (label in GNSS_ROWS) {
            val item = ui.ListItem(label, "–")
            gnssItems[label] = item
            column.addView(item)
        }
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

    // ---- Control ----

    private fun buildControl(): View {
        val column = ui.column().apply { setPadding(0, 0, 0, ui.dp(24)) }
        fun action(label: String, supporting: String? = null, run: () -> Unit) =
            column.addView(ui.ListItem(label, supporting).onClick(run))

        column.addView(ui.header("Device state"))
        column.addView(dsResult)
        action("Get the firmware version") { main.sendDs(P.DS_CMD_GET_FW_VERSION) }
        action("Get the device ID") { main.sendDs(P.DS_CMD_GET_DEVICE_ID) }

        column.addView(ui.header("Mode"))
        column.addView(ui.note("The FlySight accepts these requests in idle mode only, except Idle."))
        action("Idle") { main.sendDs(P.DS_CMD_REQUEST_SLEEP) }
        action("Active", "Records a session") { main.sendDs(P.DS_CMD_REQUEST_ACTIVE) }
        action("Start", "For the starter pistol") { main.sendDs(P.DS_CMD_REQUEST_START) }
        action("Config", "Plays the profiles to select one") { main.sendDs(P.DS_CMD_REQUEST_CONFIG) }
        action("Pairing", "Lets a new phone pair") { main.sendDs(P.DS_CMD_REQUEST_PAIRING) }
        action("Sensor", "Reads all sensors") { main.sendDs(P.DS_CMD_REQUEST_SENSOR) }
        action("Reboot", "The connection closes") {
            main.confirm("Reboot the FlySight?", "The connection closes. The FlySight starts again in idle mode.", "Reboot") {
                main.sendDs(P.DS_CMD_REBOOT)
            }
        }

        column.addView(ui.header("Firmware update"))
        column.addView(ui.note("Use an APP.SFB file for the hardware batch of this FlySight. Make it with Scripts/build.sh --batch."))
        action("Upload APP.SFB", "To /FW/APP.SFB") { pickFile(MainActivity.REQUEST_FIRMWARE) }
        action("Install /FW/APP.SFB", "The FlySight restarts and installs the file") {
            main.confirm("Install /FW/APP.SFB?",
                "The FlySight restarts and installs the uploaded file. If the file is not correct for this FlySight, the bootloader does not install it.",
                "Install") { main.sendDs(P.DS_CMD_INSTALL_FIRMWARE) }
        }

        column.addView(ui.header("Starter pistol"))
        column.addView(ui.note("Works in start mode only."))
        column.addView(spResult)
        fun sp(opcode: Int) = main.sendControl(P.STARTER_PISTOL_SERVICE, P.SP_CONTROL_POINT, "SP", P.SP_COMMAND_NAMES, opcode)
        action("Start the countdown") { sp(P.SP_CMD_START_COUNTDOWN) }
        action("Cancel the countdown") { sp(P.SP_CMD_CANCEL_COUNTDOWN) }
        action("Read the result") { main.connection?.read(P.STARTER_PISTOL_SERVICE, P.SP_RESULT) }
        return ui.scroll(column, bar)
    }

    private fun uploadFirmware(data: ByteArray) {
        if (data.size < 512 || data.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) != "SFU1") {
            ui.dialog("This is not an APP.SFB file", "An update file starts with \"SFU1\". Make it with Scripts/build.sh --batch.",
                positive = Ui.DialogButton("OK"), negative = null).show()
            return
        }
        val f = main.idleFiles() ?: return
        selectTab(TAB_FILES)
        // The folder is usually there. A NAK only means that it exists.
        f.mkdir("/FW") { _ ->
            writeFile("/FW/APP.SFB", data) {
                listDir("/FW")
                main.confirm("Upload complete", "Install /FW/APP.SFB now?", "Install") { main.sendDs(P.DS_CMD_INSTALL_FIRMWARE) }
            }
        }
    }

    override fun onValue(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            P.SD_GNSS_MEASUREMENT -> GnssMeasurement.parse(value)?.let { showGnss(it) }
            P.SP_RESULT -> StartResult.parse(value)?.let {
                spResult.setSupporting("Start time: $it")
                main.log("SP result: $it")
            }
            P.DS_CONTROL_POINT -> response("DS", P.DS_COMMAND_NAMES, value)?.let { dsResult.setSupporting(describe(P.DS_COMMAND_NAMES, it)) }
            P.SP_CONTROL_POINT -> response("SP", P.SP_COMMAND_NAMES, value)?.let { spResult.setSupporting(describe(P.SP_COMMAND_NAMES, it)) }
            P.SD_CONTROL_POINT -> response("SD", P.SD_COMMAND_NAMES, value)?.let { r ->
                if (r.opcode == P.SD_CMD_GET_MASK && r.success && r.data.isNotEmpty()) showMask(r.data[0].toInt() and 0xff)
                maskResult.text = describe(P.SD_COMMAND_NAMES, r)
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
        private const val TAB_FILES = 1
        private const val TAB_LOG = 4
        private const val MAX_EDIT_SIZE = 64 * 1024L

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
