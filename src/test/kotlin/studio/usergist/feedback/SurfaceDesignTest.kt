package studio.usergist.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.surveys.SurveyProgress
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.ui.requests.relativeTime
import studio.usergist.feedback.internal.ui.surface.SurfaceColor
import studio.usergist.feedback.internal.ui.surface.SurfacePalette

/** Parity with packages/sdk-react-native/src/ui/color.test.ts and theme.ts. */
class SurfaceDesignTest {
    private fun rgb(hex: Int): Int = 0xFF000000.toInt() or hex

    @Test
    fun orangeGetsBlackLabelByRealContrast() {
        // Luminance-0.45 rules gave white on #D97706; WCAG contrast picks black.
        assertEquals(SurfaceColor.BLACK, SurfaceColor.readableOn(rgb(0xD97706)))
    }

    @Test
    fun darkAndSaturatedPrimariesGetWhite() {
        assertEquals(SurfaceColor.WHITE, SurfaceColor.readableOn(rgb(0x111111)))
        assertEquals(SurfaceColor.WHITE, SurfaceColor.readableOn(rgb(0x5B4BFF)))
        assertEquals(SurfaceColor.WHITE, SurfaceColor.readableOn(rgb(0x6154E8)))
    }

    @Test
    fun lightPrimariesGetBlack() {
        assertEquals(SurfaceColor.BLACK, SurfaceColor.readableOn(rgb(0xFACC15)))
        assertEquals(SurfaceColor.BLACK, SurfaceColor.readableOn(rgb(0xFFFFFF)))
        assertEquals(SurfaceColor.BLACK, SurfaceColor.readableOn(rgb(0x86EFAC)))
    }

    @Test
    fun readableLabelAlwaysMeetsLargeTextContrast() {
        val samples = listOf(0x808080, 0x777777, 0x3B82F6, 0xEF4444, 0x22C55E, 0xF59E0B, 0x9CA3AF)
        samples.forEach { hex ->
            val bg = rgb(hex)
            val fg = SurfaceColor.readableOn(bg)
            val ratio = SurfaceColor.contrastRatio(SurfaceColor.relativeLuminance(bg), SurfaceColor.relativeLuminance(fg))
            assertTrue("#${Integer.toHexString(hex)} ratio $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun withAlphaKeepsChannels() {
        val tinted = SurfaceColor.withAlpha(rgb(0x5B4BFF), 0.10f)
        assertEquals(26, (tinted ushr 24) and 0xFF)
        assertEquals(0x5B, SurfaceColor.red(tinted))
        assertEquals(0x4B, SurfaceColor.green(tinted))
        assertEquals(0xFF, SurfaceColor.blue(tinted))
    }

    @Test
    fun paletteDerivesTokensFromThemeNotFixedGreys() {
        val dark = SurfacePalette.from(ResolvedTheme(
            primary = rgb(0xA78BFA), background = rgb(0x0F172A), text = rgb(0xF8FAFC),
            subtext = rgb(0x94A3B8), border = rgb(0x334155), radiusDp = 12, fontFamily = "Inter",
        ))
        // Field fill and Close face are washes of the (light) text colour on dark themes.
        assertEquals(0xF8, SurfaceColor.red(dark.fill))
        assertEquals(15, (dark.fill ushr 24) and 0xFF)
        assertEquals(SurfaceColor.BLACK, dark.onPrimary)
        assertEquals(false, dark.lightBackground)
        assertEquals(12, dark.radius(16))
        assertEquals("Inter", dark.fontFamily)
    }

    @Test
    fun paletteFallsBackToRnDefaults() {
        val palette = SurfacePalette.from(ResolvedTheme(null, null, null, null, null, null, null))
        assertEquals(SurfacePalette.DEFAULT.primary, palette.primary)
        assertEquals(20, palette.radiusDp)
        assertEquals(SurfaceColor.WHITE, palette.onPrimary)
        assertEquals(true, palette.lightBackground)
    }

    @Test
    fun progressFractionIsBounded() {
        assertEquals(0f, SurveyProgress.fraction(0, 0), 0f)
        assertEquals(0.25f, SurveyProgress.fraction(4, 0), 0.0001f)
        assertEquals(1f, SurveyProgress.fraction(4, 9), 0f)
    }

    @Test
    fun relativeTimeBuckets() {
        val now = 1_800_000_000_000L
        assertEquals("just now", relativeTime("2027-01-15T08:00:00.000Z", parse("2027-01-15T08:00:30.000Z")))
        assertEquals("5m ago", relativeTime("2027-01-15T08:00:00.000Z", parse("2027-01-15T08:05:00.000Z")))
        assertEquals("3h ago", relativeTime("2027-01-15T08:00:00.000Z", parse("2027-01-15T11:00:00.000Z")))
        assertEquals("2d ago", relativeTime("2027-01-15T08:00:00.000Z", parse("2027-01-17T08:00:00.000Z")))
        assertEquals("", relativeTime("not a date", now))
    }

    private fun parse(iso: String): Long =
        requireNotNull(studio.usergist.feedback.internal.util.DateTime.parseIso(iso))
}
