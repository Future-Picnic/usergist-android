package studio.usergist.feedback.internal.ui

import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.usergist.feedback.R
import studio.usergist.feedback.api.PromptAnswerInfo
import studio.usergist.feedback.api.PromptAnswerValue
import studio.usergist.feedback.api.PromptResponseInfo
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.model.ClientPrompt
import studio.usergist.feedback.internal.model.Question
import studio.usergist.feedback.internal.ui.questions.MultipleChoiceQuestionView
import studio.usergist.feedback.internal.ui.questions.NpsQuestionView
import studio.usergist.feedback.internal.ui.questions.QuestionView
import studio.usergist.feedback.internal.ui.questions.RatingQuestionView
import studio.usergist.feedback.internal.ui.questions.ShortTextQuestionView
import studio.usergist.feedback.internal.ui.surface.BoundedScrollView
import studio.usergist.feedback.internal.ui.surface.CONTENT_MAX_WIDTH_DP
import studio.usergist.feedback.internal.ui.surface.MeasureLayout
import studio.usergist.feedback.internal.ui.surface.ReservedHeightFrame
import studio.usergist.feedback.internal.ui.surface.SurfaceButton
import studio.usergist.feedback.internal.ui.surface.SurfaceHeader
import studio.usergist.feedback.internal.ui.surface.SurfaceNavigation
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.detach
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.keepFocusedFieldVisible
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.surfaceClose
import studio.usergist.feedback.internal.ui.surface.surfaceText

/**
 * Feedback bottom sheet (port of RN ui/PromptSheet.tsx): one question at a
 * time with tap-to-select auto-advance.
 *
 * The sheet background always spans the full window width with the theme's
 * top radius. The header sits at the safe edges (Close leading, no Back);
 * the question and the full-width Next/Submit footer share one centred
 * 560dp readable column in both orientations.
 */
internal class PromptSheetFragment : BottomSheetDialogFragment() {

    private var questionView: QuestionView? = null
    private var hostTheme: PromptTheme? = null
    private var clientPrompt: ClientPrompt? = null
    private var shownAtMs: Long = 0L
    private var currentIndex = 0
    private val answers = linkedMapOf<String, PromptAnswerValue>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingAdvance: Runnable? = null
    private lateinit var resolvedTheme: ResolvedTheme
    private lateinit var palette: SurfacePalette

    private lateinit var container: ReservedHeightFrame
    private lateinit var header: SurfaceHeader
    private lateinit var questionSlot: FrameLayout
    private lateinit var footer: FrameLayout
    private lateinit var stepView: TextView
    private var nextButton: SurfaceButton? = null
    private var nextVisible = false
    private var nextDisabled = false

    override fun getTheme(): Int = R.style.UserGist_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ReservedHeightFrame(inflater.context).also { this.container = it }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val promptJson = arguments?.getString(ARG_PROMPT_JSON)
        val themeJson = arguments?.getString(ARG_THEME_JSON)
        val json = Json { ignoreUnknownKeys = true }
        val prompt = promptJson?.let {
            runCatching { json.decodeFromString<ClientPrompt>(it) }.getOrNull()
        }
        val theme = themeJson?.let {
            runCatching { json.decodeFromString<HostThemeEnvelope>(it) }.getOrNull()?.toPromptTheme()
        }
        if (prompt == null || prompt.questions.isEmpty()) {
            UserGistLogger.w("PromptSheetFragment: missing or empty prompt payload")
            dismissAllowingStateLoss()
            return
        }
        clientPrompt = prompt
        hostTheme = theme
        shownAtMs = System.currentTimeMillis()
        resolvedTheme = ThemeResolver.merge(prompt.theme, hostTheme)
        palette = SurfacePalette.from(resolvedTheme)

