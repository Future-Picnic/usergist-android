package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.ui.surface.PressFrame
import studio.usergist.feedback.internal.ui.surface.SurfaceColor
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.oval
import studio.usergist.feedback.internal.ui.surface.questionHeader
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceText

/**
 * Rating question (port of RN questions/RatingQuestion.tsx). Honours the
 * dashboard display: stars (default), numeric chips, or emoji (5-point only).
 * The row is centred.
 */
internal class RatingQuestionView(
    context: Context,
    private val question: Question.Rating,
    theme: ResolvedTheme,
    initialValue: Int? = null,
    includeHeader: Boolean = true,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null

    private companion object {
        val EMOJI = listOf("😡", "😕", "😐", "🙂", "😍")
    }

    private val palette = SurfacePalette.from(theme)
    private val scale = if (question.scale == 10) 10 else 5
    // Not named `display`: inside the Item view that would resolve to View.getDisplay().
    private val ratingDisplay = if (question.display == Question.Rating.Display.EMOJI && scale != 5) {
        Question.Rating.Display.STARS
    } else {
        question.display
    }
    private var selectedValue: Int? = initialValue?.takeIf { it in 1..scale }
    private val items = mutableListOf<Item>()
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = root

    init {
        if (includeHeader) {
            root.addView(context.questionHeader(palette, question.title, question.subtitle, question.imageUrl, centered = true), matchWidth())
        }
        // Wrap like the RN flex row: one line when it fits, otherwise rows of five.
        val itemWidth = if (ratingDisplay ==Question.Rating.Display.EMOJI) 52 else 48
        val fitsOneRow = context.resources.configuration.screenWidthDp - 48 >= scale * (itemWidth + 8)
        val perRow = if (fitsOneRow) scale else 5
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        var row: LinearLayout? = null
        for (i in 1..scale) {
            val index = (i - 1) % perRow
            if (index == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    if (i > 1) topMargin = context.dpPx(8)
                })
            }
            val item = Item(context, i)
            items += item
            requireNotNull(row).addView(item, LinearLayout.LayoutParams(
                if (ratingDisplay == Question.Rating.Display.NUMERIC) ViewGroup.LayoutParams.WRAP_CONTENT else context.dpPx(itemWidth),
                context.dpPx(itemWidth),
            ).apply { if (index > 0) marginStart = context.dpPx(8) })
        }
        root.addView(rows, matchWidth())
        if (!question.lowLabel.isNullOrBlank() || !question.highLabel.isNullOrBlank()) {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(context.surfaceText(palette, question.lowLabel.orEmpty(), 13f, palette.subtext),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(context.surfaceText(palette, question.highLabel.orEmpty(), 13f, palette.subtext, gravity = Gravity.END),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpPx(16) })
            }, matchWidth().apply { topMargin = context.dpPx(10) })
        }
        render()
    }

    override fun currentAnswer(): PromptAnswerValue {
        val v = selectedValue ?: return PromptAnswerValue.None
        return PromptAnswerValue.Number(v.toDouble())
    }

    override fun isSubmittable(): Boolean = selectedValue != null

    private fun render() = items.forEach { it.paint(false) }

    private inner class Item(context: Context, val value: Int) : PressFrame(context, 1f) {
        private val label: TextView = TextView(context).apply {
            includeFontPadding = false
            gravity = Gravity.CENTER
            when (ratingDisplay) {
                Question.Rating.Display.STARS -> {
                    text = "★"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                }
                Question.Rating.Display.EMOJI -> {
                    text = EMOJI[value - 1]
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                }
                Question.Rating.Display.NUMERIC -> {
                    text = value.toString()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = palette.typeface(700)
                }
            }
        }

        init {
            isClickable = true
            isFocusable = true
            contentDescription = "Rate $value"
            if (ratingDisplay == Question.Rating.Display.NUMERIC) {
                minimumWidth = context.dpPx(48)
                setPadding(context.dpPx(12), 0, context.dpPx(12), 0)
            }
            addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = selectedValue == value
                }
            }
            setOnClickListener {
                selectedValue = value
                render()
                onValueChange?.invoke(currentAnswer())
            }
        }

        fun paint(pressed: Boolean) {
            val selected = selectedValue == value
            when (ratingDisplay) {
                Question.Rating.Display.STARS -> {
                    val filled = selectedValue?.let { value <= it } == true
                    label.setTextColor(if (filled) palette.primary else SurfaceColor.withAlpha(palette.text, 0.16f))
                    alpha = if (pressed) 0.7f else 1f
                }
                Question.Rating.Display.EMOJI -> {
                    background = if (selected) oval(palette.tint) else null
                    alpha = if (selected || selectedValue == null) 1f else 0.4f
                }
                Question.Rating.Display.NUMERIC -> {
                    background = roundRect(
                        when {
                            selected -> palette.primary
                            pressed -> palette.fill
                            else -> 0
                        },
                        context.dpF(palette.radius(12)),
                        context.dpPx(1.5f),
                        if (selected) palette.primary else palette.border,
                    )
                    label.setTextColor(if (selected) palette.onPrimary else palette.text)
                }
            }
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            paint(pressed)
        }
    }
}
