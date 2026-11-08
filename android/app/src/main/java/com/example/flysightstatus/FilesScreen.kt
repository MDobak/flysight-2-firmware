package com.example.flysightstatus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.flysightstatus.Ui.Companion.MATCH
import com.example.flysightstatus.Ui.Companion.WRAP
import java.util.Locale
import com.example.flysightstatus.FlySightProtocol as P

/** The file browser for the microSD card of the FlySight. */
@SuppressLint("SetTextI18n") // English only
class FilesScreen(main: MainActivity) : Screen(main) {

    private val bar = ui.TopBar("Files")
    private val connectButton = ui.button(Ui.ButtonStyle.TEXT, "Connect") {
        if (main.connection != null) main.disconnect() else main.connectOrScan()
    }
    private var currentDir = "/"
    private var listedOnce = false
    private var wasConnected = false
    private val pathText = ui.text(Ui.Type.TITLE_SMALL, "/").apply { typeface = Typeface.MONOSPACE }
    private val fileList = ui.column()
    private val fileProgress = ui.linearProgress()
    private val fileProgressText = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant)
    private val fileProgressRow = LinearLayout(main)
    private val fileMessage = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant)
    private var pendingSave: ByteArray? = null
    private var pendingReplacePath: String? = null

    override val view: View

    init {
        bar.addAction(connectButton)
        view = ui.column().apply {
            addView(bar)
            addView(buildFiles(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        updateConnection()
    }

    override fun onShow() {
        updateConnection()
        if (!listedOnce && main.connected) listDir(currentDir)
    }

    override fun onStateChanged() {
        val connected = main.connected
        if (connected != wasConnected) {
            wasConnected = connected
            if (connected) {
                if (main.currentTab == MainActivity.TAB_FILES) listDir(currentDir)
            } else {
                clear()
            }
        }
        updateConnection()
    }

    private fun updateConnection() {
        connectButton.text = if (main.connection != null) "Disconnect" else "Connect"
        if (main.connection == null && !listedOnce) showFileMessage("Connect to the FlySight to see its files.")
    }

    private fun clear() {
        currentDir = "/"
        pathText.text = currentDir
        listedOnce = false
        fileList.removeAllViews()
        showFileMessage(null)
        setFileIdle()
    }

    private fun buildFiles(): View {
        val toolbar = LinearLayout(main).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
            addView(ui.iconButton(R.drawable.ic_arrow_up, "Parent folder") { if (currentDir != "/") listDir(parentOf(currentDir)) })
            addView(pathText, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ui.dp(4) })
            addView(ui.iconButton(R.drawable.ic_refresh, "Read the folder again") { listDir(currentDir) })
            addView(ui.iconButton(R.drawable.ic_new_folder, "New folder") { askNewFolder() })
            addView(ui.iconButton(R.drawable.ic_upload, "Upload") { askUpload() })
        }
        fileProgressRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(4), 0)
            addView(fileProgressText, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(ui.button(Ui.ButtonStyle.TEXT, "Cancel") { main.files?.cancel() })
        }
        fileMessage.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        val list = ui.column().apply {
            addView(fileMessage)
            addView(fileList)
            addView(ui.note("Files are available in idle mode only. Tap a file to see the actions. Hold an item to delete it."))
        }
        setFileIdle()
        return ui.column().apply {
            addView(toolbar)
            addView(fileProgress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(fileProgressRow)
            addView(ui.scroll(list, bar), LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    private fun join(dir: String, name: String) = JumpSync.join(dir, name)

    private fun parentOf(path: String): String {
        val i = path.trimEnd('/').lastIndexOf('/')
        return if (i <= 0) "/" else path.substring(0, i)
    }

    private fun listDir(path: String) {
        val f = main.idleFiles() ?: return
        setFileBusy("Reading $path…", indeterminate = true)
        f.list(path) { result ->
            setFileIdle()
            result.onSuccess { entries ->
                listedOnce = true
                currentDir = path
                pathText.text = path
                fileList.removeAllViews()
                val visible = entries.filter { it.name != "." && it.name != ".." }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                for (info in visible) {
                    val item = ui.ListItem(
                        if (info.isDirectory) info.name else info.name,
                        if (info.isDirectory) null else "${FileInfo.formatSize(info.size)} · ${info.modified}",
                        ui.avatar(if (info.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                    )
                    item.onClick { onFileSelected(info) }.onLongClick { confirmDelete(info) }
                    fileList.addView(item)
                }
                showFileMessage(if (visible.isEmpty()) "The folder is empty." else null)
                main.log("FT: listed $path (${entries.size} items)")
            }.onFailure { showFileMessage(main.fileError("Read $path", it)) }
        }
    }

    private fun onFileSelected(info: FileInfo) {
        val path = join(currentDir, info.name)
        if (info.isDirectory) return listDir(path)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (info.size <= MAX_EDIT_SIZE) actions += "Edit as text" to { readFile(path, info.size) { showEditor(path, it) } }
        if (info.name.equals("TRACK.CSV", ignoreCase = true)) {
            actions += "Show in the track viewer" to { readFile(path, info.size) { openTrack(path, it) } }
        }
        actions += "Save on the phone" to { readFile(path, info.size) { saveToPhone(info.name, it) } }
        actions += "Replace with a file from the phone" to {
            pendingReplacePath = path
            pickFile(MainActivity.REQUEST_REPLACE)
        }
        actions += "Delete" to { confirmDelete(info) }
        ui.menuDialog(info.name, actions, "$path · ${FileInfo.formatSize(info.size)}").show()
    }

    private fun readFile(path: String, size: Long, then: (ByteArray) -> Unit) {
        val f = main.idleFiles() ?: return
        val started = System.currentTimeMillis()
        setFileBusy("Download $path…", indeterminate = size <= 0)
        f.read(path, progress = { n -> showProgress(n.toLong(), size, "Download") }) { result ->
            setFileIdle()
            result.onSuccess { data ->
                main.log("FT: read $path, ${data.size} bytes, ${rate(data.size, started)}")
                then(data)
            }.onFailure { showFileMessage(main.fileError("Read $path", it)) }
        }
    }

    private fun writeFile(path: String, data: ByteArray, then: () -> Unit = { listDir(currentDir) }) {
        val f = main.idleFiles() ?: return
        val started = System.currentTimeMillis()
        setFileBusy("Upload $path…", indeterminate = false)
        f.write(path, data, progress = { sent, total -> showProgress(sent.toLong(), total.toLong(), "Upload") }) { result ->
            setFileIdle()
            result.onSuccess {
                main.log("FT: wrote $path, ${data.size} bytes, ${rate(data.size, started)}")
                main.snack("Saved $path.")
                then()
            }.onFailure { showFileMessage(main.fileError("Write $path", it)) }
        }
    }

    private fun confirmDelete(info: FileInfo) {
        val path = join(currentDir, info.name)
        if (info.isDirectory) {
            main.confirm("Delete ${info.name}?", "The folder and all its files and folders are deleted. You cannot undo this.", "Delete") {
                deleteFolder(path)
            }
            return
        }
        main.confirm("Delete ${info.name}?", "You cannot undo this.", "Delete") {
            val f = main.idleFiles() ?: return@confirm
            f.delete(path) { result ->
                result.onSuccess { main.log("FT: deleted $path"); listDir(currentDir) }
                    .onFailure { showFileMessage(main.fileError("Delete $path", it)) }
            }
        }
    }

    /**
     * Delete the folder [root] and all its content. The FlySight deletes only
     * empty folders, so the app first reads the tree. Then it deletes the
     * content of each folder before the folder.
     */
    private fun deleteFolder(root: String) {
        if (main.idleFiles() == null) return
        val folders = ArrayDeque(listOf(root))
        val order = mutableListOf<String>()
        var index = 0

        fun stop(text: String) {
            setFileIdle()
            listDir(currentDir)
            showFileMessage(text)
        }

        fun files(): FileTransfer? {
            val f = main.files
            if (f != null && main.connected) return f
            stop("The delete stopped. The FlySight is not connected.")
            return null
        }

        fun deleteNext() {
            if (index >= order.size) {
                setFileIdle()
                main.log("FT: deleted $root (${order.size} items)")
                main.snack("Deleted $root.")
                return listDir(currentDir)
            }
            val path = order[index++]
            val f = files() ?: return
            fileProgress.progress = (index * 1000L / order.size).toInt()
            fileProgressText.text = "Delete $index of ${order.size}: $path"
            f.delete(path) { result ->
                result.onSuccess { deleteNext() }.onFailure {
                    if (it.message == "Cancelled" || main.files == null) return@delete stop("The delete stopped.")
                    stop(main.fileError("Delete $path", it) + " A read-only file cannot be deleted.")
                }
            }
        }

        fun scanNext() {
            val dir = folders.removeFirstOrNull()
            if (dir == null) {
                // A folder is after all its content in the reversed order.
                order.reverse()
                fileProgress.isIndeterminate = false
                return deleteNext()
            }
            order += dir
            val f = files() ?: return
            fileProgressText.text = "Read $dir…"
            f.list(dir) { result ->
                result.onSuccess { entries ->
                    for (e in entries) {
                        if (e.name == "." || e.name == ".." || e.name.isEmpty()) continue
                        val child = join(dir, e.name)
                        if (e.isDirectory) folders.addLast(child) else order += child
                    }
                    scanNext()
                }.onFailure { stop(main.fileError("Read $dir", it)) }
            }
        }

        setFileBusy("Read $root…", indeterminate = true)
        scanNext()
    }

    private fun askNewFolder() {
        ui.inputDialog("New folder", "", "In $currentDir. Maximum 8 characters.",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, okLabel = "Make") { name ->
            if (name.isEmpty()) return@inputDialog
            val f = main.idleFiles() ?: return@inputDialog
            val path = join(currentDir, name)
            f.mkdir(path) { result ->
                result.onSuccess { main.log("FT: made folder $path"); listDir(currentDir) }
                    .onFailure { showFileMessage(main.fileError("Make $path", it)) }
            }
        }.show()
    }

    private fun showEditor(path: String, data: ByteArray) {
        main.push(TextFileScreen(main, path, data.toString(Charsets.ISO_8859_1)) { text, done ->
            writeFile(path, text.toByteArray(Charsets.ISO_8859_1)) { done(); listDir(currentDir) }
        })
    }

    private fun openTrack(remotePath: String, data: ByteArray) {
        try {
            TrackActivity.open(main, TrackStore.save(main, TrackStore.localName(remotePath), data))
        } catch (e: Exception) {
            main.snack("Cannot save the track: ${e.message}")
        }
    }

    private fun setFileBusy(text: String, indeterminate: Boolean) {
        fileProgress.visibility = View.VISIBLE
        fileProgress.isIndeterminate = indeterminate
        fileProgress.progress = 0
        fileProgressRow.visibility = View.VISIBLE
        fileProgressText.text = text
        showFileMessage(null)
    }

    private fun setFileIdle() {
        fileProgress.visibility = View.INVISIBLE
        fileProgressRow.visibility = View.GONE
    }

    private fun showProgress(done: Long, total: Long, label: String) {
        if (total > 0) {
            fileProgress.isIndeterminate = false
            fileProgress.progress = (done * 1000 / total).toInt()
            fileProgressText.text = "$label: ${FileInfo.formatSize(done)} of ${FileInfo.formatSize(total)}"
        } else {
            fileProgressText.text = "$label: ${FileInfo.formatSize(done)}"
        }
    }

    private fun showFileMessage(text: String?) {
        fileMessage.text = text ?: ""
        fileMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun rate(bytes: Int, startedMs: Long): String {
        val seconds = (System.currentTimeMillis() - startedMs) / 1000.0
        return String.format(Locale.US, "%.1f s, %.1f KB/s", seconds, if (seconds > 0) bytes / 1024.0 / seconds else 0.0)
    }

    // ---- Phone files ----

    private fun pickFile(requestCode: Int) {
        if (main.idleFiles() == null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        main.startActivityForResult(intent, requestCode)
    }

    private fun saveToPhone(name: String, data: ByteArray) {
        pendingSave = data
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        @Suppress("DEPRECATION")
        main.startActivityForResult(intent, MainActivity.REQUEST_SAVE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode !in listOf(MainActivity.REQUEST_SAVE, MainActivity.REQUEST_UPLOAD, MainActivity.REQUEST_REPLACE,
                MainActivity.REQUEST_UPLOAD_FOLDER)) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            pendingSave = null
            return
        }
        try {
            when (requestCode) {
                MainActivity.REQUEST_SAVE -> {
                    val bytes = pendingSave ?: return
                    main.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    main.snack("Saved ${FileInfo.formatSize(bytes.size.toLong())} on the phone.")
                    pendingSave = null
                }
                MainActivity.REQUEST_UPLOAD -> {
                    val bytes = main.readUri(uri) ?: return
                    askUploadName(suggestShortName(main.displayName(uri) ?: "FILE.BIN"), bytes)
                }
                MainActivity.REQUEST_REPLACE -> {
                    val bytes = main.readUri(uri) ?: return
                    writeFile(pendingReplacePath ?: return, bytes)
                }
                MainActivity.REQUEST_UPLOAD_FOLDER -> scanFolder(uri)
            }
        } catch (e: Exception) {
            main.snack("Phone file error: ${e.message}")
            main.log("Phone file error: $e")
        }
    }

    private fun askUpload() {
        if (main.idleFiles() == null) return
        ui.menuDialog("Upload to $currentDir", listOf(
            "A file" to { pickFile(MainActivity.REQUEST_UPLOAD) },
            "A folder with all its files" to { pickFolder() },
        )).show()
    }

    private fun pickFolder() {
        if (main.idleFiles() == null) return
        @Suppress("DEPRECATION")
        main.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), MainActivity.REQUEST_UPLOAD_FOLDER)
    }

    /** One step of a folder upload. A folder has no [uri]. [path] is relative to the target folder. */
    private class UploadItem(val path: String, val uri: Uri?, val size: Long)

    /** Read the phone folder [tree] in the background, then ask where to upload it. */
    private fun scanFolder(tree: Uri) {
        setFileBusy("Reading the phone folder…", indeterminate = true)
        Thread {
            val result = runCatching { readTree(tree) }
            main.runOnUiThread {
                setFileIdle()
                result.onSuccess { (name, items) -> askFolderTarget(name, items) }
                    .onFailure { showFileMessage("Cannot read the phone folder: ${it.message}") }
            }
        }.start()
    }

    /** The short name of the selected folder, and its contents in upload order (a folder before its files). */
    private fun readTree(tree: Uri): Pair<String, List<UploadItem>> {
        val resolver = main.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "FOLDER"
        val items = mutableListOf<UploadItem>()

        fun walk(docId: String, dir: String) {
            class Child(val id: String, val name: String, val isDir: Boolean, val size: Long)
            val children = mutableListOf<Child>()
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    children += Child(c.getString(0), c.getString(1) ?: continue,
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3))
                }
            }
            val used = mutableMapOf<String, String>()
            for (child in children.sortedBy { it.name }) {
                if (child.name.startsWith(".")) continue // .DS_Store and other hidden files
                val short = suggestShortName(child.name)
                used.put(short, child.name)?.let {
                    throw IllegalStateException("\"$it\" and \"${child.name}\" in ${dir.ifEmpty { "/" }} both get the short name $short")
                }
                val path = if (dir.isEmpty()) short else "$dir/$short"
                if (child.isDir) {
                    items += UploadItem(path, null, 0)
                    walk(child.id, path)
                } else {
                    items += UploadItem(path, DocumentsContract.buildDocumentUriUsingTree(tree, child.id), child.size)
                }
            }
        }
        walk(rootId, "")
        return suggestShortName(rootName) to items
    }

    private fun askFolderTarget(name: String, items: List<UploadItem>) {
        val files = items.count { it.uri != null }
        if (files == 0) return showFileMessage("The phone folder has no files.")
        val size = FileInfo.formatSize(items.sumOf { it.size })
        val folders = items.count { it.uri == null }
        val inside = join(currentDir, name)
        ui.menuDialog("Upload $files files ($size)", listOf(
            "Make $inside and upload into it" to { uploadFolder(listOf(UploadItem("", null, 0)) + items, inside) },
            "Upload only the contents to $currentDir" to { uploadFolder(items, currentDir) },
        ), "The phone folder has $files files" + (if (folders > 0) " in $folders folders" else "") +
            ". Long names change to short names (8.3). Files with the same name are replaced.").show()
    }

    /** Make the folders and write the files of [items] into [target], one at a time. */
    private fun uploadFolder(items: List<UploadItem>, target: String) {
        if (main.idleFiles() == null) return
        val total = items.sumOf { it.size }
        val count = items.count { it.uri != null }
        val started = System.currentTimeMillis()
        var index = 0
        var file = 0
        var sent = 0L

        fun stop(text: String) {
            setFileIdle()
            listDir(currentDir)
            showFileMessage(text)
        }

        fun next() {
            if (index >= items.size) {
                setFileIdle()
                main.log("FT: uploaded $count files to $target, ${rate(sent.toInt(), started)}")
                main.snack("Uploaded $count files to $target.")
                return listDir(currentDir)
            }
            val item = items[index++]
            val path = if (item.path.isEmpty()) target else join(target, item.path)
            val f = main.files
            if (f == null || !main.connected) return stop("The upload stopped. The FlySight is not connected.")
            if (item.uri == null) {
                fileProgressText.text = "Make $path…"
                f.mkdir(path) { result ->
                    result.onFailure {
                        if (it.message == "Cancelled" || main.files == null) return@mkdir stop("The upload stopped.")
                        // The folder can exist already. A write into a missing folder shows the error.
                        main.log("FT: make $path: ${it.message}")
                    }
                    next()
                }
                return
            }
            val data = try {
                main.readUri(item.uri)
            } catch (e: Exception) {
                main.log("Phone file error: $e")
                null
            } ?: return stop("Cannot read ${item.path} on the phone.")
            file++
            val label = "Upload $file of $count"
            fileProgressText.text = "$label: $path"
            f.write(path, data, progress = { n, _ -> showProgress(sent + n, total, label) }) { result ->
                result.onSuccess {
                    sent += data.size
                    next()
                }.onFailure { stop(main.fileError("Write $path", it)) }
            }
        }

        setFileBusy("Upload…", indeterminate = false)
        next()
    }

    private fun askUploadName(suggested: String, data: ByteArray) {
        ui.inputDialog("Upload ${FileInfo.formatSize(data.size.toLong())} to $currentDir", suggested,
            "Use a short name: 8 characters, a dot, 3 characters. A file with the same name is replaced.",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, okLabel = "Upload") { name ->
            if (name.isNotEmpty()) writeFile(join(currentDir, name), data)
        }.show()
    }

    /** The FlySight file system has no long file names. Make an 8.3 name. */
    private fun suggestShortName(name: String): String {
        val clean = { s: String, n: Int -> s.uppercase(Locale.US).filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(n) }
        val dot = name.lastIndexOf('.')
        val base = clean(if (dot > 0) name.substring(0, dot) else name, 8).ifEmpty { "FILE" }
        val ext = if (dot > 0) clean(name.substring(dot + 1), 3) else ""
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** Write an APP.SFB file to /FW and offer to install it. */
    fun uploadFirmware(data: ByteArray) {
        if (data.size < 512 || data.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) != "SFU1") {
            ui.dialog("This is not an APP.SFB file", "An update file starts with \"SFU1\". Make it with Scripts/build.sh --batch.",
                positive = Ui.DialogButton("OK"), negative = null).show()
            return
        }
        val f = main.idleFiles() ?: return
        // The folder is usually there. A NAK only means that it exists.
        f.mkdir("/FW") { _ ->
            writeFile("/FW/APP.SFB", data) {
                listDir("/FW")
                main.confirm("Upload complete", "Install /FW/APP.SFB now?", "Install") { main.sendDs(P.DS_CMD_INSTALL_FIRMWARE) }
            }
        }
    }

    private companion object {
        const val MAX_EDIT_SIZE = 64 * 1024L
    }
}
