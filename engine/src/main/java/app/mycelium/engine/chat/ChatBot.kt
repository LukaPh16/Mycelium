package app.mycelium.engine.chat

import app.mycelium.engine.attr.Attribution
import app.mycelium.engine.db.Row
import app.mycelium.engine.db.toJson
import app.mycelium.engine.stats.Query
import app.mycelium.engine.stats.Stats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Grounded template chatbot: Router -> stats (FACTS) -> TemplateWriter -> Validator. No model, no network.
 * Every number in an answer comes from FACTS; every claim about an app, company or time cites event rows.
 */
class ChatBot(
    private val q: Query,
    private val templates: Map<String, List<String>>,
    private val tz: TimeZone = TimeZone.getDefault(),
    /** Non-null while mode=simulate (the scripted packages). Otherwise sim=1 rows are invisible and uncitable. */
    private val simulating: () -> Set<String>? = { null },
    /** Screen time of any app straight from Android (package -> ms, "*" = whole phone); null without usage access. */
    private val liveUsage: (from: Long, to: Long) -> Map<String, Long>? = { _, _ -> null },
) {
    private val stats = Stats(q, tz)

    fun answer(text: String, apps: List<AppRef>, companies: Collection<String>, now: Long): JsonObject {
        val simPkgs = simulating()
        val p = Router.route(text, apps, companies, now, tz)
        val ex = Exec(p, now, Stats.Scope(simPackages = simPkgs), apps)
        val lines = ex.run()
        val sentences = TemplateWriter.render(lines, templates, text.trim().lowercase().hashCode())
        val labels = apps.associate { it.label.lowercase() to it.pkg }
        val v = Validator.validate(sentences, ex.facts, ::citedRows, labels, companies, tz, allowSim = simPkgs != null)
        val kept = v.kept.ifEmpty { TemplateWriter.render(listOf(Line("no_data")), templates, 0) }
        return buildJsonObject {
            put("sentences", JsonArray(kept.map { s ->
                buildJsonObject { put("text", s.text); put("cites", JsonArray(s.cites.map { JsonPrimitive(it) })) }
            }))
            // evidence_ids: every row the planned answer rests on, so a cloud model has ids to cite for any intent.
            put("facts", JsonObject(ex.facts + ("evidence_ids" to JsonArray(lines.flatMap { it.cites }.distinct().take(50).map { JsonPrimitive(it) }))))
            put("intent", p.intent)
            put("engine", "template")
            put("dropped", v.dropped)
            ex.suggestWatch?.let { put("suggest_watch", it) }
            ex.confirm?.let { put("confirm_action", it) }
        }
    }

    private fun citedRows(ids: List<Long>): Map<Long, Row> =
        if (ids.isEmpty()) emptyMap()
        else q("SELECT id, ts, package, company, sim FROM event WHERE id IN (${ids.joinToString(",")})", emptyList()).associateBy { it.l("id") }

    private inner class Exec(val p: Parsed, val now: Long, private val scope: Stats.Scope, private val apps: List<AppRef>) {
        var suggestWatch: String? = null
        var confirm: JsonObject? = null
        private val parts = LinkedHashMap<String, JsonElement>()
        private val derived = LinkedHashMap<String, JsonElement>()
        val facts: JsonObject get() = JsonObject(if (derived.isEmpty()) parts else parts + ("derived" to JsonObject(derived)))
        private val hm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
        private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }

        fun run(): List<Line> {
            parts["intent"] = JsonPrimitive(p.intent)
            p.app?.let { a ->
                parts["app"] = buildJsonObject { put("package", a.pkg); put("label", a.label); put("watched", a.watched); put("species", species(a.pkg)) }
            }
            p.domain?.let { parts["domain"] = JsonPrimitive(it) }
            p.company?.let { parts["company"] = JsonPrimitive(it) }
            val app = p.app
            if (app != null && !app.watched && p.intent in APP_INTENTS) return notWatched(app)
            return when (p.intent) {
                "limits" -> listOf(Line("limits"))
                "help" -> listOf(Line("help"), Line("help_examples"))
                "watch_app" -> watch(app, true)
                "unwatch_app" -> watch(app, false)
                "app_summary" -> appSummary(app!!)
                "screen_time" -> screenTime(app)
                "top_apps" -> topApps(p.by)
                "top_companies" -> topCompanies(app)
                "when_contacted" -> whenContacted(app)
                "what_happened_recently" -> recent()
                "night_report" -> night(app)
                "blocked_report" -> blocked(app)
                "bytes_report" -> if (app != null) bytes(app) else topApps("bytes")
                "compare" -> compare(app)
                "anomaly_report" -> anomalies(app)
                else -> listOf(Line("out_of_scope"))
            }.ifEmpty { listOf(Line("no_data")) }
        }

        // ---- intents ----

        private fun notWatched(app: AppRef): List<Line> {
            val s = slot(TimeSlot(now - 2 * D, now, "over the past two days", "48h"))
            val t = q(
                "SELECT COALESCE(SUM(rx_bytes),0) rx_bytes, COALESCE(SUM(tx_bytes),0) tx_bytes FROM traffic " +
                    "WHERE package=? AND start_ts<${s.to} AND end_ts>${s.from}",
                listOf(app.pkg),
            ).first()
            parts["data"] = t.toJson()
            suggestWatch = app.pkg
            val lines = mutableListOf(Line("not_watched", mapOf("label" to app.label)))
            val total = t.l("rx_bytes") + t.l("tx_bytes")
            if (total > 0) {
                val (amount, unit) = human(total, "total")
                lines += Line("not_watched_bytes", mapOf("amount" to amount, "unit" to unit, "period" to s.label))
            }
            lines += Line("not_watched_offer", mapOf("label" to app.label))
            return lines
        }

        private fun watch(app: AppRef?, watch: Boolean): List<Line> {
            if (app == null) return listOf(Line("watch_unknown"))
            val label = mapOf("label" to app.label)
            if (watch && app.watched) return listOf(Line("watch_already", label))
            if (!watch && !app.watched) return listOf(Line("unwatch_not_watched", label))
            confirm = buildJsonObject {
                put("method", if (watch) "watch" else "unwatch")
                put("packages", JsonArray(listOf(JsonPrimitive(app.pkg))))
            }
            return listOf(Line(if (watch) "watch_confirm" else "unwatch_confirm", label))
        }

        private fun appSummary(app: AppRef): List<Line> {
            val s = slot(pastDay())
            val r = stats.appSummary(app.pkg, s.from, s.to, scope)
            parts["data"] = r
            if (r.l("lookups") == 0L) return emptyList()
            val sp = species(app.pkg)
            val w = "kind='dns' AND package=? AND ts>=${s.from} AND ts<${s.to}${scope.sim}"
            val a = listOf(app.pkg)
            val lines = mutableListOf(Line("summary_lookups",
                mapOf("species" to sp, "lookups" to r.n("lookups"), "domains" to r.n("unique_domains"), "period" to s.label), ids(w, a)))
            if (r.l("tracker_hits") > 0) {
                val top = r.getValue("trackers").jsonArray[0].jsonObject
                val company = top.str("company")
                lines += Line("summary_trackers", mapOf(
                    "tracker_hits" to r.n("tracker_hits"), "companies" to r.n("companies"),
                    "top_company" to company, "top_hits" to top.n("hits"),
                ), ids("$w AND company=?", a + company))
            } else {
                lines += Line("summary_no_trackers", mapOf("species" to sp, "period" to s.label), ids(w, a, 3))
            }
            if (r.l("blocked") > 0) lines += Line("summary_blocked", mapOf("blocked" to r.n("blocked")), ids("$w AND verdict='blocked'", a))
            if (r.l("bg") > 0) lines += Line("summary_background", mapOf("bg" to r.n("bg"), "species" to sp), ids("$w AND fg=0", a))
            q("SELECT id, ts FROM event WHERE $w ORDER BY ts DESC, id DESC LIMIT 1", a).firstOrNull()?.let {
                lines += Line("summary_last", mapOf("species" to sp, "time" to hm.format(it.l("ts"))), listOf(it.l("id")))
            }
            return lines
        }

        private fun topApps(by: String): List<Line> {
            val s = slot(pastDay())
            val r = stats.topApps(s.from, s.to, by, 3, scope)
            parts["data"] = r
            val unit = mapOf("lookups" to "lookups", "trackers" to "tracker lookups", "background" to "background lookups")
            val cond = mapOf("lookups" to "", "trackers" to " AND company IS NOT NULL", "background" to " AND fg=0")
            return r.getValue("apps").jsonArray.mapIndexed { i, e ->
                val a = e.jsonObject
                val pkg = a.str("package")
                val (value, u) = if (by == "bytes") human(a.long("value"), "app${i}_bytes") else a.n("value") to unit.getValue(by)
                val cites = if (by == "bytes") emptyList()
                    else ids("kind='dns' AND package=? AND ts>=${s.from} AND ts<${s.to}${scope.sim}${cond.getValue(by)}", listOf(pkg))
                Line(if (i == 0) "top_apps_lead" else "top_apps_next",
                    mapOf("species" to species(pkg), "value" to value, "unit" to u, "period" to s.label), cites)
            }
        }

        private fun topCompanies(app: AppRef?): List<Line> {
            val s = slot(pastDay())
            val r = if (app != null) stats.appSummary(app.pkg, s.from, s.to, scope) else stats.topCompanies(s.from, s.to, 3, scope)
            parts["data"] = r
            val list = r.getValue(if (app != null) "trackers" else "companies").jsonArray.map { it.jsonObject }.take(3)
            val base = "kind='dns' AND company=? AND ts>=${s.from} AND ts<${s.to}${scope.sim}" + if (app != null) " AND package=?" else scope.watched()
            return list.mapIndexed { i, c ->
                val company = c.str("company")
                val key = when { i > 0 -> "companies_next"; app != null -> "companies_app_lead"; else -> "companies_lead" }
                val vars = mutableMapOf("company" to company, "hits" to c.n("hits"), "period" to s.label)
                if (app != null) vars["species"] = species(app.pkg) else vars["apps"] = c.n("apps")
                Line(key, vars, ids(base, listOfNotNull(company, app?.pkg)))
            }
        }

        private fun whenContacted(app: AppRef?): List<Line> {
            val s = slot(TimeSlot(now - 7 * D, now, "this week", "week"))
            val domain = p.domain
            val target = domain ?: p.company ?: return emptyList()
            val (cond, args) = if (domain != null) "(domain=? OR domain LIKE ? ESCAPE '\\')" to listOf(domain, "%." + like(domain))
                else "company=?" to listOf(target)
            val w = "kind='dns' AND $cond AND ts>=${s.from} AND ts<${s.to}${scope.sim}" + if (app != null) " AND package=?" else scope.watched()
            val wa = args + listOfNotNull(app?.pkg)
            val rows = q("SELECT package, COUNT(*) hits, MIN(ts) first_ts, MAX(ts) last_ts FROM event WHERE $w GROUP BY package ORDER BY hits DESC LIMIT 3", wa)
            parts["data"] = JsonArray(rows.map { it.toJson() })
            if (rows.isEmpty()) return listOf(Line("when_none", mapOf("target" to target, "period" to s.label)))
            return rows.map { r ->
                val pkg = r["package"] as String?
                val pw = if (pkg != null) "$w AND package=?" else "$w AND package IS NULL"
                val pa = wa + listOfNotNull(pkg)
                val first = q("SELECT id FROM event WHERE $pw ORDER BY ts, id LIMIT 1", pa).first().l("id")
                val last = q("SELECT id FROM event WHERE $pw ORDER BY ts DESC, id DESC LIMIT 1", pa).first().l("id")
                val sp = speciesOrUnknown(pkg)
                if (r.l("hits") == 1L) Line("when_once", mapOf("species" to sp, "target" to target, "period" to s.label, "time" to hm.format(r.l("first_ts"))), listOf(first))
                else Line("when_app", mapOf(
                    "species" to sp, "target" to target, "hits" to r.n("hits"), "period" to s.label,
                    "first_time" to hm.format(r.l("first_ts")), "last_time" to hm.format(r.l("last_ts")),
                ), listOf(first, last))
            }
        }

        private fun recent(): List<Line> {
            val s = slot(TimeSlot(now - Stats.HOUR, now, "in the last hour", "hour"))
            val w = "ts>=${s.from} AND ts<${s.to} AND kind IN ('dns','tripwire','anomaly','block')${scope.events}"
            val total = q("SELECT COUNT(*) n FROM event WHERE $w", emptyList()).first().l("n")
            val rows = q("SELECT id, ts, kind, package, domain, company, verdict, port, category FROM event WHERE $w ORDER BY ts DESC, id DESC LIMIT 5", emptyList()).reversed()
            parts["data"] = buildJsonObject { put("total", total); put("events", JsonArray(rows.map { it.toJson() })) }
            if (rows.isEmpty()) return emptyList()
            val lines = mutableListOf<Line>()
            if (total > rows.size) lines += Line("recent_count", mapOf("n" to total.toString(), "period" to s.label), rows.map { it.l("id") })
            for (r in rows) {
                val v = mapOf("time" to hm.format(r.l("ts")), "species" to speciesOrUnknown(r["package"] as String?), "domain" to (r["domain"] as String? ?: ""))
                val cites = listOf(r.l("id"))
                lines += when (r["kind"]) {
                    "tripwire" -> Line("recent_tripwire", v + ("port" to r.n("port")), cites)
                    "anomaly" -> Line("recent_anomaly", v + ("what" to ruleText(r["category"] as String?, r["company"] as String?)), cites)
                    "block" -> Line("recent_block", v + ("target" to (r["domain"] as String? ?: r["company"] as String? ?: "")), cites)
                    else -> when {
                        r["verdict"] == "blocked" -> Line("recent_dns_blocked", v, cites)
                        r["company"] != null -> Line("recent_dns_tracker", v + ("company" to r["company"] as String), cites)
                        else -> Line("recent_dns", v, cites)
                    }
                }
            }
            return lines
        }

        private fun night(app: AppRef?): List<Line> {
            val t = p.time
            val date = day.format(if (t?.kind == "yesterday") t.from else Router.startOfDay(now, tz))
            val r = stats.nightReport(date, scope)
            parts["data"] = r
            if (r.l("lookups") == 0L) return listOf(Line("night_quiet", mapOf("date" to date)))
            val lines = mutableListOf(Line("night_total",
                mapOf("date" to date, "lookups" to r.n("lookups"), "bg" to r.n("background_lookups")),
                r.getValue("event_ids").jsonArray.take(5).map { it.jsonPrimitive.long }))
            val apps = r.getValue("apps").jsonArray.map { it.jsonObject }.filter { app == null || it.str("package") == app.pkg }
            for (a in apps.take(3)) {
                val pkg = a.str("package")
                lines += Line("night_app", mapOf("species" to species(pkg), "lookups" to a.n("lookups"), "tracker_hits" to a.n("tracker_hits")),
                    ids("kind='dns' AND fg=0 AND package=? AND ts>=${r.l("from_ms")} AND ts<${r.l("to_ms")}${scope.sim}", listOf(pkg)))
            }
            return lines
        }

        private fun blocked(app: AppRef?): List<Line> {
            val s = slot(pastDay())
            val w = "kind='dns' AND verdict='blocked' AND ts>=${s.from} AND ts<${s.to}${scope.sim}" + if (app != null) " AND package=?" else scope.watched()
            val a = listOfNotNull(app?.pkg)
            val total = q("SELECT COUNT(*) n FROM event WHERE $w", a).first().l("n")
            val rows = q("SELECT package, domain, COUNT(*) n, MAX(id) last_id FROM event WHERE $w GROUP BY package, domain ORDER BY n DESC, last_id DESC LIMIT 3", a)
            parts["data"] = buildJsonObject { put("total", total); put("items", JsonArray(rows.map { it.toJson() })) }
            if (total == 0L) return listOf(Line("blocked_none", mapOf("period" to s.label)))
            return listOf(Line("blocked_total", mapOf("n" to total.toString(), "period" to s.label), ids(w, a))) + rows.map { r ->
                Line("blocked_item", mapOf("species" to speciesOrUnknown(r["package"] as String?), "domain" to (r["domain"] as String? ?: ""), "n" to r.n("n")),
                    listOf(r.l("last_id")))
            }
        }

        /** Byte totals come from the traffic table (NetworkStatsManager), which has no event ids to cite. */
        private fun bytes(app: AppRef): List<Line> {
            val s = slot(pastDay())
            val t = q(
                "SELECT COALESCE(SUM(rx_bytes),0) rx_bytes, COALESCE(SUM(tx_bytes),0) tx_bytes FROM traffic " +
                    "WHERE package=? AND start_ts<${s.to} AND end_ts>${s.from}",
                listOf(app.pkg),
            ).first()
            parts["data"] = t.toJson()
            val total = t.l("rx_bytes") + t.l("tx_bytes")
            if (total == 0L) return emptyList()
            val (ta, tu) = human(total, "total")
            val (ra, ru) = human(t.l("rx_bytes"), "rx")
            val (xa, xu) = human(t.l("tx_bytes"), "tx")
            return listOf(Line("bytes_app", mapOf(
                "species" to species(app.pkg), "total_amount" to ta, "total_unit" to tu, "rx_amount" to ra, "rx_unit" to ru,
                "tx_amount" to xa, "tx_unit" to xu, "period" to s.label,
            )))
        }

        /**
         * Foreground time of one app (any app, watched or not) or the top apps, with the share of the whole phone.
         * Watched apps and demo apps come from the session table; others are asked of Android live, never stored.
         */
        private fun screenTime(app: AppRef?): List<Line> {
            val s = slot(TimeSlot(Router.startOfDay(now, tz), now, "today", "today"))
            val db = stats.screenTime(s.from, s.to, scope)
            val dbMs = db.getValue("apps").jsonArray.associate { it.jsonObject.str("package") to it.jsonObject.l("ms") }
            val live = liveUsage(s.from, s.to)
            fun ms(pkg: String) = if (scope.simPackages?.contains(pkg) == true || live == null) dbMs[pkg] ?: 0L else live[pkg] ?: dbMs[pkg] ?: 0L
            val total = maxOf(live?.get("*") ?: 0L, db.l("device_total_ms"), db.l("total_ms"))
            parts["data"] = buildJsonObject {
                put("source", if (live != null) "android_usage_stats" else "watched_apps_only")
                put("phone_total_ms", total)
                app?.let { put("package", it.pkg); put("ms", ms(it.pkg)) }
            }
            if (app != null) {
                val m = ms(app.pkg)
                if (m == 0L) return listOf(Line(if (live == null && !app.watched) "screen_no_access" else "screen_app_none", mapOf("label" to app.label, "period" to s.label)))
                if (total == 0L) return listOf(Line("screen_app_nototal", mapOf("label" to app.label, "duration" to dur(m, "app"), "period" to s.label)))
                return listOf(Line("screen_app", mapOf("label" to app.label, "duration" to dur(m, "app"), "period" to s.label,
                    "pct" to pct(m, total, "app"), "total" to dur(total, "total"))))
            }
            if (total == 0L) return listOf(Line(if (live == null) "screen_no_access" else "screen_none", mapOf("label" to "your phone", "period" to s.label)))
            val labels = apps.associate { it.pkg to it.label }
            val all = (if (live != null) live.keys - "*" else emptySet()) + dbMs.keys
            val top = all.map { it to ms(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(3)
            parts["data"] = (parts["data"] as JsonObject).let { d ->
                JsonObject(d + ("top" to JsonArray(top.map { (p, m) -> buildJsonObject { put("package", p); put("label", labels[p] ?: p); put("ms", m) } })))
            }
            return listOf(Line("screen_total", mapOf("duration" to dur(total, "total"), "period" to s.label))) +
                top.mapIndexed { i, (pkg, m) ->
                    Line("screen_top", mapOf("label" to (labels[pkg] ?: pkg.substringAfterLast('.')), "duration" to dur(m, "top$i"), "pct" to pct(m, total, "top$i")))
                }
        }

        /** "1 h 12 min" / "18 min" / "under a minute"; the shown numbers go into FACTS.derived for the validator. */
        private fun dur(ms: Long, key: String): String {
            val h = ms / Stats.HOUR
            val m = (ms % Stats.HOUR) / 60_000
            derived["${key}_hours"] = JsonPrimitive(h)
            derived["${key}_minutes"] = JsonPrimitive(m)
            return when {
                h > 0 && m > 0 -> "$h h $m min"
                h > 0 -> "$h h"
                m > 0 -> "$m min"
                else -> "under a minute"
            }
        }

        private fun pct(part: Long, total: Long, key: String): String {
            val v = part * 100 / total
            derived["${key}_pct"] = JsonPrimitive(maxOf(v, 1))
            return if (v < 1) "under 1%" else "$v%"
        }

        private fun compare(app: AppRef?): List<Line> {
            val midnight = Router.startOfDay(now, tz)
            val (p1, p2, labels) = when (p.time?.kind) {
                "week" -> Triple(now - 14 * D to now - 7 * D, now - 7 * D to now, "the week before" to "this week")
                "hour" -> Triple(now - 2 * Stats.HOUR to now - Stats.HOUR, now - Stats.HOUR to now, "the hour before" to "in the last hour")
                else -> Triple(midnight - D to now - D, midnight to now, "yesterday up to the same time" to "today")
            }
            parts["time"] = buildJsonObject {
                put("period1", buildJsonObject { put("from_ms", p1.first); put("to_ms", p1.second); put("label", labels.first) })
                put("period2", buildJsonObject { put("from_ms", p2.first); put("to_ms", p2.second); put("label", labels.second) })
            }
            val vars = mapOf("period1" to labels.first, "period2" to labels.second)
            if (app != null) {
                val r = stats.compare(app.pkg, p1, p2, scope)
                parts["data"] = r
                val a = r.getValue("period1").jsonObject
                val b = r.getValue("period2").jsonObject
                if (a.l("lookups") == 0L && b.l("lookups") == 0L) return emptyList()
                val cites = r.getValue("event_ids").jsonArray.take(6).map { it.jsonPrimitive.long }
                val lines = mutableListOf(
                    Line("compare_app", vars + mapOf("species" to species(app.pkg), "n1" to a.n("lookups"), "n2" to b.n("lookups")), cites),
                    Line("compare_trackers", mapOf("t1" to a.n("tracker_hits"), "t2" to b.n("tracker_hits")), cites),
                )
                val fresh = r.getValue("new_companies").jsonArray.map { it.jsonPrimitive.content }.take(3)
                if (fresh.isNotEmpty()) {
                    val ids = fresh.flatMap { c ->
                        ids("kind='dns' AND package=? AND company=? AND ts>=${p2.first} AND ts<${p2.second}${scope.sim}", listOf(app.pkg, c), 1)
                    }
                    lines += Line("compare_new", vars + ("list" to fresh.joinToString(", ")), ids)
                }
                return lines
            }
            fun totals(p: Pair<Long, Long>) = q(
                "SELECT COUNT(*) lookups, COALESCE(SUM(company IS NOT NULL),0) tracker_hits FROM event " +
                    "WHERE kind='dns' AND ts>=${p.first} AND ts<${p.second}${scope.events}",
                emptyList(),
            ).first()
            val a = totals(p1)
            val b = totals(p2)
            parts["data"] = buildJsonObject { put("period1", a.toJson()); put("period2", b.toJson()) }
            if (a.l("lookups") == 0L && b.l("lookups") == 0L) return emptyList()
            val cites = listOf(p2, p1).flatMap { p -> ids("kind='dns' AND ts>=${p.first} AND ts<${p.second}${scope.events}", emptyList(), 3) }
            return listOf(
                Line("compare_all", vars + mapOf("n1" to a.n("lookups"), "n2" to b.n("lookups")), cites),
                Line("compare_all_trackers", mapOf("t1" to a.n("tracker_hits"), "t2" to b.n("tracker_hits")), cites),
            )
        }

        private fun anomalies(app: AppRef?): List<Line> {
            val s = slot(pastDay())
            val w = "kind='anomaly' AND ts>=${s.from} AND ts<${s.to}${scope.sim}" + if (app != null) " AND package=?" else scope.watched()
            val rows = q("SELECT id, ts, package, category, company, note FROM event WHERE $w ORDER BY ts DESC, id DESC LIMIT 5", listOfNotNull(app?.pkg))
            parts["data"] = JsonArray(rows.map { it.toJson() })
            if (rows.isEmpty()) return listOf(Line("anomaly_none", mapOf("period" to s.label)))
            return rows.reversed().map { r ->
                val evidence = (r["note"] as String?).orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.take(3)
                Line("anomaly_item", mapOf(
                    "time" to hm.format(r.l("ts")), "species" to speciesOrUnknown(r["package"] as String?),
                    "what" to ruleText(r["category"] as String?, r["company"] as String?),
                ), listOf(r.l("id")) + evidence)
            }
        }

        // ---- helpers ----

        private fun pastDay() = TimeSlot(now - D, now, "over the past day", "24h")

        private fun slot(default: TimeSlot): TimeSlot {
            val s = p.time ?: default
            parts["time"] = buildJsonObject {
                put("from_ms", s.from); put("to_ms", s.to); put("label", s.label); s.atMs?.let { put("at_ms", it) }
            }
            return s
        }

        private fun ids(where: String, args: List<String>, limit: Int = 5): List<Long> =
            q("SELECT id FROM event WHERE $where ORDER BY ts DESC, id DESC LIMIT $limit", args).map { it.l("id") }

        /** "<label> <kingdom>"; the kingdom comes from the seed's low two bits. */
        fun species(pkg: String): String {
            val row = q("SELECT label, seed FROM app WHERE package=?", listOf(pkg)).firstOrNull()
            val label = row?.get("label") as String? ?: pkg.substringAfterLast('.')
            val seed = row?.get("seed") as String? ?: Attribution.seed(pkg)
            return "$label ${KINGDOMS[(seed.take(2).toIntOrNull(16) ?: 0) and 3]}"
        }

        private fun speciesOrUnknown(pkg: String?) = if (pkg == null) "an unidentified creature" else species(pkg)

        /** Human byte amount; the shown number is also stored in FACTS.derived so the validator can find it. */
        private fun human(bytes: Long, key: String): Pair<String, String> {
            val (value, unit) = when {
                bytes >= 1L shl 30 -> Math.round(bytes / 1073741824.0 * 10) / 10.0 to "GB"
                bytes >= 1L shl 20 -> Math.round(bytes / 1048576.0 * 10) / 10.0 to "MB"
                bytes >= 1024 -> bytes / 1024 to "KB"
                else -> bytes to "bytes"
            }
            derived[key] = JsonPrimitive(value)
            derived[key + "_unit"] = JsonPrimitive(unit)
            return (if (value is Double) "%.1f".format(Locale.US, value) else value.toString()) to unit
        }

        private fun ruleText(rule: String?, company: String?) = when (rule) {
            "new_domains_burst" -> "a burst of never-seen domains"
            "night_background" -> "background lookups in the small hours"
            "new_company" -> "a first contact with ${company ?: "a new company"}"
            "tripwire_retry" -> "repeated knocks on the tripwire after a block"
            else -> "unusual behaviour"
        }

        private fun like(d: String) = d.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }

    private fun Row.l(k: String) = (this[k] as Number?)?.toLong() ?: 0L
    private fun Row.n(k: String) = l(k).toString()
    private fun JsonObject.l(k: String) = this[k]?.jsonPrimitive?.long ?: 0L
    private fun JsonObject.long(k: String) = l(k)
    private fun JsonObject.n(k: String) = l(k).toString()
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    companion object {
        private const val D = Stats.DAY
        val KINGDOMS = listOf("fern", "mushroom", "coral", "vine")
        private val APP_INTENTS = setOf(
            "app_summary", "top_companies", "when_contacted", "night_report", "blocked_report", "bytes_report", "compare", "anomaly_report",
        )
    }
}
