package studio.usergist.feedback.internal.triggers

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.model.FrequencyCaps

/**
 * Persistent sliding-window frequency-cap store.
 *
 * For each `promptId` we record the timestamps of every prompt we have
 * shown; when checking `allows(...)` we drop any timestamps older than
 * the window. Per-user caps are tracked under the synthetic key
 * [USER_CAP_KEY].
 *
 * This is intentionally simple: v1 cap windows are days, the server is
 * authoritative, and the SDK only needs to avoid obvious spam. The
 * store trims itself to [MAX_TIMESTAMPS_PER_KEY] entries per key to
 * keep the JSON payload small.
 */
internal class FrequencyCapStore(
    private val storage: Storage,
    private val json: Json,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()

    @Volatile
    private var cached: MutableMap<String, MutableList<Long>>? = null

    /** True iff caps permit firing [promptId] right now. */
    fun allows(promptId: String, caps: FrequencyCaps): Boolean {
        val now = nowMs()
        synchronized(lock) {
            val state = loadLocked()
            val perPrompt = caps.perPromptDays
            if (perPrompt != null && perPrompt > 0) {
                val cutoff = now - daysToMs(perPrompt)
                if (state[promptId].orEmpty().any { it >= cutoff }) {
                    UserGistLogger.d("FrequencyCap: blocked $promptId by per-prompt cap ($perPrompt d)")
                    return false
                }
            }
            val perUser = caps.perUserDays
            if (perUser != null && perUser > 0) {
                val cutoff = now - daysToMs(perUser)
                if (state[USER_CAP_KEY].orEmpty().any { it >= cutoff }) {
                    UserGistLogger.d("FrequencyCap: blocked $promptId by per-user cap ($perUser d)")
                    return false
                }
            }
            return true
        }
    }

    /** Record that [promptId] was shown right now. */
    fun recordShown(promptId: String) {
        val now = nowMs()
        synchronized(lock) {
            val state = loadLocked()
            append(state, promptId, now)
            append(state, USER_CAP_KEY, now)
            persistLocked(state)
        }
    }

    /** Wipe all recorded timestamps. Used on `reset()`. */
    fun clear() {
        synchronized(lock) {
            cached = mutableMapOf()
            storage.delete(storage.frequencyCapsFile)
        }
    }

    // ---------------- Internal ----------------

    private fun append(
        state: MutableMap<String, MutableList<Long>>,
        key: String,
        timestamp: Long,
    ) {
        val list = state.getOrPut(key) { mutableListOf() }
        list.add(timestamp)
        if (list.size > MAX_TIMESTAMPS_PER_KEY) {
            // Drop oldest.
            val excess = list.size - MAX_TIMESTAMPS_PER_KEY
            repeat(excess) { list.removeAt(0) }
        }
    }

    private fun loadLocked(): MutableMap<String, MutableList<Long>> {
        cached?.let { return it }
        val text = storage.readText(storage.frequencyCapsFile)
        val snapshot = text?.let {
            runCatching { json.decodeFromString<Snapshot>(it) }.getOrNull()
        }
        val map: MutableMap<String, MutableList<Long>> = snapshot?.timestamps
            ?.mapValues { it.value.toMutableList() }
            ?.toMutableMap()
            ?: mutableMapOf()
        cached = map
        return map
    }

    private fun persistLocked(state: Map<String, MutableList<Long>>) {
        val copy = state.mapValues { it.value.toList() }
        val text = try {
            json.encodeToString(Snapshot(copy))
        } catch (e: Throwable) {
            UserGistLogger.w("FrequencyCapStore.persist encode failed", e)
            return
        }
        storage.writeText(storage.frequencyCapsFile, text)
    }

    private fun daysToMs(days: Int): Long = days.toLong() * 86_400_000L

    /** Persisted shape — stable on disk. */
    @Serializable
    private data class Snapshot(
        val timestamps: Map<String, List<Long>> = emptyMap(),
    )

    companion object {
        /** Synthetic key under which per-user caps are tracked. */
        private const val USER_CAP_KEY: String = "__user__"

        /** Bound timestamps per key to keep the JSON payload small. */
        private const val MAX_TIMESTAMPS_PER_KEY: Int = 128
    }
}
