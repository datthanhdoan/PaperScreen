package io.github.datthanhdoan.paperscreen.texture

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Inputs that change the tile pixels. Warmth and vignette are not here: they are
 * drawn by [io.github.datthanhdoan.paperscreen.overlay.PaperView] on top of the tile.
 *
 * @param preset paper type, see [PaperPreset].
 * @param intensity 0..1, overall strength ("độ đậm"); 0.5 is the preset's nominal look.
 * @param coarseness 0..1, scales every feature size ("độ thô"), 0.5 = preset size.
 * @param relief 0..1, strength of the emboss shading ("độ nổi").
 */
data class TextureParams(
    val preset: PaperPreset = PaperPreset.DEFAULT,
    val intensity: Float,
    val coarseness: Float,
    val relief: Float = 0.5f,
    val seed: Long,
)

/**
 * Square tile, row-major, 4 bytes per pixel in the byte order Android's ARGB_8888
 * buffers use (R, G, B, A) with premultiplied colour, so it can be copied 1:1.
 */
class TextureTile(val size: Int, val pixels: ByteArray) {
    fun alpha(x: Int, y: Int): Int = channel(x, y, 3)
    fun channel(x: Int, y: Int, c: Int): Int = pixels[(y * size + x) * 4 + c].toInt() and 0xFF
}

/**
 * Pure (no Android APIs) generator for a seamless paper tile, see section B4 of
 * docs/feasibility-report.md and docs/texture-research.md.
 *
 * A premultiplied pixel (c, a) composites as `out = c + dst * (1 - a)`, so one pixel
 * can independently darken white by `a - c` and lift black by `c`. The tile uses that
 * to draw an embossed paper surface: a height map (tooth, cold-press domes, fibers,
 * formation) is lit from the upper left; shadows darken light content and highlights
 * faintly lift dark content, so text and images look printed on the sheet.
 */
object TextureGenerator {

    /** Upper bound for darkening white, on screen (after window alpha). */
    const val MAX_DARKENING = 0.22f

    /** Upper bound for lifting black, on screen; keeps dark themes and OLED blacks intact. */
    const val MAX_LIFT = 0.05f

    /** Smallest feature in physical px, so detail does not sparkle on PenTile OLED (B5). */
    const val MIN_FEATURE_PX = 1.5f

    private const val TOOTH_OCTAVES = 3
    private const val FORMATION_HEIGHT = 0.4f
    private const val SHADOW_GAIN = 0.10f
    private const val MAX_HIGHLIGHT = 0.35f
    private const val LIFT_GAIN = 0.05f

    /** Light from the upper left at a low angle, like a desk lamp grazing the sheet. */
    private val LIGHT = run {
        val v = floatArrayOf(-0.6f, -0.8f, 0.55f)
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        FloatArray(3) { v[it] / m }
    }

    /** Tile edge in physical px: ~320dp, clamped to 640..1024 and a multiple of 8. */
    fun tileSizeFor(densityDpi: Int): Int {
        val px = 320f * densityDpi / 160f
        val rounded = (ceil(px / 8f) * 8f).toInt()
        return rounded.coerceIn(640, 1024)
    }

    /** Multiplier on every preset length: 0.6x at coarseness 0, 1.6x at 1. */
    fun featureScale(coarseness: Float): Float = 0.6f + coarseness.coerceIn(0f, 1f)

