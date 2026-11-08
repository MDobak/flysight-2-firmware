package com.example.flysightstatus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import com.example.flysightstatus.FlySightProtocol as P

/**
 * Live values of all FlySight sensors. In idle mode, the screen starts
 * sensor mode, and it sets the FlySight to idle again when it closes.
 * In active and start mode, the values come from the recording.
 */
@SuppressLint("SetTextI18n") // English only
class SensorsScreen(main: MainActivity) : Screen(main) {

    private val bar = ui.TopBar("Sensors")
    private val statusTitle = ui.text(Ui.Type.TITLE_MEDIUM, "", ui.onSecondaryContainer)
    private val statusBody = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSecondaryContainer)
    private val statusAction = LinearLayout(main).apply { gravity = Gravity.CENTER_VERTICAL }
    private var statusKey = ""

    private val gnss = Card("GNSS")
    private val baro = Card("Barometer", "Altitude", spark = true)
    private val hum = Card("Humidity", "Relative humidity")
    private val temp = Card("Temperature", "From the humidity sensor")
    private val accel = Card("Accelerometer", "Total acceleration", spark = true)
    private val gyro = Card("Gyroscope", "Total rotation rate", spark = true)
    private val mag = Card("Magnetometer", "Total field")
    private val battery = Card("Battery", "Voltage")
    private val cards = listOf(gnss, baro, hum, temp, accel, gyro, mag, battery)

    private val gSv = gnss.field("Satellites")
    private val gAcc = gnss.field("Accuracy")
    private val gTime = gnss.field("Time (UTC)")
    private val gAlt = gnss.field("Altitude (MSL)")
    private val gAgl = gnss.field("Above ground")
    private val gHSpeed = gnss.newRow().field("Horizontal")
    private val gVSpeed = gnss.field("Vertical")
    private val bPressure = baro.field("Pressure")
    private val bRaw = baro.field("Raw altitude")
    private val bGround = baro.field("Ground level")
    /** Each sensor chip has a temperature sensor. The chips are warmer than the air. */
    private val tBaro = temp.field("Barometer")
    private val tImu = temp.field("IMU")
    private val tMag = temp.field("Magnetometer")
    private val aX = accel.field("X")
    private val aY = accel.field("Y")
    private val aZ = accel.field("Z")
    private val wX = gyro.field("X")
    private val wY = gyro.field("Y")
    private val wZ = gyro.field("Z")
    private val mX = mag.field("X")
    private val mY = mag.field("Y")
    private val mZ = mag.field("Z")
    private val vLevel = battery.field("Level")

    /** The ground level of the FlySight (DS_CMD_GET_ALT_ZERO), or null. */
    private var zero: AltZero? = null
    /** The barometer card shows the height above the ground level (true) or the raw altitude. */
    private var baroCalibrated: Boolean? = null
    private var lastZeroRequest = 0L

    override val view: View

    private var visible = false
    private var subscribed = false
    /** The screen sent a sensor mode request since it became visible. */
    private var autoStarted = false
    /** A sensor mode request is sent, but DS_Mode did not change yet. */
    private var requested = false
    private var wasConnected = false

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            cards.forEach { it.updateFreshness(now) }
            // Automatic ground level can change in active mode
            if (main.connected && supported && now - lastZeroRequest > ZERO_REQUEST_MS) requestZero()
            main.handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        val status = ui.card(ui.secondaryContainer, 16f, 0).apply {
            setPadding(ui.dp(20), ui.dp(16), ui.dp(12), ui.dp(16))
            addView(LinearLayout(main).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.column().apply {
                    addView(statusTitle)
                    addView(statusBody)
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = ui.dp(8) })
                addView(statusAction)
            })
        }
        val body = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24))
            addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
            for (c in cards) addView(c.view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })
        }
        view = ui.column().apply {
            addView(bar)
            addView(ui.scroll(body, bar), LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        clearValues()
        updateStatus()
    }

    // ---- Screen ----

    override fun onShow() {
        visible = true
        autoStarted = false
        main.handler.removeCallbacks(ticker)
        main.handler.post(ticker)
        update()
    }

    override fun onHide() {
        if (!visible) return
        visible = false
        main.handler.removeCallbacks(ticker)
        if (main.connected && (requested || main.mode == DeviceMode.SENSOR)) main.sendDs(P.DS_CMD_REQUEST_SLEEP)
        requested = false
        unsubscribe()
    }

    override fun onStateChanged() {
        val connected = main.connected
        if (connected != wasConnected) {
            wasConnected = connected
            subscribed = false
            requested = false
            autoStarted = false
            if (!connected) {
                clearValues()
                zero = null
            }
        }
        if (main.mode != DeviceMode.SLEEP) requested = false
        update()
    }

    override fun onValue(characteristic: UUID, value: ByteArray) {
        if (!visible) return
        when (characteristic) {
            P.SD_SENSOR_MEASUREMENT -> SensorMeasurement.parse(value)?.let { show(it) }
            P.SD_GNSS_MEASUREMENT -> GnssMeasurement.parse(value)?.let { show(it) }
            P.DS_CONTROL_POINT -> onResponse(value)
        }
    }

    private fun requestZero() {
        lastZeroRequest = SystemClock.elapsedRealtime()
        main.sendDs(P.DS_CMD_GET_ALT_ZERO)
    }

    private fun onResponse(value: ByteArray) {
        val r = CpResponse.parse(value) ?: return
        if (r.opcode != P.DS_CMD_GET_ALT_ZERO && r.opcode != P.DS_CMD_SET_ALT_ZERO) return
        zero = if (r.success) AltZero.parse(r.data) else null
    }

    // ---- Mode and notifications ----

    private val supported get() = main.connection?.hasCharacteristic(P.SENSOR_DATA_SERVICE, P.SD_SENSOR_MEASUREMENT) == true

    private val jumpDownload get() = main.sync.running || main.files?.busy == true

    private fun update() {
        if (visible && main.connected && supported) {
            subscribe()
            if (!autoStarted && main.mode == DeviceMode.SLEEP && !jumpDownload) startSensorMode()
        }
        updateStatus()
    }

    private fun startSensorMode() {
        autoStarted = true
        requested = true
        main.sendDs(P.DS_CMD_REQUEST_SENSOR)
        updateStatus()
    }

    private fun stopSensorMode() {
        requested = false
        main.sendDs(P.DS_CMD_REQUEST_SLEEP)
    }

    private fun subscribe() {
        if (subscribed) return
        val c = main.connection ?: return
        subscribed = true
        c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_SENSOR_MEASUREMENT, true)
        c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_GNSS_MEASUREMENT, true)
        main.sendControl(P.SENSOR_DATA_SERVICE, P.SD_CONTROL_POINT, "SD", P.SD_COMMAND_NAMES,
            P.SD_CMD_SET_MASK, P.GNSS_SENSORS_MASK)
    }

    private fun unsubscribe() {
        if (!subscribed) return
        subscribed = false
        val c = main.connection ?: return
        c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_SENSOR_MEASUREMENT, false)
        // The Device screen can also use the GNSS data
        if (!main.tab(DeviceScreen::class.java).gnssLive) c.setNotifications(P.SENSOR_DATA_SERVICE, P.SD_GNSS_MEASUREMENT, false)
    }

    private fun updateStatus() {
        val connected = main.connected
        val mode = main.mode
        val (title, body, key) = when {
            !connected && main.connection != null -> Triple("Connecting…", "", "connecting")
            !connected -> Triple("Not connected", "Connect to your FlySight to see live data from its sensors.", "connect")
            !supported -> Triple("Firmware update necessary",
                "This firmware does not send sensor data. Install the firmware with sensor mode, then connect again.", "")
            mode == DeviceMode.SENSOR -> Triple("Sensor mode",
                "The LED pulses orange. Sensor mode stops when you leave this tab.", "stop")
            mode == DeviceMode.SLEEP && requested -> Triple("Starting sensor mode…", "", "")
            mode == DeviceMode.SLEEP && jumpDownload -> Triple("Idle",
                "Sensor mode starts after the jump download.", "")
            mode == DeviceMode.SLEEP -> Triple("Idle", "Start sensor mode to read the sensors.", "start")
            mode == DeviceMode.ACTIVE -> Triple("Recording", "The values come from the active recording.", "")
            mode == DeviceMode.START -> Triple("Start mode", "The values come from the start mode recording.", "")
            mode == DeviceMode.UNKNOWN -> Triple("Connected", "Reading the device state…", "")
            else -> Triple("${mode.label} mode", "Sensor data is available in idle, active, and start mode.", "")
        }
        statusTitle.text = title
        statusBody.text = body
        statusBody.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
        if (key != statusKey) {
            statusKey = key
            statusAction.removeAllViews()
            when (key) {
                "connect" -> statusAction.addView(ui.button(Ui.ButtonStyle.FILLED, "Connect") { main.connectOrScan() })
                "start" -> statusAction.addView(ui.button(Ui.ButtonStyle.FILLED, "Start") { startSensorMode() })
                "stop" -> statusAction.addView(ui.button(Ui.ButtonStyle.OUTLINED, "Stop", content = ui.onSecondaryContainer) { stopSensorMode() })
            }
            statusAction.visibility = if (statusAction.childCount == 0) View.GONE else View.VISIBLE
        }
    }

    // ---- Values ----

    private fun clearValues() {
        for (c in cards) c.clear()
        baroCalibrated = null
        baro.setCaption("Altitude")
    }

    private fun show(m: GnssMeasurement) {
        val fix = m.hAccM != null && m.hAccM < MAX_FIX_ACCURACY_M && m.latDeg != null && m.lonDeg != null
        gnss.setValue(if (fix) String.format(Locale.US, "%.6f, %.6f", m.latDeg, m.lonDeg) else "No fix", null)
        gnss.setCaption(if (fix) "Latitude, longitude" else "Wait outside with a clear view of the sky.")
        gSv.text = m.numSv?.toString() ?: DASH
        gAcc.text = m.hAccM?.let { if (it < 1000) f("%.1f m", it) else "> 1 km" } ?: DASH
        gTime.text = m.iTowMs?.let { utcTime(it) } ?: DASH
        gAlt.text = if (fix) m.hMslM?.let { f("%.1f m", it) } ?: DASH else DASH
        val z = zero
        gAgl.text = when {
            z == null || !z.hasGnss -> "Not set"
            fix && m.hMslM != null -> f("%+.1f m", m.hMslM - z.groundHmslM)
            else -> DASH
        }
        gHSpeed.text = if (fix && m.velN != null && m.velE != null) f("%.1f km/h", sqrt(m.velN * m.velN + m.velE * m.velE) * 3.6) else DASH
        gVSpeed.text = if (fix && m.velD != null) f("%.1f km/h", m.velD * 3.6) else DASH
        gnss.fresh()
    }

    private fun show(m: SensorMeasurement) {
        if (m.pressurePa != null) {
            val raw = pressureAltitude(m.pressurePa)
            val z = zero?.takeIf { it.hasBaro }
            val calibrated = z != null
            if (calibrated != baroCalibrated) {
                // The two altitudes have different zeros. Do not mix them in one line.
                baroCalibrated = calibrated
                baro.clearHistory()
                baro.setCaption(if (calibrated) "Height above the ground level" else
                    "Raw altitude. Set the ground level on the Home tab to see the height above it.")
            }
            val shown = if (z != null) raw - pressureAltitude(z.groundPressurePa) else raw
            baro.setValue(f(if (calibrated) "%+.1f" else "%.1f", shown), "m", shown)
            bPressure.text = f("%.2f hPa", m.pressurePa / 100)
            bRaw.text = f("%.1f m", raw)
            bGround.text = z?.let { f("%.2f hPa", it.groundPressurePa / 100) } ?: "Not set"
            baro.fresh()
            m.baroTempC?.let { tBaro.text = f("%.1f °C", it) }
        }
        if (m.accel != null) {
            val (x, y, z) = m.accel
            val total = sqrt(x * x + y * y + z * z)
            accel.setValue(f("%.3f", total), "g", total)
            aX.text = f("%.3f g", x)
            aY.text = f("%.3f g", y)
            aZ.text = f("%.3f g", z)
            m.imuTempC?.let { tImu.text = f("%.1f °C", it) }
            accel.fresh()
        }
        if (m.gyro != null) {
            val (x, y, z) = m.gyro
            val total = sqrt(x * x + y * y + z * z)
            gyro.setValue(f("%.1f", total), "°/s", total)
            wX.text = f("%.1f °/s", x)
            wY.text = f("%.1f °/s", y)
            wZ.text = f("%.1f °/s", z)
            gyro.fresh()
        }
        if (m.mag != null) {
            val (x, y, z) = m.mag
            mag.setValue(f("%.3f", sqrt(x * x + y * y + z * z)), "G")
            mX.text = f("%.3f G", x)
            mY.text = f("%.3f G", y)
            mZ.text = f("%.3f G", z)
            m.magTempC?.let { tMag.text = f("%.1f °C", it) }
            mag.fresh()
        }
        if (m.humidity != null) {
            hum.setValue(f("%.1f", m.humidity), "%")
            hum.fresh()
            m.humTempC?.let { temp.setValue(f("%.1f", it), "°C") }
            temp.fresh()
        }
        if (m.batteryV != null) {
            battery.setValue(f("%.2f", m.batteryV), "V")
            vLevel.text = main.battery?.let { "$it %" } ?: DASH
            battery.fresh()
        }
    }

    // ---- Card ----

    /** A card with a large value, an optional history line, and a grid of small values. */
    private inner class Card(title: String, caption: String? = null, spark: Boolean = false) {
        val view = ui.card(ui.surfaceContainer, 16f, 0)
        private val stateLabel = ui.text(Ui.Type.LABEL_MEDIUM, "No data", ui.onSurfaceVariant)
        private val value = ui.text(Ui.Type.HEADLINE_SMALL, DASH).apply { fontFeatureSettings = "tnum" }
        private val caption = ui.text(Ui.Type.BODY_SMALL, caption ?: "", ui.onSurfaceVariant)
        private val sparkline = if (spark) Sparkline(main, ui.primary, ui.dpf(2f)) else null
        private val content = ui.column()
        private val grid = ui.column()
        private val fields = mutableListOf<TextView>()
        private var cells = 0
        private var lastUpdate = 0L

        init {
            view.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16))
            view.addView(LinearLayout(main).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(32)
                addView(ui.text(Ui.Type.TITLE_SMALL, title, ui.onSurfaceVariant), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(stateLabel)
            })
            content.addView(value)
            if (caption != null) content.addView(this.caption)
            if (sparkline != null) {
                content.addView(sparkline, LinearLayout.LayoutParams(MATCH, ui.dp(40)).apply { topMargin = ui.dp(8) })
            }
            content.addView(grid)
            view.addView(content)
        }

        /** Add a small value to the grid. Three values make one row. */
        fun field(label: String): TextView {
            val valueText = ui.text(Ui.Type.BODY_LARGE, DASH).apply {
                fontFeatureSettings = "tnum"
                isSingleLine = true
            }
            val cell = ui.column().apply {
                addView(ui.text(Ui.Type.LABEL_MEDIUM, label, ui.onSurfaceVariant).apply { isSingleLine = true })
                addView(valueText)
            }
            val row = if (cells % COLUMNS == 0) {
                LinearLayout(main).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
                    grid.addView(r, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(12) })
                    repeat(COLUMNS) { r.addView(View(main), LinearLayout.LayoutParams(0, WRAP, 1f)) }
                }
            } else {
                grid.getChildAt(grid.childCount - 1) as LinearLayout
            }
            val index = cells % COLUMNS
            row.removeViewAt(index)
            row.addView(cell, index, LinearLayout.LayoutParams(0, WRAP, 1f))
            fields += valueText
            cells++
            return valueText
        }

        /** The next field starts a new row. */
        fun newRow(): Card {
            if (cells % COLUMNS != 0) cells += COLUMNS - cells % COLUMNS
            return this
        }

        fun clearHistory() {
            sparkline?.clear()
        }

        fun setValue(text: String, unit: String?, history: Double? = null) {
            value.text = if (unit == null) text else SpannableString("$text $unit").apply {
                val start = text.length + 1
                setSpan(RelativeSizeSpan(0.6f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(ui.onSurfaceVariant), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (history != null) sparkline?.add(history.toFloat())
        }

        fun setCaption(text: String) {
            caption.text = text
        }

        fun fresh() {
            lastUpdate = SystemClock.elapsedRealtime()
            updateFreshness(lastUpdate)
        }

        fun updateFreshness(now: Long) {
            val stale = lastUpdate == 0L || now - lastUpdate > STALE_MS
            stateLabel.visibility = if (stale) View.VISIBLE else View.GONE
            content.alpha = if (stale) 0.5f else 1f
        }

        fun clear() {
            value.text = DASH
            fields.forEach { it.text = DASH }
            sparkline?.clear()
            lastUpdate = 0L
            updateFreshness(SystemClock.elapsedRealtime())
        }
    }

    /** The last 30 seconds of a value as a line. The range fits the values, with a minimum span. */
    private class Sparkline(context: Context, color: Int, strokePx: Float) : View(context) {
        private val values = FloatArray(HISTORY)
        private var count = 0
        private var next = 0
        private val path = Path()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx / 2
            this.color = color
            alpha = 48
        }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun add(v: Float) {
            values[next] = v
            next = (next + 1) % HISTORY
            if (count < HISTORY) count++
            invalidate()
        }

        fun clear() {
            count = 0
            next = 0
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val pad = line.strokeWidth
            val h = height - 2 * pad
            canvas.drawLine(0f, height - pad / 2, width.toFloat(), height - pad / 2, base)
            if (count < 2) return
            var min = Float.MAX_VALUE
            var max = -Float.MAX_VALUE
            for (i in 0 until count) {
                val v = values[(next - count + i + HISTORY) % HISTORY]
                if (v < min) min = v
                if (v > max) max = v
            }
            // Small noise must not fill the full height
            val minSpan = maxOf(abs(max) * 0.002f, 0.05f)
            if (max - min < minSpan) {
                val mid = (max + min) / 2
                min = mid - minSpan / 2
                max = mid + minSpan / 2
            }
            val step = width.toFloat() / (HISTORY - 1)
            val x0 = width - (count - 1) * step
            path.reset()
            for (i in 0 until count) {
                val v = values[(next - count + i + HISTORY) % HISTORY]
                val x = x0 + i * step
                val y = pad + h - (v - min) / (max - min) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, line)
        }

        companion object {
            /** 30 s at 4 values each second. */
            const val HISTORY = 120
        }
    }

    companion object {
        private const val DASH = "–"
        private const val COLUMNS = 3
        private const val TICK_MS = 500L
        private const val STALE_MS = 3000L
        private const val ZERO_REQUEST_MS = 10_000L
        /** A larger horizontal accuracy means that the receiver has no position yet. */
        private const val MAX_FIX_ACCURACY_M = 100.0
        /** GPS time is ahead of UTC by this number of leap seconds (since 2017). */
        private const val LEAP_SECONDS = 18

        private fun f(format: String, v: Double) = String.format(Locale.US, format, v)

        /** Standard atmosphere altitude for a pressure. */
        fun pressureAltitude(pa: Double) = 44330.77 * (1 - (pa / 101325.0).pow(0.190263))

        /** Time of day (UTC) from the GPS time of week. */
        fun utcTime(iTowMs: Long): String {
            val s = Math.floorMod(iTowMs / 1000 - LEAP_SECONDS, 86_400L)
            return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        }
    }
}
