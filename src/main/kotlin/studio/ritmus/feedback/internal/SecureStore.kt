package studio.ritmus.feedback.internal

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import studio.ritmus.feedback.internal.util.Hashing

// PORTED FROM (concept): packages/sdk-react-native/src/internal/storage.ts
//
// EncryptedSharedPreferences-backed secrets store for identity, consent,
// and push token. Each entry is scoped to the SDK's writeKey hash so two
// apps embedding the SDK against different keys never collide.
//
// Failures are non-fatal: EncryptedSharedPreferences can fail on devices
// with corrupt or revoked keystore entries (rare, recoverable by clearing
// app data). Callers should treat a `null` read as "no data yet" and
// tolerate a `false` write as best-effort.

internal class SecureStore private constructor(
    private val prefs: SharedPreferences?,
) {

    enum class Key(val raw: String) {
        IDENTITY("identity"),
        CONSENT("consent"),
        PUSH_TOKEN("push_token"),
    }

    fun read(key: Key): String? = prefs?.getString(key.raw, null)

    fun write(key: Key, value: String): Boolean {
        val p = prefs ?: return false
        return try {
            p.edit().putString(key.raw, value).commit()
        } catch (e: Throwable) {
            RitmusLogger.w("SecureStore.write(${key.raw}) failed", e)
            false
        }
    }

    fun delete(key: Key): Boolean {
        val p = prefs ?: return false
        return try {
            p.edit().remove(key.raw).commit()
        } catch (e: Throwable) {
            RitmusLogger.w("SecureStore.delete(${key.raw}) failed", e)
            false
        }
    }

    companion object {
        /** Build a SecureStore. Returns a no-op stub if encryption setup fails. */
        fun create(appContext: Context, writeKey: String): SecureStore {
            val name = "ritmus_secrets_${Hashing.shortSha256(writeKey)}"
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
                SecureStore(prefs)
            } catch (e: Throwable) {
                RitmusLogger.w("SecureStore.create failed; falling back to plaintext-only mode", e)
                SecureStore(null)
            }
        }
    }
}
