package app.aether.core.designsystem.motion

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Where things are on screen, found again every frame by their name. An element marked with
 * [anchor] is entered here while it's laid out and taken out when it leaves, so a list row that is
 * scrolled away and made again later is found again under the same name: a flight never holds on to
 * the place it started from, it asks where that place is now.
 *
 * Main thread only, like layout itself.
 */
@Stable
class Anchors {
    private val placed = HashMap<Any, LayoutCoordinates>()

    internal fun enter(key: Any, at: LayoutCoordinates) {
        placed[key] = at
    }

    internal fun leave(key: Any, at: LayoutCoordinates?) {
        // Only its own entry: a newer element under the same name may already have taken the place.
        if (at == null || placed[key] === at) placed.remove(key)
    }

    /** The element named [key], if it's laid out now. */
    fun coordinates(key: Any): LayoutCoordinates? = placed[key]?.takeIf { it.isAttached }

    /**
     * Where [key] is this frame, in [host]'s coordinates, with every transform on the way (a page
     * scaled behind another, a row scrolled): null when it isn't laid out.
     */
    fun bounds(key: Any, host: LayoutCoordinates): Rect? {
        val at = coordinates(key) ?: return null
        if (!host.isAttached) return null
        return runCatching { host.localBoundingBoxOf(at, clipBounds = false) }.getOrNull()
    }
}

/** Enters this element in [anchors] under [key] for as long as it's laid out. */
fun Modifier.anchor(anchors: Anchors, key: Any): Modifier = this then AnchorElement(anchors, key)

private data class AnchorElement(val anchors: Anchors, val key: Any) : ModifierNodeElement<AnchorNode>() {
    override fun create() = AnchorNode(anchors, key)

    override fun update(node: AnchorNode) {
        if (node.anchors !== anchors || node.key != key) {
            node.anchors.leave(node.key, node.at)
            node.anchors = anchors
            node.key = key
            node.at?.let { anchors.enter(key, it) }
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "anchor"
        properties["key"] = key
    }
}

private class AnchorNode(var anchors: Anchors, var key: Any) : Modifier.Node(), LayoutAwareModifierNode {
    var at: LayoutCoordinates? = null

    override fun onPlaced(coordinates: LayoutCoordinates) {
        at = coordinates
        anchors.enter(key, coordinates)
    }

    override fun onDetach() {
        anchors.leave(key, at)
        at = null
    }
}
