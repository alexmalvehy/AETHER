// AETHER: BitChord's Automix (shared/src/commonMain/kotlin/com/music/bitchord/data/settings/AutomixPerformanceMode.kt, github.com/kushagrasinghx/BitChord),
// ported with its notices below; only its package and its ties to BitChord's app are changed.
package app.aether.automix

/** CPU budget for Automix's background analysis, not its audible mix algorithm. */
enum class AutomixPerformanceMode(val inferenceThreads: Int) {
    EFFICIENT(1),
    BALANCED(2),
    PERFORMANCE(4),
    ;

    /** Whether analysis should yield to playback rather than compete with it. */
    val yieldsToPlayback: Boolean get() = this == EFFICIENT
}
