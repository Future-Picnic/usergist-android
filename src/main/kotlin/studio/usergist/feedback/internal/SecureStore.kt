package studio.usergist.feedback.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import studio.usergist.feedback.internal.util.Hashing

// PORTED FROM (concept): packages/sdk-react-native/src/internal/storage.ts
//
// EncryptedSharedPreferences-backed secrets store for identity, consent,
// and push token. Each entry is scoped to the SDK's writeKey hash so two
// apps embedding the SDK against different keys never collide.
//
// Failures are non-fatal. Credential-bearing values fail closed instead of
// falling back to plaintext SharedPreferences; non-secret compatibility state
// can still use the fallback when encrypted preferences are unavailable.

internal class SecureStore private constructor(
    private val prefs: SharedPreferences?,
    private val fallback: SharedPreferences,
) {

    enum class Key(val raw: String) {
        IDENTITY("identity"),
        CONSENT("consent"),
        PUSH_TOKEN("push_token"),
        SUBJECT_TOKEN("subject_token"),
        SESSION_REVOCATIONS("session_revocations"),
        MUTATION_QUEUE("mutation_queue"),
    }

    fun read(key: Key): String? {
        val encrypted = try {
            prefs?.getString(key.raw, null)
        } catch (e: Throwable) {
            UserGistLogger.w("SecureStore.read(${key.raw}) failed", e)
            null
        }
        if (encrypted != null) {
            fallback.edit().remove(key.raw).commit()
            return encrypted
        }
        val legacy = fallback.getString(key.raw, null) ?: return null
        if (!key.requiresSecureStorage) return legacy
        // Credential data from older releases is usable only after a
        // successful migration into encrypted preferences.
        if (write(key, legacy)) {
            return legacy
        }
        fallback.edit().remove(key.raw).commit()
        return null
    }

    fun write(key: Key, value: String): Boolean {
        return try {
            val encrypted = prefs?.edit()?.putString(key.raw, value)?.commit() == true
            if (encrypted) {
                fallback.edit().remove(key.raw).commit()
                true
            } else if (key.requiresSecureStorage) {
                fallback.edit().remove(key.raw).commit()
                false
            } else {
                fallback.edit().putString(key.raw, value).commit()
            }
        } catch (e: Throwable) {
            UserGistLogger.w("SecureStore.write(${key.raw}) failed", e)
            if (key.requiresSecureStorage) {
                runCatching { fallback.edit().remove(key.raw).commit() }
            }
            false
        }
    }

    fun delete(key: Key): Boolean {
        return try {
            val primaryDeleted = prefs?.edit()?.remove(key.raw)?.commit() ?: true
            val fallbackDeleted = fallback.edit().remove(key.raw).commit()
            primaryDeleted && fallbackDeleted
        } catch (e: Throwable) {
            UserGistLogger.w("SecureStore.delete(${key.raw}) failed", e)
            false
        }
    }

    companion object {
        /** Builds encrypted preferences with a scoped non-secret compatibility fallback. */
        fun create(appContext: Context, writeKey: String): SecureStore {
            val name = "usergist_secrets_${Hashing.shortSha256(writeKey)}"
            val fallback = appContext.getSharedPreferences(
                "${name}_fallback",
                Context.MODE_PRIVATE,
            )
            return try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val prefs = EncryptedSharedPreferences.create(
                    appContext,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                SecureStore(prefs, fallback)
            } catch (e: Throwable) {
                UserGistLogger.w("SecureStore.create failed; credentials will remain memory-only", e)
                SecureStore(null, fallback)
            }
        }
    }
}

private val SecureStore.Key.requiresSecureStorage: Boolean
    get() = when (this) {
        SecureStore.Key.PUSH_TOKEN,
        SecureStore.Key.SUBJECT_TOKEN,
        SecureStore.Key.SESSION_REVOCATIONS,
        SecureStore.Key.MUTATION_QUEUE,
        -> true
        SecureStore.Key.IDENTITY,
        SecureStore.Key.CONSENT,
        -> false
    }
