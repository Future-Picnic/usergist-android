package studio.usergist.feedback

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
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
            canRead = { true }, canWrite = { true },
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope(requestJson)))
        assertNotNull(api.submit("anon-1", null, "Title", "Description"))
        assertUuidBody(server.takeRequest().body.readUtf8())

        server.enqueue(MockResponse().setResponseCode(200).setBody(envelope(commentJson)))
        assertNotNull(api.postComment("request-1", "anon-1", null, "Comment"))
        assertUuidBody(server.takeRequest().body.readUtf8())
    }

    @Test fun live_consent_blocks_network_until_granted_and_again_after_revocation() = runTest {
        var readAllowed = false
        var writeAllowed = false
        val api = RequestsApi(ApiClient(server.url("/").toString().trimEnd('/'),
            "wk_test", json, policy = RetryPolicy(maxAttempts = 1)).apply { setSubjectToken("st_test") },
            json, canRead = { readAllowed }, canWrite = { writeAllowed })
        repeat(12) { server.enqueue(MockResponse().setBody(envelope("{}"))) }
        val options = studio.usergist.feedback.api.GetRequestsOptions()
        api.list("anon", null, options)
        api.getOne("request", "anon", null)
        api.branding()
        api.comments("request", "anon", null)
        api.submit("anon", null, "Title", "Description")
        api.vote("request", "anon", null, true)
        api.follow("request", "anon", null, true)
        api.postComment("request", "anon", null, "Comment")
        api.editComment("request", "comment", "anon", "Comment")
        api.deleteComment("request", "comment", "anon")
        assertEquals(0, server.requestCount)

        readAllowed = true // Another purpose may allow public reads, not writes.
        api.list("anon", null, options)
        api.submit("anon", null, "Title", "Description")
        assertEquals(1, server.requestCount)
        writeAllowed = true
        api.submit("anon", null, "Title", "Description")
        assertEquals(2, server.requestCount)
        writeAllowed = false
        readAllowed = false
        api.submit("anon", null, "Title", "Description")
        api.list("anon", null, options)
        assertEquals(2, server.requestCount)
    }

    private fun assertUuidBody(raw: String) {
        val key = json.parseToJsonElement(raw).jsonObject["idempotencyKey"]!!.jsonPrimitive.content
        assertNotNull(UUID.fromString(key))
    }

    private fun envelope(data: String) = """{"success":true,"data":$data}"""

    private val requestJson = """{"id":"request-1","appId":"app-1","title":"Title","description":"Description","status":"under_review","devResponse":null,"upvoteCount":0,"followerCount":0,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","statusChangedAt":"2026-01-01T00:00:00Z","lastRespondedAt":null,"viewerHasUpvoted":false,"viewerIsFollowing":false,"viewerIsSubmitter":true}"""
    private val commentJson = """{"id":"comment-1","requestId":"request-1","body":"Comment","authorAnonymousId":"anon-1","authorExternalId":null,"viewerIsAuthor":true,"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}"""
}
