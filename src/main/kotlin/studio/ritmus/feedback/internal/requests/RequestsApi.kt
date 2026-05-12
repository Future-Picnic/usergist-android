package studio.ritmus.feedback.internal.requests

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.ritmus.feedback.api.FeatureRequest
import studio.ritmus.feedback.api.GetRequestsOptions
import studio.ritmus.feedback.api.GetRequestsResult
import studio.ritmus.feedback.api.RequestFollow
import studio.ritmus.feedback.api.RequestFollowSource
import studio.ritmus.feedback.api.RequestStatus
import studio.ritmus.feedback.api.RequestSummary
import studio.ritmus.feedback.api.RequestVote
import studio.ritmus.feedback.internal.RitmusLogger
import studio.ritmus.feedback.internal.transport.ApiClient
import studio.ritmus.feedback.internal.transport.Endpoints

// PORTED FROM: packages/sdk-react-native/src/Ritmus.ts (requests methods)
//
// HTTP wiring for the Feature Requests pillar. Public types in api/Requests.kt
// are not @Serializable yet; we hand-build JSON via kotlinx.serialization
// element builders to avoid changing the public data-class declarations.

internal data class RequestComment(
    val id: String,
    val requestId: String,
    val authorAnonymousId: String?,
    val authorRole: String?,
    val body: String,
    val createdAt: String,
    val updatedAt: String,
    val isFromTeam: Boolean,
)

