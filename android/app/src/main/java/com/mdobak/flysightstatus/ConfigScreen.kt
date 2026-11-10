package com.mdobak.flysightstatus

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.util.Locale

/** The main configuration and the profiles in /CONFIG. */
class ConfigScreen(main: MainActivity) : Screen(main) {

    private val bar = ui.TopBar("Config")
    private val list = ui.column().apply { setPadding(0, 0, 0, ui.dp(96)) }
    private val progress = ui.linearProgress().apply { isIndeterminate = true; visibility = View.INVISIBLE }
    private val fab = ui.fab("New profile", R.drawable.ic_add) { askNewProfile() }

    private var profiles: List<FileInfo>? = null
    private var error: String? = null
    private var loading = false
    private var wasConnected = false

    override val view: View

    init {
        val frame = FrameLayout(main).apply {
            addView(ui.scroll(list, bar), FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, ui.dp(16), ui.dp(16))
            })
        }
        view = ui.column().apply {
            addView(bar)
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        render()
    }

    override fun onShow() {
        render()
        if (main.connected && profiles == null && !loading) loadProfiles()
    }

    override fun onStateChanged() {
        val connected = main.connected
        if (connected == wasConnected) return
        wasConnected = connected
        if (!connected) {
            profiles = null
            error = null
        }
        render()
        if (connected && main.currentTab == MainActivity.TAB_CONFIG && profiles == null && !loading) loadProfiles()
    }

    private fun loadProfiles() {
        val f = main.idleFiles() ?: return
        loading = true
        error = null
        render()
        f.list("/CONFIG") { result ->
            loading = false
            result.onSuccess { entries ->
                profiles = entries.filter { !it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }
            }.onFailure {
                // A FlySight with no /CONFIG folder has no profiles.
                profiles = emptyList()
                if (main.mode != DeviceMode.SLEEP && main.mode != DeviceMode.UNKNOWN) error = main.fileError("Read the profiles", it)
            }
            render()
        }
    }

    private fun render() {
        list.removeAllViews()
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        if (!main.connected) {
            fab.visibility = View.GONE
            list.addView(ui.emptyState(R.drawable.ic_tune, "Not connected",
                "Connect to the FlySight to change its settings.",
                ui.button(Ui.ButtonStyle.TONAL, "Connect", R.drawable.ic_bluetooth) { main.connectOrScan() }))
            return
        }
        fab.visibility = View.VISIBLE
        list.addView(ui.ListItem("Main configuration", "CONFIG.TXT · The settings for each session",
            ui.avatar(R.drawable.ic_tune, ui.primaryContainer, ui.onPrimaryContainer),
            ui.icon(R.drawable.ic_chevron_right)).onClick { open("/CONFIG.TXT", profile = false) })

        error?.let {
            list.addView(ui.text(Ui.Type.BODY_MEDIUM, it, ui.onErrorContainer).apply {
                background = ui.shape(ui.errorContainer, 12f)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0) })
        }

        list.addView(ui.header("Profiles"))
        val items = profiles
        when {
            items == null -> list.addView(ui.note(if (loading) "Reading the profiles…" else ""))
            items.isEmpty() -> list.addView(ui.note("No profiles. A profile changes some settings of the main configuration, for example for a different discipline."))
            else -> for (info in items) {
                list.addView(ui.ListItem(info.name.substringBeforeLast('.'), FileInfo.formatSize(info.size),
                    ui.avatar(R.drawable.ic_file), ui.icon(R.drawable.ic_chevron_right))
                    .onClick { open("/CONFIG/${info.name}", profile = true) }
                    .onLongClick { profileMenu(info) })
            }
        }
        if (!items.isNullOrEmpty()) {
            list.addView(ui.note(
                "To select a profile, start config mode. The FlySight plays the Init_File sound of each profile in turn. " +
                    "Push the button during the sound of the profile that you want."))
            list.addView(ui.button(Ui.ButtonStyle.TONAL, "Start config mode") {
                main.sendDs(FlySightProtocol.DS_CMD_REQUEST_CONFIG)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(16) })
        }
    }

    private fun profileMenu(info: FileInfo) {
        val name = info.name.substringBeforeLast('.')
        ui.menuDialog(name, listOf(
            "Edit" to { open("/CONFIG/${info.name}", profile = true) },
            "Delete" to { confirmDelete(name, "/CONFIG/${info.name}") },
        )).show()
    }

    fun confirmDelete(name: String, path: String, then: () -> Unit = {}) {
        main.confirm("Delete the profile $name?", "This deletes $path on the FlySight.", "Delete") {
            val f = main.idleFiles() ?: return@confirm
            f.delete(path) { result ->
                result.onSuccess {
                    main.log("FT: deleted $path")
                    main.snack("Deleted the profile $name.")
                    then()
                    loadProfiles()
                }.onFailure { main.snack(main.fileError("Delete", it), long = true) }
            }
        }
    }

    private fun open(path: String, profile: Boolean) {
        val f = main.idleFiles() ?: return
        loading = true
        render()
        f.read(path, progress = {}) { result ->
            loading = false
            render()
            result.onSuccess { data ->
                main.push(ConfigEditor(main, path, data.toString(Charsets.ISO_8859_1), profile) { loadProfiles() })
            }.onFailure { main.snack(main.fileError("Read $path", it), long = true) }
        }
    }

    private fun askNewProfile() {
        val existing = profiles?.map { it.name.substringBeforeLast('.').uppercase(Locale.US) }?.toSet() ?: emptySet()
        fun problem(name: String): String? = when {
            name.isEmpty() -> "Use letters, digits, - and _. Maximum 8 characters."
            name.length > 8 -> "Maximum 8 characters."
            !name.all { it.isLetterOrDigit() || it == '_' || it == '-' } -> "Use only letters, digits, - and _."
            name.uppercase(Locale.US) in existing -> "A profile with this name exists."
            else -> null
        }
        ui.inputDialog("New profile", "", "The name of the file in /CONFIG on the FlySight.",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, suffix = ".TXT",
            hint = { problem(it.trim()) }, okLabel = "Make") { input ->
            val name = input.uppercase(Locale.US)
            problem(name)?.let { return@inputDialog main.snack(it) }
            val f = main.idleFiles() ?: return@inputDialog
            val path = "/CONFIG/$name.TXT"
            val text = "; FlySight profile $name\r\n" +
                "; The settings in this file replace the settings in /CONFIG.TXT.\r\n" +
                "; Init_File is the sound that the FlySight plays for this profile in config mode.\r\n\r\n"
            // A NAK from mkdir means that the folder exists.
            f.mkdir("/CONFIG") { _ ->
                val writer = main.files ?: return@mkdir
                writer.write(path, text.toByteArray(Charsets.ISO_8859_1), progress = { _, _ -> }) { result ->
                    result.onSuccess {
                        main.log("FT: made $path")
                        loadProfiles()
                        main.push(ConfigEditor(main, path, text, profile = true) { loadProfiles() })
                    }.onFailure { main.snack(main.fileError("Make $path", it), long = true) }
                }
            }
        }.show()
    }
}