        buildSheet()
        renderCurrentQuestion(animated = false)
        notifyShown(prompt.id)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setDimAmount(0.45f)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            // Our own view draws the themed, top-rounded background.
            it.background = null
            BottomSheetBehavior.from(it).apply {
                maxWidth = ViewGroup.LayoutParams.MATCH_PARENT // full width in landscape too
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        if (::container.isInitialized) {
            val activityInsets = activity?.window?.decorView?.let(ViewCompat::getRootWindowInsets)
            val top = activityInsets
                ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                ?.top ?: 0
            container.reservePx = top + requireContext().dpPx(if (isLandscape()) 12 else 16)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Hosts that handle rotation themselves keep this fragment; rebuild
        // the footer action for the new configuration.
        if (::header.isInitialized) placeNextButton()
    }

    override fun onDestroyView() {
        pendingAdvance?.let(handler::removeCallbacks)
        pendingAdvance = null
        super.onDestroyView()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val prompt = clientPrompt ?: return
        deliverResponse(dismissed = true, answers = orderedAnswers(), promptIdOverride = prompt.id)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        deliveredOnce?.let { /* no-op — avoid double deliver */ }
    }

    // ---------------- Layout ----------------

    private fun buildSheet() {
        val ctx = requireContext()
        val radius = ctx.dpF(palette.radiusDp)
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                setColor(palette.background)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val minBottom = ctx.dpPx(if (isLandscape()) 12 else 16)
            target.setPadding(
                safe.left + ctx.dpPx(12),
                ctx.dpPx(8),
                safe.right + ctx.dpPx(12),
                maxOf(safe.bottom, minBottom),
            )
            insets
        }

        header = SurfaceHeader(ctx)
        header.setLeading(ctx.surfaceClose(palette, getString(R.string.usergist_close)) {
            deliverResponse(dismissed = true, answers = orderedAnswers())
            dismissAllowingStateLoss()
        })
        stepView = ctx.surfaceText(palette, null, 13f, palette.subtext, 600)
        header.setCenter(stepView)
        sheet.addView(header.view, matchWidth())

        // One centred readable column for the question and the portrait footer.
        val measure = MeasureLayout(ctx, ctx.dpPx(CONTENT_MAX_WIDTH_DP)).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = BoundedScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            keepFocusedFieldVisible(ctx.dpPx(12))
        }
        questionSlot = FrameLayout(ctx).apply {
            setPadding(ctx.dpPx(12), ctx.dpPx(4), ctx.dpPx(12), ctx.dpPx(20))
        }
        scroll.addView(questionSlot, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Weighted wrap-content children shrink when the sheet runs out of height,
        // so the footer stays visible and the question scrolls (keyboard included).
        measure.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer = FrameLayout(ctx).apply { setPadding(ctx.dpPx(12), ctx.dpPx(4), ctx.dpPx(12), 0) }
        measure.addView(footer, matchWidth())
        sheet.addView(measure, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        container.addView(sheet, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.requestApplyInsets(sheet)
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Feedback has no Back: Close leads, the trailing slot stays empty, and
     * Next/Submit is one full-width footer action in both orientations.
     */
    private fun placeNextButton() {
        val ctx = context ?: return
        val prompt = clientPrompt ?: return
        val isLast = currentIndex >= prompt.questions.lastIndex
        val button = SurfaceButton(ctx, palette, if (isLast) "Submit" else "Next").apply {
            setDisabled(nextDisabled)
            setOnAction { advance() }
        }
        nextButton?.detach()
        nextButton = button
        val nav = SurfaceNavigation.resolve(canGoBack = false, hasMainAction = nextVisible)
        header.setTrailing()
        footer.removeAllViews()
        footer.visibility = if (nav.footerAction) View.VISIBLE else View.GONE
        if (nav.footerAction) {
            footer.addView(button, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------- Rendering helpers ----------------

    private fun renderCurrentQuestion(animated: Boolean) {
        val prompt = clientPrompt ?: return
        val question = prompt.questions.getOrNull(currentIndex) ?: return
        pendingAdvance?.let(handler::removeCallbacks)
        pendingAdvance = null

        stepView.text = if (prompt.questions.size > 1) "${currentIndex + 1} of ${prompt.questions.size}" else null
        stepView.visibility = if (prompt.questions.size > 1) View.VISIBLE else View.GONE

        val rendered = buildQuestionView(question, resolvedTheme)
        questionView = rendered
        questionSlot.removeAllViews()
        questionSlot.addView(rendered.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        rendered.onValueChange = valueChange@ { answer ->
            if (questionView !== rendered) return@valueChange
            answers[question.id] = answer
            updateNextButton(question)
            if (PromptFlow.shouldAutoAdvance(question)) scheduleAdvance(question.id)
        }
        if (rendered is NpsQuestionView) {
            rendered.onFollowUpChange = { text ->
                val key = "${question.id}__followUp"
                if (text.isBlank()) answers.remove(key) else answers[key] = PromptAnswerValue.Text(text)
            }
        }
        updateNextButton(question)

        if (animated) {
            questionSlot.alpha = 0f
            questionSlot.translationX = requireContext().dpPx(36).toFloat()
            questionSlot.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                .start()
        }
    }

    private fun buildQuestionView(question: Question, theme: ResolvedTheme): QuestionView {
        val ctx = requireContext()
        val previous = answers[question.id] as? PromptAnswerValue.Number
        return when (question) {
            is Question.Rating -> RatingQuestionView(ctx, question, theme)
            is Question.Nps -> NpsQuestionView(ctx, question, theme, initialScore = previous?.value?.toInt())
            is Question.MultipleChoice -> MultipleChoiceQuestionView(ctx, question, theme)
            is Question.ShortText -> ShortTextQuestionView(ctx, question, theme)
        }
    }

    private fun updateNextButton(question: Question) {
        val visible = PromptFlow.needsExplicitNext(question)
        val requiresAnswer = question is Question.ShortText ||
            (question is Question.Nps && !question.followUp.isNullOrEmpty())
        val disabled = requiresAnswer && questionView?.isSubmittable() != true
        val changed = visible != nextVisible || nextButton == null
        nextVisible = visible
        nextDisabled = disabled
        if (changed) placeNextButton() else nextButton?.setDisabled(disabled)
        val prompt = clientPrompt ?: return
        nextButton?.label = if (currentIndex >= prompt.questions.lastIndex) "Submit" else "Next"
    }

    private fun scheduleAdvance(questionId: String) {
        pendingAdvance?.let(handler::removeCallbacks)
        val action = Runnable {
            val current = clientPrompt?.questions?.getOrNull(currentIndex)
            if (current?.id == questionId) advance()
        }
        pendingAdvance = action
        handler.postDelayed(action, 220)
    }

    private fun advance() {
        val prompt = clientPrompt ?: return
        pendingAdvance?.let(handler::removeCallbacks)
        pendingAdvance = null
        if (currentIndex < prompt.questions.lastIndex) {
            currentIndex += 1
            renderCurrentQuestion(animated = true)
            return
        }
        deliverResponse(dismissed = false, answers = orderedAnswers())
        dismissAllowingStateLoss()
    }

    private fun orderedAnswers(): List<PromptAnswerInfo> {
        val prompt = clientPrompt ?: return emptyList()
        return PromptFlow.orderedAnswers(prompt, answers)
    }

    // ---------------- Callback delivery ----------------

    @Volatile
    private var deliveredOnce: Any? = null

    private fun notifyShown(promptId: String) {
        val cb = shownCallback
        if (cb != null) {
            safeExec { cb(promptId) }
        }
    }

    private fun deliverResponse(
        dismissed: Boolean,
        answers: List<PromptAnswerInfo>,
        promptIdOverride: String? = null,
    ) {
        if (deliveredOnce != null) return
        deliveredOnce = Any()
        val prompt = clientPrompt ?: return
        val promptId = promptIdOverride ?: prompt.id
        val info = PromptResponseInfo(
            promptId = promptId,
            answers = answers,
            dismissed = dismissed,
            latencyMs = System.currentTimeMillis() - shownAtMs,
        )
        val cb = responseCallback
        if (cb != null) {
            safeExec { cb(info) }
        }
    }

    private fun safeExec(block: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            try { block() } catch (e: Throwable) {
                UserGistLogger.w("PromptSheetFragment callback threw", e)
            }
        }
    }

    companion object {
        private const val ARG_PROMPT_JSON: String = "usergist.prompt_json"
        private const val ARG_THEME_JSON: String = "usergist.theme_json"

        /**
         * Callbacks are static because the Fragment is instantiated by
         * the OS on config change; we keep them on the presenter's side
         * of the fence so recreation still calls the right handlers.
         */
        @Volatile
        internal var shownCallback: ((String) -> Unit)? = null

        @Volatile
        internal var responseCallback: ((PromptResponseInfo) -> Unit)? = null

        fun newInstance(prompt: ClientPrompt, theme: PromptTheme?): PromptSheetFragment {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val promptJson = json.encodeToString(prompt)
            val themeJson = theme?.let { json.encodeToString(HostThemeEnvelope.from(it)) }
            return PromptSheetFragment().apply {
                arguments = bundleOf(
                    ARG_PROMPT_JSON to promptJson,
                    ARG_THEME_JSON to themeJson,
                )
            }
        }
    }

    @kotlinx.serialization.Serializable
    internal data class HostThemeEnvelope(
        val primary: String? = null,
        val background: String? = null,
        val text: String? = null,
        val subtext: String? = null,
        val border: String? = null,
        val radius: Int? = null,
        val fontFamily: String? = null,
    ) {
        fun toPromptTheme(): PromptTheme = PromptTheme(
            colors = studio.usergist.feedback.api.ThemeColors(
                primary = primary,
                background = background,
                text = text,
                subtext = subtext,
                border = border,
            ),
            radius = radius,
            fontFamily = fontFamily,
        )

        companion object {
            fun from(theme: PromptTheme): HostThemeEnvelope = HostThemeEnvelope(
                primary = theme.colors?.primary,
                background = theme.colors?.background,
                text = theme.colors?.text,
                subtext = theme.colors?.subtext,
                border = theme.colors?.border,
                radius = theme.radius,
                fontFamily = theme.fontFamily,
            )
        }
    }
}
