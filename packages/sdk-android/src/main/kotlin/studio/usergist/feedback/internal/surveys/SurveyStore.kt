package studio.usergist.feedback.internal.surveys

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import studio.usergist.feedback.internal.Storage
import studio.usergist.feedback.internal.UserGistLogger

@Serializable
private data class StoredSurveyAttempt(
    val surveyId: String,
    val attemptId: String,
    val currentQuestionId: String?,
    val progressSnapshot: Map<String, JsonElement>,
)

@Serializable
private data class StoredSurveyAttempts(
    val version: Int = 1,
    val attempts: List<StoredSurveyAttempt> = emptyList(),
)

/** Versioned local resume snapshot used when progress transport is offline. */
internal class SurveyStore(
    private val storage: Storage,
    private val json: Json,
) {
    private val lock = Any()
    private var attempts = hydrate()

    fun merge(surveyId: String, server: SdkSurveyAttemptSession): SdkSurveyAttemptSession =
        synchronized(lock) {
            val local = attempts[surveyId]
            if (local?.attemptId == server.attemptId) {
                server.copy(
                    currentQuestionId = local.currentQuestionId ?: server.currentQuestionId,
                    progressSnapshot = server.progressSnapshot + local.progressSnapshot,
                    resumed = true,
                )
            } else {
                server
            }
        }

    fun upsert(surveyId: String, attempt: SdkSurveyAttemptSession) = synchronized(lock) {
        attempts[surveyId] = StoredSurveyAttempt(
            surveyId = surveyId,
            attemptId = attempt.attemptId,
            currentQuestionId = attempt.currentQuestionId ?: attempt.startQuestionId,
            progressSnapshot = attempt.progressSnapshot,
        )
        persistLocked()
    }

    fun update(
        attemptId: String,
        currentQuestionId: String?,
        snapshot: Map<String, JsonElement>,
    ) = synchronized(lock) {
        val entry = attempts.entries.firstOrNull { it.value.attemptId == attemptId }
            ?: return@synchronized
        attempts[entry.key] = entry.value.copy(
            currentQuestionId = currentQuestionId,
            progressSnapshot = snapshot,
        )
        persistLocked()
    }

    fun removeAttempt(attemptId: String) = synchronized(lock) {
        attempts.entries.removeAll { it.value.attemptId == attemptId }
        persistLocked()
    }

    fun clear() = synchronized(lock) {
        attempts.clear()
        persistLocked()
    }

    private fun hydrate(): MutableMap<String, StoredSurveyAttempt> {
        val raw = storage.readText(storage.surveyAttemptsFile) ?: return LinkedHashMap()
        val decoded = runCatching {
            json.decodeFromString<StoredSurveyAttempts>(raw)
        }.getOrElse {
            UserGistLogger.w("SurveyStore hydrate failed", it)
            return LinkedHashMap()
        }
        if (decoded.version != 1) return LinkedHashMap()
        return decoded.attempts.associateByTo(LinkedHashMap(), StoredSurveyAttempt::surveyId)
    }

    private fun persistLocked() {
        val encoded = json.encodeToString(
            StoredSurveyAttempts(attempts = attempts.values.toList()),
        )
        if (!storage.writeText(storage.surveyAttemptsFile, encoded)) {
            throw IllegalStateException("Unable to persist survey progress")
        }
    }
}
