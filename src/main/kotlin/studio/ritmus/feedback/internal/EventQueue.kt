package studio.ritmus.feedback.internal

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import studio.ritmus.feedback.internal.model.IngestEvent
import java.io.File

// PORTED FROM: packages/sdk-react-native/src/internal/queue.ts
//
// Persisted shape on disk:
//   <header line>: {"version":1}\n
//   <event line 1>: {...}\n
//   <event line 2>: {...}\n
//
// Bumped every time the on-disk shape changes; older snapshots are
// discarded rather than risk a deserialise mismatch. Events are
// best-effort, not durable contracts.
//
// Legacy migration: pre-versioning builds wrote bare {...}\n event
// lines with no header. On hydrate, if the first line is missing the
// "version" key, we treat the entire file as legacy events; the next
// `dropOldestLocked()` rewrite re-emits the header.
private const val QUEUE_SCHEMA_VERSION = 1
private const val QUEUE_HEADER_LINE = "{\"version\":$QUEUE_SCHEMA_VERSION}"

/**
 * Bounded, persistent FIFO queue of events.
 *
 * Format: versioned header line followed by JSON-lines (one serialized
 * [IngestEvent] per line). Append is O(1) — we append to the end of the
 * file (after ensuring the header exists). Draining loads the file,
 * slices off the drained prefix, and rewrites the remainder atomically.
 *
 * Overflow policy: drop-oldest. When [maxQueueSize] is exceeded, we
 * rewrite the tail so newer events survive.
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
                // First write to a fresh file emits the version header so
                // future hydrates know how to parse the rest. If the file
                // already exists with legacy bare-event lines, we leave it
                // alone — the next dropOldestLocked() rewrite injects the
                // header (mirrors RN's "re-wrap on next persist" pattern).
                val previousSize = sizeLocked()
                if (!file.exists() || file.length() == 0L) {
                    file.appendText(QUEUE_HEADER_LINE + "\n", Charsets.UTF_8)
                }
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
                var headerConsumed = false
                var count = 0
                while (count < batchSize) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!headerConsumed) {
                        headerConsumed = true
                        val version = readHeaderVersion(line)
                        if (version != null) {
                            if (version != QUEUE_SCHEMA_VERSION) {
                                discardStaleLocked()
                                return emptyList()
                            }
                            continue
                        }
                        // No header → legacy line; parse as event.
                    }
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
        if (isVersionStaleLocked()) {
            discardStaleLocked()
            return 0
        }
        var count = 0
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var headerConsumed = false
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!headerConsumed) {
                        headerConsumed = true
                        if (isHeaderLine(line)) continue
                    }
                    count++
                }
            }
        } catch (e: Throwable) {
            RitmusLogger.w("EventQueue.sizeLocked failed", e)
        }
        cachedSize = count
        return count
    }

    /** True when the persisted file's header declares an unrecognised version. */
    private fun isVersionStaleLocked(): Boolean {
        val file = storage.eventsFile
        if (!file.exists() || file.length() == 0L) return false
        val firstLine = try {
            file.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        } catch (e: Throwable) {
            return false
        } ?: return false
        if (firstLine.isBlank()) return false
        val version = readHeaderVersion(firstLine) ?: return false
        return version != QUEUE_SCHEMA_VERSION
    }

    private fun isHeaderLine(line: String): Boolean = readHeaderVersion(line) != null

    private fun readHeaderVersion(line: String): Int? {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
            ?: return null
        return (obj["version"] as? JsonPrimitive)?.intOrNull
    }

    private fun discardStaleLocked() {
        try {
            storage.delete(storage.eventsFile)
        } catch (e: Throwable) {
            RitmusLogger.w("EventQueue.discardStaleLocked failed", e)
        }
        cachedSize = 0
    }

    private fun dropOldestLocked(count: Int) {
        if (count <= 0) return
        val file = storage.eventsFile
        if (!file.exists()) return
        if (isVersionStaleLocked()) {
            discardStaleLocked()
            return
        }
        try {
            val remaining = ArrayList<String>()
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var headerConsumed = false
                var skipped = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!headerConsumed) {
                        headerConsumed = true
                        if (isHeaderLine(line)) continue
                    }
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
                // Always rewrite with the version header in front, ensuring any
                // legacy bare-line file gets re-wrapped on its next drop.
                val body = remaining.joinToString(separator = "\n", postfix = "\n")
                storage.writeText(file, "$QUEUE_HEADER_LINE\n$body")
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
