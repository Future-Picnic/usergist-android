package studio.usergist.feedback.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.usergist.feedback.api.Consent
import studio.usergist.feedback.internal.util.DateTime

/** JSON-serializable twin of the public [Consent] type. */
@Serializable
internal data class ConsentRecord(
    val analytics: Boolean? = null,
    val feedback: Boolean? = null,
    val push: Boolean? = null,
    val survey: Boolean? = null,
    val version: Int = 0,
    val updatedAt: String? = null,
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

    @Volatile
    private var currentVersion: Int = 0

    @Volatile
    private var currentUpdatedAt: String? = null

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
                currentVersion = secureRecord.version
                currentUpdatedAt = secureRecord.updatedAt
                loaded = true
                return cached
            }
            // Legacy plaintext migration.
            val legacyText = storage.readText(storage.consentFile)
            val legacyRecord = legacyText?.let {
                runCatching { json.decodeFromString<ConsentRecord>(it) }.getOrNull()
            }
            if (legacyRecord != null) {
                val migrated = secure?.write(SecureStore.Key.CONSENT, legacyText) ?: false
                if (migrated) storage.delete(storage.consentFile)
                cached = legacyRecord.toPublic()
                currentVersion = legacyRecord.version
                currentUpdatedAt = legacyRecord.updatedAt
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
    fun set(consent: Consent): Consent {
        synchronized(lock) {
            val previous = get()
            cached = Consent(
                analytics = consent.analytics ?: previous.analytics,
                feedback = consent.feedback ?: previous.feedback,
                push = consent.push ?: previous.push,
                survey = consent.survey ?: previous.survey,
            )
            currentVersion += 1
            currentUpdatedAt = DateTime.nowIso()
            loaded = true
            val text = try {
                json.encodeToString(
                    ConsentRecord(
                        analytics = cached.analytics,
                        feedback = cached.feedback,
                        push = cached.push,
                        survey = cached.survey,
                        version = currentVersion,
                        updatedAt = currentUpdatedAt,
                    ),
                )
            } catch (e: Throwable) {
                UserGistLogger.w("ConsentStore.set encode failed", e)
                return cached
            }
            val wroteSecure = secure?.write(SecureStore.Key.CONSENT, text) ?: false
            if (!wroteSecure) {
                storage.writeText(storage.consentFile, text)
            }
            return cached
        }
    }

    fun version(): Int = synchronized(lock) {
        get()
        currentVersion
    }

    fun updatedAt(): String = synchronized(lock) {
        get()
        currentUpdatedAt ?: DateTime.nowIso()
    }

    /** Wipes any stored consent (revert to "no consent"). */
    fun clear() {
        synchronized(lock) {
            cached = Consent()
            currentVersion += 1
            currentUpdatedAt = DateTime.nowIso()
            loaded = true
            secure?.delete(SecureStore.Key.CONSENT)
            storage.delete(storage.consentFile)
        }
    }
}
