# userGist Feedback SDK — Android (experimental)

This native Android SDK is not launch-supported yet. Its authenticated-subject,
durable-instruction, survey-rendering, and release-device gates are tracked in
`packages/PARITY.md`. Use the React Native SDK for the supported v0.1 launch.

> Published on Maven Central as `studio.usergist:feedback`. Kotlin `1.9+`, `minSdk 24`, `compileSdk 34`.

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
UserGist.identify(userId = "u_123", properties = mapOf("plan" to "pro"))
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

Mirrors the layering in `DEV_PRD.md` §6:

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

Storage layout: `context.filesDir/usergist/{sha256(writeKey).take(16)}/`
containing `events.log`, `identity.json`, `consent.json`,
`armed_triggers.json`, `frequency_caps.json`.

## Testing

Unit tests live in `src/test` and run on the JVM:

```
./gradlew test
```

Key test suites: `EventQueueTest`, `SegmentEvaluatorTest`,
`FrequencyCapTest`, `RetryPolicyTest`, `ApiClientTest` (MockWebServer).

## License

Apache-2.0 © userGist Studio
