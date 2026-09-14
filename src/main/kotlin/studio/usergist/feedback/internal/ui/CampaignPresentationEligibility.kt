package studio.usergist.feedback.internal.ui

import studio.usergist.feedback.api.Consent

/** Validity tickets survive async preparation and permanently expire on revocation/reset. */
internal object CampaignPresentationEligibility {
    enum class Purpose { FEEDBACK, SURVEY }
    private var identity = 0L
    private var feedback = 0L
    private var survey = 0L
    private var consent = Consent()

    @Synchronized fun updateConsent(next: Consent) {
        if (consent.feedback == true && next.feedback != true) feedback++
        if (consent.survey == true && next.survey != true) survey++
        consent = next
    }
    @Synchronized fun invalidateIdentity() { identity++ }
    @Synchronized fun validator(purpose: Purpose): () -> Boolean {
        val capturedIdentity = identity
        val capturedPurpose = if (purpose == Purpose.SURVEY) survey else feedback
        val allowed = if (purpose == Purpose.SURVEY) consent.survey == true else consent.feedback == true
        return {
            synchronized(this) {
                allowed && identity == capturedIdentity &&
                    capturedPurpose == (if (purpose == Purpose.SURVEY) survey else feedback) &&
                    (if (purpose == Purpose.SURVEY) consent.survey == true else consent.feedback == true)
            }
        }
    }
}
