package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import studio.usergist.feedback.R
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme

/**
 * Renders the React Native full-width stacked option rows.
 */
internal class MultipleChoiceQuestionView(
    context: Context,
    private val question: Question.MultipleChoice,
    private val theme: ResolvedTheme,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.usergist_question_multiple_choice, FrameLayout(context), false)

    private val stack: LinearLayout = view as LinearLayout
    private val buttons = mutableListOf<MaterialButton>()
    private val selectedIds = mutableListOf<String>()

    init {
        question.options.forEachIndexed { index, option ->
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = option.label
                isCheckable = true
                tag = option.id
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                typeface = Typeface.create(theme.fontFamily, Typeface.BOLD)
                minimumHeight = dp(context, 48)
                minHeight = dp(context, 48)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(context, 14)
                strokeWidth = dp(context, 1)
                setPadding(dp(context, 14), 0, dp(context, 14), 0)
                contentDescription = option.label
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index < question.options.lastIndex) bottomMargin = dp(context, 8)
                }
                setOnClickListener { toggle(option.id) }
            }
            buttons += button
            stack.addView(button)
        }
        refreshStyles()
    }

    override fun currentAnswer(): PromptAnswerValue {
        if (selectedIds.isEmpty()) return PromptAnswerValue.None
        return PromptAnswerValue.Choices(selectedIds.toList())
    }

    override fun isSubmittable(): Boolean {
        return selectedIds.isNotEmpty()
    }

    private fun toggle(id: String) {
        if (question.multiSelect) {
            if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        } else {
            selectedIds.clear()
            selectedIds.add(id)
        }
        refreshStyles()
        onValueChange?.invoke(currentAnswer())
    }

    private fun refreshStyles() {
        val primary = theme.primary ?: Color.rgb(17, 17, 17)
        val background = theme.background ?: Color.WHITE
        val text = theme.text ?: Color.rgb(11, 11, 11)
        val border = theme.border ?: Color.rgb(229, 229, 229)
        buttons.forEach { button ->
            val selected = selectedIds.contains(button.tag as String)
            button.isChecked = selected
            button.isSelected = selected
            button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
            button.strokeColor = ColorStateList.valueOf(if (selected) primary else border)
            button.setTextColor(if (selected) background else text)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
