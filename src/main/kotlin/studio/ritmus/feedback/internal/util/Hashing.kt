package studio.ritmus.feedback.internal.util

import java.security.MessageDigest

/** Small hashing helpers for filesystem-safe keying of write keys. */
internal object Hashing {

    /**
     * Returns the first 16 hex characters of SHA-256(input). Collision
     * probability is negligible for our use case (disambiguating storage
     * directories by write key) and the shorter path stays comfortably
     * under filesystem limits.
     */
    fun shortSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            hex.append(HEX[(b.toInt() ushr 4) and 0x0F])
            hex.append(HEX[b.toInt() and 0x0F])
        }
        return hex.substring(0, 16)
    }

    private val HEX: CharArray = "0123456789abcdef".toCharArray()
}
