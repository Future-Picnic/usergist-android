package studio.usergist.feedback.internal.ui.questions

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.view.accessibility.AccessibilityNodeInfo
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.ui.surface.PressFrame
import studio.usergist.feedback.internal.ui.surface.SurfaceField
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.questionHeader
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceText

/**
 * NPS scale (port of RN questions/NpsQuestion.tsx).
 * - Wide layouts (landscape, tablets): the 0–10 row, each numeral centred
 *   in its own cell (including 10), endpoint labels underneath.
 * - Narrow portrait: a vertical list with 10 at the top, so no number is
 *   squeezed below a usable touch target.
 */
internal class NpsQuestionView(
    context: Context,
    private val question: Question.Nps,
    theme: ResolvedTheme,
    includeHeader: Boolean = true,
    initialScore: Int? = null,
) : QuestionView {

    override var onValueChange: ((PromptAnswerValue) -> Unit)? = null
    var onFollowUpChange: ((String) -> Unit)? = null

    private val palette = SurfacePalette.from(theme)
    private val lowLabel = question.lowLabel ?: "Not at all likely"
    private val highLabel = question.highLabel ?: "Extremely likely"
    private val cells = mutableListOf<ScoreCell>()
    private var selectedScore: Int? = initialScore?.takeIf { it in 0..10 }
    private val wideThresholdPx = context.dpPx(WIDE_MIN_CONTENT_DP)
    private var wide: Boolean? = null

    // Both layouts are built once; the one that fits the *measured content
    // width* (not the window) is shown, like RN's onLayout width >= 524.
    private val root = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                applyWide(MeasureSpec.getSize(widthMeasureSpec) >= wideThresholdPx)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply { orientation = LinearLayout.VERTICAL }
    override val view: View = root
    private var header: LinearLayout? = null
    private lateinit var row: View
    private lateinit var column: View
    private lateinit var rowLabels: View

    private val followUp: LinearLayout? = question.followUp?.takeIf { it.isNotEmpty() }?.let { prompt ->
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (selectedScore != null) View.VISIBLE else View.GONE
            addView(context.surfaceText(palette, prompt, 16f, palette.text, 600), matchWidth().apply {
                bottomMargin = context.dpPx(10)
            })
            addView(SurfaceField(context, palette, "Tell us more", prompt, multiline = true, minHeightDp = 80).apply {
                onTextChanged = { text -> onFollowUpChange?.invoke(text) }
            }, matchWidth())
        }
    }

    init {
        if (includeHeader) {
            header = context.questionHeader(palette, question.title, question.subtitle, question.imageUrl)
            root.addView(header, matchWidth())
        }
        row = buildRow(context)
        column = buildColumn(context)
        rowLabels = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(context.surfaceText(palette, lowLabel, 13f, palette.subtext), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(context.surfaceText(palette, highLabel, 13f, palette.subtext, gravity = Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = context.dpPx(16)
            })
        }
        root.addView(row, matchWidth())
        root.addView(column, matchWidth())
        root.addView(rowLabels, matchWidth().apply { topMargin = context.dpPx(10) })
        followUp?.let { root.addView(it, matchWidth().apply { topMargin = context.dpPx(24) }) }
        applyWide(false)
        render()
    }

    /** Shows the 0–10 row (wide) or the vertical list; centres the title in the row layout. */
    private fun applyWide(value: Boolean) {
        if (wide == value) return
        wide = value
        row.visibility = if (value) View.VISIBLE else View.GONE
        rowLabels.visibility = row.visibility
        column.visibility = if (value) View.GONE else View.VISIBLE
        val align = if (value) Gravity.CENTER_HORIZONTAL else Gravity.START
        header?.let { h -> (0 until h.childCount).map(h::getChildAt).filterIsInstance<android.widget.TextView>().forEach { it.gravity = align } }
    }

    override fun currentAnswer(): PromptAnswerValue {
        val score = selectedScore ?: return PromptAnswerValue.None
        return PromptAnswerValue.Number(score.toDouble())
    }

    override fun isSubmittable(): Boolean = selectedScore != null

    private fun buildRow(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        (0..10).forEach { n ->
            addView(cell(context, n, null, inRow = true), LinearLayout.LayoutParams(0, context.dpPx(48), 1f).apply {
                if (n > 0) marginStart = context.dpPx(6)
            })
        }
    }

    private fun buildColumn(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        (10 downTo 0).forEach { n ->
            val endpoint = when (n) { 10 -> highLabel; 0 -> lowLabel; else -> null }
            addView(cell(context, n, endpoint, inRow = false), matchWidth().apply { if (n < 10) topMargin = context.dpPx(6) })
        }
    }

    private fun cell(context: Context, n: Int, endpoint: String?, inRow: Boolean): ScoreCell {
        val number = context.surfaceText(palette, n.toString(), 16f, palette.text, 700,
            gravity = if (inRow) Gravity.CENTER else Gravity.START)
        val label = endpoint?.let { context.surfaceText(palette, it, 14f, palette.subtext) }
        val cell = ScoreCell(context, n, number, label)
        if (inRow) {
            cell.addView(number, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        } else {
            cell.minimumHeight = context.dpPx(48)
            cell.setPadding(context.dpPx(16), context.dpPx(8), context.dpPx(16), context.dpPx(8))
            cell.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                number.minWidth = context.dpPx(22)
                addView(number)
                label?.let { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dpPx(10) }) }
            }, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        }
        cell.contentDescription = "Score $n" + when (n) { 10 -> ", $highLabel"; 0 -> ", $lowLabel"; else -> "" }
        cell.setOnClickListener {
            selectedScore = n
            render()
            followUp?.visibility = View.VISIBLE
            onValueChange?.invoke(currentAnswer())
        }
        cells += cell
        return cell
    }

    private fun render() = cells.forEach { it.paint(selectedScore == it.score) }

    private companion object {
        /** Content width at which 11 cells stay ≥ 42dp wide with 6dp gaps (RN: 524). */
        const val WIDE_MIN_CONTENT_DP = 524
    }

    private inner class ScoreCell(
        context: Context,
        val score: Int,
        private val number: android.widget.TextView,
        private val label: android.widget.TextView?,
    ) : PressFrame(context, 1f) {
        private var selected = false

        init {
            isClickable = true
            isFocusable = true
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = selected
                }
            }
        }

        fun paint(isSelected: Boolean, pressed: Boolean = false) {
            selected = isSelected
            background = roundRect(
                when {
                    isSelected -> palette.primary
                    pressed -> palette.fill
                    else -> 0
                },
                context.dpF(palette.radius(12)),
                context.dpPx(1.5f),
                if (isSelected) palette.primary else palette.border,
            )
            number.setTextColor(if (isSelected) palette.onPrimary else palette.text)
            label?.setTextColor(if (isSelected) palette.onPrimary else palette.subtext)
        }

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            paint(selected, pressed)
        }
    }
}
