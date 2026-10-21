package io.github.datthanhdoan.paperscreen.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.datthanhdoan.paperscreen.texture.PaperPreset
import io.github.datthanhdoan.paperscreen.texture.TextureParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class PaperSettings(
    val enabled: Boolean = false,
    /** Paper type; intensity, coarseness and relief adjust it. */
    val preset: PaperPreset = PaperPreset.DEFAULT,
    /** 0.5 is the preset's nominal strength. */
    val intensity: Float = 0.5f,
    /** 0.5 keeps the preset's feature sizes. */
    val coarseness: Float = 0.5f,
    /** Emboss shading strength ("độ nổi"). */
    val relief: Float = 0.5f,
    val warmth: Float = 0.35f,
    val vignette: Float = 0.3f,
    val seed: Long = 1L,
    /**
     * Section A6: on a dark UI the warm tint mostly lifts blacks, so scale it down.
     * Only used while [darkProfileEnabled] is off; the dark profile has its own warmth.
     */
    val dimTintInDarkMode: Boolean = true,
    /** "Dùng thiết lập riêng cho chế độ tối": use the dark* values below in dark mode. */
    val darkProfileEnabled: Boolean = true,
    /** Texture strength in dark mode. On a dark UI it mostly shows as relief highlights. */
    val darkIntensity: Float = 0.5f,
    /** Emboss strength in dark mode. */
    val darkRelief: Float = 0.6f,
    /** Warm tint in dark mode; every bit of it lifts black, so the default is low. */
    val darkWarmth: Float = 0.1f,
) {
    /** Light-mode texture, kept for callers that do not care about the UI mode. */
    val textureParams get() = textureParams(night = false)

    /** The values that apply in the given UI mode. */
    fun profile(night: Boolean): PaperProfile = when {
        !night -> PaperProfile(intensity, relief, warmth, vignette)
        darkProfileEnabled -> PaperProfile(darkIntensity, darkRelief, darkWarmth, vignette)
        else -> {
            val w = if (dimTintInDarkMode) warmth * DIM_WARMTH_FACTOR else warmth
            PaperProfile(intensity, relief, w, vignette)
        }
    }

    /**
     * Tile inputs for the given UI mode. A dark UI always gets the dark tile variant,
     * whose preset tint is reduced so kraft/dó/newsprint do not turn black brown.
     */
    fun textureParams(night: Boolean): TextureParams {
        val p = profile(night)
        return TextureParams(preset, p.intensity, coarseness, p.relief, seed, darkUi = night)
    }

    companion object {
        /** Warmth scale in dark mode when there is no dark profile (old dimTintInDarkMode). */
        const val DIM_WARMTH_FACTOR = 0.25f
    }
}

/** Effective per-mode values, see [PaperSettings.profile]. */
data class PaperProfile(
    val intensity: Float,
    val relief: Float,
    val warmth: Float,
    val vignette: Float,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Single source of truth shared by the activity, the overlay service, the tile and boot. */
class SettingsRepository private constructor(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<PaperSettings> = store.data
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    suspend fun update(transform: (PaperSettings) -> PaperSettings) {
        store.edit { it.write(transform(it.toSettings())) }
    }

    private fun Preferences.toSettings(): PaperSettings {
        val d = PaperSettings()
        return PaperSettings(
            enabled = this[ENABLED] ?: d.enabled,
            preset = PaperPreset.fromName(this[PRESET]),
            intensity = this[INTENSITY] ?: d.intensity,
            coarseness = this[COARSENESS] ?: d.coarseness,
            relief = this[RELIEF] ?: d.relief,
            warmth = this[WARMTH] ?: d.warmth,
            vignette = this[VIGNETTE] ?: d.vignette,
            seed = this[SEED] ?: d.seed,
            dimTintInDarkMode = this[DIM_IN_DARK] ?: d.dimTintInDarkMode,
            darkProfileEnabled = this[DARK_PROFILE] ?: d.darkProfileEnabled,
            darkIntensity = this[DARK_INTENSITY] ?: d.darkIntensity,
            darkRelief = this[DARK_RELIEF] ?: d.darkRelief,
            darkWarmth = this[DARK_WARMTH] ?: d.darkWarmth,
        )
    }

    private fun MutablePreferences.write(s: PaperSettings) {
        this[ENABLED] = s.enabled
        this[PRESET] = s.preset.name
        this[INTENSITY] = s.intensity.coerceIn(0f, 1f)
        this[COARSENESS] = s.coarseness.coerceIn(0f, 1f)
        this[RELIEF] = s.relief.coerceIn(0f, 1f)
        this[WARMTH] = s.warmth.coerceIn(0f, 1f)
        this[VIGNETTE] = s.vignette.coerceIn(0f, 1f)
        this[SEED] = s.seed
        this[DIM_IN_DARK] = s.dimTintInDarkMode
        this[DARK_PROFILE] = s.darkProfileEnabled
        this[DARK_INTENSITY] = s.darkIntensity.coerceIn(0f, 1f)
        this[DARK_RELIEF] = s.darkRelief.coerceIn(0f, 1f)
        this[DARK_WARMTH] = s.darkWarmth.coerceIn(0f, 1f)
    }

    companion object {
        private val ENABLED = booleanPreferencesKey("enabled")
        private val PRESET = stringPreferencesKey("preset")
        private val INTENSITY = floatPreferencesKey("intensity")
        private val COARSENESS = floatPreferencesKey("coarseness")
        private val RELIEF = floatPreferencesKey("relief")
        private val WARMTH = floatPreferencesKey("warmth")
        private val VIGNETTE = floatPreferencesKey("vignette")
        private val SEED = longPreferencesKey("seed")
        private val DIM_IN_DARK = booleanPreferencesKey("dim_tint_in_dark_mode")
        private val DARK_PROFILE = booleanPreferencesKey("dark_profile_enabled")
        private val DARK_INTENSITY = floatPreferencesKey("dark_intensity")
        private val DARK_RELIEF = floatPreferencesKey("dark_relief")
        private val DARK_WARMTH = floatPreferencesKey("dark_warmth")

        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
