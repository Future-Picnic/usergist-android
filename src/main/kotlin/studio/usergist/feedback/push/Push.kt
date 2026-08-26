package studio.usergist.feedback.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import studio.usergist.feedback.UserGist

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
 * A parsed UserGist push payload. Host apps extract this from an incoming
 * FCM `RemoteMessage` via [parseFcmData] and forward to [UserGist.push].
 */
data class UserGistPushMessage(
    val campaignId: String?,
    val variantId: String?,
    val deliveryId: String?,
    val language: String?,
    val deepLink: String?,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val androidChannelId: String?,
    val actions: String?,
) {
    companion object {
        /** Parses a UserGist envelope from an FCM data map. */
        fun parse(data: Map<String, String>, title: String? = null, body: String? = null): UserGistPushMessage? {
            val campaignId = data["usergist_campaign_id"] ?: return null
            return UserGistPushMessage(
                campaignId = campaignId,
                variantId = data["usergist_variant_id"],
                deliveryId = data["usergist_delivery_id"],
                language = data["usergist_language"],
                deepLink = data["usergist_deep_link"],
                actions = data["usergist_actions"],
                title = title ?: data["usergist_title"],
                body = body ?: data["usergist_body"],
                imageUrl = data["usergist_image_url"],
                androidChannelId = data["usergist_channel_id"],
            )
        }
    }
}

/**
 * Host-app callback surface.
 */
data class PushHandlers(
    val onReceive: ((UserGistPushMessage, Map<String, String>) -> Unit)? = null,
    val onOpen: ((UserGistPushMessage) -> Unit)? = null,
    val onAction: ((UserGistPushMessage, String) -> Unit)? = null,
    val onDismiss: ((UserGistPushMessage) -> Unit)? = null,
    val onSilent: ((String) -> Unit)? = null,
    val onEvent: ((String, Map<String, Any?>) -> Unit)? = null,
)

/**
 * UserGist channel registry data class. Mirrors `push_channels` on the
 * server. Host apps fetch this and pass to [Push.registerChannels] at
 * app launch (Android 8+ requires every notification to belong to an
 * already-registered channel; otherwise the notification is silently
 * dropped by the OS).
 */
@Serializable
data class UserGistPushChannel(
    @SerialName("channel_id") val id: String,
    @SerialName("display_name") val displayName: String,
    val description: String?,
    val importance: Int,            // 0..4 mapping to NotificationManager.IMPORTANCE_*
    @SerialName("default_sound") val defaultSound: String?,
    @SerialName("default_vibrate") val defaultVibrate: Boolean,
    @SerialName("default_badge") val defaultBadge: Boolean,
    val category: String,
)

