package studio.usergist.feedback.internal.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import studio.usergist.feedback.BuildConfig
import studio.usergist.feedback.internal.Config
import studio.usergist.feedback.internal.model.IngestContext
import java.util.Locale
import java.util.TimeZone

/** Builds the static-ish device / app context attached to every ingest batch. */
internal object ContextBuilder {

    fun build(
        context: Context,
        anonymousId: String,
        externalId: String?,
    ): IngestContext = IngestContext(
        anonymousId = anonymousId,
        externalId = externalId,
        sdkVersion = safeSdkVersion(),
        platform = Config.SDK_PLATFORM,
        appVersion = appVersion(context),
        locale = Locale.getDefault().toLanguageTag(),
        timezone = TimeZone.getDefault().id,
        osName = "Android",
        osVersion = Build.VERSION.RELEASE,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    )

    private fun appVersion(context: Context): String? = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun safeSdkVersion(): String = try {
        BuildConfig.SDK_VERSION
    } catch (_: Throwable) {
        Config.SDK_VERSION_FALLBACK
    }
}
