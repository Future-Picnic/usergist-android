package studio.usergist.feedback.internal.ui.surface

import android.graphics.Typeface
import android.os.Build
import studio.usergist.feedback.internal.ui.ResolvedTheme

/**
 * Fully-resolved surface theme. Every SDK surface reads colours from here
 * instead of fixed greys or white, so any dashboard theme (light, dark,
 * pastel or saturated) stays legible. Mirrors RN `ui/theme.ts`
 * (DEFAULT_THEME + surfaceTokens).
 */
internal data class SurfacePalette(
    val primary: Int,
    val background: Int,
    val text: Int,
    val subtext: Int,
    val border: Int,
    val radiusDp: Int,
    val fontFamily: String?,
) {
    /** Readable label colour on a primary fill (black or white by contrast). */
    val onPrimary: Int get() = SurfaceColor.readableOn(primary)

    /** Quiet primary wash for selected options. */
    val tint: Int get() = SurfaceColor.withAlpha(primary, 0.10f)

    /** Neutral wash for inputs and disabled actions. */
    val fill: Int get() = SurfaceColor.withAlpha(text, 0.06f)

    /** Slightly stronger neutral, used for pressed states and tracks. */
    val fillStrong: Int get() = SurfaceColor.withAlpha(text, 0.12f)

    /** Close/Back face. */
    val iconFace: Int get() = SurfaceColor.withAlpha(text, 0.08f)

    /** Outline for secondary actions. */
    val quietOutline: Int get() = SurfaceColor.withAlpha(text, 0.16f)

    /** Label colour of a disabled primary action. */
    val disabledLabel: Int get() = SurfaceColor.withAlpha(text, 0.38f)

    val placeholder: Int get() = SurfaceColor.withAlpha(subtext, 0.8f)

    /** True when dark status-bar icons are the readable choice on [background]. */
    val lightBackground: Boolean get() = SurfaceColor.readableOn(background) == SurfaceColor.BLACK

    fun radius(cap: Int): Int = minOf(radiusDp, cap)

    /** Theme font at a CSS-like weight (400/500/600/700). */
    fun typeface(weight: Int = 400): Typeface {
        val base = fontFamily?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight, false)
        } else {
            Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    companion object {
        const val SCRIM: Int = 0x73000000 // rgba(0,0,0,0.45)

        val DEFAULT = SurfacePalette(
            primary = 0xFF111111.toInt(),
            background = SurfaceColor.WHITE,
            text = 0xFF0B0B0B.toInt(),
            subtext = 0xFF6B6B6B.toInt(),
            border = 0xFFE5E5E5.toInt(),
            radiusDp = 20,
            fontFamily = "Plus Jakarta Sans",
        )

        fun from(theme: ResolvedTheme?): SurfacePalette {
            if (theme == null) return DEFAULT
            return SurfacePalette(
                primary = theme.primary ?: DEFAULT.primary,
                background = theme.background ?: DEFAULT.background,
                text = theme.text ?: DEFAULT.text,
                subtext = theme.subtext ?: DEFAULT.subtext,
                border = theme.border ?: DEFAULT.border,
                radiusDp = theme.radiusDp ?: DEFAULT.radiusDp,
                fontFamily = theme.fontFamily ?: DEFAULT.fontFamily,
            )
        }
    }
}

/** Readable measure for content inside full-width sheets and screens (dp). */
internal const val CONTENT_MAX_WIDTH_DP: Int = 560
