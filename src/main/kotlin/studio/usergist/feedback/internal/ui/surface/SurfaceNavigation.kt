package studio.usergist.feedback.internal.ui.surface

/** What a header slot holds in the survey / feedback flow. */
internal enum class HeaderControl { BACK, CLOSE, NONE }

/**
 * Portrait retains Back/Close and a full-width footer. Landscape groups Close
 * then Back at the leading edge and puts the main action in the same row.
 */
internal data class SurfaceNavigation(
    val leading: HeaderControl,
    val trailing: HeaderControl,
    val footerAction: Boolean,
    val headerAction: Boolean = false,
) {
    companion object {
        fun resolve(canGoBack: Boolean, hasMainAction: Boolean, landscape: Boolean = false): SurfaceNavigation =
            if (landscape) {
                SurfaceNavigation(HeaderControl.CLOSE, HeaderControl.NONE, false, hasMainAction)
            } else if (canGoBack) {
                SurfaceNavigation(HeaderControl.BACK, HeaderControl.CLOSE, hasMainAction)
            } else {
                SurfaceNavigation(HeaderControl.CLOSE, HeaderControl.NONE, hasMainAction)
            }
    }
}
