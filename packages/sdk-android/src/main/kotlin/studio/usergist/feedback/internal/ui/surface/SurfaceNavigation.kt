package studio.usergist.feedback.internal.ui.surface

/** What a header slot holds in the survey / feedback flow. */
internal enum class HeaderControl { BACK, CLOSE, NONE }

/**
 * Header + footer placement for survey and feedback surfaces. Identical in
 * portrait and landscape: with a usable Back, Back leads and Close trails;
 * without one, Close leads and the trailing slot stays empty. The main action
 * (Next / Submit / end-screen CTA) is never a header control; it is one
 * full-width footer button in the readable column, and Back never sits in
 * the footer.
 */
internal data class SurfaceNavigation(
    val leading: HeaderControl,
    val trailing: HeaderControl,
    val footerAction: Boolean,
) {
    companion object {
        fun resolve(canGoBack: Boolean, hasMainAction: Boolean): SurfaceNavigation =
            if (canGoBack) {
                SurfaceNavigation(HeaderControl.BACK, HeaderControl.CLOSE, hasMainAction)
            } else {
                SurfaceNavigation(HeaderControl.CLOSE, HeaderControl.NONE, hasMainAction)
            }
    }
}
