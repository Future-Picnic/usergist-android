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
    const val PUSH_REBIND: String = "$BASE/push/rebind"
    const val PUSH_APP_OPEN: String = "$BASE/push/app-open"
    const val PUSH_DELIVERED: String = "$BASE/push/delivered"
    const val PUSH_DISPLAYED: String = "$BASE/push/displayed"
    const val PUSH_DISMISSED: String = "$BASE/push/dismissed"
    const val PUSH_SILENT_ACK: String = "$BASE/push/silent-ack"
    const val PUSH_CHANNELS: String = "$BASE/push/channels"
    const val PUSH_CHANNEL_SUBSCRIPTION: String = "$BASE/push/channels/subscription"

    // Feature requests
    const val REQUESTS: String = "$BASE/requests"
    fun request(id: String): String = "$BASE/requests/$id"
    fun requestVote(id: String): String = "$BASE/requests/$id/vote"
    fun requestFollow(id: String): String = "$BASE/requests/$id/follow"
    fun requestComments(id: String): String = "$BASE/requests/$id/comments"
    fun requestComment(requestId: String, commentId: String): String =
        "$BASE/requests/$requestId/comments/$commentId"
    const val REQUEST_BRANDING: String = "$BASE/request-branding"
}
