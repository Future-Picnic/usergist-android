package studio.ritmus.feedback.internal.model

import kotlinx.serialization.Serializable

/**
 * The minimal prompt shape the SDK needs to render a feedback prompt on
 * device. Excludes dashboard-only fields (name, status, etc.).
 */
@Serializable
internal data class ClientPrompt(
    val id: String,
    val questions: List<Question>,
    val theme: WirePromptTheme? = null,
)

/** Wire-level prompt theme (matches dashboard payload shape). */
@Serializable
internal data class WirePromptTheme(
    val colors: WireThemeColors? = null,
    val radius: Int? = null,
    val fontFamily: String? = null,
)

/** Wire-level prompt theme colors. */
@Serializable
internal data class WireThemeColors(
    val primary: String? = null,
    val background: String? = null,
    val text: String? = null,
    val subtext: String? = null,
    val border: String? = null,
)
