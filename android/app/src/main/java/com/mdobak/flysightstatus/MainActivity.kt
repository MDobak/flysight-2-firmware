package com.mdobak.flysightstatus

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import com.mdobak.flysightstatus.FlySightProtocol as P

/**
 * The app shell: the Bluetooth connection, the navigation bar with the
 * Home, Jumps, Sensors, Config, and Device screens, and full screens on top of them.
 */
@SuppressLint("MissingPermission") // Permissions are checked before each Bluetooth call
class MainActivity : Activity(), FlySightConnection.Listener {

    lateinit var ui: Ui
        private set
    val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private val prefs by lazy { getSharedPreferences("main", Context.MODE_PRIVATE) }

    // ---- Connection and device data ----

    var connection: FlySightConnection? = null
        private set
    var files: FileTransfer? = null
        private set
    var state = FlySightConnection.State.DISCONNECTED
        private set
    /** Name of the connected FlySight, or of the FlySight that the app connects to. */
    var deviceName: String? = null
        private set
    var battery: Int? = null
        private set
    var mode = DeviceMode.UNKNOWN
        private set
    val info = linkedMapOf<String, String>()
    /** DEVICE_ID of the connected FlySight (lowercase hex, as in TRACK.CSV), or null. */
    var deviceId: String? = null
        private set
    /**
     * The key of the default owner of the connected FlySight: its device ID, or its
     * BLE address until the app knows the ID. See [setDeviceId].
     */
    val ownerKey: String? get() = deviceId ?: connection?.device?.address?.let { OWNER_ADDRESS_KEY + it }
    var updated: LocalTime? = null
        private set
    val connected get() = state == FlySightConnection.State.READY && connection != null
    val rememberedName: String? get() = prefs.getString(PREF_NAME, null)?.takeIf { rememberedDevice() != null }

    lateinit var sync: JumpSync
        private set

    // ---- Views ----

    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var pushLayer: FrameLayout
    private lateinit var navBar: Ui.NavBar
    private lateinit var snackbars: Ui.SnackbarHost
    private var navInset = 0

    private lateinit var tabs: List<Screen>
    var currentTab = 0
        private set
    private val pushed = ArrayDeque<Screen>()

    val logLines = ArrayDeque<String>()

    // ---- Scan ----

    private class Found(val device: BluetoothDevice, val name: String, val rssi: Int, val pairingMode: Boolean) {
        val bonded get() = device.bondState == BluetoothDevice.BOND_BONDED
    }

    private var scanning = false
    private var scanDialog: Dialog? = null
    private var scanList: LinearLayout? = null
    private var scanProgress: View? = null
    private val found = linkedMapOf<String, Found>()
    private var bondingDevice: BluetoothDevice? = null
    /** What to do after the user gives the Bluetooth permissions. */
    private var afterPermissions: (() -> Unit)? = null
    private var autoConnecting = false