    /**
     * @param windowAlpha alpha of the overlay window; the tile is divided by it so the
     *   on-screen result stays within [MAX_DARKENING] and [MAX_LIFT].
     * @param checkCancelled called once per row so callers can abort a stale job.
     */
    fun generate(
        params: TextureParams,
        densityDpi: Int,
        windowAlpha: Float = 0.8f,
        checkCancelled: () -> Unit = {},
    ): TextureTile {
        require(densityDpi > 0) { "densityDpi must be positive" }
        require(windowAlpha > 0f && windowAlpha <= 1f) { "windowAlpha must be in (0, 1]" }
        val size = tileSizeFor(densityDpi)
        val preset = params.preset
        val n = size * size

        val height = FloatArray(n)
        val albedo = FloatArray(n)
        val field = PaperField(params, size, densityDpi)
        for (y in 0 until size) {
            checkCancelled()
            for (x in 0 until size) {
                val px = x + 0.5f
                val py = y + 0.5f
                val f = field.formation(px, py)
                height[y * size + x] = field.surface(px, py) + FORMATION_HEIGHT * f
                // Denser flocs let less light through and look a little darker.
                albedo[y * size + x] = preset.formationDarkness * f
            }
        }
        rasterizeFibers(params, size, densityDpi, height, albedo)
        scatterSpecks(params, size, densityDpi, albedo)
        checkCancelled()
        val shade = shade(height, size, params)

        val intensity = params.intensity.coerceIn(0f, 1f)
        val relief = params.relief.coerceIn(0f, 1f)
        val amp = intensity * 2f
        val tintAmount = min(1f, intensity * 2f)
        val tintAlpha = max(1f - preset.tint.r, max(1f - preset.tint.g, 1f - preset.tint.b)) * tintAmount
        val tintColor = FloatArray(3) { c ->
            if (tintAlpha > 0f) 1f - (1f - preset.tint[c]) * tintAmount / tintAlpha else 0f
        }

        val out = ByteArray(n * 4)
        for (y in 0 until size) {
            checkCancelled()
            for (x in 0 until size) {
                val k = y * size + x
                val s = shade[k]
                val dark = (amp * (preset.baseDarkness + SHADOW_GAIN * max(-s, -MAX_HIGHLIGHT) + albedo[k]))
                    .coerceIn(0f, MAX_DARKENING)
                val lift = (amp * LIFT_GAIN * max(0f, s) * (0.5f + relief)).coerceIn(0f, MAX_LIFT)
                val a = ((dark + lift + tintAlpha) / windowAlpha).coerceAtMost(1f)
                val o = k * 4
                for (c in 0..2) {
                    val color = ((lift * preset.highlightTone[c] + tintAlpha * tintColor[c]) / windowAlpha)
                        .coerceAtMost(a)
                    out[o + c] = (color * 255f).roundToInt().toByte()
                }
                out[o + 3] = (a * 255f).roundToInt().toByte()
            }
        }
        return TextureTile(size, out)
    }

    /**
     * Lambert term of the height map against [LIGHT], minus the flat-surface value and
     * then minus its mean, so 0 means "as bright as the average sheet". Gradients use
     * wrapped neighbours, which keeps the tile seamless.
     */
    private fun shade(height: FloatArray, size: Int, params: TextureParams): FloatArray {
        val scale = params.preset.relief * (0.3f + 1.4f * params.relief.coerceIn(0f, 1f)) * 1.6f
        val out = FloatArray(size * size)
        var sum = 0.0
        for (y in 0 until size) {
            val up = Math.floorMod(y - 1, size) * size
            val down = Math.floorMod(y + 1, size) * size
            val row = y * size
            for (x in 0 until size) {
                val hx = height[row + Math.floorMod(x + 1, size)] - height[row + Math.floorMod(x - 1, size)]
                val hy = height[down + x] - height[up + x]
                val nx = -hx * scale
                val ny = -hy * scale
                val len = sqrt(nx * nx + ny * ny + 1f)
                val s = (nx * LIGHT[0] + ny * LIGHT[1] + LIGHT[2]) / len - LIGHT[2]
                out[row + x] = s
                sum += s
            }
        }
        val mean = (sum / out.size).toFloat()
        for (i in out.indices) out[i] -= mean
        return out
    }

