package studio.usergist.feedback.internal.lifecycle

/** Coalesces startup and lifecycle notifications for one foreground visit. */
internal class AppOpenGate {
    private var requested = false
    private var pending = false

    @Synchronized fun request(feedbackGranted: Boolean): Boolean {
        if (requested) return false
        requested = true
        pending = !feedbackGranted
        return feedbackGranted
    }

    @Synchronized fun grant(feedbackGranted: Boolean): Boolean {
        if (!pending || !feedbackGranted) return false
        pending = false
        return true
    }

    @Synchronized fun background() { requested = false }
    @Synchronized fun reset() { requested = false; pending = false }
}
