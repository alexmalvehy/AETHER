package app.aether

import android.content.Intent

/** Release builds: the live checks are debug builds' alone (src/debug). */
object SyncCheck {
    @Suppress("UNUSED_PARAMETER")
    fun handle(intent: Intent?, container: Container, context: android.content.Context) = Unit
}
