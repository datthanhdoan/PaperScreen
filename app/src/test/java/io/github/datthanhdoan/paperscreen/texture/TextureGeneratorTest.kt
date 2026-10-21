package io.github.datthanhdoan.paperscreen.texture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TextureGeneratorTest {

    private val params = TextureParams(PaperPreset.PLAIN, intensity = 1f, coarseness = 0.5f, relief = 1f, seed = 42L)
    private val dpi = 420

    @Test
    fun sameSeedGivesIdenticalTile() {
        for (preset in PaperPreset.entries) {
            val p = params.copy(preset = preset, intensity = 0.5f)
            val a = TextureGenerator.generate(p, dpi)
            val b = TextureGenerator.generate(p, dpi)
            assertEquals(a.size, b.size)
            assertArrayEquals("$preset", a.pixels, b.pixels)
        }
    }

    @Test
    fun differentSeedGivesDifferentTile() {
        val a = TextureGenerator.generate(params, dpi)
        val b = TextureGenerator.generate(params.copy(seed = 43L), dpi)
        assertFalse(a.pixels.contentEquals(b.pixels))
    }

    @Test
    fun presetsGiveDifferentTiles() {
        val tiles = PaperPreset.entries.map { TextureGenerator.generate(params.copy(preset = it), dpi).pixels }
        for (i in tiles.indices) for (j in i + 1 until tiles.size) {
            assertFalse("${PaperPreset.entries[i]} == ${PaperPreset.entries[j]}", tiles[i].contentEquals(tiles[j]))
        }
    }

    @Test
    fun heightFieldIsPeriodicOverTileSize() {
        val size = TextureGenerator.tileSizeFor(dpi)
        // DRAWING also exercises the warped Worley domes.
        for (preset in listOf(PaperPreset.PLAIN, PaperPreset.DRAWING)) {
            val field = TextureGenerator.PaperField(params.copy(preset = preset), size, dpi)
            for (i in 0 until 200) {
                val x = (i * 37 % size) + 0.5f
                val y = (i * 91 % size) + 0.5f
                assertEquals(field.surface(x, y), field.surface(x + size, y), 1e-3f)
                assertEquals(field.surface(x, y), field.surface(x, y + size), 1e-3f)
                assertEquals(field.formation(x, y), field.formation(x + size, y + size), 1e-3f)
            }
        }
    }

    /**
     * The step across the wrap seam (last column -> first column) must look like an
     * ordinary step between neighbouring interior columns. A non-tileable image would
     * show a much larger jump there. Checked on alpha (darkening) and on red (lift).
     */
    @Test
    fun tileEdgesAreSeamless() {
        for (preset in PaperPreset.entries) {
            val tile = TextureGenerator.generate(params.copy(preset = preset), dpi)
            for (channel in listOf(3, 0)) assertSeamless(tile, channel, "$preset/$channel")
        }
    }

    private fun assertSeamless(tile: TextureTile, c: Int, label: String) {
        val n = tile.size
        var interiorCols = 0.0
        var interiorRows = 0.0
        var seamCol = 0.0
        var seamRow = 0.0
        for (i in 0 until n) {
            for (j in 0 until n - 1) {
                interiorCols += abs(tile.channel(j + 1, i, c) - tile.channel(j, i, c))
                interiorRows += abs(tile.channel(i, j + 1, c) - tile.channel(i, j, c))
            }
            seamCol += abs(tile.channel(0, i, c) - tile.channel(n - 1, i, c))
            seamRow += abs(tile.channel(i, 0, c) - tile.channel(i, n - 1, c))
        }
        interiorCols /= n.toDouble() * (n - 1)
        interiorRows /= n.toDouble() * (n - 1)
        seamCol /= n
        seamRow /= n

        assertTrue("$label: texture is flat, test is meaningless", interiorCols > 0.3)
        assertTrue("$label: column seam $seamCol vs interior $interiorCols", seamCol < interiorCols * 1.5 + 0.5)
        assertTrue("$label: row seam $seamRow vs interior $interiorRows", seamRow < interiorRows * 1.5 + 0.5)
    }

    @Test
    fun fibersWrapAcrossEdges() {
        val size = TextureGenerator.tileSizeFor(dpi)
        val height = FloatArray(size * size)
        val albedo = FloatArray(size * size)
        TextureGenerator.rasterizeFibers(params, size, dpi, height, albedo)
        // Hundreds of millimetre-long strands: some must cross each edge and leave a
        // deposit on both the first and the last column.
        val first = (0 until size).count { height[it * size] > 0f }
        val last = (0 until size).count { height[it * size + size - 1] > 0f }
        assertTrue(first > 0 && last > 0)
    }

    @Test
    fun zeroIntensityIsFullyTransparent() {
        for (preset in PaperPreset.entries) {
            val tile = TextureGenerator.generate(params.copy(preset = preset, intensity = 0f), dpi)
            assertTrue("$preset", tile.pixels.all { it.toInt() == 0 })
        }
    }

    /** Premultiplied colour can never exceed alpha, or ARGB_8888 rendering is undefined. */
    @Test
    fun pixelsArePremultiplied() {
        for (preset in PaperPreset.entries) {
            val tile = TextureGenerator.generate(params.copy(preset = preset), dpi)
            for (i in 0 until tile.size * tile.size) {
                val a = tile.pixels[i * 4 + 3].toInt() and 0xFF
                for (c in 0..2) assertTrue("$preset", (tile.pixels[i * 4 + c].toInt() and 0xFF) <= a)
            }
        }
    }

    /**
     * On screen a pixel (c, a) under window alpha W gives `out = W c + dst (1 - W a)`:
     * white is darkened by W (a - c) and black is lifted by W c. Both stay in budget,
     * plus the preset's B3 tint, which is uniform.
     */
    @Test
    fun darkeningAndLiftStayWithinBudget() {
        val windowAlpha = 0.8f
        for (preset in PaperPreset.entries) {
            val tile = TextureGenerator.generate(params.copy(preset = preset), dpi, windowAlpha)
            val tintAlpha = with(preset.tint) { maxOf(1 - r, 1 - g, 1 - b) }
            var maxDark = 0f
            var maxLift = 0f
            for (i in 0 until tile.size * tile.size) {
                val a = (tile.pixels[i * 4 + 3].toInt() and 0xFF) / 255f * windowAlpha
                for (c in 0..2) {
                    val col = (tile.pixels[i * 4 + c].toInt() and 0xFF) / 255f * windowAlpha
                    maxDark = maxOf(maxDark, a - col)
                    maxLift = maxOf(maxLift, col)
                }
            }
            val eps = 2f / 255f
            // A highlight whose tone is not white also darkens white a little.
            val darkBudget = TextureGenerator.MAX_DARKENING + TextureGenerator.MAX_LIFT + tintAlpha
            assertTrue("$preset dark $maxDark", maxDark <= darkBudget + eps)
            assertTrue("$preset lift $maxLift", maxLift <= TextureGenerator.MAX_LIFT + tintAlpha + eps)
        }
    }

    /** The default paper must keep black untouched apart from faint relief highlights. */
    @Test
    fun plainPaperBarelyLiftsBlack() {
        val tile = TextureGenerator.generate(params.copy(intensity = 0.5f, relief = 0.5f), dpi)
        var sum = 0.0
        for (i in 0 until tile.size * tile.size) sum += tile.pixels[i * 4].toInt() and 0xFF
        val meanLift = sum / (tile.size * tile.size) / 255.0 * 0.8
        assertTrue("mean lift $meanLift", meanLift < 0.01)
    }

    @Test
    fun reliefZeroIsFlatterThanReliefOne() {
        fun spread(relief: Float): Double {
            val tile = TextureGenerator.generate(params.copy(relief = relief, intensity = 0.5f), dpi)
            val n = tile.size * tile.size
            val mean = (0 until n).sumOf { tile.pixels[it * 4 + 3].toInt() and 0xFF } / n.toDouble()
            return (0 until n).sumOf { abs((tile.pixels[it * 4 + 3].toInt() and 0xFF) - mean) } / n
        }
        assertTrue(spread(0f) < spread(1f))
    }

    @Test
    fun tileSizeScalesWithDensity() {
        assertEquals(640, TextureGenerator.tileSizeFor(160))
        assertEquals(680, TextureGenerator.tileSizeFor(340))
        assertEquals(1024, TextureGenerator.tileSizeFor(560))
        for (dpi in listOf(120, 160, 240, 320, 340, 420, 480, 560, 640)) {
            assertEquals(0, TextureGenerator.tileSizeFor(dpi) % 8)
        }
        assertTrue(TextureGenerator.featureScale(1f) > TextureGenerator.featureScale(0f))
    }

    @Test
    fun unknownPresetNameFallsBackToDefault() {
        assertEquals(PaperPreset.DEFAULT, PaperPreset.fromName(null))
        assertEquals(PaperPreset.DEFAULT, PaperPreset.fromName("NOPE"))
        assertEquals(PaperPreset.KRAFT, PaperPreset.fromName("KRAFT"))
    }
}
