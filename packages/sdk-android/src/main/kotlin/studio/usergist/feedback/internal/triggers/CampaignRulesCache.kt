package studio.usergist.feedback.internal.triggers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.usergist.feedback.api.ArmedInAppMessage
import studio.usergist.feedback.api.ArmedInAppMessagesResponse
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.surveys.ArmedSurvey
import studio.usergist.feedback.internal.surveys.ArmedSurveysResponse
import studio.usergist.feedback.internal.transport.ApiClient
import studio.usergist.feedback.internal.transport.Endpoints

/** Durable hot-path cache for locally-evaluable surveys and in-app messages. */
internal class CampaignRulesCache(
    private val storage: Storage,
    private val json: Json,
) {
    @Volatile
    private var surveys: List<ArmedSurvey> = emptyList()

    @Volatile
    private var surveysByEvent: Map<String, List<ArmedSurvey>> = emptyMap()

    @Volatile
    private var surveysById: Map<String, ArmedSurvey> = emptyMap()

    @Volatile
    private var inAppMessages: List<ArmedInAppMessage> = emptyList()

    @Volatile
    private var inAppByEvent: Map<String, List<ArmedInAppMessage>> = emptyMap()

    private val refreshMutex = Mutex()

    fun bootstrapFromDisk() {
        storage.readText(storage.armedSurveysFile)?.let { raw ->
            runCatching { json.decodeFromString<ArmedSurveysResponse>(raw) }
                .getOrNull()
                ?.let { replaceSurveys(it.surveys, persist = false) }
        }
        storage.readText(storage.armedInAppMessagesFile)?.let { raw ->
            runCatching { json.decodeFromString<ArmedInAppMessagesResponse>(raw) }
                .getOrNull()
                ?.let { replaceInApp(it.messages, persist = false) }
        }
    }

    fun surveysFor(eventName: String): List<ArmedSurvey> =
        surveysByEvent[eventName].orEmpty()

    fun survey(campaignId: String): ArmedSurvey? = surveysById[campaignId]

    fun inAppFor(eventName: String): List<ArmedInAppMessage> =
        inAppByEvent[eventName].orEmpty()

    suspend fun refresh(
        apiClient: ApiClient,
        anonymousId: String,
        externalId: String?,
        includeSurveys: Boolean,
        includeInApp: Boolean,
    ) = refreshMutex.withLock {
        val query = mapOf("anonymousId" to anonymousId, "externalId" to externalId)
        if (includeSurveys) {
            apiClient.getJson(
                path = Endpoints.ARMED_SURVEYS,
                query = query,
                deserializer = ArmedSurveysResponse.serializer(),
            )?.let { response -> replaceSurveys(response.surveys, response) }
        }
        if (includeInApp) {
            apiClient.getJson(
                path = Endpoints.ARMED_INAPP_MESSAGES,
                query = query,
                deserializer = ArmedInAppMessagesResponse.serializer(),
            )?.let { response -> replaceInApp(response.messages, response) }
        }
    }

    fun clear() {
        replaceSurveys(emptyList(), persist = false)
        replaceInApp(emptyList(), persist = false)
        storage.delete(storage.armedSurveysFile)
        storage.delete(storage.armedInAppMessagesFile)
    }

    private fun replaceSurveys(
        next: List<ArmedSurvey>,
        envelope: ArmedSurveysResponse? = null,
        persist: Boolean = true,
    ) {
        surveys = next.toList()
        surveysByEvent = next.groupBy { it.eventName }
        surveysById = next.associateBy { it.campaignId }
        if (persist) {
            val value = envelope ?: ArmedSurveysResponse(surveys = next)
            runCatching { json.encodeToString(value) }
                .onSuccess { storage.writeText(storage.armedSurveysFile, it) }
                .onFailure { UserGistLogger.w("Armed survey cache persist failed", it) }
        }
    }

    private fun replaceInApp(
        next: List<ArmedInAppMessage>,
        envelope: ArmedInAppMessagesResponse? = null,
        persist: Boolean = true,
    ) {
        inAppMessages = next.toList()
        inAppByEvent = next.filter { it.eventName.isNotBlank() }.groupBy { it.eventName }
        if (persist) {
            val value = envelope ?: ArmedInAppMessagesResponse(messages = next)
            runCatching { json.encodeToString(value) }
                .onSuccess { storage.writeText(storage.armedInAppMessagesFile, it) }
                .onFailure { UserGistLogger.w("Armed in-app cache persist failed", it) }
        }
    }
}
