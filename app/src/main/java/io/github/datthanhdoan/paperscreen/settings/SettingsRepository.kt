package io.github.datthanhdoan.paperscreen.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.datthanhdoan.paperscreen.texture.TextureParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class PaperSettings(
    val enabled: Boolean = false,
    val intensity: Float = 0.45f,
    val coarseness: Float = 0.4f,
    val warmth: Float = 0.35f,
    val vignette: Float = 0.3f,
    val seed: Long = 1L,
    /** Section A6: on a dark UI the warm tint mostly lifts blacks, so scale it down. */
    val dimTintInDarkMode: Boolean = true,
) {
    val textureParams get() = TextureParams(intensity, coarseness, seed)
}

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
            intensity = this[INTENSITY] ?: d.intensity,
            coarseness = this[COARSENESS] ?: d.coarseness,
            warmth = this[WARMTH] ?: d.warmth,
            vignette = this[VIGNETTE] ?: d.vignette,
            seed = this[SEED] ?: d.seed,
            dimTintInDarkMode = this[DIM_IN_DARK] ?: d.dimTintInDarkMode,
        )
    }

    private fun MutablePreferences.write(s: PaperSettings) {
        this[ENABLED] = s.enabled
        this[INTENSITY] = s.intensity.coerceIn(0f, 1f)
        this[COARSENESS] = s.coarseness.coerceIn(0f, 1f)
        this[WARMTH] = s.warmth.coerceIn(0f, 1f)
        this[VIGNETTE] = s.vignette.coerceIn(0f, 1f)
        this[SEED] = s.seed
        this[DIM_IN_DARK] = s.dimTintInDarkMode
    }

    companion object {
        private val ENABLED = booleanPreferencesKey("enabled")
        private val INTENSITY = floatPreferencesKey("intensity")
        private val COARSENESS = floatPreferencesKey("coarseness")
        private val WARMTH = floatPreferencesKey("warmth")
        private val VIGNETTE = floatPreferencesKey("vignette")
        private val SEED = longPreferencesKey("seed")
        private val DIM_IN_DARK = booleanPreferencesKey("dim_tint_in_dark_mode")

        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
