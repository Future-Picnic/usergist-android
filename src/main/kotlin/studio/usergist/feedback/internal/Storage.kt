package studio.usergist.feedback.internal

import android.content.Context
import studio.usergist.feedback.internal.util.Hashing
import java.io.File

/**
 * Directory layout for the SDK's on-disk state. Each write key gets its
 * own namespace so multiple SDK instances (e.g., prod + staging in the
 * same debug build) don't clobber each other.
 *
 * ```
 * context.filesDir/usergist/{hash(writeKey)}/
 *   events.log              — JSON-line persistent queue
 *   identity.json           — anonymous_id, external_id
 *   consent.json            — {"analytics":true|false,"feedback":...}
 *   armed_triggers.json     — cached ArmedTriggersResponse
 *   frequency_caps.json     — FrequencyCapStore snapshot
 * ```
 */
internal class Storage private constructor(
    private val root: File,
) {

    constructor(appContext: Context, writeKey: String) : this(
        File(appContext.filesDir, "usergist/${Hashing.shortSha256(writeKey)}"),
    )

    init {
        if (!root.exists()) {
            root.mkdirs()
        }
    }

    companion object {
        /** Test-only constructor for unit tests with a plain filesystem path. */
        internal fun forTest(root: File): Storage = Storage(root)
    }

    /** Root directory for this write key's state. */
    val rootDir: File get() = root

    /** Append-only queue of serialized events (one JSON object per line). */
    val eventsFile: File get() = File(root, "events.log")

    /** Identity snapshot ({ "anonymousId":..., "externalId":... }). */
    val identityFile: File get() = File(root, "identity.json")

    /** Consent snapshot. */
    val consentFile: File get() = File(root, "consent.json")

    /** Cached armed triggers payload. */
    val armedTriggersFile: File get() = File(root, "armed_triggers.json")

    /** Cached locally-evaluable survey campaigns. */
    val armedSurveysFile: File get() = File(root, "armed_surveys.json")

    /** Cached locally-evaluable in-app campaigns. */
    val armedInAppMessagesFile: File get() = File(root, "armed_inapp_messages.json")

    /** Sliding-window frequency cap state. */
    val frequencyCapsFile: File get() = File(root, "frequency_caps.json")

    /** Bounded event timestamp history used by client-side segments. */
    val userStateFile: File get() = File(root, "user_state.json")

    /** Durable instruction cursor and bounded dedupe ledger. */
    val instructionStateFile: File get() = File(root, "instruction_state.json")

    /** Legacy plaintext mutation snapshot used only for secure migration. */
    val mutationQueueFile: File get() = File(root, "mutation_queue.json")

    /** Versioned in-progress survey snapshots used for offline resume. */
    val surveyAttemptsFile: File get() = File(root, "survey_attempts.json")

    /** Safe UTF-8 read. Returns `null` if the file does not exist. */
    fun readText(file: File): String? = try {
        if (!file.exists()) null else file.readText(Charsets.UTF_8)
    } catch (e: Throwable) {
        UserGistLogger.w("Storage.readText failed for ${file.name}", e)
        null
    }

    /** Atomic UTF-8 write (via rename). Returns `true` on success. */
    fun writeText(file: File, content: String): Boolean = try {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        // rename is atomic on POSIX.
        if (!tmp.renameTo(file)) {
            // Fall back to a copy if rename failed (rare, e.g. emulated fs).
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
        true
    } catch (e: Throwable) {
        UserGistLogger.w("Storage.writeText failed for ${file.name}", e)
        false
    }

    /** Safe delete. Returns `true` if the file did not exist or was removed. */
    fun delete(file: File): Boolean = try {
        !file.exists() || file.delete()
    } catch (e: Throwable) {
        UserGistLogger.w("Storage.delete failed for ${file.name}", e)
        false
    }
}
