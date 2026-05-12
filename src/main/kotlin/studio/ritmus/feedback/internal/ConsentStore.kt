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
    val push: Boolean? = null,
    val survey: Boolean? = null,
) {
    fun toPublic(): Consent = Consent(
        analytics = analytics,
        feedback = feedback,
        push = push,
        survey = survey,
    )

    companion object {
        fun from(consent: Consent): ConsentRecord =
            ConsentRecord(
                analytics = consent.analytics,
                feedback = consent.feedback,
                push = consent.push,
                survey = consent.survey,
            )
    }
}

/** Persistent consent state. Backed by encrypted store (legacy: `consent.json`). */
internal class ConsentStore(
    private val storage: Storage,
    private val secure: SecureStore?,
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
            // Encrypted store first.
            val secureText = secure?.read(SecureStore.Key.CONSENT)
            val secureRecord = secureText?.let {
                runCatching { json.decodeFromString<ConsentRecord>(it) }.getOrNull()
            }
            if (secureRecord != null) {
                cached = secureRecord.toPublic()
                loaded = true
                return cached
            }
            // Legacy plaintext migration.
            val legacyText = storage.readText(storage.consentFile)
            val legacyRecord = legacyText?.let {
                runCatching { json.decodeFromString<ConsentRecord>(it) }.getOrNull()
            }
            if (legacyRecord != null) {
                val migrated = secure?.write(SecureStore.Key.CONSENT, legacyText!!) ?: false
                if (migrated) storage.delete(storage.consentFile)
                cached = legacyRecord.toPublic()
                loaded = true
                return cached
            }
            cached = Consent()
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
            val wroteSecure = secure?.write(SecureStore.Key.CONSENT, text) ?: false
            if (!wroteSecure) {
                storage.writeText(storage.consentFile, text)
            }
        }
    }

    /** Wipes any stored consent (revert to "no consent"). */
    fun clear() {
        synchronized(lock) {
            cached = Consent()
            loaded = true
            secure?.delete(SecureStore.Key.CONSENT)
            storage.delete(storage.consentFile)
        }
    }
}
