package studio.usergist.feedback

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import studio.usergist.feedback.internal.model.IngestBatch
import studio.usergist.feedback.internal.model.IngestContext
import studio.usergist.feedback.internal.model.WireIngestEvent
import studio.usergist.feedback.internal.transport.ApiClient
import java.util.UUID

class NativeDeliveryContextTest {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    private fun batch(count: Int): IngestBatch = IngestBatch(
        events = (0 until count).map { i -> WireIngestEvent(
            eventId = UUID.randomUUID().toString(), name = "qa.remote.$i",
            timestamp = "2026-11-11T00:00:00.000Z", anonymousId = "installation",
            sdkVersion = "0.2.0", platform = "android",
        ) },
        context = IngestContext(anonymousId = "installation", sdkVersion = "0.2.0", platform = "android"),
    )

    @Test fun actualRequestAuthorizesTheExactIngestedEvent() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"success\":true,\"data\":{\"accepted\":1}}"))
            val payload = batch(1)
            val client = ApiClient(baseUrl = server.url("/").toString().trimEnd('/'), writeKey = "qa", json = json)
            client.setSubjectToken("st_test")
            client.postJson("/v1/sdk/ingest", payload, IngestBatch.serializer())
            val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertNotNull("Server-side targeting requires delivery.eventIds", body["delivery"])
            assertEquals(payload.events.map { it.eventId }, body["delivery"]!!.jsonObject["eventIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        } finally { server.shutdown() }
    }

    @Test fun twentyEventBoundaryRetainsEveryAuthorization() {
        val payload = batch(20)
        val body = json.encodeToJsonElement(IngestBatch.serializer(), payload).jsonObject
        assertNotNull(body["delivery"])
        assertEquals(payload.events.map { it.eventId }, body["delivery"]!!.jsonObject["eventIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun rejectsOversizedAndEmptyNativeDeliveryBatches() {
        assertThrows(IllegalArgumentException::class.java) { batch(21) }
        assertThrows(IllegalArgumentException::class.java) { batch(0) }
    }
}
