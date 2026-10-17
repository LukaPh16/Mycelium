package app.mycelium.engine.terrarium

import app.mycelium.engine.FixtureDb
import app.mycelium.engine.classify.PacketTyper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class TerrariumTest {
    private val typer = PacketTyper.parse(File(System.getProperty("packetTypesPath")!!).readText())
    private val config = Terrarium.parseConfig(File(System.getProperty("terrariumConfigPath")!!).readText())
    private val assets = File(System.getProperty("assetsDir")!!)
    private val presets: Map<String, JsonObject> = Json.parseToJsonElement(File(assets, "organisms/index.json").readText()).jsonObject
        .getValue("presets").jsonArray.associate { p ->
            val o = p.jsonObject
            o.getValue("id").jsonPrimitive.content to Json.parseToJsonElement(File(assets, o.getValue("file").jsonPrimitive.content).readText()).jsonObject
        }
    private lateinit var fx: FixtureDb

    @Before fun setUp() {
        fx = FixtureDb()
        // Give the fixture's packet rows a ptype, as live writes and the backfill do.
        fx.exec("UPDATE event SET ptype = CASE WHEN company IS NOT NULL THEN 'tracker' WHEN kind='dns' THEN 'dns' ELSE 'https' END WHERE kind IN ('dns','tripwire')")
    }

    @After fun tearDown() = fx.close()

    private fun terrarium(cfg: Terrarium.Config = config, sim: Set<String>? = null) = Terrarium(fx.query, cfg, presets, typer, simulating = { sim })
    private fun JsonObject.counts() = getValue("packet_counts_by_type").jsonObject.mapValues { it.value.jsonPrimitive.long }

    @Test fun configMatchesSpec() {
        assertEquals(listOf(0.0, 50.0, 200.0), config.thresholds)
        assertEquals(mapOf("default" to 1.0, "tracker" to 2.0, "os_packets" to 0.01), config.weights)
        assertEquals("preset01", config.defaultPreset)
        assertEquals(25, presets.size)
    }

    @Test fun stageMath() {
        val t = listOf(0.0, 50.0, 200.0)
        assertEquals(1 to 50.0, Terrarium.stage(0.0, t))
        assertEquals(1 to 50.0, Terrarium.stage(49.99, t))
        assertEquals(2 to 200.0, Terrarium.stage(50.0, t))
        assertEquals(3 to null, Terrarium.stage(200.0, t))
        assertEquals(3 to null, Terrarium.stage(10_000.0, t))
        val four = listOf(0.0, 10.0, 20.0, 40.0)                               // stage count follows the config
        assertEquals(3 to 40.0, Terrarium.stage(25.0, four))
        assertEquals(4 to null, Terrarium.stage(40.0, four))
    }

    @Test fun perPresetThresholdsWin() {
        val slow = presets.entries.single { it.value["name"]?.jsonPrimitive?.content == "Slow Moss" }.key
        assertEquals(listOf(0.0, 80.0, 300.0), terrarium().thresholdsFor(slow))
        assertEquals(config.thresholds, terrarium().thresholdsFor("preset01"))
        // Same counts, different stage: com.b grows 4 (tracker 2 + dns 1 + ... ) under a low global config vs Slow Moss.
        val low = config.copy(thresholds = listOf(0.0, 2.0, 1000.0))
        fx.exec("INSERT INTO organism(package, preset_id, chosen_ts) VALUES('com.b', 'preset01', 0)")
        assertEquals(2, terrarium(low).forApp("com.b")!!.getValue("stage").jsonPrimitive.int)
        fx.exec("UPDATE organism SET preset_id='$slow' WHERE package='com.b'")
        val b = terrarium(low).forApp("com.b")!!
        assertEquals(1, b.getValue("stage").jsonPrimitive.int)
        assertEquals(80.0, b.getValue("next_threshold").jsonPrimitive.double, 0.0)
    }

    @Test fun weightsApplied() {
        val t = terrarium()
        assertEquals(10 + 5 * 2 + 1000 * 0.01 + 3, t.growth(mapOf("dns" to 10L, "tracker" to 5L, "os_packets" to 1000L, "https" to 3L)), 1e-9)
        assertEquals(2.0, t.weight("tracker"), 0.0)
        assertEquals(1.0, t.weight("carrier-pigeon"), 0.0)                    // unknown -> other -> default weight
        // com.a: tracker 3 (ids 1, 2, 11), dns 1 (id 3), https 1 (tripwire 7), os_packets 21 (traffic)
        val a = t.forApp("com.a")!!
        assertEquals(mapOf("tracker" to 3L, "dns" to 1L, "https" to 1L, "os_packets" to 21L), a.counts().filterValues { it > 0 })
        assertEquals(3 * 2 + 1 + 1 + 21 * 0.01, a.getValue("growth").jsonPrimitive.double, 1e-9)
        assertEquals(26, a.getValue("total_packets").jsonPrimitive.int)
        val noOs = terrarium(config.copy(countOsPackets = false)).forApp("com.a")!!
        assertEquals(0L, noOs.counts().getValue("os_packets"))
    }

    @Test fun everyListedTypeAppears() {
        val a = terrarium().forApp("com.a")!!
        assertEquals(typer.ids, a.counts().keys.toList())
        assertEquals(1, a.getValue("stage").jsonPrimitive.int)
        assertEquals(3, a.getValue("stage_count").jsonPrimitive.int)
        assertEquals(1, a.getValue("stage_shown").jsonPrimitive.int)
        assertEquals("preset07", a.getValue("preset_id").jsonPrimitive.content)    // fixture organism
        assertTrue(terrarium().forApp("com.c") == null)                            // not watched
        assertEquals(listOf("com.a", "com.b"), terrarium().list().map { it.getValue("app_id").jsonPrimitive.content })
    }

    @Test fun sanctuaryQueueCountedOnceBeforeAndAfterProcessing() {
        val before = terrarium().forApp("com.b")!!
        fx.exec("INSERT INTO queue(ts, kind, ptype, package, domain, done) VALUES(1, 'dns', 'tracker', 'com.b', 'ads.example', 0)")
        val queued = terrarium().forApp("com.b")!!
        assertEquals(before.counts().getValue("tracker") + 1, queued.counts().getValue("tracker"))
        assertEquals(1, queued.getValue("sanctuary_pending").jsonPrimitive.int)
        // processing: the queue row is marked done and becomes one event row
        fx.exec("UPDATE queue SET done=1")
        fx.ev(2, "dns", "com.b", "ads.example", "Example", "ads", verdict = "offline")
        fx.exec("UPDATE event SET ptype='tracker' WHERE domain='ads.example'")
        val processed = terrarium().forApp("com.b")!!
        assertEquals(queued.counts(), processed.counts())
        assertEquals(0, processed.getValue("sanctuary_pending").jsonPrimitive.int)
    }

    @Test fun simRowsOnlyWhileSimulating() {
        val real = terrarium().forApp("com.a")!!.counts().getValue("dns")
        val sim = terrarium(sim = setOf("com.c")).forApp("com.a")!!.counts().getValue("dns")
        assertEquals(1L, real)
        assertEquals(2L, sim)                                                     // + fixture row 8 (x.sim, sim=1)
        assertTrue(terrarium(sim = setOf("com.c")).forApp("com.c") != null)      // scripted app shown while simulating
    }

    @Test fun nextThresholdNullAtTop() {
        val top = terrarium(config.copy(thresholds = listOf(0.0, 1.0, 2.0))).forApp("com.a")!!
        assertEquals(3, top.getValue("stage").jsonPrimitive.int)
        assertEquals(JsonNull, top["next_threshold"])
    }

    private fun dnsRow(i: Int) {
        fx.ev(FixtureDb.B + 20 * FixtureDb.H + i, "dns", "com.a", "n$i.example", fg = 1)
        fx.exec("UPDATE event SET ptype='dns' WHERE domain='n$i.example'")
    }

    @Test fun feedEmitsStageChangeAtOnceAndThrottlesTheRest() {
        val out = CopyOnWriteArrayList<String>()
        val t = terrarium(config.copy(thresholds = listOf(0.0, 9.0, 1000.0)))       // com.a starts at 8.21 -> stage 1
        val feed = TerrariumFeed(t, { out += it })
        feed.touch("com.a", "dns")                 // first touch: computes and emits
        Thread.sleep(150)
        assertEquals(1, out.size)
        for (i in 1..5) { dnsRow(i); feed.touch("com.a", "dns") }   // 8.21 -> 9.21 on the first row: stage 2
        Thread.sleep(150)
        assertEquals(2, out.size)                  // the stage change went out at once
        assertTrue(out[1].contains("\"stage\":2"))
        Thread.sleep(1300)
        assertEquals(3, out.size)                  // the other four rows coalesced into one update a second later
        assertTrue(out.all { it.contains("\"type\":\"terrarium_update\"") && it.contains("\"app_id\":\"com.a\"") })
    }

    @Test fun feedKeepsThrottlingWhenPredictionIsWrong() {
        val out = CopyOnWriteArrayList<String>()
        val feed = TerrariumFeed(terrarium(config.copy(thresholds = listOf(0.0, 9.0, 1000.0))), { out += it })
        feed.touch("com.a", "dns")
        Thread.sleep(150)
        repeat(10) { feed.touch("com.a", "tracker") }   // no rows written: the DB still says stage 1
        Thread.sleep(1300)
        assertTrue("emits ${out.size}", out.size <= 2)
    }
}