/**
 * Public push surface. Host apps call:
 *   - [didReceiveFcmToken] when [com.google.firebase.messaging.FirebaseMessagingService.onNewToken] fires
 *   - [handleReceived] from onMessageReceived for foreground notifications
 *   - [handleOpened] from the launch Intent when the user taps a notification
 *   - [handleSilentIfPresent] FIRST in onMessageReceived to early-exit on silent pings
 *   - [registerChannels] at app start to ensure channels exist before any post
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
        UserGist.registerPushToken(token)
    }

    /** Invalidates a token when notifications are disabled or rotated. */
    fun invalidateDeviceToken(token: String) {
        if (token.isBlank()) return
        UserGist.invalidatePushToken(token)
        if (lastRegisteredToken == token) lastRegisteredToken = null
    }

    /** Latest token registered with the server, or null if none yet. */
    fun lastToken(): String? = lastRegisteredToken

    internal fun reset() {
        lastRegisteredToken = null
    }

    internal fun emitSdkEvent(name: String, properties: Map<String, Any?>) {
        handlers.onEvent?.invoke(name, properties)
    }

    /**
     * Called by the SDK's identify() flow so subsequent campaign sends to
     * the now-identified user reach this device. Host apps don't normally
     * need to invoke this directly.
     */
    fun rebind(externalId: String) {
        val token = lastRegisteredToken ?: return
        UserGist.rebindPushToken(externalId, token)
    }

    /**
     * Forward applicationDidBecomeActive (or your activity's onResume)
     * so the server's adaptive reachability worker knows this device is
     * alive — skips the next silent-ping cycle.
     */
    fun appDidBecomeActive() {
        if (lastRegisteredToken == null) return
        UserGist.reportPushAppOpen()
    }

    /**
     * Detect and ack a silent reachability ping. Returns true if the
     * data map was a silent ping (host SHOULD NOT show any notification).
     * Call FIRST inside onMessageReceived so the data-only display branch
     * doesn't render a blank notification.
     */
    fun handleSilentIfPresent(data: Map<String, String>): Boolean {
        if (data["usergist_silent"] != "1") return false
        val pingId = data["usergist_ping_id"] ?: return true
        handlers.onSilent?.invoke(pingId)
        UserGist.ackSilentPush(pingId)
        return true
    }

    /** Beacon: NSE/SDK observed delivery. Idempotent server-side. */
    fun beaconDelivered(deliveryId: String) {
        if (deliveryId.isBlank()) return
        UserGist.pushBeacon("delivered", deliveryId)
    }

    /** Beacon: SDK rendered a local notification. */
    fun beaconDisplayed(deliveryId: String) {
        if (deliveryId.isBlank()) return
        UserGist.pushBeacon("displayed", deliveryId)
    }

    /** Beacon: user dismissed the notification without opening. */
    fun beaconDismissed(deliveryId: String) {
        if (deliveryId.isBlank()) return
        UserGist.pushBeacon("dismissed", deliveryId)
    }

    /** Fetches the channel registry used to create local OS channels. */
    fun fetchChannels(callback: (List<UserGistPushChannel>) -> Unit) {
        UserGist.fetchPushChannels(callback)
    }

    /** Updates the current user's server-side channel preference. */
    fun setChannelSubscription(channelId: String, subscribed: Boolean) {
        UserGist.setPushChannelSubscription(channelId, subscribed)
    }

    /**
     * Register the UserGist channel registry on the device. Idempotent —
     * existing channels are updated; new channels created. Pre-Android 8
     * this is a no-op (channels only exist on API 26+).
     */
    fun registerChannels(context: Context, channels: List<UserGistPushChannel>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        for (def in channels) {
            val osImportance = when (def.importance) {
                0 -> NotificationManager.IMPORTANCE_NONE
                1 -> NotificationManager.IMPORTANCE_MIN
                2 -> NotificationManager.IMPORTANCE_LOW
                3 -> NotificationManager.IMPORTANCE_DEFAULT
                4 -> NotificationManager.IMPORTANCE_HIGH
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val channel = NotificationChannel(def.id, def.displayName, osImportance).apply {
                description = def.description
                enableVibration(def.defaultVibrate)
                setShowBadge(def.defaultBadge)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Create the SDK's default channel set. Useful as a fallback when the
     * server-side registry hasn't been populated yet so notifications
     * never silently drop on Android 8+.
     */
    fun registerDefaultChannels(context: Context) {
        registerChannels(
            context,
            listOf(
                UserGistPushChannel(
                    id = "usergist_transactional",
                    displayName = "Transactional",
                    description = "Account, security, and order updates.",
                    importance = 4,
                    defaultSound = null,
                    defaultVibrate = true,
                    defaultBadge = true,
                    category = "transactional",
                ),
                UserGistPushChannel(
                    id = "usergist_marketing",
                    displayName = "News & offers",
                    description = "Product news and promotions.",
                    importance = 3,
                    defaultSound = null,
                    defaultVibrate = false,
                    defaultBadge = true,
                    category = "marketing",
                ),
                UserGistPushChannel(
                    id = "usergist_silent",
                    displayName = "Background sync",
                    description = "Silent updates that keep your data fresh.",
                    importance = 2,
                    defaultSound = null,
                    defaultVibrate = false,
                    defaultBadge = false,
                    category = "silent",
                ),
            ),
        )
    }

    /** Call from onMessageReceived when the app is in the foreground. */
    fun handleReceived(data: Map<String, String>, title: String? = null, body: String? = null) {
        val msg = UserGistPushMessage.parse(data, title, body) ?: return
        UserGist.trackPushEvent("\$push_received", msg, actionButton = null)
        emitSdkEvent("\$push_received", eventProperties(msg))
        handlers.onReceive?.invoke(msg, data)
    }

    /**
     * Call from onMessageReceived after rendering a local notification.
     * Fires both the analytics event and the server beacon so the
     * server-side `displayed_at` is populated.
     */
    fun handleDisplayed(data: Map<String, String>) {
        val msg = UserGistPushMessage.parse(data) ?: return
        UserGist.trackPushEvent("\$push_displayed", msg, actionButton = null)
        emitSdkEvent("\$push_displayed", eventProperties(msg))
        msg.deliveryId?.let { UserGist.pushBeacon("displayed", it) }
    }

    /** Call from the Activity that handles notification taps. */
    fun handleOpened(data: Map<String, String>, actionButton: String? = null) {
        val msg = UserGistPushMessage.parse(data) ?: return
        if (!actionButton.isNullOrEmpty()) {
            UserGist.trackPushEvent("\$push_action_clicked", msg, actionButton = actionButton)
            emitSdkEvent(
                "\$push_action_clicked",
                eventProperties(msg) + ("action_button" to actionButton),
            )
            handlers.onAction?.invoke(msg, actionButton)
        } else {
            UserGist.trackPushEvent("\$push_opened", msg, actionButton = null)
            emitSdkEvent("\$push_opened", eventProperties(msg))
            handlers.onOpen?.invoke(msg)
        }
    }

    /** Call from the dismiss receiver for a posted notification. */
    fun handleDismissed(data: Map<String, String>) {
        val msg = UserGistPushMessage.parse(data) ?: return
        UserGist.trackPushEvent("\$push_dismissed", msg, actionButton = null)
        emitSdkEvent("\$push_dismissed", eventProperties(msg))
        msg.deliveryId?.let { UserGist.pushBeacon("dismissed", it) }
        handlers.onDismiss?.invoke(msg)
    }

    private fun eventProperties(message: UserGistPushMessage): Map<String, Any?> = mapOf(
        "campaign_id" to message.campaignId,
        "variant_id" to message.variantId,
        "delivery_id" to message.deliveryId,
        "language" to message.language,
    )
}
