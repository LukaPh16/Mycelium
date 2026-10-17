package app.mycelium.engine.vpn

import app.mycelium.engine.vpn.WatchRules.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRulesTest {
    private val own = "app.mycelium"
    private val all = { _: String -> true }

    @Test fun ownPackageRejected() {
        val plan = WatchRules.watch(emptyMap(), listOf(own, "com.android.chrome"), own, 100, all)
        assertEquals(mapOf(own to "own_package"), plan.rejected)
        assertEquals(setOf("com.android.chrome"), plan.changes.keys)
        assertEquals(listOf("com.android.chrome"), WatchRules.watched(WatchRules.apply(emptyMap(), plan), own))
    }

    @Test fun ownPackageNeverCountsAsWatched() {
        assertEquals(emptyList<String>(), WatchRules.watched(mapOf(own to State(true, 1)), own))
    }

    @Test fun notInstalledRejected() {
        val plan = WatchRules.watch(emptyMap(), listOf("gone.app"), own, 100) { false }
        assertEquals(mapOf("gone.app" to "not_installed"), plan.rejected)
        assertTrue(plan.changes.isEmpty())
    }

    @Test fun emptySelectionBlocksStart() {
        assertEquals("no_apps_selected", WatchRules.startError(emptyList(), own))
        assertEquals("no_apps_selected", WatchRules.startError(listOf(own), own))
        assertNull(WatchRules.startError(listOf("com.android.chrome"), own))
    }

    @Test fun watchSetsWatchedAndTs() {
        val plan = WatchRules.watch(mapOf("a" to State(false, null)), listOf("a", "b"), own, 500, all)
        assertEquals(mapOf("a" to State(true, 500), "b" to State(true, 500)), plan.changes)
    }

    @Test fun watchingAgainKeepsOriginalTs() {
        val plan = WatchRules.watch(mapOf("a" to State(true, 100)), listOf("a"), own, 500, all)
        assertTrue(plan.changes.isEmpty())
    }

    @Test fun unwatchKeepsTsAndRewatchRefreshesIt() {
        var cur = WatchRules.apply(emptyMap(), WatchRules.watch(emptyMap(), listOf("a", "b"), own, 100, all))
        val off = WatchRules.unwatch(cur, listOf("a", "never.watched"))
        assertEquals(mapOf("a" to State(false, 100)), off.changes)
        cur = WatchRules.apply(cur, off)
        assertEquals(listOf("b"), WatchRules.watched(cur, own))
        cur = WatchRules.apply(cur, WatchRules.watch(cur, listOf("a"), own, 900, all))
        assertEquals(State(true, 900), cur["a"])
    }

    @Test fun unwatchingEverythingBlocksStart() {
        var cur = WatchRules.apply(emptyMap(), WatchRules.watch(emptyMap(), listOf("a"), own, 1, all))
        cur = WatchRules.apply(cur, WatchRules.unwatch(cur, listOf("a")))
        assertEquals("no_apps_selected", WatchRules.startError(WatchRules.watched(cur, own), own))
    }

    @Test fun presetPickIsSeedModCount() {
        assertEquals("preset01", WatchRules.pickPreset("3966e13ad3d5eb0c", listOf("preset01")))
        val presets = (1..25).map { "preset%02d".format(it) }
        assertEquals("preset24", WatchRules.pickPreset("3966e13ad3d5eb0c", presets))   // 4136240950564743948 mod 25 = 23
        assertEquals("preset01", WatchRules.pickPreset("ffffffffffffffff", listOf("preset01")))
    }
}
