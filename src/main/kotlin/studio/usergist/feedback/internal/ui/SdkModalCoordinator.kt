package studio.usergist.feedback.internal.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide FIFO for every UserGist-owned modal surface. */
internal object SdkModalCoordinator {
    private data class Pending(
        val owner: Any,
        val start: ((() -> Unit)) -> Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Pending>()
    private var active = false
    private var activeOwner: Any? = null
    private var activeRelease: (() -> Unit)? = null
    private var activeToken: Any? = null

    @Synchronized
    fun enqueue(owner: Any, start: ((() -> Unit)) -> Boolean): Boolean {
        if (pending.size >= MAX_PENDING) return false
        pending.addLast(Pending(owner, start))
        mainHandler.post(::drain)
        return true
    }

    @Synchronized
    fun cancelPending(owner: Any) {
        pending.removeAll { it.owner === owner }
    }

    fun cancelActive(owner: Any) {
        mainHandler.post {
            val release = synchronized(this) {
                activeRelease.takeIf { activeOwner === owner }
            }
            release?.invoke()
        }
    }

    fun retryPending() {
        mainHandler.post(::drain)
    }

    @Synchronized
    private fun drain() {
        if (active || pending.isEmpty()) return
        val item = pending.removeFirst()
        active = true
        val token = Any()
        val released = AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                mainHandler.post {
                    synchronized(this) {
                        if (activeToken === token) {
                            active = false
                            activeOwner = null
                            activeRelease = null
                            activeToken = null
                        }
                    }
                    drain()
                }
            }
        }
        activeOwner = item.owner
        activeRelease = release
        activeToken = token
        val started = runCatching { item.start(release) }.getOrDefault(false)
        if (!started) {
            active = false
            activeOwner = null
            activeRelease = null
            activeToken = null
            pending.addFirst(item)
        }
    }

    private const val MAX_PENDING = 100
}
