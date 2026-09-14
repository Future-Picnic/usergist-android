# Changelog

## 0.1.2

- Add startup presentation readiness: initialize with `presentationPaused`, then call `resumePresentation()` after the loaded screen is ready.
- Keep analytics and networking running while campaign UI is paused; `pausePresentation()` can protect later host flows without dismissing active UI.
- Invalidate queued presentation work after consent revocation, reset, or identity changes, including requests prepared asynchronously.

All notable changes to `com.usergist:feedback` are documented here. Releases
use [Semantic Versioning](https://semver.org/).

## 0.1.1

- Corrected the Maven coordinate to the domain-verified `com.usergist` namespace.
- Preserved the `studio.usergist.feedback` Kotlin package for source compatibility.

## 0.1.0

- Initial production Android library for API 24 and later.
- Anonymous and identified-user sessions with encrypted credential storage.
- Consent-aware offline queues, targeting, and campaign instruction handling.
- Native feedback, surveys, in-app messages, feature requests, and FCM hooks.
- Consumer ProGuard rules and release AAR, sources, and Javadoc artifacts.
