package studio.usergist.feedback.internal.ui

import android.os.Handler
import android.os.Looper

/** One process-wide main-thread queue for every UserGist campaign surface. */
internal object SdkModalCoordinator {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ModalPresentationQueue { handler.post(it) }
    fun enqueue(owner: Any, isValid: () -> Boolean = { true }, start: ((() -> Unit)) -> Boolean): Boolean =
        queue.enqueue(owner, isValid, start)
    fun setPaused(value: Boolean) = queue.setPaused(value)
    fun cancelPending(owner: Any) = queue.cancelPending(owner)
    fun cancelActive(owner: Any) = queue.cancelActive(owner)
    fun retryPending() = queue.retryPending()
}
