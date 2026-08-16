package studio.usergist.feedback.internal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A single event enqueued for ingest.
 *
 * Mirrors the `IngestEvent` shape from `@usergist/sdk-core`.
 */
@Serializable
internal data class IngestEvent(
    val name: String,
    /** ISO-8601 string with millisecond precision (UTC). */
    val timestamp: String,
    @SerialName("anonymousId")
    val anonymousId: String,
    @SerialName("externalId")
    val externalId: String? = null,
    val properties: JsonObject? = null,
    @SerialName("sessionId")
    val sessionId: String? = null,
    @SerialName("sdkVersion")
    val sdkVersion: String,
    @SerialName("appVersion")
    val appVersion: String? = null,
    val platform: String,
)
