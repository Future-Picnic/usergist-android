package studio.usergist.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.surveys.SdkSurveyAttemptSession
import studio.usergist.feedback.internal.surveys.SurveyStore
import java.nio.file.Files

class SurveyStoreTest {
    @Test
    fun `local progress survives restart and wins over stale server data`() {
        val root = Files.createTempDirectory("usergist-surveys-").toFile()
        try {
            val storage = Storage.forTest(root)
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
            val first = SurveyStore(storage, json)
            first.upsert(
                "survey-1",
                SdkSurveyAttemptSession(
                    attemptId = "attempt-1",
                    startQuestionId = "q1",
                    progressSnapshot = mapOf("q1" to JsonPrimitive("server")),
                    currentQuestionId = "q1",
                ),
            )
            first.update(
                "attempt-1",
                "q2",
                mapOf("q1" to JsonPrimitive("local")),
            )

            val restored = SurveyStore(storage, json)
            val merged = restored.merge(
                "survey-1",
                SdkSurveyAttemptSession(
                    attemptId = "attempt-1",
                    startQuestionId = "q1",
                    progressSnapshot = mapOf("q1" to JsonPrimitive("stale")),
                    currentQuestionId = "q1",
                ),
            )
            assertEquals("q2", merged.currentQuestionId)
            assertEquals(JsonPrimitive("local"), merged.progressSnapshot["q1"])
            assertTrue(merged.resumed)
        } finally {
            root.deleteRecursively()
        }
    }
}
