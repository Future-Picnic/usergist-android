package studio.usergist.feedback.internal.ui

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.util.Collections
import java.util.WeakHashMap

/** One process-wide main-thread queue for every UserGist campaign surface. */
internal object SdkModalCoordinator {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ModalPresentationQueue { handler.post(it) }
    private val observedManagers = Collections.newSetFromMap(WeakHashMap<FragmentManager, Boolean>())
    /** A response callback may release before the fragment removal commits. */
    fun observeDismissals(manager: FragmentManager) {
        if (!observedManagers.add(manager)) return
        manager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentDetached(fm: FragmentManager, fragment: Fragment) {
                if (fragment.javaClass.name.startsWith("studio.usergist.feedback.")) retryPending()
            }
        }, false)
    }
    fun enqueue(owner: Any, isValid: () -> Boolean = { true }, start: ((() -> Unit)) -> Boolean): Boolean =
        queue.enqueue(owner, isValid, start)
    fun setPaused(value: Boolean) = queue.setPaused(value)
    fun cancelPending(owner: Any) = queue.cancelPending(owner)
    fun cancelActive(owner: Any) = queue.cancelActive(owner)
    fun retryPending() = queue.retryPending()
}
