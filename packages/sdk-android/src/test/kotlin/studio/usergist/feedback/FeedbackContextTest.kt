package studio.usergist.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.usergist.feedback.internal.MutationKind
import studio.usergist.feedback.internal.MutationPurpose
import studio.usergist.feedback.internal.MutationQueue
import studio.usergist.feedback.internal.Storage
import java.nio.file.Files

class FeedbackContextTest {
    @Test
    fun responseContextSurvivesDurableRetry() {
        val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        val storage = Storage.forTest(Files.createTempDirectory("usergist-feedback-context-").toFile())
        val payload = UserGist.SdkResponsePayload(
            idempotencyKey = "stable-response-id", promptId = "rating-prompt",
            anonymousId = "anonymous-a", answers = listOf(
                UserGist.SdkResponseAnswer("rating", JsonPrimitive(5)),
            ), dismissed = false, latencyMs = 123,
            platform = "android", sdkVersion = BuildConfig.SDK_VERSION,
            appVersion = "1.0-debug",
        )
        MutationQueue(storage, secure = null, json = json).enqueue(
            MutationKind.FEEDBACK_RESPONSE, MutationPurpose.FEEDBACK,
            json.encodeToJsonElement(UserGist.SdkResponsePayload.serializer(), payload).jsonObject,
        )
        val restored = MutationQueue(storage, secure = null, json = json).peek()!!.payload
        assertEquals("android", restored["platform"]!!.jsonPrimitive.content)
        assertEquals(BuildConfig.SDK_VERSION, restored["sdkVersion"]!!.jsonPrimitive.content)
        assertEquals("1.0-debug", restored["appVersion"]!!.jsonPrimitive.content)
        assertEquals("stable-response-id", restored["idempotencyKey"]!!.jsonPrimitive.content)
        assertEquals("anonymous-a", restored["anonymousId"]!!.jsonPrimitive.content)
    }
}
