package studio.usergist.feedback.internal.ui

import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.usergist.feedback.R
import studio.usergist.feedback.api.ArmedInAppMessage
import studio.usergist.feedback.api.InAppCta
import studio.usergist.feedback.api.InAppCtaAction
import studio.usergist.feedback.api.InAppDismissReason
import studio.usergist.feedback.api.InAppMessageFormat
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.model.WirePromptTheme
import studio.usergist.feedback.internal.model.WireThemeColors
import studio.usergist.feedback.internal.ui.surface.BoundedScrollView
import studio.usergist.feedback.internal.ui.surface.CONTENT_MAX_WIDTH_DP
import studio.usergist.feedback.internal.ui.surface.MeasureLayout
import studio.usergist.feedback.internal.ui.surface.SurfaceButton
import studio.usergist.feedback.internal.ui.surface.SurfaceColor
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.accessibilityHeading
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceClose
import studio.usergist.feedback.internal.ui.surface.surfaceImage
import studio.usergist.feedback.internal.ui.surface.surfaceText

/**
 * Renderer for card, full-screen and slide-up messages (port of RN
 * InAppMessageView + InAppMessageLayout). All formats use one full-window,
 * edge-to-edge dialog so the scrim, safe insets and placement are ours:
 * - card: centred card (400dp, 560dp in landscape), scrolls within safe height;
 * - full: full screen, content in a centred 560dp column;
 * - slide-up: floating inset card with all corners rounded, above the
 *   gesture bar, capped to 560dp in landscape.
 * Images are never dropped; long bodies scroll and the action area keeps its
 * natural height up to 40% of the screen before it scrolls too.
 */
