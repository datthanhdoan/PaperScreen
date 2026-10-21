package io.github.datthanhdoan.paperscreen.texture

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Inputs that change the tile pixels. Warmth and vignette are not here: they are
 * drawn by [io.github.datthanhdoan.paperscreen.overlay.PaperView] on top of the tile.
 *
 * @param intensity 0..1, overall darkness of the texture ("độ đậm").
 * @param coarseness 0..1, grain size and fiber density ("độ thô").
 */
data class TextureParams(
    val intensity: Float,
    val coarseness: Float,
    val seed: Long,
)

/** Square ALPHA_8 tile, row-major, one unsigned byte per pixel. */
class TextureTile(val size: Int, val alpha: ByteArray) {
    operator fun get(x: Int, y: Int): Int = alpha[y * size + x].toInt() and 0xFF
}

/**
 * Pure (no Android APIs) generator for a seamless paper tile, see section B4 of
 * docs/feasibility-report.md. The tile stores "darkness" (1 - paper luminance), so
 * drawing it in a near-black colour over the screen behaves like a multiply (B3).
 */
object TextureGenerator {

    /** Darkness at intensity = 1, measured on screen (after window alpha). */
    const val MAX_VISIBLE_DARKNESS = 0.15f

    private const val GRAIN_OCTAVES = 4
    private const val GRAIN_WEIGHT = 0.55f
    private const val MOTTLE_WEIGHT = 0.30f
    private const val FIBER_WEIGHT = 0.60f

    /** Tile edge in physical px: ~200dp, clamped to 512..1024 and a multiple of 8. */
    fun tileSizeFor(densityDpi: Int): Int {
        val px = 200f * densityDpi / 160f
        val rounded = (ceil(px / 8f) * 8f).toInt()
        return rounded.coerceIn(512, 1024)
    }

    /**
     * Smallest grain cell in physical px. Kept at >= 1.5px so single-pixel noise does
     * not sparkle on PenTile/diamond OLED subpixel layouts (B5).
     */
    fun grainCellPx(coarseness: Float, densityDpi: Int): Float {
        val grainDp = lerp(0.6f, 2.4f, coarseness.coerceIn(0f, 1f))
        return max(1.5f, grainDp * densityDpi / 160f)
    }

