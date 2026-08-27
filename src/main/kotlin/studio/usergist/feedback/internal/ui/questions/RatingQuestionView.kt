package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import studio.usergist.feedback.R
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme

/** Renders the dashboard-selected rating style. Stars are the wire default. */
internal class RatingQuestionView(
    context: Context,
    private val question: Question.Rating,
    private val theme: ResolvedTheme,
    initialValue: Int? = null,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null

    private companion object {
        val EMOJI = listOf("😡", "😕", "😐", "🙂", "😍")
    }

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.usergist_question_rating, FrameLayout(context), false)

    private val group: LinearLayout = view.findViewById(R.id.usergist_rating_group)
    private val lowLabel: TextView = view.findViewById(R.id.usergist_rating_low)
    private val highLabel: TextView = view.findViewById(R.id.usergist_rating_high)
    private val buttons = mutableListOf<MaterialButton>()

    private var selectedValue: Int? = initialValue

    init {
        val scale = if (question.scale == 10) 10 else 5
        selectedValue = selectedValue?.takeIf { it in 1..scale }
        val display = if (question.display == Question.Rating.Display.EMOJI && scale != 5) {
            Question.Rating.Display.STARS
        } else {
            question.display
        }
        var row: LinearLayout? = null
        for (i in 1..scale) {
            val indexInRow = (i - 1) % 5
            val targetRow = if (indexInRow == 0) {
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                }.also { newRow ->
                    row = newRow
                    group.addView(
                        newRow,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (i > 1) topMargin = dp(context, 8)
                        },
                    )
                }
            } else {
                requireNotNull(row)
            }
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                tag = i
                contentDescription = "Rate $i"
                isCheckable = true
                when (display) {
                    Question.Rating.Display.STARS -> {
                        text = "★"
                        // MaterialButton's glyph renders optically smaller than the
                        // 28sp React Native Text reference inside the same 36dp target.
                        textSize = 36f
                        includeFontPadding = false
                        minWidth = dp(context, 36)
                        minimumWidth = dp(context, 36)
                        minHeight = dp(context, 36)
                        minimumHeight = dp(context, 36)
                        setPadding(0, 0, 0, 0)
                        insetTop = 0
                        insetBottom = 0
                        strokeWidth = 0
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    }
                    Question.Rating.Display.EMOJI -> {
                        text = EMOJI[i - 1]
                        textSize = 30f
                        includeFontPadding = false
                        minWidth = dp(context, 44)
                        minimumWidth = dp(context, 44)
                        minHeight = dp(context, 44)
                        minimumHeight = dp(context, 44)
                        setPadding(0, 0, 0, 0)
                        insetTop = 0
                        insetBottom = 0
                        strokeWidth = 0
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    }
                    Question.Rating.Display.NUMERIC -> {
                        text = i.toString()
                        minWidth = dp(context, 40)
                        minimumWidth = dp(context, 40)
                        minHeight = dp(context, 40)
                        minimumHeight = dp(context, 40)
                        cornerRadius = dp(context, 20)
                        insetTop = 0
                        insetBottom = 0
                        strokeWidth = dp(context, 1)
                        strokeColor = ColorStateList.valueOf(theme.border ?: Color.LTGRAY)
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    }
                }
            }
            val itemSize = when (display) {
                Question.Rating.Display.STARS -> dp(context, 36)
                Question.Rating.Display.EMOJI -> dp(context, 44)
                Question.Rating.Display.NUMERIC -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val itemHeight = when (display) {
                Question.Rating.Display.STARS -> dp(context, 36)
                Question.Rating.Display.EMOJI -> dp(context, 44)
                Question.Rating.Display.NUMERIC -> dp(context, 40)
            }
            targetRow.addView(
                button,
                LinearLayout.LayoutParams(itemSize, itemHeight).apply {
                    if (indexInRow > 0) marginStart = dp(context, 8)
                },
            )
            button.setOnClickListener {
                selectedValue = i
                renderSelection(display)
                onValueChange?.invoke(currentAnswer())
            }
            buttons += button
        }
        renderSelection(display)
        lowLabel.text = question.lowLabel.orEmpty()
        highLabel.text = question.highLabel.orEmpty()
        theme.subtext?.let {
            lowLabel.setTextColor(it)
            highLabel.setTextColor(it)
        }
    }

    override fun currentAnswer(): PromptAnswerValue {
        val v = selectedValue ?: return PromptAnswerValue.None
        return PromptAnswerValue.Number(v.toDouble())
    }

    override fun isSubmittable(): Boolean = selectedValue != null

    private fun renderSelection(display: Question.Rating.Display) {
        val primary = theme.primary ?: Color.rgb(98, 0, 238)
        val border = theme.border ?: Color.LTGRAY
        val text = theme.text ?: Color.DKGRAY
        buttons.forEach { button ->
            val value = button.tag as Int
            when (display) {
                Question.Rating.Display.STARS -> {
                    button.setTextColor(if (selectedValue != null && value <= selectedValue!!) primary else border)
                }
                Question.Rating.Display.EMOJI -> {
                    button.alpha = if (selectedValue == null || selectedValue == value) 1f else 0.35f
                }
                Question.Rating.Display.NUMERIC -> {
                    val selected = selectedValue == value
                    button.setTextColor(if (selected) theme.background ?: Color.WHITE else text)
                    button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
                }
            }
            button.isSelected = selectedValue == value
            button.isChecked = selectedValue == value
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
