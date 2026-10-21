package io.github.datthanhdoan.paperscreen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperSettingsTest {

    private val s = PaperSettings(
        intensity = 0.7f, relief = 0.3f, warmth = 0.4f, vignette = 0.2f,
        darkIntensity = 0.6f, darkRelief = 0.8f, darkWarmth = 0.05f,
    )

    @Test
    fun lightModeUsesLightValues() {
        assertEquals(PaperProfile(0.7f, 0.3f, 0.4f, 0.2f), s.profile(night = false))
        assertFalse(s.textureParams(night = false).darkUi)
        assertEquals(s.textureParams, s.textureParams(night = false))
    }

    @Test
    fun darkModeUsesDarkProfile() {
        assertEquals(PaperProfile(0.6f, 0.8f, 0.05f, 0.2f), s.profile(night = true))
        val p = s.textureParams(night = true)
        assertTrue(p.darkUi)
        assertEquals(0.6f, p.intensity)
        assertEquals(0.8f, p.relief)
    }

    @Test
    fun withoutDarkProfileDarkModeDimsLightWarmth() {
        val off = s.copy(darkProfileEnabled = false)
        assertEquals(PaperProfile(0.7f, 0.3f, 0.4f * PaperSettings.DIM_WARMTH_FACTOR, 0.2f), off.profile(night = true))
        assertEquals(0.4f, off.copy(dimTintInDarkMode = false).profile(night = true).warmth)
        // The dark tile variant still applies, so the preset tint stays reduced.
        assertTrue(off.textureParams(night = true).darkUi)
    }
}
