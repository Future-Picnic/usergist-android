package studio.usergist.feedback.internal.ui

import studio.usergist.feedback.api.PromptAnswerInfo
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.ClientPrompt
import studio.usergist.feedback.internal.model.Question

/** Pure prompt-navigation policy shared by the Android renderer and tests. */
internal object PromptFlow {
    fun shouldAutoAdvance(question: Question): Boolean = when (question) {
        is Question.Rating -> true
        is Question.Nps -> question.followUp.isNullOrEmpty()
        is Question.MultipleChoice -> !question.multiSelect
        is Question.ShortText -> false
    }

    fun needsExplicitNext(question: Question): Boolean = when (question) {
        is Question.Nps -> !question.followUp.isNullOrEmpty()
        is Question.MultipleChoice -> question.multiSelect
        is Question.ShortText -> true
        else -> false
    }

    fun orderedAnswers(
        prompt: ClientPrompt,
        answers: Map<String, PromptAnswerValue>,
    ): List<PromptAnswerInfo> = buildList {
        prompt.questions.forEach { question ->
            answers[question.id]?.takeUnless { it == PromptAnswerValue.None }?.let {
                add(PromptAnswerInfo(questionId = question.id, value = it))
            }
            val followUpId = "${question.id}__followUp"
            answers[followUpId]?.takeUnless { it == PromptAnswerValue.None }?.let {
                add(PromptAnswerInfo(questionId = followUpId, value = it))
            }
        }
    }
}
