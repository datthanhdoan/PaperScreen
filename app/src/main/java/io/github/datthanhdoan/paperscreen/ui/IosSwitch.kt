package io.github.datthanhdoan.paperscreen.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.widget.CompoundButton
import io.github.datthanhdoan.paperscreen.R

/**
 * A full-width settings row: the label is this view's own text, the 51x31dp iOS-style
 * toggle is drawn at the end. One view means the whole row is the touch target and
 * TalkBack reads it as a single switch.
 */
class IosSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CompoundButton(context, attrs) {

    private val density = resources.displayMetrics.density
    private val trackWidth = 51 * density
    private val trackHeight = 31 * density
    private val thumbInset = 2 * density
    private val gap = 12 * density

    private val onColor = context.getColor(R.color.ios_green)
    private val offColor = context.getColor(R.color.ios_toggle_off)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
    private val track = RectF()

    /** 0 = off, 1 = on; animated between the two. */
    private var position = 0f
    private var animator: ValueAnimator? = null
    private val basePaddingEnd: Int

    init {
        buttonDrawable = null
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        basePaddingEnd = paddingEnd
        setPaddingRelative(paddingStart, paddingTop, basePaddingEnd + (trackWidth + gap).toInt(), paddingBottom)
        position = if (isChecked) 1f else 0f
    }

    override fun setChecked(checked: Boolean) {
        val changed = checked != isChecked
        super.setChecked(checked)
        // super's constructor calls this before our fields exist.
        @Suppress("SENSELESS_COMPARISON")
        if (trackPaint == null) return
        val target = if (checked) 1f else 0f
        animator?.cancel()
        if (!changed || !isAttachedToWindow || !isLaidOut) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 220
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        animator?.cancel()
        position = if (isChecked) 1f else 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val minHeight = (trackHeight + paddingTop + paddingBottom).toInt()
        if (measuredHeight < minHeight) setMeasuredDimension(measuredWidth, minHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val end = if (rtl) basePaddingEnd.toFloat() else width - basePaddingEnd - trackWidth
        val top = (height - trackHeight) / 2f
        track.set(end, top, end + trackWidth, top + trackHeight)

        trackPaint.color = blend(offColor, onColor, position)
        trackPaint.alpha = if (isEnabled) 255 else 110
        canvas.drawRoundRect(track, trackHeight / 2f, trackHeight / 2f, trackPaint)

        val radius = trackHeight / 2f - thumbInset
        val travel = trackWidth - 2 * (radius + thumbInset)
        val p = if (rtl) 1f - position else position
        val cx = track.left + thumbInset + radius + travel * p
        val cy = track.centerY()
        canvas.drawCircle(cx, cy + density, radius + 0.5f * density, shadowPaint)
        canvas.drawCircle(cx, cy, radius, thumbPaint)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) = (((from shr shift) and 0xFF) * (1 - t) + ((to shr shift) and 0xFF) * t).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}
