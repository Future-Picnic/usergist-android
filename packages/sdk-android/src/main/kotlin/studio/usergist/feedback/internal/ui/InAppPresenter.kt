package studio.usergist.feedback.internal.ui

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import studio.usergist.feedback.api.ArmedInAppMessage
import studio.usergist.feedback.api.InAppCta
import studio.usergist.feedback.api.InAppDismissReason
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.lifecycle.AppLifecycleObserver
import java.lang.ref.WeakReference

/** Routes authenticated in-app instructions to the current host activity. */
internal class InAppPresenter(
    private val lifecycleObserver: AppLifecycleObserver,
) {
    @Volatile
    var onShown: ((String) -> Unit)? = null

    @Volatile
    var onDismissed: ((String, InAppDismissReason) -> Unit)? = null

    @Volatile
    var onCta: ((String, InAppCta, Int) -> Unit)? = null

    @Volatile
    private var themeOverrides: PromptTheme? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeFragment: WeakReference<InAppMessageFragment>? = null

    fun setThemeOverrides(theme: PromptTheme?) {
        themeOverrides = theme
    }

    fun present(message: ArmedInAppMessage): Boolean {
        val accepted = SdkModalCoordinator.enqueue(this) { release ->
            start(message, release)
        }
        if (!accepted) {
            UserGistLogger.w("InAppPresenter queue full; dropping ${message.messageId}")
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

    private fun start(message: ArmedInAppMessage, release: () -> Unit): Boolean {
        val activity = lifecycleObserver.topActivity() as? FragmentActivity
        if (activity == null) {
            UserGistLogger.d("InAppPresenter: no resumed FragmentActivity")
            return false
        }
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved || fm.isDestroyed) return false
        if (fm.findFragmentByTag(INAPP_FRAGMENT_TAG) != null ||
            fm.findFragmentByTag(PROMPT_FRAGMENT_TAG) != null
        ) {
            UserGistLogger.d("InAppPresenter: another UserGist modal is active")
            return false
        }
        return try {
            InAppMessageFragment.shownCallback = onShown
            InAppMessageFragment.dismissedCallback = { id, reason ->
                onDismissed?.invoke(id, reason)
                activeFragment = null
                release()
            }
            InAppMessageFragment.ctaCallback = { id, cta, index ->
                onCta?.invoke(id, cta, index)
                activeFragment = null
                release()
            }
            val fragment = InAppMessageFragment.newInstance(message, themeOverrides)
            activeFragment = WeakReference(fragment)
            fragment.show(fm, INAPP_FRAGMENT_TAG)
            true
        } catch (error: Throwable) {
            UserGistLogger.w("InAppPresenter.present failed", error)
            false
        }
    }

    companion object {
        private const val INAPP_FRAGMENT_TAG = "studio.usergist.feedback.inapp"
        private const val PROMPT_FRAGMENT_TAG = "studio.usergist.feedback.prompt"
    }
}
