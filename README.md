# Ritmus Feedback SDK — Android

Native Android SDK for the Ritmus mobile engagement tool. Kotlin-first, Coroutines-based,
Kotlinx Serialization for wire types, OkHttp for transport.

> Published on Maven Central as `studio.ritmus:feedback`. Kotlin `1.9+`, `minSdk 24`, `compileSdk 34`.

## Install

```kotlin
dependencies {
    implementation("studio.ritmus:feedback:0.1.0")
}
```

## Quick start

```kotlin
import studio.ritmus.feedback.Ritmus
import studio.ritmus.feedback.api.Consent
import studio.ritmus.feedback.api.Environment

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Ritmus.initialize(
            context = this,
            writeKey = BuildConfig.RITMUS_WRITE_KEY,
            environment = Environment.PRODUCTION,
            debug = BuildConfig.DEBUG,
        )

        Ritmus.onPromptShown = { promptId ->
            // analytics hook
        }
        Ritmus.onResponse = { response ->
            // analytics hook: response.promptId, response.answers, response.dismissed
        }
    }
}
```

### Consent gate (GDPR)

No network traffic leaves the device until consent is granted.

```kotlin
Ritmus.setConsent(Consent(analytics = true, feedback = true))
```

### Identify and track

```kotlin
Ritmus.identify(userId = "u_123", properties = mapOf("plan" to "pro"))
Ritmus.track("checkout_completed", mapOf("amount_cents" to 4999, "currency" to "EUR"))
```

### Theming

```kotlin
Ritmus.setThemeOverrides(
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
Ritmus.reset()
```

## Architecture

Mirrors the layering in `DEV_PRD.md` §6:

```
+-------------------+    +-------------------+    +-------------------+
|   Public API      |    |   Lifecycle       |    |   UI              |
|   (Ritmus)        |    |   (foreground/bg) |    |   (BottomSheet)   |
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

Storage layout: `context.filesDir/ritmus/{sha256(writeKey).take(16)}/`
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

Apache-2.0 © Ritmus Studio
