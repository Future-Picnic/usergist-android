package studio.usergist.feedback.internal.ui

import android.graphics.Color
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.api.ThemeColors
import studio.usergist.feedback.internal.model.WirePromptTheme
import studio.usergist.feedback.internal.model.WireThemeColors

/**
 * Resolves the global SDK theme and the server-supplied prompt theme.
 * The global theme is the baseline; per-prompt dashboard styling wins
 * per field, matching the React Native SDK.
 */
internal object ThemeResolver {

    fun merge(server: WirePromptTheme?, host: PromptTheme?): ResolvedTheme {
        val tokens = resolveTokens(server, host)
        return ResolvedTheme(
            primary = parseColor(tokens.primary),
            background = parseColor(tokens.background),
            text = parseColor(tokens.text),
            subtext = parseColor(tokens.subtext),
            border = parseColor(tokens.border),
            radiusDp = tokens.radius,
            fontFamily = tokens.fontFamily,
        )
    }

    internal fun resolveTokens(server: WirePromptTheme?, host: PromptTheme?): ResolvedThemeTokens =
        ResolvedThemeTokens(
            primary = server?.colors?.primary ?: host?.colors?.primary,
            background = server?.colors?.background ?: host?.colors?.background,
            text = server?.colors?.text ?: host?.colors?.text,
            subtext = server?.colors?.subtext ?: host?.colors?.subtext,
            border = server?.colors?.border ?: host?.colors?.border,
            radius = server?.radius ?: host?.radius,
            fontFamily = server?.fontFamily ?: host?.fontFamily ?: "Plus Jakarta Sans",
        )

    fun toWire(theme: PromptTheme?): WirePromptTheme? {
        if (theme == null) return null
        val colors = theme.colors
        val wireColors = if (colors == null) null else WireThemeColors(
            primary = colors.primary,
            background = colors.background,
            text = colors.text,
            subtext = colors.subtext,
            border = colors.border,
        )
        return WirePromptTheme(
            colors = wireColors,
            radius = theme.radius,
            fontFamily = theme.fontFamily,
        )
    }

    fun fromWire(theme: WirePromptTheme?): PromptTheme? {
        if (theme == null) return null
        val c = theme.colors
        return PromptTheme(
            colors = if (c == null) null else ThemeColors(
                primary = c.primary,
                background = c.background,
                text = c.text,
                subtext = c.subtext,
                border = c.border,
            ),
            radius = theme.radius,
            fontFamily = theme.fontFamily,
        )
    }

    private fun parseColor(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return try {
            Color.parseColor(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

internal data class ResolvedThemeTokens(
    val primary: String?,
    val background: String?,
    val text: String?,
    val subtext: String?,
    val border: String?,
    val radius: Int?,
    val fontFamily: String,
)

/** Resolved (ARGB) theme used by the rendering layer. */
internal data class ResolvedTheme(
    val primary: Int?,
    val background: Int?,
    val text: Int?,
    val subtext: Int?,
    val border: Int?,
    val radiusDp: Int?,
    val fontFamily: String?,
)
