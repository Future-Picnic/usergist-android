package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import studio.usergist.feedback.internal.ui.ResolvedTheme

/** Applies the React Native text-answer treatment to Material text fields. */
internal fun styleTextAnswer(
    layout: TextInputLayout,
    input: TextInputEditText,
    theme: ResolvedTheme,
    minimumHeightDp: Int,
    minimumLines: Int,
    maximumLines: Int,
    maxLength: Int? = null,
    showCounter: Boolean = false,
) {
    val context = layout.context
    val primary = theme.primary ?: context.resolveThemeColor(
        com.google.android.material.R.attr.colorPrimary,
        Color.BLACK,
    )
    val background = theme.background ?: context.resolveThemeColor(
        com.google.android.material.R.attr.colorSurface,
        Color.WHITE,
    )
    val text = theme.text ?: context.resolveThemeColor(
        com.google.android.material.R.attr.colorOnSurface,
        Color.BLACK,
    )
    val subtext = theme.subtext ?: context.resolveThemeColor(
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        Color.DKGRAY,
    )
    val border = theme.border ?: context.resolveThemeColor(
        com.google.android.material.R.attr.colorOutlineVariant,
        Color.LTGRAY,
    )
    val radius = context.dp(12).toFloat()
    val outline = GradientDrawable().apply {
        cornerRadius = radius
        setColor(background)
        setStroke(context.dp(1), border)
    }

    input.hint = input.hint ?: layout.hint
    layout.hint = null
    layout.isHintEnabled = false
    layout.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
    layout.background = outline
    layout.defaultHintTextColor = ColorStateList.valueOf(subtext)
    layout.isCounterEnabled = showCounter && maxLength != null && maxLength > 0
    if (layout.isCounterEnabled) {
        layout.counterMaxLength = maxLength ?: -1
        layout.setCounterTextColor(ColorStateList.valueOf(subtext))
        layout.setCounterOverflowTextColor(ColorStateList.valueOf(subtext))
    }

    input.setTextColor(text)
    input.setHintTextColor(subtext)
    input.setBackgroundColor(Color.TRANSPARENT)
    input.gravity = Gravity.TOP or Gravity.START
    val contentPadding = context.dp(12)
    input.setPaddingRelative(contentPadding, contentPadding, contentPadding, contentPadding)
    input.minHeight = context.dp(minimumHeightDp)
    input.minLines = minimumLines
    input.maxLines = maximumLines
    input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    input.typeface = theme.fontFamily?.let { Typeface.create(it, Typeface.NORMAL) }
        ?: Typeface.DEFAULT
    input.setOnFocusChangeListener { _, focused ->
        outline.setStroke(context.dp(1), if (focused) primary else border)
    }
}

private fun Context.resolveThemeColor(attribute: Int, fallback: Int): Int {
    val value = TypedValue()
    return if (theme.resolveAttribute(attribute, value, true)) value.data else fallback
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
