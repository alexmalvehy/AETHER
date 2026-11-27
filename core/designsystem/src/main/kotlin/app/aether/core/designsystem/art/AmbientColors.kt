// After BitChord's MeshGradient (sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0): its four colours from a cover (`paletteOf`, `distinctEnough`,
// `expandedToFour`, `tuned`, `dimmed`), unchanged but for one thing: a cover with no colour in it gets none. BitChord
// tops such a cover up with its own stock purples; AETHER's light is only ever the music's own colour.
package app.aether.core.designsystem.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.min

/**
 * Four colours drawn from a cover, for the light behind Now Playing.
 *
 * The named swatches (vibrant, muted and friends) come back empty on dark or desaturated sleeves, so
 * the whole swatch list is read instead, by population, and any shortfall is made from the art's
 * own colours, its hue and lightness fanned out. Each is then made a little richer and kept from
 * being too dark or too bright, so any cover gives light rather than mud.
 */
object AmbientColors {
    /** The four lights' colours, or none at all for a cover with nothing in it (or none read yet). */
    fun of(bitmap: ImageBitmap): List<Color> {
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it, 0, 0, bitmap.width, bitmap.height) }
        return of(pixels, bitmap.width, bitmap.height)
    }

    /** The same, from a cover's ARGB pixels, row by row. */
    fun of(pixels: IntArray, width: Int, height: Int): List<Color> {
        fun swatchesOf(clearFilters: Boolean): List<Color> =
            PaletteQuantizer.swatches(pixels, width, height, 24, clearFilters)
                .sortedByDescending { it.population }
                .map { Color(it.rgb) }

        val found = swatchesOf(clearFilters = false).ifEmpty {
            // The default filter discards near-black and near-white, which on a
            // monochrome sleeve can be everything there is.
            swatchesOf(clearFilters = true)
        }

        val distinct = found.distinctEnough()
        val four = when {
            distinct.isEmpty() -> return emptyList()
            distinct.size >= 4 -> distinct.take(4)
            else -> distinct.expandedToFour()
        }
        return four.map { it.tuned() }
    }

    /** Drop near-duplicates, so the four lights don't collapse into one wash. */
    private fun List<Color>.distinctEnough(): List<Color> {
        val kept = mutableListOf<Color>()
        forEach { color -> if (kept.none { it.isCloseTo(color) }) kept += color }
        return kept
    }

    private fun Color.isCloseTo(other: Color): Boolean {
        val a = hsl()
        val b = other.hsl()
        val hueGap = abs(a[0] - b[0]).let { min(it, 360f - it) }
        return hueGap < 15f && abs(a[2] - b[2]) < 0.12f
    }

    /** Fill the empty slots off the art itself, fanning hue and lightness out. */
    private fun List<Color>.expandedToFour(): List<Color> {
        val out = toMutableList()
        var step = 1
        while (out.size < 4) {
            out += this[(out.size - size) % size].shifted(24f * step, 0.12f * step)
            step++
        }
        return out
    }

    private fun Color.shifted(hue: Float, lightness: Float): Color {
        val hsl = hsl()
        hsl[0] = (hsl[0] + hue) % 360f
        hsl[2] = (hsl[2] + lightness).coerceIn(0.2f, 0.7f)
        return Color(Hsl.toArgb(hsl))
    }

    private fun Color.hsl(): FloatArray =
        Hsl.of(toArgb())

    /** The ground under the lights: [color] at lightness 0.12, its hue and saturation kept. */
    fun dimmed(color: Color): Color {
        val hsl = Hsl.of(color.toArgb())
        hsl[2] = 0.12f
        return Color(Hsl.toArgb(hsl))
    }

    /** Boost saturation and clamp lightness so any artwork yields a rich, non-muddy light. */
    private fun Color.tuned(): Color {
        val hsl = Hsl.of(toArgb())
        hsl[1] = (hsl[1] * 1.35f).coerceAtMost(1f)
        hsl[2] = hsl[2].coerceIn(0.28f, 0.58f)
        return Color(Hsl.toArgb(hsl))
    }
}
