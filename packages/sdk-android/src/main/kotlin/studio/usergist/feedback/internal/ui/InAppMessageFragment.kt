package studio.usergist.feedback.internal.ui

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.os.bundleOf
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.usergist.feedback.R
import studio.usergist.feedback.api.ArmedInAppMessage
import studio.usergist.feedback.api.InAppCta
import studio.usergist.feedback.api.InAppCtaAction
import studio.usergist.feedback.api.InAppDismissReason
import studio.usergist.feedback.api.InAppMessageFormat
import studio.usergist.feedback.api.PromptTheme
import studio.usergist.feedback.internal.UserGistLogger
import studio.usergist.feedback.internal.model.WirePromptTheme
import studio.usergist.feedback.internal.model.WireThemeColors

/** Native Material renderer for modal, full-screen, and slide-up messages. */
internal class InAppMessageFragment : DialogFragment() {
    private val handler = Handler(Looper.getMainLooper())
    private var delivered = false
    private var message: ArmedInAppMessage? = null
    private var hostTheme: PromptTheme? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        message = arguments?.getString(ARG_MESSAGE)?.let {
            runCatching { json.decodeFromString<ArmedInAppMessage>(it) }.getOrNull()
        }
        hostTheme = arguments?.getString(ARG_THEME)?.let {
            runCatching {
                json.decodeFromString<PromptSheetFragment.HostThemeEnvelope>(it).toPromptTheme()
            }.getOrNull()
        }
        if (message == null) dismissAllowingStateLoss()
    }

    override fun getTheme(): Int = when (message?.format) {
        InAppMessageFormat.MODAL_FULL -> R.style.UserGist_InApp_Full
        InAppMessageFormat.SLIDEUP -> R.style.UserGist_BottomSheet
        else -> R.style.UserGist_InApp_Dialog
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return if (message?.format == InAppMessageFormat.SLIDEUP) {
            BottomSheetDialog(requireContext(), theme)
        } else {
            super.onCreateDialog(savedInstanceState)
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val current = message ?: return View(requireContext())
        return buildContent(current)
    }

    override fun onStart() {
        super.onStart()
        val current = message ?: return
        configureWindow(dialog?.window, current)
        shownCallback?.safeInvoke(current.messageId)
        val seconds = current.autoDismissSeconds
        if (current.format == InAppMessageFormat.SLIDEUP && seconds != null && seconds > 0) {
            handler.postDelayed(
                {
                    if (isAdded && !delivered) {
                        deliverDismiss(InAppDismissReason.AUTO)
                        dismissAllowingStateLoss()
                    }
                },
                (seconds * 1_000).toLong(),
            )
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        deliverDismiss(InAppDismissReason.USER)
        super.onCancel(dialog)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun buildContent(current: ArmedInAppMessage): View {
        val context = requireContext()
        val resolved = ThemeResolver.merge(
            WirePromptTheme(
                colors = WireThemeColors(
                    primary = current.accentColor,
                    background = current.backgroundColor,
                ),
            ),
            hostTheme,
        )
        val surface = resolved.background ?: MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE,
        )
        val onSurface = resolved.text ?: MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurface,
            Color.BLACK,
        )
        val subtext = resolved.subtext ?: MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            Color.DKGRAY,
        )
        val primary = resolved.primary ?: MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            Color.BLACK,
        )
        val radius = dp(resolved.radiusDp ?: 16).toFloat()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = if (current.format == InAppMessageFormat.MODAL_FULL) 0f else radius
            }
        }

        val close = ImageButton(context).apply {
            setImageResource(R.drawable.usergist_ic_close)
            contentDescription = context.getString(R.string.usergist_close)
            background = null
            setColorFilter(onSurface)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12))
            setOnClickListener {
                deliverDismiss(InAppDismissReason.USER)
                dismissAllowingStateLoss()
            }
        }
        content.addView(
            close,
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.END },
        )

        current.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                load(imageUrl) { crossfade(true) }
            }
            content.addView(
                image,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (current.format == InAppMessageFormat.MODAL_FULL) 260 else 176),
                ),
            )
        }

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(12))
        }
        copy.addView(MaterialTextView(context).apply {
            text = current.title
            setTextColor(onSurface)
            textSize = 22f
            typeface = resolved.fontFamily?.let { Typeface.create(it, Typeface.BOLD) }
                ?: Typeface.DEFAULT_BOLD
        })
        current.body?.takeIf { it.isNotBlank() }?.let { body ->
            copy.addView(MaterialTextView(context).apply {
                text = body
                setTextColor(subtext)
                textSize = 15f
                setLineSpacing(0f, 1.25f)
                typeface = resolved.fontFamily?.let { Typeface.create(it, Typeface.NORMAL) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        content.addView(copy)

        if (current.ctas.isNotEmpty()) {
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(24))
            }
            current.ctas.forEachIndexed { index, cta ->
                val button = MaterialButton(
                    context,
                    null,
                    if (index == 0) {
                        com.google.android.material.R.attr.materialButtonStyle
                    } else {
                        com.google.android.material.R.attr.materialButtonOutlinedStyle
                    },
                ).apply {
                    text = cta.label
                    minHeight = dp(48)
                    cornerRadius = dp(12)
                    if (index == 0) {
                        setBackgroundColor(primary)
                        setTextColor(contrastOn(primary))
                    } else {
                        setTextColor(primary)
                        strokeColor = android.content.res.ColorStateList.valueOf(primary)
                    }
                    setOnClickListener {
                        deliverCta(current, cta, index)
                        openTarget(cta)
                        dismissAllowingStateLoss()
                    }
                }
                buttons.addView(
                    button,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) topMargin = dp(8) },
                )
            }
            content.addView(buttons)
        }

        return ScrollView(context).apply {
            isFillViewport = current.format == InAppMessageFormat.MODAL_FULL
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun configureWindow(window: Window?, current: ArmedInAppMessage) {
        if (window == null) return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        if (current.backdropEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { dimAmount = 0.4f }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        when (current.format) {
            InAppMessageFormat.MODAL_FULL -> window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            InAppMessageFormat.SLIDEUP -> window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            InAppMessageFormat.MODAL -> window.setLayout(
                minOf(resources.displayMetrics.widthPixels - dp(32), dp(420)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun deliverDismiss(reason: InAppDismissReason) {
        val id = message?.messageId ?: return
        if (delivered) return
        delivered = true
        dismissedCallback?.safeInvoke(id, reason)
    }

    private fun deliverCta(current: ArmedInAppMessage, cta: InAppCta, index: Int) {
        if (delivered) return
        delivered = true
        ctaCallback?.safeInvoke(current.messageId, cta, index)
    }

    private fun openTarget(cta: InAppCta) {
        if (cta.action != InAppCtaAction.OPEN_URL && cta.action != InAppCtaAction.DEEP_LINK) return
        val target = cta.target?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure { UserGistLogger.w("Unable to open in-app CTA target", it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun contrastOn(color: Int): Int {
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(color)
        return if (luminance < 0.45) Color.WHITE else Color.BLACK
    }

    companion object {
        private const val ARG_MESSAGE = "usergist.inapp.message"
        private const val ARG_THEME = "usergist.inapp.theme"

        @Volatile
        internal var shownCallback: ((String) -> Unit)? = null

        @Volatile
        internal var dismissedCallback: ((String, InAppDismissReason) -> Unit)? = null

        @Volatile
        internal var ctaCallback: ((String, InAppCta, Int) -> Unit)? = null

        fun newInstance(
            message: ArmedInAppMessage,
            theme: PromptTheme?,
        ): InAppMessageFragment {
            val json = Json { encodeDefaults = true }
            return InAppMessageFragment().apply {
                arguments = bundleOf(
                    ARG_MESSAGE to json.encodeToString(message),
                    ARG_THEME to theme?.let {
                        json.encodeToString(PromptSheetFragment.HostThemeEnvelope.from(it))
                    },
                )
            }
        }

        private fun <A> ((A) -> Unit).safeInvoke(a: A) {
            runCatching { invoke(a) }.onFailure {
                UserGistLogger.w("In-app callback threw", it)
            }
        }

        private fun <A, B> ((A, B) -> Unit).safeInvoke(a: A, b: B) {
            runCatching { invoke(a, b) }.onFailure {
                UserGistLogger.w("In-app callback threw", it)
            }
        }

        private fun <A, B, C> ((A, B, C) -> Unit).safeInvoke(a: A, b: B, c: C) {
            runCatching { invoke(a, b, c) }.onFailure {
                UserGistLogger.w("In-app callback threw", it)
            }
        }
    }
}
