package app.aether.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.toSize
import app.aether.core.designsystem.component.DragState
import app.aether.core.designsystem.motion.Anchors
import app.aether.core.designsystem.motion.Becoming
import kotlin.math.max
import kotlin.math.min

/** Where the mini player is, and its cover, in the shell's [Anchors]. */
const val PillKey = "pill"
const val PillArtKey = "pill:art"

/**
 * The mini player growing into Now Playing, and back (the design of 18 November). The pill itself is
 * the window: one rounded rectangle whose edges go from the pill's bounds, read live every frame (the
 * dock folds and unfolds under it), to the screen's, and whose corners go from the pill's to the
 * screen's own. [player]'s value is how far: 0 the pill, 1 Now Playing, the way back the same path.
 */
@Stable
class PlayerWindow(val player: DragState, private val anchors: Anchors) {
    /** Now Playing's own box, the whole screen: what the window is measured in. */
    internal var host: LayoutCoordinates? = null

    /** The screen's corner radius, in pixels. */
    internal var screenRadius = 0f

    /** Where the pill stands with the dock open: closed into, and faded, when the pill can't be found. */
    internal var restingPill = Rect.Zero

    /** This frame: the window, the pill, the pill's cover. Null until Now Playing has been laid out. */
    fun frame(): PlayerFrame? {
        val host = host?.takeIf { it.isAttached } ?: return null
        val screen = Rect(Offset.Zero, host.size.toSize())
        val live = anchors.bounds(PillKey, host)
        val pill = live ?: restingPill.takeIf { it.width > 0f } ?: return null
        val p = player.value
        val window = Becoming.lerp(pill, screen, p.coerceAtMost(1f))
        val radius = Becoming.lerp(pill.height / 2f, screenRadius, p.coerceIn(0f, 1f))
        return PlayerFrame(p, screen, pill, window, radius, anchors.bounds(PillArtKey, host), found = live != null)
    }

    /** How far the window's top edge travels: a pull keeps that edge under the finger. */
    fun travel(): Float = frame()?.pill?.top?.takeIf { it > 1f } ?: 0f
}

/** One frame of the window. [found]: the pill is on screen to land on. */
class PlayerFrame(
    val p: Float,
    val screen: Rect,
    val pill: Rect,
    val window: Rect,
    val radius: Float,
    val art: Rect?,
    val found: Boolean,
)

/**
 * How Now Playing stands at [p] of the way from the pill (0) to Now Playing (1): the Dynamic Island's
 * way. The pill itself grows: its own dark glass, deepening into Now Playing's ground, its lit rim
 * along its edge until it's the screen. What's written on the pill goes in the first moment; its cover
 * flies to be the print; Now Playing's own parts come in once there's room, fading up from a little
 * smaller, inside the island and never outside it. On springs with Apple's bounce, so the print lands
 * with a little give, and closing, the pill takes the landing and settles ([squash]). All of it is 1
 * (or gone) at rest, so Now Playing at rest is as it always was.
 */
object Growth {
    /** The pill's surface: its glass's tint (the Control weight's) to begin with. */
    const val PillTint = 0.4f

    /** The pill's song, artist and keys, riding its top edge: gone by 12%. */
    fun face(p: Float) = 1f - Becoming.ramp(p, 0f, 0.12f)

    /** The island's surface: the pill's tint, deepening into Now Playing's ground by 30%. */
    fun ground(p: Float) = PillTint + (1f - PillTint) * Becoming.ramp(p, 0f, 0.3f)

    /** The lit rim along the island's edge: there while it's an island, gone as it becomes the screen. */
    fun rim(p: Float) = 1f - Becoming.ramp(p, 0.5f, 0.92f)

    /** Now Playing's own parts, coming in once the island has room for them: 30% to 80%. */
    fun bloom(p: Float) = Becoming.ramp(p, 0.3f, 0.8f)

    /** ...from a little smaller than they stand. */
    fun bloomScale(p: Float) = 0.94f + 0.06f * bloom(p)

    /** The pill's own cover, over the print flying out of it: handed over by 8%, while it's still small. */
    fun pillCover(p: Float) = 1f - Becoming.ramp(p, 0f, 0.08f)

    /** The tab bar under the pill, as the island grows over it. */
    fun dock(p: Float) = 1f - Becoming.ramp(p, 0.05f, 0.3f)

    /** The song's film: once the print has landed. */
    fun film(p: Float) = Becoming.ramp(p, 0.8f, 1f)

    /** What stands on the print without flying with it (Automix's dots): only once it has landed. */
    fun landed(p: Float) = Becoming.ramp(p, 0.97f, 1f)

    /** Closing, the spring carries a little past the pill: the pill takes it, a few percent smaller, and settles. */
    fun squash(p: Float) = 1f + min(0f, p) * 1.2f
}

/**
 * The print in flight between the pill's cover ([art]) and its place ([print]), [p] of the way: drawn
 * as it stands, scaled about its middle by [scale] and moved by ([dx], [dy]), and cut to [clip] with
 * corners of [radius], both in its own unscaled units. At the pill it's the print's middle, round, the
 * size of the pill's cover; at rest it's all of it, where it stands, with its own corners ([corner]).
 * The print itself flies, so it lands as itself: nothing is handed over at the end.
 */
data class PrintFlight(val scale: Float, val dx: Float, val dy: Float, val clip: Rect, val radius: Float) {
    companion object {
        fun at(art: Rect, print: Rect, p: Float, corner: Float): PrintFlight {
            val r = Becoming.lerp(art, print, p)
            val s = max(r.width / print.width, r.height / print.height).coerceAtLeast(0.0001f)
            val w = (r.width / s).coerceAtMost(print.width)
            val h = (r.height / s).coerceAtMost(print.height)
            val cx = print.width / 2f
            val cy = print.height / 2f
            return PrintFlight(
                scale = s,
                dx = r.center.x - print.center.x,
                dy = r.center.y - print.center.y,
                clip = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f),
                radius = Becoming.lerp(art.height / 2f, corner, p.coerceIn(0f, 1f)) / s,
            )
        }
    }
}