    /**
     * Fibers: quadratic Bézier strands with a Gaussian cross-section, deposited into
     * the height map (raised strands catch the light) and optionally into [albedo].
     * Splats wrap around, so strands crossing an edge continue on the opposite side.
     */
    internal fun rasterizeFibers(
        params: TextureParams,
        size: Int,
        densityDpi: Int,
        height: FloatArray,
        albedo: FloatArray,
    ) {
        val preset = params.preset
        val rnd = Random(params.seed xor 0x5DEECE66DL)
        val pxPerMm = densityDpi / 25.4f
        val scale = featureScale(params.coarseness)
        val tileMm = size / pxPerMm
        val count = (tileMm * tileMm * preset.fibersPerMm2).roundToInt()

        repeat(count) {
            val length = lerp(preset.fiberMinMm, preset.fiberMaxMm, rnd.nextFloat().pow(1.5f)) * scale * pxPerMm
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            val x0 = rnd.nextFloat() * size
            val y0 = rnd.nextFloat() * size
            val dx = cos(angle) * length
            val dy = sin(angle) * length
            val bend = (rnd.nextFloat() - 0.5f) * preset.fiberBend * length
            // Control point: midpoint pushed sideways, perpendicular to the strand.
            val cx = x0 + dx / 2f - sin(angle) * bend
            val cy = y0 + dy / 2f + cos(angle) * bend
            val x2 = x0 + dx
            val y2 = y0 + dy
            val width = max(MIN_FEATURE_PX, preset.fiberWidthMm * scale * pxPerMm * lerp(0.7f, 1.3f, rnd.nextFloat()))
            val raise = preset.fiberHeight * lerp(0.5f, 1f, rnd.nextFloat())
            val dark = preset.fiberDarkness * when {
                preset.allFibersDark -> lerp(0.4f, 1f, rnd.nextFloat())
                rnd.nextFloat() < 0.5f -> rnd.nextFloat()
                else -> 0f
            }

            // One step per px of length; dividing by the width keeps a strand's total
            // deposit roughly independent of how wide it is.
            val steps = ceil(length).toInt().coerceAtLeast(2)
            val radius = ceil(width * 1.5f).toInt()
            val invW2 = 4f / (width * width)
            val norm = 1f / max(1f, width * 0.8f)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val u = 1f - t
                val x = u * u * x0 + 2f * u * t * cx + t * t * x2
                val y = u * u * y0 + 2f * u * t * cy + t * t * y2
                val taper = min(1f, sin(PI.toFloat() * t) * 3f) * norm
                val ix = floor(x).toInt()
                val iy = floor(y).toInt()
                for (oy in -radius..radius) {
                    val py = iy + oy
                    val ddy = py + 0.5f - y
                    val row = Math.floorMod(py, size) * size
                    for (ox in -radius..radius) {
                        val px = ix + ox
                        val ddx = px + 0.5f - x
                        val d2 = (ddx * ddx + ddy * ddy) * invW2
                        if (d2 > 4f) continue
                        val g = exp(-d2) * taper
                        val k = row + Math.floorMod(px, size)
                        height[k] += raise * g
                        albedo[k] += dark * g * 0.25f
                    }
                }
            }
        }
    }

    /** Tiny dark inclusions, as in newsprint and recycled pulp. */
    private fun scatterSpecks(params: TextureParams, size: Int, densityDpi: Int, albedo: FloatArray) {
        val pxPerMm = densityDpi / 25.4f
        val tileMm = size / pxPerMm
        val count = (tileMm * tileMm * params.preset.specksPerMm2 * 0.6f).roundToInt()
        val rnd = Random(params.seed xor 0x3C6EF372L)
        repeat(count) {
            val x = rnd.nextFloat() * size
            val y = rnd.nextFloat() * size
            val radius = MIN_FEATURE_PX * lerp(0.6f, 1.4f, rnd.nextFloat())
            val strength = lerp(0.03f, 0.12f, rnd.nextFloat())
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            for (oy in -3..3) {
                for (ox in -3..3) {
                    val ddx = ix + ox + 0.5f - x
                    val ddy = iy + oy + 0.5f - y
                    val d2 = (ddx * ddx + ddy * ddy) / (radius * radius)
                    val k = Math.floorMod(iy + oy, size) * size + Math.floorMod(ix + ox, size)
                    albedo[k] += strength * exp(-d2 * 2f)
                }
            }
        }
    }

    /**
     * Continuous, periodic height fields over pixel coordinates. Every octave uses an
     * integer number of lattice cells per tile, so `f(x + size, y) == f(x, y)`.
     */
    internal class PaperField(params: TextureParams, val size: Int, densityDpi: Int) {
        private val preset = params.preset
        private val noise = PeriodicNoise(params.seed.toInt() xor (params.seed ushr 32).toInt())
        private val toothCells: Int
        private val formationCells: Int
        private val bumpCells: Int

        init {
            val pxPerMm = densityDpi / 25.4f * featureScale(params.coarseness)
            toothCells = cellsFor(preset.toothMm * pxPerMm)
            formationCells = cellsFor(preset.formationMm * pxPerMm)
            bumpCells = if (preset.bumpMm > 0f) cellsFor(preset.bumpMm * pxPerMm) else 0
        }

        private fun cellsFor(wavelengthPx: Float): Int =
            max(1, (size / max(MIN_FEATURE_PX * 2f, wavelengthPx)).roundToInt())

        /** Tooth fBm (finest octave strongest) plus optional cold-press domes, ~[-1, 1]. */
        fun surface(px: Float, py: Float): Float {
            val u = px / size
            val v = py / size
            var sum = 0f
            var norm = 0f
            var amp = 1f
            for (o in 0 until TOOTH_OCTAVES) {
                val c = max(1, toothCells shr o)
                sum += amp * noise.gradient(u * c, v * c, c, o)
                norm += amp
                amp *= 0.6f
            }
            var h = preset.toothAmp * sum / norm
            if (bumpCells > 0) {
                val b = bumpCells
                // Domain warp makes the Worley domes irregular instead of a honeycomb.
                val wx = 0.35f * noise.gradient(u * b, v * b, b, 30)
                val wy = 0.35f * noise.gradient(u * b, v * b, b, 31)
                val dome = max(0f, 1f - 1.1f * noise.worley(u * b + wx, v * b + wy, b, 7))
                h += preset.bumpAmp * 1.6f * (dome * dome - 0.3f) +
                    0.25f * noise.gradient(u * b * 2, v * b * 2, b * 2, 9)
            }
            return h
        }

        /** Low-frequency flocculation clouds, ~[-1, 1]. */
        fun formation(px: Float, py: Float): Float {
            val u = px / size
            val v = py / size
            val c = formationCells
            return 0.65f * noise.gradient(u * c, v * c, c, 20) +
                0.35f * noise.gradient(u * c * 2, v * c * 2, c * 2, 21)
        }
    }

    /** Gradient and Worley noise on an integer lattice that wraps every `period` cells. */
    internal class PeriodicNoise(private val seed: Int) {

        /** Perlin gradient noise, roughly in [-1, 1]. */
        fun gradient(x: Float, y: Float, period: Int, layer: Int): Float {
            val xf = floor(x)
            val yf = floor(y)
            val ix = xf.toInt()
            val iy = yf.toInt()
            val tx = x - xf
            val ty = y - yf
            val x0 = Math.floorMod(ix, period)
            val y0 = Math.floorMod(iy, period)
            val x1 = Math.floorMod(ix + 1, period)
            val y1 = Math.floorMod(iy + 1, period)
            val u = fade(tx)
            val v = fade(ty)
            val a = dot(x0, y0, layer, tx, ty)
            val b = dot(x1, y0, layer, tx - 1f, ty)
            val c = dot(x0, y1, layer, tx, ty - 1f)
            val d = dot(x1, y1, layer, tx - 1f, ty - 1f)
            return 1.41f * lerp(lerp(a, b, u), lerp(c, d, u), v)
        }

        /** Distance to the nearest jittered feature point (Worley F1), in cells. */
        fun worley(x: Float, y: Float, period: Int, layer: Int): Float {
            val xf = floor(x).toInt()
            val yf = floor(y).toInt()
            var best = Float.MAX_VALUE
            for (j in -1..1) {
                for (i in -1..1) {
                    val cx = xf + i
                    val cy = yf + j
                    val wx = Math.floorMod(cx, period)
                    val wy = Math.floorMod(cy, period)
                    val fx = cx + unit(wx, wy, layer) - x
                    val fy = cy + unit(wx, wy, layer + 101) - y
                    best = min(best, fx * fx + fy * fy)
                }
            }
            return sqrt(best)
        }

        private fun dot(x: Int, y: Int, layer: Int, dx: Float, dy: Float): Float {
            val g = (hash(x, y, layer) ushr 28) * 2
            return GRADIENTS[g] * dx + GRADIENTS[g + 1] * dy
        }

        private fun unit(x: Int, y: Int, layer: Int): Float = (hash(x, y, layer) ushr 8) / 16_777_216f

        private fun hash(x: Int, y: Int, z: Int): Int {
            var h = seed xor (x * 0x27d4eb2d) xor (y * 0x165667b1) xor (z * 0x1b873593)
            h = (h xor (h ushr 15)) * 0x2c1b3c6d
            h = (h xor (h ushr 12)) * 0x297a2d39
            return h xor (h ushr 15)
        }

        private fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)

        private companion object {
            /** 16 unit vectors, interleaved x, y. */
            val GRADIENTS = FloatArray(32).also {
                for (i in 0 until 16) {
                    val a = (i + 0.5f) * PI.toFloat() / 8f
                    it[2 * i] = cos(a)
                    it[2 * i + 1] = sin(a)
                }
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
