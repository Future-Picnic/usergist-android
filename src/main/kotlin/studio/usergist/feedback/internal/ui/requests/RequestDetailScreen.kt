package studio.usergist.feedback.internal.ui.requests

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.content.res.ColorStateList
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import studio.usergist.feedback.UserGist
import studio.usergist.feedback.api.FeatureRequest
import studio.usergist.feedback.internal.requests.RequestComment
import studio.usergist.feedback.internal.ui.surface.CONTENT_MAX_WIDTH_DP
import studio.usergist.feedback.internal.ui.surface.Glyph
import studio.usergist.feedback.internal.ui.surface.GlyphView
import studio.usergist.feedback.internal.ui.surface.MeasureLayout
import studio.usergist.feedback.internal.ui.surface.PressFrame
import studio.usergist.feedback.internal.ui.surface.SurfaceButton
import studio.usergist.feedback.internal.ui.surface.SurfaceField
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.accessibilityHeading
import studio.usergist.feedback.internal.ui.surface.announceAsButton
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.keepFocusedFieldVisible
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceText

private const val COMMENT_MAX = 1000

/** What the detail screen needs from its activity. */
internal interface RequestsScreenHost {
    val activity: Activity
    val palette: SurfacePalette
    fun isShowingDetail(requestId: String): Boolean
    fun toast(message: String)
}

/**
 * Suggestion detail (port of RN DetailView + DetailContent + CommentsSection).
 * Body scrolls; the vote/follow bar is pinned below it. Votes and follows are
 * optimistic and roll back on failure, as before.
 */
