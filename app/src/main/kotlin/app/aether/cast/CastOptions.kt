// Ported from BitChord (app/.../playback/cast/CastOptionsProvider.kt, kushagrasinghx/BitChord; GPL-3.0).
package app.aether.cast

import android.content.Context
import app.aether.R
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * How the Cast framework is told AETHER casts. Named in the manifest and read by the framework itself.
 *
 * The receiver is AETHER's own (site/cast/) once it's registered and its id given to the build
 * (`aether.castReceiverId`), and Google's standard one until then: either plays what it's handed.
 * The framework's own notification and media session are both off: the engine already has a session
 * and a notification, which follow the TV instead (BitChord's choice: a second pair, each with its own
 * idea of what's playing, is what casting looks like bolted on).
 */
class CastOptions : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .setMediaSessionEnabled(false)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(receiverId(context))
            .setCastMediaOptions(media)
            // A TV left running after the phone let go of it stays on a "casting" screen with nothing
            // driving it.
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null

    companion object {
        /** Google's standard receiver: it plays the music on a plain screen of its own. */
        const val StandardReceiver = "CC1AD845"

        fun receiverId(context: Context): String = context.getString(R.string.aether_cast_receiver).trim().ifEmpty { StandardReceiver }

        /** Whether the TV runs AETHER's own receiver, which shows the stage (the film, the dial, the words). */
        fun ownReceiver(context: Context): Boolean = receiverId(context) != StandardReceiver
    }
}
