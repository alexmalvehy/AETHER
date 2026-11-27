package app.aether.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.aether.core.designsystem.art.PullRipple
import app.aether.core.designsystem.component.Haptics
import app.aether.core.designsystem.theme.FluidSpring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val DrawIn = FluidSpring(0.2f, 1f)
private val LetGo = FluidSpring(0.55f, 0.62f)
private val Fades = FluidSpring(0.9f, 1f)

/**
 * The music going out to a TV, as a song comes into the phone (KeepFlight's arrival) turned the other
 * way: a bright mouth opens at the middle of the screen's top edge, everything is drawn up after it and
 * springs back, and one wave runs down through the screen, as AirDrop's and NameDrop's do. The TV takes
 * it up from the foot of its screen (site/cast/cast.js). The same wave as a download's, the same
 * numbers, on a ripple of its own so the two never cross. Nothing at all with motion reduced.
 */
@Stable
class CastHandoff(private val scope: CoroutineScope, private val haptics: Haptics) {
    val ripple = PullRipple()

    fun play(screen: Size, density: Float, reduce: Boolean) {
        if (reduce || screen.width <= 0f || screen.height <= 0f || ripple.waving) return
        scope.launch {
            val mouth = Offset(screen.width / 2f, 0f)
            ripple.aim(mouth)
            haptics.strong()
            launch {
                ripple.pull.to(1f, DrawIn)
                ripple.pull.to(0f, LetGo)
            }
            launch {
                ripple.glow.to(1f, DrawIn)
                ripple.glow.to(0f, Fades)
            }
            launch { ripple.wave(mouth, 30f * density, 14f, 2.9f, screen.height * 1.05f, 0.6f, 1.7f) }
        }
    }
}
