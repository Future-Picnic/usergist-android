package studio.usergist.feedback.internal.ui.requests

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.api.RequestStatus
import studio.usergist.feedback.api.RequestSummary
import studio.usergist.feedback.internal.ui.ThemeResolver
import studio.usergist.feedback.internal.ui.surface.Glyph
import studio.usergist.feedback.internal.ui.surface.GlyphView
import studio.usergist.feedback.internal.ui.surface.PressFrame
import studio.usergist.feedback.internal.ui.surface.SurfaceColor
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.announceAsButton
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceText
import studio.usergist.feedback.internal.model.WirePromptTheme
import studio.usergist.feedback.internal.model.WireThemeColors
import studio.usergist.feedback.internal.util.DateTime

// Presentational pieces of the requests UI (port of RN ui/requests/*).
// All colours come from the resolved theme; only status pills keep their
// semantic colours, as in RN StatusPill.

/** Host theme captured by UserGist.setThemeOverrides for SDK-owned activities. */
internal object RequestsHostTheme {
    @Volatile
    var theme: PromptTheme? = null
}

internal const val FALLBACK_ACCENT = "#5B4BFF"

/** Host theme + board accent (RN useRequestsTheme). */
internal fun requestsPalette(accentColor: String?): SurfacePalette = SurfacePalette.from(
    ThemeResolver.merge(
        WirePromptTheme(colors = WireThemeColors(primary = accentColor?.takeIf { it.isNotBlank() } ?: FALLBACK_ACCENT)),
        RequestsHostTheme.theme,
    ),
)

internal val STATUS_ORDER = listOf(
    RequestStatus.UNDER_REVIEW,
    RequestStatus.PLANNED,
    RequestStatus.IN_PROGRESS,
    RequestStatus.SHIPPED,
    RequestStatus.DECLINED,
)

internal fun statusLabel(status: RequestStatus): String = when (status) {
    RequestStatus.UNDER_REVIEW -> "Under review"
    RequestStatus.PLANNED -> "Planned"
    RequestStatus.IN_PROGRESS -> "In progress"
    RequestStatus.SHIPPED -> "Shipped"
    RequestStatus.DECLINED -> "Declined"
}

internal fun statusColor(status: RequestStatus): Int = when (status) {
    RequestStatus.UNDER_REVIEW -> 0xFF9CA3AF.toInt()
    RequestStatus.PLANNED -> 0xFF3B82F6.toInt()
    RequestStatus.IN_PROGRESS -> 0xFFF59E0B.toInt()
    RequestStatus.SHIPPED -> 0xFF22C55E.toInt()
    RequestStatus.DECLINED -> 0xFFEF4444.toInt()
}

internal fun Context.statusPill(palette: SurfacePalette, status: RequestStatus): View {
    val color = statusColor(status)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpPx(8), dpPx(3), dpPx(8), dpPx(3))
        background = roundRect(SurfaceColor.withAlpha(color, 0.094f), dpF(6), dpPx(1), SurfaceColor.withAlpha(color, 0.33f))
        addView(View(context).apply { background = roundRect(color, dpF(2.5f)) }, LinearLayout.LayoutParams(dpPx(5), dpPx(5)))
        addView(surfaceText(palette, statusLabel(status), 11f, color, 600), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpPx(5) })
    }
}

/** Header title with an optional caption under it. */
internal fun Context.requestsTitle(palette: SurfacePalette, title: String, caption: String? = null): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(surfaceText(palette, title, 17f, palette.text, 700, gravity = Gravity.CENTER).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        caption?.let {
            addView(surfaceText(palette, it, 13f, palette.subtext, gravity = Gravity.CENTER), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpPx(1) })
        }
    }

/** Status filter chip: 36dp pill inside a 48dp touch target. */
internal fun Context.filterChip(
    palette: SurfacePalette,
    label: String,
    count: Int,
    active: Boolean,
    dotColor: Int?,
    onClick: () -> Unit,
): View = PressFrame(this, 0.7f).apply {
    isClickable = true
    isFocusable = true
    announceAsButton()
    isSelected = active
    contentDescription = "$label, $count" + if (active) ", selected" else ""
    minimumHeight = dpPx(48)
    val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dpPx(36)
        setPadding(dpPx(14), 0, dpPx(14), 0)
        background = roundRect(if (active) palette.tint else 0, dpF(18), dpPx(1.5f), if (active) palette.primary else palette.border)
        dotColor?.let {
            addView(View(context).apply { background = roundRect(it, dpF(3.5f)) }, LinearLayout.LayoutParams(dpPx(7), dpPx(7)).apply { marginEnd = dpPx(6) })
        }
        addView(surfaceText(palette, label, 14f, if (active) palette.primary else palette.text, 600))
        addView(surfaceText(palette, count.toString(), 13f, if (active) palette.primary else palette.subtext, 600),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpPx(6) })
    }
    addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    setOnClickListener { onClick() }
}

