package studio.usergist.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.usergist.feedback.internal.EventQueue
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.model.IngestEvent
import java.io.File
import java.nio.file.Files

/**
 * Covers the contract promised in DEV_PRD §6:
 *  - FIFO append + drain round-trip
 *  - bounded queue: drop-oldest on overflow
 *  - persistence round-trip across re-instantiation
 */
class EventQueueTest {

    private lateinit var tempDir: File
    private lateinit var storage: Storage
    private lateinit var queue: EventQueue
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("usergist-queue-test-").toFile()
        storage = Storage.forTest(tempDir)
        queue = EventQueue(storage, json, maxQueueSize = 10)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun append_peek_and_drop_round_trip() {
        enqueue("a", 1)
        enqueue("a", 2)
        enqueue("a", 3)
        assertEquals(3, queue.size())

        val peeked = queue.peek(2)
        assertEquals(2, peeked.size)
        assertEquals("a", peeked[0].name)
        assertEquals("a", peeked[1].name)

        queue.drop(2)
        assertEquals(1, queue.size())
        val remaining = queue.peek(10)
        assertEquals(1, remaining.size)
        assertEquals("a", remaining.first().name)
    }

    @Test
    fun overflow_drops_oldest() {
        for (i in 1..13) enqueue("evt", i)
        assertEquals(10, queue.size())

        val remaining = queue.peek(100)
        assertEquals(10, remaining.size)
        val firstN = remaining.first().properties?.get("n")?.toString()
        assertEquals("4", firstN)
        val lastN = remaining.last().properties?.get("n")?.toString()
        assertEquals("13", lastN)
    }

    @Test
    fun persistence_roundtrip() {
        enqueue("a", 1)
        enqueue("b", 2)

        val reborn = EventQueue(storage, json, maxQueueSize = 10)
        assertEquals(2, reborn.size())
        val events = reborn.peek(10)
        assertEquals("a", events[0].name)
        assertEquals("b", events[1].name)
    }

    @Test
    fun unbounded_peek_reads_the_bounded_queue_without_preallocating_the_limit() {
        enqueue("a", 1)
        enqueue("b", 2)

        val events = queue.peek(Int.MAX_VALUE)

        assertEquals(2, events.size)
        assertEquals(listOf("a", "b"), events.map(IngestEvent::name))
    }

    @Test
    fun clear_empties_queue() {
        enqueue("x", 1)
        assertTrue(queue.size() > 0)
        queue.clear()
        assertEquals(0, queue.size())
        assertFalse(storage.eventsFile.exists())
    }

    @Test
    fun persisted_file_includes_version_header() {
        enqueue("a", 1)
        val firstLine = storage.eventsFile.bufferedReader().use { it.readLine() }
        assertEquals("{\"version\":2}", firstLine)
    }

    @Test
    fun hydrates_legacy_bare_lines_and_rewrites_with_header() {
        // Simulates a pre-versioning SDK install: the events.log file
        // contains bare event lines with no header. Mirrors the fallback
        // in packages/sdk-react-native/src/internal/queue.ts.
        storage.eventsFile.parentFile?.mkdirs()
        val legacy = buildJsonObject {
            put("name", JsonPrimitive("legacy"))
            put("timestamp", JsonPrimitive("2026-04-21T00:00:00.000Z"))
            put("anonymousId", JsonPrimitive("anon-1"))
            put("sdkVersion", JsonPrimitive("0.1.1"))
            put("platform", JsonPrimitive("android"))
        }
        storage.eventsFile.writeText(legacy.toString() + "\n")

        val reborn = EventQueue(storage, json, maxQueueSize = 10)
        assertEquals(1, reborn.size())
        val peeked = reborn.peek(10)
        assertEquals("legacy", peeked.first().name)

        // Force a rewrite (overflow path also writes the header).
        for (i in 1..10) enqueueInto(reborn, "evt", i)
        val firstLine = storage.eventsFile.bufferedReader().use { it.readLine() }
        assertEquals("{\"version\":2}", firstLine)
    }

    @Test
    fun discards_unknown_version_header() {
        storage.eventsFile.parentFile?.mkdirs()
        storage.eventsFile.writeText("{\"version\":999}\n{\"name\":\"x\"}\n")
        val reborn = EventQueue(storage, json, maxQueueSize = 10)
        assertEquals(0, reborn.size())
        assertFalse(storage.eventsFile.exists())
    }

    private fun enqueueInto(target: EventQueue, name: String, n: Int) {
        val e = IngestEvent(
            name = name,
            timestamp = "2026-04-21T00:00:00.000Z",
            anonymousId = "anon-1",
            externalId = null,
            properties = buildJsonObject { put("n", JsonPrimitive(n)) },
            sessionId = null,
            sdkVersion = "0.1.1",
            appVersion = null,
            platform = "android",
        )
        assertTrue(target.enqueue(e))
    }

    private fun enqueue(name: String, n: Int) {
        val e = IngestEvent(
            name = name,
            timestamp = "2026-04-21T00:00:00.000Z",
            anonymousId = "anon-1",
            externalId = null,
            properties = buildJsonObject { put("n", JsonPrimitive(n)) },
            sessionId = null,
            sdkVersion = "0.1.1",
            appVersion = null,
            platform = "android",
        )
        assertTrue(queue.enqueue(e))
    }
}
