package studio.usergist.feedback.internal.ui.requests

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import studio.usergist.feedback.UserGist
import studio.usergist.feedback.api.FeatureRequest
import studio.usergist.feedback.api.GetRequestsOptions
import studio.usergist.feedback.api.RequestSort
import studio.usergist.feedback.api.RequestStatus
import studio.usergist.feedback.api.RequestSummary
import studio.usergist.feedback.internal.ui.surface.CONTENT_MAX_WIDTH_DP
import studio.usergist.feedback.internal.ui.surface.MeasureLayout
import studio.usergist.feedback.internal.ui.surface.SurfaceButton
import studio.usergist.feedback.internal.ui.surface.SurfaceField
import studio.usergist.feedback.internal.ui.surface.SurfaceHeader
import studio.usergist.feedback.internal.ui.surface.SurfacePalette
import studio.usergist.feedback.internal.ui.surface.dpF
import studio.usergist.feedback.internal.ui.surface.dpPx
import studio.usergist.feedback.internal.ui.surface.keepFocusedFieldVisible
import studio.usergist.feedback.internal.ui.surface.matchWidth
import studio.usergist.feedback.internal.ui.surface.roundRect
import studio.usergist.feedback.internal.ui.surface.surfaceBack
import studio.usergist.feedback.internal.ui.surface.surfaceClose
import studio.usergist.feedback.internal.ui.surface.surfaceText

