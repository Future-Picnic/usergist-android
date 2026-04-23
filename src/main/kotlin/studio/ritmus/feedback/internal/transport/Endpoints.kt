package studio.ritmus.feedback.internal.transport

/**
 * SDK-facing endpoint paths. Matches the endpoint map in
 * `@ritmus/sdk-core` (`packages/sdk-core/src/contract/endpoints.ts`).
 */
internal object Endpoints {
    const val BASE: String = "/v1/sdk"

    const val INGEST: String = "$BASE/ingest"
    const val ARMED_TRIGGERS: String = "$BASE/armed-triggers"
    const val CONSENT: String = "$BASE/consent"
    const val IDENTIFY: String = "$BASE/identify"
    const val RESPONSES: String = "$BASE/responses"

    // Push
    const val PUSH_REGISTER_TOKEN: String = "$BASE/push/register-token"
    const val PUSH_UPDATE_TOKEN: String = "$BASE/push/update-token"
    const val PUSH_INVALIDATE_TOKEN: String = "$BASE/push/invalidate-token"
}
