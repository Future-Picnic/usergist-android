package studio.usergist.feedback.internal.ui.requests

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import studio.usergist.feedback.UserGist
import studio.usergist.feedback.api.FeatureRequest
import studio.usergist.feedback.api.GetRequestsOptions
import studio.usergist.feedback.api.RequestSort
import studio.usergist.feedback.api.RequestSummary
import studio.usergist.feedback.internal.requests.RequestComment

// PORTED FROM: packages/sdk-react-native/src/ui/RequestsBoard.tsx
//
// Lightweight native Activity that drives the Feature Requests board and
// detail surfaces. Uses raw Android views to avoid pulling in a Compose
// dependency the SDK's host apps might not have. The host can replace it
// by listening to `RequestsHandlers` and presenting their own UI.

object RequestsBoardLauncher {
    private const val EXTRA_GENERATION = "studio.usergist.feedback.requests.generation"
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val activities = java.util.WeakHashMap<RequestsBoardActivity, Long>()

    internal fun attach(activity: RequestsBoardActivity): Boolean {
        val captured = activity.intent.getLongExtra(EXTRA_GENERATION, -1L)
        if (captured != generation.get()) return false
        activities[activity] = captured
        return true
    }

    internal fun detach(activity: RequestsBoardActivity) { activities.remove(activity) }

    internal fun reset() {
        val current = generation.incrementAndGet()
        Handler(Looper.getMainLooper()).post {
            activities.entries.toList().filter { it.value < current }.forEach { (activity, _) ->
                activity.finish()
                activities.remove(activity)
            }
        }
    }
    private const val EXTRA_MODE = "studio.usergist.feedback.requests.mode"
    private const val EXTRA_REQUEST_ID = "studio.usergist.feedback.requests.request_id"
    internal const val MODE_BOARD = "board"
    internal const val MODE_DETAIL = "detail"

    fun openBoard(ctx: Context) {
        val intent = Intent(ctx, RequestsBoardActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_BOARD)
            putExtra(EXTRA_GENERATION, generation.get())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun openDetail(ctx: Context, requestId: String) {
        val intent = Intent(ctx, RequestsBoardActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_DETAIL)
            putExtra(EXTRA_GENERATION, generation.get())
            putExtra(EXTRA_REQUEST_ID, requestId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    internal fun extractMode(intent: Intent): String =
        intent.getStringExtra(EXTRA_MODE) ?: MODE_BOARD

    internal fun extractRequestId(intent: Intent): String? =
        intent.getStringExtra(EXTRA_REQUEST_ID)
}

class RequestsBoardActivity : AppCompatActivity() {
    private lateinit var contentLayout: LinearLayout
    private var mode = RequestsBoardLauncher.MODE_BOARD
    private var currentRequestId: String? = null
    private var accentColor: Int = Color.rgb(91, 75, 255)
    private var boardLabel: String = "Suggestions"
    private var boardItems: List<RequestSummary> = emptyList()
    private var boardVoteInFlight: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RequestsBoardLauncher.attach(this)) { finish(); return }
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(250, 250, 250))
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        root.addView(contentLayout)
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) {
            if (mode == RequestsBoardLauncher.MODE_BOARD) finish() else loadBoard()
        }

        UserGist.getRequestBranding { _, branding ->
            runOnUiThread {
                boardLabel = branding?.entryLabel?.takeIf { it.isNotBlank() } ?: boardLabel
                accentColor = parseColor(branding?.accentColor) ?: accentColor
                if (mode == RequestsBoardLauncher.MODE_BOARD) supportActionBar?.title = boardLabel
            }
        }

