package com.example.flysightstatus

import android.app.Dialog
import android.view.View
import android.widget.LinearLayout
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import com.example.flysightstatus.FlySightProtocol as P

/**
 * Set the ground level of the FlySight. The FlySight must be on the ground at
 * the landing area. In idle mode, the dialog starts sensor mode for the sensor
 * data, and it sets idle mode again when it closes.
 *
 * "Set ground level here" sends DS_CMD_SET_ALT_ZERO. The firmware keeps this
 * zero in RAM until a reset, and it then ignores DZ_Elev.
 *
 * "Save as Ground elevation" writes the GPS altitude to CONFIG.TXT and to each profile
 * that has DZ_Elev, because a profile value replaces the value in CONFIG.TXT.
 */
@android.annotation.SuppressLint("SetTextI18n")
class GroundElevation(private val main: MainActivity, private val onClosed: () -> Unit) {

    private val ui = main.ui
    private val samples = ArrayDeque<Double>()
    private var numSv: Int? = null
    private var vAcc: Double? = null
    private var hMsl: Double? = null
    private var pressure: Double? = null
    private var zero: AltZero? = null
    /** Null until the firmware answers. False for firmware without the command. */
    private var zeroSupported: Boolean? = null
    private var zeroMessage: String? = null
    private var startedSensorMode = false
    private var saving = false
    private var closed = false

