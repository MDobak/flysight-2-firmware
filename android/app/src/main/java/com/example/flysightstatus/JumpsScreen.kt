package com.example.flysightstatus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.io.File
import java.util.Locale

/**
 * The jumps on the phone and on the FlySight. Select 2 or more jumps on the
 * phone to compare them.
 */
@SuppressLint("SetTextI18n") // English only
class JumpsScreen(main: MainActivity) : Screen(main) {

    private val bar = ui.TopBar("Jumps")
    private val segmented = ui.Segmented(listOf("On this phone", "On FlySight"), 0) { showSegment(it) }
    private val phoneList = ui.column().apply { setPadding(0, 0, 0, ui.dp(96)) }
    private val remoteList = ui.column().apply { setPadding(0, 0, 0, ui.dp(24)) }
    private val phoneScroll: ScrollView = ui.scroll(phoneList, bar)
    private val remoteScroll: ScrollView = ui.scroll(remoteList, bar)
    private val progress = ui.linearProgress()
    private val fab = ui.fab("Compare", R.drawable.ic_compare) { compareSelected() }

    private val selectButton = ui.iconButton(R.drawable.ic_compare, "Select jumps to compare") { startSelection(null) }
    private val importButton = ui.iconButton(R.drawable.ic_add, "Import a CSV file") { pickCsv() }
    private val refreshButton = ui.iconButton(R.drawable.ic_refresh, "Read the FlySight again") { findRemote() }
    private val deleteButton = ui.iconButton(R.drawable.ic_delete, "Delete the selected jumps") { confirmDelete() }

    private var segment = 0
    private var selecting = false
    private val selected = linkedSetOf<String>()

    private val sessions get() = main.sync.known
    private val finding get() = main.sync.finding
    private var failed = false
    private var shownSessions: List<JumpSync.Session>? = null
    private var remoteError: String? = null
    private var wasConnected = false

    override val view: View

