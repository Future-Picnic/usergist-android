package studio.ritmus.feedback.internal.ui.requests

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import studio.ritmus.feedback.Ritmus
import studio.ritmus.feedback.api.FeatureRequest
import studio.ritmus.feedback.api.GetRequestsOptions
import studio.ritmus.feedback.api.RequestSummary

// PORTED FROM: packages/sdk-react-native/src/ui/RequestsBoard.tsx
//
// Lightweight native Activity that drives the Feature Requests board and
// detail surfaces. Uses raw Android views to avoid pulling in a Compose
// dependency the SDK's host apps might not have. The host can replace it
// by listening to `RequestsHandlers` and presenting their own UI.

object RequestsBoardLauncher {
    private const val EXTRA_MODE = "studio.ritmus.feedback.requests.mode"
    private const val EXTRA_REQUEST_ID = "studio.ritmus.feedback.requests.request_id"
    internal const val MODE_BOARD = "board"
    internal const val MODE_DETAIL = "detail"

    fun openBoard(ctx: Context) {
        val intent = Intent(ctx, RequestsBoardActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_BOARD)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    fun openDetail(ctx: Context, requestId: String) {
        val intent = Intent(ctx, RequestsBoardActivity::class.java).apply {
            putExtra(EXTRA_MODE, MODE_DETAIL)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }
        root.addView(contentLayout)
        setContentView(root)

        val progress = ProgressBar(this)
        contentLayout.addView(progress)

        when (RequestsBoardLauncher.extractMode(intent)) {
            RequestsBoardLauncher.MODE_DETAIL -> {
                val rid = RequestsBoardLauncher.extractRequestId(intent) ?: return
                loadDetail(rid)
            }
            else -> loadBoard()
        }
    }

    private fun loadBoard() {
        Ritmus.getRequests(GetRequestsOptions()) { err, page ->
            runOnUiThread {
                contentLayout.removeAllViews()
                title = "Feature requests"
                if (err != null || page == null) {
                    contentLayout.addView(TextView(this).apply {
                        text = "Unable to load requests"
                    })
                    return@runOnUiThread
                }
                for (row in page.items) {
                    contentLayout.addView(boardRow(row))
                }
            }
        }
    }

    private fun boardRow(row: RequestSummary): TextView {
        return TextView(this).apply {
            text = "▲ ${row.upvoteCount}   ${row.title}\n${row.description.take(80)}"
            setPadding(0, 16, 0, 16)
            setOnClickListener {
                RequestsBoardLauncher.openDetail(this@RequestsBoardActivity, row.id)
            }
        }
    }

    private fun loadDetail(requestId: String) {
        Ritmus.getRequest(requestId) { err, req ->
            runOnUiThread {
                contentLayout.removeAllViews()
                title = "Request"
                if (err != null || req == null) {
                    contentLayout.addView(TextView(this).apply {
                        text = "Unable to load request"
                    })
                    return@runOnUiThread
                }
                renderDetail(req)
            }
        }
    }

    private fun renderDetail(req: FeatureRequest) {
        contentLayout.addView(TextView(this).apply {
            text = req.title
            textSize = 20f
            setPadding(0, 0, 0, 16)
        })
        contentLayout.addView(TextView(this).apply { text = req.description })
        contentLayout.addView(TextView(this).apply {
            text = "${req.upvoteCount} upvotes  •  ${req.followerCount} followers  •  ${req.status.raw}"
            setPadding(0, 16, 0, 16)
        })
        if (!req.devResponse.isNullOrBlank()) {
            contentLayout.addView(TextView(this).apply {
                text = req.devResponse
                setPadding(16, 16, 16, 16)
                setBackgroundColor(0x11000000)
            })
        }
    }
}
