package studio.usergist.feedback.internal.ui

import androidx.fragment.app.FragmentActivity
import studio.usergist.feedback.api.PromptResponseInfo
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.lifecycle.AppLifecycleObserver
import studio.usergist.feedback.internal.model.ClientPrompt

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

    fun setThemeOverrides(theme: PromptTheme?) {
        themeOverrides = theme
    }

    /** Attempts to present [prompt]. Returns `true` iff the sheet was shown. */
    fun present(prompt: ClientPrompt): Boolean {
        val activity = lifecycleObserver.topActivity() as? FragmentActivity
        if (activity == null) {
            UserGistLogger.d("PromptPresenter: no resumed FragmentActivity; skipping prompt ${prompt.id}")
            return false
        }
        val fm = activity.supportFragmentManager
        if (fm.isStateSaved || fm.isDestroyed) {
            UserGistLogger.d("PromptPresenter: fragment manager not ready; skipping prompt ${prompt.id}")
            return false
        }
        return try {
            PromptSheetFragment.shownCallback = onPromptShown
            PromptSheetFragment.responseCallback = onResponse
            val fragment = PromptSheetFragment.newInstance(prompt, themeOverrides)
            fragment.show(fm, PROMPT_FRAGMENT_TAG)
            true
        } catch (e: Throwable) {
            UserGistLogger.w("PromptPresenter.present failed", e)
            false
        }
    }

    companion object {
        private const val PROMPT_FRAGMENT_TAG: String = "studio.usergist.feedback.prompt"
    }
}
