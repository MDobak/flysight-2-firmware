package com.mdobak.flysightstatus

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.min

/**
 * Material 3 for platform views: the colour roles from the dynamic colour of
 * the phone, the type scale, and the components that the app uses.
 */
@SuppressLint("InlinedApi") // The API 34 colour roles are used only on API 34 and later
class Ui(val context: Context) {

    val density = context.resources.displayMetrics.density

    fun dp(value: Int) = (value * density).toInt()

    fun dpf(value: Float) = value * density

    val dark = context.resources.configuration.isNightModeActive

    private val roles34 = Build.VERSION.SDK_INT >= 34

    private fun color(light34: Int, dark34: Int, light: Int, dark: Int) =
        context.getColor(if (roles34) (if (this.dark) dark34 else light34) else (if (this.dark) dark else light))

    private fun color(light: Int, dark: Int) = context.getColor(if (this.dark) dark else light)

    // ---- Colour roles ----

    val primary = color(android.R.color.system_primary_light, android.R.color.system_primary_dark,
        android.R.color.system_accent1_600, android.R.color.system_accent1_200)
    val onPrimary = color(android.R.color.system_on_primary_light, android.R.color.system_on_primary_dark,
        android.R.color.system_accent1_0, android.R.color.system_accent1_800)
    val primaryContainer = color(android.R.color.system_primary_container_light, android.R.color.system_primary_container_dark,
        android.R.color.system_accent1_100, android.R.color.system_accent1_700)
    val onPrimaryContainer = color(android.R.color.system_on_primary_container_light, android.R.color.system_on_primary_container_dark,
        android.R.color.system_accent1_900, android.R.color.system_accent1_100)
    val secondaryContainer = color(android.R.color.system_secondary_container_light, android.R.color.system_secondary_container_dark,
        android.R.color.system_accent2_100, android.R.color.system_accent2_700)
    val onSecondaryContainer = color(android.R.color.system_on_secondary_container_light, android.R.color.system_on_secondary_container_dark,
        android.R.color.system_accent2_900, android.R.color.system_accent2_100)
    val tertiaryContainer = color(android.R.color.system_tertiary_container_light, android.R.color.system_tertiary_container_dark,
        android.R.color.system_accent3_100, android.R.color.system_accent3_700)
    val onTertiaryContainer = color(android.R.color.system_on_tertiary_container_light, android.R.color.system_on_tertiary_container_dark,
        android.R.color.system_accent3_900, android.R.color.system_accent3_100)
    val surface = color(android.R.color.system_surface_light, android.R.color.system_surface_dark,
        android.R.color.system_neutral1_10, android.R.color.system_neutral1_900)
    val surfaceContainerLow = color(android.R.color.system_surface_container_low_light, android.R.color.system_surface_container_low_dark,
        android.R.color.system_neutral1_50, android.R.color.system_neutral1_900)
    val surfaceContainer = color(android.R.color.system_surface_container_light, android.R.color.system_surface_container_dark,
        android.R.color.system_neutral2_50, android.R.color.system_neutral1_800)
    val surfaceContainerHigh = color(android.R.color.system_surface_container_high_light, android.R.color.system_surface_container_high_dark,
        android.R.color.system_neutral2_50, android.R.color.system_neutral1_800)
    val surfaceContainerHighest = color(android.R.color.system_surface_container_highest_light, android.R.color.system_surface_container_highest_dark,
        android.R.color.system_neutral2_100, android.R.color.system_neutral2_800)
    val onSurface = color(android.R.color.system_on_surface_light, android.R.color.system_on_surface_dark,
        android.R.color.system_neutral1_900, android.R.color.system_neutral1_100)
    val onSurfaceVariant = color(android.R.color.system_on_surface_variant_light, android.R.color.system_on_surface_variant_dark,
        android.R.color.system_neutral2_700, android.R.color.system_neutral2_200)
    val outline = color(android.R.color.system_outline_light, android.R.color.system_outline_dark,
        android.R.color.system_neutral2_500, android.R.color.system_neutral2_400)
    val outlineVariant = color(android.R.color.system_outline_variant_light, android.R.color.system_outline_variant_dark,
        android.R.color.system_neutral2_200, android.R.color.system_neutral2_700)
    val inverseSurface = color(android.R.color.system_neutral1_800, android.R.color.system_neutral1_100)
    val inverseOnSurface = color(android.R.color.system_neutral1_50, android.R.color.system_neutral1_800)
    val inversePrimary = color(android.R.color.system_accent1_200, android.R.color.system_accent1_600)
    val error = if (roles34) color(android.R.color.system_error_light, android.R.color.system_error_dark)
    else if (dark) Color.rgb(0xF2, 0xB8, 0xB5) else Color.rgb(0xB3, 0x26, 0x1E)
    val errorContainer = if (roles34) color(android.R.color.system_error_container_light, android.R.color.system_error_container_dark)
    else if (dark) Color.rgb(0x8C, 0x1D, 0x18) else Color.rgb(0xF9, 0xDE, 0xDC)
    val onErrorContainer = if (roles34) color(android.R.color.system_on_error_container_light, android.R.color.system_on_error_container_dark)
    else if (dark) Color.rgb(0xF9, 0xDE, 0xDC) else Color.rgb(0x41, 0x0E, 0x0B)

