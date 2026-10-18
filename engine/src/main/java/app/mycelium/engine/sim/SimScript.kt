package app.mycelium.engine.sim

import app.mycelium.engine.classify.Classifier
import app.mycelium.engine.classify.PacketTyper
import app.mycelium.engine.classify.RuleSet
import app.mycelium.engine.db.Row
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.TimeZone

/** A demo script from assets/sim/<name>.json and the event rows its lines become (always sim=1). */
class SimScript(val name: String, val apps: List<Pair<String, String>>, val events: List<Line>) {

    /** One script line: {dt_ms, package, domain, fg, kind, port?, ptype?, at_local?}. */
    data class Line(
        val dtMs: Long, val kind: String, val pkg: String?, val domain: String?, val fg: Int?,
        val port: Int?, val ptype: String?, val atLocal: String?,
    )

    val packages: Set<String> get() = apps.map { it.first }.toSet()

    /** Demo screen time: an app is on screen from its first fg=1 line until another app's fg=1 line (at_local lines skipped). */
    class FgTrack {
        private var pkg: String? = null
        private var start = 0L

        /** Session rows (package, start, end) to upsert after [line] ran at [ts]. */
        fun feed(line: Line, ts: Long): List<Triple<String, Long, Long>> {
            val p = line.pkg
            if (line.fg != 1 || p == null || line.atLocal != null) return emptyList()
            if (p == pkg) return listOf(Triple(p, start, ts))
            val out = end(ts)
            pkg = p; start = ts
            return out + Triple(p, ts, ts)
        }

        fun end(ts: Long): List<Triple<String, Long, Long>> = pkg?.let { listOf(Triple(it, start, ts)) } ?: emptyList()
    }

    companion object {
        const val TRIPWIRE_IP = "10.111.222.9"

        fun parse(json: String): SimScript {
            val o = Json.parseToJsonElement(json).jsonObject
            fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
            return SimScript(
                o.getValue("name").jsonPrimitive.content,
                o.getValue("apps").jsonArray.map { it.jsonObject.let { a -> a.s("package")!! to (a.s("label") ?: a.s("package")!!) } },
                o.getValue("events").jsonArray.map { e ->
                    val r = e.jsonObject
                    Line(
                        r["dt_ms"]?.jsonPrimitive?.longOrNull ?: 0, r.s("kind") ?: "dns", r.s("package")?.takeIf { it != "*" },
                        r.s("domain"), r["fg"]?.jsonPrimitive?.intOrNull, r["port"]?.jsonPrimitive?.intOrNull, r.s("ptype"), r.s("at_local"),
                    )
                },
            )
        }

        /**
         * The event row for [line], built the same way as live traffic: the real classifier and block rules, plus
         * the script's own blocks in [simRules] (a script never changes the user's rules), and the same PacketTyper.
         */
        fun row(
            line: Line, now: Long, tz: TimeZone, classify: (String?, String?, String) -> Classifier.Verdict,
            typer: PacketTyper, simRules: MutableList<RuleSet.Rule>,
        ): Row {
            val ts = line.atLocal?.let { at(it, now, tz) } ?: now
            return when (line.kind) {
                "block" -> {
                    val rule = RuleSet.Rule(RuleSet.SCOPE_DOMAIN, line.domain.orEmpty(), line.pkg ?: "*", ts)
                    simRules += rule
                    mapOf("ts" to ts, "kind" to "block", "package" to line.pkg, "domain" to line.domain,
                        "note" to "domain=${rule.value} package=${rule.pkg}", "sim" to 1)
                }
                "tripwire" -> mapOf(
                    "ts" to ts, "kind" to "tripwire", "ptype" to (line.ptype?.let(typer::known) ?: typer.type("tripwire", "tcp", line.port, null, null)),
                    "package" to line.pkg, "how" to "uid_tcp", "port" to line.port, "fg" to line.fg, "verdict" to "blocked", "sim" to 1,
                )
                else -> {
                    val v = classify(line.domain, line.pkg, "uid_dns")
                    val blocked = v.blockedBy ?: RuleSet(simRules).blocking(line.domain, v.company, line.pkg, "uid_dns")
                    val ptype = if (v.company != null) typer.type("dns", "udp", 53, line.domain, v.company)
                        else line.ptype?.let(typer::known) ?: typer.type("dns", "udp", 53, line.domain, null)
                    mapOf(
                        "ts" to ts, "kind" to "dns", "ptype" to ptype, "package" to line.pkg, "how" to "uid_dns",
                        "domain" to line.domain, "qtype" to 1, "company" to v.company, "category" to v.category,
                        "verdict" to if (blocked != null) "blocked" else "allowed", "fg" to line.fg,
                        "answer" to if (blocked != null) TRIPWIRE_IP else null, "sim" to 1,
                    )
                }
            }
        }

        /** "HH:mm[:ss]" today in [tz], or yesterday if that is still ahead of [now]. */
        fun at(hms: String, now: Long, tz: TimeZone): Long {
            val p = hms.split(':').map { it.toInt() }
            val c = Calendar.getInstance(tz).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, p[0]); set(Calendar.MINUTE, p.getOrElse(1) { 0 }); set(Calendar.SECOND, p.getOrElse(2) { 0 })
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > now) c.add(Calendar.DAY_OF_MONTH, -1)
            return c.timeInMillis
        }
    }
}
