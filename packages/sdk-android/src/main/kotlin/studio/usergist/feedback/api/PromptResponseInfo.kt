package studio.usergist.feedback.api

/**
 * A decoded value answering a single question.
 *
 * Delivered via [studio.usergist.feedback.UserGist.onResponse] so integrators
 * can switch on it exhaustively rather than handling `Any`.
 */
sealed class PromptAnswerValue {
    /** Numeric answer (rating / NPS). */
    data class Number(val value: Double) : PromptAnswerValue()

    /** Free text answer. */
    data class Text(val value: String) : PromptAnswerValue()

    /** Multi-select / single-select choice IDs. */
    data class Choices(val ids: List<String>) : PromptAnswerValue()

    /** User dismissed the question without answering. */
    data object None : PromptAnswerValue()
}

/** A single question answer included in a prompt response. */
data class PromptAnswerInfo(
    val questionId: String,
    val value: PromptAnswerValue,
)

/**
 * Structured information about a submitted prompt response.
 *
 * Delivered via [studio.usergist.feedback.UserGist.onResponse] when the user
 * completes or dismisses a feedback prompt.
 */
data class PromptResponseInfo(
    val promptId: String,
    val answers: List<PromptAnswerInfo>,
    val dismissed: Boolean,
    val latencyMs: Long,
)
