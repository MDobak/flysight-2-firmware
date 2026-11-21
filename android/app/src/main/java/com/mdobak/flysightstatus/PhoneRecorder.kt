package com.mdobak.flysightstatus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Records a jump with the GPS of the phone, in the FlySight format.
 *
 * TRACK.CSV has only GPS data, as from a FlySight. velD comes from the change of
 * the GPS altitude, thus each row is written one fix late. If the phone has a
 * barometer, a second file has its data in the SENSOR.CSV format.
 *
 * The recording goes to recording/. At the stop, the track goes to the saved
 * tracks ("phone_yy-MM-dd_HH-mm-ss.csv") and the sensor file goes to sensors/.
 */
class PhoneRecorder : Service() {

    /** The data of one GPS fix. */
    private class Fix(val location: Location, val hMsl: Double, val elapsedNs: Long, val numSv: Int)

    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var locations: LocationManager? = null
    private var sensors: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var track: Writer? = null
    private var sensor: Writer? = null
    private var startNs = 0L
    private var lastFlushNs = 0L
    private var satellites = 0
    private var fixes = 0
    /** The fix before [last]. */
    private var previous: Fix? = null
    /** The newest fix. It is not written yet, because its velD needs the next fix. */
    private var last: Fix? = null
    @Volatile private var stopping = false
    private var startWall = 0L

    private val locationListener = LocationListener { onLocation(it) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satellites = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    private val baroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val w = sensor ?: return
            val t = (event.timestamp - startNs) / 1e9
            if (t < 0) return
            w.write(String.format(Locale.US, "\$BARO,%.6f,%.2f,\n", t, event.values[0] * 100.0))
            if (event.timestamp - lastFlushNs > 1_000_000_000L) {
                lastFlushNs = event.timestamp
                w.flush()
            }
        }

