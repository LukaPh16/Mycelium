package app.mycelium.engine.classify

import app.mycelium.engine.classify.Anomalies.Ev
import app.mycelium.engine.classify.Anomalies.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AnomaliesTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val noon = 1_760_000_000_000L - 1_760_000_000_000L % 86_400_000L + 12 * 3600_000L   // 12:00 UTC
    private val night = noon - 9 * 3600_000L                                                    // 03:00 UTC
    private val never = { _: String, _: String -> false }
    private val always = { _: String, _: String -> true }

    private fun detect(window: List<Ev>, blocks: List<Ev> = emptyList(), knownDomain: (String, String) -> Boolean = always,
                       knownCompany: (String, String) -> Boolean = always, recent: Set<Key> = emptySet()) =
        Anomalies.detect(window, blocks, knownDomain, knownCompany, recent, utc)

    private fun dns(id: Long, domain: String, pkg: String = "com.a", ts: Long = noon + id, fg: Int? = 1, company: String? = null) =
        Ev(id, ts, "dns", pkg, domain, company, fg)

    @Test fun newDomainsBurstNeedsFiveNeverSeen() {
        val five = (1L..5).map { dns(it, "d$it.example") } + dns(6, "d1.example")
        val found = detect(five, knownDomain = never)
        assertEquals(listOf("new_domains_burst"), found.map { it.rule })
        assertEquals(listOf(1L, 2, 3, 4, 5), found[0].evidence)
        assertTrue(detect(five.take(4), knownDomain = never).isEmpty())
        assertTrue(detect(five, knownDomain = { _, d -> d == "d5.example" }).isEmpty())
        assertTrue(detect(five).isEmpty())
    }

    @Test fun burstCountsPerApp() {
        val mixed = (1L..3).map { dns(it, "a$it.example", "com.a") } + (4L..5).map { dns(it, "b$it.example", "com.b") }
        assertTrue(detect(mixed, knownDomain = never).isEmpty())
    }

    @Test fun nightBackgroundNeedsThreeBackgroundLookupsBefore6() {
        val bg = (1L..3).map { dns(it, "x.example", ts = night + it, fg = 0) }
        assertEquals(listOf("night_background"), detect(bg).map { it.rule })
        assertTrue(detect(bg.map { it.copy(fg = 1) }).isEmpty())
        assertTrue(detect(bg.map { it.copy(fg = null) }).isEmpty())
        assertTrue(detect(bg.map { it.copy(ts = noon + it.id) }).isEmpty())
        assertTrue(detect(bg.take(2)).isEmpty())
    }

    @Test fun newCompanyOnFirstContact() {
        val evs = listOf(dns(1, "t.appsflyer.com", company = "AppsFlyer"), dns(2, "x.appsflyer.com", company = "AppsFlyer"),
            dns(3, "doubleclick.net", company = "Google"))
        val found = detect(evs, knownCompany = { _, c -> c == "Google" })
        assertEquals(1, found.size)
        assertEquals("new_company", found[0].rule)
        assertEquals("AppsFlyer", found[0].company)
        assertEquals(listOf(1L, 2), found[0].evidence)
    }

    @Test fun tripwireRetryNeedsTwoHitsAfterABlock() {
        val block = Ev(10, noon, "block", null, "doubleclick.net")
        val hits = listOf(Ev(11, noon + 1, "tripwire", "com.a"), Ev(12, noon + 2, "tripwire", "com.a"))
        assertEquals(listOf("tripwire_retry"), detect(hits, listOf(block)).map { it.rule })
        assertTrue(detect(hits.take(1), listOf(block)).isEmpty())
        assertTrue(detect(hits).isEmpty())
        assertTrue(detect(hits, listOf(block.copy(ts = noon + 5))).isEmpty())
        assertTrue(detect(hits, listOf(block.copy(pkg = "com.other"))).isEmpty())
        assertEquals(1, detect(hits, listOf(block.copy(pkg = "com.a"))).size)
    }

    @Test fun dedupedPerAppPerRule() {
        val bg = (1L..3).map { dns(it, "x.example", ts = night + it, fg = 0) }
        assertTrue(detect(bg, recent = setOf(Key("com.a", "night_background", ""))).isEmpty())
        assertEquals(1, detect(bg, recent = setOf(Key("com.b", "night_background", ""))).size)
    }

    @Test fun evidenceIsCapped() {
        val many = (1L..50).map { dns(it, "x.example", ts = night + it, fg = 0) }
        assertEquals(20, detect(many)[0].evidence.size)
    }
}
