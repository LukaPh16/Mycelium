package app.mycelium.app.garden

import app.mycelium.app.Api
import app.mycelium.app.obj
import app.mycelium.app.objects
import app.mycelium.app.startOfToday
import java.util.zip.CRC32

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

    /**
     * How sick the trackers made it today, 0..3: from the share of contacts that went to Snoops still visiting, and
     * how many of them there are. Shooing a Snoop away lowers it (healing).
     */
    val infection: Int
        get() {
            val v = visiting
            if (v.isEmpty()) return 0
            val share = v.sumOf { it.hits }.toFloat() / maxOf(1, contactsToday)
            return when {
                share > 0.3f || v.size >= 3 -> 3
                share > 0.12f || v.size == 2 -> 2
                else -> 1
            }
        }

    /** Colors come with the creature type (what you see in the picker is what you get); the hat comes from the app's seed. */
    fun look(atlas: SpriteAtlas): String = lookForPreset(atlas, preset)
    fun hat(atlas: SpriteAtlas): String? = (crc(seed + "hat") % (atlas.hats.size + 2)).toInt().let { atlas.hats.getOrNull(it) }
}

/** Each creature type has its own colors, so types are easy to tell apart (preset01 -> look 1, preset02 -> look 2, ...). */
fun lookForPreset(atlas: SpriteAtlas, presetId: String): String {
    val n = presetId.filter { it.isDigit() }.toIntOrNull() ?: crc(presetId).toInt()
    return atlas.looks.getOrElse(Math.floorMod(n * 3, maxOf(1, atlas.looks.size))) { "" }
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

const val MAX_PETS = 16

/**
 * Deterministic places from each app's seed among the scene's slots (the layout lists the nicest spots first);
 * the seed picks a preferred slot, collisions move to the next free one. The same apps always get the same places.
 */
fun placePets(layout: Layout, pets: List<Pet>, moved: Map<String, Spot> = emptyMap()): Map<String, Spot> {
    val slots = layout.slots
    if (slots.isEmpty()) return emptyMap()
    val shown = pets.sortedBy { it.pkg }.take(MAX_PETS)
    val out = LinkedHashMap<String, Spot>()
    for (p in shown) moved[p.pkg]?.let { out[p.pkg] = it }   // spots the user dragged creatures to win
    val taken = BooleanArray(slots.size) { i -> out.values.any { s -> kotlin.math.abs(s.x - slots[i].x) < 18 && kotlin.math.abs(s.y - slots[i].y) < 12 } }
    for (p in shown) {
        if (p.pkg in out) continue
        var k = (crc(p.seed) % minOf(slots.size, pets.size + 1)).toInt()
        var tries = 0
        while (taken[k] && tries < slots.size) { k = (k + 1) % slots.size; tries++ }
        if (taken[k]) break
        taken[k] = true
        out[p.pkg] = slots[k]
    }
    return out
}

