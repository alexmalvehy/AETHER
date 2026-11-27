package app.aether.core.model

/**
 * A list on this phone kept in step with its copy in the listener's account: what goes each way, worked
 * out from the two lists as they are now and what both held when they last agreed ([of]'s base).
 *
 * What either side has gained since is given to the other, and what either has let go is taken from the
 * other: a song on both stays, one gone from both is forgotten. With nothing agreed yet (the first time),
 * the two are joined and nothing is taken from either. Songs are compared by id, each once however often
 * a list holds it; they go in the order their own side has them.
 *
 * A step that doesn't take is tried again next time: [base] counts an addition only once it's made, and
 * keeps a removal that wasn't, so the next plan finds the same difference and makes the same step.
 */
data class SyncPlan(
    /** On this phone, to be added there (this phone's order). */
    val push: List<String>,
    /** Taken out here since they agreed, to be taken out there. */
    val unpush: List<String>,
    /** There, to be added here (the account's order). */
    val pull: List<String>,
    /** Taken out there since they agreed, to be taken out here. */
    val unpull: List<String>,
    /** On both sides already. */
    val agreed: Set<String>,
) {
    val nothing: Boolean get() = push.isEmpty() && unpush.isEmpty() && pull.isEmpty() && unpull.isEmpty()

    /**
     * What both hold once the plan has been carried out, given which of its steps took. Every step,
     * unless told otherwise.
     */
    fun base(
        pushed: Set<String> = push.toSet(),
        unpushed: Set<String> = unpush.toSet(),
        pulled: Set<String> = pull.toSet(),
        unpulled: Set<String> = unpull.toSet(),
    ): Set<String> = agreed + pushed + pulled + (unpush.toSet() - unpushed) + (unpull.toSet() - unpulled)

    /** What a playlist's name does: goes there, comes here, or stays as both have it. */
    sealed interface Name {
        data object Same : Name
        data class Push(val name: String) : Name
        data class Pull(val name: String) : Name
    }

    companion object {
        /**
         * The plan for [here] and [there] (songs' ids, in each side's order) from what both held when they
         * last agreed. [refused] are songs the account wouldn't take when offered: they stay on this phone
         * only, and aren't offered again.
         */
        fun of(base: Set<String>, here: List<String>, there: List<String>, refused: Set<String> = emptySet()): SyncPlan {
            val mine = here.toCollection(LinkedHashSet())
            val theirs = there.toCollection(LinkedHashSet())
            return SyncPlan(
                push = mine.filter { it !in theirs && it !in base && it !in refused },
                unpush = theirs.filter { it !in mine && it in base },
                pull = theirs.filter { it !in mine && it !in base },
                unpull = mine.filter { it !in theirs && it in base },
                agreed = mine.filterTo(LinkedHashSet()) { it in theirs },
            )
        }

        /** The name each side should have: a change on one side goes to the other; changed on both, this phone's. */
        fun name(base: String?, here: String, there: String): Name = when {
            here == there -> Name.Same
            base == null || here != base -> Name.Push(here)
            else -> Name.Pull(there)
        }
    }
}
