package studio.ritmus.feedback.internal

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.ritmus.feedback.internal.model.IngestEvent
import java.io.File

/**
 * Bounded, persistent FIFO queue of events.
 *
 * Format: JSON-lines (one serialized [IngestEvent] per line). Append is
 * O(1) — we append to the end of the file. Draining loads the file,
 * slices off the drained prefix, and rewrites the remainder atomically.
 *
 * Overflow policy: drop-oldest. When [maxQueueSize] is exceeded, we
 * rewrite the tail so newer events survive. This matches the iOS SDK
 * and matches DEV_PRD §6 ("bounded persistent FIFO").
 */
internal class EventQueue(
    private val storage: Storage,
    private val json: Json,
    private val maxQueueSize: Int,
) {

    private val lock = Any()

    /** Current in-memory size (recomputed lazily from disk on first access). */
    @Volatile
    private var cachedSize: Int = -1

    /** Appends [event] to disk. Applies overflow policy if needed. */
    fun enqueue(event: IngestEvent): Boolean {
        val line = try {
            json.encodeToString(event)
        } catch (e: Throwable) {
            RitmusLogger.w("EventQueue.enqueue encode failed for ${event.name}", e)
            return false
        }
        synchronized(lock) {
            val file = storage.eventsFile
            try {
                ensureParent(file)
                // Ensure we have a baseline count before the append.
                val previousSize = sizeLocked()
                file.appendText(line + "\n", Charsets.UTF_8)
                cachedSize = previousSize + 1
                if (cachedSize > maxQueueSize) {
                    dropOldestLocked(cachedSize - maxQueueSize)
                }
                return true
            } catch (e: Throwable) {
                RitmusLogger.w("EventQueue.enqueue failed", e)
                return false
            }
        }
    }

    /** Number of events currently persisted. */
    fun size(): Int {
        synchronized(lock) { return sizeLocked() }
    }

    /**
     * Peeks up to [batchSize] events from the head of the queue without
     * mutating on-disk state. Returns an empty list when the queue is empty.
     */
    fun peek(batchSize: Int): List<IngestEvent> {
        if (batchSize <= 0) return emptyList()
        synchronized(lock) {
            val file = storage.eventsFile
            if (!file.exists() || file.length() == 0L) return emptyList()
            val events = ArrayList<IngestEvent>(batchSize)
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var count = 0
                while (count < batchSize) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val parsed = runCatching {
                        json.decodeFromString<IngestEvent>(line)
                    }.getOrNull()
                    if (parsed != null) {
                        events.add(parsed)
                        count++
                    }
                }
            }
            return events
        }
    }

    /**
     * Removes the first [count] events from the queue. Used after a
     * successful batch send.
     */
    fun drop(count: Int) {
        if (count <= 0) return
        synchronized(lock) { dropOldestLocked(count) }
    }

    /** Convenience for atomically peeking and then consuming on success. */
    fun peekBatch(batchSize: Int): Batch = synchronized(lock) {
        val events = peek(batchSize)
        Batch(events, onConsumed = { drop(events.size) })
    }

    /** Wipe the queue entirely. Used on `reset()`. */
    fun clear() {
        synchronized(lock) {
            storage.delete(storage.eventsFile)
            cachedSize = 0
        }
    }

    /** A peeked batch plus a callback to acknowledge a successful send. */
    internal class Batch(
        val events: List<IngestEvent>,
        private val onConsumed: () -> Unit,
    ) {
        fun consume() = onConsumed()
    }

    // ---------------- Internal ----------------

    private fun sizeLocked(): Int {
        val file = storage.eventsFile
        if (!file.exists()) {
            cachedSize = 0
            return 0
        }
        if (cachedSize >= 0) return cachedSize
        var count = 0
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) count++
                }
            }
        } catch (e: Throwable) {
            RitmusLogger.w("EventQueue.sizeLocked failed", e)
        }
        cachedSize = count
        return count
    }

    private fun dropOldestLocked(count: Int) {
        if (count <= 0) return
        val file = storage.eventsFile
        if (!file.exists()) return
        try {
            val remaining = ArrayList<String>()
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var skipped = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (skipped < count) {
                        skipped++
                    } else {
                        remaining.add(line)
                    }
                }
            }
            if (remaining.isEmpty()) {
                storage.delete(file)
                cachedSize = 0
            } else {
                storage.writeText(file, remaining.joinToString(separator = "\n", postfix = "\n"))
                cachedSize = remaining.size
            }
        } catch (e: Throwable) {
            RitmusLogger.w("EventQueue.dropOldestLocked failed", e)
        }
    }

    private fun ensureParent(file: File) {
        val parent = file.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
    }
}
