package studio.usergist.feedback

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import studio.usergist.feedback.internal.requests.RequestsApi
import studio.usergist.feedback.internal.transport.ApiClient
import studio.usergist.feedback.internal.transport.RetryPolicy
import java.util.UUID

class RequestsApiContractTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    @Test fun submit_and_comment_include_required_idempotency_keys() = runTest {
        val api = RequestsApi(
            ApiClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                writeKey = "wk_test",
                json = json,
                policy = RetryPolicy(maxAttempts = 1),
            ).apply { setSubjectToken("st_test") },
            json,
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope(requestJson)))
        assertNotNull(api.submit("anon-1", null, "Title", "Description"))
        assertUuidBody(server.takeRequest().body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope(commentJson)))
        assertNotNull(api.postComment("request-1", "anon-1", null, "Comment"))
        assertUuidBody(server.takeRequest().body.readUtf8())
    }

    private fun assertUuidBody(raw: String) {
        val key = json.parseToJsonElement(raw).jsonObject["idempotencyKey"]!!.jsonPrimitive.content
        assertNotNull(UUID.fromString(key))
    }

    private fun envelope(data: String) = """{"success":true,"data":$data}"""

    private val requestJson = """{"id":"request-1","appId":"app-1","title":"Title","description":"Description","status":"under_review","devResponse":null,"upvoteCount":0,"followerCount":0,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","statusChangedAt":"2026-01-01T00:00:00Z","lastRespondedAt":null,"viewerHasUpvoted":false,"viewerIsFollowing":false,"viewerIsSubmitter":true}"""
    private val commentJson = """{"id":"comment-1","requestId":"request-1","body":"Comment","authorAnonymousId":"anon-1","authorExternalId":null,"viewerIsAuthor":true,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""
}