        override fun onAccuracyChanged(s: Sensor, accuracy: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("PhoneRecorder").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startWall = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, notification("Waiting for GPS…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        running = true
        stopping = false
        startedAt = SystemClock.elapsedRealtime()
        firstFixAt = null
        publish(0, 0, null)
        worker.post { begin() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) {
            // The system stopped the service. Keep the data.
            unregister()
            worker.post { finish() }
        }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun begin() {
        val ctx = applicationContext
        TrackStore.init(ctx)
        val dir = recordingDir(ctx)
        dir.listFiles()?.forEach { it.delete() }
        startNs = SystemClock.elapsedRealtimeNanos()
        val device = TrackStore.phoneDeviceId
        val session = randomHex(12)
        val firmware = "Android-${Build.MANUFACTURER}-${Build.MODEL}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val head = "\$FLYS,1\n\$VAR,FIRMWARE_VER,$firmware\n\$VAR,DEVICE_ID,$device\n\$VAR,SESSION_ID,$session\n"
        track = File(dir, TRACK).bufferedWriter().apply {
            write(head)
            write("\$COL,GNSS,time,lat,lon,hMSL,velN,velE,velD,hAcc,vAcc,sAcc,numSV\n")
            write("\$UNIT,GNSS,,deg,deg,m,m/s,m/s,m/s,m,m,m/s,\n")
            write("\$DATA\n")
            flush()
        }
        val sm = getSystemService(SensorManager::class.java)
        sensors = sm
        val baro = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (baro != null) {
            sensor = File(dir, SENSOR).bufferedWriter().apply {
                write(head)
                write("\$COL,BARO,time,pressure,temperature\n")
                write("\$UNIT,BARO,s,Pa,deg C\n")
                write("\$COL,TIME,time,tow,week\n")
                write("\$UNIT,TIME,s,s,\n")
                write("\$DATA\n")
            }
            sm.registerListener(baroListener, baro, BARO_PERIOD_US, worker)
        }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlySight:PhoneRecorder")
            ?.apply { acquire(MAX_RECORDING_MS) }
        val lm = getSystemService(LocationManager::class.java)
        locations = lm
        try {
            val executor = java.util.concurrent.Executor { worker.post(it) }
            lm?.registerGnssStatusCallback(executor, gnssCallback)
            val request = LocationRequest.Builder(FIX_INTERVAL_MS)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(0)
                .build()
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, executor, locationListener)
        } catch (e: SecurityException) {
            main.post { result = Result(null, "The app has no location permission.") }
            stop()
        }
        main.post { notifyListeners() }
    }

    private fun onLocation(location: Location) {
        val w = track ?: return
        if (stopping) return
        var hMsl = location.altitude
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                android.location.altitude.AltitudeConverter().addMslAltitudeToLocation(applicationContext, location)
                if (location.hasMslAltitude()) hMsl = location.mslAltitudeMeters
            } catch (_: Exception) {
            }
        }
        val fix = Fix(location, hMsl, location.elapsedRealtimeNanos, satellites)
        last?.let { writeRow(w, it, previous, fix) }
        previous = last
        last = fix
        fixes++
        timeRow(fix)
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val count = fixes
        val sv = satellites
        main.post {
            if (firstFixAt == null) {
                firstFixAt = SystemClock.elapsedRealtime()
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification("Recording"))
            }
            publish(count, sv, accuracy)
        }
    }

    /** A $TIME row: the time of the sensor file and the GPS time of the fix. */
    private fun timeRow(fix: Fix) {
        val w = sensor ?: return
        val t = (fix.elapsedNs - startNs) / 1e9
        val gpsMs = fix.location.time - GPS_EPOCH_MS + LEAP_SECONDS * 1000L
        val week = gpsMs / WEEK_MS
        val tow = (gpsMs % WEEK_MS) / 1000.0
        w.write(String.format(Locale.US, "\$TIME,%.6f,%.3f,%d\n", t, tow, week))
        w.flush()
    }

    /** Write [fix]. velD comes from the altitude of the fixes before and after it. */
    private fun writeRow(w: Writer, fix: Fix, before: Fix?, after: Fix?) {
        fun near(other: Fix?) = other?.takeIf { kotlin.math.abs(it.elapsedNs - fix.elapsedNs) in 1..MAX_GAP_NS }
        val a = near(before) ?: fix
        val b = near(after) ?: fix
        val dt = (b.elapsedNs - a.elapsedNs) / 1e9
        val velD = if (dt > 0) -(b.hMsl - a.hMsl) / dt else 0.0
        val loc = fix.location
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val bearing = if (loc.hasBearing()) Math.toRadians(loc.bearing.toDouble()) else 0.0
        val vAcc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else 0f
        val sAcc = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else 0f
        w.write(String.format(Locale.US, "\$GNSS,%s,%.7f,%.7f,%.3f,%.2f,%.2f,%.2f,%.3f,%.3f,%.2f,%d\n",
            TIME.format(Instant.ofEpochMilli(loc.time)), loc.latitude, loc.longitude, fix.hMsl,
            speed * cos(bearing), speed * sin(bearing), velD, loc.accuracy, vAcc, sAcc, fix.numSv))
        w.flush()
    }

    private fun stop() {
        if (!running || stopping) return
        stopping = true
        unregister()
        worker.post {
            val saved = finish()
            main.post {
                result = saved
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun unregister() {
        locations?.removeUpdates(locationListener)
        locations?.unregisterGnssStatusCallback(gnssCallback)
        sensors?.unregisterListener(baroListener)
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    /** Write the last fix, close the files and move them to the saved tracks. Runs on the worker thread. */
    private fun finish(): Result {
        val w = track
        last?.let { if (w != null) writeRow(w, it, previous, null) }
        runCatching { w?.close() }
        runCatching { sensor?.close() }
        track = null
        sensor = null
        last = null
        previous = null
        val saved = runCatching { save(applicationContext) }
        main.post {
            running = false
            publish(0, 0, null)
        }
        return saved.getOrElse { Result(null, "Cannot save the recording: ${it.message}") }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Phone recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneRecorder::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_s_gnss)
            .setContentTitle("FlySight · Phone recording")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(startWall)
            .setUsesChronometer(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** The end of a recording: the saved track, or an error message. */
    class Result(val file: File?, val error: String?)

    companion object {
        private const val ACTION_STOP = "com.mdobak.flysightstatus.STOP_RECORDING"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val TRACK = "track.csv"
        private const val SENSOR = "sensor.csv"
        private const val FIX_INTERVAL_MS = 200L
        private const val BARO_PERIOD_US = 100_000
        private const val MAX_RECORDING_MS = 6 * 3600_000L
        /** Fixes that are farther apart than this do not give velD. */
        private const val MAX_GAP_NS = 3_000_000_000L
        private const val GPS_EPOCH_MS = 315_964_800_000L
        private const val LEAP_SECONDS = 18
        private const val WEEK_MS = 7 * 24 * 3600_000L
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        private val main = Handler(Looper.getMainLooper())

        /** True from the start until the files are saved. Main thread. */
        var running = false
            private set
        /** SystemClock.elapsedRealtime() at the start and at the first fix. */
        var startedAt = 0L
            private set
        var firstFixAt: Long? = null
            private set
        var fixCount = 0
            private set
        var satelliteCount = 0
            private set
        /** The horizontal accuracy of the last fix, in metres. */
        var accuracy: Float? = null
            private set
        /** The result of the last recording. The screen sets it to null when it shows it. */
        var result: Result? = null
            set(value) {
                field = value
                notifyListeners()
            }

        /** The listeners get a call on the main thread when the state changes. */
        val listeners = mutableSetOf<() -> Unit>()

        private fun publish(fixes: Int, satellites: Int, acc: Float?) {
            fixCount = fixes
            satelliteCount = satellites
            accuracy = acc
            notifyListeners()
        }

        private fun notifyListeners() {
            for (l in listeners.toList()) l()
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PhoneRecorder::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PhoneRecorder::class.java).setAction(ACTION_STOP))
        }

        private fun recordingDir(context: Context) = File(context.filesDir, "recording").apply { mkdirs() }

        /**
         * Move a recording that the app did not save (for example, after a crash) to
         * the saved tracks. Call on a background thread, and only if [running] is false.
         */
        fun recover(context: Context): Result? =
            if (File(recordingDir(context), TRACK).exists()) save(context) else null

        /** Move the files from recording/ to the saved tracks. A track with less than 2 fixes is deleted. */
        private fun save(context: Context): Result {
            val dir = recordingDir(context)
            val trackFile = File(dir, TRACK)
            val sensorFile = File(dir, SENSOR)
            val fixes = if (trackFile.exists()) trackFile.useLines { l -> l.count { it.startsWith("\$GNSS,") } } else 0
            if (fixes < 2) {
                trackFile.delete()
                sensorFile.delete()
                return Result(null, "No GPS fix. The app did not save a track.")
            }
            val first = TrackHeader.parse(trackFile.bufferedReader().use { r ->
                CharArray(8192).let { String(it, 0, maxOf(0, r.read(it))) }
            }).firstMs ?: trackFile.lastModified()
            val file = TrackStore.addRecording(context, trackFile, sensorFile.takeIf { it.exists() }, first)
            return Result(file, null)
        }

        private fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** A new random device ID for this phone: 24 hex digits, as the ID of a FlySight. */
        fun newDeviceId(): String = randomHex(12)
    }
}
