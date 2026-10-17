package app.mycelium.engine.classify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** "a.b.example.com" -> a.b.example.com, b.example.com, example.com, com. Lowercase, no trailing dot. */
fun suffixes(domain: String): Sequence<String> {
    val d = normalizeDomain(domain)
    return generateSequence(d) { s -> s.indexOf('.').takeIf { it >= 0 }?.let { s.substring(it + 1) } }
        .filter { it.isNotEmpty() }
}

fun normalizeDomain(domain: String) = domain.trim().trimEnd('.').lowercase()

/** Tracker list from assets/trackers.json. Lookup walks up the labels: a few HashMap probes. */
class Trackers(private val map: Map<String, Entry>) {
    data class Entry(val company: String, val category: String)
    data class Match(val suffix: String, val company: String, val category: String)

    val size get() = map.size
    val companies: Set<String> by lazy { map.values.map { it.company }.toSortedSet() }

    fun match(domain: String): Match? {
        for (s in suffixes(domain)) map[s]?.let { return Match(s, it.company, it.category) }
        return null
    }

    companion object {
        val TRACKER_CATEGORIES = setOf("ads", "analytics", "fingerprinting", "social")

        fun parse(json: String): Trackers {
            val map = HashMap<String, Entry>()
            for ((k, v) in Json.parseToJsonElement(json).jsonObject) {
                val o = v.jsonObject
                map[normalizeDomain(k)] = Entry(o.getValue("company").jsonPrimitive.content, o.getValue("category").jsonPrimitive.content)
            }
            return Trackers(map)
        }
    }
}
