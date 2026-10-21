package io.github.datthanhdoan.paperscreen.texture

/**
 * Physical description of one paper type, see docs/texture-research.md. Lengths are
 * in millimetres at coarseness 0.5; darkness values are fractions of white on screen
 * at intensity 0.5 (the preset's nominal strength).
 *
 * @param toothMm wavelength of the fine surface relief ("tooth").
 * @param toothAmp height of that relief.
 * @param bumpMm size of cold-press domes (drawing paper), 0 for none.
 * @param fibersPerMm2 strand density; fibers are drawn into both height and colour.
 * @param fiberDarkness extra darkness of a fiber; negative draws lighter, silky strands.
 * @param allFibersDark false = only about half the strands carry [fiberDarkness].
 * @param fiberBend sideways bend of a strand relative to its length.
 * @param formationMm wavelength of flocculation ("formation") clouds.
 * @param formationDarkness darkness contrast of those clouds.
 * @param specksPerMm2 tiny dark inclusions (newsprint, recycled pulp).
 * @param baseDarkness mean darkening of white. Needed so that relief highlights can
 *   be lighter than their surroundings even on a white page.
 * @param highlightTone colour of the light component, i.e. what shows through ink.
 * @param tint whole-sheet multiply colour on white, approximated as in B3. Every
 *   channel below 1 also lifts black, so keep it mild.
 * @param relief strength of the emboss shading at relief = 0.5.
 */
enum class PaperPreset(
    val toothMm: Float,
    val toothAmp: Float,
    val bumpMm: Float,
    val bumpAmp: Float,
    val fibersPerMm2: Float,
    val fiberMinMm: Float,
    val fiberMaxMm: Float,
    val fiberWidthMm: Float,
    val fiberHeight: Float,
    val fiberDarkness: Float,
    val allFibersDark: Boolean,
    val fiberBend: Float,
    val formationMm: Float,
    val formationDarkness: Float,
    val specksPerMm2: Float,
    val baseDarkness: Float,
    val highlightTone: Rgb,
    val tint: Rgb,
    val relief: Float,
) {
    /** Smooth book/office paper: fine tooth, a few faint fibers. The default. */
    PLAIN(
        toothMm = 0.16f, toothAmp = 1.0f, bumpMm = 0f, bumpAmp = 0f,
        fibersPerMm2 = 0.25f, fiberMinMm = 0.6f, fiberMaxMm = 2.0f, fiberWidthMm = 0.05f,
        fiberHeight = 0.6f, fiberDarkness = 0f, allFibersDark = false, fiberBend = 0.6f,
        formationMm = 2.5f, formationDarkness = 0.010f, specksPerMm2 = 0f,
        baseDarkness = 0.035f, highlightTone = Rgb(1.00f, 0.98f, 0.95f), tint = Rgb.WHITE,
        relief = 0.9f,
    ),

    /** Greyer, softer relief, short dark fibers and specks, cloudy formation. */
    NEWSPRINT(
        toothMm = 0.14f, toothAmp = 0.6f, bumpMm = 0f, bumpAmp = 0f,
        fibersPerMm2 = 0.6f, fiberMinMm = 0.3f, fiberMaxMm = 1.2f, fiberWidthMm = 0.04f,
        fiberHeight = 0.25f, fiberDarkness = 0.12f, allFibersDark = false, fiberBend = 0.6f,
        formationMm = 2.0f, formationDarkness = 0.025f, specksPerMm2 = 0.4f,
        baseDarkness = 0.070f, highlightTone = Rgb(0.97f, 0.96f, 0.93f), tint = Rgb(0.99f, 0.98f, 0.95f),
        relief = 0.6f,
    ),

    /** Cold-press drawing/watercolour paper: irregular domes plus tooth. */
    DRAWING(
        toothMm = 0.14f, toothAmp = 0.7f, bumpMm = 0.7f, bumpAmp = 0.6f,
        fibersPerMm2 = 0.12f, fiberMinMm = 0.6f, fiberMaxMm = 1.8f, fiberWidthMm = 0.05f,
        fiberHeight = 0.4f, fiberDarkness = 0f, allFibersDark = false, fiberBend = 0.6f,
        formationMm = 3.0f, formationDarkness = 0.006f, specksPerMm2 = 0f,
        baseDarkness = 0.045f, highlightTone = Rgb(1.00f, 0.99f, 0.96f), tint = Rgb.WHITE,
        relief = 1.0f,
    ),

    /** Brown kraft: dense dark fibers, strong formation. Its tint lifts black to brown. */
    KRAFT(
        toothMm = 0.16f, toothAmp = 0.8f, bumpMm = 0f, bumpAmp = 0f,
        fibersPerMm2 = 0.7f, fiberMinMm = 0.8f, fiberMaxMm = 2.5f, fiberWidthMm = 0.06f,
        fiberHeight = 0.4f, fiberDarkness = 0.25f, allFibersDark = false, fiberBend = 0.8f,
        formationMm = 3.0f, formationDarkness = 0.030f, specksPerMm2 = 0.1f,
        baseDarkness = 0.060f, highlightTone = Rgb(0.92f, 0.80f, 0.62f), tint = Rgb(0.98f, 0.94f, 0.87f),
        relief = 1.0f,
    ),

    /** Giấy dó / washi: long, light, wavy bast fibers over cloudy flocs. */
    DO(
        toothMm = 0.20f, toothAmp = 0.6f, bumpMm = 0f, bumpAmp = 0f,
        fibersPerMm2 = 0.22f, fiberMinMm = 3.0f, fiberMaxMm = 10.0f, fiberWidthMm = 0.12f,
        fiberHeight = 0.35f, fiberDarkness = -0.35f, allFibersDark = true, fiberBend = 1.2f,
        formationMm = 4.0f, formationDarkness = 0.055f, specksPerMm2 = 0.05f,
        baseDarkness = 0.045f, highlightTone = Rgb(0.99f, 0.95f, 0.88f), tint = Rgb(1.0f, 0.98f, 0.94f),
        relief = 1.0f,
    ),
    ;

    companion object {
        val DEFAULT = PLAIN

        /** Stable lookup for persisted names; unknown values fall back to [DEFAULT]. */
        fun fromName(name: String?): PaperPreset = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Linear 0..1 colour. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    operator fun get(channel: Int): Float = when (channel) {
        0 -> r
        1 -> g
        else -> b
    }

    companion object {
        val WHITE = Rgb(1f, 1f, 1f)
    }
}
