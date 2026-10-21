package io.github.datthanhdoan.paperscreen.preview

import io.github.datthanhdoan.paperscreen.overlay.PaperAppearance
import io.github.datthanhdoan.paperscreen.overlay.PaperLayers
import io.github.datthanhdoan.paperscreen.overlay.appearance
import io.github.datthanhdoan.paperscreen.settings.PaperSettings
import io.github.datthanhdoan.paperscreen.texture.PaperPreset
import io.github.datthanhdoan.paperscreen.texture.TextureGenerator
import io.github.datthanhdoan.paperscreen.texture.TextureTile
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders what the overlay would look like, without a device: sample screens (light
 * page, dark page, photo) composited with the tile, warm tint and vignette exactly as
 * WindowManager blends a TYPE_APPLICATION_OVERLAY window (premultiplied src-over inside
 * the window, then the window alpha). Writes PNGs and an index.html gallery to
 * `app/build/texture-preview/`, which CI uploads as an artifact.
 */
class TexturePreviewTest {

    private val dpi = 340
    private val windowAlpha = 0.8f
    private val width = 900
    private val height = 600
    private val outDir = File("build/texture-preview")

    private enum class Screen(val label: String, val night: Boolean) {
        LIGHT_PAGE("Trang chữ nền trắng", false),
        DARK_PAGE("Trang chữ nền tối", true),
        PHOTO("Ảnh màu", false),
    }

    private enum class Variant(val label: String) {
        NONE("Không overlay"),
        OLD_DARK("Tối, kiểu cũ (v0.2)"),
    }

    @Test
    fun renderGallery() {
        System.setProperty("java.awt.headless", "true")
        outDir.mkdirs()
        val settings = PaperSettings(enabled = true)
        val screens = Screen.entries.associateWith { render(it) }
        val rows = StringBuilder()

        for (s in Screen.entries) write(screens.getValue(s), "none_${s.name.lowercase()}.png")

        for (preset in PaperPreset.entries) {
            val st = settings.copy(preset = preset)
            val lightTile = TextureGenerator.generate(st.textureParams(night = false), dpi, windowAlpha)
            val darkTile = TextureGenerator.generate(st.textureParams(night = true), dpi, windowAlpha)
            val cells = StringBuilder()
            for (s in Screen.entries) {
                val night = s.night
                val tile = if (night) darkTile else lightTile
                val img = composite(screens.getValue(s), tile, st.appearance(night, windowAlpha))
                val name = "${preset.name.lowercase()}_${s.name.lowercase()}.png"
                write(img, name)
                cells.append(cell(name, "${s.label} · ${if (night) "hồ sơ tối" else "hồ sơ sáng"}"))
                if (night) {
                    // Before this change: light tile and dimmed light warmth on a dark UI.
                    val old = composite(
                        screens.getValue(s),
                        lightTile,
                        st.copy(darkProfileEnabled = false).appearance(true, windowAlpha),
                    )
                    val oldName = "${preset.name.lowercase()}_${s.name.lowercase()}_old.png"
                    write(old, oldName)
                    cells.append(cell(oldName, "${s.label} · ${Variant.OLD_DARK.label}"))
                }
                if (s == Screen.PHOTO) {
                    val photoDark = composite(screens.getValue(s), darkTile, st.appearance(true, windowAlpha))
                    val pdName = "${preset.name.lowercase()}_photo_dark.png"
                    write(photoDark, pdName)
                    cells.append(cell(pdName, "${s.label} · hồ sơ tối"))
                }
            }
            rows.append("<h2>${preset.name}</h2><div class=\"grid\">$cells</div>\n")
        }

        val none = Screen.entries.joinToString("") {
            cell("none_${it.name.lowercase()}.png", "${it.label} · ${Variant.NONE.label}")
        }
        val p = settings
        File(outDir, "index.html").writeText(
            """
            <!doctype html><html lang="vi"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>PaperScreen texture preview</title>
            <style>
            body{font:14px/1.4 system-ui,sans-serif;margin:16px;background:#777;color:#111}
            h1,h2{margin:24px 0 8px} .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(450px,1fr));gap:12px}
            figure{margin:0;background:#eee;padding:6px} figure img{width:100%;display:block;image-rendering:auto}
            figcaption{padding-top:4px} code{background:#ddd;padding:0 3px}
            </style></head><body>
            <h1>PaperScreen: mô phỏng overlay</h1>
            <p>Mô phỏng ${dpi} dpi, window alpha $windowAlpha, ảnh ${width}×$height px ở tỉ lệ 1:1 (bấm để xem đầy đủ).
            Sáng: intensity ${p.intensity}, relief ${p.relief}, warmth ${p.warmth}, vignette ${p.vignette}.
            Tối: darkIntensity ${p.darkIntensity}, darkRelief ${p.darkRelief}, darkWarmth ${p.darkWarmth}.
            "Kiểu cũ" là tile sáng cùng warmth ×${PaperSettings.DIM_WARMTH_FACTOR} trên giao diện tối, như bản v0.2.</p>
            <h2>Gốc</h2><div class="grid">$none</div>
            $rows
            </body></html>
            """.trimIndent(),
        )
        assertTrue(File(outDir, "index.html").length() > 0)
    }

