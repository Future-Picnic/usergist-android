package studio.usergist.feedback

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.surveys.SdkSurveyQuestion
import studio.usergist.feedback.internal.surveys.toPromptRatingQuestion

class SurveyRatingParityTest {
    @Test
    fun `survey rating uses the prompt star control contract`() {
        val rating = SdkSurveyQuestion(
            id = "survey-rating",
            type = "rating",
            title = "How reliable did it feel?",
            required = true,
            scale = 5,
            lowLabel = "Not reliable",
            highLabel = "Very reliable",
        ).toPromptRatingQuestion()

        assertEquals(Question.Rating.Display.STARS, rating.display)
        assertEquals(5, rating.scale)
        assertEquals("Not reliable", rating.lowLabel)
        assertEquals("Very reliable", rating.highLabel)
    }

    @Test
    fun `survey rating normalizes unsupported scales to five stars`() {
        val rating = SdkSurveyQuestion(
            id = "survey-rating",
            type = "rating",
            title = "Rate it",
            scale = 7,
        ).toPromptRatingQuestion()

        assertEquals(5, rating.scale)
    }
}
