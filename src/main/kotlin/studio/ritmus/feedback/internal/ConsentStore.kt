package studio.ritmus.feedback.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.ritmus.feedback.api.Consent

/** JSON-serializable twin of the public [Consent] type. */
@Serializable
internal data class ConsentRecord(
    val analytics: Boolean? = null,
    val feedback: Boolean? = null,
) {
    fun toPublic(): Consent = Consent(analytics = analytics, feedback = feedback)

    companion object {
        fun from(consent: Consent): ConsentRecord =
            ConsentRecord(analytics = consent.analytics, feedback = consent.feedback)
    }
}

/** Persistent consent state. Backed by `consent.json`. */
internal class ConsentStore(
    private val storage: Storage,
    private val json: Json,
) {

    @Volatile
    private var cached: Consent = Consent()

    @Volatile
    private var loaded: Boolean = false

    private val lock = Any()

    /** Returns the current consent state, loading from disk on first call. */
    fun get(): Consent {
        if (loaded) return cached
        synchronized(lock) {
            if (loaded) return cached
            val text = storage.readText(storage.consentFile)
            val record = text?.let {
                runCatching { json.decodeFromString<ConsentRecord>(it) }.getOrNull()
            }
            cached = record?.toPublic() ?: Consent()
            loaded = true
            return cached
        }
    }

    /** Hard-blocks transport until [Consent.allowsTransport] returns true. */
    fun allowsTransport(): Boolean = get().allowsTransport

    /** Persists a new consent snapshot. */
    fun set(consent: Consent) {
        synchronized(lock) {
            cached = consent
            loaded = true
            val text = try {
                json.encodeToString(ConsentRecord.from(consent))
            } catch (e: Throwable) {
                RitmusLogger.w("ConsentStore.set encode failed", e)
                return
            }
            storage.writeText(storage.consentFile, text)
        }
    }

    /** Wipes any stored consent (revert to "no consent"). */
    fun clear() {
        synchronized(lock) {
            cached = Consent()
            loaded = true
            storage.delete(storage.consentFile)
        }
    }
}