internal class RequestDetailScreen(
    private val host: RequestsScreenHost,
    initial: FeatureRequest,
) {
    private val ctx = host.activity
    private val palette = host.palette
    private var request = initial
    private var mutationInFlight = false
    private val comments = mutableListOf<RequestComment>()
    private var commentsLoading = true
    private var commentsError = false
    private var editingId: String? = null
    private var posting = false

    private val stats = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val commentsTitle = ctx.surfaceText(palette, "Comments", 17f, palette.text, 700).apply { accessibilityHeading() }
    private val commentsList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val composer = SurfaceField(ctx, palette, "Add a comment", "Request comment", multiline = true, minHeightDp = 72, maxLength = COMMENT_MAX)
    private val postButton = SurfaceButton(ctx, palette, "Post comment", compact = true)
    private val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    val body: View = buildBody()
    val bottomBar: View = buildBottomBar()

    init {
        composer.onTextChanged = { refreshPost() }
        postButton.setOnAction { postComment() }
        refreshPost()
        refreshMeta()
        renderComments()
        loadComments()
    }

    private fun buildBody(): View {
        val measure = MeasureLayout(ctx, ctx.dpPx(CONTENT_MAX_WIDTH_DP)).apply {
            orientation = LinearLayout.VERTICAL
            addView(ctx.statusPill(palette, request.status), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ctx.surfaceText(palette, request.title, 24f, palette.text, 700, 30f).apply {
                letterSpacing = -0.012f
                accessibilityHeading()
            }, matchWidth().apply { topMargin = ctx.dpPx(12) })
            addView(stats, matchWidth().apply { topMargin = ctx.dpPx(10) })
            addView(ctx.surfaceText(palette, request.description, 16f, palette.text, 400, 24f), matchWidth().apply { topMargin = ctx.dpPx(18) })
            addView(responseBlock(), matchWidth().apply { topMargin = ctx.dpPx(24) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(commentsTitle, matchWidth().apply { bottomMargin = ctx.dpPx(12) })
                addView(composer, matchWidth())
                addView(postButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                    topMargin = ctx.dpPx(4)
                })
                addView(commentsList, matchWidth())
            }, matchWidth().apply { topMargin = ctx.dpPx(32) })
        }
        val padded = FrameLayout(ctx).apply {
            setPadding(ctx.dpPx(24), ctx.dpPx(20), ctx.dpPx(24), ctx.dpPx(32))
            addView(measure, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        return ScrollView(ctx).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(padded, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Composer and inline edit fields stay fully visible above the keyboard.
            keepFocusedFieldVisible(ctx.dpPx(16))
        }
    }

    private fun responseBlock(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dpPx(16), ctx.dpPx(16), ctx.dpPx(16), ctx.dpPx(16))
        val radius = ctx.dpF(palette.radius(16))
        val response = request.devResponse?.takeIf { it.isNotBlank() }
        if (response != null) {
            background = roundRect(palette.tint, radius)
            addView(ctx.surfaceText(palette, "Update from the team", 15f, palette.primary, 700), matchWidth())
            addView(ctx.surfaceText(palette, response, 15f, palette.text, 400, 22f), matchWidth().apply { topMargin = ctx.dpPx(6) })
        } else {
            background = roundRect(palette.fill, radius)
            addView(ctx.surfaceText(palette, "No update yet", 15f, palette.text, 700), matchWidth())
            addView(ctx.surfaceText(palette, "Follow this suggestion to hear when the team posts an update.", 15f, palette.subtext, 400, 22f),
                matchWidth().apply { topMargin = ctx.dpPx(6) })
        }
    }

    private fun buildBottomBar(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
        addView(View(ctx).apply { setBackgroundColor(palette.border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val measure = MeasureLayout(ctx, ctx.dpPx(CONTENT_MAX_WIDTH_DP)).apply {
            orientation = LinearLayout.VERTICAL
            addView(actions, matchWidth())
        }
        addView(FrameLayout(ctx).apply {
            setPadding(ctx.dpPx(16), ctx.dpPx(12), ctx.dpPx(16), ctx.dpPx(12))
            addView(measure, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }, matchWidth())
    }

    // ---------------- Vote / follow ----------------

    private fun refreshMeta() {
        stats.removeAllViews()
        stats.addView(ctx.statText(palette, request.upvoteCount, "upvote", "upvotes"))
        stats.addView(ctx.statText(palette, request.followerCount, "follower", "followers"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpPx(16) })

        actions.removeAllViews()
        val voted = request.viewerHasUpvoted
        val following = request.viewerIsFollowing
        val vote = actionButton(
            label = if (voted) "Upvoted, ${request.upvoteCount}" else "Upvote, ${request.upvoteCount}",
            description = when {
                mutationInFlight -> "Updating request vote"
                voted -> "Remove request vote"
                else -> "Upvote request"
            },
            fill = if (voted) palette.primary else palette.tint,
            fg = if (voted) palette.onPrimary else palette.primary,
            outline = null,
            chevron = true,
            selected = voted,
        ) { toggleVote() }
        val follow = actionButton(
            label = if (following) "Following" else "Follow",
            description = when {
                mutationInFlight -> "Updating request follow"
                following -> "Unfollow request"
                else -> "Follow request"
            },
            fill = if (following) palette.fill else 0,
            fg = palette.text,
            outline = if (following) palette.quietOutline else palette.border,
            chevron = false,
            selected = following,
        ) { toggleFollow() }
        actions.addView(vote, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(follow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dpPx(10) })
    }

    private fun actionButton(
        label: String,
        description: String,
        fill: Int,
        fg: Int,
        outline: Int?,
        chevron: Boolean,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = PressFrame(ctx, 0.82f).apply {
        isClickable = true
        isFocusable = true
        isEnabled = !mutationInFlight
        isSelected = selected
        announceAsButton()
        contentDescription = description
        minimumHeight = ctx.dpPx(48)
        setPadding(ctx.dpPx(16), ctx.dpPx(10), ctx.dpPx(16), ctx.dpPx(10))
        background = roundRect(fill, ctx.dpF(24), if (outline != null) ctx.dpPx(1.5f) else 0, outline ?: 0)
        alpha = if (mutationInFlight) 0.7f else 1f
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (chevron) addView(GlyphView(ctx, Glyph.UP, fg), LinearLayout.LayoutParams(ctx.dpPx(14), ctx.dpPx(12)).apply { marginEnd = ctx.dpPx(8) })
            addView(ctx.surfaceText(palette, label, 16f, fg, 700, gravity = Gravity.CENTER))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setOnClickListener { if (!mutationInFlight) onClick() }
    }

    private fun apply(next: FeatureRequest, inFlight: Boolean) {
        if (!host.isShowingDetail(next.id)) return
        request = next
        mutationInFlight = inFlight
        refreshMeta()
    }

    private fun toggleVote() {
        val before = request
        val vote = !before.viewerHasUpvoted
        val optimistic = before.copy(
            upvoteCount = (before.upvoteCount + if (vote) 1 else -1).coerceAtLeast(0),
            followerCount = (before.followerCount + if (vote && !before.viewerIsFollowing) 1 else 0).coerceAtLeast(0),
            viewerHasUpvoted = vote,
            viewerIsFollowing = if (vote) true else before.viewerIsFollowing,
        )
        apply(optimistic, inFlight = true)
        UserGist.voteOnRequest(before.id, vote) { error, result ->
            host.activity.runOnUiThread {
                if (error != null || result == null) {
                    apply(before, inFlight = false)
                    host.toast("Vote failed")
                } else {
                    apply(optimistic.copy(
                        upvoteCount = result.upvoteCount,
                        followerCount = result.followerCount,
                        viewerHasUpvoted = result.upvoted,
                        viewerIsFollowing = result.followed,
                    ), inFlight = false)
                }
            }
        }
    }

    private fun toggleFollow() {
        val before = request
        val follow = !before.viewerIsFollowing
        val optimistic = before.copy(
            followerCount = (before.followerCount + if (follow) 1 else -1).coerceAtLeast(0),
            viewerIsFollowing = follow,
        )
        apply(optimistic, inFlight = true)
        UserGist.followRequest(before.id, follow) { error, result ->
            host.activity.runOnUiThread {
                if (error != null || result == null) {
                    apply(before, inFlight = false)
                    host.toast("Follow failed")
                } else {
                    apply(optimistic.copy(
                        followerCount = result.followerCount,
                        viewerIsFollowing = result.following,
                    ), inFlight = false)
                }
            }
        }
    }

    // ---------------- Comments ----------------

    private fun refreshPost() {
        val trimmed = composer.value.trim()
        postButton.setDisabled(trimmed.isEmpty() || trimmed.length > COMMENT_MAX)
        postButton.loading = posting
    }

    private fun loadComments() {
        UserGist.getComments(request.id) { error, fetched ->
            host.activity.runOnUiThread {
                commentsLoading = false
                if (error != null) {
                    commentsError = comments.isEmpty()
                } else {
                    val items = fetched.orEmpty()
                    val ids = items.mapTo(mutableSetOf()) { it.id }
                    val localOnly = comments.filterNot { it.id in ids }
                    comments.clear()
                    comments += items
                    comments += localOnly
                    commentsError = false
                }
                renderComments()
            }
        }
    }

    private fun postComment() {
        if (posting) return
        val body = composer.value.trim()
        if (body.isEmpty()) return
        posting = true
        composer.setValue("")
        refreshPost()
        UserGist.postComment(request.id, body) { error, created ->
            host.activity.runOnUiThread {
                posting = false
                if (error != null || created == null) {
                    if (composer.value.isEmpty()) composer.setValue(body)
                    host.toast("Could not post comment")
                } else if (comments.none { it.id == created.id }) {
                    comments += created
                    commentsError = false
                }
                refreshPost()
                renderComments()
            }
        }
    }

    private fun renderComments() {
        commentsTitle.text = if (comments.isEmpty()) "Comments" else "Comments (${comments.size})"
        commentsList.removeAllViews()
        when {
            commentsLoading && comments.isEmpty() -> commentsList.addView(ProgressBar(ctx).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(palette.primary)
            }, LinearLayout.LayoutParams(ctx.dpPx(28), ctx.dpPx(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = ctx.dpPx(16)
            })
            commentsError -> commentsList.addView(ctx.surfaceText(palette, "Comments couldn't load. Reopen this suggestion to try again.", 15f, palette.subtext, 400, 21f),
                matchWidth().apply { topMargin = ctx.dpPx(16) })
            comments.isEmpty() -> commentsList.addView(ctx.surfaceText(palette, "No comments yet. Start the conversation.", 15f, palette.subtext, 400, 21f),
                matchWidth().apply { topMargin = ctx.dpPx(16) })
            else -> comments.forEach { commentsList.addView(commentRow(it), matchWidth().apply { topMargin = ctx.dpPx(4) }) }
        }
    }

    private fun commentRow(comment: RequestComment): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(ctx).apply { setBackgroundColor(palette.border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dpPx(14), 0, ctx.dpPx(14))
        }
        addView(inner, matchWidth())
        val mine = comment.viewerIsAuthor
        val editing = editingId == comment.id
        inner.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dpPx(24)
            addView(ctx.surfaceText(palette, if (mine) "You" else "Anonymous", 13f, if (mine) palette.primary else palette.text, 700).apply {
                setPadding(ctx.dpPx(8), ctx.dpPx(3), ctx.dpPx(8), ctx.dpPx(3))
                background = roundRect(if (mine) palette.tint else palette.fill, ctx.dpF(8))
            })
            val time = relativeTime(comment.createdAt) + if (comment.updatedAt != comment.createdAt) ", edited" else ""
            addView(ctx.surfaceText(palette, time, 13f, palette.subtext), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ctx.dpPx(8)
            })
            if (mine && !editing) {
                addView(PressFrame(ctx, 0.5f).apply {
                    isClickable = true
                    isFocusable = true
                    announceAsButton()
                    contentDescription = "Comment actions"
                    addView(GlyphView(ctx, Glyph.MORE, palette.subtext), FrameLayout.LayoutParams(ctx.dpPx(28), ctx.dpPx(16), Gravity.CENTER))
                    setOnClickListener { showCommentActions(comment) }
                }, LinearLayout.LayoutParams(ctx.dpPx(48), ctx.dpPx(48)).apply { topMargin = -ctx.dpPx(12); bottomMargin = -ctx.dpPx(12) })
            }
        }, matchWidth().apply { bottomMargin = ctx.dpPx(6) })
        if (editing) {
            inner.addView(editBlock(comment), matchWidth())
        } else {
            inner.addView(ctx.surfaceText(palette, comment.body, 15f, palette.text, 400, 22f).apply {
                contentDescription = "Request comment body: ${comment.body}"
            }, matchWidth())
        }
    }

    private fun showCommentActions(comment: RequestComment) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Comment")
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                if (which == 0) {
                    editingId = comment.id
                    renderComments()
                } else {
                    confirmDelete(comment)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editBlock(comment: RequestComment): View {
        val field = SurfaceField(ctx, palette, null, "Edit comment", multiline = true, minHeightDp = 72, maxLength = COMMENT_MAX, initial = comment.body)
        val save = SurfaceButton(ctx, palette, "Save", compact = true)
        fun refresh() {
            val trimmed = field.value.trim()
            save.setDisabled(trimmed.isEmpty() || trimmed.length > COMMENT_MAX)
        }
        field.onTextChanged = { refresh() }
        refresh()
        save.setOnAction {
            val body = field.value.trim()
            save.loading = true
            UserGist.editComment(request.id, comment.id, body) { error, updated ->
                host.activity.runOnUiThread {
                    save.loading = false
                    if (error != null || updated == null) {
                        host.toast("Could not save comment")
                        return@runOnUiThread
                    }
                    val index = comments.indexOfFirst { it.id == comment.id }
                    if (index >= 0) comments[index] = updated
                    editingId = null
                    renderComments()
                }
            }
        }
        val cancel = PressFrame(ctx, 0.5f).apply {
            isClickable = true
            isFocusable = true
            announceAsButton()
            contentDescription = "Cancel"
            minimumHeight = ctx.dpPx(48)
            minimumWidth = ctx.dpPx(64)
            setPadding(ctx.dpPx(12), 0, ctx.dpPx(12), 0)
            addView(ctx.surfaceText(palette, "Cancel", 15f, palette.text, 600), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener {
                editingId = null
                renderComments()
            }
        }
        field.editText.post { field.editText.requestFocus() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(field, matchWidth())
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(cancel)
                addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dpPx(8) })
            }, matchWidth().apply { topMargin = ctx.dpPx(4) })
        }
    }

    private fun confirmDelete(comment: RequestComment) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Delete comment?")
            .setMessage("This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val index = comments.indexOfFirst { it.id == comment.id }
                if (index < 0) return@setPositiveButton
                comments.removeAt(index)
                renderComments()
                UserGist.deleteComment(request.id, comment.id) { error ->
                    host.activity.runOnUiThread {
                        if (error != null) {
                            comments.add(index.coerceAtMost(comments.size), comment)
                            renderComments()
                            host.toast("Could not delete comment")
                        }
                    }
                }
            }
            .show()
    }
}
