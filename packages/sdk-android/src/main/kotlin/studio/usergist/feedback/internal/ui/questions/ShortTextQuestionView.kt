package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.ui.surface.SurfaceField
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.questionHeader

/** Free-text question (port of RN questions/ShortTextQuestion.tsx). */
internal class ShortTextQuestionView(
    context: Context,
    private val question: Question.ShortText,
    theme: ResolvedTheme,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null

    private val palette = SurfacePalette.from(theme)
    private var current: String = ""
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = root

    /** The text field, so the host can focus it or keep it above the keyboard. */
    val field = SurfaceField(
        context,
        palette,
        hint = question.placeholder,
        accessibilityLabel = "Feedback text answer",
        multiline = true,
        minHeightDp = 96,
        maxLength = question.maxLength?.takeIf { it > 0 },
    )

    init {
        root.addView(context.questionHeader(palette, question.title, question.subtitle, question.imageUrl), matchWidth())
        field.onTextChanged = { text ->
            current = text
            onValueChange?.invoke(currentAnswer())
        }
        root.addView(field, matchWidth())
    }

    override fun currentAnswer(): PromptAnswerValue {
        val trimmed = current.trim()
        return if (trimmed.isEmpty()) PromptAnswerValue.None else PromptAnswerValue.Text(trimmed)
    }

    override fun isSubmittable(): Boolean = current.trim().isNotEmpty()
}
