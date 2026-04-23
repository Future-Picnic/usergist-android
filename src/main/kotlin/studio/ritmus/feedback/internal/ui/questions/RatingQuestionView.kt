package studio.ritmus.feedback.internal.ui.questions

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import studio.ritmus.feedback.R
import studio.ritmus.feedback.api.PromptAnswerValue
import studio.ritmus.feedback.internal.model.Question

/** Renders a 1-5 / 1-10 rating question. */
internal class RatingQuestionView(
    context: Context,
    private val question: Question.Rating,
) : QuestionView {

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.ritmus_question_rating, FrameLayout(context), false)

    private val group: MaterialButtonToggleGroup = view.findViewById(R.id.ritmus_rating_group)
    private val lowLabel: TextView = view.findViewById(R.id.ritmus_rating_low)
    private val highLabel: TextView = view.findViewById(R.id.ritmus_rating_high)

    private var selectedValue: Int? = null

    init {
        val scale = if (question.scale == 10) 10 else 5
        for (i in 1..scale) {
            val button = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = i.toString()
                id = View.generateViewId()
                minWidth = dp(context, 44)
                minimumWidth = dp(context, 44)
            }
            group.addView(button)
            button.tag = i
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val button = group.findViewById<MaterialButton>(checkedId)
                selectedValue = (button?.tag as? Int)
            } else if (group.checkedButtonId == View.NO_ID) {
                selectedValue = null
            }
        }
        lowLabel.text = question.lowLabel.orEmpty()
        highLabel.text = question.highLabel.orEmpty()
    }

    override fun currentAnswer(): PromptAnswerValue {
        val v = selectedValue ?: return PromptAnswerValue.None
        return PromptAnswerValue.Number(v.toDouble())
    }

    override fun isSubmittable(): Boolean = selectedValue != null

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