/** Upvote block: chevron + count; filled when the viewer has voted. */
internal fun Context.upvoteBlock(
    palette: SurfacePalette,
    count: Int,
    voted: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
): View = PressFrame(this, 0.82f).apply {
    isClickable = true
    isFocusable = true
    isEnabled = enabled
    announceAsButton()
    isSelected = voted
    contentDescription = label
    background = roundRect(if (voted) palette.primary else palette.tint, dpF(palette.radius(12)))
    val fg = if (voted) palette.onPrimary else palette.primary
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(GlyphView(context, Glyph.UP, fg), LinearLayout.LayoutParams(dpPx(16), dpPx(12)))
        addView(surfaceText(palette, count.toString(), 15f, fg, 700, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpPx(4) })
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    setOnClickListener { if (enabled) onClick() }
}

/** Board card (RN RequestCard). */
internal fun Context.requestCard(
    palette: SurfacePalette,
    item: RequestSummary,
    voteEnabled: Boolean,
    onOpen: () -> Unit,
    onUpvote: () -> Unit,
): View {
    val radius = dpF(palette.radius(18))
    val card = object : LinearLayout(this) {
        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            background = roundRect(if (pressed) palette.fill else 0, radius, dpPx(1), palette.border)
        }
    }
    return card.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        isClickable = true
        isFocusable = true
        contentDescription = "Open request ${item.title}"
        setPadding(dpPx(14), dpPx(14), dpPx(14), dpPx(14))
        background = roundRect(0, radius, dpPx(1), palette.border)
        setOnClickListener { onOpen() }
        val label = if (item.viewerHasUpvoted) "Remove vote from ${item.title}" else "Upvote ${item.title}"
        val upvote = upvoteBlock(palette, item.upvoteCount, item.viewerHasUpvoted, label, voteEnabled, onUpvote).apply {
            minimumHeight = dpPx(56)
            setPadding(0, dpPx(8), 0, dpPx(8))
        }
        addView(upvote, LinearLayout.LayoutParams(dpPx(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(surfaceText(palette, item.title, 16f, palette.text, 600, 21f).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, matchWidth())
            addView(surfaceText(palette, item.description, 14f, palette.subtext, 400, 20f).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, matchWidth().apply { topMargin = dpPx(4) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(statusPill(palette, item.status))
                val followers = if (item.viewerIsFollowing) "Following" else
                    "${item.followerCount} ${if (item.followerCount == 1) "follower" else "followers"}"
                addView(surfaceText(palette, followers, 13f, palette.subtext), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpPx(10) })
            }, matchWidth().apply { topMargin = dpPx(10) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpPx(14) })
    }
}

/** "12 upvotes" with the number emphasised in the text colour. */
internal fun Context.statText(palette: SurfacePalette, value: Int, singular: String, plural: String): TextView {
    val number = value.toString()
    val text = SpannableStringBuilder("$number ${if (value == 1) singular else plural}").apply {
        setSpan(ForegroundColorSpan(palette.text), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return surfaceText(palette, text, 15f, palette.subtext).apply {
        contentDescription = "$value ${if (value == 1) singular else plural}"
    }
}

/** Empty / error block with an optional primary action. */
internal fun Context.requestsEmpty(palette: SurfacePalette, title: String, body: String, action: View? = null): View =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dpPx(32), dpPx(48), dpPx(32), dpPx(48))
        addView(surfaceText(palette, title, 18f, palette.text, 700, gravity = Gravity.CENTER), matchWidth())
        addView(surfaceText(palette, body, 15f, palette.subtext, 400, 22f, Gravity.CENTER), matchWidth().apply { topMargin = dpPx(8) })
        action?.let {
            addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpPx(20)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            it.minimumWidth = dpPx(200)
        }
    }

/** Relative time used by comment rows ("just now", "5m ago", …). */
internal fun relativeTime(iso: String, nowMillis: Long = System.currentTimeMillis()): String {
    val then = DateTime.parseIso(iso) ?: return ""
    val minutes = (nowMillis - then) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(then))
    }
}
