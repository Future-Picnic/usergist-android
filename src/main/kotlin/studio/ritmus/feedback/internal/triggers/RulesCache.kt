package studio.ritmus.feedback.internal.triggers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.ritmus.feedback.internal.RitmusLogger
import studio.ritmus.feedback.internal.Storage
import studio.ritmus.feedback.internal.model.ArmedTrigger
import studio.ritmus.feedback.internal.model.ArmedTriggersResponse
import studio.ritmus.feedback.internal.transport.ApiClient
import studio.ritmus.feedback.internal.transport.Endpoints

/**
 * In-memory + on-disk cache of the armed triggers for the current user.
 *
 * Refreshed on foreground and every
 * [studio.ritmus.feedback.internal.Config.triggerSyncIntervalMs] via
 * [refresh]. Consumers read via [snapshot] and [triggersFor].
 */
internal class RulesCache(
    private val storage: Storage,
    private val json: Json,
) {

    @Volatile
    private var cachedList: List<ArmedTrigger> = emptyList()

    @Volatile
    private var byEvent: Map<String, List<ArmedTrigger>> = emptyMap()

    private val refreshMutex = Mutex()

    /** Loads cached armed triggers from disk; called once at init. */
    fun bootstrapFromDisk() {
        val text = storage.readText(storage.armedTriggersFile) ?: return
        val parsed = runCatching {
            json.decodeFromString<ArmedTriggersResponse>(text)
        }.getOrNull()
        if (parsed != null) {
            updateSnapshot(parsed.triggers)
            RitmusLogger.d("RulesCache bootstrap: ${parsed.triggers.size} triggers from disk")
        }
    }

    /** Returns the current full list of armed triggers. */
    fun snapshot(): List<ArmedTrigger> = cachedList

    /** Returns triggers whose `eventName` matches [eventName]. */
    fun triggersFor(eventName: String): List<ArmedTrigger> =
        byEvent[eventName].orEmpty()

    /**
     * Fetch the latest armed triggers from the server and persist them.
     * Safe to call concurrently — only one refresh runs at a time.
     */
    suspend fun refresh(
        apiClient: ApiClient,
        anonymousId: String,
        externalId: String?,
    ): Boolean = refreshMutex.withLock {
        val response = apiClient.getJson(
            path = Endpoints.ARMED_TRIGGERS,
            query = mapOf(
                "anonymousId" to anonymousId,
                "externalId" to externalId,
            ),
            deserializer = ArmedTriggersResponse.serializer(),
        ) ?: return@withLock false

        updateSnapshot(response.triggers)
        val text = runCatching { json.encodeToString(response) }.getOrNull()
        if (text != null) {
            storage.writeText(storage.armedTriggersFile, text)
        }
        RitmusLogger.d("RulesCache refresh: ${response.triggers.size} triggers")
        true
    }

    /** Wipe both memory and disk. Used on `reset()`. */
    fun clear() {
        updateSnapshot(emptyList())
        storage.delete(storage.armedTriggersFile)
    }

    private fun updateSnapshot(triggers: List<ArmedTrigger>) {
        val index = HashMap<String, MutableList<ArmedTrigger>>()
        for (trigger in triggers) {
            index.getOrPut(trigger.eventName) { mutableListOf() }.add(trigger)
        }
        cachedList = triggers.toList()
        byEvent = index.mapValues { it.value.toList() }
    }
}
