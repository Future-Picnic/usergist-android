package studio.usergist.feedback.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Server-authorized in-app message rendered by the SDK. */
@Serializable
data class ArmedInAppMessage(
    val messageId: String,
    val eventName: String = "server",
    val clientSideEligible: Boolean? = null,
    val format: InAppMessageFormat,
    val title: String,
    val body: String? = null,
    val imageUrl: String? = null,
    val backgroundColor: String? = null,
    val accentColor: String? = null,
    val backdropEnabled: Boolean = true,
    val ctas: List<InAppCta> = emptyList(),
    val autoDismissSeconds: Double? = null,
    val screenAllowlist: List<String> = emptyList(),
    val screenDenylist: List<String> = emptyList(),
    val forceShow: Boolean = false,
)

@Serializable
internal data class ArmedInAppMessagesResponse(
    val messages: List<ArmedInAppMessage> = emptyList(),
    val serverTime: String? = null,
    val nextSyncMs: Long? = null,
)

/** Visual presentation requested by the server-authored message. */
@Serializable
enum class InAppMessageFormat {
    @SerialName("modal") MODAL,
    @SerialName("modal_full") MODAL_FULL,
    @SerialName("slideup") SLIDEUP,
}

/** Action attached to an in-app message button. */
@Serializable
data class InAppCta(
    val label: String,
    val action: InAppCtaAction,
    val target: String? = null,
    val actionJson: JsonObject? = null,
)

@Serializable
enum class InAppCtaAction {
    @SerialName("open_url") OPEN_URL,
    @SerialName("deep_link") DEEP_LINK,
    @SerialName("dismiss") DISMISS,
    @SerialName("custom_event") CUSTOM_EVENT,
    @SerialName("json") JSON,
}

enum class InAppDismissReason { USER, AUTO }

/** Details delivered to [InAppHandlers.onCtaClick]. */
data class InAppCtaClick(
    val messageId: String,
    val action: InAppCtaAction,
    val target: String?,
    val label: String,
    val index: Int,
    val actionJson: JsonObject? = null,
)

/** Optional lifecycle callbacks for SDK-rendered in-app messages. */
data class InAppHandlers(
    val onShow: ((messageId: String) -> Unit)? = null,
    val onDismiss: ((messageId: String, reason: InAppDismissReason) -> Unit)? = null,
    val onCtaClick: ((InAppCtaClick) -> Unit)? = null,
    val onJsonAction: ((JsonObject, InAppCtaClick) -> Unit)? = null,
)