    /** Kraft's black lift on a dark UI must drop well below the light-mode tile's. */
    @Test
    fun darkTileKeepsBlackDark() {
        val black = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val st = PaperSettings(enabled = true, preset = PaperPreset.KRAFT)
        val oldTile = TextureGenerator.generate(st.textureParams(night = false), dpi, windowAlpha)
        val newTile = TextureGenerator.generate(st.textureParams(night = true), dpi, windowAlpha)
        val old = composite(black, oldTile, st.copy(darkProfileEnabled = false).appearance(true, windowAlpha))
        val new = composite(black, newTile, st.appearance(true, windowAlpha))
        val oldRed = meanRed(old)
        val newRed = meanRed(new)
        assertTrue("old $oldRed new $newRed", newRed < oldRed * 0.5)
        assertTrue("new $newRed", newRed < 255 * 0.05)
    }

    private fun meanRed(img: BufferedImage): Double {
        var sum = 0.0
        for (y in 0 until img.height) for (x in 0 until img.width) sum += (img.getRGB(x, y) shr 16) and 0xFF
        return sum / (img.width * img.height)
    }

    /**
     * Inside the window: tile, then tint, then vignette, each premultiplied src-over.
     * Window to screen: `out = W * c + dst * (1 - W * a)`.
     */
    private fun composite(dst: BufferedImage, tile: TextureTile, appearance: PaperAppearance): BufferedImage {
        val out = BufferedImage(dst.width, dst.height, BufferedImage.TYPE_INT_RGB)
        val ta = PaperLayers.tintAlpha(appearance)
        val tint = rgb(PaperLayers.TINT)
        val va = PaperLayers.vignetteAlpha(appearance)
        val ink = rgb(PaperLayers.INK)
        val stops = PaperLayers.VIGNETTE_STOPS
        val radius = hypot(dst.width.toFloat(), dst.height.toFloat()) / 2f
        val w = appearance.windowAlpha
        val c = FloatArray(3)
        for (y in 0 until dst.height) {
            for (x in 0 until dst.width) {
                val tx = x % tile.size
                val ty = y % tile.size
                var a = tile.alpha(tx, ty) / 255f
                for (i in 0..2) c[i] = tile.channel(tx, ty, i) / 255f
                // Tint layer.
                for (i in 0..2) c[i] = tint[i] * ta + c[i] * (1 - ta)
                a = ta + a * (1 - ta)
                // Vignette: transparent up to stops[1] of the half-diagonal, then a ramp.
                val r = hypot(x + 0.5f - dst.width / 2f, y + 0.5f - dst.height / 2f) / radius
                val v = va * ((r - stops[1]) / (stops[2] - stops[1])).coerceIn(0f, 1f)
                for (i in 0..2) c[i] = ink[i] * v + c[i] * (1 - v)
                a = v + a * (1 - v)

                val d = dst.getRGB(x, y)
                var o = 0
                for (i in 0..2) {
                    val dv = ((d shr (16 - 8 * i)) and 0xFF) / 255f
                    val ov = (w * c[i] + dv * (1 - w * a)).coerceIn(0f, 1f)
                    o = o or ((ov * 255f).roundToInt() shl (16 - 8 * i))
                }
                out.setRGB(x, y, o)
            }
        }
        return out
    }

    private fun rgb(argb: Int) = FloatArray(3) { ((argb shr (16 - 8 * it)) and 0xFF) / 255f }

