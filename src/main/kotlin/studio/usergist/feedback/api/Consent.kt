package studio.usergist.feedback.api

/**
 * User consent flags for SDK data purposes (GDPR-aligned).
 *
 * The SDK hard-blocks all network transport until the [feedback] purpose
 * is granted. Callers can still invoke `track` / `identify` before
 * consent — those events are queued locally and released once consent is
 * set, or dropped on [studio.usergist.feedback.UserGist.reset].
 */
data class Consent(
    /** Permission to use data for product analytics. */
    val analytics: Boolean? = null,
    /** Permission to render feedback prompts and submit responses. */
    val feedback: Boolean? = null,
    /** Permission to register a device token and receive push notifications. */
    val push: Boolean? = null,
    /** Permission to deliver survey invitations and collect survey responses. */
    val survey: Boolean? = null,
) {
    /** Whether the transport layer may ship data to the backend. */
    val allowsTransport: Boolean
        get() = analytics == true || feedback == true || push == true || survey == true

    /** Whether the SDK may register a device token and accept pushes. */
    val allowsPush: Boolean
        get() = push == true

    /** Whether the SDK may deliver surveys. */
    val allowsSurvey: Boolean
        get() = survey == true
}
