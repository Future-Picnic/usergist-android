package studio.ritmus.feedback.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Persistent identity state — a generated `anonymousId` and an optional
 * developer-supplied `externalId`. Backed by `identity.json`.
 *
 * Immutable — mutation returns a new [Identity]. Persistence is the
 * responsibility of [IdentityStore].
 */
@Serializable
internal data class Identity(
    val anonymousId: String,
    val externalId: String? = null,
    val userProperties: JsonObject? = null,
) {
    fun withExternalId(newExternalId: String?, newProperties: JsonObject?): Identity =
        copy(
            externalId = newExternalId ?: externalId,
            userProperties = newProperties ?: userProperties,
        )
}

/** Thread-safe read/write for the identity file. */
internal class IdentityStore(
    private val storage: Storage,
    private val json: Json,
) {

    @Volatile
    private var cached: Identity? = null

    private val lock = Any()

    /** Loads or lazily initialises the stored identity. */
    fun load(): Identity {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val text = storage.readText(storage.identityFile)
            val parsed = text?.let {
                runCatching { json.decodeFromString<Identity>(it) }.getOrNull()
            }
            val resolved = parsed ?: Identity(anonymousId = UUID.randomUUID().toString()).also {
                persist(it)
            }
            cached = resolved
            return resolved
        }
    }

    fun update(block: (Identity) -> Identity): Identity {
        synchronized(lock) {
            val current = cached ?: load()
            val next = block(current)
            if (next != current) {
                persist(next)
                cached = next
            }
            return next
        }
    }

    /** Wipes the identity — issues a new anonymousId and drops externalId. */
    fun reset(): Identity {
        synchronized(lock) {
            val fresh = Identity(anonymousId = UUID.randomUUID().toString())
            persist(fresh)
            cached = fresh
            return fresh
        }
    }

    private fun persist(identity: Identity) {
        val text = try {
            json.encodeToString(identity)
        } catch (e: Throwable) {
            RitmusLogger.w("IdentityStore.persist encode failed", e)
            return
        }
        storage.writeText(storage.identityFile, text)
    }
}
