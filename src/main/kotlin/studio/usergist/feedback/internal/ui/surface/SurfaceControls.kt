package studio.usergist.feedback.internal.ui.surface

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.TextViewCompat

// Shared chrome for every UserGist surface: one Close, one Back, one primary
// action and one header layout. Port of RN ui/SurfaceControls.tsx.
//
// Layout rule used by feedback, surveys, messages and requests:
//   - Close is always the leading control in the header.
//   - Portrait: the primary action sits in a footer below the content.
//   - Landscape: the primary action moves to the trailing side of the header.

/** Styled text helper: size in sp, CSS-like weight, optional line height in sp. */
internal fun Context.surfaceText(
    palette: SurfacePalette,
    value: CharSequence?,
    sizeSp: Float,
    color: Int,
    weight: Int = 400,
    lineHeightSp: Float? = null,
    gravity: Int = Gravity.START,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = palette.typeface(weight)
    includeFontPadding = false
    this.gravity = gravity
    lineHeightSp?.let { lh ->
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, lh, resources.displayMetrics).toInt()
        TextViewCompat.setLineHeight(this, px)
    }
}

internal fun roundRect(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

internal fun oval(color: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** Marks a custom view as a button for TalkBack. */
internal fun View.announceAsButton(className: String = Button::class.java.name) {
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = className
        }
    }
}

/**
 * 48dp touch target with a 32dp circular face (quiet wash of the text colour)
 * and a drawn glyph. Used for Close (cross) and Back (chevron).
 */
internal fun Context.surfaceIconButton(
    palette: SurfacePalette,
    glyph: Glyph,
    label: String,
    regular: Boolean = false,
    onClick: () -> Unit,
): View = PressFrame(this).apply {
    contentDescription = label
    isClickable = true
    isFocusable = true
    announceAsButton()
    minimumWidth = dpPx(48)
    minimumHeight = dpPx(48)
    val face = FrameLayout(context).apply { background = oval(palette.iconFace) }
    val faceSize = dpPx(if (regular) 48 else 32)
    face.addView(GlyphView(context, glyph, palette.text).apply {
        if (regular) { scaleX = 1.15f; scaleY = 1.15f }
    }, FrameLayout.LayoutParams(faceSize, faceSize))
    addView(face, FrameLayout.LayoutParams(faceSize, faceSize, Gravity.CENTER))
    setOnClickListener { onClick() }
    layoutParams = ViewGroup.LayoutParams(dpPx(48), dpPx(48))
}

internal fun Context.surfaceClose(palette: SurfacePalette, label: String = "Close", regular: Boolean = false, onClick: () -> Unit) =
    surfaceIconButton(palette, Glyph.CLOSE, label, regular, onClick)

internal fun Context.surfaceBack(palette: SurfacePalette, label: String = "Back", regular: Boolean = false, onClick: () -> Unit) =
    surfaceIconButton(palette, Glyph.BACK, label, regular, onClick)

/**
 * Main and secondary actions. Labels wrap instead of truncating; the fixed
 * radius keeps a pill on one line and a soft rounded rectangle when wrapped.
 * Disabled primary actions use a neutral fill, not a faded brand colour.
 */
@SuppressLint("ViewConstructor")
internal class SurfaceButton(
    context: Context,
    private val palette: SurfacePalette,
    label: String,
    private val secondary: Boolean = false,
    compact: Boolean = false,
) : FrameLayout(context) {

    private val labelView: TextView = context.surfaceText(
        palette, label, if (compact) 15f else 16f, palette.text, weight = 700, gravity = Gravity.CENTER,
    ).apply { letterSpacing = 0.006f }
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(palette.onPrimary)
        visibility = View.GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val radiusPx = context.dpF(if (compact) 24 else 26)
    private var disabled = false
    var loading = false
        set(value) { field = value; refresh() }
    var label: String = label
        set(value) { field = value; labelView.text = value; contentDescription = value }

    init {
        isClickable = true
        isFocusable = true
        announceAsButton()
        contentDescription = label
        minimumHeight = context.dpPx(if (compact) 48 else 52)
        if (compact) minimumWidth = context.dpPx(88)
        val padH = context.dpPx(if (compact) 18 else 24)
        val padV = context.dpPx(if (compact) 10 else 12)
        setPadding(padH, padV, padH, padV)
        addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(spinner, LayoutParams(context.dpPx(22), context.dpPx(22), Gravity.CENTER))
        refresh()
    }

    fun setDisabled(value: Boolean) {
        disabled = value
        refresh()
    }

    fun setOnAction(action: () -> Unit) = setOnClickListener { if (!disabled && !loading) action() }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        refreshBackground(pressed && !disabled && !loading)
    }

    private fun refresh() {
        isEnabled = !disabled && !loading
        labelView.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        spinner.visibility = if (loading) View.VISIBLE else View.GONE
        labelView.setTextColor(
            when {
                secondary -> palette.text
                disabled && !loading -> palette.disabledLabel
                else -> palette.onPrimary
            },
        )
        stateDescriptionCompat(if (loading) "Loading" else null)
        refreshBackground(false)
    }

    private fun refreshBackground(pressed: Boolean) {
        alpha = 1f
        background = if (secondary) {
            roundRect(if (pressed) palette.fill else 0, radiusPx, context.dpPx(1.5f), palette.quietOutline)
        } else {
            if (pressed) alpha = 0.82f
            roundRect(if (disabled && !loading) palette.fill else palette.primary, radiusPx)
        }
    }

    private fun stateDescriptionCompat(value: String?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) stateDescription = value
    }
}

/**
 * Three-slot header. Leading and trailing size to their content; the centre
 * takes the remaining width (progress, title or step). Trailing actions wrap
 * within 320dp instead of squeezing the title.
 */
internal class SurfaceHeader(context: Context) {
    var balancedSides = false
        set(value) {
            field = value
            val width = view.context.dpPx(if (value) 104 else 48)
            leading.minimumWidth = width
            trailing.minimumWidth = width
            view.requestLayout()
        }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dpPx(48)
    }
    val leading = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumWidth = context.dpPx(48)
    }
    val center = FrameLayout(context)
    val trailing = MeasureLayout(context, context.dpPx(320)).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        minimumWidth = context.dpPx(48)
    }

    init {
        val gap = context.dpPx(8)
        view.addView(leading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        view.addView(center, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = gap
            marginEnd = gap
        })
        view.addView(trailing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setCenter(child: View?, fill: Boolean = false) {
        center.removeAllViews()
        val width = if (fill) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        child?.let { center.addView(it, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)) }
    }

    fun setTrailing(vararg children: View?) = fill(trailing, children.filterNotNull())

    fun setLeading(vararg children: View?) = fill(leading, children.filterNotNull())

    private fun fill(slot: LinearLayout, children: List<View>) {
        slot.removeAllViews()
        children.forEachIndexed { index, child ->
            (child.parent as? ViewGroup)?.removeView(child)
            slot.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = slot.context.dpPx(if (balancedSides) 8 else 4)
            })
        }
    }
}

/** Detaches [view] from any parent so it can move between header and footer. */
internal fun View.detach(): View {
    (parent as? ViewGroup)?.removeView(this)
    return this
}
