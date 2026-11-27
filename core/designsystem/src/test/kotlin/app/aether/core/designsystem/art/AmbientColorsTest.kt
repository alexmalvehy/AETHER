package app.aether.core.designsystem.art

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The light behind Now Playing is the cover's own colour, and only ever that. */
class AmbientColorsTest {

    /** A [width] x [height] cover painted in horizontal bands of [colors], [shares] of its height each. */
    private fun cover(colors: List<Int>, shares: List<Float>, width: Int = 96, height: Int = 96): IntArray {
        val pixels = IntArray(width * height)
        var row = 0
        colors.forEachIndexed { i, color ->
            val rows = if (i == colors.lastIndex) height - row else (shares[i] * height).toInt()
            repeat(rows) { r -> for (x in 0 until width) pixels[(row + r) * width + x] = color }
            row += rows
        }
        return pixels
    }

    private fun hue(argb: Int) = Hsl.of(argb)[0]
    private fun saturation(argb: Int) = Hsl.of(argb)[1]
    private fun lightness(argb: Int) = Hsl.of(argb)[2]

    @Test
    fun hslIsAndroidsOwnArithmetic() {
        val red = Hsl.of(0xFFFF0000.toInt())
        assertEquals(0f, red[0], 0.01f)
        assertEquals(1f, red[1], 0.001f)
        assertEquals(0.5f, red[2], 0.001f)
        for (color in listOf(0xFFFF0000.toInt(), 0xFF2E3FBF.toInt(), 0xFF8E3FBF.toInt(), 0xFF808080.toInt(), 0xFF00FF7F.toInt())) {
            assertEquals(color, Hsl.toArgb(Hsl.of(color)))
        }
    }

    @Test
    fun aBlueAndPurpleCoverGivesBlueAndPurpleLight() {
        // Mostly a deep blue sky and a violet, a little pink: a TV Girl sleeve, roughly.
        val pixels = cover(listOf(0xFF2E3FBF.toInt(), 0xFF7A3FBF.toInt(), 0xFFE070B0.toInt()), listOf(0.5f, 0.35f, 0.15f))
        val light = AmbientColors.of(pixels, 96, 96).map { it.toArgb() }
        assertEquals(4, light.size)
        // Every light is the cover's own: blue, violet or pink, never anything else.
        light.forEach { assertTrue("hue ${hue(it)}", hue(it) in 200f..345f) }
        // The cover's most of: blue first.
        assertTrue(hue(light.first()) in 215f..245f)
        // Rich, and neither dark nor bright.
        light.forEach { assertTrue(lightness(it) in 0.279f..0.581f) }
    }

    @Test
    fun aBlackAndWhiteCoverGivesGreyLightNotAnInventedColour() {
        val pixels = cover(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF777777.toInt()), listOf(0.6f, 0.3f, 0.1f))
        val light = AmbientColors.of(pixels, 96, 96).map { it.toArgb() }
        assertTrue(light.isNotEmpty())
        light.forEach { assertEquals("saturation of ${Integer.toHexString(it)}", 0f, saturation(it), 0.02f) }
    }

    @Test
    fun theGroundIsTheFirstColourDimmed() {
        val first = 0xFF2E3FBF.toInt()
        val ground = AmbientColors.dimmed(androidx.compose.ui.graphics.Color(first)).toArgb()
        assertEquals(0.12f, lightness(ground), 0.01f)
        assertEquals(hue(first), hue(ground), 2f)
        assertEquals(saturation(first), saturation(ground), 0.03f)
    }

    @Test
    fun nothingReadIsNoLight() {
        assertTrue(AmbientColors.of(IntArray(0), 0, 0).isEmpty())
    }
}
