package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.ui.surface.ChoiceRow
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.questionHeader

/**
 * Stacked choice rows (port of RN questions/MultipleChoiceQuestion.tsx):
 * tint + primary border + radio/checkbox indicator for the selection.
 */
internal class MultipleChoiceQuestionView(
    context: Context,
    private val question: Question.MultipleChoice,
    theme: ResolvedTheme,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null

    private val palette = SurfacePalette.from(theme)
    private val rows = mutableListOf<Pair<String, ChoiceRow>>()
    private val selectedIds = mutableListOf<String>()
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = root

    init {
        val subtitle = question.subtitle ?: if (question.multiSelect) "Choose all that apply" else null
        root.addView(context.questionHeader(palette, question.title, subtitle, question.imageUrl), matchWidth())
        question.options.forEachIndexed { index, option ->
            val row = ChoiceRow(context, palette, option.label, checkbox = question.multiSelect).apply {
                setOnClickListener { toggle(option.id) }
            }
            rows += option.id to row
            root.addView(row, matchWidth().apply { if (index > 0) topMargin = context.dpPx(8) })
        }
    }

    override fun currentAnswer(): PromptAnswerValue {
        if (selectedIds.isEmpty()) return PromptAnswerValue.None
        return PromptAnswerValue.Choices(selectedIds.toList())
    }

    override fun isSubmittable(): Boolean = selectedIds.isNotEmpty()

    private fun toggle(id: String) {
        if (question.multiSelect) {
            if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        } else {
            selectedIds.clear()
            selectedIds.add(id)
        }
        rows.forEach { (optionId, row) -> row.selectedState = selectedIds.contains(optionId) }
        onValueChange?.invoke(currentAnswer())
    }
}
