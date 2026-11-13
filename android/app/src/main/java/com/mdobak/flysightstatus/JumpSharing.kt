package com.mdobak.flysightstatus

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.io.File

/**
 * Send jumps to other people, and the names of jumps and FlySight owners.
 *
 * The app sends the original track file through the share sheet (Quick Share,
 * messengers, e-mail). The file name has the name of the jump and its date, so
 * the app on the other phone can show the same name.
 */
object JumpSharing {

    const val CSV_MIME = "text/csv"

    /** The track MIME types that the app can receive. They are also in the manifest. */
    val RECEIVE_MIME = arrayOf(CSV_MIME, "text/comma-separated-values", "text/x-csv", "text/x-comma-separated-values",
        "application/csv", "application/x-csv")

    private val NOT_IN_NAME = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private val COPY_NUMBER = Regex("""\s*\(\d+\)$""")
    private val DATE_TIME = Regex("""[\s_-]*\d{4}-\d\d-\d\d[\s_]\d\d-\d\d(-\d\d)?$""")
    private val GENERIC = Regex("""(?i)flysight|track|temp_?\d*|in_.*|\d\d-\d\d-\d\d_\d\d-\d\d-\d\d|""")

    /** Copy the jumps to the export folder and open the share sheet. */
    fun share(activity: Activity, files: List<File>, onError: (String) -> Unit) {
        if (files.isEmpty()) return
        Thread {
            val result = runCatching { export(activity, files) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                result.onSuccess { uris ->
                    val send = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                    send.type = CSV_MIME
                    // The clip gives the read access for all files, and the preview in the share sheet
                    send.clipData = ClipData(ClipDescription(title(files.size), arrayOf(CSV_MIME)), ClipData.Item(uris[0])).apply {
                        for (uri in uris.drop(1)) addItem(ClipData.Item(uri))
                    }
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(send, title(files.size)))
                }.onFailure { onError("Cannot share: ${it.message}") }
            }
        }.start()
    }

    private fun title(count: Int) = if (count == 1) "Share jump" else "Share $count jumps"

    private fun export(context: Context, files: List<File>): List<Uri> {
        val dir = File(context.cacheDir, ExportProvider.DIR)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        return files.map { file ->
            val base = fileName(file)
            var target = File(dir, "$base.csv")
            var n = 2
            while (target.exists()) target = File(dir, "$base (${n++}).csv")
            file.copyTo(target)
            ExportProvider.uri(context, target)
        }
    }

    /** "Big way 2026-11-07 14-10", or "FlySight 2026-11-07 14-10" for a jump without a name. */
    fun fileName(file: File): String {
        val key = TrackStore.sortKey(file)
        val time = if (key.length >= 14) "20${key.take(8)} ${key.substring(9, 14)}" else key
        val name = (TrackStore.customName(file) ?: "FlySight").replace(NOT_IN_NAME, "_").trim().take(80)
        return "$name $time"
    }

    /**
     * The jump name in the name of a received file, or null. File names that a
     * FlySight or this app makes ("TRACK.CSV", "FlySight 2026-11-07 14-10.csv")
     * give no name.
     */
    fun nameFromFile(displayName: String?): String? {
        var s = displayName?.substringBeforeLast('.')?.trim() ?: return null
        s = s.replace(COPY_NUMBER, "").replace(DATE_TIME, "").trim(' ', '-', '_', '·')
        return if (GENERIC.matches(s)) null else s
    }

    // ---- Names ----

    /**
     * A dialog for the name and the owner of a jump. If the owner changes and the
     * phone has other jumps from the same FlySight, the app asks which jumps to change.
     * [onSaved] runs after a change.
     */
    fun editNames(ui: Ui, file: File, onSaved: () -> Unit) {
        val content = ui.column().apply { setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(8)) }
        val name = field(ui, content, "Jump name", TrackStore.customName(file) ?: "", TrackStore.dateTitle(file),
            "If you remove the name, the app shows the date and time.")
        val oldOwner = TrackStore.owner(file)
        val owner = field(ui, content, "Owner", oldOwner ?: "", "For example, Anna",
            "The person who made this jump.", top = 20)

        val dialog = ui.dialog("Name and owner", content = content, positive = Ui.DialogButton("Save") {
            TrackStore.setCustomName(file, name.text.toString())
            val newOwner = owner.text.toString().trim().ifEmpty { null }
            if (newOwner == oldOwner) return@DialogButton onSaved()
            TrackStore.setOwner(file, newOwner)
            val id = TrackStore.deviceId(file)
            if (id == null) return@DialogButton onSaved()
            val others = TrackStore.jumpsFrom(ui.context, id).filter { it != file }
            if (others.isEmpty()) {
                // The first jump from this FlySight: its owner is also the default for new jumps
                if (TrackStore.deviceOwner(id) == null) TrackStore.setDeviceOwner(id, newOwner)
                return@DialogButton onSaved()
            }
            askOtherJumps(ui, id, newOwner, others, onlyLabel = "Only this jump",
                onlyHelp = "The other jumps from this FlySight do not change.", onDone = onSaved)
        }, scrollContent = true)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        name.requestFocus()
        name.setSelection(name.text.length)
        dialog.show()
    }

    /**
     * A dialog for the default owner of the connected FlySight. If the phone has
     * jumps from it, the app asks which jumps to change.
     */
    fun editDeviceOwner(ui: Ui, deviceId: String, onSaved: () -> Unit) {
        val content = ui.column().apply { setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(8)) }
        val old = TrackStore.deviceOwner(deviceId)
        val owner = field(ui, content, "Owner", old ?: "", "Your name",
            "The app adds this name to all new jumps from this FlySight. The name stays on this phone.")
        val dialog = ui.dialog("Owner of this FlySight", content = content, positive = Ui.DialogButton("Save") {
            val name = owner.text.toString().trim().ifEmpty { null }
            if (name == old) return@DialogButton
            TrackStore.setDeviceOwner(deviceId, name)
            val jumps = TrackStore.jumpsFrom(ui.context, deviceId)
            if (jumps.isEmpty()) return@DialogButton onSaved()
            askOtherJumps(ui, deviceId, name, jumps, onlyLabel = "Only new jumps",
                onlyHelp = "The jumps on this phone do not change.", onDone = onSaved)
        })
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        owner.requestFocus()
        owner.setSelection(owner.text.length)
        dialog.show()
    }

    /**
     * Ask if the owner of [jumps] (from the FlySight [deviceId]) must also change.
     * The 2 options that change jumps also make [owner] the default owner of the FlySight.
     */
    private fun askOtherJumps(
        ui: Ui, deviceId: String, owner: String?, jumps: List<File>,
        onlyLabel: String, onlyHelp: String, onDone: () -> Unit,
    ) {
        val withoutOwner = jumps.filter { TrackStore.owner(it) == null }
        val different = jumps.filter { TrackStore.owner(it) != owner }
        if (different.isEmpty()) return onDone()
        val list = ui.column()
        lateinit var dialog: android.app.Dialog
        fun option(title: String, help: String, action: () -> Unit) {
            list.addView(ui.column().apply {
                setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10))
                minimumHeight = ui.dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = ui.ripple(ui.onSurface, null)
                addView(ui.text(Ui.Type.BODY_LARGE, title))
                addView(ui.text(Ui.Type.BODY_MEDIUM, help, ui.onSurfaceVariant))
                setOnClickListener {
                    action()
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fun apply(files: List<File>) {
            TrackStore.setDeviceOwner(deviceId, owner)
            for (f in files) TrackStore.setOwner(f, owner)
        }
        fun count(n: Int) = if (n == 1) "1 jump" else "$n jumps"

        option(onlyLabel, onlyHelp) {}
        if (owner != null && withoutOwner.isNotEmpty() && withoutOwner.size < different.size) {
            option("Jumps without an owner", "Adds the owner to ${count(withoutOwner.size)}. The other owners stay.") { apply(withoutOwner) }
        }
        option("All jumps from this FlySight", if (owner == null) "Removes the owner from ${count(different.size)}."
        else "Changes the owner of ${count(different.size)}.") { apply(different) }

        val message = "This phone has ${count(jumps.size)} from the same FlySight. " +
            if (owner == null) "Remove the owner from them too?" else "Give them the owner \"$owner\" too?"
        dialog = ui.dialog("Change other jumps?", message, list, negative = null, scrollContent = true)
        // A tap outside the dialog is the same as the first option
        dialog.setOnDismissListener { onDone() }
        dialog.show()
    }

    /** A label, a text field and a help text, added to [parent]. */
    private fun field(ui: Ui, parent: LinearLayout, label: String, value: String, placeholder: String, help: String, top: Int = 0): EditText {
        parent.addView(ui.text(Ui.Type.LABEL_LARGE, label, ui.onSurfaceVariant).apply {
            setPadding(0, ui.dp(top), 0, ui.dp(8))
        })
        val field = ui.textField(value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply { hint = placeholder }
        parent.addView(field, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.addView(ui.text(Ui.Type.BODY_SMALL, help, ui.onSurfaceVariant).apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0)
            visibility = if (help.isEmpty()) View.GONE else View.VISIBLE
        })
        return field
    }
}
