package app.mycelium.engine.stats

import app.mycelium.engine.FixtureDb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** Each stats function on the fixture DB (real SQLite via test-only sqlite-jdbc). */
class StatsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val H = FixtureDb.H
    private val D = FixtureDb.D
    private val B = FixtureDb.B
    private lateinit var fx: FixtureDb
    private lateinit var stats: Stats
    private val query: Query get() = fx.query

    @Before fun setUp() {
        fx = FixtureDb()
        stats = Stats(fx.query, utc)
    }

    @After fun tearDown() = fx.close()

    private fun JsonObject.l(k: String) = getValue(k).jsonPrimitive.long
    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.arr(k: String) = getValue(k).jsonArray.map { it.jsonObject }
    private fun JsonObject.ids() = getValue("event_ids").jsonArray.map { it.jsonPrimitive.long }

    @Test fun appSummary() {
        val r = stats.appSummary("com.a", B, B + D)
        assertEquals("Alpha", r.s("label"))
        assertEquals(3, r.l("lookups"))                 // sim row and earlier period excluded
        assertEquals(3, r.l("unique_domains"))
        assertEquals(2, r.l("tracker_hits"))
        assertEquals(1, r.l("blocked"))
        assertEquals(2, r.l("fg")); assertEquals(1, r.l("bg"))
        assertEquals(1, r.l("tripwire_hits"))
        assertEquals(1500, r.l("rx_bytes")); assertEquals(300, r.l("tx_bytes")); assertEquals(14, r.l("rx_packets"))
        assertEquals(B + 12 * H, r.l("first_seen")); assertEquals(B + 13 * H, r.l("last_seen"))
        assertEquals(listOf("Google" to 2L), r.arr("trackers").map { it.s("company") to it.l("hits") })
        assertEquals(listOf(7L, 3, 2, 1), r.ids())
    }

    @Test fun topAppsByLookupsTrackersBackgroundBytes() {
        fun top(by: String, s: Stats.Scope = Stats.Scope()) =
            stats.topApps(B, B + D, by, 10, s).arr("apps").map { it.s("package") to it.l("value") }
        assertEquals(listOf("com.a" to 3L, "com.b" to 2L), top("lookups"))
        assertEquals(listOf("com.a" to 3L, "com.b" to 2L, "com.c" to 1L), top("lookups", Stats.Scope(includeUnwatched = true)))
        assertEquals(listOf("com.a" to 2L, "com.b" to 1L), top("trackers"))
        assertEquals(listOf("com.b" to 2L, "com.a" to 1L), top("background"))
        assertEquals(listOf("com.b" to 5000L, "com.a" to 1800L), top("bytes"))
        assertEquals("Alpha", stats.topApps(B, B + D, "lookups", 1, Stats.Scope()).arr("apps")[0].s("label"))
        assertEquals(setOf(1L, 2, 3, 4, 5), stats.topApps(B, B + D, "lookups", 10).ids().toSet())
        assertTrue(stats.topApps(B, B + D, "bytes", 10).ids().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun topAppsRejectsUnknownBy() { stats.topApps(B, B + D, "vibes", 10) }

    @Test fun topCompanies() {
        val r = stats.topCompanies(B, B + D, 10)
        assertEquals(listOf("Google" to 2L, "AppsFlyer" to 1L), r.arr("companies").map { it.s("company") to it.l("hits") })
        assertEquals(setOf(1L, 2, 4), r.ids().toSet())
        val all = stats.topCompanies(B, B + D, 10, Stats.Scope(includeUnwatched = true))
        assertEquals(3, all.arr("companies")[0].l("hits"))
        assertEquals(2, all.arr("companies")[0].l("apps"))
    }

    @Test fun timeline() {
        val r = stats.timeline(B, B + D, H, null)
        val b = r.arr("buckets")
        assertEquals(24, b.size)
        assertEquals(B + 12 * H, b[12].l("start_ms"))
        assertEquals(2, b[12].l("lookups")); assertEquals(2, b[12].l("tracker_hits")); assertEquals(1, b[12].l("blocked"))
        assertEquals(1, b[3].l("lookups")); assertEquals(1, b[3].l("bg"))
        assertEquals(5, b.sumOf { it.l("lookups") })
        assertEquals(1, stats.timeline(B, B + D, H, "com.c").arr("buckets")[12].l("lookups"))   // explicit package
    }

    @Test(expected = IllegalArgumentException::class) fun timelineCapsBuckets() { stats.timeline(B, B + 30 * D, 60_000, null) }

    @Test fun domainHistoryMatchesSubdomains() {
        val r = stats.domainHistory("DoubleClick.net", B, B + D)
        val apps = r.arr("apps")
        assertEquals(listOf("com.a"), apps.map { it.s("package") })
        assertEquals(2, apps[0].l("hits"))
        assertEquals(setOf("allowed", "blocked"), apps[0].s("verdicts").split(',').toSet())
        assertEquals("Google", r.s("company"))
        assertEquals(listOf(2L, 1), r.ids())
        assertTrue(stats.domainHistory("ubleclick.net", B, B + D).arr("apps").isEmpty())
    }

    @Test fun lastEvents() {
        val r = stats.lastEvents(null, null, 50)
        val evs = r.arr("events")
        assertFalse(evs.any { it["package"]?.jsonPrimitive?.content == "com.c" })
        assertFalse(evs.any { it.l("sim") == 1L })
        assertTrue(evs.any { it.s("kind") == "vpn" })
        assertEquals(evs.map { it.l("id") }, evs.map { it.l("id") }.sortedDescending())
        assertEquals(listOf(6L), stats.lastEvents("com.c", null, 10).ids())
        assertEquals(listOf(11L, 2, 1), stats.lastEvents("com.a", "doubleclick.net", 10).ids())
        assertEquals(2, stats.lastEvents(null, null, 2).arr("events").size)
    }

    @Test fun nightReport() {
        val date = "2025-10-09"
        assertEquals(B to B + 6 * H, stats.nightRange(date))
        val r = stats.nightReport(date)
        assertEquals(2, r.l("lookups")); assertEquals(2, r.l("background_lookups"))
        val apps = r.arr("apps")
        assertEquals(listOf("com.b"), apps.map { it.s("package") })
        assertEquals("Beta", apps[0].s("label"))
        assertEquals(1, apps[0].l("tracker_hits"))
        assertEquals(2, r.arr("top_domains").size)
        assertEquals(listOf(5L, 4), r.ids())
    }

    @Test fun compare() {
        val r = stats.compare("com.a", B - 3 * D to B - D, B to B + D)
        assertEquals(1, r.getValue("period1").jsonObject.l("lookups"))
        assertEquals(3, r.getValue("period2").jsonObject.l("lookups"))
        val delta = r.getValue("delta").jsonObject
        assertEquals(2, delta.l("lookups")); assertEquals(1500, delta.l("rx_bytes")); assertEquals(1, delta.l("blocked"))
        assertTrue(r.getValue("new_companies").jsonArray.isEmpty())
        assertEquals(listOf(7L, 3, 2, 1, 11), r.ids())
    }

    @Test fun gardenCoversWatchedApps() {
        val apps = stats.garden(B + D).arr("apps")
        assertEquals(listOf("com.a", "com.b"), apps.map { it.s("package") })
        val a = apps[0]
        assertEquals("preset07", a.s("preset_id"))
        assertEquals(3, a.l("lookups_24h")); assertEquals(2, a.l("tracker_hits_24h")); assertEquals(1, a.l("companies_24h"))
        assertEquals(1, a.l("blocked_24h")); assertEquals(1, a.l("anomalies_24h")); assertEquals(1800, a.l("bytes_24h"))
        assertFalse(apps[1].containsKey("preset_id"))
        assertEquals(3, stats.garden(B + D, Stats.Scope(includeUnwatched = true)).arr("apps").size)
    }

    @Test fun recordWithEvidence() {
        val r = stats.record(9)
        assertEquals("anomaly", r.getValue("record").jsonObject.s("kind"))
        assertEquals("Alpha", r.getValue("record").jsonObject.s("app_label"))
        assertEquals(listOf(1L, 2), r.arr("evidence").map { it.l("id") })
        assertEquals(listOf(9L, 1, 2), r.ids())
    }

    @Test(expected = IllegalArgumentException::class) fun recordNotFound() { stats.record(999) }

    @Test fun eventsLogMarksUnwatched() {
        val evs = stats.events(50, null, null, null, null).arr("events")
        assertEquals(11, evs.size)
        assertEquals(0, evs.first { it.l("id") == 6L }.l("watched"))
        assertEquals(1, evs.first { it.l("id") == 1L }.l("watched"))
        assertEquals(listOf(5L, 4), stats.events(10, "com.b", "dns", null, null).arr("events").map { it.l("id") })
        assertEquals(listOf(4L, 3), stats.events(2, null, null, null, 5).arr("events").map { it.l("id") })
    }

    @Test fun dispatch() {
        val now = B + D
        assertEquals(3, stats.call("appSummary", buildJsonObject { put("package", "com.a") }, now).l("lookups"))
        assertEquals("bytes", stats.call("topApps", buildJsonObject { put("by", "bytes") }, now).s("by"))
        val cmp = stats.call("compare", buildJsonObject {
            put("package", "com.a")
            put("period1", buildJsonObject { put("from_ms", B - 3 * D); put("to_ms", B - D) })
            put("period2", buildJsonObject { put("from_ms", B); put("to_ms", B + D) })
        }, now)
        assertEquals(2, cmp.getValue("delta").jsonObject.l("lookups"))
        for ((fn, err) in listOf("nope" to "unknown_fn", "nightReport" to "missing_date", "appSummary" to "missing_package")) {
            try { stats.call(fn, JsonObject(emptyMap()), now); throw AssertionError(fn) } catch (e: IllegalArgumentException) { assertEquals(err, e.message) }
        }
        assertTrue(stats.call("timeline", JsonObject(emptyMap()), now).getValue("buckets") is JsonArray)
    }

    @Test fun guardedQueriesRunOnFixture() {
        for (sql in listOf("SELECT COUNT(*) n FROM event", "WITH x AS (SELECT package FROM v_parasites) SELECT * FROM x")) {
            assertEquals(null, SqlGuard.check(sql)); query(sql, emptyList())
        }
    }
}