        when (RequestsBoardLauncher.extractMode(intent)) {
            RequestsBoardLauncher.MODE_DETAIL -> {
                val rid = RequestsBoardLauncher.extractRequestId(intent) ?: return
                loadDetail(rid)
            }
            else -> loadBoard()
        }
    }

    override fun onDestroy() {
        RequestsBoardLauncher.detach(this)
        super.onDestroy()
    }

    private fun loadBoard() {
        mode = RequestsBoardLauncher.MODE_BOARD
        currentRequestId = null
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = boardLabel
        showLoading()
        UserGist.getRequests(GetRequestsOptions(sort = RequestSort.TOP, limit = 50)) { err, page ->
            runOnUiThread {
                if (err != null || page == null) {
                    showError("Unable to load requests")
                    return@runOnUiThread
                }
                renderBoard(page.items)
            }
        }
    }

    private fun renderBoard(items: List<RequestSummary>) {
        boardItems = items
        contentLayout.removeAllViews()
        contentLayout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heading(boardLabel), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(primaryButton("+ New", "Create new request") { loadSubmit() })
        })
        contentLayout.addView(spacer(12))
        contentLayout.addView(caption("${items.size} ${if (items.size == 1) "idea" else "ideas"}"))
        contentLayout.addView(spacer(12))
        for (row in items) {
            contentLayout.addView(boardRow(row))
            contentLayout.addView(spacer(10))
        }
    }

    private fun boardRow(row: RequestSummary): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(Color.WHITE, 16)

            addView(Button(this@RequestsBoardActivity).apply {
                text = "▲\n${row.upvoteCount}"
                textSize = 13f
                isAllCaps = false
                minWidth = dp(52)
                minHeight = dp(52)
                contentDescription = if (boardVoteInFlight == row.id) {
                    "Updating vote for ${row.title}"
                } else if (row.viewerHasUpvoted) {
                    "Remove vote from ${row.title}"
                } else {
                    "Upvote ${row.title}"
                }
                isEnabled = boardVoteInFlight == null
                backgroundTintList = ColorStateList.valueOf(
                    if (row.viewerHasUpvoted) accentColor else withAlpha(accentColor, 24),
                )
                setTextColor(if (row.viewerHasUpvoted) Color.WHITE else accentColor)
                setOnClickListener {
                    val before = boardItems
                    val vote = !row.viewerHasUpvoted
                    val optimistic = row.copy(
                        upvoteCount = (row.upvoteCount + if (vote) 1 else -1).coerceAtLeast(0),
                        followerCount = (
                            row.followerCount + if (vote && !row.viewerIsFollowing) 1 else 0
                        ).coerceAtLeast(0),
                        viewerHasUpvoted = vote,
                        viewerIsFollowing = if (vote) true else row.viewerIsFollowing,
                    )
                    boardVoteInFlight = row.id
                    renderBoard(boardItems.map { if (it.id == row.id) optimistic else it })
                    UserGist.voteOnRequest(row.id, vote) { error, result ->
                        runOnUiThread {
                            boardVoteInFlight = null
                            if (error != null || result == null) {
                                renderBoard(before)
                                toast("Vote failed")
                            } else {
                                renderBoard(boardItems.map {
                                    if (it.id == row.id) {
                                        it.copy(
                                            upvoteCount = result.upvoteCount,
                                            followerCount = result.followerCount,
                                            viewerHasUpvoted = result.upvoted,
                                            viewerIsFollowing = result.followed,
                                        )
                                    } else {
                                        it
                                    }
                                })
                            }
                        }
                    }
                }
            })
            addView(spacer(12, horizontal = true))
            addView(LinearLayout(this@RequestsBoardActivity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = "Open request ${row.title}"
                setOnClickListener { loadDetail(row.id) }
                addView(TextView(this@RequestsBoardActivity).apply {
                    text = row.title
                    textSize = 16f
                    setTextColor(Color.rgb(17, 17, 17))
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(spacer(4))
                addView(TextView(this@RequestsBoardActivity).apply {
                    text = row.description
                    textSize = 13f
                    maxLines = 2
                    setTextColor(Color.rgb(107, 114, 128))
                })
                addView(spacer(8))
                addView(caption("${statusLabel(row.status.raw)}  •  ${row.followerCount} followers"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun loadSubmit() {
        mode = "submit"
        currentRequestId = null
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "New suggestion"
        contentLayout.removeAllViews()

        contentLayout.addView(caption("Tell us what you'd like to see. Other users can upvote your idea, and the team will respond as work progresses."))
        contentLayout.addView(spacer(20))
        contentLayout.addView(fieldLabel("Title"))
        val titleInput = input("A short, clear title", "Suggestion title", multiline = false).apply {
            filters = arrayOf(InputFilter.LengthFilter(120))
        }
        contentLayout.addView(titleInput)
        contentLayout.addView(spacer(16))
        contentLayout.addView(fieldLabel("Description"))
        val descriptionInput = input(
            "What does this do? Who is it for? Why does it matter?",
            "Suggestion description",
            multiline = true,
        ).apply {
            filters = arrayOf(InputFilter.LengthFilter(1500))
            minLines = 6
        }
        contentLayout.addView(descriptionInput)
        contentLayout.addView(spacer(20))
        lateinit var postButton: Button
        var posting = false
        postButton = primaryButton("Post", "Post") {
            if (posting) return@primaryButton
            val cleanTitle = titleInput.text.toString().trim()
            val cleanDescription = descriptionInput.text.toString().trim()
            if (cleanTitle.isEmpty() || cleanDescription.isEmpty()) {
                toast("Title and description are required")
                return@primaryButton
            }
            posting = true
            postButton.isEnabled = false
            postButton.text = "Posting…"
            postButton.contentDescription = "Posting request"
            titleInput.isEnabled = false
            descriptionInput.isEnabled = false
            showLoading()
            UserGist.submitRequest(cleanTitle, cleanDescription) { error, request ->
                runOnUiThread {
                    if (error != null || request == null) {
                        loadSubmit()
                        toast("Could not submit request")
                    } else {
                        loadDetail(request.id)
                    }
                }
            }
        }
        contentLayout.addView(postButton)
    }

    private fun loadDetail(requestId: String) {
        mode = RequestsBoardLauncher.MODE_DETAIL
        currentRequestId = requestId
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Suggestion"
        showLoading()
        UserGist.getRequest(requestId) { err, req ->
            runOnUiThread {
                contentLayout.removeAllViews()
                if (err != null || req == null) {
                    showError("Unable to load request")
                    return@runOnUiThread
                }
                renderDetail(req)
            }
        }
    }

    private fun renderDetail(req: FeatureRequest, mutationInFlight: Boolean = false) {
        contentLayout.addView(caption(statusLabel(req.status.raw).uppercase()))
        contentLayout.addView(spacer(10))
        contentLayout.addView(heading(req.title))
        contentLayout.addView(spacer(16))
        contentLayout.addView(TextView(this).apply {
            text = "${req.upvoteCount} upvotes  •  ${req.followerCount} followers"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedBackground(Color.WHITE, 14)
        })
        contentLayout.addView(spacer(20))
        contentLayout.addView(TextView(this).apply {
            text = req.description
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
        })
        if (!req.devResponse.isNullOrBlank()) {
            contentLayout.addView(TextView(this).apply {
                text = req.devResponse
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = roundedBackground(Color.WHITE, 12)
            })
        }
        contentLayout.addView(spacer(20))
        contentLayout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val voteButton = primaryButton(
                if (req.viewerHasUpvoted) "▲ Upvoted (${req.upvoteCount})" else "▲ Upvote (${req.upvoteCount})",
                if (mutationInFlight) {
                    "Updating request vote"
                } else if (req.viewerHasUpvoted) {
                    "Remove request vote"
                } else {
                    "Upvote request"
                },
            ) {
                if (mutationInFlight) return@primaryButton
                val vote = !req.viewerHasUpvoted
                val optimistic = req.copy(
                    upvoteCount = (req.upvoteCount + if (vote) 1 else -1).coerceAtLeast(0),
                    followerCount = (
                        req.followerCount + if (vote && !req.viewerIsFollowing) 1 else 0
                    ).coerceAtLeast(0),
                    viewerHasUpvoted = vote,
                    viewerIsFollowing = if (vote) true else req.viewerIsFollowing,
                )
                replaceDetail(optimistic, mutationInFlight = true)
                UserGist.voteOnRequest(req.id, vote) { error, result ->
                    runOnUiThread {
                        if (error != null || result == null) {
                            replaceDetail(req)
                            toast("Vote failed")
                        } else {
                            replaceDetail(optimistic.copy(
                                upvoteCount = result.upvoteCount,
                                followerCount = result.followerCount,
                                viewerHasUpvoted = result.upvoted,
                                viewerIsFollowing = result.followed,
                            ))
                        }
                    }
                }
            }.apply { isEnabled = !mutationInFlight }
            addView(voteButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(spacer(8, horizontal = true))
            val followButton = secondaryButton(
                if (req.viewerIsFollowing) "✓ Following" else "Follow",
                if (mutationInFlight) {
                    "Updating request follow"
                } else if (req.viewerIsFollowing) {
                    "Unfollow request"
                } else {
                    "Follow request"
                },
            ) {
                if (mutationInFlight) return@secondaryButton
                val follow = !req.viewerIsFollowing
                val optimistic = req.copy(
                    followerCount = (
                        req.followerCount + if (follow) 1 else -1
                    ).coerceAtLeast(0),
                    viewerIsFollowing = follow,
                )
                replaceDetail(optimistic, mutationInFlight = true)
                UserGist.followRequest(req.id, follow) { error, result ->
                    runOnUiThread {
                        if (error != null || result == null) {
                            replaceDetail(req)
                            toast("Follow failed")
                        } else {
                            replaceDetail(optimistic.copy(
                                followerCount = result.followerCount,
                                viewerIsFollowing = result.following,
                            ))
                        }
                    }
                }
            }.apply { isEnabled = !mutationInFlight }
            addView(followButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        contentLayout.addView(spacer(28))
        val commentsLabel = fieldLabel("COMMENTS")
        contentLayout.addView(commentsLabel)
        val commentsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentLayout.addView(commentsContainer)
        contentLayout.addView(spacer(12))
        val commentInput = input("Add a comment…", "Request comment", multiline = true).apply {
            filters = arrayOf(InputFilter.LengthFilter(1000))
            minLines = 3
        }
        contentLayout.addView(commentInput)
        contentLayout.addView(spacer(8))
        val renderedComments = mutableListOf<RequestComment>()
        fun renderComments(error: Boolean = false) {
            commentsContainer.removeAllViews()
            commentsLabel.text = if (renderedComments.isEmpty()) {
                "COMMENTS"
            } else {
                "COMMENTS · ${renderedComments.size}"
            }
            when {
                error -> commentsContainer.addView(caption("Unable to load comments"))
                renderedComments.isEmpty() -> commentsContainer.addView(
                    caption("No comments yet — be the first to weigh in."),
                )
                else -> renderedComments.forEach { comment ->
                    commentsContainer.addView(commentView(comment))
                }
            }
        }
        lateinit var commentPostButton: Button
        var postingComment = false
        commentPostButton = primaryButton("Post", "Post request comment") {
            if (postingComment) return@primaryButton
            val body = commentInput.text.toString().trim()
            if (body.isEmpty()) return@primaryButton
            postingComment = true
            commentInput.setText("")
            commentPostButton.isEnabled = false
            commentPostButton.text = "Posting…"
            commentPostButton.contentDescription = "Posting request comment"
            UserGist.postComment(req.id, body) { error, created ->
                runOnUiThread {
                    postingComment = false
                    commentPostButton.isEnabled = true
                    commentPostButton.text = "Post"
                    commentPostButton.contentDescription = "Post request comment"
                    if (error != null || created == null) {
                        if (commentInput.text.isNullOrEmpty()) commentInput.setText(body)
                        toast("Could not post comment")
                    } else {
                        if (renderedComments.none { it.id == created.id }) {
                            renderedComments += created
                        }
                        renderComments()
                    }
                }
            }
        }
        contentLayout.addView(commentPostButton)
        UserGist.getComments(req.id) { error, comments ->
            runOnUiThread {
                if (error != null) {
                    renderComments(error = renderedComments.isEmpty())
                } else {
                    val fetched = comments.orEmpty()
                    val fetchedIds = fetched.mapTo(mutableSetOf()) { it.id }
                    val localOnly = renderedComments.filterNot { it.id in fetchedIds }
                    renderedComments.clear()
                    renderedComments += fetched
                    renderedComments += localOnly
                    renderComments()
                }
            }
        }
    }

    private fun commentView(comment: RequestComment): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBackground(Color.WHITE, 10)
        addView(caption(if (comment.viewerIsAuthor) "You" else "Anonymous"))
        addView(TextView(this@RequestsBoardActivity).apply {
            text = comment.body
            contentDescription = "Request comment body: ${comment.body}"
            textSize = 14f
            setTextColor(Color.rgb(31, 41, 55))
        })
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(8)
        layoutParams = params
    }

    private fun replaceDetail(req: FeatureRequest, mutationInFlight: Boolean = false) {
        if (mode != RequestsBoardLauncher.MODE_DETAIL || currentRequestId != req.id) return
        contentLayout.removeAllViews()
        renderDetail(req, mutationInFlight)
    }

    private fun showLoading() {
        contentLayout.removeAllViews()
        contentLayout.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accentColor)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
    }

    private fun showError(message: String) {
        contentLayout.removeAllViews()
        contentLayout.addView(heading(message))
        contentLayout.addView(spacer(16))
        contentLayout.addView(primaryButton("Try again", "Try again") {
            currentRequestId?.let(::loadDetail) ?: loadBoard()
        })
    }

    private fun heading(value: String) = TextView(this).apply {
        text = value
        textSize = 22f
        setTextColor(Color.rgb(17, 17, 17))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun fieldLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.rgb(55, 65, 81))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun caption(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(Color.rgb(107, 114, 128))
    }

    private fun input(hintValue: String, accessibilityLabel: String, multiline: Boolean) = EditText(this).apply {
        hint = hintValue
        contentDescription = accessibilityLabel
        textSize = 15f
        setTextColor(Color.rgb(17, 17, 17))
        setHintTextColor(Color.rgb(156, 163, 175))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedStrokeBackground(Color.WHITE, Color.rgb(229, 231, 235), 12)
        inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
    }

    private fun primaryButton(label: String, accessibilityLabel: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            contentDescription = accessibilityLabel
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(accentColor)
            setOnClickListener { action() }
        }

    private fun secondaryButton(label: String, accessibilityLabel: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            contentDescription = accessibilityLabel
            setTextColor(Color.rgb(17, 17, 17))
            backgroundTintList = ColorStateList.valueOf(Color.rgb(243, 244, 246))
            setOnClickListener { action() }
        }

    private fun spacer(size: Int, horizontal: Boolean = false) = View(this).apply {
        layoutParams = if (horizontal) {
            LinearLayout.LayoutParams(dp(size), 1)
        } else {
            LinearLayout.LayoutParams(1, dp(size))
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun roundedStrokeBackground(color: Int, strokeColor: Int, radiusDp: Int) =
        roundedBackground(color, radiusDp).apply { setStroke(dp(1), strokeColor) }

    private fun parseColor(value: String?): Int? = try {
        value?.let(Color::parseColor)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha,
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun statusLabel(raw: String): String = raw
        .split('_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        loadBoard()
        return true
    }

}
