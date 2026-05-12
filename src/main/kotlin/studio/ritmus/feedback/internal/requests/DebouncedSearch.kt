package studio.ritmus.feedback.internal.requests

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// PORTED FROM: packages/sdk-react-native/src/internal/requests.ts
//                (createDebouncedSearch — 300ms typeahead helper)
//
// Coalesces rapid query() calls so only the latest survives the debounce
// window. A monotonically increasing sequence number drops in-flight
// requests whose result returns after a newer query was issued — typeahead
// never flickers a stale result.

internal class DebouncedSearch<TResult>(
    private val delayMs: Long = 300L,
    private val fn: (String, (Result<TResult>) -> Unit) -> Unit,
) {

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ritmus-debounce-search").apply { isDaemon = true }
        }
    private var pending: ScheduledFuture<*>? = null
    private val lastSeq = AtomicLong(0L)
    private val listeners: MutableMap<UUID, (String, TResult) -> Unit> = HashMap()
    private val lock = Any()

    fun query(q: String) {
        synchronized(lock) {
            pending?.cancel(false)
            val seq = lastSeq.incrementAndGet()
            pending = scheduler.schedule(
                {
                    fn(q) { result ->
                        // Drop stale results: another query has fired since.
                        if (seq != lastSeq.get()) return@fn
                        val snapshot = synchronized(lock) { listeners.values.toList() }
                        result.fold(
                            onSuccess = { r ->
                                for (cb in snapshot) cb(q, r)
                            },
                            onFailure = {
                                // Swallow — RN reference renders nothing on error
                                // and lets the user "post anyway".
                            },
                        )
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun subscribe(cb: (String, TResult) -> Unit): () -> Unit {
        val token = UUID.randomUUID()
        synchronized(lock) { listeners[token] = cb }
        return { synchronized(lock) { listeners.remove(token) } }
    }

    fun cancel() {
        synchronized(lock) {
            pending?.cancel(false)
            pending = null
            lastSeq.incrementAndGet()
        }
    }
}
