package studio.ritmus.feedback

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.ritmus.feedback.internal.transport.ApiClient
import studio.ritmus.feedback.internal.transport.RetryPolicy

/**
 * MockWebServer-driven tests for the transport layer. Verifies:
 *  - POST body + headers are correct
 *  - 5xx triggers a retry and then succeeds
 *  - 429 with Retry-After is honored (low retryAfter for fast tests)
 *  - 4xx (non-429) is terminal with no retry
 */
class ApiClientTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Serializable
    data class Payload(val hello: String, val n: Int)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun post_json_attaches_auth_header_and_body() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val client = ApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            writeKey = "wk_abc",
            json = json,
            policy = RetryPolicy(maxAttempts = 1),
        )
        val ok = client.postJson(
            path = "/v1/sdk/ingest",
            body = Payload("hi", 7),
            serializer = Payload.serializer(),
        )
        assertTrue(ok)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/sdk/ingest", recorded.path)
        assertEquals("Bearer wk_abc", recorded.getHeader("Authorization"))
        val contentType = recorded.getHeader("Content-Type") ?: ""
        assertTrue("content-type was $contentType", contentType.startsWith("application/json"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"hello\":\"hi\""))
        assertTrue(body.contains("\"n\":7"))
    }

    @Test
    fun retries_server_error_then_succeeds() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val client = ApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            writeKey = "wk_abc",
            json = json,
            policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, capDelayMs = 4),
        )
        val ok = client.postJson("/x", Payload("a", 1), Payload.serializer())
        assertTrue(ok)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun honors_retry_after_on_429() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "0"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val client = ApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            writeKey = "wk_abc",
            json = json,
            policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, capDelayMs = 4),
        )
        val ok = client.postJson("/x", Payload("a", 1), Payload.serializer())
        assertTrue(ok)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun does_not_retry_4xx_non_429() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))
        val client = ApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            writeKey = "wk_abc",
            json = json,
            policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 1, capDelayMs = 4),
        )
        val ok = client.postJson("/x", Payload("a", 1), Payload.serializer())
        assertEquals(false, ok)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun get_json_parses_response() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"value":"hi"}"""))
        val client = ApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            writeKey = "wk_abc",
            json = json,
            policy = RetryPolicy(maxAttempts = 1),
        )

        @Serializable
        data class Resp(val value: String)

        val r = client.getJson("/v1/sdk/armed-triggers", mapOf("anonymousId" to "a-1"), Resp.serializer())
        assertNotNull(r)
        assertEquals("hi", r!!.value)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/v1/sdk/armed-triggers"))
        assertTrue(recorded.path!!.contains("anonymousId=a-1"))
    }
}
