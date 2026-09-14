package studio.usergist.feedback.internal.ui

import java.util.concurrent.atomic.AtomicBoolean

internal class ModalPresentationQueue(private val dispatch: (() -> Unit) -> Unit) {
    private data class Pending(
        val owner: Any,
        val isValid: () -> Boolean,
        val start: ((() -> Unit)) -> Boolean,
    )

    private val pending = ArrayDeque<Pending>()
    private var paused = false
    private var active = false
    private var activeOwner: Any? = null
    private var activeRelease: (() -> Unit)? = null
    private var activeToken: Any? = null

    @Synchronized
    fun enqueue(owner: Any, isValid: () -> Boolean = { true }, start: ((() -> Unit)) -> Boolean): Boolean {
        pending.removeAll { !it.isValid() }
        if (pending.size >= MAX_PENDING) return false
        pending.addLast(Pending(owner, isValid, start))
        dispatch(::drain)
        return true
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        paused = value
        if (!value) dispatch(::drain)
    }

    @Synchronized
    fun cancelPending(owner: Any) {
        pending.removeAll { it.owner === owner }
    }

    fun cancelActive(owner: Any) {
        dispatch {
            val release = synchronized(this) {
                activeRelease.takeIf { activeOwner === owner }
            }
            release?.invoke()
        }
    }

    fun retryPending() {
        dispatch(::drain)
    }

    @Synchronized
    private fun drain() {
        pending.removeAll { !it.isValid() }
        if (paused || active || pending.isEmpty()) return
        val item = pending.removeFirst()
        active = true
        val token = Any()
        val released = AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                dispatch {
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

    private val MAX_PENDING = 100
}
