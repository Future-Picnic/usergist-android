package studio.usergist.feedback.internal.ui

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
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

/**
 * React Native-parity feedback prompt: one question at a time with
 * tap-to-select auto-advance and an explicit close affordance.
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
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var imageView: ImageView
    private lateinit var questionSlot: FrameLayout
    private lateinit var nextButton: MaterialButton

    override fun getTheme(): Int = R.style.UserGist_BottomSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.usergist_prompt_sheet, container, false)

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

        titleView = view.findViewById(R.id.usergist_title)
        subtitleView = view.findViewById(R.id.usergist_subtitle)
        imageView = view.findViewById(R.id.usergist_question_image)
        questionSlot = view.findViewById(R.id.usergist_question_slot)
        nextButton = view.findViewById(R.id.usergist_next)
        val closeButton = view.findViewById<AppCompatImageButton>(R.id.usergist_close)
        val handleView = view.findViewById<View>(R.id.usergist_handle)
        val sheetRoot = view.findViewById<LinearLayout>(R.id.usergist_prompt_root)

        resolvedTheme = ThemeResolver.merge(prompt.theme, hostTheme)
        applyTheme(sheetRoot, resolvedTheme, titleView, subtitleView, nextButton, closeButton, handleView)

        nextButton.setOnClickListener { advance() }
        closeButton.setOnClickListener {
            deliverResponse(dismissed = true, answers = orderedAnswers())
            dismissAllowingStateLoss()
        }

        renderCurrentQuestion(animated = false)

        notifyShown(prompt.id)
    }

    override fun onStart() {
        super.onStart()
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            BottomSheetBehavior.from(it).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
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

    // ---------------- Rendering helpers ----------------

    private fun renderCurrentQuestion(animated: Boolean) {
        val prompt = clientPrompt ?: return
        val question = prompt.questions.getOrNull(currentIndex) ?: return
        pendingAdvance?.let(handler::removeCallbacks)
        pendingAdvance = null

        titleView.text = question.title
        val centered = question is Question.Rating || question is Question.Nps
        titleView.gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        subtitleView.gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        question.subtitle?.takeIf { it.isNotBlank() }?.let {
            subtitleView.text = it
            subtitleView.visibility = View.VISIBLE
        } ?: run {
            subtitleView.text = null
            subtitleView.visibility = View.GONE
        }
        renderImage(question)

        val rendered = buildQuestionView(question, resolvedTheme)
        questionView = rendered
        questionSlot.removeAllViews()
        questionSlot.addView(rendered.view)
        val slotParams = questionSlot.layoutParams as LinearLayout.LayoutParams
        slotParams.topMargin = dpToPx(spacingBeforeControl(question, !question.subtitle.isNullOrBlank()))
        questionSlot.layoutParams = slotParams

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
            questionSlot.translationX = dpToPx(36).toFloat()
            questionSlot.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(240)
                .start()
        }
    }

    private fun buildQuestionView(question: Question, theme: ResolvedTheme): QuestionView {
        val ctx = requireContext()
        return when (question) {
            is Question.Rating -> RatingQuestionView(ctx, question, theme)
            is Question.Nps -> NpsQuestionView(ctx, question, theme)
            is Question.MultipleChoice -> MultipleChoiceQuestionView(ctx, question, theme)
            is Question.ShortText -> ShortTextQuestionView(ctx, question, theme)
        }
    }

    private fun renderImage(question: Question) {
        val url = when (question) {
            is Question.Rating -> question.imageUrl
            is Question.Nps -> question.imageUrl
            is Question.MultipleChoice -> question.imageUrl
            is Question.ShortText -> question.imageUrl
        }
        if (url.isNullOrBlank()) {
            imageView.visibility = View.GONE
            imageView.setImageDrawable(null)
            return
        }
        imageView.visibility = View.VISIBLE
        imageView.doOnLayout { image ->
            val params = image.layoutParams
            val desired = image.width * 9 / 16
            if (params.height != desired) {
                params.height = desired
                image.layoutParams = params
            }
        }
        imageView.background = GradientDrawable().apply {
            cornerRadius = (resolvedTheme.radiusDp ?: 20) * resources.displayMetrics.density
            setColor(Color.TRANSPARENT)
        }
        imageView.clipToOutline = true
        imageView.load(url) { crossfade(true) }
    }

    private fun updateNextButton(question: Question) {
        val visible = PromptFlow.needsExplicitNext(question)
        nextButton.visibility = if (visible) View.VISIBLE else View.GONE
        val requiresAnswer = question is Question.ShortText ||
            (question is Question.Nps && !question.followUp.isNullOrEmpty())
        val enabled = !requiresAnswer || questionView?.isSubmittable() == true
        nextButton.isEnabled = enabled
        nextButton.alpha = if (enabled) 1f else 0.4f
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

    private fun spacingBeforeControl(question: Question, hasSubtitle: Boolean): Int = when (question) {
        is Question.Rating -> if (hasSubtitle) 24 else 12
        is Question.Nps -> if (hasSubtitle) 24 else 16
        is Question.MultipleChoice -> if (hasSubtitle) 16 else 12
        is Question.ShortText -> if (hasSubtitle) 12 else 4
    }

    private fun applyTheme(
        root: View,
        theme: ResolvedTheme,
        title: TextView,
        subtitle: TextView,
        nextBtn: MaterialButton,
        closeBtn: AppCompatImageButton,
        handle: View,
    ) {
        val radiusPx = theme.radiusDp?.let { dpToPx(it) } ?: dpToPx(16)
        val bg = theme.background ?: Color.WHITE
        val shape = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                radiusPx.toFloat(), radiusPx.toFloat(),
                radiusPx.toFloat(), radiusPx.toFloat(),
                0f, 0f,
                0f, 0f,
            )
            setColor(bg)
        }
        root.background = shape
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            target.updatePadding(bottom = maxOf(dpToPx(30), systemBottom) + dpToPx(16))
            insets
        }
        ViewCompat.requestApplyInsets(root)

        theme.text?.let {
            title.setTextColor(it)
            closeBtn.imageTintList = ColorStateList.valueOf(it)
        }
        theme.subtext?.let { subtitle.setTextColor(it) }
        theme.primary?.let {
            nextBtn.backgroundTintList = ColorStateList.valueOf(it)
        }
        nextBtn.setTextColor(Color.WHITE)
        nextBtn.cornerRadius = dpToPx(26)
        val family = Typeface.create(theme.fontFamily, Typeface.NORMAL)
        title.typeface = Typeface.create(theme.fontFamily, Typeface.BOLD)
        subtitle.typeface = family
        nextBtn.typeface = Typeface.create(theme.fontFamily, Typeface.BOLD)
        handle.background = GradientDrawable().apply {
            cornerRadius = dpToPx(2).toFloat()
            setColor(theme.border ?: Color.rgb(229, 229, 229))
        }
        closeBtn.background = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(Color.argb(15, 0, 0, 0))
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
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
