package studio.usergist.feedback.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import studio.usergist.feedback.internal.util.DateTime
import java.util.UUID

@Serializable
internal enum class MutationKind {
    @SerialName("identify") IDENTIFY,
    @SerialName("feedback-response") FEEDBACK_RESPONSE,
    @SerialName("survey-complete") SURVEY_COMPLETE,
    @SerialName("survey-abandon") SURVEY_ABANDON,
}

@Serializable
internal enum class MutationPurpose {
    @SerialName("essential") ESSENTIAL,
    @SerialName("feedback") FEEDBACK,
    @SerialName("survey") SURVEY,
}

@Serializable
internal data class PendingMutation(
    val id: String,
    val kind: MutationKind,
    val purpose: MutationPurpose,
    val payload: JsonObject,
    val createdAt: String,
    val dedupeKey: String? = null,
)

@Serializable
private data class PersistedMutations(
    val version: Int = 1,
    val items: List<PendingMutation> = emptyList(),
)

/** Encrypted, versioned durable queue for non-event state transitions. */
internal class MutationQueue(
    private val storage: Storage,
    private val secure: SecureStore?,
    private val json: Json,
) {
    private val lock = Any()
    private var items: List<PendingMutation> = hydrate()

    fun size(): Int = synchronized(lock) { items.size }
    fun peek(): PendingMutation? = synchronized(lock) { items.firstOrNull() }
    fun has(id: String): Boolean = synchronized(lock) { items.any { it.id == id } }

    fun enqueue(
        kind: MutationKind,
        purpose: MutationPurpose,
        payload: JsonObject,
        dedupeKey: String? = null,
    ): String = synchronized(lock) {
        val existing = dedupeKey?.let { key -> items.firstOrNull { it.dedupeKey == key } }
        if (existing != null) return@synchronized existing.id
        val next = PendingMutation(
            id = UUID.randomUUID().toString(),
            kind = kind,
            purpose = purpose,
            payload = payload,
            createdAt = DateTime.nowIso(),
            dedupeKey = dedupeKey,
        )
        val previous = items
        try {
            items = if (purpose == MutationPurpose.ESSENTIAL) {
                listOf(next) + items
            } else {
                items + next
            }
            persistLocked()
        } catch (error: Throwable) {
            items = previous
            throw error
        }
        next.id
    }

    fun remove(id: String) = synchronized(lock) {
        mutateAndPersist { current -> current.filterNot { it.id == id } }
    }

    fun removePurpose(purpose: MutationPurpose) = synchronized(lock) {
        mutateAndPersist { current -> current.filterNot { it.purpose == purpose } }
    }

    fun clear() = synchronized(lock) {
        mutateAndPersist { emptyList() }
    }

    private fun hydrate(): List<PendingMutation> {
        val secureStore = secure
        val raw = if (secureStore == null) {
            storage.readText(storage.mutationQueueFile)
        } else {
            secureStore.read(SecureStore.Key.MUTATION_QUEUE) ?: run {
                val legacy = storage.readText(storage.mutationQueueFile) ?: return@run null
                val migrated = secureStore.write(SecureStore.Key.MUTATION_QUEUE, legacy)
                storage.delete(storage.mutationQueueFile)
                legacy.takeIf { migrated }
            }
        } ?: return emptyList()
        val decoded = runCatching { json.decodeFromString<PersistedMutations>(raw) }.getOrNull()
        return if (decoded?.version == 1) decoded.items else emptyList()
    }

    private fun persistLocked() {
        val raw = json.encodeToString(PersistedMutations(items = items))
        if (secure == null) {
            if (!storage.writeText(storage.mutationQueueFile, raw)) {
                throw IllegalStateException("Unable to persist UserGist mutation queue")
            }
        } else if (secure.write(SecureStore.Key.MUTATION_QUEUE, raw)) {
            storage.delete(storage.mutationQueueFile)
        } else {
            throw IllegalStateException("Unable to persist UserGist mutation queue")
        }
    }

    private fun mutateAndPersist(transform: (List<PendingMutation>) -> List<PendingMutation>) {
        val previous = items
        try {
            items = transform(previous)
            persistLocked()
        } catch (error: Throwable) {
            items = previous
            throw error
        }
    }
}
