package studio.usergist.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.ui.surface.HeaderControl
import studio.usergist.feedback.internal.ui.surface.SurfaceNavigation

/** Approved survey / feedback navigation placement (design-rollout-20261115). */
class SurfaceNavigationTest {

    @Test
    fun landscapeKeepsCloseAndThePrimaryInOneHeaderRow() {
        listOf(true, false).forEach { back ->
            val nav = SurfaceNavigation.resolve(back, hasMainAction = true, landscape = true)
            assertEquals(HeaderControl.CLOSE, nav.leading)
            assertEquals(HeaderControl.NONE, nav.trailing)
            assertTrue(nav.headerAction)
            assertFalse(nav.footerAction)
        }
        val autoAdvance = SurfaceNavigation.resolve(true, hasMainAction = false, landscape = true)
        assertFalse(autoAdvance.headerAction)
        assertFalse(autoAdvance.footerAction)
    }

    @Test
    fun withBackBackLeadsAndCloseTrails() {
        val nav = SurfaceNavigation.resolve(canGoBack = true, hasMainAction = true)
        assertEquals(HeaderControl.BACK, nav.leading)
        assertEquals(HeaderControl.CLOSE, nav.trailing)
        assertTrue(nav.footerAction)
    }

    @Test
    fun withoutBackCloseLeadsAndTrailingIsEmpty() {
        val nav = SurfaceNavigation.resolve(canGoBack = false, hasMainAction = true)
        assertEquals(HeaderControl.CLOSE, nav.leading)
        assertEquals(HeaderControl.NONE, nav.trailing)
        assertTrue(nav.footerAction)
    }

    @Test
    fun autoAdvanceQuestionsKeepHeaderButHaveNoFooter() {
        val nav = SurfaceNavigation.resolve(canGoBack = true, hasMainAction = false)
        assertEquals(HeaderControl.BACK, nav.leading)
        assertEquals(HeaderControl.CLOSE, nav.trailing)
        assertFalse(nav.footerAction)
    }

    @Test
    fun mainActionAndBackAreNeverHeaderTrailingOrFooterBack() {
        listOf(true, false).forEach { back ->
            listOf(true, false).forEach { main ->
                val nav = SurfaceNavigation.resolve(back, main)
                // Back only ever leads; Close is the only trailing control.
                assertTrue(nav.trailing == HeaderControl.CLOSE || nav.trailing == HeaderControl.NONE)
                assertEquals(back, nav.leading == HeaderControl.BACK)
                assertEquals(main, nav.footerAction)
            }
        }
    }
}
