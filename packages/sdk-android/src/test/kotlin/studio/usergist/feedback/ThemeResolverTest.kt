package studio.usergist.feedback

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.api.ThemeColors
import studio.usergist.feedback.internal.model.WirePromptTheme
import studio.usergist.feedback.internal.model.WireThemeColors
import studio.usergist.feedback.internal.ui.ThemeResolver

class ThemeResolverTest {
    @Test
    fun `per-prompt theme wins while global theme fills missing fields`() {
        val global = PromptTheme(
            colors = ThemeColors(
                primary = "#111111",
                background = "#FFFFFF",
                text = "#222222",
                subtext = "#333333",
                border = "#444444",
            ),
            radius = 16,
            fontFamily = "Global Font",
        )
        val prompt = WirePromptTheme(
            colors = WireThemeColors(
                primary = "#6548E8",
                background = "#F7F5FF",
                text = "#1D1933",
            ),
            radius = 24,
        )

        val resolved = ThemeResolver.resolveTokens(prompt, global)

        assertEquals("#6548E8", resolved.primary)
        assertEquals("#F7F5FF", resolved.background)
        assertEquals("#1D1933", resolved.text)
        assertEquals("#333333", resolved.subtext)
        assertEquals("#444444", resolved.border)
        assertEquals(24, resolved.radius)
        assertEquals("Global Font", resolved.fontFamily)
    }
}