    /**
     * @param windowAlpha alpha of the overlay window; tile darkness is divided by it so
     *   the on-screen result matches [MAX_VISIBLE_DARKNESS] * intensity.
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
        val field = PaperField(params, size, densityDpi)
        val fibers = rasterizeFibers(params, size, densityDpi)

        val scale = params.intensity.coerceIn(0f, 1f) * MAX_VISIBLE_DARKNESS / windowAlpha
        val out = ByteArray(size * size)
        for (y in 0 until size) {
            checkCancelled()
            val row = y * size
            for (x in 0 until size) {
                val px = x + 0.5f
                val py = y + 0.5f
                val dark = GRAIN_WEIGHT * field.grain(px, py) +
                    MOTTLE_WEIGHT * field.mottle(px, py) +
                    FIBER_WEIGHT * fibers[row + x].coerceAtMost(1f)
                val a = (dark.coerceIn(0f, 1f) * scale).coerceIn(0f, 1f)
                out[row + x] = (a * 255f).roundToInt().toByte()
            }
        }
        return TextureTile(size, out)
    }

    /**
     * Fibers: short quadratic Bézier strands, 2–12 mm long, 0.5–1 px wide, splatted
     * with wrap-around so strands crossing an edge continue on the opposite side.
     */
    internal fun rasterizeFibers(params: TextureParams, size: Int, densityDpi: Int): FloatArray {
        val buf = FloatArray(size * size)
        val rnd = Random(params.seed xor 0x5DEECE66DL)
        val pxPerMm = densityDpi / 25.4f
        val tileMm = size / pxPerMm
        val perMm2 = lerp(0.06f, 0.18f, params.coarseness.coerceIn(0f, 1f))
        val count = (tileMm * tileMm * perMm2).roundToInt()

        repeat(count) {
            val length = lerp(2f, 12f, rnd.nextFloat()) * pxPerMm
            val angle = rnd.nextFloat() * 2f * PI.toFloat()
            val x0 = rnd.nextFloat() * size
            val y0 = rnd.nextFloat() * size
            val dx = cos(angle) * length
            val dy = sin(angle) * length
            val bend = (rnd.nextFloat() - 0.5f) * 0.5f * length
            // Control point: midpoint pushed sideways, perpendicular to the strand.
            val cx = x0 + dx / 2f - sin(angle) * bend
            val cy = y0 + dy / 2f + cos(angle) * bend
            val x2 = x0 + dx
            val y2 = y0 + dy
            val width = lerp(0.5f, 1f, rnd.nextFloat())
            val strength = lerp(0.25f, 0.6f, rnd.nextFloat())

            val steps = ceil(length * 2f).toInt().coerceAtLeast(2)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val u = 1f - t
                val x = u * u * x0 + 2f * u * t * cx + t * t * x2
                val y = u * u * y0 + 2f * u * t * cy + t * t * y2
                // Taper both ends; 0.5px step so each step deposits half a pixel.
                val w = strength * width * sin(PI.toFloat() * t) * 0.5f
                splat(buf, size, x, y, w)
            }
        }
        return buf
    }

    private fun splat(buf: FloatArray, size: Int, x: Float, y: Float, w: Float) {
        val fx = x - 0.5f
        val fy = y - 0.5f
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        add(buf, size, ix, iy, w * (1 - tx) * (1 - ty))
        add(buf, size, ix + 1, iy, w * tx * (1 - ty))
        add(buf, size, ix, iy + 1, w * (1 - tx) * ty)
        add(buf, size, ix + 1, iy + 1, w * tx * ty)
    }

    private fun add(buf: FloatArray, size: Int, x: Int, y: Int, w: Float) {
        buf[Math.floorMod(y, size) * size + Math.floorMod(x, size)] += w
    }

    /**
     * Continuous, periodic noise fields over pixel coordinates. Every octave uses an
     * integer number of lattice cells per tile, so `f(x + size, y) == f(x, y)`.
     */
    internal class PaperField(params: TextureParams, val size: Int, densityDpi: Int) {
        private val noise = PeriodicValueNoise(params.seed.toInt() xor (params.seed ushr 32).toInt())
        private val grainCells: IntArray
        private val mottleCells = intArrayOf(2, 4)

        init {
            val finest = max(1, (size / grainCellPx(params.coarseness, densityDpi)).roundToInt())
            grainCells = IntArray(GRAIN_OCTAVES) { i ->
                max(1, finest shr (GRAIN_OCTAVES - 1 - i))
            }
        }

        /** fBm grain, persistence 0.5, contrast-stretched to 0..1. */
        fun grain(px: Float, py: Float): Float {
            val v = fbm(px, py, grainCells, octaveOffset = 0)
            return ((v - 0.5f) * 2.4f + 0.5f).coerceIn(0f, 1f)
        }

        /** Very low frequency blotches. */
        fun mottle(px: Float, py: Float): Float {
            val v = fbm(px, py, mottleCells, octaveOffset = 16)
            return ((v - 0.5f) * 2f + 0.5f).coerceIn(0f, 1f)
        }

        private fun fbm(px: Float, py: Float, cells: IntArray, octaveOffset: Int): Float {
            var sum = 0f
            var norm = 0f
            var amp = 1f
            for (i in cells.indices) {
                val c = cells[i]
                sum += amp * noise.sample(px * c / size, py * c / size, c, octaveOffset + i)
                norm += amp
                amp *= 0.5f
            }
            return sum / norm
        }
    }

    /** Value noise on an integer lattice that wraps every [period] cells. */
    internal class PeriodicValueNoise(private val seed: Int) {
        fun sample(x: Float, y: Float, period: Int, layer: Int): Float {
            val xf = floor(x)
            val yf = floor(y)
            val ix = xf.toInt()
            val iy = yf.toInt()
            val sx = smooth(x - xf)
            val sy = smooth(y - yf)
            val x0 = Math.floorMod(ix, period)
            val y0 = Math.floorMod(iy, period)
            val x1 = Math.floorMod(ix + 1, period)
            val y1 = Math.floorMod(iy + 1, period)
            val a = lattice(x0, y0, layer)
            val b = lattice(x1, y0, layer)
            val c = lattice(x0, y1, layer)
            val d = lattice(x1, y1, layer)
            return lerp(lerp(a, b, sx), lerp(c, d, sx), sy)
        }

        private fun lattice(x: Int, y: Int, layer: Int): Float =
            (hash(x, y, layer) ushr 8) / 16_777_216f

        private fun hash(x: Int, y: Int, z: Int): Int {
            var h = seed xor (x * 0x27d4eb2d) xor (y * 0x165667b1) xor (z * 0x1b873593)
            h = (h xor (h ushr 15)) * 0x2c1b3c6d
            h = (h xor (h ushr 12)) * 0x297a2d39
            return h xor (h ushr 15)
        }

        private fun smooth(t: Float) = t * t * (3f - 2f * t)
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
