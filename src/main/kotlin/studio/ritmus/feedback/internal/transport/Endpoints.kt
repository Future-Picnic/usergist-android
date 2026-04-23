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
}
