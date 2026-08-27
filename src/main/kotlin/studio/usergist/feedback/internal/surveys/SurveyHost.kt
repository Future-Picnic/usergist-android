package studio.usergist.feedback.internal.surveys

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.JsonElement
import studio.usergist.feedback.internal.ui.SdkModalCoordinator
import studio.usergist.feedback.internal.ui.ResolvedTheme
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class SurveyPresentation(
    val survey: SdkSurveyCampaignWithFlow,
    val attempt: SdkSurveyAttemptSession,
    val theme: ResolvedTheme,
    val onShown: () -> Unit,
    val onProgress: (String, String?, Map<String, JsonElement>) -> Unit,
    val onComplete: (
        String,
        Map<String, JsonElement>,
        (Boolean) -> Unit,
    ) -> Unit,
    val onAbandon: (String, (Boolean) -> Unit) -> Unit,
)

/** Process-local handoff from the runtime to the private survey Activity. */
internal object SurveyHost {
    private const val EXTRA_TOKEN = "studio.usergist.feedback.survey.token"
    private val pending = ConcurrentHashMap<String, SurveyPresentation>()
    private val releases = ConcurrentHashMap<String, () -> Unit>()
    private val activities = ConcurrentHashMap<String, WeakReference<SurveyActivity>>()
    private val shown = HashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun present(context: Context, presentation: SurveyPresentation): Boolean {
        val contextRef = WeakReference(context.applicationContext)
        return SdkModalCoordinator.enqueue(this) { release ->
            val launchContext = contextRef.get() ?: return@enqueue false
            val token = UUID.randomUUID().toString()
            pending[token] = presentation
            releases[token] = release
            val intent = Intent(launchContext, SurveyActivity::class.java).apply {
                putExtra(EXTRA_TOKEN, token)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching {
                launchContext.startActivity(intent)
                true
            }.getOrDefault(false)
            if (!started) {
                pending.remove(token)
                releases.remove(token)
            }
            started
        }
    }

    fun take(intent: Intent): HostedSurvey? {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return null
        val presentation = pending[token] ?: return null
        return HostedSurvey(token, presentation)
    }

    fun attach(token: String, activity: SurveyActivity) {
        activities[token] = WeakReference(activity)
    }

    @Synchronized
    fun markShown(token: String): Boolean = shown.add(token)

    @Synchronized
    fun release(token: String) {
        pending.remove(token)
        activities.remove(token)
        shown.remove(token)
        releases.remove(token)?.invoke()
    }

    fun clearWaiting() {
        SdkModalCoordinator.cancelPending(this)
        mainHandler.post {
            val activeTokens = releases.keys.toList()
            activeTokens.forEach { token -> activities[token]?.get()?.finish() }
            activeTokens.forEach(::release)
            SdkModalCoordinator.cancelActive(this)
        }
    }

    internal data class HostedSurvey(
        val token: String,
        val presentation: SurveyPresentation,
    )
}
