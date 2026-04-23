package studio.ritmus.feedback.api

/**
 * The deployment environment the SDK is talking to.
 *
 * Controls which default API base URL is used when none is explicitly
 * provided via `apiUrl` in [studio.ritmus.feedback.Ritmus.initialize].
 */
enum class Environment(
    /** Default HTTPS base URL for this environment. */
    val defaultApiUrl: String,
) {
    /** Production environment — real user data. */
    PRODUCTION("https://api.ritmus.studio"),

    /** Staging environment — pre-release validation. */
    STAGING("https://api.staging.ritmus.studio"),

    /** Development environment — local or dev backends. */
    DEVELOPMENT("http://10.0.2.2:3000"),
}
