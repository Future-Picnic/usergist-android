package studio.usergist.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.usergist.feedback.internal.surveys.SdkSurveyAttemptRequest

class SurveyRequestContextTest {
    @Test
    fun platformIsSentWhenDefaultEncodingIsDisabled() {
        val body = Json { encodeDefaults = false }.encodeToJsonElement(
            SdkSurveyAttemptRequest.serializer(),
            SdkSurveyAttemptRequest(
                anonymousId = "survey-anonymous", source = "triggered",
                sdkVersion = "0.2.0", appVersion = "1.0-debug", platform = "android",
            ),
        ).jsonObject
        assertEquals("android", body["platform"]?.jsonPrimitive?.content)
    }
}
