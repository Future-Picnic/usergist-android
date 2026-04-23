package studio.ritmus.feedback.internal.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import studio.ritmus.feedback.internal.RitmusLogger
import java.lang.ref.WeakReference

/**
 * Tracks the current resumed [Activity] and reports foreground /
 * background transitions via a pair of callbacks.
 *
 * Registration is idempotent; callers can call [start] multiple times
 * (only the first call actually attaches to the process lifecycle).
 */
internal class AppLifecycleObserver(
    private val application: Application,
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    @Volatile
    private var started: Boolean = false

    fun start() {
        if (started) return
        started = true
        try {
            application.registerActivityLifecycleCallbacks(this)
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        } catch (e: Throwable) {
            RitmusLogger.w("AppLifecycleObserver.start failed", e)
        }
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            application.unregisterActivityLifecycleCallbacks(this)
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        } catch (e: Throwable) {
            RitmusLogger.w("AppLifecycleObserver.stop failed", e)
        }
    }

    fun topActivity(): Activity? = currentActivity?.get()

    // ---------------- Process lifecycle ----------------

    override fun onStart(owner: LifecycleOwner) {
        try {
            onForeground()
        } catch (e: Throwable) {
            RitmusLogger.w("AppLifecycleObserver.onStart handler threw", e)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        try {
            onBackground()
        } catch (e: Throwable) {
            RitmusLogger.w("AppLifecycleObserver.onStop handler threw", e)
        }
    }

    // ---------------- Activity lifecycle ----------------

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (currentActivity?.get() === activity) {
            currentActivity = null
        }
    }

    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
