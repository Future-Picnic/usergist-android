package studio.usergist.feedback

import org.junit.Assert.*
import org.junit.Test
import studio.usergist.feedback.internal.ui.ModalPresentationQueue

class PresentationQueueTest {
    @Test fun pauseDefersAndDuplicateResumeDoesNotDuplicate() {
        val queue = ModalPresentationQueue { it() }
        queue.setPaused(true)
        var shown = 0
        queue.enqueue(this) { shown++; true }
        assertEquals(0, shown)
        queue.setPaused(false)
        queue.setPaused(false)
        assertEquals(1, shown)
    }
    @Test fun pauseLeavesActiveSurfaceAndDefersNextUntilResumed() {
        val queue = ModalPresentationQueue { it() }
        var release: (() -> Unit)? = null
        var second = false
        queue.enqueue(this) { release = it; true }
        queue.enqueue(Any()) { second = true; true }
        queue.setPaused(true)
        release!!()
        assertFalse(second)
        queue.setPaused(false)
        assertTrue(second)
    }
    @Test fun invalidatedWorkIsDiscardedAndUnavailableHostIsRetried() {
        val queue = ModalPresentationQueue { it() }
        queue.setPaused(true)
        var valid = true
        var stale = false
        var ready = false
        var fresh = false
        queue.enqueue(this, { valid }) { stale = true; true }
        queue.enqueue(Any()) { fresh = ready; ready }
        valid = false
        queue.setPaused(false)
        assertFalse(stale)
        assertFalse(fresh)
        ready = true
        queue.retryPending()
        assertTrue(fresh)
    }
}
