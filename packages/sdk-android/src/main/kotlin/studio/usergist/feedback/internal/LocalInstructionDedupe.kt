package studio.usergist.feedback.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persists locally rendered campaign/event pairs until their matching server
 * instruction is consumed. This prevents process death between ingest and
 * instruction polling from replaying the same SDK surface.
 */
internal class LocalInstructionDedupe(
    private val storage: Storage,
    private val json: Json,
    private val maxKeys: Int = 200,
) {
    private val lock = Any()
    private val keys = LinkedHashSet<String>()

    init {
        val stored = storage.readText(storage.localInstructionDedupeFile)?.let { text ->
            runCatching { json.decodeFromString<State>(text) }.getOrNull()
        } ?: State()
        stored.keys.takeLast(maxKeys).forEach { key ->
            if (key.isNotBlank()) keys += key
        }
    }

    fun remember(key: String) {
        synchronized(lock) {
            keys.remove(key)
            keys += key
            while (keys.size > maxKeys) keys.remove(keys.first())
            persistLocked()
        }
    }

    fun consume(key: String): Boolean = synchronized(lock) {
        if (!keys.remove(key)) return@synchronized false
        persistLocked()
        true
    }

    fun clear() {
        synchronized(lock) {
            keys.clear()
            storage.delete(storage.localInstructionDedupeFile)
        }
    }

    internal fun size(): Int = synchronized(lock) { keys.size }

    internal fun contains(key: String): Boolean = synchronized(lock) { keys.contains(key) }

    private fun persistLocked() {
        val persisted = storage.writeText(
            storage.localInstructionDedupeFile,
            json.encodeToString(State.serializer(), State(keys.toList())),
        )
        if (!persisted) {
            UserGistLogger.w("Failed to persist local instruction dedupe state")
        }
    }

    @Serializable
    private data class State(val keys: List<String> = emptyList())
}
