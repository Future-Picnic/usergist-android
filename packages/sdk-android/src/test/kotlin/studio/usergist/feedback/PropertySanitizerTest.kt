package studio.usergist.feedback

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.util.AnyMap

class PropertySanitizerTest {
    @Test
    fun `event properties match reference bounds and scalar contract`() {
        val input = linkedMapOf<String, Any?>(
            "plan" to "pro",
            "account.email" to "private@example.com",
            "nested" to mapOf("bad" to true),
            "list" to listOf(1, 2),
            "score" to 4.5,
            "enabled" to true,
            "nothing" to null,
            "long" to "x".repeat(10_001),
        )

        val clean = requireNotNull(AnyMap.toJsonObject(input))

        assertEquals(JsonPrimitive("pro"), clean["plan"])
        assertEquals(JsonPrimitive(4.5), clean["score"])
        assertEquals(JsonPrimitive(true), clean["enabled"])
        assertEquals(JsonNull, clean["nothing"])
        assertEquals(10_000, (clean["long"] as JsonPrimitive).content.length)
        assertFalse(clean.containsKey("account.email"))
        assertFalse(clean.containsKey("nested"))
        assertFalse(clean.containsKey("list"))
        assertTrue(clean.size <= 100)
    }
}
