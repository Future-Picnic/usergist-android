package studio.usergist.feedback.internal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Static-ish device / SDK context attached to each ingest batch.
 *
 * Kept separate from [IngestEvent] so common fields aren't repeated on
 * every event in the batch.
 */
@Serializable
internal data class IngestContext(
    @SerialName("anonymousId")
    val anonymousId: String,
    @SerialName("externalId")
    val externalId: String? = null,
    @SerialName("sdkVersion")
    val sdkVersion: String,
    val platform: String,
    @SerialName("appVersion")
    val appVersion: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    @SerialName("osName")
    val osName: String? = null,
    @SerialName("osVersion")
    val osVersion: String? = null,
    @SerialName("deviceModel")
    val deviceModel: String? = null,
)

/** Wire-level request body for `POST /v1/sdk/ingest`. */
@Serializable
internal data class IngestBatch(
    val events: List<WireIngestEvent>,
    val context: IngestContext,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val delivery: NativeIngestDelivery = NativeIngestDelivery(events.map { it.eventId }),
) {
    init {
        require(events.size in 1..20) { "Native ingest delivery requires 1–20 events" }
        require(delivery.eventIds == events.map { it.eventId }) { "Delivery IDs must match the ingested events" }
    }
}

@Serializable
internal data class NativeIngestDelivery(val eventIds: List<String>)

/** Response for `POST /v1/sdk/ingest`. */
@Serializable
internal data class IngestResponse(
    val accepted: Int = 0,
    val rejected: Int = 0,
)
