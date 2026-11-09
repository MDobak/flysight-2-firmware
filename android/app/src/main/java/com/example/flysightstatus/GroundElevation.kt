package com.example.flysightstatus

import android.app.Dialog
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import com.example.flysightstatus.FlySightProtocol as P

/**
 * Show and set the ground level of the FlySight: the GPS ground level (above
 * sea level) and the ground pressure (QFE). Each value has a field. The user
 * can type a value, or fill the field from the GPS or the barometer. A caption
 * shows the value that is stored in the FlySight now.
 *
 * In idle mode, the dialog starts sensor mode for the sensor data, and it sets
 * idle mode again when it closes.
 *
 * "Set" sends DS_CMD_SET_ALT_ZERO with the changed values. RAM keeps them until
 * a reset. A new GPS ground level is also written as DZ_Elev to CONFIG.TXT and
 * to each profile that has DZ_Elev (a profile value replaces the value in
 * CONFIG.TXT). Thus the GPS ground level stays after a reset.
 */
@android.annotation.SuppressLint("SetTextI18n")
class GroundElevation(private val main: MainActivity, private val onClosed: () -> Unit) {

    private val ui = main.ui
    /** Accurate GPS altitudes in sequence. */
    private val gpsSamples = ArrayDeque<Double>()
    private val baroSamples = ArrayDeque<Double>()
    private var numSv: Int? = null
    private var vAcc: Double? = null
    private var hMsl: Double? = null
    private var pressure: Double? = null
    private var zero: AltZero? = null
    /** Null until the firmware answers. False for firmware without the command. */
    private var zeroSupported: Boolean? = null
    private var message: String? = null
    private var messageIsError = false
    /** The field texts of the stored values. A field with a different text is changed. */
    private var gpsStoredText: String? = null
    private var baroStoredText: String? = null
    private var sending = false
    private var startedSensorMode = false
    private var saving = false
    private var closed = false