    private fun render(screen: Screen): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        when (screen) {
            Screen.LIGHT_PAGE -> page(g, Color.WHITE, Color(0x111111), Color(0x555555), Color(0xF1F3F4))
            Screen.DARK_PAGE -> page(g, Color(0x121212), Color(0xE6E6E6), Color(0x9E9E9E), Color(0x1E1E1E))
            Screen.PHOTO -> photo(g)
        }
        g.dispose()
        return img
    }

    /** A reading page: title, body, small UI text, a card and an accent button. */
    private fun page(g: Graphics2D, bg: Color, fg: Color, muted: Color, card: Color) {
        g.color = bg
        g.fillRect(0, 0, width, height)
        g.color = fg
        g.font = Font(Font.SERIF, Font.BOLD, 40)
        g.drawString("Trên giấy và màn hình", 40, 70)
        g.font = Font(Font.SERIF, Font.PLAIN, 28)
        val body = listOf(
            "Giấy được làm từ sợi xenluloza ép lại rồi phơi khô.",
            "Mặt giấy không bao giờ phẳng: sợi đan chéo, chồng lên",
            "nhau, tạo phù điêu rất mịn bắt ánh sáng xiên.",
        )
        body.forEachIndexed { i, line -> g.drawString(line, 40, 125 + i * 40) }
        g.color = muted
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        g.drawString("Chữ nhỏ 12sp: The quick brown fox jumps over the lazy dog 0123456789", 40, 270)
        g.color = card
        g.fillRoundRect(40, 300, 820, 170, 24, 24)
        g.color = fg
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
        g.drawString("Thẻ nội dung", 70, 345)
        g.color = muted
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        g.drawString("Văn bản phụ trên nền thẻ, kiểm tra độ tương phản.", 70, 385)
        g.color = Color(0x1A, 0x73, 0xE8)
        g.fillRoundRect(70, 410, 180, 44, 22, 22)
        g.color = Color.WHITE
        g.drawString("Nút bấm", 115, 440)
        g.color = Color(0xD9, 0x30, 0x25)
        g.fillOval(780, 410, 44, 44)
        g.color = muted
        g.stroke = BasicStroke(2f)
        g.drawLine(40, 510, 860, 510)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 20)
        g.drawString("Thanh điều hướng · Trang chủ · Tìm kiếm · Cài đặt", 40, 550)
    }

    /** Synthetic landscape: sky gradient, sun, layered hills with foliage-like ripples. */
    private fun photo(g: Graphics2D) {
        g.paint = GradientPaint(0f, 0f, Color(0x2B, 0x5D, 0xA8), 0f, height * 0.6f, Color(0xF6, 0xC1, 0x8A))
        g.fillRect(0, 0, width, height)
        g.paint = RadialGradientPaint(650f, 170f, 90f, floatArrayOf(0f, 0.6f, 1f),
            arrayOf(Color(255, 250, 220), Color(255, 220, 150), Color(255, 200, 120, 0)))
        g.fill(Ellipse2D.Float(560f, 80f, 180f, 180f))
        val hills = listOf(
            Triple(330f, Color(0x6B, 0x8E, 0x5A), Color(0x3F, 0x5F, 0x3A)),
            Triple(400f, Color(0x3E, 0x6B, 0x3A), Color(0x1F, 0x3A, 0x22)),
            Triple(480f, Color(0x2A, 0x45, 0x22), Color(0x0E, 0x18, 0x0C)),
        )
        hills.forEachIndexed { i, (base, top, bottom) ->
            val path = Path2D.Float()
            path.moveTo(0f, height.toFloat())
            var x = 0f
            while (x <= width) {
                val y = base - 40f * sin(x / (90f + 40f * i) + i) - 12f * sin(x / 17f + i * 3)
                path.lineTo(x, y)
                x += 4f
            }
            path.lineTo(width.toFloat(), height.toFloat())
            path.closePath()
            g.paint = GradientPaint(0f, base - 60f, top, 0f, height.toFloat(), bottom)
            g.fill(path)
        }
        g.color = Color(0xF4, 0xF1, 0xEA)
        g.fill(Ellipse2D.Float(120f, 520f, 260f, 40f))
    }

    private fun cell(name: String, caption: String) =
        "<figure><a href=\"$name\"><img src=\"$name\" alt=\"$caption\"></a><figcaption>$caption</figcaption></figure>"

    private fun write(img: BufferedImage, name: String) {
        ImageIO.write(img, "png", File(outDir, name))
    }
}
