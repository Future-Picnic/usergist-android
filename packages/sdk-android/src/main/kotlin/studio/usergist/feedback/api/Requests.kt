// Feature requests (5th pillar) — public API surface for sdk-android.
//
// STATUS: API surface declared; HTTP wiring + UI Fragments tracked in
// PARITY.md. Mirrors the React Native reference.

package studio.usergist.feedback.api

enum class RequestStatus(val raw: String) {
    UNDER_REVIEW("under_review"),
    PLANNED("planned"),
    IN_PROGRESS("in_progress"),
    SHIPPED("shipped"),
    DECLINED("declined");

    companion object {
        fun fromRaw(raw: String): RequestStatus =
            values().firstOrNull { it.raw == raw } ?: UNDER_REVIEW
    }
}

enum class RequestFollowSource(val raw: String) {
    UPVOTE_AUTO("upvote_auto"),
    MANUAL("manual"),
}

enum class RequestSort(val raw: String) {
    TOP("top"),
    NEWEST("newest"),
    RECENTLY_UPDATED("recently_updated"),
}

enum class RequestPersonalFilter(val raw: String) {
    SUBMITTED("submitted"),
    UPVOTED("upvoted"),
    FOLLOWING("following"),
}

data class FeatureRequest(
    val id: String,
    val appId: String,
    val title: String,
    val description: String,
    val status: RequestStatus,
    val devResponse: String?,
    val upvoteCount: Int,
    val followerCount: Int,
    val createdAt: String,
    val updatedAt: String,
    val statusChangedAt: String,
    val lastRespondedAt: String?,
    val viewerHasUpvoted: Boolean,
    val viewerIsFollowing: Boolean,
    val viewerIsSubmitter: Boolean,
)

data class RequestSummary(
    val id: String,
    val title: String,
    val description: String,
    val status: RequestStatus,
    val upvoteCount: Int,
    val followerCount: Int,
    val createdAt: String,
    val statusChangedAt: String,
    val viewerHasUpvoted: Boolean,
    val viewerIsFollowing: Boolean,
)

data class RequestSearchResult(
    val id: String,
    val title: String,
    val status: RequestStatus,
    val upvoteCount: Int,
)

data class RequestVote(
    val requestId: String,
    val upvoted: Boolean,
    val followed: Boolean,
    val upvoteCount: Int,
    val followerCount: Int,
)

data class RequestFollow(
    val requestId: String,
    val following: Boolean,
    val followerCount: Int,
    val source: RequestFollowSource,
)

data class GetRequestsOptions(
    val sort: RequestSort? = null,
    val statuses: List<RequestStatus>? = null,
    val mine: RequestPersonalFilter? = null,
    val q: String? = null,
    val cursor: String? = null,
    val limit: Int? = null,
)

data class GetRequestsResult(
    val items: List<RequestSummary>,
    val nextCursor: String?,
)

data class RequestStatusChangedNotice(
    val requestId: String,
    val title: String,
    val oldStatus: RequestStatus,
    val newStatus: RequestStatus,
    val devResponseExcerpt: String?,
)

interface RequestsHandlers {
    fun onSubmit(request: FeatureRequest) {}
    fun onVote(vote: RequestVote) {}
    fun onFollow(follow: RequestFollow) {}
    fun onStatusChanged(notice: RequestStatusChangedNotice) {}
}

sealed class RequestsResult<T> {
    data class Success<T>(val value: T) : RequestsResult<T>()
    data class Failure<T>(val error: Throwable) : RequestsResult<T>()
}

data class RequestBranding(
    val entryLabel: String,
    val accentColor: String?,
    val logoUrl: String?,
    val introCopy: String?,
)
