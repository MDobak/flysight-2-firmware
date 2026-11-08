package com.example.flysightstatus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Select the navigation target on the map. The input and the result are
 * [EXTRA_LAT] and [EXTRA_LON] in degrees * 10 000 000, as in the config file.
 */
class MapPickActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var map: TrackMapView
    private lateinit var coordinates: android.widget.TextView
    private lateinit var done: Ui.M3Button
    private var lat: Double? = null
    private var lon: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.setUpWindow(this)

        val bar = ui.TopBar("Navigation target")
        bar.setNavigation(R.drawable.ic_back, "Back") { finish() }
        done = ui.button(Ui.ButtonStyle.TEXT, "Done") { finishWithResult() }
        bar.addAction(done)

        map = TrackMapView(this)
        map.onPick = { la, lo -> setTarget(la, lo) }

        coordinates = ui.text(Ui.Type.TITLE_MEDIUM)
        val sheet = ui.column(16).apply {
            background = ui.shape(ui.surfaceContainerLow, 16f)
            elevation = ui.dpf(3f)
            addView(coordinates)
            addView(ui.text(Ui.Type.BODY_MEDIUM, "Tap the map to move the target. Pinch to zoom.", ui.onSurfaceVariant))
        }
        val mapFrame = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(28))
            })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(mapFrame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            (sheet.layoutParams as FrameLayout.LayoutParams).bottomMargin = ui.dp(28) + sys.bottom
            sheet.requestLayout()
            insets
        }
        setContentView(root)

        val inLat = intent.getIntExtra(EXTRA_LAT, 0)
        val inLon = intent.getIntExtra(EXTRA_LON, 0)
        if (inLat != 0 || inLon != 0) {
            setTarget(inLat / 1e7, inLon / 1e7)
            map.post { map.setCenter(inLat / 1e7, inLon / 1e7, 15.0) }
        } else {
            showNoTarget()
            centerOnLastLanding()
        }
    }

    private fun setTarget(la: Double, lo: Double) {
        lat = la
        lon = lo
        map.pin = la to lo
        coordinates.text = String.format(Locale.US, "%.6f, %.6f", la, lo)
        done.isEnabled = true
    }

    private fun showNoTarget() {
        coordinates.text = getString(R.string.no_target)
        done.isEnabled = false
    }

    /** Without a target, show the landing of the newest saved jump. */
    private fun centerOnLastLanding() {
        val file = TrackStore.list(this).firstOrNull() ?: return
        Thread {
            val track = runCatching { Track.load(file) }.getOrNull() ?: return@Thread
            runOnUiThread {
                if (!isDestroyed && lat == null) map.setCenter(track.lat.last(), track.lon.last(), 14.0)
            }
        }.start()
    }

    private fun finishWithResult() {
        val la = lat ?: return
        val lo = lon ?: return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_LAT, (la * 1e7).roundToLong().toInt()).putExtra(EXTRA_LON, (lo * 1e7).roundToLong().toInt()))
        finish()
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"

        fun intent(context: Context, latE7: Int?, lonE7: Int?) = Intent(context, MapPickActivity::class.java).apply {
            if (latE7 != null) putExtra(EXTRA_LAT, latE7)
            if (lonE7 != null) putExtra(EXTRA_LON, lonE7)
        }
    }
}
