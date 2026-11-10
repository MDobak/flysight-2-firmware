package com.mdobak.flysightstatus

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mdobak.flysightstatus.ConfigSchema.Target
import com.mdobak.flysightstatus.Ui.Companion.MATCH
import com.mdobak.flysightstatus.Ui.Companion.WRAP
import java.util.Locale

/**
 * A form for CONFIG.TXT or a profile. The form changes only the values in
 * the file, so the comments and the unknown lines stay. In a profile, the
 * form shows only the settings that are in the file.
 *
 * Each section is a card that opens and closes. The rows are tiles in the
 * card. Short choices are segmented buttons, long choices are menus, and the
 * numbers show in real units (km/h, Hz, ratio), not in file units.
 */
class ConfigEditor(
    main: MainActivity,
    private val path: String,
    text: String,
    private val profile: Boolean,
    private val onChanged: () -> Unit,
) : Screen(main) {

    private var original = text
    private var file = ConfigFile(text)
    private var textMode = false

    private val name = if (profile) path.substringAfterLast('/').substringBeforeLast('.') else "Main configuration"
    private val bar = ui.TopBar(name)
    private val saveButton = ui.button(Ui.ButtonStyle.FILLED, "Save") { save() }
    private val moreButton = ui.iconButton(R.drawable.ic_more, "More options") { showMenu() }
    private val progress = ui.linearProgress().apply { isIndeterminate = true; visibility = View.INVISIBLE }
    private val body = ui.column().apply { setPadding(0, ui.dp(4), 0, ui.dp(32)) }
    private val scroll: ScrollView = ui.scroll(body, bar)
    private val textEditor = EditText(main).apply {
        ui.style(this, Ui.Type.BODY_MEDIUM)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(true)
        background = null
        setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        visibility = View.GONE
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = updateBar()
        })
    }
    private val prefs = main.getSharedPreferences("config_editor", Context.MODE_PRIVATE)

    /** The open sections. In the main configuration the app remembers them. A profile opens all sections. */
    private val open: MutableSet<String> =
        if (profile) ConfigSchema.sections.map { it.title }.toMutableSet().apply { add(OTHER) }
        else prefs.getStringSet(PREF_OPEN, emptySet())!!.toMutableSet()

    override val view: View

    init {
        bar.setNavigation(R.drawable.ic_back, "Back") { if (!onBack()) main.popScreen() }
        bar.addAction(saveButton)
        bar.addAction(moreButton)
        (saveButton.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = ui.dp(4)
        val frame = FrameLayout(main).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(textEditor, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        view = ui.column().apply {
            addView(bar)
            addView(progress, LinearLayout.LayoutParams(MATCH, ui.dp(4)))
            addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        render()
    }

    private fun currentText() = if (textMode) textEditor.text.toString() else file.text()

    private val dirty get() = currentText() != original

    private fun updateBar() {
        saveButton.visibility = if (dirty) View.VISIBLE else View.GONE
        saveButton.isEnabled = !main.sync.running
    }

    private fun changed() = render()

    override fun onBack(): Boolean {
        if (!dirty) return false
        ui.dialog("Discard the changes?", "The changes are not saved on the FlySight.",
            positive = Ui.DialogButton("Discard") { main.popScreen() },
            negative = Ui.DialogButton("Keep editing")).show()
        return true
    }

    override fun onStateChanged() = updateBar()

    // ---- Form ----

    private fun render() {
        updateBar()
        if (textMode) return
        val y = scroll.scrollY
        body.layoutTransition = null
        body.removeAllViews()
        if (profile) {
            body.addView(ui.text(Ui.Type.BODY_MEDIUM,
                "This profile changes only the settings in it. The other settings come from the main configuration.",
                ui.onSecondaryContainer).apply {
                background = ui.shape(ui.secondaryContainer, 20f)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(4)) })
        }
        for ((group, sections) in ConfigSchema.groupedSections) {
            var header = false
            for (section in sections) {
                val items = section.items.filter { !profile || present(it) }
                if (items.isEmpty()) continue
                if (!header) {
                    header = true
                    body.addView(ui.text(Ui.Type.TITLE_SMALL, group, ui.primary).apply {
                        setPadding(ui.dp(32), ui.dp(24), ui.dp(32), 0)
                        isAccessibilityHeading = true
                    })
                }
                val rows = items.flatMap { item ->
                    when (item) {
                        is ConfigSchema.Group -> groupRows(item)
                        is ConfigSchema.Field -> listOf(fieldTile(item, file.get(item.key), removable = profile,
                            onRemove = { remove(item.key) }) { v -> setValue(item.key, v) })
                        else -> emptyList()
                    }
                }
                val summary = if (profile) settingsCount(items) else section.summary(file)
                val reset = when {
                    profile -> Ui.DialogButton("Remove from the profile") { resetSection(section) }
                    isDefault(section) -> null
                    else -> Ui.DialogButton("Reset to defaults") { resetSection(section) }
                }
                body.addView(sectionCard(section.title, section.icon, summary, rows, section.note, reset))
            }
        }
        val unknown = file.keys().filter { it !in KNOWN_KEYS }
        if (unknown.isNotEmpty()) {
            val rows = unknown.map { key ->
                Tile(key).support(file.get(key) ?: "").help(LEGACY_HELP[key]).apply {
                    setOnClickListener {
                        if (key == "Window") return@setOnClickListener replaceWindow()
                        if (key in UNUSED_KEYS) return@setOnClickListener removeUnused(key)
                        ui.inputDialog(key, file.get(key) ?: "", "The app does not know this setting.") { v ->
                            if (v.isEmpty()) file.remove(key) else file.set(key, v)
                            changed()
                        }.show()
                    }
                }
            }
            body.addView(ui.text(Ui.Type.TITLE_SMALL, "Other", ui.primary).apply {
                setPadding(ui.dp(32), ui.dp(24), ui.dp(32), 0)
                isAccessibilityHeading = true
            })
            body.addView(sectionCard(OTHER, R.drawable.ic_s_other, unknown.joinToString(" · "), rows, null))
        }
        if (profile) {
            if (file.keys().isEmpty()) {
                body.addView(ui.emptyState(R.drawable.ic_tune, "No settings yet", "Add the settings that this profile changes."))
            }
            body.addView(ui.button(Ui.ButtonStyle.TONAL, "Add a setting", R.drawable.ic_add) { addSetting() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(ui.dp(16), ui.dp(16), 0, 0) })
        }
        scroll.post {
            scroll.scrollTo(0, y)
            body.layoutTransition = transition()
        }
    }

    private fun transition() = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        setDuration(200)
    }

    private fun settingsCount(items: List<Any>): String {
        val n = items.sumOf { item ->
            when (item) {
                is ConfigSchema.Group -> file.group(item.keys).size
                else -> 1
            }
        }
        return if (n == 1) "1 setting" else "$n settings"
    }

    private fun present(item: Any): Boolean = when (item) {
        is ConfigSchema.Group -> item.keys.any { file.has(it) }
        Target -> file.has("Lat") || file.has("Lon")
        is ConfigSchema.Field -> file.has(item.key)
        else -> false
    }

    // ---- Defaults ----

    /** True if the main configuration uses the firmware default for each setting of the section. */
    private fun isDefault(section: ConfigSchema.Section): Boolean = section.items.all { item ->
        when (item) {
            is ConfigSchema.Group -> file.group(item.keys).isEmpty()
            Target -> (file.getInt("Lat") ?: 0) == 0 && (file.getInt("Lon") ?: 0) == 0
            is ConfigSchema.Text -> !file.has(item.key)
            is ConfigSchema.Field -> {
                val raw = file.get(item.key)
                raw == null || ConfigFile.parseInt(raw) == ConfigSchema.defaultValue(item)?.let { ConfigFile.parseInt(it) }
            }
            else -> true
        }
    }

    /**
     * In the main configuration, set the firmware defaults. In a profile, remove the
     * settings, so the values of the main configuration apply. The snackbar can undo it.
     */
    private fun resetSection(section: ConfigSchema.Section) {
        val before = file.text()
        for (item in section.items) when (item) {
            is ConfigSchema.Group -> file.setGroup(item.keys, emptyList())
            Target -> if (profile) {
                file.remove("Lat")
                file.remove("Lon")
            } else if (file.has("Lat") || file.has("Lon")) {
                file.set("Lat", "0")
                file.set("Lon", "0")
            }
            is ConfigSchema.Field -> {
                val default = ConfigSchema.defaultValue(item)
                when {
                    profile || default == null -> file.remove(item.key)
                    file.has(item.key) -> file.set(item.key, default)
                }
            }
        }
        changed()
        main.snack(if (profile) "Removed the ${section.title.lowercase(Locale.US)} settings." else "${section.title}: default values set.",
            "Undo") {
            file = ConfigFile(before)
            changed()
        }
    }

    /**
     * Window is an old key. It sets Win_Above and Win_Below together. The firmware
     * reads the lines in order, so the last line of each key gives the value.
     */
    private fun replaceWindow() {
        val window = file.get("Window") ?: return
        fun value(key: String) = if (file.lastLine("Window") > file.lastLine(key)) window else file.get(key) ?: window
        val above = value("Win_Above")
        val below = value("Win_Below")
        ui.dialog("Window (old setting)",
            "Window is the old name of the two quiet zones. It sets both values together, in meters. " +
                "Around each alarm and each altitude call, there is no tone and no spoken value, " +
                "so you can hear the alarm or the call clearly.\n\n" +
                "Replace it with the two new settings? The values do not change: $above m above and $below m below.",
            positive = Ui.DialogButton("Replace") {
                file.remove("Window")
                file.set("Win_Above", above)
                file.set("Win_Below", below)
                changed()
            }).show()
    }

    private fun removeUnused(key: String) {
        ui.dialog("Remove $key?", LEGACY_HELP[key]?.removeSuffix(" Tap to remove it.") +
                " The FlySight ignores this line, so the removal does not change how the FlySight works.",
            positive = Ui.DialogButton("Remove") {
                file.remove(key)
                changed()
            }).show()
    }

    private fun setValue(key: String, value: String?) {
        if (value == null) file.remove(key) else file.set(key, value)
        changed()
    }

    private fun remove(key: String) {
        when (key) {
            "Lat" -> {
                file.remove("Lat")
                file.remove("Lon")
            }
            else -> file.remove(key)
        }
        changed()
    }

    // ---- Cards and tiles ----

    /** The shape of a tile. The outer corners of a card are large, the corners between tiles are small. */
    private fun tileShape(top: Boolean, bottom: Boolean): GradientDrawable {
        val t = ui.dpf(if (top) 20f else 4f)
        val b = ui.dpf(if (bottom) 20f else 4f)
        return GradientDrawable().apply {
            setColor(ui.surfaceContainerHigh)
            cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
        }
    }

    private fun tileBackground(top: Boolean, bottom: Boolean) =
        RippleDrawable(ColorStateList.valueOf(ui.alpha(ui.onSurface, 0.12f)), tileShape(top, bottom), tileShape(top, bottom))

    private fun sectionCard(title: String, icon: Int, summary: String, rows: List<View>, note: String?, reset: Ui.DialogButton? = null): View {
        val key = title
        var isOpen = key in open
        val chevron = ui.icon(R.drawable.ic_expand_more).apply { rotation = if (isOpen) 180f else 0f }
        val summaryView = ui.text(Ui.Type.BODY_MEDIUM, summary, ui.onSurfaceVariant).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val header = LinearLayout(main).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(72)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            addView(ui.avatar(icon), LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(16) })
            addView(ui.column().apply {
                addView(ui.text(Ui.Type.TITLE_MEDIUM, title))
                addView(summaryView)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(chevron, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(12) })
            background = tileBackground(true, !isOpen)
            stateDescription = if (isOpen) "Open" else "Closed"
        }
        val content = ui.column().apply { visibility = if (isOpen) View.VISIBLE else View.GONE }
        for ((i, row) in rows.withIndex()) {
            row.background = tileBackground(false, i == rows.lastIndex && note == null && reset == null)
            content.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(2) })
        }
        if (note != null) {
            content.addView(ui.text(Ui.Type.BODY_SMALL, note, ui.onSurfaceVariant).apply {
                background = tileShape(false, reset == null)
                setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(2) })
        }
        if (reset != null) {
            content.addView(LinearLayout(main).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                background = tileShape(false, true)
                setPadding(ui.dp(8), 0, ui.dp(8), 0)
                addView(ui.button(Ui.ButtonStyle.TEXT, reset.label, R.drawable.ic_refresh) { reset.action?.invoke() })
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(2) })
        }
        header.setOnClickListener {
            isOpen = !isOpen
            content.visibility = if (isOpen) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (isOpen) 180f else 0f).setDuration(200).start()
            header.background = tileBackground(true, !isOpen)
            header.stateDescription = if (isOpen) "Open" else "Closed"
            if (isOpen) open += key else open -= key
            if (!profile) prefs.edit().putStringSet(PREF_OPEN, open.toSet()).apply()
        }
        return ui.column().apply {
            addView(header)
            addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0) }
            layoutTransition = transition()
        }
    }

    /** One setting: a label, lines of text, and a control at the end or below. */
    private inner class Tile(label: String) : LinearLayout(main) {
        val labelView = ui.text(Ui.Type.BODY_LARGE, label)
        private val supportView = ui.text(Ui.Type.BODY_MEDIUM, "", ui.onSurfaceVariant).apply { visibility = GONE }
        private val helpView = ui.text(Ui.Type.BODY_SMALL, "", ui.onSurfaceVariant).apply {
            visibility = GONE
            setPadding(0, ui.dp(2), 0, 0)
        }
        private val top = LinearLayout(main).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        private val labelRow = LinearLayout(main).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(64)
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
            // The label shrinks and wraps when it is long, so the info button stays next to it.
            labelRow.addView(labelView, LayoutParams(WRAP, WRAP, 1f))
            top.addView(ui.column().apply {
                addView(labelRow, LayoutParams(WRAP, WRAP))
                addView(supportView)
                addView(helpView)
            }, LayoutParams(0, WRAP, 1f))
            addView(top, LayoutParams(MATCH, WRAP))
        }

        fun support(text: CharSequence?) = apply {
            supportView.text = text ?: ""
            supportView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        }

        fun help(text: CharSequence?) = apply {
            helpView.text = text ?: ""
            helpView.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        }

        /** A small button after the label. It shows [text] in a dialog. */
        fun info(title: String, text: String?) = apply {
            if (text == null) return@apply
            val button = ui.icon(R.drawable.ic_info_outline, ui.onSurfaceVariant).apply {
                contentDescription = "About $title"
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                background = ui.ripple(ui.onSurfaceVariant, null, 18f)
                setOnClickListener { ui.dialog(title, text, positive = Ui.DialogButton("OK"), negative = null).show() }
            }
            // The touch area is 36 dp, but the negative margins keep the row as high as the label.
            labelRow.addView(button, LayoutParams(ui.dp(36), ui.dp(36)).apply {
                marginStart = ui.dp(2)
                topMargin = -ui.dp(8)
                bottomMargin = -ui.dp(8)
            })
        }

        fun leading(view: View, sizeDp: Int) = apply {
            top.addView(view, 0, LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)).apply { marginEnd = ui.dp(16) })
        }

        fun trailing(view: View) = apply {
            top.addView(view, LayoutParams(WRAP, WRAP).apply { marginStart = ui.dp(12) })
        }

        fun below(view: View) = apply {
            addView(view, LayoutParams(MATCH, WRAP).apply { topMargin = ui.dp(8) })
        }
    }

    /** A value in a filled box. It shows that a tap changes the value. */
    private fun valueBox(text: String) = ui.text(Ui.Type.BODY_LARGE, text).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        minWidth = ui.dp(56)
        minHeight = ui.dp(40)
        maxWidth = ui.dp(180)
        ellipsize = TextUtils.TruncateAt.END
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        background = ui.shape(ui.surfaceContainerHighest, 12f)
    }

    /**
     * The tile for [field]. [raw] is the value in the file, or null. [onSet] gets
     * the new file value. In a profile, [removable] adds a button that removes the setting.
     */
    private fun fieldTile(
        field: ConfigSchema.Field,
        raw: String?,
        removable: Boolean = false,
        onRemove: () -> Unit = {},
        onSet: (String?) -> Unit,
    ): Tile {
        val tile = Tile(field.label).info(field.label, field.more)
        val default = if (raw == null && !profile) "Default" else null
        when (field) {
            Target -> {
                tile.support(ConfigSchema.valueText(field, raw, file)).help(field.help)
                tile.trailing(ui.icon(R.drawable.ic_place))
                tile.setOnClickListener { pickTarget() }
            }
            is ConfigSchema.Choice -> {
                val current = raw?.let { ConfigFile.parseInt(it) } ?: field.default
                if (field.segmented) {
                    tile.support(default).help(field.help)
                    tile.below(ui.Segmented(field.options.map { it.second }, field.options.indexOfFirst { it.first == current }) {
                        onSet(field.options[it].first.toString())
                    })
                } else {
                    tile.support(listOfNotNull(field.labelOf(current), default).joinToString(" · ")).help(field.help)
                    tile.trailing(ui.icon(R.drawable.ic_arrow_drop_down))
                    tile.setOnClickListener { dropdown(tile, field, current, onSet) }
                }
            }
            is ConfigSchema.Number -> {
                val d = field.display(file)
                val value = raw?.let { ConfigFile.parseInt(it) } ?: field.default
                tile.support(listOfNotNull(d.note?.invoke(value), default).joinToString(" · ")).help(field.help)
                tile.trailing(valueBox(d.text(value)))
                tile.setOnClickListener { editNumber(field, value, onSet) }
            }
            is ConfigSchema.Slider -> {
                val value = raw?.let { ConfigFile.parseInt(it) } ?: field.default
                val valueView = ui.text(Ui.Type.LABEL_LARGE, field.format(value), ui.primary)
                tile.support(default).help(field.help)
                tile.trailing(valueView)
                tile.setPadding(tile.paddingLeft, tile.paddingTop, tile.paddingRight, ui.dp(8))
                val slider = M3Slider(ui, field.range, value, field.format).apply {
                    contentDescription = field.label
                    onChange = { valueView.text = field.format(it) }
                    onCommit = { if (it != value) onSet(it.toString()) }
                }
                // The slider has 8 dp padding for the handle, so the track aligns with the text.
                tile.below(slider)
                (slider.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = -ui.dp(8)
                    marginEnd = -ui.dp(8)
                    topMargin = ui.dp(4)
                }
            }
            is ConfigSchema.Toggle -> {
                val on = raw?.let { ConfigFile.parseInt(it) }?.let { it != 0 } ?: field.default
                tile.support(default).help(field.help)
                val switch = ui.switch(on) { v -> onSet(if (v) "1" else "0") }
                tile.trailing(switch)
                tile.setOnClickListener { switch.toggle() }
            }
            is ConfigSchema.Text -> {
                tile.help(field.help)
                tile.trailing(valueBox(raw ?: "None").apply { if (raw == null) setTextColor(ui.onSurfaceVariant) })
                tile.setOnClickListener { if (field.audio) pickAudio(field, raw, onSet) else editText(field, raw, onSet) }
            }
        }
        if (removable) {
            tile.trailing(ui.iconButton(R.drawable.ic_close, "Remove ${field.label} from the profile") { onRemove() })
        }
        return tile
    }

    // ---- Editors ----

    private fun dropdown(anchor: View, field: ConfigSchema.Choice, current: Int, onSet: (String?) -> Unit) {
        ui.popupMenu(anchor, field.options.map { option ->
            option.second to { if (option.first != current) onSet(option.first.toString()) }
        }, checked = field.options.indexOfFirst { it.first == current })
    }

    private fun editNumber(field: ConfigSchema.Number, current: Int, onSet: (String?) -> Unit) {
        val d = field.display(file)
        val range = field.range
        fun problem(s: String): String? {
            val v = d.parse(s) ?: return "Enter a number."
            if (range != null && v !in range) {
                return if (range.last == Int.MAX_VALUE) "Enter ${d.text(range.first)} or more."
                else "Enter a value from ${d.text(range.first)} to ${d.text(range.last)}."
            }
            return d.note?.invoke(v)
        }
        ui.inputDialog(field.label, d.plain(current), field.help, d.inputType,
            suffix = d.unit.ifEmpty { null }, hint = { problem(it) }) { s ->
            val v = d.parse(s)
            if (v == null || (range != null && v !in range)) return@inputDialog main.snack(problem(s) ?: "Enter a number.")
            if (v != current) onSet(v.toString())
        }.show()
    }

    private fun editText(field: ConfigSchema.Text, raw: String?, onSet: (String?) -> Unit) {
        ui.inputDialog(field.label, raw ?: "", field.help, hint = {
            if (it.length > field.maxLength) "The FlySight uses only the first ${field.maxLength} characters." else null
        }) { s ->
            val v = s.filter { !it.isWhitespace() && it != ';' && it != ':' }.take(field.maxLength)
            onSet(v.ifEmpty { null })
        }.show()
    }

    // ---- Audio files ----

    /** The WAV files in /audio on the FlySight, without the extension. The app reads them one time. */
    private var audioFiles: List<String>? = null

    private fun pickAudio(field: ConfigSchema.Text, raw: String?, onSet: (String?) -> Unit) {
        audioFiles?.let { return showAudioChoice(field, raw, it, onSet) }
        val f = main.idleFiles() ?: return
        progress.visibility = View.VISIBLE
        f.list("/audio") { result ->
            progress.visibility = View.INVISIBLE
            result.onSuccess { entries ->
                val names = entries
                    .filter { !it.isDirectory && !it.isHidden && it.name.endsWith(".wav", ignoreCase = true) }
                    .map { it.name.substringBeforeLast('.') }
                    .filter { it.length <= field.maxLength }
                    .sortedWith(compareBy<String>({ it.toIntOrNull() == null }, { it.toIntOrNull() ?: 0 }, { it.lowercase(Locale.US) }))
                audioFiles = names
                showAudioChoice(field, raw, names, onSet)
            }.onFailure {
                main.snack(main.fileError("Read /audio", it), long = true)
                editText(field, raw, onSet)
            }
        }
    }

    private fun showAudioChoice(field: ConfigSchema.Text, raw: String?, names: List<String>, onSet: (String?) -> Unit) {
        val missing = raw != null && names.none { it.equals(raw, ignoreCase = true) }
        val files = if (missing) listOf(raw!!) + names else names
        val labels = listOf("None") + files.mapIndexed { i, n -> if (missing && i == 0) "$n (not on the FlySight)" else n } + "Type a name…"
        val selected = if (raw == null) 0 else files.indexOfFirst { it.equals(raw, ignoreCase = true) } + 1
        val message = if (names.isEmpty()) "There are no WAV files in /audio on the FlySight." else "WAV files in /audio on the FlySight."
        ui.choiceDialog(field.label, labels, selected, message) { i ->
            when (i) {
                0 -> onSet(null)
                labels.lastIndex -> editText(field, raw, onSet)
                else -> if (files[i - 1] != raw) onSet(files[i - 1])
            }
        }.show()
    }

    // ---- Navigation target ----

    private fun pickTarget() {
        @Suppress("DEPRECATION")
        main.startActivityForResult(MapPickActivity.intent(main, file.getInt("Lat"), file.getInt("Lon")), MainActivity.REQUEST_TARGET)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != MainActivity.REQUEST_TARGET || resultCode != Activity.RESULT_OK || data == null) return
        file.set("Lat", data.getIntExtra(MapPickActivity.EXTRA_LAT, 0).toString())
        file.set("Lon", data.getIntExtra(MapPickActivity.EXTRA_LON, 0).toString())
        changed()
    }

    // ---- Lists ----

    private fun article(word: String) = if (word.first() in "aeiou") "an" else "a"

    /** A tile for each item, then a tile that adds an item. */
    private fun groupRows(group: ConfigSchema.Group): List<View> {
        val items = file.group(group.keys)
        val rows = mutableListOf<View>()
        for ((i, item) in items.withIndex()) {
            val badge = ui.text(Ui.Type.LABEL_LARGE, "${i + 1}", ui.onSecondaryContainer).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ui.secondaryContainer)
                }
            }
            rows += Tile(group.describe(item)).support(group.detail(item, file)).leading(badge, 32)
                .trailing(ui.icon(R.drawable.ic_chevron_right)).apply {
                    contentDescription = "${group.itemName} ${i + 1}: ${group.describe(item)}"
                    setOnClickListener { editGroupItem(group, i) }
                }
        }
        val full = items.size >= group.max
        val color = if (full) ui.alpha(ui.onSurface, 0.38f) else ui.primary
        rows += Tile("Add ${article(group.itemName)} ${group.itemName}").help(group.help).leading(ui.icon(R.drawable.ic_add, color), 24).apply {
            labelView.setTextColor(color)
            labelView.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            minimumHeight = ui.dp(56)
            isEnabled = !full
            if (!full) setOnClickListener { addGroupItem(group) }
        }
        return rows
    }

    private fun addGroupItem(group: ConfigSchema.Group) {
        val items = file.group(group.keys).toMutableList()
        if (items.size >= group.max) return main.snack("The maximum is ${group.max}.")
        items += group.newItem
        file.setGroup(group.keys, items)
        changed()
        editGroupItem(group, items.lastIndex)
    }

    private fun editGroupItem(group: ConfigSchema.Group, index: Int) {
        val content = ui.column()
        val title = "${group.itemName.replaceFirstChar { it.uppercase() }} ${index + 1}"

        fun fill() {
            content.removeAllViews()
            val item = file.group(group.keys).getOrNull(index) ?: return
            for (field in group.fields) {
                content.addView(fieldTile(field, item[field.key]) { v ->
                    // The first key starts an item, so it cannot be removed.
                    if (v == null && field.key == group.keys[0]) return@fieldTile
                    val items = file.group(group.keys).map { it.toMutableMap() }
                    val target = items.getOrNull(index) ?: return@fieldTile
                    if (v == null) target.remove(field.key) else target[field.key] = v
                    file.setGroup(group.keys, items)
                    changed()
                    fill()
                }.apply {
                    setPadding(ui.dp(24), paddingTop, ui.dp(24), paddingBottom)
                    if (hasOnClickListeners()) background = ui.ripple(ui.onSurface, null)
                })
            }
        }
        fill()
        ui.dialog(title, null, content,
            positive = Ui.DialogButton("Done"),
            negative = null,
            neutral = Ui.DialogButton("Delete") {
                val items = file.group(group.keys).toMutableList()
                if (index < items.size) items.removeAt(index)
                file.setGroup(group.keys, items)
                changed()
            },
            scrollContent = true).show()
    }

    // ---- Add a setting to a profile ----

    private fun addSetting() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        for (section in ConfigSchema.sections) {
            for (item in section.items) {
                if (present(item)) continue
                when (item) {
                    is ConfigSchema.Group -> options += "${section.title} · ${item.title}" to { addGroupItem(item) }
                    Target -> options += "${section.title} · ${Target.label}" to { pickTarget() }
                    is ConfigSchema.Field -> options += "${section.title} · ${item.label}" to { addField(item) }
                }
            }
        }
        ui.menuDialog("Add a setting", options).show()
    }

    /** Add [field] to the profile with the default value. Then the user can change it in the form. */
    private fun addField(field: ConfigSchema.Field) {
        val value = when (field) {
            is ConfigSchema.Choice -> field.default.toString()
            is ConfigSchema.Number -> field.default.toString()
            is ConfigSchema.Slider -> field.default.toString()
            is ConfigSchema.Toggle -> if (field.default) "1" else "0"
            is ConfigSchema.Text -> return editText(field, null) { v -> if (v != null) setValue(field.key, v) }
            else -> return
        }
        file.set(field.key, value)
        changed()
    }

    // ---- Menu, text mode, save ----

    private fun showMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += (if (textMode) "Edit as a form" else "Edit as text") to { setTextMode(!textMode) }
        if (profile) items += "Delete the profile" to {
            main.tab(ConfigScreen::class.java).confirmDelete(name, path) {
                onChanged()
                main.popScreen()
            }
        }
        ui.popupMenu(moreButton, items)
    }

    private fun setTextMode(on: Boolean) {
        if (on == textMode) return
        if (on) {
            textEditor.setText(file.text())
        } else {
            file = ConfigFile(textEditor.text.toString())
        }
        textMode = on
        textEditor.visibility = if (on) View.VISIBLE else View.GONE
        scroll.visibility = if (on) View.GONE else View.VISIBLE
        render()
    }

    private fun save() {
        val f = main.idleFiles() ?: return
        val text = currentText()
        saveButton.isEnabled = false
        progress.visibility = View.VISIBLE
        f.write(path, text.toByteArray(Charsets.ISO_8859_1), progress = { _, _ -> }) { result ->
            progress.visibility = View.INVISIBLE
            result.onSuccess {
                main.log("FT: wrote $path")
                original = text
                onChanged()
                main.snack("Saved. The FlySight uses the new settings at the start of the next session.", long = true)
            }.onFailure { main.snack(main.fileError("Save", it), long = true) }
            updateBar()
        }
    }

    private companion object {
        const val PREF_OPEN = "open_sections"
        const val OTHER = "Other settings"
        /** Old keys that the firmware still reads. */
        val LEGACY_HELP = mapOf(
            "Window" to "An old setting: the two quiet zones together, in meters. " +
                "Tap to replace it with the two new settings.",
            "Device_ID" to "Not used. Navigation now works on all FlySight devices. Tap to remove it.",
            "Ble_Tx_Power" to "Not used. The firmware does not change the Bluetooth power. Tap to remove it.",
        )
        /** Old keys that the firmware does not use. */
        val UNUSED_KEYS = setOf("Device_ID", "Ble_Tx_Power")
        val KNOWN_KEYS: Set<String> = buildSet {
            for (section in ConfigSchema.sections) for (item in section.items) when (item) {
                is ConfigSchema.Group -> addAll(item.keys)
                is ConfigSchema.Field -> add(item.key)
            }
            add("Lon")
        }
    }
}

