package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import studio.usergist.feedback.R
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme

/** Renders the dashboard-selected rating style. Stars are the wire default. */
internal class RatingQuestionView(
    context: Context,
    private val question: Question.Rating,
    private val theme: ResolvedTheme,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null

    private companion object {
        val EMOJI = listOf("😡", "😕", "😐", "🙂", "😍")
    }

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.usergist_question_rating, FrameLayout(context), false)

    private val group: MaterialButtonToggleGroup = view.findViewById(R.id.usergist_rating_group)
    private val lowLabel: TextView = view.findViewById(R.id.usergist_rating_low)
    private val highLabel: TextView = view.findViewById(R.id.usergist_rating_high)
    private val buttons = mutableListOf<MaterialButton>()

    private var selectedValue: Int? = null

    init {
        val scale = if (question.scale == 10) 10 else 5
        val display = if (question.display == Question.Rating.Display.EMOJI && scale != 5) {
            Question.Rating.Display.STARS
        } else {
            question.display
        }
        for (i in 1..scale) {
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                tag = i
                contentDescription = "Rate $i"
                when (display) {
                    Question.Rating.Display.STARS -> {
                        text = "★"
                        textSize = 28f
                        minWidth = dp(context, 36)
                        minimumWidth = dp(context, 36)
                        setPadding(0, 0, 0, 0)
                        insetTop = 0
                        insetBottom = 0
                        strokeWidth = 0
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    }
                    Question.Rating.Display.EMOJI -> {
                        text = EMOJI[i - 1]
                        textSize = 28f
                        minWidth = dp(context, 44)
                        minimumWidth = dp(context, 44)
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
            group.addView(button)
            buttons += button
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val button = group.findViewById<MaterialButton>(checkedId)
                selectedValue = (button?.tag as? Int)
            } else if (group.checkedButtonId == View.NO_ID) {
                selectedValue = null
            }
            renderSelection(display)
            if (isChecked && selectedValue != null) {
                onValueChange?.invoke(currentAnswer())
            }
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
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
