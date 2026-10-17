package app.mycelium.engine.classify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Packet types from assets/config/packet_types.json; ids, colors, protocols and ports all come from that file.
 * Precedence: tracker (domain classified as a tracker, any protocol) > the most specific matching protocol rule
 * (named host > ports > protocol; ties by file order) > "other".
 */
class PacketTyper(private val types: List<Def>, val neutral: String, private val dohHosts: Set<String>) {
    class Def(
        val id: String, val color: String,
        val protos: Set<String>?, val ports: List<IntRange>?, val dohHost: Boolean, val tracker: Boolean, val source: String?,
    ) {
        val rule get() = protos != null || ports != null || dohHost || tracker || source != null
        val specificity get() = (if (dohHost) 8 else 0) + (if (ports != null) 4 else 0) + (if (source != null) 4 else 0) + (if (protos != null) 2 else 0)
    }

    /** All ids in file order (legend order). */
    val ids: List<String> = types.map { it.id }.let { if (OTHER in it) it else it + OTHER }

    /** Color for an id; unknown ids get the neutral color. */
    fun color(id: String?): String = types.firstOrNull { it.id == id }?.color ?: neutral

    /** A configured id, else "other". */
    fun known(id: String?) = if (id != null && id in ids) id else OTHER

    /** [company] is non-null only for domains on the tracker list, so it marks a tracker. */
    fun type(kind: String?, protocol: String?, port: Int?, domain: String?, company: String?, source: String? = null): String {
        if (company != null) types.firstOrNull { it.tracker }?.let { return it.id }
        val proto = protocol?.lowercase()
        val host = domain?.let(::normalizeDomain)
        return types.filter { d ->
            d.rule && !d.tracker &&
                (d.source == null || d.source == source) &&
                (d.source != null || source == null) &&
                (d.protos == null || proto in d.protos) &&
                (d.ports == null || (port != null && d.ports.any { port in it })) &&
                (!d.dohHost || (host != null && suffixes(host).any { it in dohHosts }))
        }.maxByOrNull { it.specificity }?.id ?: OTHER
    }

    companion object {
        const val OTHER = "other"

        fun parse(json: String): PacketTyper {
            val o = Json.parseToJsonElement(json).jsonObject
            val defs = o.getValue("types").jsonArray.map { t ->
                val d = t.jsonObject
                val m = d["match"]?.jsonObject ?: JsonObject(emptyMap())
                Def(
                    d.getValue("id").jsonPrimitive.content,
                    d["color"]?.jsonPrimitive?.contentOrNull ?: "",
                    m["proto"]?.jsonArray?.map { it.jsonPrimitive.content.lowercase() }?.toSet(),
                    m["ports"]?.jsonArray?.map { range(it.jsonPrimitive.content) },
                    m["doh_host"]?.jsonPrimitive?.booleanOrNull == true,
                    m["tracker"]?.jsonPrimitive?.booleanOrNull == true,
                    m["source"]?.jsonPrimitive?.contentOrNull,
                )
            }
            val neutral = o["neutral"]?.jsonPrimitive?.contentOrNull ?: "#9AA0A6"
            val doh = o["doh_hosts"]?.jsonArray?.map { normalizeDomain(it.jsonPrimitive.content) }?.toSet().orEmpty()
            return PacketTyper(defs, neutral, doh)
        }

        /** "443" or "5228-5230". */
        private fun range(s: String): IntRange {
            val p = s.split('-').map { it.trim().toInt() }
            return p[0]..(p.getOrNull(1) ?: p[0])
        }
    }
}
