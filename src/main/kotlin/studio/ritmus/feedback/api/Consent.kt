package studio.ritmus.feedback.api

/**
 * User consent flags for SDK data purposes (GDPR-aligned).
 *
 * The SDK hard-blocks all network transport until the [feedback] purpose
 * is granted. Callers can still invoke `track` / `identify` before
 * consent — those events are queued locally and released once consent is
 * set, or dropped on [studio.ritmus.feedback.Ritmus.reset].
 */
data class Consent(
    /** Permission to use data for product analytics. */
    val analytics: Boolean? = null,
    /** Permission to render feedback prompts and submit responses. */
    val feedback: Boolean? = null,
    /** Permission to register a device token and receive push notifications. */
    val push: Boolean? = null,
) {
    /** Whether the transport layer may ship data to the backend. */
    val allowsTransport: Boolean
        get() = feedback == true

    /** Whether the SDK may register a device token and accept pushes. */
    val allowsPush: Boolean
        get() = push == true
}
