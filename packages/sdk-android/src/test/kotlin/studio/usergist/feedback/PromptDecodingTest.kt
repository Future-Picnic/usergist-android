package studio.usergist.feedback

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.usergist.feedback.internal.model.ClientPrompt
import studio.usergist.feedback.internal.model.Question

class PromptDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun ratingDisplayDefaultsToStars() {
        val prompt = json.decodeFromString<ClientPrompt>(
            """{"id":"p","questions":[{"type":"rating","id":"q","title":"Rate","scale":5}]}""",
        )
        val rating = prompt.questions.single() as Question.Rating
        assertEquals(Question.Rating.Display.STARS, rating.display)
    }

    @Test
    fun ratingDisplayDecodesExplicitMode() {
        val prompt = json.decodeFromString<ClientPrompt>(
            """{"id":"p","questions":[{"type":"rating","id":"q","title":"Rate","scale":5,"display":"emoji"}]}""",
        )
        val rating = prompt.questions.single() as Question.Rating
        assertEquals(Question.Rating.Display.EMOJI, rating.display)
    }

    @Test
    fun promptVisualFieldsDecodeForEveryQuestionFamily() {
        val prompt = json.decodeFromString<ClientPrompt>(
            """{"id":"p","questions":[{"type":"rating","id":"r","title":"Rate","scale":5,"imageUrl":"https://example.com/r.png"},{"type":"nps","id":"n","title":"NPS","lowLabel":"Never","highLabel":"Absolutely"},{"type":"multiple_choice","id":"m","title":"Pick","imageUrl":"https://example.com/m.png","options":[{"id":"a","label":"A"}]},{"type":"short_text","id":"t","title":"Tell","imageUrl":"https://example.com/t.png"}]}""",
        )

        assertEquals("https://example.com/r.png", (prompt.questions[0] as Question.Rating).imageUrl)
        assertEquals("Never", (prompt.questions[1] as Question.Nps).lowLabel)
        assertEquals("Absolutely", (prompt.questions[1] as Question.Nps).highLabel)
        assertEquals("https://example.com/m.png", (prompt.questions[2] as Question.MultipleChoice).imageUrl)
        assertEquals("https://example.com/t.png", (prompt.questions[3] as Question.ShortText).imageUrl)
    }
}