// PORTED FROM: packages/sdk-react-native/src/ui/requests/*
//
// Lightweight native Activity that drives the Feature Requests board,
// detail and new-suggestion surfaces with raw Android views (no Compose
// dependency). Colours come from the host theme plus the board accent.
// The host can replace it by listening to `RequestsHandlers`.

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
    private lateinit var root: LinearLayout
    private lateinit var headerHolder: FrameLayout
    private lateinit var bodyHolder: FrameLayout
    private lateinit var bottomHolder: FrameLayout
    private var mode = RequestsBoardLauncher.MODE_BOARD
    private var currentRequestId: String? = null
    private var accentColor: String? = null
    private var boardLabel: String = "Suggestions"
    private var boardItems: List<RequestSummary> = emptyList()
    private var boardVoteInFlight: String? = null
    private var statusFilter: RequestStatus? = null
    private var imeVisible = false
    private var palette: SurfacePalette = requestsPalette(null)

    private val screenHost = object : RequestsScreenHost {
        override val activity: Activity get() = this@RequestsBoardActivity
        override val palette: SurfacePalette get() = this@RequestsBoardActivity.palette
        override fun isShowingDetail(requestId: String): Boolean =
            mode == RequestsBoardLauncher.MODE_DETAIL && currentRequestId == requestId
        override fun toast(message: String) = this@RequestsBoardActivity.toast(message)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RequestsBoardLauncher.attach(this)) { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildShell()
        onBackPressedDispatcher.addCallback(this) {
            if (mode == RequestsBoardLauncher.MODE_BOARD) finish() else loadBoard()
        }

        UserGist.getRequestBranding { _, branding ->
            runOnUiThread {
                boardLabel = branding?.entryLabel?.takeIf { it.isNotBlank() } ?: boardLabel
                val accent = branding?.accentColor?.takeIf { it.isNotBlank() }
                if (accent != accentColor) {
                    accentColor = accent
                    palette = requestsPalette(accent)
                    applyChrome()
                }
                // Re-render screens that hold no typed input.
                if (mode == RequestsBoardLauncher.MODE_BOARD && boardItems.isNotEmpty()) renderBoard(boardItems)
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

    // ---------------- Shell ----------------

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerHolder = FrameLayout(this)
        bodyHolder = FrameLayout(this)
        bottomHolder = FrameLayout(this)
        root.addView(headerHolder, matchWidth())
        root.addView(bodyHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomHolder, matchWidth())
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            // The pinned vote/follow bar gives its height to the comment composer while typing.
            bottomHolder.visibility = if (imeVisible || bottomHolder.childCount == 0) View.GONE else View.VISIBLE
            insets
        }
        setContentView(root)
        applyChrome()
    }

    private fun applyChrome() {
        root.setBackgroundColor(palette.background)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.lightBackground
            isAppearanceLightNavigationBars = palette.lightBackground
        }
    }

    private fun setScreen(header: View, body: View, bottom: View? = null) {
        headerHolder.removeAllViews()
        headerHolder.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(FrameLayout(context).apply {
                setPadding(dpPx(12), dpPx(4), dpPx(12), dpPx(4))
                addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, matchWidth())
            addView(View(context).apply { setBackgroundColor(palette.border) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setBody(body)
        bottomHolder.removeAllViews()
        bottom?.let { bottomHolder.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        bottomHolder.visibility = if (bottom == null || imeVisible) View.GONE else View.VISIBLE
    }

    private fun setBody(body: View) {
        bodyHolder.removeAllViews()
        bodyHolder.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun header(leading: View, center: View?, trailing: View? = null): View = SurfaceHeader(this).apply {
        setLeading(leading)
        setCenter(center)
        setTrailing(trailing)
    }.view

    private fun loadingView(): View = FrameLayout(this).apply {
        addView(ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(palette.primary)
        }, FrameLayout.LayoutParams(dpPx(40), dpPx(40), Gravity.CENTER))
    }

    private fun scrollingColumn(content: View, maxWidthDp: Int = CONTENT_MAX_WIDTH_DP, padTop: Int = 20, padH: Int = 24): ScrollView {
        val measure = MeasureLayout(this, dpPx(maxWidthDp)).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, matchWidth())
        }
        return ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(FrameLayout(context).apply {
                setPadding(dpPx(padH), dpPx(padTop), dpPx(padH), dpPx(32))
                addView(measure, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------- Board ----------------

    private fun loadBoard() {
        mode = RequestsBoardLauncher.MODE_BOARD
        currentRequestId = null
        hideKeyboard()
        setScreen(boardHeader(boardItems.size), loadingView())
        UserGist.getRequests(GetRequestsOptions(sort = RequestSort.TOP, limit = 50)) { err, page ->
            runOnUiThread {
                if (mode != RequestsBoardLauncher.MODE_BOARD) return@runOnUiThread
                if (err != null || page == null) {
                    showError(
                        "Suggestions couldn't load",
                        "Check your connection, then try again.",
                    ) { loadBoard() }
                    return@runOnUiThread
                }
                renderBoard(page.items)
            }
        }
    }

    private fun boardHeader(count: Int): View = header(
        surfaceClose(palette, "Close suggestions") { finish() },
        requestsTitle(palette, boardLabel, "$count ${if (count == 1) "idea" else "ideas"}"),
        SurfaceButton(this, palette, "New idea", compact = true).apply {
            setOnAction { loadSubmit() }
        },
    )

    private fun renderBoard(items: List<RequestSummary>) {
        boardItems = items
        val counts = items.groupingBy { it.status }.eachCount()
        if (statusFilter != null && (counts[statusFilter] ?: 0) == 0) statusFilter = null
        val filtered = statusFilter?.let { s -> items.filter { it.status == s } } ?: items

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpPx(16), dpPx(4), dpPx(16), dpPx(4))
            addView(filterChip(palette, "All", items.size, statusFilter == null, null) {
                statusFilter = null
                renderBoard(boardItems)
            })
            STATUS_ORDER.forEach { status ->
                val count = counts[status] ?: 0
                if (count == 0) return@forEach
                addView(filterChip(palette, statusLabel(status), count, statusFilter == status, statusColor(status)) {
                    statusFilter = status
                    renderBoard(boardItems)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dpPx(8)
                })
            }
        }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chips)
        }

        val list: View = if (filtered.isEmpty()) {
            if (items.isEmpty()) {
                requestsEmpty(palette, "No suggestions yet", "Share the first idea for making this app better.",
                    SurfaceButton(this, palette, "Share an idea").apply { setOnAction { loadSubmit() } })
            } else {
                requestsEmpty(palette, "Nothing with this status", "Choose All to see every suggestion.")
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                filtered.forEachIndexed { index, row ->
                    addView(requestCard(palette, row, boardVoteInFlight == null, onOpen = { loadDetail(row.id) }, onUpvote = { voteFromBoard(row) }),
                        matchWidth().apply { if (index > 0) topMargin = dpPx(10) })
                }
            }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chipScroll, matchWidth())
            addView(scrollingColumn(list, CONTENT_MAX_WIDTH_DP + 32, padTop = 4, padH = 16),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setScreen(boardHeader(items.size), body)
    }

    private fun voteFromBoard(row: RequestSummary) {
        if (boardVoteInFlight != null) return
        val before = boardItems
        val vote = !row.viewerHasUpvoted
        val optimistic = row.copy(
            upvoteCount = (row.upvoteCount + if (vote) 1 else -1).coerceAtLeast(0),
            followerCount = (row.followerCount + if (vote && !row.viewerIsFollowing) 1 else 0).coerceAtLeast(0),
            viewerHasUpvoted = vote,
            viewerIsFollowing = if (vote) true else row.viewerIsFollowing,
        )
        boardVoteInFlight = row.id
        renderBoard(boardItems.map { if (it.id == row.id) optimistic else it })
        UserGist.voteOnRequest(row.id, vote) { error, result ->
            runOnUiThread {
                boardVoteInFlight = null
                if (mode != RequestsBoardLauncher.MODE_BOARD) return@runOnUiThread
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

    // ---------------- New suggestion ----------------

    private fun loadSubmit() {
        mode = "submit"
        currentRequestId = null
        var posting = false
        val title = SurfaceField(this, palette, "A short, clear title", "Suggestion title", multiline = false, maxLength = TITLE_MAX,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val description = SurfaceField(this, palette, "What should it do, who is it for, and why does it matter?",
            "Suggestion description", multiline = true, minHeightDp = 140, maxLength = DESCRIPTION_MAX)
        val post = SurfaceButton(this, palette, "Post", compact = true)
        val errorSlot = FrameLayout(this).apply { visibility = View.GONE }

        fun refresh() {
            val t = title.value.trim()
            val d = description.value.trim()
            post.setDisabled(t.isEmpty() || t.length > TITLE_MAX || d.isEmpty() || d.length > DESCRIPTION_MAX)
            post.loading = posting
        }
        title.onTextChanged = { refresh() }
        description.onTextChanged = { refresh() }
        refresh()

        post.setOnAction {
            if (posting) return@setOnAction
            val cleanTitle = title.value.trim()
            val cleanDescription = description.value.trim()
            if (cleanTitle.isEmpty() || cleanDescription.isEmpty()) return@setOnAction
            posting = true
            errorSlot.visibility = View.GONE
            title.isEnabled = false
            description.isEnabled = false
            refresh()
            UserGist.submitRequest(cleanTitle, cleanDescription) { error, request ->
                runOnUiThread {
                    posting = false
                    if (mode != "submit") return@runOnUiThread
                    if (error != null || request == null) {
                        title.isEnabled = true
                        description.isEnabled = true
                        refresh()
                        errorSlot.removeAllViews()
                        errorSlot.addView(surfaceText(palette, "Your suggestion wasn't posted. Check your connection, then post again.",
                            15f, palette.text, 400, 21f).apply {
                            setPadding(dpPx(14), dpPx(14), dpPx(14), dpPx(14))
                            background = roundRect(palette.fill, dpF(palette.radius(14)))
                            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                        })
                        errorSlot.visibility = View.VISIBLE
                    } else {
                        loadDetail(request.id)
                    }
                }
            }
        }

        fun label(text: String) = surfaceText(palette, text, 15f, palette.text, 600)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(surfaceText(palette, "Share an idea. Others can upvote it, and the team posts updates as work moves forward.",
                15f, palette.subtext, 400, 22f), matchWidth())
            addView(errorSlot, matchWidth().apply { topMargin = dpPx(20) })
            addView(label("Title"), matchWidth().apply { topMargin = dpPx(20) })
            addView(title, matchWidth().apply { topMargin = dpPx(8) })
            addView(label("Description"), matchWidth().apply { topMargin = dpPx(20) })
            addView(description, matchWidth().apply { topMargin = dpPx(8) })
        }
        setScreen(
            header(surfaceClose(palette, "Cancel new suggestion") { loadBoard() }, requestsTitle(palette, "New suggestion"), post),
            // Reveal the whole focused field plus its label above the keyboard,
            // not just the cursor line (description's first line stayed hidden).
            scrollingColumn(form).apply { keepFocusedFieldVisible(dpPx(32)) },
        )
        title.editText.post {
            title.editText.requestFocus()
            WindowCompat.getInsetsController(window, title.editText).show(WindowInsetsCompat.Type.ime())
        }
    }

    // ---------------- Detail ----------------

    private fun loadDetail(requestId: String) {
        mode = RequestsBoardLauncher.MODE_DETAIL
        currentRequestId = requestId
        hideKeyboard()
        val detailHeader = header(surfaceBack(palette, "Back to suggestions") { loadBoard() }, requestsTitle(palette, "Suggestion"))
        setScreen(detailHeader, loadingView())
        UserGist.getRequest(requestId) { err, req ->
            runOnUiThread {
                if (!screenHost.isShowingDetail(requestId)) return@runOnUiThread
                if (err != null || req == null) {
                    showError("This suggestion couldn't load", "Check your connection, then try again.") { loadDetail(requestId) }
                    return@runOnUiThread
                }
                renderDetail(detailHeader, req)
            }
        }
    }

    private fun renderDetail(detailHeader: View, req: FeatureRequest) {
        val screen = RequestDetailScreen(screenHost, req)
        (detailHeader.parent as? ViewGroup)?.removeView(detailHeader)
        setScreen(detailHeader, screen.body, screen.bottomBar)
    }

    private fun showError(title: String, body: String, retry: () -> Unit) {
        setBody(requestsEmpty(palette, title, body, SurfaceButton(this, palette, "Try again").apply { setOnAction(retry) }))
        bottomHolder.removeAllViews()
        bottomHolder.visibility = View.GONE
    }

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TITLE_MAX = 120
        const val DESCRIPTION_MAX = 1500
    }
}
