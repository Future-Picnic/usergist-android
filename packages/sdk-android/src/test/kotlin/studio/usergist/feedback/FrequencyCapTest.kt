package studio.usergist.feedback

import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.model.FrequencyCaps
import studio.usergist.feedback.internal.triggers.FrequencyCapStore
import java.io.File
import java.nio.file.Files

class FrequencyCapTest {

    private lateinit var tempDir: File
    private lateinit var storage: Storage
    private var now: Long = 1_700_000_000_000L
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("usergist-caps-test-").toFile()
        storage = Storage.forTest(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun per_prompt_cap_blocks_inside_window() {
        val store = FrequencyCapStore(storage, json, nowMs = { now })
        val caps = FrequencyCaps(perPromptDays = 7)

        assertTrue(store.allows("p1", caps))
        store.recordShown("p1")

        // 6 days later — still within the window.
        now += 6L * 86_400_000L
        assertFalse(store.allows("p1", caps))

        // 8 days later — outside the window.
        now += 2L * 86_400_000L
        assertTrue(store.allows("p1", caps))
    }

    @Test
    fun per_user_cap_applies_across_prompts() {
        val store = FrequencyCapStore(storage, json, nowMs = { now })
        val caps = FrequencyCaps(perUserDays = 3)

        assertTrue(store.allows("p1", caps))
        store.recordShown("p1")

        // Different prompt, same user — cap still blocks.
        now += 1L * 86_400_000L
        assertFalse(store.allows("p2", caps))

        // After the window — allowed again.
        now += 3L * 86_400_000L
        assertTrue(store.allows("p2", caps))
    }

    @Test
    fun null_caps_always_allow() {
        val store = FrequencyCapStore(storage, json, nowMs = { now })
        assertTrue(store.allows("p1", FrequencyCaps()))
        store.recordShown("p1")
        assertTrue(store.allows("p1", FrequencyCaps()))
    }

    @Test
    fun clear_resets_state() {
        val store = FrequencyCapStore(storage, json, nowMs = { now })
        val caps = FrequencyCaps(perPromptDays = 30)
        store.recordShown("p1")
        assertFalse(store.allows("p1", caps))
        store.clear()
        assertTrue(store.allows("p1", caps))
    }

    @Test
    fun persistence_across_instances() {
        val store = FrequencyCapStore(storage, json, nowMs = { now })
        val caps = FrequencyCaps(perPromptDays = 7)
        store.recordShown("p1")

        val reborn = FrequencyCapStore(storage, json, nowMs = { now })
        assertFalse(reborn.allows("p1", caps))
    }
}
