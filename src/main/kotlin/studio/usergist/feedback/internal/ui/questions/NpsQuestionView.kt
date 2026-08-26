package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import studio.usergist.feedback.R
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme

/**
 * Renders the React Native vertical 10-to-0 NPS list.
 */
internal class NpsQuestionView(
    context: Context,
    private val question: Question.Nps,
    private val theme: ResolvedTheme,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null
    var onFollowUpChange: ((String) -> Unit)? = null

    override val view: View = LayoutInflater.from(context)
        .inflate(R.layout.usergist_question_nps, FrameLayout(context), false)

    private val group: LinearLayout = view.findViewById(R.id.usergist_nps_group)
    private val followupLayout: TextInputLayout = view.findViewById(R.id.usergist_nps_followup_layout)
    private val followupInput: TextInputEditText = view.findViewById(R.id.usergist_nps_followup)

    private var selectedScore: Int? = null
    private var followup: String = ""
    private val buttons = mutableListOf<MaterialButton>()

    init {
        for (i in 10 downTo 0) {
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                id = View.generateViewId()
                tag = i
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                minimumHeight = dp(context, 44)
                minHeight = dp(context, 44)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(context, 12)
                strokeWidth = dp(context, 1)
                setPadding(dp(context, 16), 0, dp(context, 16), 0)
                contentDescription = "Score $i"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (i != 0) bottomMargin = dp(context, 8) }
                setOnClickListener {
                    selectedScore = i
                    renderSelection()
                    updateFollowupVisibility()
                    onValueChange?.invoke(currentAnswer())
                }
            }
            group.addView(button)
            buttons += button
        }
        renderSelection()

        followupLayout.hint = question.followUp
            ?: view.context.getString(R.string.usergist_nps_followup_hint)
        followupInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                followup = s?.toString().orEmpty()
                onFollowUpChange?.invoke(followup)
            }
        })
    }

    private fun updateFollowupVisibility() {
        followupLayout.visibility = if (selectedScore != null && !question.followUp.isNullOrEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    override fun currentAnswer(): PromptAnswerValue {
        val score = selectedScore ?: return PromptAnswerValue.None
        return PromptAnswerValue.Number(score.toDouble())
    }

    override fun isSubmittable(): Boolean = selectedScore != null

    private fun renderSelection() {
        val primary = theme.primary ?: Color.rgb(17, 17, 17)
        val background = theme.background ?: Color.WHITE
        val text = theme.text ?: Color.rgb(11, 11, 11)
        val subtext = theme.subtext ?: Color.rgb(107, 107, 107)
        val border = theme.border ?: Color.rgb(229, 229, 229)
        buttons.forEach { button ->
            val value = button.tag as Int
            val selected = selectedScore == value
            button.isSelected = selected
            button.backgroundTintList = ColorStateList.valueOf(if (selected) primary else Color.TRANSPARENT)
            button.strokeColor = ColorStateList.valueOf(if (selected) primary else border)
            button.text = scoreTitle(
                value = value,
                numberColor = if (selected) background else text,
                labelColor = if (selected) background else subtext,
            )
        }
    }

    private fun scoreTitle(value: Int, numberColor: Int, labelColor: Int): SpannableString {
        val endpoint = when (value) {
            10 -> question.highLabel ?: "Extremely likely"
            0 -> question.lowLabel ?: "Not at all likely"
            else -> null
        }
        val number = value.toString()
        val full = endpoint?.let { "$number    $it" } ?: number
        return SpannableString(full).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(numberColor), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (endpoint != null) {
                setSpan(
                    ForegroundColorSpan(labelColor),
                    number.length + 4,
                    full.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
