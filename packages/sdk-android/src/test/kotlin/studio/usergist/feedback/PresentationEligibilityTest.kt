package studio.usergist.feedback

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import studio.usergist.feedback.api.Consent
import studio.usergist.feedback.internal.ui.CampaignPresentationEligibility as Gate

class PresentationEligibilityTest {
    @Before fun prepare() {
        Gate.invalidateIdentity()
        Gate.updateConsent(Consent(feedback = true, survey = true))
    }
    @Test fun revokedConsentDoesNotReviveOldWork() {
        val feedback = Gate.validator(Gate.Purpose.FEEDBACK)
        val survey = Gate.validator(Gate.Purpose.SURVEY)
        Gate.updateConsent(Consent(feedback = false, survey = true))
        Gate.updateConsent(Consent(feedback = true, survey = true))
        assertFalse(feedback())
        assertTrue(survey())
        assertTrue(Gate.validator(Gate.Purpose.FEEDBACK)())
    }
    @Test fun identityChangeInvalidatesBothPurposes() {
        val feedback = Gate.validator(Gate.Purpose.FEEDBACK)
        val survey = Gate.validator(Gate.Purpose.SURVEY)
        Gate.invalidateIdentity()
        assertFalse(feedback())
        assertFalse(survey())
    }
}
