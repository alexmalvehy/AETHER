package app.aether.core.designsystem.art

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import app.aether.core.designsystem.theme.Glide

/*
 * The screen as a sheet of something that gives. Each point is pushed along the line from the origin
 * by a damped wave that reaches it later the further away it is (the ripple Apple showed for SwiftUI
 * at WWDC 2024, and the way AirDrop's screen moves), and the wave's crest catches the light. [pull]
 * draws the whole sheet toward the origin as well, most strongly near it.
 */
private const val PULL_RIPPLE = """
    uniform shader content;
    uniform float2 size;
    uniform float2 origin;
    uniform float time;
    uniform float amplitude;
    uniform float frequency;
    uniform float decay;
    uniform float speed;
    uniform float pull;
    uniform float glow;
    uniform float shine;

    // How far a point gives way toward an edge [room] away: as far as it's pushed while there's room,
    // then less and less (tanh), never past it.
    float pinned(float v, float room) {
        float r = max(room, 0.001);
        float e = exp(-2.0 * abs(v) / r);
        return sign(v) * r * (1.0 - e) / (1.0 + e);
    }

    half4 main(float2 p) {
        float2 d = p - origin;
        float dist = length(d);
        float2 n = dist > 0.5 ? d / dist : float2(0.0);
        float t = max(time - dist / speed, 0.0);
        float envelope = exp(-decay * t);
        float swing = sin(frequency * t);
        float reach = exp(-dist / (size.y * 0.5));
        float2 v = n * (amplitude * swing * envelope + pull * size.y * 0.1 * reach);
        // The sheet is held at the screen's edges, as the screen itself would be: it stretches toward
        // them instead of coming away from them, so no gap ever opens onto what's beneath it.
        float2 room = float2(v.x < 0.0 ? p.x : size.x - p.x, v.y < 0.0 ? p.y : size.y - p.y);
        float2 from = clamp(p + float2(pinned(v.x, room.x), pinned(v.y, room.y)), float2(0.5), size - float2(0.5));
        half4 c = content.eval(from);
        // Only the very top of the wave catches the light, so it's a rim and not a wash.
        float crest = amplitude > 0.0 ? pow(max(swing, 0.0), 5.0) * envelope : 0.0;
        float mouth = glow * exp(-(dist * dist) / (size.x * size.x * 0.18));
        float light = clamp(crest * shine + mouth * 0.85, 0.0, 1.0);
        c.rgb += half3(light) * (1.0 - c.rgb * 0.6) * c.a;
        return c;
    }
"""

/** One wave and one pull across the screen. Costs nothing at all while nothing is moving. */
@Stable
class PullRipple {
    /** A wave is on its way across. */
    var waving by mutableStateOf(false)
        private set
    private val time = mutableFloatStateOf(0f)
    private var origin = Offset.Zero
    private var amplitude = 0f
    private var frequency = 0f
    private var decay = 0f
    private var speed = 1f
    private var shine = 0f
    private var warming = false

    /** 0 at rest, 1 drawn in as far as it goes. */
    val pull = Glide(0f)

    /** The light where the wave begins, 0 to 1. */
    val glow = Glide(0f)

    /** Stays on, for drawing once out of sight: the first real wave then finds its shader compiled. */
    fun warm() {
        warming = true
    }

    /** The origin of a [pull] or [glow] with no wave running. */
    fun aim(at: Offset) {
        origin = at
    }

    /**
     * Sends a wave out from [at]: [amplitude] pixels at its strongest, swinging at [frequency] radians a
     * second, dying away at [decay], travelling at [speed] pixels a second, its crest as bright as
     * [shine], for [seconds]. A damped
     * oscillation run by elapsed time: at most a thirtieth of a second a frame, so a long frame holds
     * the wave rather than skipping part of it.
     */
    suspend fun wave(at: Offset, amplitude: Float, frequency: Float, decay: Float, speed: Float, shine: Float, seconds: Float) {
        origin = at
        this.shine = shine
        this.amplitude = amplitude
        this.frequency = frequency
        this.decay = decay
        this.speed = speed
        time.floatValue = 0f
        waving = true
        try {
            var elapsed = 0f
            var last = -1L
            while (elapsed < seconds) {
                val now = withFrameNanos { it }
                if (last >= 0L) elapsed += ((now - last) / 1e9f).coerceAtMost(1f / 30f)
                last = now
                time.floatValue = elapsed
            }
        } finally {
            waving = false
            this.amplitude = 0f
        }
    }

    internal val drawing: Boolean get() = warming || waving || pull.value > 0.001f || glow.value > 0.001f

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun apply(shader: RuntimeShader, width: Float, height: Float) {
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("origin", origin.x, origin.y)
        shader.setFloatUniform("time", time.floatValue)
        shader.setFloatUniform("amplitude", if (waving) amplitude else 0f)
        shader.setFloatUniform("frequency", frequency)
        shader.setFloatUniform("decay", decay)
        shader.setFloatUniform("speed", speed)
        shader.setFloatUniform("pull", pull.value)
        shader.setFloatUniform("glow", glow.value)
        shader.setFloatUniform("shine", shine)
    }
}

/** For drawing a [PullRipple] once before it's needed, from where a modifier can't be composed inline. */
object PullRippleWarm {
    @Composable
    fun of(state: PullRipple): Modifier = Modifier.pullRipple(state).background(Color.Black)
}

/** Lays [state] over everything drawn here. With nothing moving it's no effect at all. */
@Composable
fun Modifier.pullRipple(state: PullRipple): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val shader = remember { RuntimeShader(PULL_RIPPLE) }
    return this.graphicsLayer {
        if (!state.drawing) {
            renderEffect = null
            return@graphicsLayer
        }
        state.apply(shader, size.width, size.height)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
