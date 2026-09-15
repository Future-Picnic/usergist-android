package studio.usergist.feedback.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom

private const val ANONYMOUS_ID_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
private const val ANONYMOUS_ID_LENGTH = 21
private val anonymousIdRandom = SecureRandom()

private fun generateAnonymousId(): String = buildString(ANONYMOUS_ID_LENGTH) {
    repeat(ANONYMOUS_ID_LENGTH) {
        append(ANONYMOUS_ID_ALPHABET[anonymousIdRandom.nextInt(ANONYMOUS_ID_ALPHABET.length)])
    }
}

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
            userProperties = newProperties?.let { JsonObject((userProperties ?: JsonObject(emptyMap())) + it) } ?: userProperties,
        )
}

/** Thread-safe read/write for the identity file. */
internal class IdentityStore(
    private val storage: Storage,
    private val secure: SecureStore?,
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
            // Preferred path: encrypted store.
            val secureText = secure?.read(SecureStore.Key.IDENTITY)
            val secureParsed = secureText?.let {
                runCatching { json.decodeFromString<Identity>(it) }.getOrNull()
            }
            if (secureParsed != null) {
                cached = secureParsed
                return secureParsed
            }
            // Legacy migration: read plaintext file, write encrypted, delete plaintext.
            val legacyText = storage.readText(storage.identityFile)
            val legacyParsed = legacyText?.let {
                runCatching { json.decodeFromString<Identity>(it) }.getOrNull()
            }
            if (legacyParsed != null) {
                val migrated = secure?.write(
                    SecureStore.Key.IDENTITY,
                    legacyText,
                ) ?: false
                if (migrated) storage.delete(storage.identityFile)
                cached = legacyParsed
                return legacyParsed
            }
            // Fresh install.
            val resolved = Identity(anonymousId = generateAnonymousId())
            persist(resolved)
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
            val fresh = Identity(anonymousId = generateAnonymousId())
            persist(fresh)
            cached = fresh
            return fresh
        }
    }

    private fun persist(identity: Identity) {
        val text = json.encodeToString(identity)
        if (secure != null) {
            check(secure.write(SecureStore.Key.IDENTITY, text)) { "Unable to persist identity securely" }
        } else {
            storage.writeText(storage.identityFile, text)
        }
    }
}