internal class RequestsApi(
    private val apiClient: ApiClient,
    private val json: Json,
) {

    suspend fun list(
        anonymousId: String,
        externalId: String?,
        options: GetRequestsOptions,
    ): GetRequestsResult? {
        val query = buildMap<String, String?> {
            put("anonymousId", anonymousId)
            if (!externalId.isNullOrBlank()) put("externalId", externalId)
            options.sort?.let { put("sort", it.raw) }
            options.statuses?.takeIf { it.isNotEmpty() }
                ?.let { put("statuses", it.joinToString(",") { s -> s.raw }) }
            options.mine?.let { put("mine", it.raw) }
            options.q?.takeIf { it.isNotEmpty() }?.let { put("q", it) }
            options.cursor?.let { put("cursor", it) }
            options.limit?.let { put("limit", it.toString()) }
        }
        val raw = runCatching {
            apiClient.getJson(
                path = Endpoints.REQUESTS,
                query = query,
                deserializer = JsonObject.serializer(),
            )
        }.getOrNull() ?: return null
        val items = raw["items"]?.jsonArray?.mapNotNull { decodeSummary(it.jsonObject) } ?: emptyList()
        val nextCursor = raw["nextCursor"]?.jsonPrimitive?.contentOrNull
        return GetRequestsResult(items, nextCursor)
    }

    suspend fun getOne(
        requestId: String,
        anonymousId: String,
        externalId: String?,
    ): FeatureRequest? {
        val query = buildMap<String, String?> {
            put("anonymousId", anonymousId)
            if (!externalId.isNullOrBlank()) put("externalId", externalId)
        }
        val raw = runCatching {
            apiClient.getJson(
                path = Endpoints.request(requestId),
                query = query,
                deserializer = JsonObject.serializer(),
            )
        }.getOrNull() ?: return null
        return decodeRequest(raw)
    }

    suspend fun submit(
        anonymousId: String,
        externalId: String?,
        title: String,
        description: String,
    ): FeatureRequest? {
        val body = buildJsonObject {
            put("anonymousId", anonymousId)
            if (!externalId.isNullOrBlank()) put("externalId", externalId)
            put("title", title)
            put("description", description)
        }
        val raw = runCatching {
            apiClient.postJsonWithResponse(
                path = Endpoints.REQUESTS,
                body = body,
                serializer = JsonObject.serializer(),
                deserializer = JsonObject.serializer(),
            )
        }.getOrNull() ?: return null
        return decodeRequest(raw)
    }

    suspend fun vote(
        requestId: String,
        anonymousId: String,
        externalId: String?,
        vote: Boolean,
    ): RequestVote? {
        val body = buildJsonObject {
            put("anonymousId", anonymousId)
            if (!externalId.isNullOrBlank()) put("externalId", externalId)
            put("vote", vote)
        }
        val raw = runCatching {
            apiClient.postJsonWithResponse(
                path = Endpoints.requestVote(requestId),
                body = body,
                serializer = JsonObject.serializer(),
                deserializer = JsonObject.serializer(),
            )
        }.getOrNull() ?: return null
        return RequestVote(
            requestId = raw["requestId"]?.jsonPrimitive?.content ?: requestId,
            upvoted = raw["upvoted"]?.jsonPrimitive?.boolean ?: vote,
            followed = raw["followed"]?.jsonPrimitive?.boolean ?: false,
            upvoteCount = raw["upvoteCount"]?.jsonPrimitive?.int ?: 0,
            followerCount = raw["followerCount"]?.jsonPrimitive?.int ?: 0,
        )
    }

    suspend fun follow(
        requestId: String,
        anonymousId: String,
        externalId: String?,
        follow: Boolean,
    ): RequestFollow? {
        val body = buildJsonObject {
            put("anonymousId", anonymousId)
            if (!externalId.isNullOrBlank()) put("externalId", externalId)
            put("follow", follow)
        }
        val raw = runCatching {
            apiClient.postJsonWithResponse(
                path = Endpoints.requestFollow(requestId),
                body = body,
                serializer = JsonObject.serializer(),
                deserializer = JsonObject.serializer(),
            )
        }.getOrNull() ?: return null
        val sourceRaw = raw["source"]?.jsonPrimitive?.content ?: "manual"
        val source = RequestFollowSource.values().firstOrNull { it.raw == sourceRaw }
            ?: RequestFollowSource.MANUAL
        return RequestFollow(
            requestId = raw["requestId"]?.jsonPrimitive?.content ?: requestId,
            following = raw["following"]?.jsonPrimitive?.boolean ?: follow,
            followerCount = raw["followerCount"]?.jsonPrimitive?.int ?: 0,
            source = source,
        )
    }

    suspend fun comments(
        requestId: String,
        anonymousId: String,
        externalId: String?,
    ): List<RequestComment>? {
        val query = buildMap<String, String?> {
            put("anonymousId", anonymousId)
            if (!externalId.isNullOrBlank()) put("externalId", externalId)
        }
        val raw = runCatching {
            apiClient.getJson(
                path = Endpoints.requestComments(requestId),
                query = query,
                deserializer = JsonObject.serializer(),
            )
        }.getOrNull() ?: return null
        return raw["items"]?.jsonArray?.mapNotNull { decodeComment(it.jsonObject) }
    }

    suspend fun postComment(
        requestId: String,
        anonymousId: String,
        externalId: String?,
        body: String,
    ): RequestComment? {
        val payload = buildJsonObject {
            put("anonymousId", anonymousId)
            if (!externalId.isNullOrBlank()) put("externalId", externalId)
            put("body", body)
        }
        val raw = runCatching {
            apiClient.postJsonWithResponse(
                path = Endpoints.requestComments(requestId),
                body = payload,
                serializer = JsonObject.serializer(),
                deserializer = JsonObject.serializer(),
            )
        }.getOrNull() ?: return null
        return decodeComment(raw)
    }

    // --- decoders ---

    private fun decodeRequest(obj: JsonObject): FeatureRequest? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val statusRaw = obj["status"]?.jsonPrimitive?.contentOrNull ?: "under_review"
        return FeatureRequest(
            id = id,
            appId = obj["appId"]?.jsonPrimitive?.contentOrNull ?: "",
            title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            status = RequestStatus.fromRaw(statusRaw),
            devResponse = obj["devResponse"]?.jsonPrimitive?.contentOrNull,
            upvoteCount = obj["upvoteCount"]?.jsonPrimitive?.intOrNull ?: 0,
            followerCount = obj["followerCount"]?.jsonPrimitive?.intOrNull ?: 0,
            createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
            updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull ?: "",
            statusChangedAt = obj["statusChangedAt"]?.jsonPrimitive?.contentOrNull ?: "",
            lastRespondedAt = obj["lastRespondedAt"]?.jsonPrimitive?.contentOrNull,
            viewerHasUpvoted = obj["viewerHasUpvoted"]?.jsonPrimitive?.booleanOrNull ?: false,
            viewerIsFollowing = obj["viewerIsFollowing"]?.jsonPrimitive?.booleanOrNull ?: false,
            viewerIsSubmitter = obj["viewerIsSubmitter"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun decodeSummary(obj: JsonObject): RequestSummary? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val statusRaw = obj["status"]?.jsonPrimitive?.contentOrNull ?: "under_review"
        return RequestSummary(
            id = id,
            title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
            status = RequestStatus.fromRaw(statusRaw),
            upvoteCount = obj["upvoteCount"]?.jsonPrimitive?.intOrNull ?: 0,
            followerCount = obj["followerCount"]?.jsonPrimitive?.intOrNull ?: 0,
            createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
            statusChangedAt = obj["statusChangedAt"]?.jsonPrimitive?.contentOrNull ?: "",
            viewerHasUpvoted = obj["viewerHasUpvoted"]?.jsonPrimitive?.booleanOrNull ?: false,
            viewerIsFollowing = obj["viewerIsFollowing"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    private fun decodeComment(obj: JsonObject): RequestComment? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        return RequestComment(
            id = id,
            requestId = obj["requestId"]?.jsonPrimitive?.contentOrNull ?: "",
            authorAnonymousId = obj["authorAnonymousId"]?.jsonPrimitive?.contentOrNull,
            authorRole = obj["authorRole"]?.jsonPrimitive?.contentOrNull,
            body = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
            createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
            updatedAt = obj["updatedAt"]?.jsonPrimitive?.contentOrNull ?: "",
            isFromTeam = obj["isFromTeam"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }
}
