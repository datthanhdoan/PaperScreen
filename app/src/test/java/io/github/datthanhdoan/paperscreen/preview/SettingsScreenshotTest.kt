package io.github.datthanhdoan.paperscreen.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import io.github.datthanhdoan.paperscreen.R
import io.github.datthanhdoan.paperscreen.SettingsActivity
import io.github.datthanhdoan.paperscreen.overlay.PaperView
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import io.github.datthanhdoan.paperscreen.texture.PaperPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

/**
 * Renders the real settings screen (layouts, theme, IosSwitch, sliders, the live
 * PaperView preview) on the JVM, so the UI can be checked without a device. Writes PNGs
 * and an index.html gallery to `app/build/settings-preview/`, which CI uploads.
 *
 * Runs on SDK 34: Robolectric needs JDK 21 for SDK 35+, CI builds with JDK 17. Layout
 * and drawing do not depend on that difference.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SettingsScreenshotTest {

    private class Shot(
        val name: String,
        val label: String,
        val qualifiers: String,
        val canDraw: Boolean = true,
        /** Also capture the list scrolled to the end (two-pane layouts). */
        val scrolledToo: Boolean = false,
        val expandRestricted: Boolean = false,
    )

    private val outDir = File("build/settings-preview")

    @Test
    fun renderGallery() {
        outDir.mkdirs()
        val app = RuntimeEnvironment.getApplication()
        runBlocking {
            SettingsRepository.get(app).update { it.copy(enabled = true, preset = PaperPreset.DO) }
        }

        val written = mutableListOf<Pair<String, String>>()
        for (shot in SHOTS) {
            RuntimeEnvironment.setQualifiers(shot.qualifiers)
            ShadowSettings.setCanDrawOverlays(shot.canDraw)
            val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
            val activity = controller.get()
            settle(activity.window.decorView)
            if (shot.expandRestricted) {
                activity.findViewById<View>(R.id.row_restricted).performClick()
                settle(activity.window.decorView)
            }
            written += capture(activity.window.decorView, shot.name) to shot.label
            if (shot.scrolledToo) {
                val scroll = activity.findViewById<NestedScrollView>(R.id.scroll)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                settle(activity.window.decorView)
                written += capture(activity.window.decorView, shot.name + "_end") to shot.label + ", cuộn xuống cuối"
            }
            controller.pause().stop().destroy()
        }
        writeIndex(written)
        assertTrue(written.all { File(outDir, it.first).length() > 0 })
    }

    /** Runs the main looper until DataStore has emitted and the preview tile is drawn. */
    private fun settle(root: View) {
        val paper = find(root) { it is PaperView } as PaperView?
        val tileField = PaperView::class.java.getDeclaredField("tile").apply { isAccessible = true }
        repeat(150) {
            shadowOf(Looper.getMainLooper()).idle()
            if (it > 5 && (paper == null || tileField.get(paper) != null)) return
            Thread.sleep(100)
        }
    }

    private fun capture(root: View, name: String): String {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = "$name.png"
        File(outDir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let { return it }
        }
        return null
    }

    private fun writeIndex(shots: List<Pair<String, String>>) {
        val cells = shots.joinToString("\n") { (file, label) ->
            """<figure><figcaption>$label</figcaption><a href="$file"><img src="$file" alt="$label"></a></figure>"""
        }
        File(outDir, "index.html").writeText(
            """
            <!doctype html><html lang="vi"><head><meta charset="utf-8"><title>PaperScreen settings preview</title>
            <style>body{margin:0;padding:24px;background:#2b2b2e;color:#ddd;font:14px system-ui}
            main{display:flex;flex-wrap:wrap;gap:32px;align-items:flex-start}
            figure{margin:0}figcaption{margin-bottom:8px}img{display:block;max-width:420px;height:auto;border-radius:8px}
            figure.wide img{max-width:900px}</style></head>
            <body><h1>Màn hình cài đặt (Robolectric, SDK 34)</h1><main>
            $cells
            </main></body></html>
            """.trimIndent(),
        )
    }

    private companion object {
        const val PHONE = "w393dp-h2300dp-port-xhdpi"
        const val TABLET = "w1280dp-h800dp-land-hdpi"

        val SHOTS = listOf(
            Shot("phone_dark", "Điện thoại, tối", "$PHONE-night"),
            Shot("phone_light", "Điện thoại, sáng", "$PHONE-notnight"),
            Shot("tablet_dark", "Tablet ngang, tối", "$TABLET-night", scrolledToo = true),
            Shot("tablet_light", "Tablet ngang, sáng", "$TABLET-notnight", scrolledToo = true),
            Shot(
                "phone_dark_setup", "Điện thoại, tối, chưa cấp quyền (đã mở hướng dẫn)",
                "$PHONE-night", canDraw = false, expandRestricted = true,
            ),
        )
    }
}
