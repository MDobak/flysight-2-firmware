package com.mdobak.flysightstatus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.io.File

/**
 * Chart and map of one or more saved tracks. A button group in the top bar shows
 * the chart, both, or the map. Drag the handle between them to change the sizes.
 */
class TrackActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var chart: TrackChartView
    private lateinit var map: TrackMapView
    private lateinit var split: SplitLayout
    private lateinit var bar: Ui.TopBar
    private lateinit var layoutGroup: Ui.IconToggleGroup
    private lateinit var progress: View
    private lateinit var controls: LinearLayout
    private lateinit var snackbars: Ui.SnackbarHost
    private var tracks: List<Track> = emptyList()
    private var files: List<File> = emptyList()

    private val prefs by lazy { getSharedPreferences("track_viewer", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.setUpWindow(this)
        files = intent.getStringArrayExtra(EXTRA_FILES)?.map { File(it) } ?: emptyList()

        bar = ui.TopBar(if (files.size > 1) "Compare ${files.size} jumps" else files.firstOrNull()?.let { TrackStore.title(it) } ?: "Track")
        bar.setNavigation(R.drawable.ic_back, "Back") { finish() }

        chart = TrackChartView(this, ui)
        map = TrackMapView(this)
        chart.onCursorChanged = { map.setCursor(it) }
        val mapPane = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
            // Above the OpenStreetMap attribution in the bottom-right corner
            addView(ui.mapButton(R.drawable.ic_fit, "Show all tracks") { map.fitTracks() },
                FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, ui.dp(8), ui.dp(24))
                })
        }
        split = SplitLayout(ui, chart, mapPane).apply {
            ratio = prefs.getFloat(PREF_RATIO, 0.5f)
            mode = SplitLayout.Mode.entries.getOrElse(prefs.getInt(PREF_MODE, 1)) { SplitLayout.Mode.BOTH }
            vertical = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            onRatioChanged = { prefs.edit().putFloat(PREF_RATIO, it).apply() }
            onModeChanged = { setMode(it) }
        }

        layoutGroup = ui.IconToggleGroup(listOf(
            R.drawable.ic_layout_chart to "Chart only",
            R.drawable.ic_layout_both to "Chart and map",
            R.drawable.ic_layout_map to "Map only",
        ), split.mode.ordinal) { setMode(SplitLayout.Mode.entries[it]) }
        updateSplitIcon()
        bar.addAction(layoutGroup)
        bar.addAction(ui.iconButton(R.drawable.ic_info, "Jump details") { showDetails() })

        progress = ui.linearProgress().apply { isIndeterminate = true }
        controls = ui.column().apply { visibility = View.GONE }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(split, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        val frame = FrameLayout(this).apply { addView(root) }
        snackbars = ui.SnackbarHost(frame)
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // The top bar uses the top inset
            root.setPadding(sys.left, 0, sys.right, sys.bottom)
            snackbars.bottomMarginPx = sys.bottom
            insets
        }
        setContentView(frame)
        load()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        split.vertical = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        updateSplitIcon()
    }

    /** The split icon shows the chart above the map. In landscape the panes are side by side. */
    private fun updateSplitIcon() {
        layoutGroup.buttons[SplitLayout.Mode.BOTH.ordinal]
            .setImageResource(if (split.vertical) R.drawable.ic_layout_both else R.drawable.ic_layout_both_side)
    }

    private fun setMode(mode: SplitLayout.Mode) {
        split.mode = mode
        if (layoutGroup.selected != mode.ordinal) layoutGroup.select(mode.ordinal)
        prefs.edit().putInt(PREF_MODE, mode.ordinal).apply()
    }

    private fun load() {
        Thread {
            val result = runCatching { files.map { Track.load(it) } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                result.onSuccess { show(it) }.onFailure {
                    snackbars.show("Cannot read the track: ${it.message}", long = true)
                }
            }
        }.start()
    }

    private fun show(list: List<Track>) {
        tracks = list
        val sameDay = files.mapNotNull { TrackStore.dateOf(it) }.distinct().size <= 1
        val labels = files.map { TrackStore.shortLabel(it, withDate = !sameDay) }
        chart.setTracks(list, labels)
        map.setTracks(list)
        buildControls(labels)
        if (list.none { it.hasExit }) snackbars.show("No exit found. Time is from the first point.")
    }

    private fun buildControls(labels: List<String>) {
        controls.removeAllViews()

        val multi = tracks.size > 1
        val seriesChips = TrackChartView.Series.entries.map { s ->
            val color = if (multi) ui.onSurfaceVariant else chart.colorOf(s, 0)
            val dash = if (multi) TrackChartView.dashFor(s, ui.density) else null
            ui.Chip(s.label, LineSample(ui, color, dash), showCheck = false) { on -> chart.setVisible(s, on) }
                .apply { checked = chart.isVisible(s) }
        }
        controls.addView(ui.chipRow(*seriesChips.toTypedArray()))

        if (multi) {
            val trackChips = labels.mapIndexed { i, label ->
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(chart.trackColor(i))
                    setSize(ui.dp(12), ui.dp(12))
                    setBounds(0, 0, ui.dp(12), ui.dp(12))
                }
                ui.Chip(label, dot, showCheck = false) { on ->
                    chart.setTrackVisible(i, on)
                    map.setTrackVisible(i, on)
                }.apply { checked = true }
            }
            controls.addView(ui.chipRow(*trackChips.toTypedArray()))
        }
        controls.visibility = View.VISIBLE
    }

    private fun showDetails() {
        if (tracks.isEmpty()) return
        val content = ui.column().apply { setPadding(ui.dp(24), 0, ui.dp(24), 0) }
        for ((i, track) in tracks.withIndex()) {
            if (tracks.size > 1) {
                content.addView(ui.text(Ui.Type.TITLE_SMALL, TrackStore.title(files[i]), chart.trackColor(i)).apply {
                    setPadding(0, if (i > 0) ui.dp(16) else 0, 0, ui.dp(4))
                })
            }
            content.addView(ui.text(Ui.Type.BODY_MEDIUM, track.summary(), ui.onSurfaceVariant))
        }
        ui.dialog(if (tracks.size > 1) "Jump details" else TrackStore.title(files[0]), content = content,
            positive = Ui.DialogButton("Close"), negative = null, scrollContent = true).show()
    }

    /** A short line in a colour and a line style, for a legend chip. */
    private class LineSample(ui: Ui, color: Int, dash: android.graphics.DashPathEffect?) : Drawable() {
        private val size = ui.dp(18)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = ui.dpf(2.5f)
            strokeCap = Paint.Cap.ROUND
            pathEffect = dash
        }

        init {
            setBounds(0, 0, size, size)
        }

        override fun draw(canvas: Canvas) {
            val y = bounds.exactCenterY()
            canvas.drawLine(bounds.left.toFloat() + 1, y, bounds.right.toFloat() - 1, y, paint)
        }

        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in the platform")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val EXTRA_FILES = "files"
        private const val PREF_RATIO = "ratio"
        private const val PREF_MODE = "mode"

        fun open(context: Context, files: List<File>) {
            context.startActivity(Intent(context, TrackActivity::class.java).putExtra(EXTRA_FILES, files.map { it.path }.toTypedArray()))
        }

        fun open(context: Context, vararg files: File) = open(context, files.toList())
    }
}