    private val gpsField = ui.textField("", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED).apply {
        fontFeatureSettings = "tnum"
    }
    private val baroField = ui.textField("", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
        fontFeatureSettings = "tnum"
    }
    private val gpsStored = caption()
    private val baroStored = caption()
    private val useGps = ui.button(Ui.ButtonStyle.TEXT, "Use GPS", R.drawable.ic_s_gnss) { fillGps() }
    private val useBaro = ui.button(Ui.ButtonStyle.TEXT, "Use barometer", R.drawable.ic_s_sensors) { fillBaro() }
    private val heightText = ui.text(Ui.Type.BODY_MEDIUM, "").apply { fontFeatureSettings = "tnum" }
    private val sensors = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant).apply { fontFeatureSettings = "tnum" }
    private val messageText = ui.text(Ui.Type.BODY_MEDIUM, "", ui.primary)
    private val progress = ui.linearProgress().apply { isIndeterminate = true; visibility = View.INVISIBLE }
    private val setButton = ui.button(Ui.ButtonStyle.TEXT, "Set") { set() }
    private val dialog: Dialog

    init {
        val actions = LinearLayout(main).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(ui.button(Ui.ButtonStyle.TEXT, "Close") { dialog.dismiss() })
            addView(setButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(8) })
        }
        val content = ui.column().apply {
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
            addView(section("GPS ground level", useGps, gpsField, "m", gpsStored))
            addView(section("Ground pressure (QFE)", useBaro, baroField, "hPa", baroStored),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
            addView(heightText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
            addView(sensors, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(4) })
            addView(messageText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)).apply { topMargin = ui.dp(8) })
            addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(4); marginEnd = -ui.dp(8) })
        }
        dialog = ui.dialog("Ground level",
            "Alarms, silence windows, and altitude calls are heights above the ground level. " +
                "A new GPS ground level is also saved as the Ground elevation.",
            content, negative = null, scrollContent = true)
        dialog.setOnDismissListener { close() }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = update()
        }
        gpsField.addTextChangedListener(watcher)
        baroField.addTextChangedListener(watcher)
    }

    private fun caption() = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant).apply {
        setPadding(ui.dp(16), ui.dp(4), 0, 0)
        fontFeatureSettings = "tnum"
    }

    /** Label and fill button, then the field with its unit, then the stored value. */
    private fun section(label: String, fill: View, field: View, unit: String, stored: View) = ui.column().apply {
        addView(LinearLayout(main).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(Ui.Type.TITLE_SMALL, label), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(fill, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = -ui.dp(12) })
        })
        addView(LinearLayout(main).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.text(Ui.Type.BODY_LARGE, unit, ui.onSurfaceVariant).apply {
                minWidth = ui.dp(40)
                setPadding(ui.dp(12), 0, 0, 0)
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(4) })
        addView(stored)
    }

    fun show() {
        val c = main.connection
        if (!main.connected || c == null) return main.snack("Connect to the FlySight first.")
        val sensorData = c.hasCharacteristic(P.SENSOR_DATA_SERVICE, P.SD_SENSOR_MEASUREMENT)
        when (main.mode) {
            DeviceMode.SLEEP -> {
                if (!sensorData) {
                    return main.snack("This firmware cannot start the GNSS in idle mode. Install the firmware with sensor mode.", long = true)
                }
                startedSensorMode = true
                main.sendDs(P.DS_CMD_REQUEST_SENSOR)
            }
            DeviceMode.ACTIVE, DeviceMode.START, DeviceMode.SENSOR -> Unit
            else -> return main.snack("Set the FlySight to idle mode first.")
        }
        c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_GNSS_MEASUREMENT, true)
        if (sensorData) c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_SENSOR_MEASUREMENT, true)
        main.sendControl(P.SENSOR_DATA_SERVICE, P.SD_CONTROL_POINT, "SD", P.SD_COMMAND_NAMES,
            P.SD_CMD_SET_MASK, P.GNSS_SENSORS_MASK)
        main.sendDs(P.DS_CMD_GET_ALT_ZERO)
        update()
        dialog.show()
    }

    fun dismiss() = dialog.dismiss()

    fun onValue(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            P.SD_GNSS_MEASUREMENT -> onGnss(value)
            P.SD_SENSOR_MEASUREMENT -> SensorMeasurement.parse(value)?.pressurePa?.let { onPressure(it) }
            P.DS_CONTROL_POINT -> onResponse(value)
        }
    }

    private fun onGnss(value: ByteArray) {
        val m = GnssMeasurement.parse(value) ?: return
        numSv = m.numSv
        vAcc = m.vAccM
        val h = m.hMslM
        // The same test as the firmware: a 3D fix and a vertical accuracy better than 10 m
        hMsl = if (h != null && m.vAccM != null && m.vAccM < MAX_V_ACC_M) h else null
        if (hMsl != null) {
            gpsSamples.addLast(h!!)
            while (gpsSamples.size > SAMPLES) gpsSamples.removeFirst()
        } else {
            gpsSamples.clear()
        }
        update()
    }

    private fun onPressure(pa: Double) {
        pressure = pa
        baroSamples.addLast(pa)
        while (baroSamples.size > BARO_SAMPLES) baroSamples.removeFirst()
        update()
    }

    private fun onResponse(value: ByteArray) {
        val r = CpResponse.parse(value) ?: return
        if (r.opcode != P.DS_CMD_GET_ALT_ZERO && r.opcode != P.DS_CMD_SET_ALT_ZERO) return
        val wasSending = sending && r.opcode == P.DS_CMD_SET_ALT_ZERO
        if (wasSending) sending = false
        if (!r.success) {
            if (r.opcode == P.DS_CMD_GET_ALT_ZERO && r.status == CP_NOT_SUPPORTED) zeroSupported = false
            else showMessage("The FlySight did not set the ground level: ${r.statusText}. Install the new firmware.", true)
            return update()
        }
        val z = AltZero.parse(r.data) ?: return
        zero = z
        zeroSupported = true
        if (r.opcode == P.DS_CMD_GET_ALT_ZERO && gpsStoredText == null) {
            fillStored(z)
        } else if (wasSending) {
            fillStored(z)
            if (z.set and AltZero.GNSS != 0) {
                val elevation = z.groundHmslM.roundToInt()
                saving = true
                main.whenFilesFree { writeAll(elevation) }
            } else if (z.set and AltZero.BARO != 0) {
                showMessage("Ground pressure set.")
            } else {
                showMessage("The FlySight did not set the ground level.", true)
            }
        }
        update()
    }

    /** Put the stored values in the fields. */
    private fun fillStored(z: AltZero) {
        val gps = if (z.hasGnss) z.groundHmslM else z.dzElevM
        gpsStoredText = gps?.roundToInt()?.toString() ?: ""
        baroStoredText = if (z.hasBaro) String.format(Locale.US, "%.2f", z.groundPressurePa / 100) else ""
        gpsField.setText(gpsStoredText)
        baroField.setText(baroStoredText)
    }

    fun onStateChanged() {
        if (!main.connected) dialog.dismiss() else update()
    }

    private fun showMessage(text: String?, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    private fun update() {
        val z = zero
        val unknown = if (zeroSupported == false) "This firmware cannot set the ground level. Install the new firmware." else "Reading…"
        val how = if (z?.automatic == true) "set automatically" else "set from the app"
        gpsStored.text = when {
            z == null -> unknown
            z.hasGnss -> "Stored: ${TrackStore.formatInt(z.groundHmslM)} m, $how"
            z.dzElevM != null -> "Stored: ${TrackStore.formatInt(z.dzElevM)} m, from the Ground elevation"
            else -> "Stored: the Ground elevation"
        }
        baroStored.text = when {
            z == null -> unknown
            z.hasBaro -> String.format(Locale.US, "Stored: %.2f hPa, %s", z.groundPressurePa / 100, how)
            else -> "Stored: not set"
        }

        // Height above the stored ground level, the same as the firmware calculates it
        val p = pressure
        val h = hMsl
        val ground = z?.let { if (it.hasGnss) it.groundHmslM else it.dzElevM }
        heightText.text = when {
            z == null -> ""
            z.altSource == 1 && z.hasBaro && p != null -> String.format(Locale.US, "Height now: %+.1f m, from the barometer",
                AltZero.pressureAltitude(p) - AltZero.pressureAltitude(z.groundPressurePa))
            z.altSource == 1 && !z.hasBaro -> "The altitude source is the barometer, but the ground pressure is not set. The FlySight uses the GPS."
            ground != null && h != null -> String.format(Locale.US, "Height now: %+.0f m, from the GPS", h - ground)
            else -> "Height now: no GPS fix"
        }
        heightText.visibility = if (heightText.text.isEmpty()) View.GONE else View.VISIBLE

        val sv = numSv?.let { "$it satellites" } ?: "no satellites yet"
        val gps = if (h != null) String.format(Locale.US, "GPS now %s m, ±%.0f m, %s",
            TrackStore.formatInt(h), vAcc ?: 0.0, sv) else "GPS now: no accurate fix, $sv"
        val baro = if (p != null) String.format(Locale.US, "Barometer now %.2f hPa", p / 100) else "Barometer now: no data"
        sensors.text = "$gps\n$baro"

        messageText.text = if (saving) "Saving the Ground elevation…" else message ?: ""
        messageText.setTextColor(if (messageIsError && !saving) ui.error else ui.primary)
        messageText.visibility = if (messageText.text.isEmpty()) View.GONE else View.VISIBLE
        progress.visibility = if (saving || sending) View.VISIBLE else View.INVISIBLE

        val idle = !saving && !sending
        enable(useGps, idle && gpsSamples.size >= MIN_SAMPLES)
        enable(useBaro, idle && baroSamples.isNotEmpty())
        enable(setButton, idle && zeroSupported == true && changed() != 0)
        gpsField.isEnabled = idle
        baroField.isEnabled = idle
    }

    private fun enable(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.38f
    }

    // ---- Ground level in the FlySight ----

    private fun fillGps() {
        if (gpsSamples.size < MIN_SAMPLES) return
        gpsField.setText(gpsSamples.average().roundToInt().toString())
    }

    private fun fillBaro() {
        if (baroSamples.isEmpty()) return
        baroField.setText(String.format(Locale.US, "%.2f", baroSamples.average() / 100))
    }

    /** The parts with a changed field text. */
    private fun changed(): Int {
        var parts = 0
        if (gpsStoredText != null && gpsField.text.toString().trim() != gpsStoredText) parts = parts or AltZero.GNSS
        if (baroStoredText != null && baroField.text.toString().trim() != baroStoredText) parts = parts or AltZero.BARO
        return parts
    }

    private fun set() {
        val parts = changed()
        if (parts == 0) return
        var hmslMm = 0
        var pressure = 0
        if (parts and AltZero.GNSS != 0) {
            val m = gpsField.text.toString().trim().toIntOrNull()
            if (m == null || m !in -500..9000) {
                showMessage("Type a GPS ground level from -500 to 9000 m.", true)
                return update()
            }
            hmslMm = m * 1000
        }
        if (parts and AltZero.BARO != 0) {
            val hPa = baroField.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (hPa == null || hPa < 300 || hPa > 1100) {
                showMessage("Type a ground pressure from 300 to 1100 hPa.", true)
                return update()
            }
            pressure = (hPa * 10000).roundToInt()
        }
        showMessage(null)
        sending = true
        main.sendDs(P.DS_CMD_SET_ALT_ZERO, parts, *le32(hmslMm), *le32(pressure))
        update()
    }

    private fun le32(v: Int) = intArrayOf(v and 0xff, (v shr 8) and 0xff, (v shr 16) and 0xff, (v ushr 24) and 0xff)

    // ---- DZ_Elev in the files ----

    private fun writeAll(elevation: Int) {
        val f = main.idleFiles() ?: return fail(null)
        setIn(f, "/CONFIG.TXT", elevation, always = true) { old ->
            f.list("/CONFIG") { result ->
                val profiles = result.getOrNull().orEmpty()
                    .filter { !it.isDirectory && !it.isHidden && it.name.endsWith(".txt", ignoreCase = true) }
                    .map { "/CONFIG/${it.name}" }
                val changed = mutableListOf<String>()
                fun next(i: Int) {
                    if (i >= profiles.size) return done(elevation, old, changed)
                    setIn(f, profiles[i], elevation, always = false) { previous ->
                        if (previous != NOT_SET) changed += profiles[i].substringAfterLast('/').substringBeforeLast('.')
                        next(i + 1)
                    }
                }
                next(0)
            }
        }
    }

    /**
     * Set DZ_Elev in the file at [path]. Without [always], change only a file that
     * has DZ_Elev. [then] gets the old value, or [NOT_SET].
     */
    private fun setIn(f: FileTransfer, path: String, elevation: Int, always: Boolean, then: (Int?) -> Unit) {
        f.read(path, progress = {}) { read ->
            val data = read.getOrElse { return@read if (always) fail(main.fileError("Read $path", it)) else then(NOT_SET) }
            val file = ConfigFile(data.toString(Charsets.ISO_8859_1))
            if (!always && !file.has("DZ_Elev")) return@read then(NOT_SET)
            val old = file.getInt("DZ_Elev")
            if (old == elevation && file.has("DZ_Elev")) return@read then(old)
            file.set("DZ_Elev", elevation.toString())
            f.write(path, file.text().toByteArray(Charsets.ISO_8859_1), progress = { _, _ -> }) { written ->
                written.onSuccess {
                    main.log("FT: DZ_Elev $elevation in $path")
                    then(old)
                }.onFailure { fail(main.fileError("Save $path", it)) }
            }
        }
    }

    private fun done(elevation: Int, old: Int?, profiles: List<String>) {
        saving = false
        val where = if (profiles.isEmpty()) "CONFIG.TXT" else "CONFIG.TXT and ${profiles.joinToString(", ")}"
        val was = if (old != null && old != elevation) " (was ${TrackStore.formatInt(old.toDouble())} m)" else ""
        showMessage("Ground level set. Ground elevation is ${TrackStore.formatInt(elevation.toDouble())} m$was in $where.")
        update()
    }

    private fun fail(error: String?) {
        saving = false
        showMessage(error ?: "The FlySight is busy. The Ground elevation is not saved.", true)
        update()
    }

    private fun close() {
        if (closed) return
        closed = true
        val c = main.connection
        if (c != null && main.connected) {
            if (!main.tab(DeviceScreen::class.java).gnssLive) c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_GNSS_MEASUREMENT, false)
            if (c.hasCharacteristic(P.SENSOR_DATA_SERVICE, P.SD_SENSOR_MEASUREMENT)) {
                c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_SENSOR_MEASUREMENT, false)
            }
            if (startedSensorMode && main.mode == DeviceMode.SENSOR) main.sendDs(P.DS_CMD_REQUEST_SLEEP)
        }
        onClosed()
    }

    private companion object {
        /** 5 s at the default GNSS rate of 5 measurements each second. */
        const val SAMPLES = 25
        const val MIN_SAMPLES = 10
        const val MAX_V_ACC_M = 10.0
        const val BARO_SAMPLES = 10
        const val NOT_SET = Int.MIN_VALUE
        const val CP_NOT_SUPPORTED = 0x02
    }
}
