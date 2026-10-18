package app.mycelium.engine.sim

import app.mycelium.engine.FixtureDb
import app.mycelium.engine.FixtureDb.Companion.B
import app.mycelium.engine.FixtureDb.Companion.H
import app.mycelium.engine.chat.AppRef
import app.mycelium.engine.chat.ChatBot
import app.mycelium.engine.chat.Sentence
import app.mycelium.engine.chat.TemplateWriter
import app.mycelium.engine.chat.Validator
import app.mycelium.engine.classify.Classifier
import app.mycelium.engine.classify.PacketTyper
import app.mycelium.engine.classify.RuleSet
import app.mycelium.engine.classify.Trackers
import app.mycelium.engine.stats.Stats
import app.mycelium.engine.terrarium.Terrarium
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class SimTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val trackers = Trackers.parse(File(System.getProperty("trackersPath")!!).readText())
    private val typer = PacketTyper.parse(File(System.getProperty("packetTypesPath")!!).readText())
    private val script = SimScript.parse(File(System.getProperty("simDemoPath")!!).readText())
    private val classify = { d: String?, _: String?, _: String ->
        val m = d?.let(trackers::match)
        Classifier.Verdict(m?.company, m?.category ?: d?.let { "unknown" }, null)
    }

    private fun replay(now: Long = B + 15 * H): List<Map<String, Any?>> {
        val simRules = ArrayList<RuleSet.Rule>()
        return script.events.map { SimScript.row(it, now, utc, classify, typer, simRules) }
    }

    @Test fun demoScriptShape() {
        assertEquals("demo1", script.name)
        assertEquals(5, script.packages.size)
        assertTrue(script.events.size in 70..90)
        val rows = replay()
        assertTrue(rows.all { it["sim"] == 1 })
        assertTrue(rows.all { it["ptype"] == null || it["ptype"] in typer.ids })
    }

    @Test fun trackerHeavyAppReachesDensityInFirst30Seconds() {
        var t = 0L
        val early = script.events.filter { t += it.dtMs; t <= 30_000 && it.kind == "dns" && it.pkg == "com.zhiliaoapp.musically" }
        val trackerHits = early.count { trackers.match(it.domain!!) != null }
        assertTrue("dns ${early.size}", early.size >= 20)
        assertTrue("trackers $trackerHits", trackerHits >= 10)
        // With the shipped terrarium.json the demo organism must reach stage 2 within 30 s at speed 1.
        val cfg = Terrarium.parseConfig(File(System.getProperty("terrariumConfigPath")!!).readText())
        val growth = early.sumOf { if (trackers.match(it.domain!!) != null) cfg.weights.getValue("tracker") else cfg.weights.getValue("default") }
        assertTrue("growth $growth", Terrarium.stage(growth, cfg.thresholds).first >= 2)
    }

    @Test fun blockThenBlockedLookupsAndTripwireRetries() {
        val rows = replay()
        val blockAt = rows.indexOfFirst { it["kind"] == "block" }
        assertEquals("applovin.com", rows[blockAt]["domain"])
        val after = rows.drop(blockAt + 1).filter { it["package"] == "com.king.candycrushsaga" }
        assertTrue(after.filter { it["kind"] == "dns" && (it["domain"] as String).endsWith("applovin.com") }.all { it["verdict"] == "blocked" && it["answer"] == "10.111.222.9" })
        assertTrue(after.count { it["kind"] == "tripwire" } >= 2)
        assertTrue(after.filter { it["kind"] == "tripwire" }.all { it["ptype"] == "https" })
        val before = rows.take(blockAt).filter { it["domain"] == "ms.applovin.com" }
        assertTrue(before.isNotEmpty() && before.all { it["verdict"] == "allowed" && it["ptype"] == "tracker" })
    }

    @Test fun nightBurstIsBackdatedToSmallHours() {
        val now = B + 15 * H
        val night = replay(now).filter { it["package"] == "com.weather.Weather" && it["fg"] == 0 }
            .filter { Calendar.getInstance(utc).apply { timeInMillis = it["ts"] as Long }.get(Calendar.HOUR_OF_DAY) < 6 }
        assertTrue("night rows ${night.size}", night.size >= 3)
        assertTrue(night.all { (it["ts"] as Long) in B until B + 6 * H })
        assertEquals(B - 24 * H + 3 * H, SimScript.at("03:00", B + 2 * H, utc))   // not yet 03:00 -> yesterday
    }

    @Test fun ptypeHintGoesThroughTyper() {
        val rows = replay()
        assertEquals("quic", rows.first { it["domain"] == "edge-chat.instagram.com" }["ptype"])
        assertEquals("tracker", rows.first { it["domain"] == "ads.tiktok.com" }["ptype"])
        assertEquals("dns", rows.first { it["domain"] == "g.whatsapp.net" }["ptype"])
    }

    // ---- sim rows are invisible outside simulate mode (fixture row 8: com.a, x.sim, sim=1) ----

    private fun JsonObject.ids() = getValue("event_ids").jsonArray.map { it.jsonPrimitive.long }

    @Test fun simRowsExcludedFromRealModeStats() {
        val fx = FixtureDb()
        try {
            val real = Stats(fx.query, utc)
            assertFalse(8L in real.lastEvents("com.a", null, 50).ids())
            assertEquals(3, real.appSummary("com.a", B, B + 24 * H).getValue("lookups").jsonPrimitive.long)
            val args = buildJsonObject { put("package", "com.a"); put("from_ms", B); put("to_ms", B + 24 * H) }
            assertEquals(3, real.call("appSummary", args, B + 23 * H).getValue("lookups").jsonPrimitive.long)

            val simulating = Stats(fx.query, utc, simulating = { setOf("com.c") })
            assertTrue(8L in simulating.call("lastEvents", buildJsonObject { put("package", "com.a") }, B + 23 * H).ids())
            assertEquals(4, simulating.call("appSummary", args, B + 23 * H).getValue("lookups").jsonPrimitive.long)
            // the scripted package counts as watched for display, without touching app.watched
            val top = simulating.call("topApps", buildJsonObject { put("from_ms", B); put("to_ms", B + 24 * H) }, B + 23 * H)
            assertTrue(top.getValue("apps").jsonArray.any { it.jsonObject.getValue("package").jsonPrimitive.content == "com.c" })
            assertEquals(0L, fx.query("SELECT watched FROM app WHERE package='com.c'", emptyList()).single()["watched"])
        } finally {
            fx.close()
        }
    }

    @Test fun simRowsExcludedFromRealModeChat() {
        val fx = FixtureDb()
        try {
            val templates = TemplateWriter.parse(File(System.getProperty("chatTemplatesPath")!!).readText())
            val apps = listOf(AppRef("com.a", "Alpha", true), AppRef("com.b", "Beta", true))
            fun texts(r: JsonObject) = r.getValue("sentences").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
            val real = ChatBot(fx.query, templates, utc).answer("What happened at 12:00?", apps, emptyList(), B + 23 * H)
            assertFalse(texts(real).toString(), texts(real).any { "x.sim" in it })
            val sim = ChatBot(fx.query, templates, utc, simulating = { setOf("com.a") }).answer("What happened at 12:00?", apps, emptyList(), B + 23 * H)
            assertTrue(texts(sim).toString(), texts(sim).any { "x.sim" in it })
            assertEquals(0, sim.getValue("dropped").jsonPrimitive.long.toInt())
        } finally {
            fx.close()
        }
    }

    @Test fun validatorRejectsSimCitesOutsideSimulateMode() {
        val fx = FixtureDb()
        try {
            val rows = { ids: List<Long> -> fx.query("SELECT id, ts, package, company, sim FROM event WHERE id IN (${ids.joinToString(",")})", emptyList())
                .associateBy { (it["id"] as Number).toLong() } }
            val s = listOf(Sentence("Alpha looked up x.sim.", listOf(8L)))
            val labels = mapOf("alpha" to "com.a")
            assertEquals(1, Validator.validate(s, JsonObject(emptyMap()), rows, labels, emptyList(), utc).dropped)
            assertEquals(0, Validator.validate(s, JsonObject(emptyMap()), rows, labels, emptyList(), utc, allowSim = true).dropped)
        } finally {
            fx.close()
        }
    }

    @Test fun demoForegroundLinesBecomeScreenTimeSessions() {
        val track = SimScript.FgTrack()
        val rows = HashMap<Pair<String, Long>, Long>()   // upsert by (package, start)
        var t = 0L
        for (line in script.events) { t += line.dtMs; track.feed(line, t).forEach { (p, a, b) -> rows[p to a] = b } }
        track.end(t + 1000).forEach { (p, a, b) -> rows[p to a] = b }
        val sessions = rows.entries.map { Triple(it.key.first, it.key.second, it.value) }.sortedBy { it.second }
        assertEquals("com.zhiliaoapp.musically", sessions.first().first)
        assertEquals(400L, sessions.first().second)
        assertTrue(sessions.none { it.first == "com.whatsapp" || it.first == "com.weather.Weather" })   // background-only apps
        sessions.zipWithNext().forEach { (x, y) -> assertEquals(x.third, y.second) }                    // one app on screen at a time
        assertEquals(t + 1000, sessions.last().third)
    }
}
