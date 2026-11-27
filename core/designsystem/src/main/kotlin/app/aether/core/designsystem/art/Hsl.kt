package app.aether.core.designsystem.art

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * androidx `ColorUtils.colorToHSL` and `HSLToColor`, step for step, without `android.graphics.Color`
 * under them (as BitChord keeps its own for the same reason): the same answers, in plain Kotlin, so the
 * colours of a cover can be worked out and tested anywhere.
 */
internal object Hsl {
    /** Hue in degrees, saturation and lightness 0..1, of an ARGB colour. */
    fun of(argb: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val rf = ((argb shr 16) and 0xFF) / 255f
        val gf = ((argb shr 8) and 0xFF) / 255f
        val bf = (argb and 0xFF) / 255f
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC
        var h: Float
        val s: Float
        val l = (maxC + minC) / 2f
        if (maxC == minC) {
            h = 0f
            s = 0f
        } else {
            h = when (maxC) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> ((bf - rf) / delta) + 2f
                else -> ((rf - gf) / delta) + 4f
            }
            s = delta / (1f - abs(2f * l - 1f))
        }
        h = (h * 60f) % 360f
        if (h < 0) h += 360f
        out[0] = h.coerceIn(0f, 360f)
        out[1] = s.coerceIn(0f, 1f)
        out[2] = l.coerceIn(0f, 1f)
        return out
    }

    /** The opaque ARGB colour of hue, saturation and lightness [hsl]. */
    fun toArgb(hsl: FloatArray): Int {
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]
        val c = (1f - abs(2 * l - 1f)) * s
        val m = l - 0.5f * c
        val x = c * (1f - abs((h / 60f % 2f) - 1f))
        val (r, g, b) = when (h.toInt() / 60) {
            0 -> Triple(c + m, x + m, m)
            1 -> Triple(x + m, c + m, m)
            2 -> Triple(m, c + m, x + m)
            3 -> Triple(m, x + m, c + m)
            4 -> Triple(x + m, m, c + m)
            else -> Triple(c + m, m, x + m)
        }
        fun channel(v: Float) = (255 * v).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
