# Changelog

## 0.1.4 — identity lifecycle

- Preserve the zero-argument Java/JVM reset API. Serialize push registration/invalidation and retire invalidated tokens from durable automatic retries.
- Confirmed identity state and asynchronous completion, with backend token renewal and retained account identity after expiration.
- Property set/unset using the active session; profile PII follows the app's server allowlist, while event filtering remains in place.
- Installation-bound credentials, anonymous ownership proof, and canonical profile adoption on identify.
- Local account reset with cancellation and independent durable logout cleanup; stale responses cannot restore the old account.
- Clear request-board viewer state and close SDK request screens on reset; discard delayed vote/follow rollbacks from the previous account.
- Push subscription state reflects server acknowledgement; OS tokens survive restart and retry after consent, connectivity, or identity changes.

Requires the coordinated backend lifecycle deployment. See the [identity integration guide](https://usergist.com/docs/integrations/identity) for backend requirements and account-switching guidance.


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
