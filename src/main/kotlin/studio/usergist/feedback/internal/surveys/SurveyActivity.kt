package studio.usergist.feedback.internal.surveys

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import studio.usergist.feedback.R
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.ui.surface.CONTENT_MAX_WIDTH_DP
import studio.usergist.feedback.internal.ui.surface.Glyph
import studio.usergist.feedback.internal.ui.surface.GlyphView
import studio.usergist.feedback.internal.ui.surface.HeaderControl
import studio.usergist.feedback.internal.ui.surface.SurfaceNavigation
import studio.usergist.feedback.internal.ui.surface.MeasureLayout
import studio.usergist.feedback.internal.ui.surface.SurfaceButton
import studio.usergist.feedback.internal.ui.surface.SurfaceHeader
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.accessibilityHeading
import studio.usergist.feedback.internal.ui.surface.detach
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.keepFocusedFieldVisible
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.oval
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceBack
import studio.usergist.feedback.internal.ui.surface.surfaceClose
import studio.usergist.feedback.internal.ui.surface.surfaceText

/**
 * Full-screen native renderer for the server-authored survey graph
 * (port of RN ui/SurveyView.tsx). Header (both orientations): Back leading +
 * Close trailing when Back is usable, otherwise Close leading; progress sits
 * in the centre. Next/Submit (and the end-screen CTA) is one full-width
 * footer action in the centred 560dp column, outside the scroll and above
 * the gesture bar and keyboard.
 */
