package studio.usergist.feedback.internal.ui.surface

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import androidx.appcompat.widget.AppCompatImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.CheckBox
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import coil.load

// Content primitives shared by feedback, surveys, messages and requests.
// Port of RN ui/SurfacePrimitives.tsx + QuestionImageHeader.tsx.

/** Image with a fixed aspect ratio, rounded corners and optional height cap. */
@SuppressLint("ViewConstructor")
internal class AspectImageView(
    context: Context,
    private val ratio: Float,
    radiusPx: Float,
    var maxHeightPx: Int = 0,
) : AppCompatImageView(context) {
    init {
        scaleType = ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // ratio <= 0 means a fixed display height of maxHeightPx (cropped).
        var height = if (ratio > 0f) (width / ratio).toInt() else maxHeightPx
        if (maxHeightPx > 0) height = minOf(height, maxHeightPx)
        setMeasuredDimension(width, height)
    }
}

internal fun Context.surfaceImage(
    url: String,
    radiusPx: Float,
    ratio: Float = 16f / 9f,
    maxHeightPx: Int = 0,
): AspectImageView = AspectImageView(this, ratio, radiusPx, maxHeightPx).apply {
    load(url) { crossfade(true) }
}

/** Title block with optional image and subtitle. */
internal fun Context.questionHeader(
    palette: SurfacePalette,
    title: String,
    subtitle: String?,
    imageUrl: String?,
    centered: Boolean = false,
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val align = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
    imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
        addView(
            surfaceImage(url, dpF(maxOf(8, palette.radiusDp - 6))),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dpPx(16) },
        )
    }
    addView(surfaceText(palette, title, 20f, palette.text, 700, 26f, align).apply {
        letterSpacing = -0.01f
        accessibilityHeading()
    }, matchWidth())
    subtitle?.takeIf { it.isNotBlank() }?.let {
        addView(surfaceText(palette, it, 15f, palette.subtext, 400, 21f, align), matchWidth().apply { topMargin = dpPx(6) })
    }
    setPadding(0, 0, 0, dpPx(16))
}

internal fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

internal fun View.accessibilityHeading() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) isAccessibilityHeading = true
}

/**
 * Selectable row. Selection is a tinted fill, a primary border and a
 * radio/checkbox indicator — never a solid flood of the brand colour.
 */
@SuppressLint("ViewConstructor")
internal class ChoiceRow(
    context: Context,
    private val palette: SurfacePalette,
    label: String,
    private val checkbox: Boolean,
    detail: String? = null,
    leading: String? = null,
) : LinearLayout(context) {
    var selectedState = false
        set(value) { field = value; refresh(false) }

    private val indicator = ChoiceIndicator(context, checkbox).apply {
        accent = palette.primary
        idle = SurfaceColor.withAlpha(palette.text, 0.25f)
        mark = palette.onPrimary
    }
    private val leadingView: TextView? = leading?.let {
        context.surfaceText(palette, it, 16f, palette.text, 700).apply { minWidth = context.dpPx(22) }
    }
    private val radiusPx = context.dpF(palette.radius(16))

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = label
        minimumHeight = context.dpPx(52)
        setPadding(context.dpPx(16), context.dpPx(12), context.dpPx(16), context.dpPx(12))
        leadingView?.let { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = context.dpPx(12) }) }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(context.surfaceText(palette, label, 16f, palette.text, 500, 21f), matchWidth())
        detail?.takeIf { it.isNotBlank() }?.let {
            texts.addView(context.surfaceText(palette, it, 13f, palette.subtext, 400, 18f), matchWidth().apply { topMargin = context.dpPx(2) })
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(indicator, LayoutParams(context.dpPx(22), context.dpPx(22)).apply { marginStart = context.dpPx(12) })
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = if (checkbox) CheckBox::class.java.name else RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = selectedState
            }
        }
        refresh(false)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        refresh(pressed)
    }

    private fun refresh(pressed: Boolean) {
        indicator.checked = selectedState
        leadingView?.setTextColor(if (selectedState) palette.primary else palette.text)
        background = roundRect(
            when {
                selectedState -> palette.tint
                pressed -> palette.fill
                else -> 0
            },
            radiusPx,
            context.dpPx(1.5f),
            if (selectedState) palette.primary else palette.border,
        )
    }
}

/** Text field with a soft neutral fill, visible focus border and optional counter. */
@SuppressLint("ViewConstructor")
internal class SurfaceField(
    context: Context,
    private val palette: SurfacePalette,
    hint: String?,
    accessibilityLabel: String?,
    multiline: Boolean,
    minHeightDp: Int = if (multiline) 112 else 50,
    private val maxLength: Int? = null,
    initial: String = "",
    inputType: Int? = null,
) : LinearLayout(context) {

    val editText: EditText = EditText(context)
    private val counter: TextView? = maxLength?.takeIf { it > 0 }?.let {
        context.surfaceText(palette, "", 12f, palette.subtext, 400, gravity = Gravity.END).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    private val radiusPx = context.dpF(palette.radius(14))
    var onTextChanged: ((String) -> Unit)? = null

    val value: String get() = editText.text?.toString().orEmpty()

    init {
        orientation = VERTICAL
        editText.apply {
            this.hint = hint
            contentDescription = accessibilityLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(palette.text)
            setHintTextColor(palette.placeholder)
            highlightColor = SurfaceColor.withAlpha(palette.primary, 0.3f)
            typeface = palette.typeface(400)
            setPadding(context.dpPx(14), context.dpPx(12), context.dpPx(14), context.dpPx(12))
            minHeight = context.dpPx(minHeightDp)
            gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.START
            this.inputType = inputType ?: if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (multiline) {
                isSingleLine = false
                maxLines = 8
                isVerticalScrollBarEnabled = true
            }
            maxLength?.takeIf { it > 0 }?.let { filters = arrayOf(InputFilter.LengthFilter(it)) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                textCursorDrawable = roundRect(palette.primary, 0f).apply { setSize(context.dpPx(2), 0) }
            }
            setText(initial)
            setOnFocusChangeListener { _, focused -> paint(focused) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    updateCounter()
                    onTextChanged?.invoke(s?.toString().orEmpty())
                }
            })
        }
        paint(false)
        addView(editText, matchWidth())
        counter?.let { addView(it, matchWidth().apply { topMargin = context.dpPx(6) }) }
        updateCounter()
    }

    fun setValue(text: String) {
        editText.setText(text)
        editText.setSelection(editText.text?.length ?: 0)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        editText.isEnabled = enabled
        editText.alpha = if (enabled) 1f else 0.6f
    }

    private fun paint(focused: Boolean) {
        editText.background = roundRect(
            palette.fill,
            radiusPx,
            context.dpPx(1.5f),
            if (focused) palette.primary else 0,
        )
    }

    private fun updateCounter() {
        val limit = maxLength ?: return
        val view = counter ?: return
        val length = value.length
        view.text = "$length/$limit"
        view.setTextColor(if (length >= limit * 0.9) palette.text else palette.subtext)
    }
}