    init {
        bar.addAction(selectButton)
        bar.addAction(importButton)
        bar.addAction(refreshButton)
        bar.addAction(deleteButton)

        val panes = FrameLayout(main).apply {
            addView(phoneScroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(remoteScroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, ui.dp(16), ui.dp(16))
            })
        }
        view = ui.column().apply {
            addView(bar)
            addView(segmented, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(4)) })
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(panes, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        showSegment(0)
    }

    override fun onShow() {
        if (segment == 0) updatePhone() else updateRemote()
        if (segment == 1 && main.connected && sessions == null && !finding && !failed) findRemote()
    }

    override fun onStateChanged() {
        val connected = main.connected
        if (connected != wasConnected) {
            wasConnected = connected
            if (!connected) {
                failed = false
                remoteError = null
            }
            if (segment == 1) {
                updateRemote()
                if (connected && main.currentTab == MainActivity.TAB_JUMPS && sessions == null && !finding && !failed) findRemote()
            }
        } else if (segment == 1 && sessions !== shownSessions) {
            updateRemote()
        }
        updateProgress()
    }

    override fun onBack(): Boolean {
        if (!selecting) return false
        endSelection()
        return true
    }

    private fun showSegment(index: Int) {
        if (selecting) endSelection()
        segment = index
        segmented.select(index)
        phoneScroll.visibility = if (index == 0) View.VISIBLE else View.GONE
        remoteScroll.visibility = if (index == 1) View.VISIBLE else View.GONE
        updateBar()
        onShow()
    }

    private fun updateBar() {
        selectButton.visibility = if (segment == 0 && !selecting) View.VISIBLE else View.GONE
        importButton.visibility = selectButton.visibility
        refreshButton.visibility = if (segment == 1 && !selecting && main.connected) View.VISIBLE else View.GONE
        deleteButton.visibility = if (selecting && selected.isNotEmpty()) View.VISIBLE else View.GONE
        if (selecting) {
            bar.title.text = if (selected.isEmpty()) "Select jumps" else "${selected.size} selected"
            bar.setNavigation(R.drawable.ic_close, "Stop selection") { endSelection() }
        } else {
            bar.title.text = "Jumps"
            bar.clearNavigation()
        }
        fab.visibility = if (selecting && selected.size >= 2) View.VISIBLE else View.GONE
        fab.text = if (selected.size >= 2) "Compare ${selected.size}" else "Compare"
    }

    private fun updateProgress() {
        val sync = main.sync
        val p = sync.progress
        progress.visibility = if (segment == 1 && (sync.running || finding)) View.VISIBLE else View.INVISIBLE
        if (p != null && p.size > 0) {
            progress.isIndeterminate = false
            progress.progress = (((p.index + p.done.toDouble() / p.size) / p.count) * 1000).toInt()
        } else {
            progress.isIndeterminate = true
        }
    }

    // ---- On this phone ----

    private fun updatePhone() {
        phoneList.removeAllViews()
        val files = TrackStore.list(main)
        selected.retainAll(files.map { it.path }.toSet())
        if (files.isEmpty()) {
            phoneList.addView(ui.emptyState(R.drawable.ic_jumps, "No jumps on this phone",
                "Get jumps from the FlySight on the Home screen, or import a TRACK.CSV file.",
                ui.button(Ui.ButtonStyle.TONAL, "Import a CSV file", R.drawable.ic_add) { pickCsv() }))
            updateBar()
            return
        }
        var group: String? = null
        for (file in files) {
            val date = TrackStore.dateOf(file)
            val title = date?.let { dayTitle(it) } ?: "Imported"
            if (title != group) {
                group = title
                phoneList.addView(ui.header(title))
            }
            val headline = if (TrackStore.isSession(file)) TrackStore.shortLabel(file, withDate = false) else file.nameWithoutExtension
            val item = HomeScreen.jumpItem(ui, file, headline)
            if (selecting) item.setLeading(checkIcon(file.path in selected))
            item.onClick {
                if (selecting) toggle(file, item) else TrackActivity.open(main, file)
            }.onLongClick {
                if (selecting) toggle(file, item) else startSelection(file)
            }
            phoneList.addView(item)
        }
        phoneList.addView(ui.note("Hold a jump to select it. Select 2 or more jumps to compare them."))
        updateBar()
    }

    private fun checkIcon(on: Boolean) = ui.icon(
        if (on) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off,
        if (on) ui.primary else ui.onSurfaceVariant,
    ).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
    }

    private fun startSelection(first: File?) {
        selecting = true
        selected.clear()
        first?.let { selected += it.path }
        updatePhone()
    }

    private fun endSelection() {
        selecting = false
        selected.clear()
        updatePhone()
    }

    private fun toggle(file: File, item: Ui.ListItem) {
        if (file.path in selected) {
            selected -= file.path
        } else {
            if (selected.size >= MAX_COMPARE) return main.snack("You can compare a maximum of $MAX_COMPARE jumps.")
            selected += file.path
        }
        item.setLeading(checkIcon(file.path in selected))
        updateBar()
    }

    private fun compareSelected() {
        val files = TrackStore.list(main).filter { it.path in selected }.reversed()
        if (files.size < 2) return
        TrackActivity.open(main, files)
    }

    private fun confirmDelete() {
        val count = selected.size
        if (count == 0) return
        val what = if (count == 1) "this jump" else "$count jumps"
        main.confirm("Delete $what?", "This deletes the copies on this phone. The files on the FlySight do not change.", "Delete") {
            for (path in selected) TrackStore.delete(File(path))
            endSelection()
            main.snack(if (count == 1) "Deleted 1 jump." else "Deleted $count jumps.")
        }
    }

    private fun pickCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        main.startActivityForResult(intent, MainActivity.REQUEST_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != MainActivity.REQUEST_IMPORT || resultCode != Activity.RESULT_OK || uri == null) return
        Thread {
            val result = runCatching {
                val bytes = main.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot open the file")
                val name = main.displayName(uri)?.substringBeforeLast('.') ?: "import"
                // Parse first, so that only usable files are saved.
                val track = Track.parse(name, bytes.toString(Charsets.ISO_8859_1))
                TrackStore.saveParsed(main, name, bytes, track)
            }
            main.runOnUiThread {
                if (main.isDestroyed) return@runOnUiThread
                result.onSuccess {
                    if (segment != 0) showSegment(0) else updatePhone()
                    TrackActivity.open(main, it)
                }.onFailure { main.snack("Cannot import the file: ${it.message}", long = true) }
            }
        }.start()
    }

    // ---- On FlySight ----

    private fun findRemote() {
        if (main.idleFiles() == null) return
        remoteError = null
        failed = false
        main.sync.findAll(found = { updateRemote() }) { result ->
            result.onFailure {
                remoteError = if (it.message == "Cancelled") null else main.fileError("Read the FlySight", it)
                failed = true
            }
            updateRemote()
            updateProgress()
        }
    }

    private fun updateRemote() {
        remoteList.removeAllViews()
        updateBar()
        if (!main.connected) {
            remoteList.addView(ui.emptyState(R.drawable.ic_bluetooth, "Not connected",
                "Connect to the FlySight to see the jumps on it.",
                ui.button(Ui.ButtonStyle.TONAL, "Connect", R.drawable.ic_bluetooth) { main.connectOrScan() }))
            return
        }
        remoteError?.let {
            remoteList.addView(ui.text(Ui.Type.BODY_MEDIUM, it, ui.onErrorContainer).apply {
                background = ui.shape(ui.errorContainer, 12f)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) })
        }
        val list = sessions ?: if (failed) emptyList() else null
        shownSessions = sessions
        if (list == null || (list.isEmpty() && finding)) {
            remoteList.addView(ui.note("Reading the FlySight…").apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0) })
            return
        }
        if (list.isEmpty()) {
            if (remoteError == null) remoteList.addView(ui.emptyState(R.drawable.ic_jumps, "No jumps on the FlySight",
                "The FlySight makes a session folder each time that it records."))
            return
        }
        var group: String? = null
        for (session in list) {
            val title = session.date?.let { dayTitle(it) } ?: "No date"
            if (title != group) {
                group = title
                remoteList.addView(ui.header(title))
            }
            val saved = session.localFile(main).exists()
            val item = ui.ListItem(
                session.time ?: session.path.trimStart('/'),
                if (saved) "On this phone" else "Tap to download",
                ui.avatar(R.drawable.ic_folder),
                ui.icon(if (saved) R.drawable.ic_check else R.drawable.ic_download, if (saved) ui.primary else ui.onSurfaceVariant).apply {
                    layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                },
            )
            item.onClick { openRemote(session) }
            remoteList.addView(item)
        }
    }

    /** Open a session. Download it first if it is not on the phone. */
    fun openRemote(session: JumpSync.Session) {
        val local = session.localFile(main)
        if (local.exists()) return TrackActivity.open(main, local)
        if (main.idleFiles() == null) return
        main.sync.download(listOf(session)) { result ->
            updateRemote()
            result.onSuccess { files ->
                if (files.isEmpty()) main.snack("This session has no usable track.")
                else TrackActivity.open(main, files[0])
            }.onFailure {
                if (it.message == "Cancelled") main.snack("Stopped.") else main.snack(main.fileError("Download", it), long = true)
            }
        }
        updateProgress()
    }

    companion object {
        private const val MAX_COMPARE = 8
        private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December")

        /** "7 November 2026" from "26-11-07". */
        fun dayTitle(date: String): String {
            val parts = date.split('-')
            if (parts.size != 3) return date
            val month = MONTHS.getOrNull(parts[1].toInt() - 1) ?: parts[1]
            return String.format(Locale.US, "%d %s 20%s", parts[2].toInt(), month, parts[0])
        }
    }
}
