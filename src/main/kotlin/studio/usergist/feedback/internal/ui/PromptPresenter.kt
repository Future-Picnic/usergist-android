package studio.usergist.feedback.internal.ui

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import studio.usergist.feedback.api.PromptResponseInfo
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.lifecycle.AppLifecycleObserver
import studio.usergist.feedback.internal.model.ClientPrompt
import java.lang.ref.WeakReference

/**
 * Bridge between the trigger engine and the Android UI. Given a
 * [ClientPrompt], it finds the currently-resumed [FragmentActivity]
 * (via [AppLifecycleObserver]) and shows a [PromptSheetFragment].
 *
 * If there's no suitable host activity, the prompt is silently skipped
 * and a debug log is emitted. This is intentional: the SDK must never
 * crash the host app.
 */
internal class PromptPresenter(
    private val lifecycleObserver: AppLifecycleObserver,
) {
    @Volatile
    var onPromptShown: ((String) -> Unit)? = null

    @Volatile
    var onResponse: ((PromptResponseInfo) -> Unit)? = null

    @Volatile
    private var themeOverrides: PromptTheme? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeFragment: WeakReference<PromptSheetFragment>? = null

    fun setThemeOverrides(theme: PromptTheme?) {
        themeOverrides = theme
    }

    /** Queues [prompt] for the next safe host-activity presentation slot. */
    fun present(prompt: ClientPrompt, isValid: () -> Boolean = CampaignPresentationEligibility.validator(CampaignPresentationEligibility.Purpose.FEEDBACK)): Boolean {
        val accepted = SdkModalCoordinator.enqueue(this, isValid) { release ->
            start(prompt, release)
        }
        if (!accepted) {
            UserGistLogger.w("PromptPresenter queue full; dropping prompt ${prompt.id}")
        }
        return accepted
    }

    fun retryPending() {
        SdkModalCoordinator.retryPending()
    }

    fun clear() {
        SdkModalCoordinator.cancelPending(this)
        mainHandler.post {
            activeFragment?.get()?.dismissAllowingStateLoss()
            activeFragment = null
            SdkModalCoordinator.cancelActive(this)
        }
    }

    private fun start(prompt: ClientPrompt, release: () -> Unit): Boolean {
        val activity = lifecycleObserver.topActivity() as? FragmentActivity
        if (activity == null) {
            UserGistLogger.d("PromptPresenter: no resumed FragmentActivity; retaining prompt")
            return false
        }
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved || fm.isDestroyed) {
            UserGistLogger.d("PromptPresenter: fragment manager not ready; retaining prompt")
            return false
        }
        if (fm.findFragmentByTag(PROMPT_FRAGMENT_TAG) != null ||
            fm.findFragmentByTag(INAPP_FRAGMENT_TAG) != null
        ) {
            return false
        }
        return try {
            PromptSheetFragment.shownCallback = onPromptShown
            PromptSheetFragment.responseCallback = { response ->
                onResponse?.invoke(response)
                activeFragment = null
                release()
            }
            val fragment = PromptSheetFragment.newInstance(prompt, themeOverrides)
            activeFragment = WeakReference(fragment)
            fragment.show(fm, PROMPT_FRAGMENT_TAG)
            true
        } catch (e: Throwable) {
            UserGistLogger.w("PromptPresenter.present failed", e)
            false
        }
    }

    companion object {
        private const val PROMPT_FRAGMENT_TAG: String = "studio.usergist.feedback.prompt"
        private const val INAPP_FRAGMENT_TAG = "studio.usergist.feedback.inapp"
    }
}