    private val zeroText = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant)
    private val heightText = ui.text(Ui.Type.DISPLAY_SMALL, "–").apply { fontFeatureSettings = "tnum" }
    private val heightCaption = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant)
    private val sensors = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant).apply { fontFeatureSettings = "tnum" }
    private val message = ui.text(Ui.Type.BODY_MEDIUM, "", ui.primary)
    private val progress = ui.linearProgress().apply { isIndeterminate = true; visibility = View.INVISIBLE }
    private val zeroButton = ui.button(Ui.ButtonStyle.FILLED, "Set ground level here") { setZero() }
    private val saveButton = ui.button(Ui.ButtonStyle.TEXT, "Save GPS altitude as Ground elevation") { save() }
    private val dialog: Dialog

    init {
        val content = ui.column().apply {
            setPadding(ui.dp(24), 0, ui.dp(24), 0)
            addView(ui.text(Ui.Type.TITLE_SMALL, "Ground level now"))
            addView(zeroText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(2) })
            addView(heightText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(16) })
            addView(heightCaption)
            addView(sensors, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })
            addView(message, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)).apply { topMargin = ui.dp(12) })
            addView(zeroButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })
            addView(saveButton, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        dialog = ui.dialog("Ground level",
            "Put the FlySight on the ground at the landing area. Alarms, silence windows, and altitude calls " +
                "are heights above the ground level.",
            content, negative = Ui.DialogButton("Close"))
        dialog.setOnDismissListener { close() }
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
            P.SD_SENSOR_MEASUREMENT -> SensorMeasurement.parse(value)?.pressurePa?.let { pressure = it; update() }
            P.DS_CONTROL_POINT -> onResponse(value)
        }
    }

    private fun onGnss(value: ByteArray) {
        if (saving) return
        val m = GnssMeasurement.parse(value) ?: return
        numSv = m.numSv
        vAcc = m.vAccM
        val h = m.hMslM
        hMsl = if (h != null && m.vAccM != null && m.vAccM < MAX_V_ACC_M) h else null
        val good = h != null && m.hAccM != null && m.hAccM < MAX_H_ACC_M && m.vAccM != null && m.vAccM < MAX_V_ACC_M
        if (good) {
            samples.addLast(h!!)
            while (samples.size > SAMPLES) samples.removeFirst()
        } else {
            samples.clear()
        }
        update()
    }

    private fun onResponse(value: ByteArray) {
        val r = CpResponse.parse(value) ?: return
        if (r.opcode != P.DS_CMD_GET_ALT_ZERO && r.opcode != P.DS_CMD_SET_ALT_ZERO) return
        if (!r.success) {
            if (r.opcode == P.DS_CMD_GET_ALT_ZERO && r.status == CP_NOT_SUPPORTED) zeroSupported = false
            else zeroMessage = "The FlySight did not set the ground level: ${r.statusText}."
            return update()
        }
        val z = AltZero.parse(r.data) ?: return
        zero = z
        zeroSupported = true
        if (r.opcode == P.DS_CMD_SET_ALT_ZERO) {
            zeroMessage = when (z.set and (AltZero.GNSS or AltZero.BARO)) {
                AltZero.GNSS or AltZero.BARO -> "Ground level set from the GPS and the barometer."
                AltZero.BARO -> "Ground level set from the barometer. The GPS is not accurate yet. Push again later to set the GPS part."
                AltZero.GNSS -> "Ground level set from the GPS. The barometer has no data."
                else -> "No accurate data yet. Wait some seconds, then push again."
            }
        }
        update()
    }

    fun onStateChanged() {
        if (!main.connected) dialog.dismiss() else update()
    }

    private fun average(): Int? = if (samples.size < MIN_SAMPLES) null else samples.average().roundToInt()

    private fun update() {
        val z = zero
        zeroText.text = when {
            zeroSupported == false -> "This firmware has no ground level. It uses the Ground elevation setting. Install the new firmware."
            z == null -> "Reading…"
            !z.hasGnss && !z.hasBaro -> "Not set. The FlySight uses the Ground elevation setting."
            else -> {
                val how = if (z.automatic) "Set automatically" else "Set from the app"
                val parts = listOfNotNull(
                    if (z.hasGnss) "GPS ${TrackStore.formatInt(z.groundHmslM)} m" else null,
                    if (z.hasBaro) String.format(Locale.US, "%.1f hPa", z.groundPressurePa / 100) else null,
                )
                "$how · ${parts.joinToString(" · ")}"
            }
        }

        // Height above the ground level, the same as the firmware calculates it
        val p = pressure
        val h = hMsl
        val (value, from) = when {
            z == null -> null to null
            z.altSource == 1 && z.hasBaro && p != null ->
                String.format(Locale.US, "%+.1f m", AltZero.pressureAltitude(p) - AltZero.pressureAltitude(z.groundPressurePa)) to "barometer"
            z.hasGnss && h != null -> String.format(Locale.US, "%+.0f m", h - z.groundHmslM) to "GPS"
            else -> null to null
        }
        heightText.text = value ?: "–"
        heightCaption.text = when {
            value != null -> "Height above the ground level, from the $from"
            z != null && z.altSource == 1 -> "Altitude source: barometer"
            else -> "Altitude source: GPS"
        }

        val sv = numSv?.let { "$it satellites" } ?: "no satellites yet"
        val gps = if (h != null) String.format(Locale.US, "GPS %s m above sea level, ±%.0f m, %s",
            TrackStore.formatInt(h), vAcc ?: 0.0, sv) else "GPS: waiting for an accurate position, $sv"
        val baro = if (p != null) String.format(Locale.US, "Barometer %.1f hPa", p / 100) else "Barometer: no data"
        sensors.text = "$gps\n$baro"

        message.text = if (saving) "Saving…" else zeroMessage ?: ""
        message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE

        val canZero = zeroSupported == true && !saving &&
            (main.mode == DeviceMode.ACTIVE || main.mode == DeviceMode.SENSOR)
        zeroButton.isEnabled = canZero
        zeroButton.alpha = if (canZero) 1f else 0.38f

        val avg = average()
        saveButton.text = if (avg != null) "Save ${TrackStore.formatInt(avg.toDouble())} m as Ground elevation" else "Save GPS altitude as Ground elevation"
        saveButton.isEnabled = avg != null && !saving
        saveButton.alpha = if (saveButton.isEnabled) 1f else 0.38f
        progress.visibility = if (saving) View.VISIBLE else View.INVISIBLE
    }

    // ---- Ground level in the FlySight ----

    private fun setZero() {
        zeroMessage = null
        main.sendDs(P.DS_CMD_SET_ALT_ZERO)
        update()
    }

    // ---- DZ_Elev in the files ----

    private fun save() {
        val elevation = average() ?: return
        saving = true
        update()
        main.whenFilesFree { writeAll(elevation) }
    }

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
        main.snack("Ground elevation is ${TrackStore.formatInt(elevation.toDouble())} m$was in $where.", long = true)
        dialog.dismiss()
    }

    private fun fail(message: String?) {
        saving = false
        if (message != null) main.snack(message, long = true)
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
        const val MAX_H_ACC_M = 20.0
        const val MAX_V_ACC_M = 10.0
        const val NOT_SET = Int.MIN_VALUE
        const val CP_NOT_SUPPORTED = 0x02
    }
}