internal class InAppMessageFragment : DialogFragment() {
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false
    private var shownReported = false
    private var message: ArmedInAppMessage? = null
    private var hostTheme: PromptTheme? = null
    private var card: View? = null
    private var scrim: View? = null
    private var closing = false
    private var savedStateFresh = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shownReported = savedInstanceState?.getBoolean("usergist.inapp.shown") ?: false
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        message = arguments?.getString(ARG_MESSAGE)?.let {
            runCatching { json.decodeFromString<ArmedInAppMessage>(it) }.getOrNull()
        }
        hostTheme = arguments?.getString(ARG_THEME)?.let {
            runCatching {
                json.decodeFromString<PromptSheetFragment.HostThemeEnvelope>(it).toPromptTheme()
            }.getOrNull()
        }
        if (message == null) dismissAllowingStateLoss()
    }

    override fun getTheme(): Int = R.style.UserGist_InApp_Full

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val current = message ?: return View(requireContext())
        return buildRoot(current)
    }

    override fun onStart() {
        super.onStart()
        val current = message ?: return
        configureWindow(dialog?.window, current)
        if (savedStateFresh) {
            savedStateFresh = false
            animateIn(current)
        }
        if (!shownReported) {
            shownReported = true
            shownCallback?.safeInvoke(current.messageId)
        }
        val seconds = current.autoDismissSeconds
        if (current.format == InAppMessageFormat.SLIDEUP && seconds != null && seconds > 0) {
            handler.postDelayed(
                {
                    if (isAdded && !delivered) {
                        deliverDismiss(InAppDismissReason.AUTO)
                        closeAnimated()
                    }
                },
                (seconds * 1_000).toLong(),
            )
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        deliverDismiss(InAppDismissReason.USER)
        super.onCancel(dialog)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("usergist.inapp.shown", shownReported)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // ---------------- Layout ----------------

    private fun resolvePalette(current: ArmedInAppMessage): SurfacePalette = SurfacePalette.from(
        ThemeResolver.merge(
            WirePromptTheme(
                colors = WireThemeColors(
                    primary = current.accentColor,
                    background = current.backgroundColor,
                ),
            ),
            hostTheme,
        ),
    )

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun buildRoot(current: ArmedInAppMessage): View {
        val ctx = requireContext()
        val palette = resolvePalette(current)
        val full = current.format == InAppMessageFormat.MODAL_FULL
        val slide = current.format == InAppMessageFormat.SLIDEUP
        val root = FrameLayout(ctx)
        if (full) {
            root.setBackgroundColor(palette.background)
        } else {
            scrim = View(ctx).apply {
                setBackgroundColor(if (current.backdropEnabled) SurfacePalette.SCRIM else Color.TRANSPARENT)
                contentDescription = "Dismiss"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { userDismiss() }
            }
            root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val surface = when {
            full -> buildFull(current, palette)
            slide -> buildSlide(current, palette)
            else -> buildCard(current, palette)
        }
        card = surface
        val holder = FrameLayout(ctx)
        holder.addView(surface, FrameLayout.LayoutParams(
            if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.MATCH_PARENT,
            if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            when {
                full -> Gravity.FILL
                slide -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                else -> Gravity.CENTER
            },
        ))
        root.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(holder) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val side = if (full) 0 else ctx.dpPx(if (slide) 12 else 16)
            target.setPadding(
                safe.left + side,
                safe.top + ctx.dpPx(if (full) 0 else 8),
                safe.right + side,
                maxOf(safe.bottom, ctx.dpPx(12)) + if (slide) ctx.dpPx(4) else 0,
            )
            insets
        }
        return root
    }

    private fun maxFooterHeight(): Int =
        maxOf(requireContext().dpPx(120), (resources.displayMetrics.heightPixels * 0.4f).toInt())

    private fun buildCard(current: ArmedInAppMessage, palette: SurfacePalette): View {
        val ctx = requireContext()
        val landscape = isLandscape()
        val card = MeasureLayout(ctx, ctx.dpPx(if (landscape) CONTENT_MAX_WIDTH_DP else 400)).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps on the card never reach the scrim
            background = roundRect(palette.background, ctx.dpF(palette.radiusDp), 1, SurfaceColor.withAlpha(palette.text, 0.08f))
            clipToOutline = true
        }
        card.addView(LinearLayout(ctx).apply {
            setPadding(ctx.dpPx(8), ctx.dpPx(8), ctx.dpPx(8), 0)
            addView(closeButton(palette))
        }, matchWidth())
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpPx(22), 0, ctx.dpPx(22), ctx.dpPx(8))
            image(current, palette, if (landscape) 128 else 168)?.let { addView(it, matchWidth().apply { bottomMargin = ctx.dpPx(18) }) }
            addCopy(this, current, palette, 19f, 25f, 15f, 22f, 8)
        }
        // The body shrinks and scrolls within the safe height; the footer is
        // capped at 40% of the screen, so the body keeps a readable slice.
        card.addView(scrolling(body, 0), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buildCtas(current, palette, stacked = !landscape, compact = false)?.let { ctas ->
            ctas.setPadding(ctx.dpPx(22), ctx.dpPx(16), ctx.dpPx(22), ctx.dpPx(22))
            card.addView(scrolling(ctas, maxFooterHeight()), matchWidth())
        }
        return card
    }

    private fun buildFull(current: ArmedInAppMessage, palette: SurfacePalette): View {
        val ctx = requireContext()
        val landscape = isLandscape()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(ctx).apply {
            setPadding(ctx.dpPx(12), ctx.dpPx(4), ctx.dpPx(12), 0)
            addView(closeButton(palette))
        }, matchWidth())
        val column = MeasureLayout(ctx, ctx.dpPx(CONTENT_MAX_WIDTH_DP)).apply {
            orientation = LinearLayout.VERTICAL
            image(current, palette, if (landscape) 128 else 240)?.let { addView(it, matchWidth().apply { bottomMargin = ctx.dpPx(24) }) }
            addCopy(this, current, palette, 26f, 32f, 17f, 26f, 8)
        }
        val centered = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dpPx(24), ctx.dpPx(8), ctx.dpPx(24), ctx.dpPx(24))
            addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        root.addView(scrolling(centered, 0).apply { isFillViewport = true },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { })
        buildCtas(current, palette, stacked = !landscape, compact = false)?.let { ctas ->
            val measure = MeasureLayout(ctx, ctx.dpPx(CONTENT_MAX_WIDTH_DP)).apply {
                orientation = LinearLayout.VERTICAL
                addView(ctas, matchWidth())
            }
            val wrap = FrameLayout(ctx).apply {
                setPadding(ctx.dpPx(24), ctx.dpPx(8), ctx.dpPx(24), 0)
                addView(measure, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            }
            root.addView(scrolling(wrap, maxFooterHeight()), matchWidth())
        }
        return root
    }

    private fun buildSlide(current: ArmedInAppMessage, palette: SurfacePalette): View {
        val ctx = requireContext()
        val card = MeasureLayout(ctx, if (isLandscape()) ctx.dpPx(CONTENT_MAX_WIDTH_DP) else 0).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(ctx.dpPx(8), ctx.dpPx(8), ctx.dpPx(8), ctx.dpPx(14))
            background = roundRect(palette.background, ctx.dpF(palette.radiusDp), 1, SurfaceColor.withAlpha(palette.text, 0.08f))
            clipToOutline = true
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(closeButton(palette))
            val copy = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dpPx(4), ctx.dpPx(10), ctx.dpPx(8), ctx.dpPx(2))
                addCopy(this, current, palette, 19f, 25f, 15f, 22f, 4)
                image(current, palette, 120)?.let { addView(it, matchWidth().apply { topMargin = ctx.dpPx(10) }) }
            }
            addView(scrolling(copy, 0), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buildCtas(current, palette, stacked = false, compact = true)?.let { ctas ->
            ctas.setPadding(0, ctx.dpPx(2), 0, 0)
            card.addView(scrolling(ctas, maxFooterHeight()), matchWidth().apply { topMargin = ctx.dpPx(10) })
        }
        return card
    }

    private fun scrolling(child: View, maxHeightPx: Int) = BoundedScrollView(requireContext(), maxHeightPx).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = maxHeightPx == 0
        addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun closeButton(palette: SurfacePalette): View =
        requireContext().surfaceClose(palette, getString(R.string.usergist_close)) { userDismiss() }

    private fun image(current: ArmedInAppMessage, palette: SurfacePalette, heightDp: Int): View? {
        val url = current.imageUrl?.takeIf { it.isNotBlank() } ?: return null
        val ctx = requireContext()
        // Fixed display height (cropped) like RN; width follows the column.
        return ctx.surfaceImage(url, ctx.dpF(maxOf(8, palette.radiusDp - 8)), ratio = 0f, maxHeightPx = ctx.dpPx(heightDp))
    }

    private fun addCopy(
        parent: LinearLayout,
        current: ArmedInAppMessage,
        palette: SurfacePalette,
        titleSp: Float,
        titleLh: Float,
        bodySp: Float,
        bodyLh: Float,
        gapDp: Int,
    ) {
        val ctx = parent.context
        parent.addView(ctx.surfaceText(palette, current.title, titleSp, palette.text, 700, titleLh).apply {
            letterSpacing = if (titleSp > 20f) -0.015f else -0.01f
            accessibilityHeading()
        }, matchWidth())
        current.body?.takeIf { it.isNotBlank() }?.let { body ->
            parent.addView(ctx.surfaceText(palette, body, bodySp, palette.subtext, 400, bodyLh), matchWidth().apply {
                topMargin = ctx.dpPx(gapDp)
            })
        }
    }

    /**
     * Portrait cards stack actions so long labels never truncate; landscape
     * and slide-up put them side by side. The first CTA is the primary action
     * and sits on the trailing side of a row.
     */
    private fun buildCtas(current: ArmedInAppMessage, palette: SurfacePalette, stacked: Boolean, compact: Boolean): LinearLayout? {
        if (current.ctas.isEmpty()) return null
        val ctx = requireContext()
        val gap = ctx.dpPx(10)
        val buttons = current.ctas.mapIndexed { index, cta ->
            SurfaceButton(ctx, palette, cta.label, secondary = index > 0, compact = compact).apply {
                setOnAction {
                    deliverCta(current, cta, index)
                    openTarget(cta)
                    closeAnimated()
                }
            }
        }
        return LinearLayout(ctx).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val ordered = if (stacked) buttons else buttons.reversed()
            ordered.forEachIndexed { i, button ->
                val params = if (stacked) {
                    matchWidth().apply { if (i > 0) topMargin = gap }
                } else {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = gap }
                }
                addView(button, params)
            }
        }
    }

    // ---------------- Window and motion ----------------

    private fun configureWindow(window: Window?, current: ArmedInAppMessage) {
        if (window == null) return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val light = when {
            current.format == InAppMessageFormat.MODAL_FULL -> resolvePalette(current).lightBackground
            current.backdropEnabled -> false
            else -> activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars } ?: false
        }
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    private fun motionEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(requireContext().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

    private fun animateIn(current: ArmedInAppMessage) {
        if (current.format != InAppMessageFormat.SLIDEUP || !motionEnabled()) return
        val target = card ?: return
        scrim?.alpha = 0f
        scrim?.animate()?.alpha(1f)?.setDuration(220)?.start()
        target.translationY = resources.displayMetrics.heightPixels.toFloat()
        target.animate().translationY(0f).setDuration(360).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun userDismiss() {
        deliverDismiss(InAppDismissReason.USER)
        closeAnimated()
    }

    private fun closeAnimated() {
        if (closing) return
        closing = true
        val target = card
        if (message?.format != InAppMessageFormat.SLIDEUP || target == null || !motionEnabled()) {
            dismissAllowingStateLoss()
            return
        }
        scrim?.animate()?.alpha(0f)?.setDuration(160)?.start()
        target.animate()
            .translationY(target.height.toFloat() + requireContext().dpPx(48))
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator(1.6f))
            .withEndAction { if (isAdded) dismissAllowingStateLoss() }
            .start()
    }

    // ---------------- Callbacks ----------------

    private fun deliverDismiss(reason: InAppDismissReason) {
        val id = message?.messageId ?: return
        if (delivered) return
        delivered = true
        dismissedCallback?.safeInvoke(id, reason)
    }

    private fun deliverCta(current: ArmedInAppMessage, cta: InAppCta, index: Int) {
        if (delivered) return
        delivered = true
        ctaCallback?.safeInvoke(current.messageId, cta, index)
    }

    private fun openTarget(cta: InAppCta) {
        if (cta.action != InAppCtaAction.OPEN_URL && cta.action != InAppCtaAction.DEEP_LINK) return
        val target = cta.target?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure { UserGistLogger.w("Unable to open in-app CTA target", it) }
    }

    companion object {
        private const val ARG_MESSAGE = "usergist.inapp.message"
        private const val ARG_THEME = "usergist.inapp.theme"

        @Volatile
        internal var shownCallback: ((String) -> Unit)? = null

        @Volatile
        internal var dismissedCallback: ((String, InAppDismissReason) -> Unit)? = null

        @Volatile
        internal var ctaCallback: ((String, InAppCta, Int) -> Unit)? = null

        fun newInstance(
            message: ArmedInAppMessage,
            theme: PromptTheme?,
        ): InAppMessageFragment {
            val json = Json { encodeDefaults = true }
            return InAppMessageFragment().apply {
                arguments = bundleOf(
                    ARG_MESSAGE to json.encodeToString(message),
                    ARG_THEME to theme?.let {
                        json.encodeToString(PromptSheetFragment.HostThemeEnvelope.from(it))
                    },
                )
            }
        }

        private fun <A> ((A) -> Unit).safeInvoke(a: A) {
            runCatching { invoke(a) }.onFailure {
                UserGistLogger.w("In-app callback threw", it)
            }
        }

        private fun <A, B> ((A, B) -> Unit).safeInvoke(a: A, b: B) {
            runCatching { invoke(a, b) }.onFailure {
                UserGistLogger.w("In-app callback threw", it)
            }
        }

        private fun <A, B, C> ((A, B, C) -> Unit).safeInvoke(a: A, b: B, c: C) {
            runCatching { invoke(a, b, c) }.onFailure {
                UserGistLogger.w("In-app callback threw", it)
            }
        }
    }
}
