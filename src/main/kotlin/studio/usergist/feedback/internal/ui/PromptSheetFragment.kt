package studio.usergist.feedback.internal.ui

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.os.bundleOf
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
 * Bottom-sheet fragment that renders a single-question feedback prompt.
 *
 * v1 renders only the first question in the list — long / branching
 * flows belong to the survey pillar (DEV_PRD §7).
 */
internal class PromptSheetFragment : BottomSheetDialogFragment() {

    private var questionView: QuestionView? = null
    private var hostTheme: PromptTheme? = null
    private var clientPrompt: ClientPrompt? = null
    private var shownAtMs: Long = 0L

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

        val titleView = view.findViewById<TextView>(R.id.usergist_title)
        val subtitleView = view.findViewById<TextView>(R.id.usergist_subtitle)
        val slot = view.findViewById<FrameLayout>(R.id.usergist_question_slot)
        val submitBtn = view.findViewById<MaterialButton>(R.id.usergist_submit)
        val dismissBtn = view.findViewById<MaterialButton>(R.id.usergist_dismiss)

        val question = prompt.questions.first()
        titleView.text = question.title
        val subtitle = question.subtitle
        if (!subtitle.isNullOrBlank()) {
            subtitleView.text = subtitle
            subtitleView.visibility = View.VISIBLE
        }

        val rendered = buildQuestionView(question)
        questionView = rendered
        slot.addView(rendered.view)

        val resolved = ThemeResolver.merge(prompt.theme, hostTheme)
        applyTheme(view, resolved, titleView, subtitleView, submitBtn, dismissBtn)

        submitBtn.setOnClickListener {
            val q = questionView ?: return@setOnClickListener
            if (!q.isSubmittable()) return@setOnClickListener
            deliverResponse(dismissed = false, value = q.currentAnswer())
            dismissAllowingStateLoss()
        }
        dismissBtn.setOnClickListener { dismissAllowingStateLoss() }

        notifyShown(prompt.id)
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val prompt = clientPrompt ?: return
        deliverResponse(
            dismissed = true,
            value = questionView?.currentAnswer() ?: PromptAnswerValue.None,
            promptIdOverride = prompt.id,
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        deliveredOnce?.let { /* no-op — avoid double deliver */ }
    }

    // ---------------- Rendering helpers ----------------

    private fun buildQuestionView(question: Question): QuestionView {
        val ctx = requireContext()
        return when (question) {
            is Question.Rating -> RatingQuestionView(ctx, question)
            is Question.Nps -> NpsQuestionView(ctx, question)
            is Question.MultipleChoice -> MultipleChoiceQuestionView(ctx, question)
            is Question.ShortText -> ShortTextQuestionView(ctx, question)
        }
    }

    private fun applyTheme(
        root: View,
        theme: ResolvedTheme,
        title: TextView,
        subtitle: TextView,
        submitBtn: MaterialButton,
        dismissBtn: MaterialButton,
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

        theme.text?.let {
            title.setTextColor(it)
        }
        theme.subtext?.let { subtitle.setTextColor(it) }
        theme.primary?.let {
            submitBtn.setBackgroundColor(it)
        }
        theme.subtext?.let { dismissBtn.setTextColor(it) }
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
        value: PromptAnswerValue,
        promptIdOverride: String? = null,
    ) {
        if (deliveredOnce != null) return
        deliveredOnce = Any()
        val prompt = clientPrompt ?: return
        val promptId = promptIdOverride ?: prompt.id
        val qId = prompt.questions.firstOrNull()?.id ?: return
        val answer = PromptAnswerInfo(questionId = qId, value = value)
        val info = PromptResponseInfo(
            promptId = promptId,
            answers = listOf(answer),
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
