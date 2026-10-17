package app.mycelium.engine.terrarium

import app.mycelium.engine.classify.PacketTyper
import app.mycelium.engine.db.Row
import app.mycelium.engine.stats.Query
import app.mycelium.engine.vpn.WatchRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Organism data per watched app (no sprites, no drawing). growth = sum(count * weight) over packet types;
 * stage = highest stage whose threshold <= growth (1-based). Counts: event rows with ptype (sim=0 unless simulating),
 * plus Sanctuary queue rows not yet processed (counted once: as queue rows before, as events after), plus OS packet
 * totals from traffic when count_os_packets is on.
 */
class Terrarium(
    private val q: Query,
    val config: Config,
    /** preset id -> preset JSON, in index order. */
    val presets: Map<String, JsonObject>,
    private val typer: PacketTyper,
    private val simulating: () -> Set<String>? = { null },
) {
    data class Config(
        val thresholds: List<Double>, val weights: Map<String, Double>, val countOsPackets: Boolean,
        val defaultPreset: String, val presetsIndex: String,
    )

    fun weight(ptype: String?): Double = config.weights[typer.known(ptype)] ?: config.weights["default"] ?: 1.0

    /** The preset's own stage_thresholds win over the global ones. */
    fun thresholdsFor(presetId: String?): List<Double> =
        presets[presetId]?.get("stage_thresholds")?.jsonArray?.map { it.jsonPrimitive.double } ?: config.thresholds

    /** Stored preset, else picked from the seed so every app always has an organism. */
    fun presetFor(stored: String?, seed: String?): String = stored?.takeIf { it in presets }
        ?: if (presets.isNotEmpty() && seed != null) WatchRules.pickPreset(seed, presets.keys.toList()) else config.defaultPreset

    /** Watched apps (plus scripted ones while simulating), optionally just [appId]. */
    fun list(appId: String? = null): List<JsonObject> {
        val sim = simulating()
        val simIn = sim?.takeIf { it.isNotEmpty() }?.joinToString(",", " OR a.package IN (", ")") { "'" + it.replace("'", "''") + "'" } ?: ""
        val apps = q(
            "SELECT a.package, a.label, a.seed, o.preset_id, o.stage_shown FROM app a LEFT JOIN organism o ON o.package=a.package " +
                "WHERE (a.watched=1$simIn)" + (if (appId != null) " AND a.package=?" else "") + " ORDER BY a.label COLLATE NOCASE",
            listOfNotNull(appId),
        )
        if (apps.isEmpty()) return emptyList()
        val pkgs = apps.map { it["package"] as String }
        val inList = pkgs.joinToString(",") { "?" }
        val simCond = if (sim != null) "" else " AND sim=0"
        val counts = HashMap<String, LinkedHashMap<String, Long>>()
        fun add(rows: List<Row>) {
            for (r in rows) {
                val m = counts.getOrPut(r["package"] as String) { LinkedHashMap() }
                val t = typer.known(r["ptype"] as String?)
                m[t] = (m[t] ?: 0) + (r["n"] as Number).toLong()
            }
        }
        add(q("SELECT package, ptype, COUNT(*) n FROM event WHERE ptype IS NOT NULL AND package IN ($inList)$simCond GROUP BY package, ptype", pkgs))
        val queued = q("SELECT package, ptype, COUNT(*) n FROM queue WHERE done=0 AND package IN ($inList) GROUP BY package, ptype", pkgs)
        add(queued)
        val pending = queued.groupBy { it["package"] as String }.mapValues { (_, rs) -> rs.sumOf { (it["n"] as Number).toLong() } }
        if (config.countOsPackets) {
            add(q("SELECT package, 'os_packets' ptype, COALESCE(SUM(rx_packets + tx_packets), 0) n FROM traffic WHERE package IN ($inList) GROUP BY package", pkgs))
        }
        return apps.map { a ->
            val pkg = a["package"] as String
            val c = LinkedHashMap<String, Long>().apply { typer.ids.forEach { put(it, counts[pkg]?.get(it) ?: 0L) } }
            val preset = presetFor(a["preset_id"] as String?, a["seed"] as String?)
            val growth = growth(c)
            val thresholds = thresholdsFor(preset)
            val (stage, next) = stage(growth, thresholds)
            buildJsonObject {
                put("app_id", pkg)
                put("label", a["label"] as String? ?: pkg)
                put("preset_id", preset)
                put("packet_counts_by_type", buildJsonObject { c.forEach { (k, v) -> put(k, v) } })
                put("total_packets", c.values.sum())
                put("growth", Math.round(growth * 100) / 100.0)
                put("stage", stage)
                put("stage_count", thresholds.size)
                if (next != null) put("next_threshold", next) else put("next_threshold", JsonNull)
                put("stage_shown", (a["stage_shown"] as Number?)?.toInt() ?: 1)
                put("sanctuary_pending", pending[pkg] ?: 0L)
                if (sim?.contains(pkg) == true) put("simulated", true)
            }
        }
    }

    fun forApp(appId: String): JsonObject? = list(appId).firstOrNull()

    fun growth(counts: Map<String, Long>): Double = counts.entries.sumOf { (t, n) -> n * weight(t) }

    companion object {
        fun parseConfig(json: String): Config {
            val o = Json.parseToJsonElement(json).jsonObject
            return Config(
                o.getValue("stage_thresholds").jsonArray.map { it.jsonPrimitive.double },
                o["weights"]?.jsonObject?.mapValues { it.value.jsonPrimitive.double } ?: emptyMap(),
                o["count_os_packets"]?.jsonPrimitive?.booleanOrNull ?: true,
                o["default_preset"]?.jsonPrimitive?.contentOrNull ?: "preset01",
                o["presets_index"]?.jsonPrimitive?.contentOrNull ?: "organisms/index.json",
            )
        }

        /** (1-based stage, next threshold or null at the top). Thresholds ascend; the first is normally 0. */
        fun stage(growth: Double, thresholds: List<Double>): Pair<Int, Double?> {
            val i = thresholds.indexOfLast { it <= growth }.coerceAtLeast(0)
            return (i + 1) to thresholds.getOrNull(i + 1)
        }
    }
}
