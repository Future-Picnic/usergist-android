package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import studio.usergist.feedback.R
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme

/** Renders a short free-text question. */
internal class ShortTextQuestionView(
    context: Context,
    private val question: Question.ShortText,
    private val theme: ResolvedTheme,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.usergist_question_short_text, FrameLayout(context), false)

    private val layout: TextInputLayout = view as TextInputLayout
    private val input: TextInputEditText = view.findViewById(R.id.usergist_short_text_input)

    private var current: String = ""

    init {
        layout.hint = question.placeholder
        theme.primary?.let { layout.boxStrokeColor = it }
        theme.text?.let { input.setTextColor(it) }
        theme.subtext?.let { input.setHintTextColor(it) }
        val max = question.maxLength
        if (max != null && max > 0) {
            input.filters = arrayOf(InputFilter.LengthFilter(max))
            layout.counterMaxLength = max
            layout.isCounterEnabled = true
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                current = s?.toString().orEmpty()
                onValueChange?.invoke(currentAnswer())
            }
        })
    }

    override fun currentAnswer(): PromptAnswerValue {
        val trimmed = current.trim()
        return if (trimmed.isEmpty()) PromptAnswerValue.None else PromptAnswerValue.Text(trimmed)
    }

    override fun isSubmittable(): Boolean = current.trim().isNotEmpty()
}
