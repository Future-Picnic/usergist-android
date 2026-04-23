package studio.ritmus.feedback.internal.ui.questions

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import studio.ritmus.feedback.R
import studio.ritmus.feedback.api.PromptAnswerValue
import studio.ritmus.feedback.internal.model.Question

/**
 * Renders the two-step NPS question: an 0-10 scale followed by a free
 * text follow-up (shown only once a value is selected, per v1 spec).
 */
internal class NpsQuestionView(
    context: Context,
    private val question: Question.Nps,
) : QuestionView {

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.ritmus_question_nps, FrameLayout(context), false)

    private val group: MaterialButtonToggleGroup = view.findViewById(R.id.ritmus_nps_group)
    private val followupLayout: TextInputLayout = view.findViewById(R.id.ritmus_nps_followup_layout)
    private val followupInput: TextInputEditText = view.findViewById(R.id.ritmus_nps_followup)

    private var selectedScore: Int? = null
    private var followup: String = ""

    init {
        for (i in 0..10) {
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = i.toString()
                id = View.generateViewId()
                tag = i
            }
            group.addView(button)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val button = group.findViewById<MaterialButton>(checkedId)
                selectedScore = (button?.tag as? Int)
                updateFollowupVisibility()
            } else if (group.checkedButtonId == View.NO_ID) {
                selectedScore = null
                updateFollowupVisibility()
            }
        }

        followupLayout.hint = question.followUp
            ?: view.context.getString(R.string.ritmus_nps_followup_hint)
        followupInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                followup = s?.toString().orEmpty()
            }
        })
    }

    private fun updateFollowupVisibility() {
        followupLayout.visibility = if (selectedScore != null) View.VISIBLE else View.GONE
    }

    override fun currentAnswer(): PromptAnswerValue {
        val score = selectedScore ?: return PromptAnswerValue.None
        // Prefer the richer shape: NPS answers are numeric; the follow-up
        // text is surfaced separately via the question's follow-up question
        // in v1.5+. For v1 we pack it into choices for observability.
        val text = followup.trim()
        return if (text.isEmpty()) {
            PromptAnswerValue.Number(score.toDouble())
        } else {
            PromptAnswerValue.Choices(listOf(score.toString(), text))
        }
    }

    override fun isSubmittable(): Boolean = selectedScore != null
}
