package app.mycelium.engine.stats

import app.mycelium.engine.stats.Sessions.Ev
import app.mycelium.engine.stats.Sessions.Kind.ALL_OFF
import app.mycelium.engine.stats.Sessions.Kind.CLOSE
import app.mycelium.engine.stats.Sessions.Kind.OPEN
import app.mycelium.engine.stats.Sessions.Span
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pairing UsageStatsManager events into foreground sessions. */
class SessionsTest {
    @Test fun resumedToPausedIsOneSession() {
        val s = Sessions.pair(listOf(Ev(1_000, OPEN, "a", "Main"), Ev(61_000, CLOSE, "a", "Main")), 100_000)
        assertEquals(listOf(Span("a", 1_000, 61_000)), s)
    }

    @Test fun activitySwitchInsideOneAppDoesNotSplit() {
        val evs = listOf(
            Ev(0, OPEN, "a", "Main"), Ev(10_000, CLOSE, "a", "Main"), Ev(10_300, OPEN, "a", "Detail"),   // paused, then next resumed
            Ev(20_000, OPEN, "a", "Third"), Ev(20_100, CLOSE, "a", "Detail"),                            // next resumed before stop
            Ev(20_200, CLOSE, "a", "Detail"),                                                            // late STOPPED: ignored
            Ev(30_000, CLOSE, "a", "Third"),
        )
        assertEquals(listOf(Span("a", 0, 30_000)), Sessions.pair(evs, 99_000))
    }

    @Test fun gapsOfFiveSecondsOrMoreSplit() {
        val evs = listOf(Ev(0, OPEN, "a"), Ev(1_000, CLOSE, "a"), Ev(6_000, OPEN, "a"), Ev(7_000, CLOSE, "a"))
        assertEquals(listOf(Span("a", 0, 1_000), Span("a", 6_000, 7_000)), Sessions.pair(evs, 9_000))
        assertEquals(listOf(Span("a", 0, 7_000)), Sessions.pair(evs.take(2) + Ev(5_999, OPEN, "a") + evs[3], 9_000))
    }

    @Test fun screenOffClosesEverythingAndOpenSessionsRunToNow() {
        val evs = listOf(Ev(0, OPEN, "a"), Ev(5_000, OPEN, "b"), Ev(9_000, ALL_OFF, ""), Ev(20_000, OPEN, "b"))
        assertEquals(listOf(Span("a", 0, 9_000), Span("b", 5_000, 9_000), Span("b", 20_000, 50_000, open = true)), Sessions.pair(evs, 50_000))
    }

    @Test fun closeWithoutOpenIsIgnored() {
        assertEquals(emptyList<Span>(), Sessions.pair(listOf(Ev(5, CLOSE, "a"), Ev(6, CLOSE, "b", "X")), 10))
    }

    @Test fun cursorRebuildsOpenSessionsIdempotently() {
        val all = listOf(Ev(1_000, OPEN, "a"), Ev(2_000, CLOSE, "a"), Ev(10_000, OPEN, "b"))
        val first = Sessions.pair(all, 20_000)
        val cursor = Sessions.cursor(first, 20_000)
        assertEquals(9_999, cursor)
        val later = all + Ev(30_000, CLOSE, "b")
        val second = Sessions.pair(later.filter { it.ts >= cursor }, 40_000)
        assertEquals(listOf(Span("b", 10_000, 30_000)), second)                 // same primary key (b, 10000): upsert, no double count
        assertEquals(40_000, Sessions.cursor(second, 40_000))
    }

    @Test fun deviceTimeIsAnyCountedAppOnScreenWithoutNames() {
        val evs = listOf(
            Ev(0, OPEN, "a", "Main"), Ev(10_000, CLOSE, "a", "Main"), Ev(10_500, OPEN, "b", "X"), Ev(20_000, CLOSE, "b", "X"),
            Ev(20_200, OPEN, "launcher", "Home"), Ev(60_000, CLOSE, "launcher", "Home"),                  // home screen: not counted
            Ev(60_100, OPEN, "c", "Y"), Ev(70_000, ALL_OFF, ""), Ev(80_000, OPEN, "a", "Main"),
        )
        assertEquals(
            listOf(Span(Sessions.DEVICE, 0, 20_000), Span(Sessions.DEVICE, 60_100, 70_000), Span(Sessions.DEVICE, 80_000, 90_000, open = true)),
            Sessions.device(evs, setOf("launcher"), 90_000),
        )
    }
}