    /** Colours for tracks in charts on the theme surface. */
    val trackColors: IntArray get() = if (dark) TRACK_COLORS_DARK else TRACK_COLORS_LIGHT

    fun alpha(color: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    // ---- Type scale ----

    enum class Type(val sp: Float, val medium: Boolean, val lineSp: Float, val trackingSp: Float) {
        DISPLAY_SMALL(36f, false, 44f, 0f),
        HEADLINE_MEDIUM(28f, false, 36f, 0f),
        HEADLINE_SMALL(24f, false, 32f, 0f),
        TITLE_LARGE(22f, false, 28f, 0f),
        TITLE_MEDIUM(16f, true, 24f, 0.15f),
        TITLE_SMALL(14f, true, 20f, 0.1f),
        BODY_LARGE(16f, false, 24f, 0.5f),
        BODY_MEDIUM(14f, false, 20f, 0.25f),
        BODY_SMALL(12f, false, 16f, 0.4f),
        LABEL_LARGE(14f, true, 20f, 0.1f),
        LABEL_MEDIUM(12f, true, 16f, 0.5f),
        LABEL_SMALL(11f, true, 16f, 0.5f),
    }

    private val mediumFace: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    private val regularFace: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)

    fun style(view: TextView, type: Type, color: Int = onSurface) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp)
        view.typeface = if (type.medium) mediumFace else regularFace
        view.letterSpacing = type.trackingSp / type.sp
        view.lineHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, type.lineSp, context.resources.displayMetrics).toInt()
        view.setTextColor(color)
    }

    fun text(type: Type, text: CharSequence = "", color: Int = onSurface) = TextView(context).apply {
        style(this, type, color)
        this.text = text
    }

    // ---- Shapes and states ----

    fun shape(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
        if (strokeColor != null) setStroke(dpf(strokeDp).toInt().coerceAtLeast(1), strokeColor)
    }

    /** A ripple with the state layer colour of [content] in the shape [mask]. */
    fun ripple(content: Int, background: Drawable?, radiusDp: Float = 0f): RippleDrawable {
        val mask = shape(Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(alpha(content, 0.12f)), background, mask)
    }

    private fun unboundedRipple(content: Int) = RippleDrawable(ColorStateList.valueOf(alpha(content, 0.12f)), null, null)

    fun icon(res: Int, tint: Int = onSurfaceVariant, sizeDp: Int = 24): ImageView = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun drawable(res: Int, tint: Int, sizeDp: Int = 18): Drawable = context.getDrawable(res)!!.mutate().apply {
        setTint(tint)
        setBounds(0, 0, dp(sizeDp), dp(sizeDp))
    }

    // ---- Buttons ----

    enum class ButtonStyle { FILLED, TONAL, OUTLINED, TEXT }

    /** A text view that dims its content when disabled, as Material buttons do. */
    class M3Button(context: Context) : TextView(context) {
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.38f
        }
    }

    fun button(
        style: ButtonStyle,
        label: String,
        iconRes: Int? = null,
        container: Int? = null,
        content: Int? = null,
        onClick: () -> Unit,
    ): M3Button {
        val bg = container ?: when (style) {
            ButtonStyle.FILLED -> primary
            ButtonStyle.TONAL -> secondaryContainer
            else -> Color.TRANSPARENT
        }
        val fg = content ?: when (style) {
            ButtonStyle.FILLED -> onPrimary
            ButtonStyle.TONAL -> onSecondaryContainer
            else -> primary
        }
        return M3Button(context).apply {
            style(this, Type.LABEL_LARGE, fg)
            text = label
            gravity = Gravity.CENTER
            isSingleLine = true
            minHeight = dp(48)
            minWidth = dp(if (style == ButtonStyle.TEXT) 48 else 64)
            val shape = shape(bg, 20f, if (style == ButtonStyle.OUTLINED) outline else null)
            background = InsetDrawable(ripple(fg, shape, 20f), 0, dp(4), 0, dp(4))
            val side = if (style == ButtonStyle.TEXT) 12 else 24
            setPadding(dp(if (iconRes != null) side - 8 else side), 0, dp(side), 0)
            if (iconRes != null) {
                setCompoundDrawablesRelative(drawable(iconRes, fg), null, null, null)
                compoundDrawablePadding = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    fun iconButton(res: Int, description: String, tint: Int = onSurfaceVariant, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        contentDescription = description
        tooltipText = description
        scaleType = ImageView.ScaleType.CENTER
        background = unboundedRipple(tint)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    /** Extended floating action button. */
    fun fab(label: String, iconRes: Int, onClick: () -> Unit) = M3Button(context).apply {
        style(this, Type.LABEL_LARGE, onPrimaryContainer)
        text = label
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(56)
        setPadding(dp(16), 0, dp(20), 0)
        setCompoundDrawablesRelative(drawable(iconRes, onPrimaryContainer, 24), null, null, null)
        compoundDrawablePadding = dp(12)
        background = ripple(onPrimaryContainer, shape(primaryContainer, 16f), 16f)
        elevation = dpf(6f)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /** A floating button on a map (as in Google Maps). 40 dp to see, 48 dp to touch. */
    fun mapButton(res: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(onSurface)
        contentDescription = description
        tooltipText = description
        scaleType = ImageView.ScaleType.CENTER
        background = InsetDrawable(ripple(onSurface, shape(surfaceContainerHigh, 12f), 12f), dp(4))
        elevation = dpf(3f)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val i = dp(4)
                outline.setRoundRect(i, i, view.width - i, view.height - i, dpf(12f))
            }
        }
        setOnClickListener { onClick() }
    }

    /**
     * Segmented control with icons. Exactly one segment is selected. One rounded
     * outline goes around the segments, with thin lines between them.
     */
    inner class IconToggleGroup(items: List<Pair<Int, String>>, selected: Int, private val onSelect: (Int) -> Unit) :
        LinearLayout(context) {

        val buttons: List<ImageView> = items.mapIndexed { i, (res, label) ->
            ImageView(context).apply {
                setImageResource(res)
                contentDescription = label
                tooltipText = label
                scaleType = ImageView.ScaleType.CENTER
                setOnClickListener { if (i != this@IconToggleGroup.selected) select(i, notify = true) }
            }
        }

        var selected = selected
            private set

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // One outline around the group. The fills of the segments are clipped to it.
            foreground = shape(Color.TRANSPARENT, 18f, outline)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) =
                    o.setRoundRect(0, 0, view.width, view.height, dpf(18f))
            }
            clipToOutline = true
            for ((i, b) in buttons.withIndex()) {
                if (i > 0) addView(View(context).apply { setBackgroundColor(outline) }, LayoutParams(dp(1), MATCH))
                addView(b, LayoutParams(dp(44), dp(36)))
            }
            select(selected, notify = false)
        }

        fun select(index: Int, notify: Boolean = false) {
            selected = index
            for ((i, b) in buttons.withIndex()) {
                val on = i == index
                val fg = if (on) onSecondaryContainer else onSurfaceVariant
                val fill = ColorDrawable(if (on) secondaryContainer else Color.TRANSPARENT)
                b.imageTintList = ColorStateList.valueOf(fg)
                b.background = RippleDrawable(ColorStateList.valueOf(alpha(fg, 0.12f)), fill, ColorDrawable(Color.WHITE))
                b.isSelected = on
                b.stateDescription = if (on) "Selected" else null
            }
            if (notify) onSelect(index)
        }
    }

    // ---- Selection controls ----

    /**
     * Filter chip. A selected chip shows a check mark in place of [leading],
     * except when [showCheck] is false (for a legend).
     */
    inner class Chip(
        label: String,
        leading: Drawable?,
        private val showCheck: Boolean = true,
        private val onToggle: (Boolean) -> Unit,
    ) : TextView(context) {
        private val leadingIcon = leading
        var checked = false
            set(value) {
                field = value
                update()
            }

        init {
            style(this, Type.LABEL_LARGE, onSurfaceVariant)
            text = label
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            minHeight = dp(48)
            compoundDrawablePadding = dp(8)
            setOnClickListener {
                checked = !checked
                onToggle(checked)
            }
            update()
        }

        private fun update() {
            val bg = if (checked) shape(secondaryContainer, 8f) else shape(Color.TRANSPARENT, 8f, outlineVariant)
            val fg = if (checked) onSecondaryContainer else onSurfaceVariant
            background = InsetDrawable(ripple(fg, bg, 8f), 0, dp(8), 0, dp(8))
            setTextColor(fg)
            val icon = if (checked && showCheck) drawable(R.drawable.ic_check, fg) else leadingIcon
            setCompoundDrawablesRelative(icon, null, null, null)
            setPadding(dp(if (icon != null) 8 else 16), 0, dp(16), 0)
            isSelected = checked
        }
    }

    fun chipRow(vararg chips: View) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding(dp(16), 0, dp(16), 0)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((i, c) in chips.withIndex()) {
                addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(8) })
            }
        })
    }

    /** Segmented button with one selection. */
    inner class Segmented(labels: List<String>, selected: Int, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
        private val items = mutableListOf<LinearLayout>()
        private val texts = mutableListOf<TextView>()
        private val checks = mutableListOf<ImageView>()
        var selected = selected
            private set

        init {
            orientation = HORIZONTAL
            for ((i, label) in labels.withIndex()) {
                // The check icon and the label are centred together, as in M3.
                val check = ImageView(context).apply { setImageResource(R.drawable.ic_check) }
                val text = TextView(context).apply {
                    style(this, Type.LABEL_LARGE)
                    this.text = label
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val item = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(48)
                    setPadding(dp(12), 0, dp(12), 0)
                    addView(check, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                    addView(text, LayoutParams(WRAP, WRAP).apply { weight = 0f })
                    setOnClickListener { select(i, notify = true) }
                    contentDescription = label
                }
                items += item
                texts += text
                checks += check
                addView(item, LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = -dp(1) })
            }
            select(selected, notify = false)
        }

        fun select(index: Int, notify: Boolean = false) {
            selected = index
            val r = dpf(20f)
            for ((i, item) in items.withIndex()) {
                val on = i == index
                val left = if (i == 0) r else 0f
                val right = if (i == items.lastIndex) r else 0f
                val bg = GradientDrawable().apply {
                    setColor(if (on) secondaryContainer else Color.TRANSPARENT)
                    setStroke(dp(1), outline)
                    cornerRadii = floatArrayOf(left, left, right, right, right, right, left, left)
                }
                val fg = if (on) onSecondaryContainer else onSurface
                item.background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(alpha(fg, 0.12f)), bg, bg), 0, dp(4), 0, dp(4))
                texts[i].setTextColor(fg)
                checks[i].imageTintList = ColorStateList.valueOf(fg)
                checks[i].visibility = if (on) VISIBLE else GONE
                item.isSelected = on
            }
            if (notify) onSelect(index)
        }
    }

    /** Primary tabs. */
    inner class Tabs(labels: List<String>, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
        private val items = mutableListOf<TextView>()

        init {
            orientation = HORIZONTAL
            for ((i, label) in labels.withIndex()) {
                val item = TextView(context).apply {
                    style(this, Type.TITLE_SMALL)
                    text = label
                    gravity = Gravity.CENTER
                    isSingleLine = true
                    minHeight = dp(48)
                    setPadding(dp(4), 0, dp(4), 0)
                    setOnClickListener { select(i, notify = true) }
                }
                items += item
                addView(item, LayoutParams(0, WRAP, 1f))
            }
            background = LayerDrawable(arrayOf(ColorDrawable(outlineVariant), ColorDrawable(surface))).apply {
                setLayerInset(1, 0, 0, 0, dp(1))
            }
        }

        fun select(index: Int, notify: Boolean = false) {
            for ((i, item) in items.withIndex()) {
                val on = i == index
                item.setTextColor(if (on) primary else onSurfaceVariant)
                val indicator = GradientDrawable().apply {
                    setColor(if (on) primary else Color.TRANSPARENT)
                    cornerRadii = floatArrayOf(dpf(3f), dpf(3f), dpf(3f), dpf(3f), 0f, 0f, 0f, 0f)
                }
                val layer = LayerDrawable(arrayOf(indicator)).apply {
                    setLayerGravity(0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    setLayerSize(0, -1, dp(3))
                    setLayerWidth(0, (item.paint.measureText(item.text.toString()) + dp(8)).toInt())
                }
                item.background = RippleDrawable(ColorStateList.valueOf(alpha(primary, 0.12f)), layer, ColorDrawable(Color.WHITE))
                item.isSelected = on
            }
            if (notify) onSelect(index)
        }
    }

    /** Material 3 switch. */
    fun switch(checked: Boolean = false, onChange: ((Boolean) -> Unit)? = null) = Switch(context).apply {
        showText = false
        text = null
        switchMinWidth = dp(52)
        val trackOn = shape(primary, 16f).apply { setSize(dp(52), dp(32)) }
        val trackOff = shape(surfaceContainerHighest, 16f, outline, 2f).apply { setSize(dp(52), dp(32)) }
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), trackOn)
            addState(intArrayOf(), trackOff)
        }
        fun dot(color: Int, size: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(dp(size), dp(size))
        }
        // The thumb is a 32 dp box with a centred dot. Both states have the same width and no padding,
        // so the dot stays in the track (centre at 16 dp when off and at 36 dp when on, as in M3).
        fun thumb(color: Int, size: Int) = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(Color.TRANSPARENT); setSize(dp(32), dp(32)) },
            dot(color, size),
        )).apply {
            setLayerGravity(1, Gravity.CENTER)
            setLayerSize(1, dp(size), dp(size))
        }
        thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumb(onPrimary, 24))
            addState(intArrayOf(), thumb(outline, 16))
        }
        thumbTintList = null
        trackTintList = null
        isChecked = checked
        if (onChange != null) setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    fun linearProgress() = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(primary)
        progressBackgroundTintList = ColorStateList.valueOf(secondaryContainer)
        indeterminateTintList = ColorStateList.valueOf(primary)
        minHeight = dp(4)
        maxHeight = dp(4)
    }

    // ---- Containers ----

    fun column(paddingDp: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
    }

    fun row(vararg views: View, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
        for (v in views) addView(v)
    }

    fun card(color: Int = surfaceContainerHighest, radiusDp: Float = 12f, paddingDp: Int = 16) = column(paddingDp).apply {
        background = shape(color, radiusDp)
    }

    fun scroll(content: View, bar: TopBar? = null) = ScrollView(context).apply {
        isFillViewport = true
        addView(content)
        if (bar != null) setOnScrollChangeListener { _, _, y, _, _ -> bar.scrolled = y > 0 }
    }

    fun space(heightDp: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp)) }

    fun divider() = View(context).apply {
        setBackgroundColor(outlineVariant)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    /** List subheader. */
    fun header(text: String, trailing: View? = null): View {
        val label = text(Type.TITLE_SMALL, text, primary)
        if (trailing == null) return label.apply { setPadding(dp(16), dp(20), dp(16), dp(8)) }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(4), 0)
            addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(trailing)
        }
    }

    /** Help text under a group. */
    fun note(text: String) = text(Type.BODY_SMALL, text, onSurfaceVariant).apply {
        setPadding(dp(16), dp(4), dp(16), dp(8))
    }

    /** List item with one or two lines. */
    inner class ListItem(
        headline: CharSequence,
        supporting: CharSequence? = null,
        leading: View? = null,
        trailing: View? = null,
    ) : LinearLayout(context) {
        val headline = text(Type.BODY_LARGE, headline)
        val supporting = text(Type.BODY_MEDIUM, supporting ?: "", onSurfaceVariant)
        var leading: View? = null
            private set

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (supporting == null) 56 else 72)
            setPadding(dp(16), dp(8), dp(if (trailing != null) 8 else 24), dp(8))
            background = ripple(onSurface, null)
            if (leading != null) setLeading(leading)
            val texts = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(this@ListItem.headline)
                addView(this@ListItem.supporting)
            }
            if (supporting == null) this.supporting.visibility = GONE
            addView(texts, LayoutParams(0, WRAP, 1f))
            if (trailing != null) addView(trailing)
        }

        fun setLeading(view: View) {
            leading?.let { removeView(it) }
            leading = view
            val lp = (view.layoutParams as? LayoutParams) ?: LayoutParams(WRAP, WRAP)
            lp.marginEnd = dp(16)
            addView(view, 0, lp)
        }

        fun setSupporting(text: CharSequence?) {
            supporting.text = text ?: ""
            supporting.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
            minimumHeight = dp(if (text.isNullOrEmpty()) 56 else 72)
        }

        fun onClick(action: () -> Unit) = apply { setOnClickListener { action() } }

        fun onLongClick(action: () -> Unit) = apply { setOnLongClickListener { action(); true } }
    }

    /** A 40 dp circle with an icon, for the start of a list item. */
    fun avatar(res: Int, container: Int = secondaryContainer, content: Int = onSecondaryContainer) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(content)
        scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(container)
        }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    /** Empty state: an icon, a title, a text, and an optional action. */
    fun emptyState(iconRes: Int, title: String, body: String, action: View? = null) = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(32), dp(48), dp(32), dp(32))
        addView(icon(iconRes, onSurfaceVariant, 48))
        addView(text(Type.TITLE_MEDIUM, title).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        })
        addView(text(Type.BODY_MEDIUM, body, onSurfaceVariant).apply { gravity = Gravity.CENTER })
        if (action != null) addView(action, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
    }

    // ---- App bars ----

    /** Small top app bar. It adds the status bar inset to its top padding. */
    inner class TopBar(title: String) : LinearLayout(context) {
        val title = text(Type.TITLE_LARGE, title).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val nav = FrameLayout(context)
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private var insetTop = 0

        var scrolled = false
            set(value) {
                if (field == value) return
                field = value
                setBackgroundColor(if (value) surfaceContainer else surface)
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            addView(nav)
            addView(this.title, LayoutParams(0, WRAP, 1f))
            addView(actions)
            updatePadding()
            setOnApplyWindowInsetsListener { _, insets ->
                insetTop = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
                updatePadding()
                insets
            }
        }

        private fun updatePadding() {
            val start = if (nav.childCount > 0) dp(4) else dp(16)
            setPadding(start, insetTop, dp(4), 0)
            minimumHeight = insetTop + dp(64)
        }

        fun setNavigation(res: Int, description: String, onClick: () -> Unit) {
            nav.removeAllViews()
            nav.addView(iconButton(res, description, onSurface, onClick))
            this.title.setPadding(dp(4), 0, 0, 0)
            updatePadding()
        }

        fun clearNavigation() {
            nav.removeAllViews()
            this.title.setPadding(0, 0, 0, 0)
            updatePadding()
        }

        fun addAction(view: View) = actions.addView(view)
    }

    /** Navigation bar with 3 to 5 destinations. It adds the navigation bar inset. */
    inner class NavBar(items: List<Pair<Int, String>>, private val onSelect: (Int) -> Unit) : LinearLayout(context) {
        private val indicators = mutableListOf<ImageView>()
        private val labels = mutableListOf<TextView>()
        private var insetBottom = 0

        init {
            orientation = HORIZONTAL
            setBackgroundColor(surfaceContainer)
            for ((i, item) in items.withIndex()) {
                val indicator = ImageView(context).apply {
                    setImageResource(item.first)
                    scaleType = ImageView.ScaleType.CENTER
                }
                val label = text(Type.LABEL_MEDIUM, item.second, onSurfaceVariant).apply { gravity = Gravity.CENTER }
                val cell = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(16))
                    // Material uses 64 dp. Six items do not fit on a narrow phone.
                    addView(indicator, LayoutParams(dp(if (items.size > 5) 56 else 64), dp(32)))
                    addView(label, LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                    contentDescription = item.second
                    setOnClickListener { select(i, notify = true) }
                }
                indicators += indicator
                labels += label
                addView(cell, LayoutParams(0, WRAP, 1f))
            }
            setOnApplyWindowInsetsListener { _, insets ->
                insetBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                setPadding(0, 0, 0, insetBottom)
                insets
            }
        }

        fun select(index: Int, notify: Boolean = false) {
            for (i in indicators.indices) {
                val on = i == index
                val fg = if (on) onSecondaryContainer else onSurfaceVariant
                indicators[i].imageTintList = ColorStateList.valueOf(fg)
                indicators[i].background = ripple(fg, shape(if (on) secondaryContainer else Color.TRANSPARENT, 16f), 16f)
                labels[i].setTextColor(if (on) onSurface else onSurfaceVariant)
                labels[i].typeface = if (on) Typeface.create(Typeface.DEFAULT, 700, false) else mediumFace
                (indicators[i].parent as View).isSelected = on
            }
            if (notify) onSelect(index)
        }
    }

    // ---- Dialogs ----

    class DialogButton(val label: String, val action: (() -> Unit)? = null)

    /** Material 3 basic dialog. */
    fun dialog(
        title: String?,
        message: CharSequence? = null,
        content: View? = null,
        positive: DialogButton? = null,
        negative: DialogButton? = DialogButton("Cancel"),
        neutral: DialogButton? = null,
        scrollContent: Boolean = false,
    ): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = column().apply {
            background = shape(surfaceContainerHigh, 28f)
            setPadding(0, dp(24), 0, dp(20))
        }
        if (title != null) box.addView(text(Type.HEADLINE_SMALL, title).apply { setPadding(dp(24), 0, dp(24), dp(16)) })
        if (message != null) box.addView(text(Type.BODY_MEDIUM, message, onSurfaceVariant).apply {
            setPadding(dp(24), 0, dp(24), dp(16))
        })
        if (content != null) {
            if (scrollContent) {
                val maxHeight = (context.resources.displayMetrics.heightPixels * 0.55).toInt()
                val scroll = object : ScrollView(context) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
                    }
                }
                scroll.addView(content)
                box.addView(scroll)
            } else {
                box.addView(content)
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        fun add(b: DialogButton?) {
            if (b == null) return
            buttons.addView(button(ButtonStyle.TEXT, b.label) {
                dialog.dismiss()
                b.action?.invoke()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        if (neutral != null) {
            add(neutral)
            buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }
        add(negative)
        add(positive)
        if (buttons.childCount > 0) box.addView(buttons)
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = min(context.resources.displayMetrics.widthPixels - dp(48), dp(560))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    /** Dialog with a list of choices. A tap selects and closes. */
    fun choiceDialog(title: String, options: List<String>, selected: Int, message: String? = null, onSelect: (Int) -> Unit): Dialog {
        val list = column()
        lateinit var dialog: Dialog
        for ((i, option) in options.withIndex()) {
            val on = i == selected
            val radio = icon(if (on) R.drawable.ic_radio_on else R.drawable.ic_radio_off, if (on) primary else onSurfaceVariant)
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(24), 0, dp(24), 0)
                background = ripple(onSurface, null)
                addView(radio, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
                addView(text(Type.BODY_LARGE, option), LinearLayout.LayoutParams(0, WRAP, 1f))
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(i)
                }
            })
        }
        dialog = dialog(title, message, list, scrollContent = true)
        return dialog
    }

    /** Dialog with a list of actions. A tap closes the dialog and runs the action. */
    /**
     * An M3 menu below [anchor], at its end edge. [checked] is the index of the
     * selected item, or -1. The selected item has a tonal background and a check.
     */
    fun popupMenu(anchor: View, items: List<Pair<String, () -> Unit>>, checked: Int = -1): PopupWindow {
        val list = column().apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
        lateinit var popup: PopupWindow
        for ((i, item) in items.withIndex()) {
            val on = i == checked
            val fg = if (on) onSecondaryContainer else onSurface
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(12), 0, dp(16), 0)
                if (checked >= 0) {
                    addView(icon(R.drawable.ic_check, fg).apply { visibility = if (on) View.VISIBLE else View.INVISIBLE },
                        LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
                }
                addView(text(Type.BODY_LARGE, item.first, fg).apply { isSingleLine = true })
                background = ripple(fg, if (on) shape(secondaryContainer, 12f) else null, 12f)
                isSelected = on
                setOnClickListener {
                    popup.dismiss()
                    item.second()
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = list.measuredWidth.coerceIn(dp(112), dp(280))
        popup = PopupWindow(ScrollView(context).apply { addView(list) }, width, WRAP, true).apply {
            setBackgroundDrawable(shape(surfaceContainer, 16f))
            elevation = dpf(3f)
            animationStyle = android.R.style.Animation_Dialog
        }
        popup.showAsDropDown(anchor, -dp(8), -dp(8), Gravity.END)
        return popup
    }

    fun menuDialog(title: String?, items: List<Pair<String, () -> Unit>>, message: String? = null): Dialog {
        val list = column()
        lateinit var dialog: Dialog
        for ((label, action) in items) {
            list.addView(text(Type.BODY_LARGE, label).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(24), 0, dp(24), 0)
                background = ripple(onSurface, null)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        dialog = dialog(title, message, list, scrollContent = true)
        return dialog
    }

    /** Outlined text field. */
    fun textField(value: String, inputType: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply {
        style(this, Type.BODY_LARGE)
        setText(value)
        this.inputType = inputType
        isSingleLine = true
        setHintTextColor(onSurfaceVariant)
        highlightColor = alpha(primary, 0.3f)
        textCursorDrawable = shape(primary, 1f).apply { setSize(dp(2), dp(20)) }
        val normal = shape(Color.TRANSPARENT, 4f, outline, 1f)
        val focused = shape(Color.TRANSPARENT, 4f, primary, 2f)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minHeight = dp(56)
    }

    /** Dialog with one text field. [suffix] is shown after the field (a unit). */
    fun inputDialog(
        title: String,
        value: String,
        message: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        suffix: String? = null,
        hint: ((String) -> String?)? = null,
        okLabel: String = "OK",
        onOk: (String) -> Unit,
    ): Dialog {
        val field = textField(value, inputType)
        val hintText = text(Type.BODY_SMALL, "", onSurfaceVariant).apply { setPadding(dp(16), dp(4), dp(16), 0) }
        val fieldRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (suffix != null) addView(text(Type.BODY_LARGE, suffix, onSurfaceVariant).apply { setPadding(dp(12), 0, 0, 0) })
        }
        val content = column().apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(fieldRow)
            if (hint != null) addView(hintText)
        }
        if (hint != null) {
            fun update() {
                val h = hint(field.text.toString())
                hintText.text = h ?: ""
                hintText.visibility = if (h == null) View.GONE else View.VISIBLE
            }
            update()
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = update()
            })
        }
        val dialog = dialog(title, message, content, DialogButton(okLabel) { onOk(field.text.toString().trim()) })
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        field.requestFocus()
        field.setSelection(field.text.length)
        return dialog
    }

    // ---- Snackbar ----

    /** Shows snackbars at the bottom of [host]. */
    inner class SnackbarHost(private val host: FrameLayout) {
        private var current: View? = null
        private val hide = Runnable { dismiss() }
        var bottomMarginPx = 0

        fun show(message: String, actionLabel: String? = null, long: Boolean = false, action: (() -> Unit)? = null) {
            current?.let { host.removeView(it) }
            host.removeCallbacks(hide)
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(16), dp(6), dp(if (actionLabel != null) 8 else 16), dp(6))
                background = shape(inverseSurface, 4f)
                elevation = dpf(6f)
                addView(text(Type.BODY_MEDIUM, message, inverseOnSurface), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (actionLabel != null && action != null) {
                    addView(button(ButtonStyle.TEXT, actionLabel, content = inversePrimary) {
                        dismiss()
                        action()
                    })
                }
            }
            host.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                setMargins(dp(12), 0, dp(12), dp(12) + bottomMarginPx)
            })
            current = bar
            if (ValueAnimator.areAnimatorsEnabled()) {
                bar.alpha = 0f
                bar.translationY = dpf(12f)
                bar.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator(2f)).start()
            }
            host.postDelayed(hide, if (long || actionLabel != null) 6000 else 3500)
        }

        fun dismiss() {
            val bar = current ?: return
            current = null
            host.removeCallbacks(hide)
            if (ValueAnimator.areAnimatorsEnabled()) {
                bar.animate().alpha(0f).setDuration(120).withEndAction { host.removeView(bar) }.start()
            } else {
                host.removeView(bar)
            }
        }
    }

    // ---- Window ----

    /** Edge-to-edge window with system bar icons that match the theme. */
    fun setUpWindow(activity: Activity) {
        activity.window.setDecorFitsSystemWindows(false)
        activity.window.setBackgroundDrawable(ColorDrawable(surface))
        activity.window.isNavigationBarContrastEnforced = false
        val light = if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        // The decor view must exist before the insets controller. It does not exist before setContentView.
        activity.window.decorView.windowInsetsController?.setSystemBarsAppearance(
            light,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Track colours (the Google chart palette). Light for light surfaces and for the map. */
        val TRACK_COLORS_LIGHT = intArrayOf(
            0xFF1A73E8.toInt(), 0xFFE8710A.toInt(), 0xFF1E8E3E.toInt(), 0xFFD01884.toInt(),
            0xFF9334E6.toInt(), 0xFF12B5CB.toInt(), 0xFFB06000.toInt(), 0xFF5F6368.toInt(),
        )
        val TRACK_COLORS_DARK = intArrayOf(
            0xFF8AB4F8.toInt(), 0xFFFCAD70.toInt(), 0xFF81C995.toInt(), 0xFFFF8BCB.toInt(),
            0xFFC58AF9.toInt(), 0xFF78D9EC.toInt(), 0xFFFDD663.toInt(), 0xFFBDC1C6.toInt(),
        )
    }
}
