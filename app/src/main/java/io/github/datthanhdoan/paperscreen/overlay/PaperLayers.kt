package io.github.datthanhdoan.paperscreen.overlay

import io.github.datthanhdoan.paperscreen.settings.PaperSettings

/** What the view draws on top of the tile. Pure value so it is cheap to diff. */
data class PaperAppearance(
    val warmth: Float = 0f,
    val vignette: Float = 0f,
    val windowAlpha: Float = 0.8f,
)

/** Appearance for the current UI mode, see [PaperSettings.profile]. */
fun PaperSettings.appearance(night: Boolean, windowAlpha: Float): PaperAppearance {
    val p = profile(night)
    return PaperAppearance(warmth = p.warmth, vignette = p.vignette, windowAlpha = windowAlpha)
}

/**
 * Colours and alphas of the layers [PaperView] draws above the tile, without Android
 * types so JVM tests can composite exactly what the overlay shows.
 */
object PaperLayers {
    /** Very dark warm brown for the vignette: close to a grey multiply, slightly warmer (B3). */
    const val INK = 0xFF22180E.toInt()

    /** Orange of the warm tint, see [tintAlpha]. */
    const val TINT = 0xFFFFAA00.toInt()

    /** Vignette gradient stops over the half-diagonal: clear centre, darker corners. */
    val VIGNETTE_STOPS = floatArrayOf(0f, 0.55f, 1f)

    private const val MAX_TINT_ALPHA = 0.12f
    private const val MAX_VIGNETTE_ALPHA = 0.05f

    /**
     * Section B3: approximate a multiply by m = (1, 0.96, 0.88) at full warmth with
     * src colour C = 1 - (1 - m) / a and alpha a = max(1 - m) = 0.12. Exact on white,
     * lifts black by C * a. Divided by the window alpha, which scales every pixel.
     */
    fun tintAlpha(a: PaperAppearance): Float =
        (MAX_TINT_ALPHA * a.warmth / a.windowAlpha).coerceIn(0f, 1f)

    /** Alpha of [INK] at the corners. */
    fun vignetteAlpha(a: PaperAppearance): Float =
        (MAX_VIGNETTE_ALPHA * a.vignette / a.windowAlpha).coerceIn(0f, 1f)
}