internal class SurveyActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hostToken: String
    private lateinit var presentation: SurveyPresentation
    private lateinit var resolvedTheme: ResolvedTheme
    private lateinit var palette: SurfacePalette
    private lateinit var root: LinearLayout
    private lateinit var header: SurfaceHeader
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var footer: FrameLayout
    private lateinit var footerColumn: MeasureLayout
    private lateinit var headerColumn: MeasureLayout
    private lateinit var headerFrame: FrameLayout
    private lateinit var closeButton: View
    private var nextButton: SurfaceButton? = null
    private var noticeView: View? = null
    private val answers = LinkedHashMap<String, JsonElement>()
    private val history = mutableListOf<String>()
    private var currentQuestionId: String? = null
    private var submitting = false
    private var ended = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = SurveyHost.take(intent)
        if (resolved == null) {
            finish()
            return
        }
        hostToken = resolved.token
        presentation = resolved.presentation
        resolvedTheme = presentation.theme
        palette = SurfacePalette.from(resolvedTheme)
        SurveyHost.attach(hostToken, this)
        restoreState(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.lightBackground
            isAppearanceLightNavigationBars = palette.lightBackground
        }
        buildShell()
        if (ended) renderEndScreen() else renderQuestion()
        onBackPressedDispatcher.addCallback(this) {
            if (ended) finish() else if (canGoBack()) goBack() else confirmAbandon()
        }
        if (SurveyHost.markShown(hostToken)) presentation.onShown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ANSWERS, JsonObject(answers).toString())
        outState.putStringArrayList(STATE_HISTORY, ArrayList(history))
        outState.putString(STATE_QUESTION_ID, currentQuestionId)
        outState.putBoolean(STATE_ENDED, ended)
        if (!ended && ::presentation.isInitialized) {
            presentation.onProgress(
                presentation.attempt.attemptId,
                currentQuestionId,
                answers.toMap(),
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::hostToken.isInitialized && !isChangingConfigurations) {
            SurveyHost.release(hostToken)
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Only reached when a host declares configChanges; otherwise the
        // activity is recreated and restored from saved state.
        if (::root.isInitialized) {
            applyBodyPadding()
            placeActions()
        }
    }

    // ---------------- Shell ----------------

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        header = SurfaceHeader(this)
        closeButton = surfaceClose(palette, getString(R.string.usergist_close)) {
            if (ended) finish() else confirmAbandon()
        }
        header.setLeading(closeButton)
        headerColumn = MeasureLayout(this, 0).apply {
            orientation = LinearLayout.VERTICAL
            addView(header.view, matchWidth())
        }
        headerFrame = FrameLayout(this).apply {
            setPadding(dpPx(12), dpPx(if (isLandscape()) 8 else 4), dpPx(12), dpPx(4))
            addView(headerColumn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        root.addView(headerFrame, matchWidth())

        scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            keepFocusedFieldVisible(dpPx(16))
        }
        content = MeasureLayout(this, dpPx(CONTENT_MAX_WIDTH_DP)).apply { orientation = LinearLayout.VERTICAL }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        applyBodyPadding()
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Footer sits outside the scroll, above the keyboard (root IME padding),
        // in the same centred readable column as the question.
        footerColumn = MeasureLayout(this, dpPx(CONTENT_MAX_WIDTH_DP)).apply { orientation = LinearLayout.VERTICAL }
        footer = FrameLayout(this).apply {
            setPadding(dpPx(24), dpPx(8), dpPx(24), dpPx(16))
            addView(footerColumn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        root.addView(footer, matchWidth())
        setContentView(root)
    }

    private fun applyBodyPadding() {
        val landscape = isLandscape()
        body.setPadding(dpPx(24), dpPx(if (landscape) 8 else 24), dpPx(24), dpPx(if (landscape) 24 else 40))
    }

    /**
     * Landscape shares one aligned header row; portrait retains its footer.
     */
    private fun placeActions() {
        val landscape = isLandscape()
        val back = canGoBack() && !ended && !submitting
        val question = currentQuestion()
        val main: SurfaceButton? = when {
            ended -> endAction()
            question != null && !autoAdvances(question.type) -> buildNext(question)
            else -> null
        }
        nextButton = if (ended) null else main
        val nav = SurfaceNavigation.resolve(
            canGoBack = canGoBack() && !ended && !submitting,
            hasMainAction = main != null,
            landscape = landscape,
        )
        footerColumn.removeAllViews()
        header.balancedSides = landscape
        headerColumn.maxWidthPx = if (landscape) dpPx(CONTENT_MAX_WIDTH_DP) else 0
        headerColumn.requestLayout()
        headerFrame.setPadding(dpPx(if (landscape) 24 else 12), dpPx(if (landscape) 8 else 4), dpPx(if (landscape) 24 else 12), dpPx(4))
        closeButton = surfaceClose(palette, getString(R.string.usergist_close), regular = landscape) {
            if (ended) finish() else confirmAbandon()
        }
        if (landscape) {
            header.setLeading(closeButton, if (back) surfaceBack(palette, regular = true) { goBack() } else null)
            header.setTrailing(if (nav.headerAction) main else null)
        } else {
            header.setLeading(headerControl(nav.leading))
            header.setTrailing(headerControl(nav.trailing))
        }
        footer.visibility = if (nav.footerAction) View.VISIBLE else View.GONE
        if (nav.footerAction && main != null) footerColumn.addView(main.detach(), matchWidth())
    }

    private fun headerControl(control: HeaderControl): View? = when (control) {
        HeaderControl.BACK -> surfaceBack(palette) { goBack() }
        HeaderControl.CLOSE -> closeButton
        HeaderControl.NONE -> null
    }

    private fun buildNext(question: SdkSurveyQuestion): SurfaceButton {
        val final = SurveyFlowEvaluator.nextQuestionId(presentation.survey.flow, question.id, answers) == null
        return SurfaceButton(this, palette, if (final) "Submit" else "Next", compact = isLandscape()).apply {
            loading = submitting
            setDisabled(nextDisabled(question))
            setOnAction { advance() }
        }
    }

    private fun nextDisabled(question: SdkSurveyQuestion): Boolean {
        val text = question.type == "short_text" || question.type == "long_text"
        val needsAnswer = text || (question.required && question.type != "info_screen")
        return needsAnswer && !SurveyFlowEvaluator.isAnswered(answers[question.id])
    }

    /** Keeps the Next/Submit label and enabled state in step with the answer. */
    private fun refreshNext() {
        val question = currentQuestion() ?: return
        val button = nextButton ?: return
        button.setDisabled(nextDisabled(question))
        val final = SurveyFlowEvaluator.nextQuestionId(presentation.survey.flow, question.id, answers) == null
        button.label = if (final) "Submit" else "Next"
    }

    // ---------------- Questions ----------------

    private fun renderQuestion() {
        val question = currentQuestion()
        if (question == null) {
            renderError("This survey is unavailable.")
            return
        }
        content.removeAllViews()
        noticeView = null
        renderProgress()
        val inputs = SurveyInputs(
            context = this,
            theme = resolvedTheme,
            palette = palette,
            answers = answers,
            onChange = { scheduleSave() },
            onAutoAdvance = { id, delayed ->
                if (autoAdvances(question.type)) {
                    val step = Runnable { if (currentQuestionId == id) advance() }
                    if (delayed) handler.postDelayed(step, RATING_ADVANCE_DELAY_MS) else root.post(step)
                }
            },
        )
        content.addView(inputs.build(question), matchWidth())
        placeActions()
        scroll.scrollTo(0, 0)
        content.alpha = 0f
        content.translationX = dpPx(36).toFloat()
        content.animate().alpha(1f).translationX(0f).setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
    }

    private fun advance() {
        if (submitting) return
        val question = currentQuestion() ?: return
        if (question.type == "info_screen") answers[question.id] = JsonPrimitive("")
        val answer = answers[question.id]
        if (question.required && !SurveyFlowEvaluator.isAnswered(answer)) {
            showNotice("Please answer this question to continue.")
            return
        }
        if (question.type == "multi_choice") {
            val count = (answer as? kotlinx.serialization.json.JsonArray)?.size ?: 0
            if (question.minSelections != null && count < question.minSelections) {
                showNotice("Select at least ${question.minSelections} options.")
                return
            }
        }
        val next = SurveyFlowEvaluator.nextQuestionId(
            presentation.survey.flow,
            question.id,
            answers,
        )
        if (next == null) {
            completeSurvey()
            return
        }
        history += question.id
        currentQuestionId = next
        scheduleSave()
        renderQuestion()
    }

    private fun completeSurvey() {
        submitting = true
        clearNotice()
        placeActions()
        presentation.onComplete(
            presentation.attempt.attemptId,
            answers.toMap(),
        ) { delivered ->
            runOnUiThread {
                submitting = false
                if (!delivered) {
                    placeActions()
                    showNotice(
                        "Your answers are saved on this device. Reconnect and try again to finish.",
                        SurfaceButton(this, palette, "Try again", secondary = true, compact = true).apply {
                            setOnAction { advance() }
                        },
                    )
                    return@runOnUiThread
                }
                ended = true
                renderEndScreen()
            }
        }
    }

    private fun renderEndScreen() {
        val end = presentation.survey.endScreen ?: presentation.survey.flow.endScreen
        header.setCenter(null)
        content.removeAllViews()
        noticeView = null
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpPx(24), 0, dpPx(24))
            addView(FrameLayout(this@SurveyActivity).apply {
                background = oval(palette.tint)
                addView(GlyphView(this@SurveyActivity, Glyph.TICK, palette.primary),
                    FrameLayout.LayoutParams(dpPx(36), dpPx(36), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dpPx(72), dpPx(72)).apply { bottomMargin = dpPx(20) })
            addView(surfaceText(palette, end?.headline ?: "Thanks!", 24f, palette.text, 700, 30f, Gravity.CENTER).apply {
                letterSpacing = -0.012f
                accessibilityHeading()
            }, matchWidth())
            end?.body?.takeIf { it.isNotBlank() }?.let { text ->
                addView(MeasureLayout(this@SurveyActivity, dpPx(420)).apply {
                    addView(surfaceText(palette, text, 16f, palette.subtext, 400, 24f, Gravity.CENTER), matchWidth())
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpPx(10)
                    gravity = Gravity.CENTER_HORIZONTAL
                })
            }
        }, matchWidth())
        body.gravity = Gravity.CENTER_VERTICAL
        placeActions()
    }

    private fun endAction(): SurfaceButton {
        val end = presentation.survey.endScreen ?: presentation.survey.flow.endScreen
        return SurfaceButton(this, palette, end?.cta?.label ?: "Done", compact = isLandscape()).apply {
            setOnAction {
                end?.cta?.let(::openEndCta)
                finish()
            }
        }
    }

    private fun openEndCta(cta: SdkSurveyEndCta) {
        if (cta.kind != "url" && cta.kind != "deep_link") return
        val target = cta.target?.takeIf { it.isNotBlank() } ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
    }

    private fun confirmAbandon() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Leave this survey?")
            .setMessage("Your current answers will be saved, but the survey will be marked abandoned.")
            .setNegativeButton("Keep answering", null)
            .setPositiveButton("Leave survey") { _, _ ->
                presentation.onAbandon(presentation.attempt.attemptId) { persisted ->
                    runOnUiThread {
                        if (persisted) {
                            finish()
                        } else {
                            showNotice("Your progress wasn't saved. Try leaving again.")
                        }
                    }
                }
            }
            .show()
    }

    private fun goBack() {
        if (!canGoBack()) return
        currentQuestionId = history.removeLast()
        scheduleSave()
        renderQuestion()
    }

    private fun canGoBack(): Boolean = presentation.survey.flow.backNavigation && history.isNotEmpty()

    private fun autoAdvances(type: String): Boolean =
        type == "single_choice" || type == "rating" || type == "nps" || type == "likert"

    private fun currentQuestion(): SdkSurveyQuestion? = presentation.survey.flow.questions
        .firstOrNull { it.id == currentQuestionId }

    private fun scheduleSave() {
        if (!ended) refreshNext()
        handler.removeCallbacksAndMessages(SAVE_TOKEN)
        handler.postAtTime(
            {
                presentation.onProgress(
                    presentation.attempt.attemptId,
                    currentQuestionId,
                    answers.toMap(),
                )
            },
            SAVE_TOKEN,
            android.os.SystemClock.uptimeMillis() + 600,
        )
    }

    private fun renderProgress() {
        val flow = presentation.survey.flow
        if (flow.progressStyle == "none") {
            header.setCenter(null)
            return
        }
        val index = flow.questions.indexOfFirst { it.id == currentQuestionId }.coerceAtLeast(0)
        if (flow.progressStyle == "dots") {
            header.setCenter(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Question ${index + 1} of ${flow.questions.size}"
                flow.questions.indices.forEach { dot ->
                    addView(View(this@SurveyActivity).apply {
                        background = roundRect(if (dot <= index) palette.primary else palette.fillStrong, dpPx(3).toFloat())
                    }, LinearLayout.LayoutParams(dpPx(if (dot == index) 18 else 6), dpPx(6)).apply {
                        if (dot > 0) marginStart = dpPx(6)
                    })
                }
            })
            return
        }
        val fraction = SurveyProgress.fraction(flow.questions.size, index)
        val track = MeasureLayout(this, dpPx(320)).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(palette.fillStrong, dpPx(3).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Question ${index + 1} of ${flow.questions.size}"
            addView(View(this@SurveyActivity).apply {
                background = roundRect(palette.primary, dpPx(3).toFloat())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fraction))
            addView(View(this@SurveyActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fraction))
        }
        header.setCenter(FrameLayout(this).apply {
            setPadding(dpPx(8), 0, dpPx(8), 0)
            addView(track, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpPx(6), Gravity.CENTER))
        }, fill = true)
    }

    private fun showNotice(message: String, action: View? = null) {
        clearNotice()
        val notice = surveyNotice(palette, message, action)
        noticeView = notice
        content.addView(notice, matchWidth().apply { topMargin = dpPx(20) })
        notice.announceForAccessibility(message)
        scroll.post { scroll.smoothScrollTo(0, notice.bottom) }
    }

    private fun clearNotice() {
        noticeView?.let { content.removeView(it) }
        noticeView = null
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        answers.clear()
        answers.putAll(presentation.attempt.progressSnapshot)
        currentQuestionId = presentation.attempt.currentQuestionId
            ?: presentation.attempt.startQuestionId
        if (savedInstanceState == null) return

        val savedAnswers = savedInstanceState.getString(STATE_ANSWERS)
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
        if (savedAnswers != null) {
            answers.clear()
            answers.putAll(savedAnswers)
        }
        history.clear()
        history.addAll(savedInstanceState.getStringArrayList(STATE_HISTORY).orEmpty())
        currentQuestionId = savedInstanceState.getString(STATE_QUESTION_ID)
            ?: currentQuestionId
        ended = savedInstanceState.getBoolean(STATE_ENDED, false)
    }

    private fun renderError(message: String) {
        content.removeAllViews()
        content.addView(surfaceText(palette, message, 16f, palette.text, 400, 24f), matchWidth())
        nextButton = null
        header.setLeading(closeButton)
        header.setTrailing()
        footerColumn.removeAllViews()
        footer.visibility = View.GONE
    }

    companion object {
        private const val RATING_ADVANCE_DELAY_MS = 220L
        private val SAVE_TOKEN = Any()
        private const val STATE_ANSWERS = "usergist.survey.answers"
        private const val STATE_HISTORY = "usergist.survey.history"
        private const val STATE_QUESTION_ID = "usergist.survey.questionId"
        private const val STATE_ENDED = "usergist.survey.ended"
    }
}

/** Progress fraction for the bar: position of the current step (min one step shown). */
internal object SurveyProgress {
    fun fraction(total: Int, index: Int): Float {
        if (total <= 0) return 0f
        return ((index + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}
