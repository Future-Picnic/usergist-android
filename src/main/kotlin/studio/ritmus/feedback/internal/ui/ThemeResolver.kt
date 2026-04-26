package studio.ritmus.feedback.internal.ui

import android.graphics.Color
import studio.ritmus.feedback.api.PromptTheme
import studio.ritmus.feedback.api.ThemeColors
import studio.ritmus.feedback.internal.model.WirePromptTheme
import studio.ritmus.feedback.internal.model.WireThemeColors

/**
 * Merges the server-supplied prompt theme with any SDK-level overrides
 * set by the host app via `setThemeOverrides`. Per-field: host override
 * wins over server value.
 */
internal object ThemeResolver {

    fun merge(server: WirePromptTheme?, host: PromptTheme?): ResolvedTheme {
        val primary = parseColor(host?.colors?.primary ?: server?.colors?.primary)
        val background = parseColor(host?.colors?.background ?: server?.colors?.background)
        val text = parseColor(host?.colors?.text ?: server?.colors?.text)
        val subtext = parseColor(host?.colors?.subtext ?: server?.colors?.subtext)
        val border = parseColor(host?.colors?.border ?: server?.colors?.border)
        val radius = host?.radius ?: server?.radius
        val fontFamily = host?.fontFamily ?: server?.fontFamily ?: "Plus Jakarta Sans"
        return ResolvedTheme(
            primary = primary,
            background = background,
            text = text,
            subtext = subtext,
            border = border,
            radiusDp = radius,
            fontFamily = fontFamily,
        )
    }

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
