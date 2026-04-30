package studio.ritmus.feedback.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
    val imageUrl: String?,
    val androidChannelId: String?,
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
                title = title ?: data["ritmus_title"],
                body = body ?: data["ritmus_body"],
                imageUrl = data["ritmus_image_url"],
                androidChannelId = data["ritmus_channel_id"],
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
    val onDismiss: ((RitmusPushMessage) -> Unit)? = null,
    val onSilent: ((String) -> Unit)? = null,
)

/**
 * Ritmus channel registry data class. Mirrors `push_channels` on the
 * server. Host apps fetch this and pass to [Push.registerChannels] at
 * app launch (Android 8+ requires every notification to belong to an
 * already-registered channel; otherwise the notification is silently
 * dropped by the OS).
 */
data class RitmusPushChannel(
    val id: String,
    val displayName: String,
    val description: String?,
    val importance: Int,            // 0..4 mapping to NotificationManager.IMPORTANCE_*
    val defaultSound: String?,
    val defaultVibrate: Boolean,
    val defaultBadge: Boolean,
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
        Ritmus.registerPushToken(token)
    }

    /** Latest token registered with the server, or null if none yet. */
    fun lastToken(): String? = lastRegisteredToken

    /**
     * Called by the SDK's identify() flow so subsequent campaign sends to
     * the now-identified user reach this device. Host apps don't normally
     * need to invoke this directly.
     */
    fun rebind(externalId: String) {
        val token = lastRegisteredToken ?: return
        Ritmus.rebindPushToken(externalId, token)
    }

    /**
     * Forward applicationDidBecomeActive (or your activity's onResume)
     * so the server's adaptive reachability worker knows this device is
     * alive — skips the next silent-ping cycle.
     */
    fun appDidBecomeActive() {
        Ritmus.reportPushAppOpen()
    }

    /**
     * Detect and ack a silent reachability ping. Returns true if the
     * data map was a silent ping (host SHOULD NOT show any notification).
     * Call FIRST inside onMessageReceived so the data-only display branch
     * doesn't render a blank notification.
     */
    fun handleSilentIfPresent(data: Map<String, String>): Boolean {
        if (data["ritmus_silent"] != "1") return false
        val pingId = data["ritmus_ping_id"] ?: return true
        handlers.onSilent?.invoke(pingId)
        Ritmus.ackSilentPush(pingId)
        return true
    }

    /** Beacon: NSE/SDK observed delivery. Idempotent server-side. */
    fun beaconDelivered(deliveryId: String) {
        if (deliveryId.isBlank()) return
        Ritmus.pushBeacon("delivered", deliveryId)
    }

    /** Beacon: SDK rendered a local notification. */
    fun beaconDisplayed(deliveryId: String) {
        if (deliveryId.isBlank()) return
        Ritmus.pushBeacon("displayed", deliveryId)
    }

    /** Beacon: user dismissed the notification without opening. */
    fun beaconDismissed(deliveryId: String) {
        if (deliveryId.isBlank()) return
        Ritmus.pushBeacon("dismissed", deliveryId)
    }

    /**
     * Register the Ritmus channel registry on the device. Idempotent —
     * existing channels are updated; new channels created. Pre-Android 8
     * this is a no-op (channels only exist on API 26+).
     */
    fun registerChannels(context: Context, channels: List<RitmusPushChannel>) {
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
                RitmusPushChannel(
                    id = "ritmus_transactional",
                    displayName = "Transactional",
                    description = "Account, security, and order updates.",
                    importance = 4,
                    defaultSound = null,
                    defaultVibrate = true,
                    defaultBadge = true,
                    category = "transactional",
                ),
                RitmusPushChannel(
                    id = "ritmus_marketing",
                    displayName = "News & offers",
                    description = "Product news and promotions.",
                    importance = 3,
                    defaultSound = null,
                    defaultVibrate = false,
                    defaultBadge = true,
                    category = "marketing",
                ),
                RitmusPushChannel(
                    id = "ritmus_silent",
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
        val msg = RitmusPushMessage.parse(data, title, body) ?: return
        Ritmus.trackPushEvent("\$push_received", msg, actionButton = null)
        handlers.onReceive?.invoke(msg, data)
    }

    /**
     * Call from onMessageReceived after rendering a local notification.
     * Fires both the analytics event and the server beacon so the
     * server-side `displayed_at` is populated.
     */
    fun handleDisplayed(data: Map<String, String>) {
        val msg = RitmusPushMessage.parse(data) ?: return
        Ritmus.trackPushEvent("\$push_displayed", msg, actionButton = null)
        msg.deliveryId?.let { Ritmus.pushBeacon("displayed", it) }
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

    /** Call from the dismiss receiver for a posted notification. */
    fun handleDismissed(data: Map<String, String>) {
        val msg = RitmusPushMessage.parse(data) ?: return
        Ritmus.trackPushEvent("\$push_dismissed", msg, actionButton = null)
        msg.deliveryId?.let { Ritmus.pushBeacon("dismissed", it) }
        handlers.onDismiss?.invoke(msg)
    }
}
