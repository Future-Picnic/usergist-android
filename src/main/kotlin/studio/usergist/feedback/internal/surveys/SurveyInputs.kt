package studio.usergist.feedback.internal.surveys

import android.app.DatePickerDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.ui.questions.NpsQuestionView
import studio.usergist.feedback.internal.ui.questions.RatingQuestionView
import studio.usergist.feedback.internal.ui.surface.ChoiceRow
import studio.usergist.feedback.internal.ui.surface.Glyph
import studio.usergist.feedback.internal.ui.surface.GlyphView
import studio.usergist.feedback.internal.ui.surface.PressFrame
import studio.usergist.feedback.internal.ui.surface.SurfaceColor
import studio.usergist.feedback.internal.ui.surface.SurfaceField
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.announceAsButton
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.oval
import studio.usergist.feedback.internal.ui.surface.questionHeader
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceText
import studio.usergist.feedback.internal.util.DateTime

/**
 * Survey question renderers (port of RN ui/survey-questions.tsx and the
 * shared question views). Answers are written into [answers]; [onChange]
 * persists progress and [onAutoAdvance] moves on for tap-to-select types.
 */
internal class SurveyInputs(
    private val context: Context,
    private val theme: ResolvedTheme,
    private val palette: SurfacePalette,
    private val answers: MutableMap<String, JsonElement>,
    private val onChange: () -> Unit,
    private val onAutoAdvance: (questionId: String, delayed: Boolean) -> Unit,
) {

    fun build(question: SdkSurveyQuestion): View = when (question.type) {
        "rating" -> rating(question)
        "nps" -> nps(question)
        else -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val subtitle = question.subtitle ?: if (question.type == "ranking") "Most important first" else null
            addView(context.questionHeader(palette, question.title, subtitle, question.imageUrl), matchWidth())
            addView(input(question), matchWidth())
        }
    }

    private fun input(question: SdkSurveyQuestion): View = when (question.type) {
        "single_choice" -> singleChoice(question, question.options.map { it.id to it.label }, numeric = false)
        "likert" -> singleChoice(
            question,
            question.labels.ifEmpty {
                listOf("Strongly disagree", "Disagree", "Neutral", "Agree", "Strongly agree")
            }.mapIndexed { index, label -> (index + 1).toString() to label },
            numeric = true,
        )
        "multi_choice" -> multiChoice(question)
        "short_text", "long_text" -> textInput(question)
        "ranking" -> RankingInput(question).view
        "single_date" -> dateInput(question)
        "info_screen" -> context.surfaceText(palette, question.body.orEmpty(), 16f, palette.subtext, 400, 24f)
        else -> context.surfaceText(palette, "This question needs a newer app version.", 16f, palette.subtext)
    }

    private fun rating(question: SdkSurveyQuestion): View {
        val initial = (answers[question.id] as? JsonPrimitive)?.content?.toIntOrNull()
        return RatingQuestionView(context, question.toPromptRatingQuestion(), theme, initialValue = initial).apply {
            onValueChange = { answer ->
                if (answer is PromptAnswerValue.Number) {
                    answers[question.id] = JsonPrimitive(answer.value.toInt())
                    onChange()
                    onAutoAdvance(question.id, true)
                }
            }
        }.view
    }

    private fun nps(question: SdkSurveyQuestion): View {
        val initial = (answers[question.id] as? JsonPrimitive)?.content?.toIntOrNull()
        val model = Question.Nps(
            id = question.id,
            title = question.title,
            subtitle = question.subtitle,
            imageUrl = question.imageUrl,
            lowLabel = question.lowLabel,
            highLabel = question.highLabel,
        )
        return NpsQuestionView(context, model, theme, initialScore = initial).apply {
            onValueChange = { answer ->
                if (answer is PromptAnswerValue.Number) {
                    answers[question.id] = JsonPrimitive(answer.value.toInt())
                    onChange()
                    onAutoAdvance(question.id, false)
                }
            }
        }.view
    }

    private fun singleChoice(question: SdkSurveyQuestion, choices: List<Pair<String, String>>, numeric: Boolean): View {
        val current = (answers[question.id] as? JsonPrimitive)?.content
        val rows = mutableListOf<Pair<String, ChoiceRow>>()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            choices.forEachIndexed { index, (id, label) ->
                val row = ChoiceRow(context, palette, label, checkbox = false).apply {
                    selectedState = current == id
                    setOnClickListener {
                        answers[question.id] = if (numeric) JsonPrimitive(id.toInt()) else JsonPrimitive(id)
                        rows.forEach { (rowId, r) -> r.selectedState = rowId == id }
                        onChange()
                        onAutoAdvance(question.id, false)
                    }
                }
                rows += id to row
                addView(row, matchWidth().apply { if (index > 0) topMargin = context.dpPx(8) })
            }
        }
    }

    private fun multiChoice(question: SdkSurveyQuestion): View {
        val selected = ((answers[question.id] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()).toMutableList()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            question.options.forEachIndexed { index, choice ->
                val row = ChoiceRow(context, palette, choice.label, checkbox = true)
                row.selectedState = selected.contains(choice.id)
                row.setOnClickListener {
                    if (selected.contains(choice.id)) {
                        selected -= choice.id
                    } else {
                        val max = question.maxSelections
                        if (max != null && selected.size >= max) return@setOnClickListener
                        selected += choice.id
                    }
                    row.selectedState = selected.contains(choice.id)
                    answers[question.id] = JsonArray(selected.map(::JsonPrimitive))
                    onChange()
                }
                addView(row, matchWidth().apply { if (index > 0) topMargin = context.dpPx(8) })
            }
        }
    }

    private fun textInput(question: SdkSurveyQuestion): View {
        val saved = (answers[question.id] as? JsonPrimitive)?.content.orEmpty()
        return SurfaceField(
            context,
            palette,
            hint = question.placeholder,
            accessibilityLabel = question.title,
            multiline = true,
            minHeightDp = if (question.type == "long_text") 132 else 96,
            maxLength = question.maxLength?.takeIf { it > 0 },
            initial = saved,
        ).apply {
            onTextChanged = { value ->
                answers[question.id] = JsonPrimitive(value)
                onChange()
            }
        }
    }

    private fun dateInput(question: SdkSurveyQuestion): View {
        fun current() = (answers[question.id] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val label = context.surfaceText(palette, current() ?: "Choose a date", 16f,
            if (current() != null) palette.text else palette.placeholder, gravity = Gravity.CENTER_VERTICAL)
        return PressFrame(context, 0.7f).apply {
            isClickable = true
            isFocusable = true
            announceAsButton()
            contentDescription = "${question.title}: ${current() ?: "Choose a date"}"
            minimumHeight = context.dpPx(50)
            setPadding(context.dpPx(14), context.dpPx(12), context.dpPx(14), context.dpPx(12))
            background = roundRect(palette.fill, context.dpF(palette.radius(14)))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            setOnClickListener {
                val now = DateTime.parseLocalDate(current()) ?: DateTime.todayLocalDate()
                val dialog = DatePickerDialog(
                    context,
                    { _, year, month, day ->
                        val picked = DateTime.localDate(year, month, day)?.iso ?: return@DatePickerDialog
                        answers[question.id] = JsonPrimitive(picked)
                        label.text = picked
                        label.setTextColor(palette.text)
                        contentDescription = "${question.title}: $picked"
                        onChange()
                    },
                    now.year,
                    now.month - 1,
                    now.day,
                )
                question.minDate?.let { raw -> DateTime.parseLocalDate(raw)?.let { dialog.datePicker.minDate = it.utcStartMillis() } }
                question.maxDate?.let { raw -> DateTime.parseLocalDate(raw)?.let { dialog.datePicker.maxDate = it.utcStartMillis() } }
                dialog.show()
            }
        }
    }

    /** Ranking with move buttons (accessible without drag gestures). */
    private inner class RankingInput(private val question: SdkSurveyQuestion) {
        val view = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private var ranking: MutableList<SdkSurveyChoice>

        init {
            val saved = (answers[question.id] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val byId = question.items.associateBy { it.id }
            ranking = if (saved.isEmpty()) question.items.toMutableList() else saved.mapNotNull(byId::get).toMutableList()
            render()
        }

        private fun render() {
            view.removeAllViews()
            ranking.forEachIndexed { index, item ->
                view.addView(row(index, item), matchWidth().apply { if (index > 0) topMargin = context.dpPx(8) })
            }
            answers[question.id] = JsonArray(ranking.map { JsonPrimitive(it.id) })
            onChange()
        }

        private fun row(index: Int, item: SdkSurveyChoice) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dpPx(56)
            setPadding(context.dpPx(12), 0, context.dpPx(4), 0)
            background = roundRect(0, context.dpF(palette.radius(16)), context.dpPx(1.5f), palette.border)
            addView(FrameLayout(context).apply {
                background = oval(palette.tint)
                addView(context.surfaceText(palette, (index + 1).toString(), 14f, palette.primary, 700, gravity = Gravity.CENTER),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }, LinearLayout.LayoutParams(context.dpPx(28), context.dpPx(28)))
            addView(context.surfaceText(palette, item.label, 16f, palette.text, 500, 21f).apply {
                setPadding(0, context.dpPx(12), 0, context.dpPx(12))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpPx(12) })
            addView(move(Glyph.UP, index > 0, "Move ${item.label} up") { swap(index, index - 1) })
            addView(move(Glyph.DOWN, index < ranking.lastIndex, "Move ${item.label} down") { swap(index, index + 1) })
        }

        private fun move(glyph: Glyph, enabled: Boolean, label: String, action: () -> Unit): View =
            PressFrame(context, 0.5f).apply {
                isEnabled = enabled
                isClickable = enabled
                isFocusable = true
                announceAsButton()
                contentDescription = label
                val color = if (enabled) palette.text else SurfaceColor.withAlpha(palette.text, 0.2f)
                addView(GlyphView(context, glyph, color), FrameLayout.LayoutParams(context.dpPx(24), context.dpPx(24), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(context.dpPx(48), context.dpPx(48))
                setOnClickListener { if (enabled) action() }
            }

        private fun swap(from: Int, to: Int) {
            if (to !in ranking.indices) return
            ranking.add(to, ranking.removeAt(from))
            render()
        }
    }
}

/** Notice block for validation and submit errors, with an optional action. */
internal fun Context.surveyNotice(palette: SurfacePalette, message: String, action: View? = null): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(16))
        background = roundRect(palette.fill, dpF(palette.radius(14)))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(surfaceText(palette, message, 15f, palette.text, 400, 21f), matchWidth())
        action?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpPx(12) }) }
    }
