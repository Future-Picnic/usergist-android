package studio.ritmus.feedback.internal.triggers

import studio.ritmus.feedback.internal.RitmusLogger
import studio.ritmus.feedback.internal.model.ArmedTrigger

/**
 * Decides, for an incoming event, which armed trigger (if any) should
 * fire. Encodes the gate ordering from DEV_PRD §6:
 *   1. consent (feedback must be granted — checked outside)
 *   2. event-name match (via [RulesCache])
 *   3. segment match (client-side best-effort; server re-verifies)
 *   4. frequency caps
 */
internal class TriggerMatcher(
    private val rulesCache: RulesCache,
    private val frequencyCapStore: FrequencyCapStore,
) {

    /**
     * Returns the first matching trigger for [eventName] given [userState],
     * or `null` if no trigger is eligible.
     */
    fun match(eventName: String, userState: UserState): ArmedTrigger? {
        val candidates = rulesCache.triggersFor(eventName)
        if (candidates.isEmpty()) {
            RitmusLogger.d("TriggerMatcher: no armed triggers for event '$eventName'")
            return null
        }
        for (trigger in candidates) {
            if (!SegmentEvaluator.matches(trigger.segmentRules, userState)) {
                RitmusLogger.d(
                    "TriggerMatcher: trigger ${trigger.promptId} skipped — segment mismatch",
                )
                continue
            }
            if (!frequencyCapStore.allows(trigger.promptId, trigger.frequency)) {
                RitmusLogger.d(
                    "TriggerMatcher: trigger ${trigger.promptId} skipped — frequency cap",
                )
                continue
            }
            RitmusLogger.d("TriggerMatcher: trigger ${trigger.promptId} matched event '$eventName'")
            return trigger
        }
        return null
    }
}
