package com.mdobak.flysightstatus

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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
    private lateinit var pane3d: Track3DPane
    private lateinit var split: SplitLayout
    private lateinit var bar: Ui.TopBar
    private lateinit var layoutGroup: Ui.IconToggleGroup
    private lateinit var progress: View
    private lateinit var controls: LinearLayout
    private lateinit var snackbars: Ui.SnackbarHost
    private lateinit var playback: PlaybackBar
    private lateinit var replayButton: android.widget.ImageView
    /** The tracks as loaded: the time of each is from its exit. */
    private var loaded: List<Track> = emptyList()
    /** The tracks that show: with the time of the synchronization. */
    private var tracks: List<Track> = emptyList()
    private var sync = TrackSync.default(0)
    /** The top bar button that opens the synchronization page. It shows only for several jumps. */
    private lateinit var syncButton: android.widget.ImageView
    private var syncPage: SyncPage? = null
    private val syncBack = OnBackInvokedCallback { closeSync() }
    private lateinit var frame: FrameLayout
    /** The switch between the 2D map and the 3D view, and the 2D map pane. */
    private lateinit var viewSwitch: View
    private lateinit var mapPane: View
    private var labels: List<String> = emptyList()
    /** The chips of the chart lines. They are hidden when only the map shows. */
    private var seriesRow: View? = null
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
        val mapFrame = mapFrame(mapPane)
        split = SplitLayout(ui, chart, mapFrame).apply {
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
        syncButton = ui.iconButton(R.drawable.ic_timer, "Synchronize jumps") { showSync() }.apply { visibility = View.GONE }
        bar.addAction(syncButton)
        replayButton = ui.iconButton(R.drawable.ic_play, "Replay") { toggleReplay() }
        bar.addAction(replayButton)
        // Secondary actions in an overflow menu: the top bar has little space
        lateinit var more: View
        more = ui.iconButton(R.drawable.ic_more, "More options") { showMore(more) }
        bar.addAction(more)

        progress = ui.linearProgress().apply { isIndeterminate = true }
        controls = ui.column().apply { visibility = View.GONE }
        playback = playbackBar()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(split, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(playback, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        frame = FrameLayout(this).apply { addView(root) }
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

    /**
     * The 2D map and the 3D view in one pane, with a switch in the top-right corner.
     * The 3D view loads its tiles only when it is shown.
     */
    private fun mapFrame(mapPane: View): View {
        pane3d = Track3DPane(this, ui).apply { visibility = View.GONE }
        val previous = chart.onCursorChanged
        chart.onCursorChanged = {
            previous?.invoke(it)
            pane3d.view.setCursor(it)
        }
        this.mapPane = mapPane
        val is3d = prefs.getBoolean(PREF_3D, false)
        val switch = ui.IconToggleGroup(listOf(
            R.drawable.ic_view_2d to "2D map",
            R.drawable.ic_view_3d to "3D view",
        ), if (is3d) 1 else 0) { show3d(it == 1, mapPane) }.apply {
            background = ui.shape(ui.surfaceContainerHigh, 18f)
            elevation = ui.dpf(3f)
        }
        viewSwitch = switch
        show3d(is3d, mapPane)
        return FrameLayout(this).apply {
            addView(mapPane, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(pane3d, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(switch, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                setMargins(0, ui.dp(8), ui.dp(8), 0)
            })
        }
    }

    /**
     * The replay moves the cursor of the chart, the map and the 3D view. The 3D
     * view follows the cursor during the replay, until the user unpins it. A
     * drag on the chart pauses the replay.
     */
    private fun playbackBar(): PlaybackBar {
        val player = PlaybackBar(this, ui)
        player.onTime = {
            chart.showCursor(it)
            map.setCursor(it)
            pane3d.view.setCursor(it)
        }
        player.onOpenChanged = { open ->
            pane3d.setReplay(open)
            replayButton.setImageResource(if (open) R.drawable.ic_stop else R.drawable.ic_play)
            replayButton.contentDescription = if (open) "Stop the replay" else "Replay"
            replayButton.tooltipText = replayButton.contentDescription
        }
        val previous = chart.onCursorChanged
        chart.onCursorChanged = {
            previous?.invoke(it)
            player.showTime(it)
        }
        return player
    }

    /** Show the player bar and play, or stop and hide it. */
    private fun toggleReplay() {
        if (tracks.isEmpty()) return
        if (playback.isOpen) playback.close() else playback.open()
    }

    private fun showMore(anchor: View) {
        if (tracks.isEmpty()) return
        ui.popupMenu(anchor, listOfNotNull(
            "Jump details" to { showDetails() },
            "Open in Google Earth" to { exportKml(googleEarth = true) },
            "Share as KML file" to { exportKml(googleEarth = false) },
        ))
    }

    /** Write the KML file, then open it in Google Earth or in the share sheet. */
    private fun exportKml(googleEarth: Boolean) {
        val title = bar.title.text.toString()
        // The KML file has the time of each jump from its exit
        val list = loaded
        val names = labels
        Thread {
            val result = runCatching { KmlExport.export(this, list, names, title) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { uri ->
                    if (googleEarth) {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, KmlExport.MIME)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            setPackage(GOOGLE_EARTH)
                        }
                        try {
                            startActivity(view)
                            return@runOnUiThread
                        } catch (_: ActivityNotFoundException) {
                            snackbars.show("Google Earth is not installed. Select a different app.")
                        }
                    }
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = KmlExport.MIME
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(title, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share the KML file"))
                }.onFailure { snackbars.show("Cannot export the track: ${it.message}", long = true) }
            }
        }.start()
    }

    /** Show the 3D view or the 2D map. [save] keeps the choice for the next tracks. */
    private fun show3d(on: Boolean, mapPane: View, save: Boolean = true) {
        mapPane.visibility = if (on) View.INVISIBLE else View.VISIBLE
        pane3d.visibility = if (on) View.VISIBLE else View.GONE
        if (save) prefs.edit().putBoolean(PREF_3D, on).apply()
    }

    /**
     * Jumps that are far apart do not fit in one 3D view. Then only the 2D map
     * shows, and the user choice of the 3D view stays for other jumps.
     */
    private fun check3d(list: List<Track>) {
        if (Track3DView.canShow(list)) return
        viewSwitch.visibility = View.GONE
        if (pane3d.visibility == View.VISIBLE) {
            show3d(false, mapPane, save = false)
            snackbars.show("The jumps are too far apart for the 3D view. The map shows them.")
        }
    }

    override fun onPause() {
        super.onPause()
        // The synchronization must not be lost if the app stops
        syncPage?.let { setSync(it.value) }
        playback.stop()
        pane3d.view.onPause()
    }

    override fun onResume() {
        super.onResume()
        pane3d.view.onResume()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        split.vertical = newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        updateSplitIcon()
        // The synchronization page has a different layout in landscape
        syncPage?.let { page ->
            frame.removeView(page)
            syncPage = null
            openSyncPage(page.value)
        }
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
        updateControls()
    }

    /** The chart chips show only with the chart. The track chips also control the map. */
    private fun updateControls() {
        val row = seriesRow ?: return
        row.visibility = if (split.mode == SplitLayout.Mode.SECOND) View.GONE else View.VISIBLE
        controls.visibility = if ((0 until controls.childCount).any { controls.getChildAt(it).visibility == View.VISIBLE }) View.VISIBLE else View.GONE
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
        loaded = list
        sync = if (list.size > 1) TrackSync.decode(prefs.getString(syncKey(), null), list.size) else TrackSync.default(list.size)
        tracks = if (sync.isDefault) list else sync.align(list)
        val sameDay = files.mapNotNull { TrackStore.dateOf(it) }.distinct().size <= 1
        labels = files.map { TrackStore.shortLabel(it, withDate = !sameDay) }
        chart.setTracks(tracks, labels)
        map.setTracks(tracks)
        check3d(list)
        pane3d.view.setTracks(tracks)
        playback.setTracks(tracks)
        buildControls(labels)
        if (list.none { it.hasExit }) snackbars.show("No exit found. Time is from the first point.")
    }

    /** The preference key of the synchronization of these jumps. */
    private fun syncKey() = "sync:" + files.joinToString("|") { it.path }

    /** Open the synchronization page above the track viewer. */
    private fun showSync() {
        if (loaded.size < 2 || syncPage != null) return
        playback.stop()
        openSyncPage(sync)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, syncBack)
    }

    private fun openSyncPage(value: TrackSync) {
        val series = TrackChartView.Series.entries.filter { chart.isVisible(it) }.toSet()
        val page = SyncPage(ui, loaded, labels, loaded.indices.map { chart.trackColor(it) }, value, series) { closeSync() }
        syncPage = page
        frame.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        page.requestApplyInsets()
    }

    /** Close the synchronization page, and show its synchronization in the track viewer. */
    private fun closeSync() {
        val page = syncPage ?: return
        syncPage = null
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(syncBack)
        frame.removeView(page)
        setSync(page.value)
    }

    /** Use a new synchronization. The views keep their position, the cursor and the replay time. */
    private fun setSync(value: TrackSync) {
        if (value == sync) return
        sync = value
        if (value.isDefault) prefs.edit().remove(syncKey()).apply() else prefs.edit().putString(syncKey(), value.encode()).apply()
        tracks = value.align(loaded)
        chart.updateTimes(tracks)
        map.updateTimes(tracks)
        map.setCursor(chart.cursor)
        pane3d.view.updateTimes(tracks)
        playback.setTracks(tracks, keepTime = true)
        updateSyncButton()
    }

    /** The description of the sync button tells the synchronization now. */
    private fun updateSyncButton() {
        syncButton.contentDescription = "Synchronize jumps. Now: ${sync.mode.label}" + if (sync.hasOffsets) ", with offsets" else ""
        syncButton.tooltipText = "Synchronize jumps"
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
        seriesRow = ui.chipRow(*seriesChips.toTypedArray()).also { controls.addView(it) }

        syncButton.visibility = if (multi) View.VISIBLE else View.GONE
        updateSyncButton()
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
                    pane3d.view.setTrackVisible(i, on)
                }.apply { checked = true }
            }
            controls.addView(ui.chipRow(*trackChips.toTypedArray()))
        }
        updateControls()
    }

    private fun showDetails() {
        if (tracks.isEmpty()) return
        val content = ui.column().apply { setPadding(ui.dp(24), 0, ui.dp(24), 0) }
        for ((i, track) in loaded.withIndex()) {
            if (loaded.size > 1) {
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
    class LineSample(ui: Ui, color: Int, dash: android.graphics.DashPathEffect?) : Drawable() {
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
        private const val PREF_3D = "map_3d"
        private const val GOOGLE_EARTH = "com.google.earth"

        fun open(context: Context, files: List<File>) {
            context.startActivity(Intent(context, TrackActivity::class.java).putExtra(EXTRA_FILES, files.map { it.path }.toTypedArray()))
        }

        fun open(context: Context, vararg files: File) = open(context, files.toList())
    }
}
