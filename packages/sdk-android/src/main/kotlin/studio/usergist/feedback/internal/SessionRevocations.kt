package studio.usergist.feedback.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.usergist.feedback.internal.transport.ApiClient
import java.util.UUID

/** Secure installation-specific logout outbox, independent of user queues. */
internal class SessionRevocations(private val secure: SecureStore, private val api: ApiClient, private val json: Json) {
    @Serializable private data class Entry(val id: String, val token: String, val anonymousId: String)
    @Serializable private data class Body(val anonymousId: String)
    private val lock = Any()
    private val drainLock = Mutex()
    private fun read(): List<Entry> = secure.read(SecureStore.Key.SESSION_REVOCATIONS)?.let { json.decodeFromString<List<Entry>>(it) } ?: emptyList()
    private fun write(items: List<Entry>) {
        check(secure.write(SecureStore.Key.SESSION_REVOCATIONS, json.encodeToString(items))) { "Unable to persist logout cleanup" }
    }
    fun remember(token: String?, anonymousId: String) {
        if (token == null) return
        synchronized(lock) { val entries = read(); if (entries.none { it.token == token && it.anonymousId == anonymousId }) write(entries + Entry(UUID.randomUUID().toString(), token, anonymousId)) }
    }
    fun isPending(): Boolean = synchronized(lock) { read().isNotEmpty() }
    suspend fun drain() = drainLock.withLock {
        for (entry in synchronized(lock) { read() }) {
            val result = api.postJsonDetailed("/v1/sdk/session/revoke", Body(entry.anonymousId), Body.serializer(),
                requiresSubject = false, subjectTokenOverride = entry.token)
            if (!result.success && result.status != 401) return@withLock
            synchronized(lock) { write(read().filterNot { it.id == entry.id }) }
        }
    }
}
