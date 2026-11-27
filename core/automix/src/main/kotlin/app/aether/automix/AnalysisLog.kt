// AETHER: BitChord's Automix (shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/AnalysisLog.kt, github.com/kushagrasinghx/BitChord),
// ported with its notices below; only its package and its ties to BitChord's app are changed.
package app.aether.automix

/** Where the analyser's warnings go, without it having to know the platform. */
object AnalysisLog {

    private var sink: ((String, Throwable?) -> Unit)? = null

    fun install(sink: (String, Throwable?) -> Unit) {
        this.sink = sink
    }

    fun warn(message: String, error: Throwable? = null) {
        sink?.invoke(message, error)
    }
}
