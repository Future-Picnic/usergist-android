package studio.usergist.feedback.internal.ui.questions

import android.view.View
import studio.usergist.feedback.api.PromptAnswerValue

/**
 * Contract shared by every question renderer. A [QuestionView] exposes
 * the rendered [view] and a synchronous [currentAnswer] getter.
 */
internal interface QuestionView {
    val view: View

    /** Called whenever the answer changes. */
    var onValueChange: ((PromptAnswerValue) -> Unit)?

    /** The currently entered answer, or [PromptAnswerValue.None] if none. */
    fun currentAnswer(): PromptAnswerValue

    /** Whether submission should be allowed right now. */
    fun isSubmittable(): Boolean
}
