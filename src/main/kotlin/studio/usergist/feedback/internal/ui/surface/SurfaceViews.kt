package studio.usergist.feedback.internal.ui.surface

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

internal fun Context.dpPx(value: Number): Int =
    (value.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.dpF(value: Number): Float =
    value.toFloat() * resources.displayMetrics.density

/** LinearLayout that never grows wider than [maxWidthPx] (0 = unbounded). */
@SuppressLint("ViewConstructor")
internal class MeasureLayout(context: Context, var maxWidthPx: Int) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(capped(widthMeasureSpec, maxWidthPx), heightMeasureSpec)
    }
}

/** ScrollView whose height is capped to [maxHeightPx] (0 = unbounded). */
@SuppressLint("ViewConstructor")
internal class BoundedScrollView(context: Context, var maxHeightPx: Int = 0) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, capped(heightMeasureSpec, maxHeightPx))
    }
}

/** FrameLayout whose measured height is reduced by [reservePx] (sheet top gap). */
@SuppressLint("ViewConstructor")
internal class ReservedHeightFrame(context: Context, var reservePx: Int = 0) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val spec = if (mode == MeasureSpec.UNSPECIFIED || reservePx <= 0) {
            heightMeasureSpec
        } else {
            val size = (MeasureSpec.getSize(heightMeasureSpec) - reservePx).coerceAtLeast(0)
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}

/** Frame that dims itself while pressed (RN Pressable opacity feedback). */
internal open class PressFrame(context: Context, private val pressedAlpha: Float = 0.6f) : FrameLayout(context) {
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed && isEnabled) pressedAlpha else 1f
    }
}

/**
 * Keeps the focused text field fully visible inside this scroller. Android's
 * own focus scrolling only brings the cursor line into view, so a multiline
 * field's first line (and its label) can sit under the header once the
 * keyboard shrinks the viewport. Runs on focus change and on every viewport
 * height change (keyboard show/hide, rotation), after the platform scroll.
 * [abovePx] extra room revealed above the field, e.g. for its label.
 */
internal fun ScrollView.keepFocusedFieldVisible(abovePx: Int) {
    val reveal = Runnable {
        val focused = findFocus() as? android.widget.EditText ?: return@Runnable
        val target: View = (focused.parent as? SurfaceField) ?: focused
        revealChild(target, abovePx)
    }
    val focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus is android.widget.EditText && isAncestorOf(newFocus)) post(reveal)
    }
    addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) post(reveal)
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        override fun onViewDetachedFromWindow(v: View) = viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
    })
    if (isAttachedToWindow) viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
}

private fun View.isAncestorOf(child: View): Boolean {
    var parent = child.parent
    while (parent != null) {
        if (parent === this) return true
        parent = parent.parent
    }
    return false
}

/** Scrolls the minimum needed so [child] (plus [abovePx]) is inside the viewport. */
internal fun ScrollView.revealChild(child: View, abovePx: Int) {
    val rect = android.graphics.Rect()
    child.getDrawingRect(rect)
    offsetDescendantRectToMyCoords(child, rect)
    val viewport = height - paddingTop - paddingBottom
    if (viewport <= 0) return
    val top = (rect.top - abovePx).coerceAtLeast(0)
    val bottom = rect.bottom + context.dpPx(12)
    val target = when {
        bottom - top >= viewport -> top          // taller than the viewport: show its top
        top < scrollY -> top                     // above: reveal from the label
        bottom > scrollY + viewport -> bottom - viewport
        else -> return
    }
    smoothScrollTo(0, target)
}

private fun capped(spec: Int, max: Int): Int {
    if (max <= 0) return spec
    val mode = View.MeasureSpec.getMode(spec)
    val size = View.MeasureSpec.getSize(spec)
    return when (mode) {
        View.MeasureSpec.UNSPECIFIED -> View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST)
        View.MeasureSpec.EXACTLY -> if (size > max) View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.EXACTLY) else spec
        else -> View.MeasureSpec.makeMeasureSpec(minOf(size, max), View.MeasureSpec.AT_MOST)
    }
}

internal enum class Glyph { CLOSE, BACK, UP, DOWN, TICK, MORE }

/** Thin drawn glyphs; strokes match the RN SurfaceControls primitives. */
@SuppressLint("ViewConstructor")
internal class GlyphView(context: Context, private val glyph: Glyph, color: Int) : View(context) {
    var color: Int = color
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val d = context.resources.displayMetrics.density
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.75f * d
        path.reset()
        when (glyph) {
            Glyph.CLOSE -> {
                val h = 6.5f * d * 0.7071f
                canvas.drawLine(cx - h, cy - h, cx + h, cy + h, paint)
                canvas.drawLine(cx - h, cy + h, cx + h, cy - h, paint)
            }
            Glyph.BACK -> {
                val s = 4.5f * d
                path.moveTo(cx + s * 0.4f, cy - s)
                path.lineTo(cx - s * 0.6f, cy)
                path.lineTo(cx + s * 0.4f, cy + s)
                canvas.drawPath(path, paint)
            }
            Glyph.UP, Glyph.DOWN -> {
                paint.strokeWidth = if (glyph == Glyph.UP) 2f * d else 1.75f * d
                val s = 4.5f * d
                val dir = if (glyph == Glyph.UP) -1f else 1f
                path.moveTo(cx - s, cy - dir * s * 0.45f)
                path.lineTo(cx, cy + dir * s * 0.55f)
                path.lineTo(cx + s, cy - dir * s * 0.45f)
                canvas.drawPath(path, paint)
            }
            Glyph.TICK -> {
                paint.strokeWidth = minOf(width, height) / 9f
                path.moveTo(width * 0.22f, height * 0.52f)
                path.lineTo(width * 0.42f, height * 0.72f)
                path.lineTo(width * 0.78f, height * 0.32f)
                canvas.drawPath(path, paint)
            }
            Glyph.MORE -> {
                paint.style = Paint.Style.FILL
                val r = 2f * d
                val gap = 7f * d
                for (i in -1..1) canvas.drawCircle(cx + i * gap, cy, r, paint)
            }
        }
    }
}

/** Radio / checkbox indicator for [ChoiceRow]. */
@SuppressLint("ViewConstructor")
internal class ChoiceIndicator(context: Context, private val checkbox: Boolean) : View(context) {
    var checked = false
        set(value) { field = value; invalidate() }
    var accent = 0
    var idle = 0
    var mark = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val d = context.resources.displayMetrics.density
        val stroke = 1.75f * d
        rect.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = if (checked) accent else idle
        if (!checkbox) {
            canvas.drawOval(rect, paint)
            if (checked) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(width / 2f, height / 2f, 5f * d, paint)
            }
            return
        }
        val r = 7f * d
        if (checked) {
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(rect, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = mark
            path.reset()
            path.moveTo(width * 0.30f, height * 0.52f)
            path.lineTo(width * 0.45f, height * 0.66f)
            path.lineTo(width * 0.72f, height * 0.36f)
            canvas.drawPath(path, paint)
        } else {
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
