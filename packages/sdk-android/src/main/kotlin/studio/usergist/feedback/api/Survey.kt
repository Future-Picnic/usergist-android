package studio.usergist.feedback.api

/** Summary of a survey currently available to the user. */
data class SurveySummary(
    val id: String,
    val name: String,
    val mode: String,
    val source: String,
    val resumableAttemptId: String? = null,
)

/** Lifecycle callbacks for surveys handled by the host app. */
data class SurveyHandlers(
    val onInvite: ((SurveySummary) -> Unit)? = null,
    val onShow: ((surveyId: String) -> Unit)? = null,
    val onComplete: ((surveyId: String, attemptId: String) -> Unit)? = null,
    val onAbandon: ((surveyId: String, attemptId: String) -> Unit)? = null,
)
