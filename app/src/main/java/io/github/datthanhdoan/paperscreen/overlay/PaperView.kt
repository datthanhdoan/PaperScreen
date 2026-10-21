package io.github.datthanhdoan.paperscreen.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What the view draws on top of the tile. Pure value so it is cheap to diff. */
data class PaperAppearance(
    val warmth: Float = 0f,
    val vignette: Float = 0f,
    val windowAlpha: Float = 0.8f,
    val nightMode: Boolean = false,
    val dimTintInDarkMode: Boolean = true,
)

/**
 * Static full-screen paper layer: ARGB tile (BitmapShader REPEAT) -> warm tint -> vignette.
 * There is no animation; the view only invalidates when its inputs or size change.
 */
@SuppressLint("ViewConstructor")
class PaperView(context: Context) : View(context) {

    interface Listener {
        fun onWindowShown()
        fun onViewConfigurationChanged(config: Configuration)
    }

    var listener: Listener? = null

    private val texturePaint = Paint()
    private val tintPaint = Paint()
    private val vignettePaint = Paint()

    private var tile: Bitmap? = null
    private var appearance = PaperAppearance()
    private var tintAlpha = 0
    private var vignetteAlpha = 0

    init {
        // An overlay must never take focus or be read out by accessibility services.
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun setTile(bitmap: Bitmap?) {
        if (bitmap === tile) return
        tile = bitmap
        // The ARGB tile carries its own light and dark parts; warmth is a separate layer,
        // so changing it never forces a regenerate (B1).
        texturePaint.shader = bitmap?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        invalidate()
    }

    fun setAppearance(value: PaperAppearance) {
        if (value == appearance) return
        appearance = value
        updateTint()
        updateVignette()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation / fold: the tile is reused, only the size-dependent vignette changes.
        updateVignette()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (tile != null) canvas.drawPaint(texturePaint)
        if (tintAlpha > 0) canvas.drawPaint(tintPaint)
        if (vignetteAlpha > 0 && vignettePaint.shader != null) canvas.drawPaint(vignettePaint)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) listener?.onWindowShown()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        listener?.onViewConfigurationChanged(newConfig)
    }

    /**
     * Section B3: approximate a multiply by m = (1, 0.96, 0.88) at full warmth with
     * src colour C = 1 - (1 - m) / a and alpha a = max(1 - m) = 0.12. Exact on white,
     * lifts black by C * a. Divided by the window alpha, which scales every pixel.
     */
    private fun updateTint() {
        val a = appearance
        val darkFactor = if (a.nightMode && a.dimTintInDarkMode) DARK_MODE_TINT_FACTOR else 1f
        val alpha = (MAX_TINT_ALPHA * a.warmth * darkFactor / a.windowAlpha).coerceIn(0f, 1f)
        tintAlpha = (alpha * 255f).roundToInt()
        tintPaint.color = Color.argb(tintAlpha, 255, 170, 0)
    }

    private fun updateVignette() {
        val a = appearance
        vignetteAlpha = ((MAX_VIGNETTE_ALPHA * a.vignette / a.windowAlpha).coerceIn(0f, 1f) * 255f).roundToInt()
        if (vignetteAlpha == 0 || width == 0 || height == 0) {
            vignettePaint.shader = null
            return
        }
        val edge = (INK and 0x00FFFFFF) or (vignetteAlpha shl 24)
        vignettePaint.shader = RadialGradient(
            width / 2f, height / 2f, hypot(width.toFloat(), height.toFloat()) / 2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, edge),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private companion object {
        /** Very dark warm brown for the vignette: close to a grey multiply, slightly warmer (B3). */
        val INK = Color.rgb(0x22, 0x18, 0x0E)
        const val MAX_TINT_ALPHA = 0.12f
        const val MAX_VIGNETTE_ALPHA = 0.05f
        const val DARK_MODE_TINT_FACTOR = 0.25f
    }
}
