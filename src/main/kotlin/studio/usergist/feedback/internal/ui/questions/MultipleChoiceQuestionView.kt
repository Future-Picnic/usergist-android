package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import studio.usergist.feedback.R
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question

/**
 * Renders a single- or multi-select multiple-choice question as a
 * chip group. Filters are added with stable `tag` values so we can
 * recover the choice IDs without string parsing.
 */
internal class MultipleChoiceQuestionView(
    context: Context,
    private val question: Question.MultipleChoice,
) : QuestionView {

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.usergist_question_multiple_choice, FrameLayout(context), false)

    private val chipGroup: ChipGroup = view as ChipGroup

    init {
        chipGroup.isSingleSelection = !question.multiSelect
        chipGroup.isSelectionRequired = false
        for (option in question.options) {
            val chip = Chip(context).apply {
                text = option.label
                isCheckable = true
                tag = option.id
            }
            chipGroup.addView(chip)
        }
    }

    override fun currentAnswer(): PromptAnswerValue {
        val ids = ArrayList<String>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) {
                (chip.tag as? String)?.let { ids.add(it) }
            }
        }
        if (ids.isEmpty()) return PromptAnswerValue.None
        return PromptAnswerValue.Choices(ids)
    }

    override fun isSubmittable(): Boolean {
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) return true
        }
        return false
    }
}
