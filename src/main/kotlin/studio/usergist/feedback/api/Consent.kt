package studio.usergist.feedback.api

/**
 * User consent flags for SDK data purposes (GDPR-aligned).
 *
 * Each purpose independently gates collection. Denied analytics are dropped;
 * feedback mutations require [feedback]. Control-plane calls can still
 * synchronize anonymous session and consent state.
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
