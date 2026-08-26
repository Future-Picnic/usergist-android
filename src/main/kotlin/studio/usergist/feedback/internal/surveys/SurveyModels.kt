package studio.usergist.feedback.internal.surveys

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import studio.usergist.feedback.internal.model.WirePromptTheme
import studio.usergist.feedback.internal.model.SerializedSegmentRules

@Serializable
internal data class ArmedSurvey(
    val campaignId: String,
    val eventName: String,
    val segmentRules: SerializedSegmentRules? = null,
    val clientSideEligible: Boolean? = null,
    val cooldownSeconds: Long? = null,
    val frequencyCap: SurveyFrequencyCap = SurveyFrequencyCap(),
    val survey: SdkSurveyCampaignWithFlow,
)

@Serializable
internal data class SurveyFrequencyCap(
    val perCampaignDays: Int? = null,
    val perPillarDays: Int? = null,
    val perGlobalDays: Int? = null,
    val maxPerUser: Int? = null,
)

@Serializable
internal data class ArmedSurveysResponse(
    val surveys: List<ArmedSurvey> = emptyList(),
    val serverTime: String? = null,
    val nextSyncMs: Long? = null,
)

@Serializable
internal data class SdkSurveyCampaignWithFlow(
    val id: String,
    val name: String,
    val flow: SdkSurveyFlow,
    val endScreen: SdkSurveyEndScreen? = null,
    val theme: WirePromptTheme? = null,
)

@Serializable
internal data class SdkSurveyFlow(
    val startQuestionId: String,
    val questions: List<SdkSurveyQuestion>,
    val branches: List<SdkSurveyBranch> = emptyList(),
    val progressStyle: String = "bar",
    val backNavigation: Boolean = true,
    val endScreen: SdkSurveyEndScreen? = null,
)

@Serializable
internal data class SdkSurveyQuestion(
    val id: String,
    val type: String,
    val title: String,
    val subtitle: String? = null,
    val required: Boolean = false,
    val imageUrl: String? = null,
    val options: List<SdkSurveyChoice> = emptyList(),
    val items: List<SdkSurveyChoice> = emptyList(),
    val allowOther: Boolean = false,
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
    val scale: Int? = null,
    val style: String? = null,
    val lowLabel: String? = null,
    val highLabel: String? = null,
    val labels: List<String> = emptyList(),
    val placeholder: String? = null,
    val maxLength: Int? = null,
    val minDate: String? = null,
    val maxDate: String? = null,
    val body: String? = null,
)

@Serializable
internal data class SdkSurveyChoice(val id: String, val label: String)

@Serializable
internal data class SdkSurveyBranch(
    val fromQuestionId: String,
    val condition: SdkSurveyBranchCondition,
    val toQuestionId: String,
)

@Serializable
internal data class SdkSurveyBranchCondition(
    val op: String,
    val value: JsonElement? = null,
)

@Serializable
internal data class SdkSurveyEndScreen(
    val headline: String = "Thanks for your feedback",
    val body: String? = null,
    val cta: SdkSurveyEndCta? = null,
)

@Serializable
internal data class SdkSurveyEndCta(
    val kind: String = "close",
    val label: String = "Close",
    val target: String? = null,
)

@Serializable
internal data class SdkSurveyAttemptRequest(
    val anonymousId: String,
    val externalId: String? = null,
    val source: String,
    val language: String? = null,
    val resume: Boolean = true,
    val sdkVersion: String,
    val appVersion: String? = null,
    val platform: String = "android",
)

@Serializable
internal data class SdkSurveyAttemptSession(
    val attemptId: String,
    val startQuestionId: String,
    val progressSnapshot: Map<String, JsonElement> = emptyMap(),
    val currentQuestionId: String? = null,
    val resumed: Boolean = false,
)

@Serializable
internal data class SdkSurveyProgress(
    val currentQuestionId: String?,
    val progressSnapshot: Map<String, JsonElement>,
)

@Serializable
internal data class SdkSurveyCompleteBody(
    val finalAnswers: List<SdkSurveyAnswer>,
)

@Serializable
internal data class SdkSurveyAnswer(
    val questionId: String,
    val value: JsonElement,
)

internal object SurveyFlowEvaluator {
    fun nextQuestionId(
        flow: SdkSurveyFlow,
        currentQuestionId: String,
        answers: Map<String, JsonElement>,
    ): String? {
        val answer = answers[currentQuestionId]
        for (branch in flow.branches.filter { it.fromQuestionId == currentQuestionId }) {
            if (matches(branch.condition, answer)) {
                return branch.toQuestionId.takeUnless { it == "__end__" }
            }
        }
        val index = flow.questions.indexOfFirst { it.id == currentQuestionId }
        if (index < 0 || index >= flow.questions.lastIndex) return null
        return flow.questions[index + 1].id
    }

    fun isAnswered(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> !value.isString || value.content.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        else -> true
    }

    private fun matches(condition: SdkSurveyBranchCondition, answer: JsonElement?): Boolean {
        val expected = condition.value
        return when (condition.op) {
            "eq" -> answer == expected
            "neq" -> answer != expected
            "lt" -> number(answer) < number(expected)
            "lte" -> number(answer) <= number(expected)
            "gt" -> number(answer) > number(expected)
            "gte" -> number(answer) >= number(expected)
            "includes" -> answer is JsonArray && expected != null && answer.contains(expected)
            "not_includes" -> answer !is JsonArray || expected == null || !answer.contains(expected)
            "answered" -> isAnswered(answer)
            "unanswered" -> !isAnswered(answer)
            else -> false
        }
    }

    private fun number(value: JsonElement?): Double =
        (value as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
}
