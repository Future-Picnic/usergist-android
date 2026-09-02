# userGist Feedback SDK — Android

Production Android SDK for userGist feedback, surveys, in-app messaging,
feature requests, analytics events, and host-compatible FCM delivery.

> Maven coordinate: `studio.usergist:feedback`. Kotlin `1.9+`, `minSdk 24`, `compileSdk 34`.

## Install

```kotlin
dependencies {
    implementation("studio.usergist:feedback:0.1.0")
}
```

## Quick start

```kotlin
import studio.usergist.feedback.UserGist
import studio.usergist.feedback.api.Consent
import studio.usergist.feedback.api.Environment

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        UserGist.initialize(
            context = this,
            writeKey = BuildConfig.USERGIST_WRITE_KEY,
            environment = Environment.PRODUCTION,
            debug = BuildConfig.DEBUG,
        )

        UserGist.onPromptShown = { promptId ->
            // analytics hook
        }
        UserGist.onResponse = { response ->
            // analytics hook: response.promptId, response.answers, response.dismissed
        }

        UserGist.setConsent(Consent(analytics = true, feedback = true))
    }
}
```

### Consent gate (GDPR)

No network traffic leaves the device until consent is granted.

```kotlin
UserGist.setConsent(Consent(analytics = true, feedback = true))
```

### Identify and track

```kotlin
// Mint this st_ token on your authenticated backend. Never ship an rtk_ token.
UserGist.identify(
    userId = "u_123",
    subjectToken = subjectToken,
    properties = mapOf("plan" to "pro"),
)
UserGist.track("checkout_completed", mapOf("amount_cents" to 4999, "currency" to "EUR"))
```

### Theming

```kotlin
UserGist.setThemeOverrides(
    PromptTheme(
        colors = ThemeColors(
            primary = "#111111",
            background = "#FFFFFF",
            text = "#111111",
            subtext = "#6B7280",
            border = "#E5E7EB",
        ),
        radius = 16,
    )
)
```

### Reset (logout)

```kotlin
UserGist.reset()
```

## Architecture

The Android implementation uses the same production layering as the other SDKs:

```
+-------------------+    +-------------------+    +-------------------+
|   Public API      |    |   Lifecycle       |    |   UI              |
|   (UserGist)        |    |   (foreground/bg) |    |   (BottomSheet)   |
+--------+----------+    +---------+---------+    +---------+---------+
         |                         |                        |
         v                         v                        v
+-------------------+    +-------------------+    +-------------------+
|   ConsentGate     |--->|   EventQueue      |    |   PromptPresenter |
|   (blocks xport)  |    |   (persistent)    |    +---------+---------+
+-------------------+    +---------+---------+              |
                                   |                         |
                                   v                         |
                         +-------------------+               |
                         |   Transport       |               |
                         |   (OkHttp + retry)|               |
                         +---------+---------+               |
                                   |                         |
                                   v                         |
                         +-------------------+    +-------------------+
                         |   RulesCache      |--->|   TriggerMatcher  |
                         |   (armed+inline)  |    |   (segment+caps)  |
                         +-------------------+    +-------------------+
```

The SDK establishes and securely persists a subject session before protected
calls, preserves anonymous and identified user state, and uses separate durable
queues for events, identify/feedback/survey mutations, and server instructions.
Prompt, survey, and in-app campaigns share one process-wide modal FIFO.

Storage is scoped to `context.filesDir/usergist/{sha256(writeKey).take(16)}/`.
Identity, consent, subject credentials, and mutations prefer Android Keystore-
backed encrypted storage. Bounded event history, frequency caps, armed campaign
caches, survey progress, and instruction dedupe state are versioned on disk.

## Testing

Unit tests live in `src/test` and run in debug and release JVM variants:

```
ANDROID_HOME=/path/to/android-sdk ./gradlew --no-daemon test lintRelease assembleRelease
```

Key suites cover authenticated transport, queue migration/isolation, mutation
durability, property bounds, armed-campaign decoding, survey branching/resume,
frequency caps, retries, and request-cache behavior.

## Push limitation

Token registration, invalidation/rebinding, channel registry, silent acks,
beacons, and host-forwarded receive/open/action/dismiss handling are present.
The React Native SDK's single-call automatic `enablePush`/`disablePush`, badge,
and initial-notification helpers do not yet have native Android equivalents.
End-to-end push validation also requires real FCM credentials and a physical
device.

## License

MIT © 2025-2026 userGist
