package studio.usergist.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.surveys.SdkSurveyCampaignWithFlow
import studio.usergist.feedback.internal.surveys.ArmedSurveysResponse
import studio.usergist.feedback.internal.surveys.SurveyFlowEvaluator

class SurveyContractTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `decodes all native flow fields used by the renderer`() {
        val campaign = json.decodeFromString<SdkSurveyCampaignWithFlow>(
            """
            {
              "id":"survey-1",
              "name":"Activation",
              "flow":{
                "startQuestionId":"q1",
                "progressStyle":"dots",
                "backNavigation":false,
                "questions":[
                  {"id":"q1","type":"multi_choice","title":"Pick","required":true,
                   "options":[{"id":"a","label":"A"}],"minSelections":1,"maxSelections":2},
                  {"id":"q2","type":"rating","title":"Rate","scale":10,"style":"numeric"}
                ],
                "branches":[{"fromQuestionId":"q1","condition":{"op":"includes","value":"a"},"toQuestionId":"q2"}],
                "endScreen":{"headline":"Done","cta":{"kind":"close","label":"Close"}}
              },
              "theme":{"colors":{"primary":"#123456"},"radius":18}
            }
            """.trimIndent(),
        )

        assertEquals("q1", campaign.flow.startQuestionId)
        assertEquals("dots", campaign.flow.progressStyle)
        assertFalse(campaign.flow.backNavigation)
        assertEquals(10, campaign.flow.questions[1].scale)
        assertEquals("Done", campaign.flow.endScreen?.headline)
        assertEquals("#123456", campaign.theme?.colors?.primary)
    }

    @Test
    fun `branch evaluator matches scalar numeric array and end sentinel rules`() {
        val campaign = json.decodeFromString<SdkSurveyCampaignWithFlow>(
            """
            {"id":"s","name":"S","flow":{"startQuestionId":"q1","questions":[
              {"id":"q1","type":"multi_choice","title":"Q1"},
              {"id":"q2","type":"nps","title":"Q2"},
              {"id":"q3","type":"info_screen","title":"Q3"}
            ],"branches":[
              {"fromQuestionId":"q1","condition":{"op":"includes","value":"vip"},"toQuestionId":"q2"},
              {"fromQuestionId":"q2","condition":{"op":"gte","value":9},"toQuestionId":"__end__"}
            ]}}
            """.trimIndent(),
        )
        val answers = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "q1" to JsonArray(listOf(JsonPrimitive("vip"))),
            "q2" to JsonPrimitive(10),
        )

        assertEquals("q2", SurveyFlowEvaluator.nextQuestionId(campaign.flow, "q1", answers))
        assertNull(SurveyFlowEvaluator.nextQuestionId(campaign.flow, "q2", answers))
        assertTrue(SurveyFlowEvaluator.isAnswered(JsonPrimitive(0)))
        assertFalse(SurveyFlowEvaluator.isAnswered(JsonPrimitive("")))
        assertFalse(SurveyFlowEvaluator.isAnswered(JsonArray(emptyList())))
    }

    @Test
    fun `decodes armed survey targeting and embedded content`() {
        val response = json.decodeFromString<ArmedSurveysResponse>(
            """
            {"surveys":[{"campaignId":"survey-1","eventName":"checkout_completed",
              "clientSideEligible":false,"cooldownSeconds":90,
              "segmentRules":{"userProperties":[{"key":"plan","op":"eq","value":"pro"}]},
              "frequencyCap":{"perCampaignDays":7,"perPillarDays":1},
              "survey":{"id":"survey-1","name":"Checkout","flow":{"startQuestionId":"q1",
                "questions":[{"id":"q1","type":"info_screen","title":"Done"}]}}}]}
            """.trimIndent(),
        )

        val armed = response.surveys.single()
        assertFalse(armed.clientSideEligible ?: true)
        assertEquals(90L, armed.cooldownSeconds)
        assertEquals(7, armed.frequencyCap.perCampaignDays)
        assertEquals("Checkout", armed.survey.name)
    }
}
