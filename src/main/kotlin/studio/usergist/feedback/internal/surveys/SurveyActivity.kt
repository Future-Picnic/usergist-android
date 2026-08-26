package studio.usergist.feedback.internal.surveys

import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import studio.usergist.feedback.R
import studio.usergist.feedback.internal.ui.ResolvedTheme
import studio.usergist.feedback.internal.util.DateTime

/** Full-screen native renderer for the server-authored survey graph. */
internal class SurveyActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var hostToken: String
    private lateinit var presentation: SurveyPresentation
    private lateinit var resolvedTheme: ResolvedTheme
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var progressSlot: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var nextButton: MaterialButton
    private lateinit var backButton: ImageButton
    private var validationText: TextView? = null
    private val answers = LinkedHashMap<String, JsonElement>()
    private val history = mutableListOf<String>()
    private var currentQuestionId: String? = null
    private var ranking = mutableListOf<SdkSurveyChoice>()
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
        SurveyHost.attach(hostToken, this)
        restoreState(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
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

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolvedTheme.background ?: resolveColor(
                com.google.android.material.R.attr.colorSurface,
                Color.WHITE,
            ))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(56)
        }
        backButton = ImageButton(this).apply {
            setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            contentDescription = "Back"
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(textColor())
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { goBack() }
        }
        header.addView(backButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.usergist_ic_close)
            contentDescription = getString(R.string.usergist_close)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(textColor())
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { if (ended) finish() else confirmAbandon() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        progressSlot = FrameLayout(this)
        root.addView(progressSlot, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        scroll.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        nextButton = MaterialButton(this).apply {
            text = "Next"
            minHeight = dp(48)
            cornerRadius = dp(12)
            setBackgroundColor(primaryColor())
            setTextColor(contrastOn(primaryColor()))
            setOnClickListener { advance() }
        }
        root.addView(nextButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(dp(24), dp(8), dp(24), dp(20))
        })
        setContentView(root)
    }

    private fun renderQuestion() {
        val question = currentQuestion()
        if (question == null) {
            renderError("This survey is unavailable.")
            return
        }
        content.removeAllViews()
        validationText = null
        renderProgress()
        backButton.visibility = if (canGoBack()) View.VISIBLE else View.INVISIBLE

        question.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            content.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                load(url) { crossfade(true) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(220),
            ).apply { bottomMargin = dp(24) })
        }
        content.addView(TextView(this).apply {
            text = question.title
            textSize = 24f
            setTextColor(textColor())
            typeface = resolvedTypeface(Typeface.BOLD)
        })
        question.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
            content.addView(TextView(this).apply {
                text = subtitle
                textSize = 15f
                setTextColor(subtextColor())
                typeface = resolvedTypeface(Typeface.NORMAL)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        content.addView(buildInput(question), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(24) })
        nextButton.visibility = if (autoAdvances(question.type)) View.GONE else View.VISIBLE
        nextButton.text = if (question.type == "info_screen") "Continue" else "Next"
        nextButton.isEnabled = true
    }

    private fun buildInput(question: SdkSurveyQuestion): View = when (question.type) {
        "single_choice" -> choiceGroup(question, question.options, 0)
        "rating" -> choiceGroup(
            question,
            (1..(question.scale ?: 5)).map { SdkSurveyChoice(it.toString(), it.toString()) },
            1,
        )
        "nps" -> choiceGroup(
            question,
            (0..10).map { SdkSurveyChoice(it.toString(), it.toString()) },
            2,
        )
        "likert" -> choiceGroup(
            question,
            (question.labels.ifEmpty {
                listOf("Strongly disagree", "Disagree", "Neutral", "Agree", "Strongly agree")
            }).mapIndexed { index, label -> SdkSurveyChoice((index + 1).toString(), label) },
            2,
        )
        "multi_choice" -> multiChoice(question)
        "short_text", "long_text" -> textInput(question)
        "ranking" -> rankingInput(question)
        "single_date" -> dateInput(question)
        "info_screen" -> TextView(this).apply {
            text = question.body.orEmpty()
            textSize = 16f
            setTextColor(subtextColor())
        }
        else -> TextView(this).apply {
            text = "Unsupported question type: ${question.type}"
            setTextColor(textColor())
        }
    }

    private fun choiceGroup(
        question: SdkSurveyQuestion,
        choices: List<SdkSurveyChoice>,
        numericMode: Int,
    ): View {
        val current = answers[question.id]
        return RadioGroup(this).apply {
            orientation = if (numericMode == 1) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            choices.forEach { choice ->
                addView(RadioButton(this@SurveyActivity).apply {
                    text = choice.label
                    minHeight = dp(48)
                    isChecked = if (numericMode > 0) {
                        (current as? JsonPrimitive)?.content == choice.id
                    } else {
                        (current as? JsonPrimitive)?.content == choice.id
                    }
                    buttonTintList = ColorStateList.valueOf(primaryColor())
                    setTextColor(textColor())
                    setOnClickListener {
                        answers[question.id] = if (numericMode > 0) {
                            JsonPrimitive(choice.id.toInt())
                        } else {
                            JsonPrimitive(choice.id)
                        }
                        scheduleSave()
                        if (autoAdvances(question.type)) {
                            root.post {
                                if (currentQuestionId == question.id) advance()
                            }
                        }
                    }
                })
            }
        }
    }

    private fun multiChoice(question: SdkSurveyQuestion): View {
        val selected = ((answers[question.id] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()).toMutableSet()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            question.options.forEach { choice ->
                addView(CheckBox(this@SurveyActivity).apply {
                    text = choice.label
                    minHeight = dp(48)
                    isChecked = selected.contains(choice.id)
                    buttonTintList = ColorStateList.valueOf(primaryColor())
                    setTextColor(textColor())
                    setOnCheckedChangeListener { _, checked ->
                        if (checked && question.maxSelections != null &&
                            selected.size >= question.maxSelections
                        ) {
                            isChecked = false
                            return@setOnCheckedChangeListener
                        }
                        if (checked) selected += choice.id else selected -= choice.id
                        answers[question.id] = JsonArray(selected.map(::JsonPrimitive))
                        scheduleSave()
                    }
                })
            }
        }
    }

    private fun textInput(question: SdkSurveyQuestion): View {
        val edit = TextInputEditText(this).apply {
            hint = question.placeholder
            minLines = if (question.type == "long_text") 4 else 1
            maxLines = if (question.type == "long_text") 8 else 3
            question.maxLength?.let { filters = arrayOf(android.text.InputFilter.LengthFilter(it)) }
            setText((answers[question.id] as? JsonPrimitive)?.content.orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    answers[question.id] = JsonPrimitive(s?.toString().orEmpty())
                    scheduleSave()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        return TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            addView(edit)
        }
    }

    private fun rankingInput(question: SdkSurveyQuestion): View {
        val saved = (answers[question.id] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val byId = question.items.associateBy { it.id }
        ranking = if (saved.isEmpty()) {
            question.items.toMutableList()
        } else {
            saved.mapNotNull(byId::get).toMutableList()
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            renderRankingRows(this, question)
        }
    }

    private fun renderRankingRows(container: LinearLayout, question: SdkSurveyQuestion) {
        container.removeAllViews()
        ranking.forEachIndexed { index, item ->
            container.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                addView(TextView(this@SurveyActivity).apply {
                    text = "${index + 1}. ${item.label}"
                    setTextColor(textColor())
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(MaterialButton(this@SurveyActivity).apply {
                    text = "Move up"
                    isEnabled = index > 0
                    setOnClickListener {
                        ranking.add(index - 1, ranking.removeAt(index))
                        saveRanking(question)
                        renderRankingRows(container, question)
                    }
                })
                addView(MaterialButton(this@SurveyActivity).apply {
                    text = "Move down"
                    isEnabled = index < ranking.lastIndex
                    setOnClickListener {
                        ranking.add(index + 1, ranking.removeAt(index))
                        saveRanking(question)
                        renderRankingRows(container, question)
                    }
                })
            })
        }
        saveRanking(question)
    }

    private fun saveRanking(question: SdkSurveyQuestion) {
        answers[question.id] = JsonArray(ranking.map { JsonPrimitive(it.id) })
        scheduleSave()
    }

    private fun dateInput(question: SdkSurveyQuestion): View = MaterialButton(this).apply {
        text = (answers[question.id] as? JsonPrimitive)?.content ?: "Choose a date"
        minHeight = dp(48)
        setOnClickListener {
            val now = DateTime.parseLocalDate(
                (answers[question.id] as? JsonPrimitive)?.content,
            ) ?: DateTime.todayLocalDate()
            val dialog = DatePickerDialog(
                this@SurveyActivity,
                { _, year, month, day ->
                    val selected = DateTime.localDate(year, month, day)?.iso ?: return@DatePickerDialog
                    answers[question.id] = JsonPrimitive(selected)
                    text = selected
                    scheduleSave()
                },
                now.year,
                now.month - 1,
                now.day,
            )
            question.minDate?.let { raw ->
                DateTime.parseLocalDate(raw)?.let { min ->
                    dialog.datePicker.minDate = min.utcStartMillis()
                }
            }
            question.maxDate?.let { raw ->
                DateTime.parseLocalDate(raw)?.let { max ->
                    dialog.datePicker.maxDate = max.utcStartMillis()
                }
            }
            dialog.show()
        }
    }

    private fun advance() {
        if (submitting) return
        val question = currentQuestion() ?: return
        if (question.type == "info_screen") answers[question.id] = JsonPrimitive("")
        val answer = answers[question.id]
        if (question.required && !SurveyFlowEvaluator.isAnswered(answer)) {
            showValidation("Please answer this question to continue.")
            return
        }
        if (question.type == "multi_choice") {
            val count = (answer as? JsonArray)?.size ?: 0
            if (question.minSelections != null && count < question.minSelections) {
                showValidation("Select at least ${question.minSelections} options.")
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
        nextButton.isEnabled = false
        nextButton.text = "Submitting…"
        presentation.onComplete(
            presentation.attempt.attemptId,
            answers.toMap(),
        ) { delivered ->
            runOnUiThread {
                submitting = false
                if (!delivered) {
                    nextButton.isEnabled = true
                    nextButton.text = "Retry"
                    showValidation("Your answers are saved on this device. Reconnect and retry to finish.")
                    return@runOnUiThread
                }
                ended = true
                renderEndScreen()
            }
        }
    }

    private fun renderEndScreen() {
        val end = presentation.survey.endScreen ?: presentation.survey.flow.endScreen
        header.visibility = View.VISIBLE
        backButton.visibility = View.INVISIBLE
        progressSlot.removeAllViews()
        content.removeAllViews()
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.addView(TextView(this).apply {
            text = end?.headline ?: "Thanks for your feedback"
            textSize = 24f
            gravity = Gravity.CENTER
            typeface = resolvedTypeface(Typeface.BOLD)
            setTextColor(textColor())
        })
        end?.body?.let { body ->
            content.addView(TextView(this).apply {
                text = body
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(subtextColor())
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        nextButton.isEnabled = true
        nextButton.text = end?.cta?.label ?: "Close"
        nextButton.setOnClickListener {
            end?.cta?.let(::openEndCta)
            finish()
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
                            showValidation("Unable to save your progress. Please try again.")
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
        progressSlot.removeAllViews()
        val flow = presentation.survey.flow
        if (flow.progressStyle == "none") return
        val index = flow.questions.indexOfFirst { it.id == currentQuestionId }.coerceAtLeast(0)
        if (flow.progressStyle == "dots") {
            val dots = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(10))
            }
            flow.questions.indices.forEach { dot ->
                dots.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (dot <= index) primaryColor() else borderColor())
                    }
                }, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    if (dot > 0) marginStart = dp(6)
                })
            }
            progressSlot.addView(dots)
        } else {
            progressSlot.addView(ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal,
            ).apply {
                max = flow.questions.size.coerceAtLeast(1)
                progress = index + 1
                progressTintList = ColorStateList.valueOf(primaryColor())
                progressBackgroundTintList = ColorStateList.valueOf(borderColor())
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4),
            ))
        }
    }

    private fun showValidation(message: String) {
        validationText?.let {
            it.text = message
            it.announceForAccessibility(message)
            return
        }
        validationText = TextView(this).apply {
            text = message
            setTextColor(resolveColor(com.google.android.material.R.attr.colorError, Color.RED))
            announceForAccessibility(message)
        }
        content.addView(validationText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
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
        content.addView(TextView(this).apply {
            text = message
            setTextColor(textColor())
        })
        nextButton.visibility = View.GONE
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val typed = android.util.TypedValue()
        return if (this@SurveyActivity.theme.resolveAttribute(attr, typed, true)) {
            typed.data
        } else {
            fallback
        }
    }

    private fun primaryColor(): Int = resolvedTheme.primary
        ?: resolveColor(com.google.android.material.R.attr.colorPrimary, Color.BLACK)

    private fun textColor(): Int = resolvedTheme.text
        ?: resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)

    private fun subtextColor(): Int = resolvedTheme.subtext
        ?: resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)

    private fun borderColor(): Int = resolvedTheme.border
        ?: resolveColor(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)

    private fun resolvedTypeface(style: Int): Typeface =
        resolvedTheme.fontFamily?.let { Typeface.create(it, style) }
            ?: Typeface.create(Typeface.DEFAULT, style)

    private fun contrastOn(color: Int): Int =
        if (ColorUtils.calculateLuminance(color) < 0.45) Color.WHITE else Color.BLACK

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val SAVE_TOKEN = Any()
        private const val STATE_ANSWERS = "usergist.survey.answers"
        private const val STATE_HISTORY = "usergist.survey.history"
        private const val STATE_QUESTION_ID = "usergist.survey.questionId"
        private const val STATE_ENDED = "usergist.survey.ended"
    }
}
