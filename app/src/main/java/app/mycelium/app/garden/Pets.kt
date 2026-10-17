package app.mycelium.app.garden

import app.mycelium.app.Api
import app.mycelium.app.obj
import app.mycelium.app.objects
import app.mycelium.app.startOfToday
import java.util.zip.CRC32
import kotlin.math.abs

/** A tracker company that visited an app today. [shooed] = the user blocked it for this app (or for all apps). */
class SnoopVisit(val company: String, val category: String, val hits: Int, val shooed: Boolean)

/** One watched app as a creature, from call("terrarium"), call("garden") and today's stats. */
class Pet(
    val pkg: String, val label: String, val preset: String, val stage: Int, val stageCount: Int,
    val growth: Double, val next: Double?, val seed: String, val contactsToday: Int, val blockedToday: Int,
    val lastSeen: Long, val snoops: List<SnoopVisit>,
) {
    val visiting get() = snoops.filter { !it.shooed }
    /** Neglected: no contact for 3 hours -> the creature naps. */
    fun sleeping(now: Long) = lastSeen <= 0 || now - lastSeen > 3 * 3600_000L
}

private val SNOOP_KINDS = setOf("ads", "analytics", "fingerprinting", "social")

suspend fun loadPets(): List<Pet> {
    val organisms = Api.io("terrarium").optJSONArray("apps").objects()
    val garden = Api.io("garden").optJSONArray("apps").objects().associateBy { it.getString("package") }
    val rules = Api.io("rules").optJSONArray("rules").objects().filter { it.optString("scope") == "company" }
    val from = startOfToday()
    return organisms.map { o ->
        val pkg = o.getString("app_id")
        val g = garden[pkg]
        val today = Api.io("stats", obj("fn" to "appSummary", "package" to pkg, "from_ms" to from))
        val snoops = today.optJSONArray("trackers").objects()
            .filter { it.optString("company").isNotEmpty() && it.optString("category") in SNOOP_KINDS }
            .map { t ->
                val c = t.getString("company")
                SnoopVisit(c, t.optString("category"), t.optInt("hits"),
                    rules.any { it.optString("value").equals(c, true) && it.optString("package").let { p -> p == pkg || p == "*" } })
            }
        Pet(
            pkg, o.optString("label", pkg), o.optString("preset_id", "preset01"), o.optInt("stage", 1), o.optInt("stage_count", 3),
            o.optDouble("growth", 0.0), if (o.isNull("next_threshold")) null else o.optDouble("next_threshold"),
            g?.optString("seed").orEmpty().ifEmpty { pkg }, today.optInt("lookups"), today.optInt("blocked"),
            g?.optLong("last_seen") ?: 0L, snoops,
        )
    }
}

fun crc(s: String): Long = CRC32().apply { update(s.toByteArray()) }.value

/** Where a creature stands: feet at art pixel ([x], [y]). */
class Spot(val x: Int, val y: Int, val island: Int)

const val MAX_PETS = 12

/**
 * Deterministic places from each app's seed: free 40 px slots on every island (round-robin, so pets spread out),
 * the seed picks a preferred slot, collisions move to the next free one. The same apps always get the same places.
 */
fun placePets(world: World, pets: List<Pet>, focusX: Int, focusY: Int): Map<String, Spot> {
    val perIsland = world.surfaces.map { s ->
        val list = mutableListOf<Spot>()
        var x = s.x0 + 18
        while (x <= s.x1 - 18) {
            if (s.blocked.none { r -> x in (r.first - 12)..(r.last + 12) }) { list += Spot(x, s.y, s.island); x += 36 } else x += 4
        }
        list
    }
    // closest to the start view first, every island's first slot before any second slot
    val slots = perIsland.flatMap { list -> list.mapIndexed { n, spot -> n to spot } }
        .sortedWith(compareBy({ it.first }, { abs(it.second.x - focusX) + abs(it.second.y - focusY) / 2 })).map { it.second }
    if (slots.isEmpty()) return emptyMap()
    val taken = BooleanArray(slots.size)
    val out = LinkedHashMap<String, Spot>()
    for (p in pets.sortedBy { it.pkg }.take(MAX_PETS)) {
        // few pets stay near the top islands (in view at start); the seed picks among the first few slots
        var k = (crc(p.seed) % minOf(slots.size, pets.size + 1)).toInt()
        var tries = 0
        while (taken[k] && tries < slots.size) { k = (k + 1) % slots.size; tries++ }
        if (taken[k]) break
        taken[k] = true
        out[p.pkg] = slots[k]
    }
    return out
}

/** Where the n-th visiting Snoop hovers around a creature of [h] art px. */
fun snoopOffset(n: Int, h: Int): Pair<Int, Int> = when (n) {
    0 -> -16 to -h + 6
    1 -> 16 to -h
    else -> 0 to -h - 10
}
