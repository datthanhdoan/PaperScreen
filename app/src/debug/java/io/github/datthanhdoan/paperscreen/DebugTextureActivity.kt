package io.github.datthanhdoan.paperscreen

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.datthanhdoan.paperscreen.overlay.OverlayLauncher
import io.github.datthanhdoan.paperscreen.settings.SettingsRepository
import io.github.datthanhdoan.paperscreen.texture.PaperPreset
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Debug-only hook for before/after screenshots without touching the settings UI:
 *
 *     adb shell am start -n io.github.datthanhdoan.paperscreen.debug/io.github.datthanhdoan.paperscreen.DebugTextureActivity \
 *         --es preset DRAWING --ef intensity 0.5 --ef relief 0.7 --ez enabled true
 *
 * Extras: preset, intensity, coarseness, relief, warmth, vignette (float), seed (long),
 * enabled (boolean: true starts the overlay, false stops it).
 */
class DebugTextureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras ?: Bundle()
        val repository = SettingsRepository.get(this)
        MainScope().launch {
            repository.update { s ->
                fun f(key: String, old: Float) = if (extras.containsKey(key)) extras.getFloat(key) else old
                s.copy(
                    preset = extras.getString("preset")?.let { PaperPreset.fromName(it.uppercase()) } ?: s.preset,
                    intensity = f("intensity", s.intensity),
                    coarseness = f("coarseness", s.coarseness),
                    relief = f("relief", s.relief),
                    warmth = f("warmth", s.warmth),
                    vignette = f("vignette", s.vignette),
                    seed = if (extras.containsKey("seed")) extras.getLong("seed") else s.seed,
                )
            }
            when {
                !extras.containsKey("enabled") -> Unit
                extras.getBoolean("enabled") -> Log.i(TAG, "start: ${OverlayLauncher.start(this@DebugTextureActivity)}")
                else -> OverlayLauncher.disable(this@DebugTextureActivity)
            }
            finish()
        }
    }

    private companion object {
        const val TAG = "DebugTexture"
    }
}
