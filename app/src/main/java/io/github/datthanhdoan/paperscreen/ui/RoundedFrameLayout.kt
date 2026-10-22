package io.github.datthanhdoan.paperscreen.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Clips its children to rounded corners with a path, which also holds for software
 * rendering (clipToOutline only applies to hardware-accelerated drawing).
 */
class RoundedFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var cornerRadius = 20 * resources.displayMetrics.density
        set(value) {
            field = value
            updatePath()
            invalidate()
        }

    private val clip = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePath()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private fun updatePath() {
        clip.reset()
        clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, Path.Direction.CW)
    }
}
