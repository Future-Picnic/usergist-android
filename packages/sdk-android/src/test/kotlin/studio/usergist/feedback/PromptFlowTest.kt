package studio.usergist.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.internal.model.ClientPrompt
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.PromptFlow

class PromptFlowTest {
    private val rating = Question.Rating(id = "rating", title = "Rate", scale = 5)
    private val nps = Question.Nps(id = "nps", title = "Recommend?")
    private val npsWithFollowUp = Question.Nps(
        id = "nps-follow-up",
        title = "Recommend?",
        followUp = "What led to your score?",
    )
    private val single = Question.MultipleChoice(
        id = "single",
        title = "Pick one",
        options = listOf(Question.MultipleChoice.Choice("a", "A")),
    )
    private val multi = Question.MultipleChoice(
        id = "multi",
        title = "Pick several",
        options = listOf(Question.MultipleChoice.Choice("a", "A")),
        multiSelect = true,
    )
    private val text = Question.ShortText(id = "text", title = "Tell us")

    @Test
    fun navigationPolicyMatchesReference() {
        assertTrue(PromptFlow.shouldAutoAdvance(rating))
        assertTrue(PromptFlow.shouldAutoAdvance(nps))
        assertTrue(PromptFlow.shouldAutoAdvance(single))
        assertFalse(PromptFlow.shouldAutoAdvance(multi))
        assertFalse(PromptFlow.shouldAutoAdvance(text))
        assertFalse(PromptFlow.shouldAutoAdvance(npsWithFollowUp))
        assertTrue(PromptFlow.needsExplicitNext(npsWithFollowUp))
        assertTrue(PromptFlow.needsExplicitNext(multi))
        assertTrue(PromptFlow.needsExplicitNext(text))
        assertFalse(PromptFlow.needsExplicitNext(rating))
    }

    @Test
    fun responseOrderPreservesPartialAnswersAndNpsFollowUp() {
        val prompt = ClientPrompt(
            id = "prompt",
            questions = listOf(rating, nps, single, multi, text),
        )
        val answers = linkedMapOf<String, PromptAnswerValue>(
            "nps__followUp" to PromptAnswerValue.Text("Because"),
            "single" to PromptAnswerValue.Choices(listOf("a")),
            "rating" to PromptAnswerValue.Number(4.0),
            "nps" to PromptAnswerValue.Number(7.0),
        )

        val ordered = PromptFlow.orderedAnswers(prompt, answers)
        assertEquals(listOf("rating", "nps", "nps__followUp", "single"), ordered.map { it.questionId })
    }
}
