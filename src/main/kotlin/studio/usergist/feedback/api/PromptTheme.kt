package studio.usergist.feedback.api

/**
 * Visual overrides applied to rendered feedback prompts.
 *
 * Values are merged with any server-supplied prompt theme and the system
 * defaults. Any `null` field falls back to the prompt or system default.
 */
data class PromptTheme(
    val colors: ThemeColors? = null,
    /** Corner radius (dp) applied to the bottom sheet and primary controls. */
    val radius: Int? = null,
    /** Font family name; must be installed in the host app. */
    val fontFamily: String? = null,
)

/**
 * Hex-string colors for the prompt surface. Each value must be
 * `#RRGGBB` or `#AARRGGBB` (Android convention).
 */
data class ThemeColors(
    val primary: String? = null,
    val background: String? = null,
    val text: String? = null,
    val subtext: String? = null,
    val border: String? = null,
)
