package studio.ritmus.feedback.push

import studio.ritmus.feedback.Ritmus

/**
 * Push permission status, mirroring iOS. On Android, pre-API-33 devices
 * always report GRANTED (notifications are default-on).
 */
enum class PushPermissionStatus {
    NOT_DETERMINED,
    DENIED,
    GRANTED,
}

/**
 * A parsed Ritmus push payload. Host apps extract this from an incoming
 * FCM `RemoteMessage` via [parseFcmData] and forward to [Ritmus.push].
 */
data class RitmusPushMessage(
    val campaignId: String?,
    val variantId: String?,
    val deliveryId: String?,
    val language: String?,
    val deepLink: String?,
    val title: String?,
    val body: String?,
    val actions: String?,
) {
    companion object {
        /** Parses a Ritmus envelope from an FCM data map. */
        fun parse(data: Map<String, String>, title: String? = null, body: String? = null): RitmusPushMessage? {
            val campaignId = data["ritmus_campaign_id"] ?: return null
            return RitmusPushMessage(
                campaignId = campaignId,
                variantId = data["ritmus_variant_id"],
                deliveryId = data["ritmus_delivery_id"],
                language = data["ritmus_language"],
                deepLink = data["ritmus_deep_link"],
                actions = data["ritmus_actions"],
                title = title,
                body = body,
            )
        }
    }
}

/**
 * Host-app callback surface.
 */
data class PushHandlers(
    val onReceive: ((RitmusPushMessage, Map<String, String>) -> Unit)? = null,
    val onOpen: ((RitmusPushMessage) -> Unit)? = null,
    val onAction: ((RitmusPushMessage, String) -> Unit)? = null,
)

/**
 * Public push surface. Host apps call:
 *   - [didReceiveFcmToken] when [com.google.firebase.messaging.FirebaseMessagingService.onNewToken] fires
 *   - [handleReceived] from onMessageReceived for foreground notifications
 *   - [handleOpened] from the launch Intent when the user taps a notification
 *
 * This SDK does not subclass FirebaseMessagingService — it plays well with
 * the host app's existing service.
 */
object Push {

    @Volatile
    private var handlers: PushHandlers = PushHandlers()

    @Volatile
    private var lastRegisteredToken: String? = null

    fun setHandlers(h: PushHandlers) {
        handlers = h
    }

    /** Call from FirebaseMessagingService.onNewToken. */
    fun didReceiveFcmToken(token: String) {
        if (token.isBlank()) return
        if (token == lastRegisteredToken) return
        lastRegisteredToken = token
        Ritmus.registerPushToken(token)
    }

    /** Call from onMessageReceived when the app is in the foreground. */
    fun handleReceived(data: Map<String, String>, title: String? = null, body: String? = null) {
        val msg = RitmusPushMessage.parse(data, title, body) ?: return
        Ritmus.trackPushEvent("\$push_received", msg, actionButton = null)
        handlers.onReceive?.invoke(msg, data)
    }

    /** Call from the Activity that handles notification taps. */
    fun handleOpened(data: Map<String, String>, actionButton: String? = null) {
        val msg = RitmusPushMessage.parse(data) ?: return
        if (!actionButton.isNullOrEmpty()) {
            Ritmus.trackPushEvent("\$push_action_clicked", msg, actionButton = actionButton)
            handlers.onAction?.invoke(msg, actionButton)
        } else {
            Ritmus.trackPushEvent("\$push_opened", msg, actionButton = null)
            handlers.onOpen?.invoke(msg)
        }
    }
}
