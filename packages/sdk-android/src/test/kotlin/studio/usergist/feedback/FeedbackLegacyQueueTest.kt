package studio.usergist.feedback

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackLegacyQueueTest {
    @Test
    fun preUpgradeQueuedResponseStillDecodesWithoutDeviceContext() {
        val response = Json { ignoreUnknownKeys = true }.decodeFromString(
            UserGist.SdkResponsePayload.serializer(),
            """{"idempotencyKey":"old-stable-id","promptId":"prompt","anonymousId":"old-owner","answers":[{"questionId":"rating","value":5}],"dismissed":false,"latencyMs":200}""",
        )
        assertEquals("old-stable-id", response.idempotencyKey)
        assertEquals("old-owner", response.anonymousId)
        assertEquals(1, response.answers.size)
    }
}
