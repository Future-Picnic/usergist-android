package studio.usergist.feedback

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.model.EventCountRule
import studio.usergist.feedback.internal.model.SerializedSegmentRules
import studio.usergist.feedback.internal.model.UserPropertyRule
import studio.usergist.feedback.internal.triggers.SegmentEvaluator
import studio.usergist.feedback.internal.triggers.UserState

/**
 * Exercises the serialized-rules evaluator shipped to the SDK. Mirrors
 * `evaluateSerializedSegmentRules` in `@usergist/sdk-core`.
 */
class SegmentEvaluatorTest {

    @Test
    fun null_rules_match_anything() {
        assertTrue(SegmentEvaluator.matches(null, UserState()))
    }

    @Test
    fun user_property_eq_neq() {
        val eqRule = SerializedSegmentRules(
            userProperties = listOf(
                UserPropertyRule("plan", "eq", JsonPrimitive("pro")),
            ),
        )
        val user = UserState(properties = mapOf("plan" to "pro"))
        assertTrue(SegmentEvaluator.matches(eqRule, user))

        val neqRule = SerializedSegmentRules(
            userProperties = listOf(
                UserPropertyRule("plan", "neq", JsonPrimitive("free")),
            ),
        )
        assertTrue(SegmentEvaluator.matches(neqRule, user))
    }

    @Test
    fun user_property_in_operator() {
        val rule = SerializedSegmentRules(
            userProperties = listOf(
                UserPropertyRule(
                    "country",
                    "in",
                    JsonArray(listOf(JsonPrimitive("DE"), JsonPrimitive("FR"))),
                ),
            ),
        )
        assertTrue(SegmentEvaluator.matches(rule, UserState(properties = mapOf("country" to "DE"))))
        assertFalse(SegmentEvaluator.matches(rule, UserState(properties = mapOf("country" to "US"))))
    }

    @Test
    fun user_property_numeric_comparisons() {
        val gte = SerializedSegmentRules(
            userProperties = listOf(
                UserPropertyRule("score", "gte", JsonPrimitive(5)),
            ),
        )
        assertTrue(SegmentEvaluator.matches(gte, UserState(properties = mapOf("score" to 5))))
        assertTrue(SegmentEvaluator.matches(gte, UserState(properties = mapOf("score" to 7.0))))
        assertFalse(SegmentEvaluator.matches(gte, UserState(properties = mapOf("score" to 4))))

        val lt = SerializedSegmentRules(
            userProperties = listOf(
                UserPropertyRule("score", "lt", JsonPrimitive(5)),
            ),
        )
        assertTrue(SegmentEvaluator.matches(lt, UserState(properties = mapOf("score" to 3))))
        assertFalse(SegmentEvaluator.matches(lt, UserState(properties = mapOf("score" to 6))))
    }

    @Test
    fun event_count_gte() {
        val rule = SerializedSegmentRules(
            eventCounts = listOf(EventCountRule("checkout", "gte", 3, 7)),
        )
        val user = UserState(
            eventCounts = mapOf("checkout" to mapOf(7 to 3)),
        )
        assertTrue(SegmentEvaluator.matches(rule, user))

        val low = UserState(eventCounts = mapOf("checkout" to mapOf(7 to 2)))
        assertFalse(SegmentEvaluator.matches(rule, low))
    }

    @Test
    fun event_count_eq_lte() {
        val eqRule = SerializedSegmentRules(
            eventCounts = listOf(EventCountRule("opened", "eq", 5, 1)),
        )
        val eqUser = UserState(eventCounts = mapOf("opened" to mapOf(1 to 5)))
        assertTrue(SegmentEvaluator.matches(eqRule, eqUser))

        val lteRule = SerializedSegmentRules(
            eventCounts = listOf(EventCountRule("opened", "lte", 5, 1)),
        )
        assertTrue(SegmentEvaluator.matches(lteRule, eqUser))
        val over = UserState(eventCounts = mapOf("opened" to mapOf(1 to 6)))
        assertFalse(SegmentEvaluator.matches(lteRule, over))
    }

    @Test
    fun combined_user_and_event_rules_are_AND() {
        val rule = SerializedSegmentRules(
            userProperties = listOf(UserPropertyRule("plan", "eq", JsonPrimitive("pro"))),
            eventCounts = listOf(EventCountRule("checkout", "gte", 1, 30)),
        )
        val matching = UserState(
            properties = mapOf("plan" to "pro"),
            eventCounts = mapOf("checkout" to mapOf(30 to 1)),
        )
        assertTrue(SegmentEvaluator.matches(rule, matching))

        val wrongPlan = matching.copy(properties = mapOf("plan" to "free"))
        assertFalse(SegmentEvaluator.matches(rule, wrongPlan))

        val noEvents = matching.copy(eventCounts = emptyMap())
        assertFalse(SegmentEvaluator.matches(rule, noEvents))
    }
}
