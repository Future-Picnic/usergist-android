package studio.usergist.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import studio.usergist.feedback.api.FeatureRequest
import studio.usergist.feedback.api.RequestStatus
import studio.usergist.feedback.internal.requests.RequestsCache

class RequestsCacheTest {
    @Test fun reset_discards_viewer_state_and_late_rollbacks() {
        val cache = RequestsCache()
        val previous = FeatureRequest(
            id = "r1", appId = "app", title = "Title", description = "Description",
            status = RequestStatus.UNDER_REVIEW, devResponse = null,
            upvoteCount = 10, followerCount = 4, createdAt = "2026-01-01",
            updatedAt = "2026-01-01", statusChangedAt = "2026-01-01", lastRespondedAt = null,
            viewerHasUpvoted = true, viewerIsFollowing = true, viewerIsSubmitter = true,
        )
        cache.upsert(previous)
        val voteRollback = cache.applyOptimisticVote("r1", false)
        val followRollback = cache.applyOptimisticFollow("r1", false)
        var oldListenerCalled = false
        cache.subscribe { _, _ -> oldListenerCalled = true }
        cache.clear()
        assertNull(cache.get("r1"))
        val next = previous.copy(upvoteCount = 25, viewerHasUpvoted = false, viewerIsFollowing = false, viewerIsSubmitter = false)
        cache.upsert(next)
        voteRollback()
        followRollback()
        assertEquals(next, cache.get("r1"))
        assertFalse(oldListenerCalled)
    }
}
