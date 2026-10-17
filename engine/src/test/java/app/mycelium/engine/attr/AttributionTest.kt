package app.mycelium.engine.attr

import app.mycelium.engine.attr.Attribution.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttributionTest {
    @Test fun seedIsDeterministicSha256Prefix() {
        assertEquals("3966e13ad3d5eb0c", Attribution.seed("com.android.chrome"))
        assertEquals("7d0c67a410c57b10", Attribution.seed("org.mozilla.firefox"))
        assertEquals(Attribution.seed("com.android.chrome"), Attribution.seed("com.android.chrome"))
        assertEquals(16, Attribution.seed("x").length)
    }

    @Test fun unusableUids() {
        assertFalse(Attribution.usableUid(1051))
        assertFalse(Attribution.usableUid(-1))
        assertTrue(Attribution.usableUid(10123))
    }

    @Test fun uidWinsOverForeground() {
        assertEquals(Result(10100, "com.a", "uid_dns", 0), Attribution.decide(10100, "com.a", "uid_dns", "com.b", fgKnown = true))
        assertEquals(Result(10100, "com.a", "uid_tcp", 1), Attribution.decide(10100, "com.a", "uid_tcp", "com.a", fgKnown = true))
    }

    @Test fun uidWithoutUsageAccessHasUnknownFg() {
        assertEquals(Result(10100, "com.a", "uid_dns", null), Attribution.decide(10100, "com.a", "uid_dns", null, fgKnown = false))
    }

    @Test fun guessFgOnlyWhenUidFailedAndForegroundExists() {
        assertEquals(Result(null, "com.b", "guess_fg", 1), Attribution.decide(1051, null, "uid_dns", "com.b", fgKnown = true))
    }

    @Test fun unknownWhenNothingKnown() {
        assertEquals(Result(null, null, "unknown", null), Attribution.decide(-1, null, "uid_dns", null, fgKnown = true))
        assertEquals(Result(null, null, "unknown", null), Attribution.decide(-1, null, "uid_tcp", null, fgKnown = false))
    }

    @Test fun rowHasEventColumns() {
        assertEquals(mapOf("package" to "com.a", "how" to "uid_dns", "fg" to 1), Result(1, "com.a", "uid_dns", 1).row())
    }
}
