package studio.usergist.feedback.internal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The server-issued description of a feedback prompt "armed" for this
 * app / user. Refreshed from `GET /v1/sdk/armed-triggers` on foreground
 * and every [studio.usergist.feedback.internal.Config.triggerSyncIntervalMs].
 */
@Serializable
internal data class ArmedTrigger(
    @SerialName("promptId") val promptId: String,
    @SerialName("eventName") val eventName: String,
    @SerialName("segmentRules") val segmentRules: SerializedSegmentRules? = null,
    val frequency: FrequencyCaps = FrequencyCaps(),
    val prompt: ClientPrompt,
)

/** Per-prompt and per-user caps. Units: days. */
@Serializable
internal data class FrequencyCaps(
    @SerialName("perPromptDays") val perPromptDays: Int? = null,
    @SerialName("perUserDays") val perUserDays: Int? = null,
)

/** Response body for `GET /v1/sdk/armed-triggers`. */
@Serializable
internal data class ArmedTriggersResponse(
    val triggers: List<ArmedTrigger> = emptyList(),
    @SerialName("serverTime") val serverTime: String? = null,
    @SerialName("nextSyncMs") val nextSyncMs: Long? = null,
)
