package studio.usergist.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.MutationKind
import studio.usergist.feedback.internal.MutationPurpose
import studio.usergist.feedback.internal.MutationQueue
import studio.usergist.feedback.internal.Storage
import java.nio.file.Files

class MutationQueueTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun persists_deduplicates_prioritizes_and_removes_by_purpose() {
        val root = Files.createTempDirectory("usergist-mutations-").toFile()
        try {
            val storage = Storage.forTest(root)
            val queue = MutationQueue(storage, secure = null, json = json)
            val feedbackId = queue.enqueue(
                MutationKind.FEEDBACK_RESPONSE,
                MutationPurpose.FEEDBACK,
                buildJsonObject { put("promptId", "p-1") },
                dedupeKey = "response:p-1",
            )
            val duplicate = queue.enqueue(
                MutationKind.FEEDBACK_RESPONSE,
                MutationPurpose.FEEDBACK,
                buildJsonObject { put("promptId", "p-1") },
                dedupeKey = "response:p-1",
            )
            val identifyId = queue.enqueue(
                MutationKind.IDENTIFY,
                MutationPurpose.ESSENTIAL,
                buildJsonObject { put("externalId", "u-1") },
            )

            assertEquals(feedbackId, duplicate)
            assertEquals(2, queue.size())
            assertEquals(identifyId, queue.peek()?.id)

            val restored = MutationQueue(storage, secure = null, json = json)
            assertEquals(2, restored.size())
            restored.removePurpose(MutationPurpose.FEEDBACK)
            assertFalse(restored.has(feedbackId))
            assertTrue(restored.has(identifyId))
        } finally {
            root.deleteRecursively()
        }
    }
    @Test
    fun fresh_identity_replaces_expired_queue_entry_without_losing_guest_properties() {
        val storage = Storage.forTest(Files.createTempDirectory("usergist-identity-queue-").toFile())
        val queue = MutationQueue(storage, secure = null, json = json)
        fun payload(token: String, guest: Boolean) = buildJsonObject {
            put("subjectToken", token); put("externalId", "backend-id")
            put("properties", buildJsonObject { put("isAnonymous", guest); if (guest) put("plan", "free") })
        }
        val old = queue.enqueue(MutationKind.IDENTIFY, MutationPurpose.ESSENTIAL, payload("st_expired", true), "identify:backend-id")
        val fresh = queue.enqueue(MutationKind.IDENTIFY, MutationPurpose.ESSENTIAL, payload("st_fresh", false), "identify:backend-id")
        assertFalse(old == fresh)
        queue.remove(old)
        val restored = MutationQueue(storage, secure = null, json = json)
        assertTrue(restored.has(fresh))
        assertTrue(restored.peek()!!.payload.toString().contains("st_fresh"))
        assertTrue(restored.peek()!!.payload.toString().contains("free"))
        assertTrue(restored.peek()!!.payload.toString().contains("false"))
    }

}
