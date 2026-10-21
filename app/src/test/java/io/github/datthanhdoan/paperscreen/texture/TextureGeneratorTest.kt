package io.github.datthanhdoan.paperscreen.texture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TextureGeneratorTest {

    private val params = TextureParams(intensity = 1f, coarseness = 0.5f, seed = 42L)
    private val dpi = 420

    @Test
    fun sameSeedGivesIdenticalTile() {
        val a = TextureGenerator.generate(params, dpi)
        val b = TextureGenerator.generate(params, dpi)
        assertEquals(a.size, b.size)
        assertArrayEquals(a.alpha, b.alpha)
    }

    @Test
    fun differentSeedGivesDifferentTile() {
        val a = TextureGenerator.generate(params, dpi)
        val b = TextureGenerator.generate(params.copy(seed = 43L), dpi)
        assertFalse(a.alpha.contentEquals(b.alpha))
    }

    @Test
    fun noiseFieldIsPeriodicOverTileSize() {
        val size = TextureGenerator.tileSizeFor(dpi)
        val field = TextureGenerator.PaperField(params, size, dpi)
        for (i in 0 until 200) {
            val x = (i * 37 % size) + 0.5f
            val y = (i * 91 % size) + 0.5f
            assertEquals(field.grain(x, y), field.grain(x + size, y), 1e-3f)
            assertEquals(field.grain(x, y), field.grain(x, y + size), 1e-3f)
            assertEquals(field.mottle(x, y), field.mottle(x + size, y + size), 1e-3f)
        }
    }

    /**
     * The step across the wrap seam (last column -> first column) must look like an
     * ordinary step between neighbouring interior columns. A non-tileable image would
     * show a much larger jump there.
     */
    @Test
    fun tileEdgesAreSeamless() {
        val tile = TextureGenerator.generate(params, dpi)
        val n = tile.size

        var interiorCols = 0.0
        var interiorRows = 0.0
        var seamCol = 0.0
        var seamRow = 0.0
        for (i in 0 until n) {
            for (j in 0 until n - 1) {
                interiorCols += abs(tile[j + 1, i] - tile[j, i])
                interiorRows += abs(tile[i, j + 1] - tile[i, j])
            }
            seamCol += abs(tile[0, i] - tile[n - 1, i])
            seamRow += abs(tile[i, 0] - tile[i, n - 1])
        }
        interiorCols /= n.toDouble() * (n - 1)
        interiorRows /= n.toDouble() * (n - 1)
        seamCol /= n
        seamRow /= n

        assertTrue("texture is flat, test is meaningless", interiorCols > 0.5)
        assertTrue("column seam $seamCol vs interior $interiorCols", seamCol < interiorCols * 1.5 + 0.5)
        assertTrue("row seam $seamRow vs interior $interiorRows", seamRow < interiorRows * 1.5 + 0.5)
    }

    @Test
    fun fibersWrapAcrossEdges() {
        val size = TextureGenerator.tileSizeFor(dpi)
        val fibers = TextureGenerator.rasterizeFibers(params, size, dpi)
        // Strands are 2–12 mm long, so with hundreds of them some must cross each edge
        // and leave ink on both the first and the last column.
        val first = (0 until size).count { fibers[it * size] > 0f }
        val last = (0 until size).count { fibers[it * size + size - 1] > 0f }
        assertTrue(first > 0 && last > 0)
    }

    @Test
    fun zeroIntensityIsFullyTransparent() {
        val tile = TextureGenerator.generate(params.copy(intensity = 0f), dpi)
        assertTrue(tile.alpha.all { it.toInt() == 0 })
    }

    @Test
    fun darknessStaysWithinVisibleBudget() {
        val windowAlpha = 0.8f
        val tile = TextureGenerator.generate(params, dpi, windowAlpha)
        val maxAlpha = tile.alpha.maxOf { it.toInt() and 0xFF }
        val limit = TextureGenerator.MAX_VISIBLE_DARKNESS / windowAlpha * 255f
        assertTrue("max $maxAlpha > $limit", maxAlpha <= limit + 1f)
    }

    @Test
    fun tileSizeScalesWithDensity() {
        assertEquals(512, TextureGenerator.tileSizeFor(160))
        assertEquals(704, TextureGenerator.tileSizeFor(560))
        assertEquals(1024, TextureGenerator.tileSizeFor(1200))
        for (dpi in listOf(120, 160, 240, 320, 420, 480, 560, 640)) {
            assertEquals(0, TextureGenerator.tileSizeFor(dpi) % 8)
        }
        assertTrue(TextureGenerator.grainCellPx(0f, 160) >= 1.5f)
        assertTrue(TextureGenerator.grainCellPx(1f, 480) > TextureGenerator.grainCellPx(0f, 480))
    }
}
