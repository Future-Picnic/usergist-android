package studio.usergist.feedback

import org.junit.Assert.*
import org.junit.Test
import studio.usergist.feedback.internal.lifecycle.AppOpenGate

class AppOpenGateTest {
    @Test fun startupAndForegroundEmitOnceButNextVisitEmitsAgain() {
        val gate = AppOpenGate()
        assertTrue(gate.request(true))
        assertFalse(gate.request(true))
        assertFalse(gate.grant(true))
        gate.background()
        assertTrue(gate.request(true))
        assertFalse(gate.request(true))
    }

    @Test fun deniedStartupWaitsForGrantOnce() {
        val gate = AppOpenGate()
        assertFalse(gate.request(false))
        assertFalse(gate.request(false))
        assertFalse(gate.grant(false))
        assertTrue(gate.grant(true))
        assertFalse(gate.grant(true))
        assertFalse(gate.request(true))
    }

    @Test fun resetDiscardsOldPendingVisit() {
        val gate = AppOpenGate()
        assertFalse(gate.request(false))
        gate.reset()
        assertFalse(gate.grant(true))
        assertTrue(gate.request(true))
    }
}
