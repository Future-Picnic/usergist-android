package studio.usergist.feedback

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.api.ArmedInAppMessage
import studio.usergist.feedback.api.InAppCtaAction
import studio.usergist.feedback.api.InAppMessageFormat

class InAppMessageTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `decodes authenticated in-app instruction message`() {
        val message = json.decodeFromString<ArmedInAppMessage>(
            """
            {
              "messageId":"message-1",
              "eventName":"checkout_completed",
              "format":"slideup",
              "title":"How did checkout go?",
              "body":"Tell us while it is fresh.",
              "backdropEnabled":false,
              "autoDismissSeconds":4,
              "ctas":[{
                "label":"Share feedback",
                "action":"custom_event",
                "target":"checkout_feedback_requested"
              }],
              "screenAllowlist":["Checkout"],
              "screenDenylist":[],
              "forceShow":false
            }
            """.trimIndent(),
        )

        assertEquals("message-1", message.messageId)
        assertEquals(InAppMessageFormat.SLIDEUP, message.format)
        assertFalse(message.backdropEnabled)
        assertEquals(4.0, message.autoDismissSeconds)
        assertEquals(InAppCtaAction.CUSTOM_EVENT, message.ctas.single().action)
        assertTrue(message.screenAllowlist.contains("Checkout"))
    }

    @Test
    fun `decodes json action payload`() {
        val message = json.decodeFromString<ArmedInAppMessage>(
            """
            {
              "messageId":"message-json",
              "format":"modal",
              "title":"Selected offer",
              "ctas":[{
                "label":"Show price",
                "action":"json",
                "actionJson":{"type":"show_special_price","price":19}
              }]
            }
            """.trimIndent(),
        )

        assertEquals(InAppCtaAction.JSON, message.ctas.single().action)
        assertEquals("\"show_special_price\"", message.ctas.single().actionJson?.get("type").toString())
    }
}