    private val backCallback = OnBackInvokedCallback { onBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrackStore.init(this)
        ui = Ui(this)
        ui.setUpWindow(this)
        sync = JumpSync(this)

        content = FrameLayout(this)
        navBar = ui.NavBar(listOf(
            R.drawable.ic_home to "Home",
            R.drawable.ic_jumps to "Jumps",
            R.drawable.ic_s_sensors to "Sensors",
            R.drawable.ic_tune to "Config",
            R.drawable.ic_folder to "Files",
            R.drawable.ic_bluetooth to "Device",
        )) { selectTab(it) }
        val mainLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(navBar, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        pushLayer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(ui.surface)
            isClickable = true
        }
        root = FrameLayout(this).apply {
            addView(mainLayer, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(pushLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        snackbars = ui.SnackbarHost(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            navInset = sys.bottom
            root.setPadding(sys.left, 0, sys.right, 0)
            pushLayer.setPadding(0, 0, 0, maxOf(sys.bottom, ime.bottom))
            insets
        }
        setContentView(root)

        tabs = listOf(HomeScreen(this), JumpsScreen(this), SensorsScreen(this), ConfigScreen(this), FilesScreen(this), DeviceScreen(this))
        for (t in tabs) {
            t.view.visibility = View.GONE
            content.addView(t.view, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        selectTab((savedInstanceState?.getInt(STATE_TAB) ?: intent.getIntExtra(EXTRA_TAB, 0)).coerceIn(tabs.indices))

        registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)

        autoConnect()
    }

    /** The receive screen opens a tab ([EXTRA_TAB]) in the running app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) selectTab(intent.getIntExtra(EXTRA_TAB, 0).coerceIn(tabs.indices))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, currentTab)
    }

    override fun onResume() {
        super.onResume()
        (pushed.lastOrNull() ?: tabs[currentTab]).onShow()
    }

    override fun onStop() {
        (pushed.lastOrNull() ?: tabs[currentTab]).onHide()
        super.onStop()
    }

    override fun onDestroy() {
        stopScan()
        sync.cancel()
        files?.reset()
        connection?.close()
        connection = null
        unregisterReceiver(bondReceiver)
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- Navigation ----

    fun selectTab(index: Int) {
        while (pushed.isNotEmpty()) popScreen(showNext = false)
        if (::tabs.isInitialized && index != currentTab) tabs[currentTab].onHide()
        currentTab = index
        for ((i, t) in tabs.withIndex()) t.view.visibility = if (i == index) View.VISIBLE else View.GONE
        navBar.select(index)
        snackbars.dismiss()
        tabs[index].onShow()
    }

    fun <T : Screen> tab(type: Class<T>): T = tabs.first { type.isInstance(it) }.let { type.cast(it)!! }

    /** Show a full screen on top of the tabs. */
    fun push(screen: Screen) {
        (pushed.lastOrNull() ?: tabs[currentTab]).onHide()
        pushed.addLast(screen)
        pushLayer.addView(screen.view, FrameLayout.LayoutParams(MATCH, MATCH))
        pushLayer.visibility = View.VISIBLE
        pushLayer.requestApplyInsets()
        snackbars.dismiss()
        screen.onShow()
    }

    fun popScreen(showNext: Boolean = true) {
        val screen = pushed.removeLastOrNull() ?: return
        screen.onHide()
        pushLayer.removeView(screen.view)
        if (pushed.isEmpty()) {
            pushLayer.visibility = View.GONE
            currentFocus?.let { v -> getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0) }
        }
        if (showNext) (pushed.lastOrNull() ?: tabs[currentTab]).onShow()
    }

    private fun onBack() {
        val top = pushed.lastOrNull()
        when {
            top != null -> if (!top.onBack()) popScreen()
            tabs[currentTab].onBack() -> Unit
            currentTab != 0 -> selectTab(0)
            else -> finish()
        }
    }

    // ---- Messages ----

    fun snack(message: String, actionLabel: String? = null, long: Boolean = false, action: (() -> Unit)? = null) {
        snackbars.bottomMarginPx = if (pushed.isNotEmpty()) pushLayer.paddingBottom else navBar.height
        snackbars.show(message, actionLabel, long, action)
    }

    fun confirm(title: String, message: String?, okLabel: String, action: () -> Unit) {
        ui.dialog(title, message, positive = Ui.DialogButton(okLabel, action)).show()
    }

    fun log(message: String) {
        val line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + " " + message
        logLines.addLast(line)
        while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        tabs.forEach { it.onLog(line) }
    }

    /** Tell all screens that the state changed. */
    fun onStateChanged() {
        if (!::tabs.isInitialized) return
        tabs.forEach { it.onStateChanged() }
        pushed.forEach { it.onStateChanged() }
    }

    /** The file transfer if the app can use it now. Otherwise show why not and return null. */
    fun idleFiles(): FileTransfer? {
        val f = files
        when {
            f == null || !connected -> snack("Connect to the FlySight first.", "Connect") { connectOrScan() }
            f.busy || sync.running -> snack("Wait for the current file operation to end.")
            else -> return f
        }
        return null
    }

    /**
     * A track from the connected FlySight gives its device ID. The FlySight cannot send
     * it over BLE: the ID is longer than the data in a control point response.
     */
    fun learnDeviceId(file: File) {
        if (deviceId != null || connection == null) return
        TrackStore.deviceId(file)?.let {
            setDeviceId(it)
            TrackStore.addDefaultOwner(file)
            onStateChanged()
        }
    }

    /** The default owner of the connected FlySight, or of the last FlySight if the app is not connected. */
    val deviceOwner: String?
        get() {
            val address = connection?.device?.address ?: prefs.getString(PREF_ADDRESS, null) ?: return null
            val id = deviceId ?: prefs.getString(PREF_DEVICE_ID + address, null)
            return id?.let { TrackStore.deviceOwner(it) } ?: TrackStore.deviceOwner(OWNER_ADDRESS_KEY + address)
        }

    /** "Anna's FlySight" if the FlySight has an owner, else [name]. */
    fun ownedName(name: String): String = deviceOwner?.let { "$it's $name" } ?: name

    /** The device ID of the connected FlySight. If the app does not know it, it uses the ID from the last connection. */
    fun findDeviceId(): String? {
        if (deviceId != null) return deviceId
        val address = connection?.device?.address ?: return null
        val id = prefs.getString(PREF_DEVICE_ID + address, null)
        if (id != null) setDeviceId(id)
        return id
    }

    /**
     * Find the device ID of the connected FlySight. If the app does not know it, it reads
     * FLYSIGHT.TXT on the FlySight. [done] gets null if the FlySight cannot send the file.
     */
    fun readDeviceId(done: (String?) -> Unit) {
        findDeviceId()?.let { return done(it) }
        val f = files
        if (f == null || f.busy || sync.running) return done(null)
        f.read("/FLYSIGHT.TXT", progress = {}) { r ->
            r.onSuccess { bytes ->
                val id = bytes.toString(Charsets.ISO_8859_1).lineSequence()
                    .firstNotNullOfOrNull { DEVICE_ID_LINE.matchEntire(it.trim())?.groupValues?.get(1) }
                if (id != null && connection != null && deviceId == null) setDeviceId(id.lowercase(Locale.US))
                if (id == null) log("Device ID: FLYSIGHT.TXT has no Device_ID")
            }.onFailure { log("Device ID: cannot read FLYSIGHT.TXT: ${it.message}") }
            done(deviceId)
        }
    }

    /** Keep the device ID for the next connection, and move an owner that the user set before the app knew the ID. */
    private fun setDeviceId(id: String) {
        deviceId = id
        val address = connection?.device?.address ?: return
        prefs.edit().putString(PREF_DEVICE_ID + address, id).apply()
        val key = OWNER_ADDRESS_KEY + address
        TrackStore.deviceOwner(key)?.let { owner ->
            if (TrackStore.deviceOwner(id) == null) TrackStore.setDeviceOwner(id, owner)
            TrackStore.setDeviceOwner(key, null)
        }
    }

    /** Text for a failed file operation, with a hint if the mode is not idle. */
    fun fileError(action: String, error: Throwable): String {
        log("FT: $action failed: ${error.message}")
        return if (mode != DeviceMode.SLEEP && mode != DeviceMode.UNKNOWN) {
            "$action failed. The FlySight is in ${mode.label.lowercase(Locale.US)} mode. Files are available in idle mode only."
        } else {
            "$action failed: ${error.message}"
        }
    }

    // ---- Activity results ----

    @Deprecated("Activity result API of the platform")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        (tabs + pushed).forEach { it.onActivityResult(requestCode, resultCode, data) }
    }

    /** Read a file from the phone. Returns null and shows a message if it is too large. */
    fun readUri(uri: Uri, maxSize: Int = MAX_UPLOAD_SIZE): ByteArray? {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > maxSize) {
            snack("The file is too large. The maximum is ${FileInfo.formatSize(maxSize.toLong())}.")
            return null
        }
        return bytes
    }

    fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ---- Permissions and Bluetooth state ----

    private fun hasPermissions() = REQUIRED_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Run [action] when Bluetooth is on and the app has the permissions. */
    private fun withBluetooth(action: () -> Unit) {
        val adapter = adapter ?: return snack("This phone has no Bluetooth.")
        if (!hasPermissions()) {
            afterPermissions = action
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
            return
        }
        if (!adapter.isEnabled) {
            snack("Turn on Bluetooth, then try again.", long = true)
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        action()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        val action = afterPermissions
        afterPermissions = null
        if (hasPermissions()) action?.invoke()
        else snack("The app needs the Nearby devices permission to find the FlySight.", long = true)
    }

    private fun rememberedDevice(): BluetoothDevice? {
        if (!hasPermissions()) return null
        val address = prefs.getString(PREF_ADDRESS, null) ?: return null
        return adapter?.bondedDevices?.firstOrNull { it.address == address }
    }

    /** At start, connect to the last FlySight if it is paired. */
    private fun autoConnect() {
        val adapter = adapter ?: return
        if (!hasPermissions() || !adapter.isEnabled) return
        val device = rememberedDevice() ?: return
        autoConnecting = true
        connect(device, prefs.getString(PREF_NAME, null) ?: "FlySight")
    }

    /** Connect to the last FlySight, or find one. */
    fun connectOrScan() = withBluetooth {
        if (connection != null) return@withBluetooth
        val device = rememberedDevice()
        if (device != null) connect(device, prefs.getString(PREF_NAME, null) ?: "FlySight") else showScanDialog()
    }

    fun findDevice() = withBluetooth { showScanDialog() }

    // ---- Scan ----

    private fun showScanDialog() {
        val list = ui.column()
        val progress = ui.linearProgress().apply { isIndeterminate = true }
        val body = ui.column().apply {
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)).apply {
                setMargins(ui.dp(24), 0, ui.dp(24), ui.dp(8))
            })
            addView(list)
            addView(ui.text(Ui.Type.BODY_SMALL,
                "To pair a new phone, push the FlySight button two times while the LED is off. The green LED pulses.",
                ui.onSurfaceVariant).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0) })
        }
        val dialog = ui.dialog("Connect to a FlySight", content = body, negative = Ui.DialogButton("Cancel"),
            neutral = Ui.DialogButton("Scan again") { handler.post { showScanDialog() } }, scrollContent = true)
        dialog.setOnDismissListener {
            if (scanDialog === dialog) {
                stopScan()
                scanDialog = null
                scanList = null
                scanProgress = null
            }
        }
        scanDialog?.dismiss()
        scanDialog = dialog
        scanList = list
        scanProgress = progress
        found.clear()
        // Show the last FlySight at once. The scan adds the others.
        rememberedDevice()?.let { found[it.address] = Found(it, prefs.getString(PREF_NAME, null) ?: "FlySight", 0, false) }
        updateScanList()
        dialog.show()
        startScan()
    }

    private fun updateScanList() {
        val list = scanList ?: return
        list.removeAllViews()
        val items = found.values.sortedWith(compareByDescending<Found> { it.bonded }.thenByDescending { it.rssi })
        if (items.isEmpty()) {
            list.addView(ui.text(Ui.Type.BODY_MEDIUM, if (scanning) "Looking for FlySight devices near the phone…" else "No FlySight found. Make sure that it is on and near the phone.",
                ui.onSurfaceVariant).apply { setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8)) })
        }
        for (item in items) {
            val tags = buildList {
                add(if (item.bonded) "Paired" else if (item.pairingMode) "Ready to pair" else "Not paired")
                if (item.rssi != 0) add("${item.rssi} dBm")
            }
            list.addView(ui.ListItem(item.name, tags.joinToString(" · "), ui.avatar(R.drawable.ic_bluetooth)).apply {
                setPadding(ui.dp(24), paddingTop, ui.dp(24), paddingBottom)
            }.onClick {
                scanDialog?.dismiss()
                onDeviceSelected(item)
            })
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val mfg = record?.getManufacturerSpecificData(P.MANUFACTURER_ID)
            found[result.device.address] = Found(
                device = result.device,
                name = record?.deviceName ?: result.device.name ?: "FlySight",
                rssi = result.rssi,
                // The first byte after the company ID is the pairing flag
                pairingMode = mfg != null && mfg.isNotEmpty() && mfg[0].toInt() != 0,
            )
            updateScanList()
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            scanProgress?.visibility = View.INVISIBLE
            log("Scan failed (error $errorCode)")
            updateScanList()
        }
    }

    private val scanTimeout = Runnable { stopScan() }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (scanning) stopScan()
        val filter = ScanFilter.Builder().setManufacturerData(P.MANUFACTURER_ID, ByteArray(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        scanProgress?.visibility = View.VISIBLE
        handler.postDelayed(scanTimeout, SCAN_TIME_MS)
        updateScanList()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        if (hasPermissions()) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanProgress?.visibility = View.INVISIBLE
        updateScanList()
    }

    // ---- Pairing and connection ----

    private fun onDeviceSelected(item: Found) {
        stopScan()
        when {
            item.bonded -> connect(item.device, item.name)
            item.pairingMode -> bond(item.device)
            else -> ui.dialog(
                "Pair this phone first",
                "The FlySight accepts connections only from paired phones.\n\n" +
                    "Push the FlySight button two times quickly. The green LED pulses. Then try again.",
                positive = Ui.DialogButton("Try again") { findDevice() },
            ).show()
        }
    }

    private fun bond(device: BluetoothDevice) {
        bondingDevice = device
        snack("Pairing…")
        if (!device.createBond()) {
            bondingDevice = null
            snack("Pairing could not start.")
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val target = bondingDevice ?: return
            if (device.address != target.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                BluetoothDevice.BOND_BONDED -> {
                    bondingDevice = null
                    snack("Paired.")
                    // The FlySight starts to advertise again after the pairing link closes
                    handler.postDelayed({ connect(device, device.name ?: "FlySight") }, 1000)
                }
                BluetoothDevice.BOND_NONE -> {
                    bondingDevice = null
                    snack("Pairing failed. Put the FlySight in pairing mode and try again.", long = true)
                }
            }
        }
    }

    private fun connect(device: BluetoothDevice, name: String) {
        if (!hasPermissions()) return
        files?.reset()
        connection?.close()
        clearDeviceData()
        deviceName = name
        val c = FlySightConnection(this, device, this)
        connection = c
        files = FileTransfer(c) { log(it) }
        log("Connect to ${device.address}")
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        c.connect()
    }

    /** If the FlySight is off or far away, the connection does not start. */
    private val connectTimeout = Runnable {
        if (state == FlySightConnection.State.READY) return@Runnable
        val wasAuto = autoConnecting
        log("Connection timeout")
        closeConnection()
        if (!wasAuto) snack("The FlySight did not answer. Make sure that it is on and near the phone.", "Find", long = true) { findDevice() }
    }

    fun disconnect() {
        log("Disconnected by the app")
        closeConnection()
    }

    private fun closeConnection() {
        idleWaiters.clear()
        handler.removeCallbacks(keepAlive)
        handler.removeCallbacks(connectTimeout)
        sync.cancel()
        files?.reset()
        files = null
        connection?.close()
        connection = null
        autoConnecting = false
        state = FlySightConnection.State.DISCONNECTED
        onStateChanged()
    }

    /**
     * The FlySight disconnects after 30 s without a write to FT_Packet_In,
     * so send a ping. A file transfer also resets the timeout, so do not
     * add pings to it. The battery level has notifications only in active
     * mode, so read it.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            val c = connection ?: return
            val f = files
            if (f != null && !f.busy) f.ping()
            c.read(P.BATTERY_SERVICE, P.BATTERY_LEVEL)
            handler.postDelayed(this, KEEP_ALIVE_MS)
        }
    }

    private fun onReady() {
        val c = connection ?: return
        handler.removeCallbacks(connectTimeout)
        autoConnecting = false
        prefs.edit().putString(PREF_ADDRESS, c.device.address).putString(PREF_NAME, deviceName).apply()
        c.read(P.DEVICE_INFORMATION_SERVICE, P.MANUFACTURER_NAME)
        c.read(P.DEVICE_INFORMATION_SERVICE, P.MODEL_NUMBER)
        c.read(P.DEVICE_INFORMATION_SERVICE, P.FIRMWARE_REVISION)
        c.read(P.DEVICE_STATE_SERVICE, P.DS_MODE)
        c.read(P.BATTERY_SERVICE, P.BATTERY_LEVEL)
        c.read(P.STARTER_PISTOL_SERVICE, P.SP_RESULT)
        c.setNotifications(P.FILE_TRANSFER_SERVICE, P.FT_PACKET_OUT, true)
        c.setNotifications(P.DEVICE_STATE_SERVICE, P.DS_MODE, true)
        c.setNotifications(P.DEVICE_STATE_SERVICE, P.DS_CONTROL_POINT, true)
        c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_CONTROL_POINT, true)
        c.setNotifications(P.STARTER_PISTOL_SERVICE, P.SP_CONTROL_POINT, true)
        c.setNotifications(P.STARTER_PISTOL_SERVICE, P.SP_RESULT, true)
        c.setNotifications(P.BATTERY_SERVICE, P.BATTERY_LEVEL, true)
        // The device ID identifies the jumps from this FlySight, for the owner name
        sendDs(P.DS_CMD_GET_DEVICE_ID)
        findDeviceId()
        handler.removeCallbacks(keepAlive)
        handler.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    private fun clearDeviceData() {
        battery = null
        mode = DeviceMode.UNKNOWN
        sync.forget()
        info.clear()
        deviceId = null
        updated = null
    }

    /** Send a command to a control point. */
    fun sendControl(service: UUID, characteristic: UUID, tag: String, names: Map<Int, String>, opcode: Int, vararg params: Int) {
        val c = connection ?: return snack("Connect to the FlySight first.")
        val packet = byteArrayOf(opcode.toByte()) + params.map { it.toByte() }.toByteArray()
        log("$tag → ${names[opcode] ?: opcode} [${packet.toHex()}]")
        c.write(service, characteristic, packet)
    }

    fun sendDs(opcode: Int, vararg params: Int) =
        sendControl(P.DEVICE_STATE_SERVICE, P.DS_CONTROL_POINT, "DS", P.DS_COMMAND_NAMES, opcode, *params)

    // ---- Mode changes ----

    /** Actions that wait for idle mode. */
    private val idleWaiters = mutableListOf<() -> Unit>()

    private val idleTimeout = Runnable {
        if (idleWaiters.isEmpty()) return@Runnable
        idleWaiters.clear()
        snack("The FlySight did not go to idle mode.")
    }

    /**
     * Run [action] when the FlySight can use its files: in idle or sensor mode.
     * In other modes, request idle mode first. The firmware ends the session.
     */
    fun whenFilesFree(action: () -> Unit) {
        if (mode == DeviceMode.SLEEP || mode == DeviceMode.SENSOR || mode == DeviceMode.UNKNOWN) return action()
        idleWaiters += action
        sendDs(P.DS_CMD_REQUEST_SLEEP)
        handler.removeCallbacks(idleTimeout)
        handler.postDelayed(idleTimeout, MODE_TIMEOUT_MS)
    }

    private fun runIdleWaiters() {
        if (idleWaiters.isEmpty()) return
        handler.removeCallbacks(idleTimeout)
        val actions = idleWaiters.toList()
        idleWaiters.clear()
        actions.forEach { it() }
    }

    fun readStatus() {
        val c = connection ?: return
        c.read(P.BATTERY_SERVICE, P.BATTERY_LEVEL)
        c.read(P.DEVICE_STATE_SERVICE, P.DS_MODE)
        c.read(P.DEVICE_INFORMATION_SERVICE, P.FIRMWARE_REVISION)
    }

    // ---- FlySightConnection.Listener ----

    override fun onStateChanged(state: FlySightConnection.State, message: String?) {
        log("State: $state" + (message?.let { " ($it)" } ?: ""))
        this.state = state
        when (state) {
            FlySightConnection.State.READY -> {
                onReady()
                if (message != null) snack(message, long = true)
            }
            FlySightConnection.State.DISCONNECTED -> {
                idleWaiters.clear()
                val wasAuto = autoConnecting
                handler.removeCallbacks(keepAlive)
                handler.removeCallbacks(connectTimeout)
                files?.reset()
                files = null
                connection = null
                autoConnecting = false
                if (!wasAuto) snack(if (message != null) "The connection closed." else "Disconnected.", "Connect") { connectOrScan() }
            }
            else -> Unit
        }
        onStateChanged()
    }

    override fun onLog(message: String) = log(message)

    override fun onValue(characteristic: UUID, value: ByteArray) {
        if (characteristic in STREAMS) {
            // Live data comes many times each second. It does not change the state.
            (tabs + pushed).forEach { it.onValue(characteristic, value) }
            return
        }
        when (characteristic) {
            P.FT_PACKET_OUT -> return files?.onPacketOut(value) ?: Unit
            P.BATTERY_LEVEL -> value.firstOrNull()?.let { battery = it.toInt() and 0xff }
            P.DS_MODE -> value.firstOrNull()?.let {
                val newMode = DeviceMode.from(it.toInt() and 0xff)
                if (newMode != mode) log("Mode: ${newMode.label}")
                mode = newMode
                if (mode == DeviceMode.SLEEP) handler.post { runIdleWaiters() }
            }
            P.FIRMWARE_REVISION -> info[INFO_FIRMWARE] = value.asText()
            P.MANUFACTURER_NAME -> info[INFO_MANUFACTURER] = value.asText()
            P.MODEL_NUMBER -> info[INFO_MODEL] = value.asText()
            P.DS_CONTROL_POINT -> CpResponse.parse(value)?.let { r ->
                if (r.opcode == P.DS_CMD_GET_DEVICE_ID && r.success && r.data.isNotEmpty()) setDeviceId(r.dataText.trim().lowercase(Locale.US))
            }
        }
        updated = LocalTime.now()
        (tabs + pushed).forEach { it.onValue(characteristic, value) }
        onStateChanged()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        const val REQUEST_UPLOAD = 10
        const val REQUEST_SAVE = 11
        const val REQUEST_FIRMWARE = 12
        const val REQUEST_REPLACE = 13
        const val REQUEST_UPLOAD_FOLDER = 14
        const val REQUEST_IMPORT = 20
        const val REQUEST_TARGET = 30

        private const val SCAN_TIME_MS = 15_000L
        private const val KEEP_ALIVE_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val MODE_TIMEOUT_MS = 10_000L
        const val MAX_UPLOAD_SIZE = 4 * 1024 * 1024
        private const val MAX_LOG_LINES = 1000
        private const val STATE_TAB = "tab"
        const val EXTRA_TAB = "tab"
        private const val PREF_ADDRESS = "lastAddress"
        private const val PREF_DEVICE_ID = "deviceId:"
        private const val OWNER_ADDRESS_KEY = "address:"
        private val DEVICE_ID_LINE = Regex("""Device_ID:\s*([0-9a-fA-F]{24})""")
        private const val PREF_NAME = "lastName"

        const val INFO_FIRMWARE = "Firmware"
        const val INFO_MANUFACTURER = "Manufacturer"
        const val INFO_MODEL = "Model"

        const val TAB_HOME = 0
        const val TAB_JUMPS = 1
        const val TAB_SENSORS = 2
        const val TAB_CONFIG = 3
        const val TAB_FILES = 4
        const val TAB_DEVICE = 5

        private val STREAMS = setOf(P.SD_GNSS_MEASUREMENT, P.SD_SENSOR_MEASUREMENT)

        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        fun ByteArray.asText() = toString(Charsets.ISO_8859_1).trimEnd('\u0000')

        fun ByteArray.toHex() = joinToString(" ") { String.format(Locale.US, "%02X", it) }
    }
}
