package studio.usergist.feedback.internal.ui.surface

import kotlin.math.pow

/**
 * Pure ARGB colour helpers (no android.graphics calls, so they run in JVM
 * unit tests). Mirrors packages/sdk-react-native/src/ui/color.ts.
 */
internal object SurfaceColor {
    const val BLACK: Int = 0xFF000000.toInt()
    const val WHITE: Int = 0xFFFFFFFF.toInt()

    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha and 0xFF) shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF)

    /** WCAG relative luminance; alpha is ignored. */
    fun relativeLuminance(argb: Int): Double {
        fun linear(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(red(argb)) + 0.7152 * linear(green(argb)) + 0.0722 * linear(blue(argb))
    }

    /** WCAG contrast ratio between two luminances (1–21). */
    fun contrastRatio(a: Double, b: Double): Double {
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Label colour on a filled background: black or white, whichever has the
     * greater actual contrast (e.g. #D97706 gets black, #5B4BFF gets white).
     */
    fun readableOn(background: Int): Int {
        val lum = relativeLuminance(background)
        return if (contrastRatio(lum, 0.0) > contrastRatio(lum, 1.0)) BLACK else WHITE
    }

    /** Same colour with a new alpha (0–1). */
    fun withAlpha(argb: Int, alpha: Float): Int =
        argb((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt(), red(argb), green(argb), blue(argb))
}
