package studio.usergist.feedback.api

/**
 * The deployment environment the SDK is talking to.
 *
 * Controls which default API base URL is used when none is explicitly
 * provided via `apiUrl` in [studio.usergist.feedback.UserGist.initialize].
 */
enum class Environment(
    /** Default HTTPS base URL for this environment. */
    val defaultApiUrl: String,
) {
    /** Production environment — real user data. */
    PRODUCTION("https://api.usergist.com"),

    /** Staging environment — pre-release validation. */
    // Staging identifies the customer's app data environment. Both customer
    // tiers use the same public UserGist edge and remain isolated by app/key.
    STAGING("https://api.usergist.com"),

    /** Development environment — local or dev backends. */
    DEVELOPMENT("http://10.0.2.2:28743"),
}
