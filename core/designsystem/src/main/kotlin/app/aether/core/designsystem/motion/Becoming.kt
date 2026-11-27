package app.aether.core.designsystem.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/*
 * One thing becoming another, the way iOS opens an app from its icon and closes it back into
 * wherever the icon is now. Both ends are read live, every frame, and one progress (a spring, or a
 * finger) carries the element between them: 0 is where it came from, 1 is where it's going, and the
 * way back is the same path. These are the pure rules; [Anchors] finds the ends on screen.
 */
object Becoming {
    /** The rectangle [t] of the way from [a] to [b]; past either end, it carries on along the same line. */
    fun lerp(a: Rect, b: Rect, t: Float): Rect = Rect(
        a.left + (b.left - a.left) * t,
        a.top + (b.top - a.top) * t,
        a.right + (b.right - a.right) * t,
        a.bottom + (b.bottom - a.bottom) * t,
    )

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** 0 below [from], 1 above [to], an even S between (smoothstep). */
    fun ramp(x: Float, from: Float, to: Float): Float {
        if (to == from) return if (x >= to) 1f else 0f
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * iOS's window: a page of [page] size shown inside [window], scaled evenly to the window's width
     * and cut off at its height, with its corners rounded [radius] on screen. All in the page's own
     * units, so it can be handed to a layer whose transform origin is its top left.
     */
    fun window(window: Rect, page: Size, radius: Float): WindowTransform {
        val scale = if (page.width > 0f) (window.width / page.width) else 1f
        val safe = scale.coerceAtLeast(0.0001f)
        return WindowTransform(
            scale = scale,
            x = window.left,
            y = window.top,
            clipHeight = (window.height / safe).coerceAtLeast(0f),
            radius = radius / safe,
        )
    }

    /**
     * Where something flying back to a place lands. [live] is where the place is this frame (null
     * once it's gone: scrolled out of the list's memory, or taken away); [lastSeen] where it was last
     * seen; [screen] the visible area; [from] where the flying thing is now.
     *
     * A place on screen, or a little off it, is landed in exactly. A place further off, past an edge,
     * is landed in just past that edge: the thing leaves by the edge its place went, at a sensible
     * speed, rather than chasing it a screen away. A place never seen is a shrink and a fade where the
     * flying thing already is (iOS, for an icon that isn't on the page showing).
     */
    fun landing(live: Rect?, lastSeen: Rect?, screen: Rect, from: Rect): Landing {
        val place = live ?: lastSeen
            ?: return Landing(shrinkInPlace(from), Landing.Kind.Vanish)
        if (place.overlaps(screen) || place == screen) {
            return Landing(place, if (live != null) Landing.Kind.Slot else Landing.Kind.Edge)
        }
        // Past an edge: held just beyond it, along the same side, at the place's own size.
        val margin = minOf(place.height, place.width) * 0.15f
        val dy = when {
            place.bottom <= screen.top -> (screen.top - margin - place.bottom).coerceAtLeast(0f)
            place.top >= screen.bottom -> (screen.bottom + margin - place.top).coerceAtMost(0f)
            else -> 0f
        }
        val dx = when {
            place.right <= screen.left -> (screen.left - margin - place.right).coerceAtLeast(0f)
            place.left >= screen.right -> (screen.right + margin - place.left).coerceAtMost(0f)
            else -> 0f
        }
        return Landing(place.translate(Offset(dx, dy)), Landing.Kind.Edge)
    }

    /** Half the size, about its own centre. */
    fun shrinkInPlace(r: Rect, by: Float = 0.5f): Rect {
        val c = r.center
        val w = r.width * by / 2f
        val h = r.height * by / 2f
        return Rect(c.x - w, c.y - h, c.x + w, c.y + h)
    }
}

/** A page drawn as a window: scaled about its top left, placed at ([x], [y]), cut at [clipHeight], corners [radius] (page units). */
data class WindowTransform(val scale: Float, val x: Float, val y: Float, val clipHeight: Float, val radius: Float)

/** Where a flight back ends, and whether the place itself is there to show ([Kind.Slot]). */
data class Landing(val rect: Rect, val kind: Kind) {
    enum class Kind {
        /** The place, live: shown in the frame the flight lands. */
        Slot,

        /** Past the edge the place went off by: nothing to show, the flight leaves the screen. */
        Edge,

        /** Never seen: the flight shrinks and fades where it is. */
        Vanish,
    }
}
