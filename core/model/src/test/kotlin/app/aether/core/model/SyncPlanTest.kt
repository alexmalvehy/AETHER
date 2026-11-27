package app.aether.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist kept in step with its copy in the listener's account: what goes each way. */
class SyncPlanTest {

    @Test
    fun theFirstTimeBothSidesAreJoinedAndNothingIsTakenAway() {
        val plan = SyncPlan.of(base = emptySet(), here = listOf("a", "b"), there = listOf("b", "c"))
        assertEquals(listOf("a"), plan.push)
        assertEquals(listOf("c"), plan.pull)
        assertTrue(plan.unpush.isEmpty() && plan.unpull.isEmpty())
        assertEquals(setOf("a", "b", "c"), plan.base())
    }

    @Test
    fun aSongTakenOutHereIsTakenOutThere() {
        val plan = SyncPlan.of(base = setOf("a", "b"), here = listOf("a"), there = listOf("a", "b"))
        assertEquals(listOf("b"), plan.unpush)
        assertTrue(plan.push.isEmpty() && plan.pull.isEmpty() && plan.unpull.isEmpty())
        assertEquals(setOf("a"), plan.base())
    }

    @Test
    fun aSongTakenOutThereIsTakenOutHere() {
        val plan = SyncPlan.of(base = setOf("a", "b"), here = listOf("a", "b"), there = listOf("a"))
        assertEquals(listOf("b"), plan.unpull)
        assertTrue(plan.push.isEmpty() && plan.pull.isEmpty() && plan.unpush.isEmpty())
        assertEquals(setOf("a"), plan.base())
    }

    @Test
    fun songsAddedOnEachSideGoToTheOther() {
        val plan = SyncPlan.of(base = setOf("a"), here = listOf("a", "b"), there = listOf("c", "a"))
        assertEquals(listOf("b"), plan.push)
        assertEquals(listOf("c"), plan.pull)
        assertEquals(setOf("a", "b", "c"), plan.base())
    }

    @Test
    fun aSongGoneFromBothIsForgotten() {
        val plan = SyncPlan.of(base = setOf("a", "b"), here = listOf("a"), there = listOf("a"))
        assertTrue(plan.nothing)
        assertEquals(setOf("a"), plan.base())
    }

    @Test
    fun songsGoInTheOrderTheirOwnSideHasThem() {
        val plan = SyncPlan.of(base = emptySet(), here = listOf("z", "y", "x"), there = listOf("c", "b", "a"))
        assertEquals(listOf("z", "y", "x"), plan.push)
        assertEquals(listOf("c", "b", "a"), plan.pull)
    }

    @Test
    fun aSongTwiceInAListCountsOnce() {
        val plan = SyncPlan.of(base = emptySet(), here = listOf("a", "a", "b"), there = listOf("b", "b"))
        assertEquals(listOf("a"), plan.push)
        assertTrue(plan.pull.isEmpty())
    }

    @Test
    fun aSongTheAccountWouldNotTakeIsNotOfferedAgain() {
        val plan = SyncPlan.of(base = emptySet(), here = listOf("a", "x"), there = listOf("a"), refused = setOf("x"))
        assertTrue(plan.push.isEmpty())
        // It stays here, and isn't counted as on both sides.
        assertEquals(setOf("a"), plan.base())
    }

    @Test
    fun aStepThatDidNotTakeIsTriedAgainNextTime() {
        val plan = SyncPlan.of(base = setOf("gone-here", "gone-there"), here = listOf("new-here", "gone-there"), there = listOf("new-there", "gone-here"))
        assertEquals(listOf("new-here"), plan.push)
        assertEquals(listOf("new-there"), plan.pull)
        assertEquals(listOf("gone-here"), plan.unpush)
        assertEquals(listOf("gone-there"), plan.unpull)
        // Nothing took: what both held stays as it was for the removals, and the additions aren't in it.
        val after = plan.base(pushed = emptySet(), unpushed = emptySet(), pulled = emptySet(), unpulled = emptySet())
        assertEquals(setOf("gone-here", "gone-there"), after)
        // So the same steps come up again.
        val again = SyncPlan.of(after, here = listOf("new-here", "gone-there"), there = listOf("new-there", "gone-here"))
        assertEquals(plan.push, again.push)
        assertEquals(plan.pull, again.pull)
        assertEquals(plan.unpush, again.unpush)
        assertEquals(plan.unpull, again.unpull)
    }

    @Test
    fun everythingTookAndTheNextTimeThereIsNothingToDo() {
        val plan = SyncPlan.of(base = setOf("a", "b", "c"), here = listOf("a", "b", "d"), there = listOf("a", "c", "e"))
        val after = plan.base()
        val here = listOf("a", "d", "e")
        val there = listOf("a", "e", "d")
        assertTrue(SyncPlan.of(after, here, there).nothing)
    }

    @Test
    fun aNameChangedHereGoesThereAndOneChangedThereComesHere() {
        assertEquals(SyncPlan.Name.Push("Late nights"), SyncPlan.name(base = "Nights", here = "Late nights", there = "Nights"))
        assertEquals(SyncPlan.Name.Pull("Late nights"), SyncPlan.name(base = "Nights", here = "Nights", there = "Late nights"))
        assertEquals(SyncPlan.Name.Same, SyncPlan.name(base = "Nights", here = "Nights", there = "Nights"))
        // Changed on both sides while apart: this phone's is kept.
        assertEquals(SyncPlan.Name.Push("Mine"), SyncPlan.name(base = "Nights", here = "Mine", there = "Theirs"))
        // Never in step yet: this phone's.
        assertEquals(SyncPlan.Name.Push("Mine"), SyncPlan.name(base = null, here = "Mine", there = "Theirs"))
    }
}
