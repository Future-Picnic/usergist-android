package studio.usergist.feedback.internal.requests

import studio.usergist.feedback.api.FeatureRequest
import studio.usergist.feedback.api.RequestFollow
import studio.usergist.feedback.api.RequestVote
import java.util.UUID
import kotlin.math.max

// PORTED FROM: packages/sdk-react-native/src/internal/requests.ts
//
// In-memory snapshot of recently-touched feature requests. Lets the SDK
// render upvote / follow counts immediately on tap, then reconcile against
// the server response. Spec §9 invariants enforced here, matching RN:
//   - Upvoting auto-creates a follow (followerCount += 1 only if the
//     user wasn't already following).
//   - Un-upvoting does NOT remove the follow.
//
// Each optimistic mutation captures the pre-mutation snapshot and returns
// a rollback closure. Concurrent taps each get their own captured snapshot,
// so rollback always restores the value seen at call time — never "the
// current value" at the moment of rollback.

internal class RequestsCache {

    private val store: MutableMap<String, FeatureRequest> = HashMap()
    private val listeners: MutableMap<UUID, (String, FeatureRequest) -> Unit> = HashMap()
    private val lock = Any()

    fun upsert(req: FeatureRequest) {
        synchronized(lock) {
            store[req.id] = req
        }
        emit(req.id, req)
    }

    fun upsertList(list: List<FeatureRequest>) {
        synchronized(lock) {
            for (r in list) store[r.id] = r
        }
    }

    fun get(id: String): FeatureRequest? = synchronized(lock) { store[id] }

    /**
     * Optimistically apply an upvote toggle. Returns a closure that restores
     * the captured pre-call snapshot. No-op if [id] is unknown.
     */
    fun applyOptimisticVote(id: String, vote: Boolean): () -> Unit {
        val before: FeatureRequest
        val next: FeatureRequest
        synchronized(lock) {
            before = store[id] ?: return { }
            val upvoteDelta = when {
                vote && !before.viewerHasUpvoted -> 1
                !vote && before.viewerHasUpvoted -> -1
                else -> 0
            }
            // Auto-follow: upvoting bumps follower count if not already following.
            val followDelta = if (vote && !before.viewerIsFollowing) 1 else 0
            next = before.copy(
                upvoteCount = max(0, before.upvoteCount + upvoteDelta),
                followerCount = max(0, before.followerCount + followDelta),
                viewerHasUpvoted = vote,
                viewerIsFollowing = if (vote) true else before.viewerIsFollowing,
            )
            store[id] = next
        }
        emit(id, next)
        return rollbackTo(id, before)
    }

    /**
     * Optimistically apply a follow toggle. Returns a closure that restores
     * the captured pre-call snapshot. No-op if [id] is unknown.
     */
    fun applyOptimisticFollow(id: String, follow: Boolean): () -> Unit {
        val before: FeatureRequest
        val next: FeatureRequest
        synchronized(lock) {
            before = store[id] ?: return { }
            val delta = when {
                follow && !before.viewerIsFollowing -> 1
                !follow && before.viewerIsFollowing -> -1
                else -> 0
            }
            next = before.copy(
                followerCount = max(0, before.followerCount + delta),
                viewerIsFollowing = follow,
            )
            store[id] = next
        }
        emit(id, next)
        return rollbackTo(id, before)
    }

    fun commitVote(id: String, result: RequestVote) {
        val next: FeatureRequest
        synchronized(lock) {
            val before = store[id] ?: return
            next = before.copy(
                upvoteCount = result.upvoteCount,
                followerCount = result.followerCount,
                viewerHasUpvoted = result.upvoted,
                viewerIsFollowing = result.followed,
            )
            store[id] = next
        }
        emit(id, next)
    }

    fun commitFollow(id: String, result: RequestFollow) {
        val next: FeatureRequest
        synchronized(lock) {
            val before = store[id] ?: return
            next = before.copy(
                followerCount = result.followerCount,
                viewerIsFollowing = result.following,
            )
            store[id] = next
        }
        emit(id, next)
    }

    /** Subscribe to mutations. Returns an unsubscribe closure. */
    fun subscribe(cb: (String, FeatureRequest) -> Unit): () -> Unit {
        val token = UUID.randomUUID()
        synchronized(lock) { listeners[token] = cb }
        return {
            synchronized(lock) { listeners.remove(token) }
        }
    }

    private fun rollbackTo(id: String, snapshot: FeatureRequest): () -> Unit = {
        synchronized(lock) { store[id] = snapshot }
        emit(id, snapshot)
    }

    private fun emit(id: String, req: FeatureRequest) {
        // Snapshot listeners so a listener that unsubscribes during its own
        // callback does not mutate the iterator.
        val snapshot = synchronized(lock) { listeners.values.toList() }
        for (cb in snapshot) cb(id, req)
    }
}
