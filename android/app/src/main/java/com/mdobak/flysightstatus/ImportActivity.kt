package com.mdobak.flysightstatus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Receives track files from other apps ("Open with" and the share sheet) and
 * from the import on the Jumps screen. It saves the new jumps, finds the jumps
 * of the same load on this phone, and asks for the names of the FlySight owners.
 */
@SuppressLint("SetTextI18n") // English only
class ImportActivity : Activity() {

    /** One received file. [file] is null if the app cannot read the file. */
    private class Received(val name: String, val file: File?, val isNew: Boolean, val error: String?)

    private lateinit var ui: Ui
    private lateinit var bar: Ui.TopBar
    private lateinit var progress: ProgressBar
    private lateinit var content: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var snackbars: Ui.SnackbarHost

    private var received: List<Received>? = null
    /** Jumps on this phone at the same time as a received jump. */
    private var near: List<File> = emptyList()
    private val checked = linkedSetOf<String>()
    /** The FlySights that had no owner name when the files came. */
    private var askOwners: List<String> = emptyList()
    private val rows = mutableMapOf<String, Ui.ListItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrackStore.init(this)
        ui = Ui(this)
        ui.setUpWindow(this)

        bar = ui.TopBar("Received jumps")
        bar.setNavigation(R.drawable.ic_close, "Close") { finish() }
        progress = ui.linearProgress().apply { isIndeterminate = true }
        content = ui.column().apply { setPadding(0, 0, 0, ui.dp(16)) }
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setBackgroundColor(ui.surfaceContainer)
            visibility = View.GONE
        }
        val root = ui.column().apply {
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(ui.scroll(content, bar), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val frame = FrameLayout(this).apply { addView(root) }
        snackbars = ui.SnackbarHost(frame)
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            root.setPadding(sys.left, 0, sys.right, 0)
            // The buttons stay above the keyboard
            actions.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12) + maxOf(sys.bottom, ime.bottom))
            snackbars.bottomMarginPx = maxOf(sys.bottom, ime.bottom) + actions.height
            insets
        }
        setContentView(frame)

        if (savedInstanceState != null && restore(savedInstanceState)) return show()
        val uris = uris(intent)
        if (uris.isEmpty()) {
            content.addView(ui.emptyState(R.drawable.ic_error, "No files", "The other app did not send a file."))
            progress.visibility = View.GONE
            return
        }
        content.addView(ui.note(if (uris.size == 1) "Reading the file…" else "Reading ${uris.size} files…").apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0)
        })
        Thread {
            val list = uris.map { receive(it) }
            val nearList = findNear(list.mapNotNull { it.file })
            val own = TrackStore.ownDevices(this)
            val ask = list.mapNotNull { it.file?.let { f -> TrackStore.deviceId(f) } }.distinct()
                .filter { it !in own && TrackStore.deviceOwner(it) == null }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                received = list
                near = nearList
                checked.clear()
                checked += nearList.map { it.path }
                askOwners = ask
                show()
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val list = received ?: return
        outState.putStringArray(STATE_NAMES, list.map { it.name }.toTypedArray())
        outState.putStringArray(STATE_FILES, list.map { it.file?.path ?: "" }.toTypedArray())
        outState.putBooleanArray(STATE_NEW, list.map { it.isNew }.toBooleanArray())
        outState.putStringArray(STATE_ERRORS, list.map { it.error ?: "" }.toTypedArray())
        outState.putStringArray(STATE_NEAR, near.map { it.path }.toTypedArray())
        outState.putStringArray(STATE_CHECKED, checked.toTypedArray())
        outState.putStringArray(STATE_OWNERS, askOwners.toTypedArray())
    }

    /** The results after a restart of the activity. The files were read before. */
    private fun restore(state: Bundle): Boolean {
        val names = state.getStringArray(STATE_NAMES) ?: return false
        val files = state.getStringArray(STATE_FILES) ?: return false
        val isNew = state.getBooleanArray(STATE_NEW) ?: return false
        val errors = state.getStringArray(STATE_ERRORS) ?: return false
        received = names.indices.map { i ->
            Received(names[i], files[i].ifEmpty { null }?.let { File(it) }?.takeIf { it.exists() }, isNew[i], errors[i].ifEmpty { null })
        }
        near = state.getStringArray(STATE_NEAR).orEmpty().map { File(it) }.filter { it.exists() }
        checked.clear()
        checked += state.getStringArray(STATE_CHECKED).orEmpty()
        askOwners = state.getStringArray(STATE_OWNERS).orEmpty().toList()
        return true
    }

    // ---- Receive ----

    /** The URIs in an intent: "Open with", "Share to" (one or more files), or the import of the app. */
    private fun uris(intent: Intent): List<Uri> {
        val list = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { list += it }
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { list += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { list += it }
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { list += it } }
        return list.distinct()
    }

    /** Read one file. Save it if it is a new track. Call on a background thread. */
    private fun receive(uri: Uri): Received {
        val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
        return try {
            val bytes = read(uri)
            val track = Track.parse(name, bytes.toString(Charsets.ISO_8859_1))
            val header = TrackHeader.parse(String(bytes, 0, minOf(bytes.size, 32 * 1024), Charsets.ISO_8859_1))
            TrackStore.findSame(this, header)?.let { return Received(name, it, isNew = false, error = null) }
            val file = TrackStore.saveReceived(this, bytes, track, header)
            TrackStore.setCustomName(file, JumpSharing.nameFromFile(name))
            Received(name, file, isNew = true, error = null)
        } catch (_: OutOfMemoryError) {
            Received(name, null, false, "The file is too large.")
        } catch (e: Exception) {
            Received(name, null, false, when (e) {
                is IllegalArgumentException -> "This is not a FlySight track. ${e.message}."
                else -> "Cannot read the file: ${e.message}"
            })
        }
    }

    private fun read(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("The other app did not give the file")
        return input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_SIZE) throw IllegalStateException("the file is larger than ${MAX_SIZE / 1024 / 1024} MB")
            }
            out.toByteArray()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Jumps on this phone with an exit near the exit of a received jump: the
     * jumps of the same load. Call on a background thread.
     */
    private fun findNear(files: List<File>): List<File> {
        val exits = files.mapNotNull { TrackStore.meta(it)?.exitMs }
        if (exits.isEmpty()) return emptyList()
        val paths = files.map { it.path }.toSet()
        return TrackStore.list(this).filter { f ->
            if (f.path in paths) return@filter false
            // The header is fast to read. Read the track only if the times are near.
            val first = TrackStore.header(f).firstMs ?: return@filter false
            if (exits.none { abs(it - first) < SEARCH_MS }) return@filter false
            val exit = TrackStore.meta(f)?.exitMs ?: return@filter false
            exits.any { abs(it - exit) < SAME_LOAD_MS }
        }.sortedBy { TrackStore.sortKey(it) }
    }

    // ---- Show ----

    private fun show() {
        val list = received ?: return
        progress.visibility = View.GONE
        content.removeAllViews()
        rows.clear()
        val good = list.filter { it.file != null }
        val added = list.count { it.isNew }
        val known = good.size - added
        val failed = list.size - good.size

        bar.title.text = when {
            added == 0 && known == 0 -> "Nothing received"
            added == 0 -> "Already on this phone"
            else -> if (added == 1) "1 new jump" else "$added new jumps"
        }

        if (good.isEmpty()) {
            content.addView(ui.emptyState(R.drawable.ic_error, if (list.size == 1) "Cannot read the file" else "Cannot read the files",
                "The app reads TRACK.CSV files from FlySight 2, and CSV files from FlySight 1."))
            for (r in list) content.addView(item(r))
            updateActions()
            return
        }

        content.addView(ui.text(Ui.Type.BODY_MEDIUM, summary(added, known, failed), ui.onSurfaceVariant).apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4))
        })
        content.addView(ui.header(if (good.size == 1 && failed == 0) "Jump" else "Received"))
        for (r in list) content.addView(item(r))

        if (askOwners.isNotEmpty()) {
            content.addView(ui.header(if (askOwners.size == 1) "Whose FlySight is it?" else "Whose FlySights are they?"))
            content.addView(ui.note("Give a name to each FlySight one time. The app adds it to these jumps, and to the jumps from that FlySight that you get later."))
            for (id in askOwners) content.addView(ownerRow(id, good.mapNotNull { it.file }.filter { TrackStore.deviceId(it) == id }))
        }

        if (near.isNotEmpty()) {
            content.addView(ui.header("Same load on this phone"))
            content.addView(ui.note("These jumps have an exit at almost the same time. Select the jumps to compare."))
            val own = TrackStore.ownDevices(this)
            for (f in near) {
                val row = HomeScreen.jumpItem(ui, f, TrackStore.title(f), who = TrackStore.who(f, own))
                row.setLeading(checkIcon(f.path in checked))
                row.onClick {
                    if (f.path in checked) checked -= f.path else checked += f.path
                    row.setLeading(checkIcon(f.path in checked))
                    updateActions()
                }
                content.addView(row)
            }
        }
        updateActions()
    }

    private fun summary(added: Int, known: Int, failed: Int) = buildList {
        if (added > 0) add(if (added == 1) "The jump is now on this phone." else "The $added jumps are now on this phone.")
        if (known > 0) add(if (known == 1) "1 jump was already on this phone." else "$known jumps were already on this phone.")
        if (failed > 0) add(if (failed == 1) "1 file is not a track." else "$failed files are not tracks.")
        add("Tap a jump to give it a name.")
    }.joinToString(" ")

    private fun item(r: Received): View {
        val file = r.file
        if (file == null) {
            return ui.ListItem(r.name, r.error, ui.avatar(R.drawable.ic_error, ui.errorContainer, ui.onErrorContainer))
        }
        val badge = if (!r.isNew) null else ui.text(Ui.Type.LABEL_MEDIUM, "New", ui.onTertiaryContainer).apply {
            background = ui.shape(ui.tertiaryContainer, 8f)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(8) }
        }
        val avatar = if (r.isNew) ui.avatar(R.drawable.ic_jumps, ui.primaryContainer, ui.onPrimaryContainer)
        else ui.avatar(R.drawable.ic_check)
        val row = ui.ListItem(TrackStore.title(file), "…", avatar, badge)
        rows[file.path] = row
        updateRow(r)
        row.onClick { JumpSharing.editNames(ui, file) { show() } }
        return row
    }

    /** The second line of a received jump: the owner, the state and the key values. */
    private fun updateRow(r: Received) {
        val file = r.file ?: return
        val row = rows[file.path] ?: return
        TrackStore.loadMeta(file) { meta ->
            row.setSupporting(listOfNotNull(
                TrackStore.owner(file),
                if (r.isNew) null else "Already on this phone",
                meta?.summary() ?: "Cannot read this track",
            ).joinToString(" · "))
        }
    }

    /** A text field for the name of the owner of a FlySight. The app saves the name at each change. */
    private fun ownerRow(id: String, files: List<File>): View {
        val withDate = files.map { TrackStore.dateOf(it) }.distinct().size > 1
        val times = files.joinToString(", ") { TrackStore.timeLabel(it, withDate) }
        val field = ui.textField(TrackStore.deviceOwner(id) ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply {
            hint = "Owner name"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // The default for new jumps from this FlySight, and the owner of the received jumps
                    TrackStore.setDeviceOwner(id, s?.toString())
                    received?.filter { it.file != null && TrackStore.deviceId(it.file) == id }?.forEach {
                        TrackStore.setOwner(it.file!!, s?.toString())
                        updateRow(it)
                    }
                }
            })
        }
        val texts = ui.column().apply {
            addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(ui.text(Ui.Type.BODY_SMALL, (if (files.size == 1) "The jump at " else "${files.size} jumps: ") + times,
                ui.onSurfaceVariant).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(ui.dp(16), ui.dp(8), ui.dp(24), ui.dp(8))
            addView(ui.avatar(R.drawable.ic_person, ui.tertiaryContainer, ui.onTertiaryContainer).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply {
                    marginEnd = ui.dp(16)
                    topMargin = ui.dp(8)
                }
            })
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun checkIcon(on: Boolean) = ui.icon(
        if (on) R.drawable.ic_checkbox_on else R.drawable.ic_checkbox_off,
        if (on) ui.primary else ui.onSurfaceVariant,
    ).apply {
        scaleType = ImageView.ScaleType.CENTER
        layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
    }

    /** The jumps to open: the selected jumps of the same load first, then the received jumps. */
    private fun toOpen(): List<File> =
        near.filter { it.path in checked } + received.orEmpty().mapNotNull { it.file }.distinct()

    private fun updateActions() {
        actions.removeAllViews()
        actions.visibility = View.VISIBLE
        val files = toOpen()
        actions.addView(ui.button(Ui.ButtonStyle.TEXT, "All jumps") { showAllJumps() })
        actions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val primary = when {
            files.isEmpty() -> ui.button(Ui.ButtonStyle.FILLED, "Close") { finish() }
            files.size == 1 -> ui.button(Ui.ButtonStyle.FILLED, "Open jump") { open(files) }
            files.size > MAX_COMPARE -> ui.button(Ui.ButtonStyle.FILLED, "Compare") {
                snackbars.show("You can compare a maximum of $MAX_COMPARE jumps. Select fewer jumps, or compare them on the Jumps screen.")
            }
            else -> ui.button(Ui.ButtonStyle.FILLED, "Compare ${files.size} jumps", R.drawable.ic_compare) { open(files) }
        }
        actions.addView(primary)
        actions.requestApplyInsets()
    }

    private fun open(files: List<File>) {
        TrackActivity.open(this, files)
        finish()
    }

    private fun showAllJumps() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_JUMPS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        private const val MAX_SIZE = 64 * 1024 * 1024
        private const val MAX_COMPARE = 8
        /** The exits of one load are less than 5 minutes apart. */
        private const val SAME_LOAD_MS = 5 * 60_000L
        /** A recording starts less than 3 hours before the exit. */
        private const val SEARCH_MS = 3 * 3600_000L

        private const val STATE_NAMES = "names"
        private const val STATE_FILES = "files"
        private const val STATE_NEW = "new"
        private const val STATE_ERRORS = "errors"
        private const val STATE_NEAR = "near"
        private const val STATE_CHECKED = "checked"
        private const val STATE_OWNERS = "owners"

        /** Import files that the user selected in the app. */
        fun start(context: Context, uris: List<Uri>) {
            if (uris.isEmpty()) return
            val intent = Intent(context, ImportActivity::class.java).setAction(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }
    }
}
