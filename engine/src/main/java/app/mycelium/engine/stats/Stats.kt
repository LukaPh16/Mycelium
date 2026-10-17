package app.mycelium.engine.stats

import app.mycelium.engine.classify.normalizeDomain
import app.mycelium.engine.db.Row
import app.mycelium.engine.db.toJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Calendar
import java.util.TimeZone

/** Runs one SELECT with string args and returns rows (Long/Double/String/null values). */
typealias Query = (sql: String, args: List<String>) -> List<Row>

/**
 * Read-only stats over event / traffic / app. Numbers are inlined as SQL literals (they are Kotlin
 * Longs); only strings are bound. Every result carries `event_ids` (max 50) it is based on.
 */
class Stats(
    private val q: Query,
    private val tz: TimeZone = TimeZone.getDefault(),
    /** Non-null while mode=simulate: the scripted packages. */
    private val simulating: () -> Set<String>? = { null },
) {

    /**
     * Default: watched apps only, real (non-simulated) rows only. While simulating ([simPackages] non-null), sim rows
     * are included and the scripted packages count as watched for display (app.watched is untouched).
     */
    data class Scope(val includeUnwatched: Boolean = false, val includeSim: Boolean = false, val simPackages: Set<String>? = null) {
        val sim get() = if (includeSim || simPackages != null) "" else " AND sim=0"
        fun watched(col: String = "package") =
            if (includeUnwatched) "" else " AND ($col IS NULL OR $col IN (SELECT package FROM app WHERE watched=1)${simIn(col)})"
        val events get() = sim + watched()
        fun simIn(col: String) = simPackages?.takeIf { it.isNotEmpty() }
            ?.joinToString(",", prefix = " OR $col IN (", postfix = ")") { "'" + it.replace("'", "''") + "'" } ?: ""
    }

    // ---- dispatch for call("stats", {fn, ...}) ----

    fun call(fn: String, a: JsonObject, now: Long): JsonObject {
        val s = Scope(a.bool("include_unwatched"), a.bool("include_sim"), simulating())
        val to = a.long("to_ms") ?: now
        val from = a.long("from_ms") ?: (to - DAY)
        return when (fn) {
            "appSummary" -> appSummary(a.req("package"), from, to, s)
            "topApps" -> topApps(from, to, a.str("by") ?: "lookups", a.int("limit") ?: 10, s)
            "topCompanies" -> topCompanies(from, to, a.int("limit") ?: 10, s)
            "timeline" -> timeline(from, to, a.long("bucket_ms") ?: HOUR, a.str("package"), s)
            "domainHistory" -> domainHistory(a.req("domain"), from, to, s)
            "lastEvents" -> lastEvents(a.str("package"), a.str("domain"), a.int("limit") ?: 20, s)
            "nightReport" -> nightReport(a.req("date"), s)
            "compare" -> compare(a.req("package"), period(a, "period1"), period(a, "period2"), s)
            else -> throw IllegalArgumentException("unknown_fn")
        }
    }

    // ---- stats functions ----

    fun appSummary(pkg: String, from: Long, to: Long, s: Scope = Scope()): JsonObject {
        val w = "package=? AND ts>=$from AND ts<$to${s.sim}"
        val app = q("SELECT label, watched FROM app WHERE package=?", listOf(pkg)).firstOrNull()
        val companies = q(
            "SELECT company, category, COUNT(*) hits, MAX(ts) last_ts FROM event " +
                "WHERE kind='dns' AND company IS NOT NULL AND $w GROUP BY company ORDER BY hits DESC, company",
            listOf(pkg),
        )
        return buildJsonObject {
            put("fn", "appSummary"); put("package", pkg)
            put("label", app?.get("label") as String? ?: pkg); put("watched", app.long("watched") == 1L)
            put("from_ms", from); put("to_ms", to)
            core(pkg, from, to, s).toJson().forEach { (k, v) -> put(k, v) }
            put("trackers", rows(companies))
            put("event_ids", ids("SELECT id FROM event WHERE kind IN ('dns','tripwire') AND $w ORDER BY id DESC LIMIT 50", listOf(pkg)))
        }
    }

    fun topApps(from: Long, to: Long, by: String, limit: Int, s: Scope = Scope()): JsonObject {
        val n = limit.coerceIn(1, 100)
        val label = "(SELECT label FROM app WHERE app.package=x.package) label"
        val top: List<Row>
        var idList = JsonArray(emptyList())
        if (by == "bytes") {
            top = q(
                "SELECT x.package, $label, SUM(x.rx_bytes + x.tx_bytes) value, SUM(x.rx_bytes) rx_bytes, SUM(x.tx_bytes) tx_bytes, " +
                    "SUM(x.rx_packets + x.tx_packets) packets FROM traffic x " +
                    "WHERE x.start_ts<$to AND x.end_ts>$from${s.watched("x.package")} GROUP BY x.package ORDER BY value DESC, x.package LIMIT $n",
                emptyList(),
            )
        } else {
            val cond = when (by) {
                "lookups" -> ""
                "trackers" -> " AND company IS NOT NULL"
                "background" -> " AND fg=0"
                else -> throw IllegalArgumentException("bad_by")
            }
            val w = "kind='dns' AND package IS NOT NULL AND ts>=$from AND ts<$to$cond${s.events}"
            top = q("SELECT x.package, $label, COUNT(*) value FROM event x WHERE $w GROUP BY x.package ORDER BY value DESC, x.package LIMIT $n", emptyList())
            val pkgs = top.map { it["package"] as String }
            if (pkgs.isNotEmpty()) idList = ids("SELECT id FROM event WHERE $w AND package IN (${marks(pkgs)}) ORDER BY id DESC LIMIT 50", pkgs)
        }
        return buildJsonObject {
            put("fn", "topApps"); put("by", by); put("from_ms", from); put("to_ms", to)
            put("apps", rows(top))
            put("event_ids", idList)
        }
    }

    fun topCompanies(from: Long, to: Long, limit: Int, s: Scope = Scope()): JsonObject {
        val w = "kind='dns' AND company IS NOT NULL AND ts>=$from AND ts<$to${s.events}"
        val top = q(
            "SELECT company, MAX(category) category, COUNT(*) hits, COUNT(DISTINCT package) apps, " +
                "GROUP_CONCAT(DISTINCT package) packages, MAX(ts) last_ts FROM event WHERE $w " +
                "GROUP BY company ORDER BY hits DESC, company LIMIT ${limit.coerceIn(1, 100)}",
            emptyList(),
        )
        val names = top.map { it["company"] as String }
        return buildJsonObject {
            put("fn", "topCompanies"); put("from_ms", from); put("to_ms", to)
            put("companies", rows(top))
            put("event_ids", if (names.isEmpty()) JsonArray(emptyList())
                else ids("SELECT id FROM event WHERE $w AND company IN (${marks(names)}) ORDER BY id DESC LIMIT 50", names))
        }
    }

    fun timeline(from: Long, to: Long, bucketMs: Long, pkg: String?, s: Scope = Scope()): JsonObject {
        require(to > from) { "bad_range" }
        require(bucketMs >= 60_000) { "bad_bucket" }
        val n = ((to - from + bucketMs - 1) / bucketMs).toInt()
        require(n <= 1000) { "too_many_buckets" }
        val w = "kind='dns' AND ts>=$from AND ts<$to${s.sim}" + if (pkg != null) " AND package=?" else s.watched()
        val args = listOfNotNull(pkg)
        val byBucket = q(
            "SELECT (ts - $from) / $bucketMs b, COUNT(*) lookups, SUM(company IS NOT NULL) tracker_hits, " +
                "SUM(verdict='blocked') blocked, SUM(fg=0) bg FROM event WHERE $w GROUP BY b",
            args,
        ).associateBy { it.long("b").toInt() }
        return buildJsonObject {
            put("fn", "timeline"); put("from_ms", from); put("to_ms", to); put("bucket_ms", bucketMs)
            pkg?.let { put("package", it) }
            put("buckets", JsonArray((0 until n).map { i ->
                val r = byBucket[i]
                buildJsonObject {
                    put("start_ms", from + i * bucketMs)
                    for (k in listOf("lookups", "tracker_hits", "blocked", "bg")) put(k, r.long(k))
                }
            }))
            put("event_ids", ids("SELECT id FROM event WHERE $w ORDER BY id DESC LIMIT 50", args))
        }
    }

    fun domainHistory(domain: String, from: Long, to: Long, s: Scope = Scope()): JsonObject {
        val d = normalizeDomain(domain)
        require(d.isNotEmpty()) { "bad_domain" }
        val like = "%." + d.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val w = "kind='dns' AND (domain=? OR domain LIKE ? ESCAPE '\\') AND ts>=$from AND ts<$to${s.events}"
        val args = listOf(d, like)
        val apps = q(
            "SELECT package, COUNT(*) hits, MIN(ts) first_ts, MAX(ts) last_ts, GROUP_CONCAT(DISTINCT verdict) verdicts " +
                "FROM event WHERE $w GROUP BY package ORDER BY hits DESC, package",
            args,
        )
        val info = q("SELECT MAX(company) company, MAX(category) category, COUNT(*) total FROM event WHERE $w", args).first()
        val recent = q("SELECT id, ts, package, domain, verdict, fg, how FROM event WHERE $w ORDER BY id DESC LIMIT 50", args)
        return buildJsonObject {
            put("fn", "domainHistory"); put("domain", d); put("from_ms", from); put("to_ms", to)
            info.toJson().forEach { (k, v) -> put(k, v) }
            put("apps", rows(apps))
            put("events", rows(recent))
            put("event_ids", JsonArray(recent.map { JsonPrimitive(it.long("id")) }))
        }
    }

    fun lastEvents(pkg: String?, domain: String?, limit: Int, s: Scope = Scope()): JsonObject {
        val args = ArrayList<String>()
        val w = StringBuilder("1")
        if (pkg != null) { w.append(" AND package=?"); args += pkg } else w.append(s.watched())
        if (domain != null) {
            val d = normalizeDomain(domain)
            w.append(" AND (domain=? OR domain LIKE ? ESCAPE '\\')")
            args += d; args += "%." + d.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        }
        w.append(s.sim)
        val evs = q("SELECT * FROM event WHERE $w ORDER BY id DESC LIMIT ${limit.coerceIn(1, 200)}", args)
        return buildJsonObject {
            put("fn", "lastEvents"); pkg?.let { put("package", it) }; domain?.let { put("domain", it) }
            put("events", rows(evs))
            put("event_ids", JsonArray(evs.take(50).map { JsonPrimitive(it.long("id")) }))
        }
    }

    /** 00:00-06:00 local time of [date] (yyyy-MM-dd). */
    fun nightReport(date: String, s: Scope = Scope()): JsonObject {
        val (from, to) = nightRange(date)
        val w = "kind='dns' AND ts>=$from AND ts<$to${s.events}"
        val total = q("SELECT COUNT(*) n, COALESCE(SUM(fg=0),0) bg FROM event WHERE $w", emptyList()).first()
        val bgW = "$w AND fg=0"
        val apps = q(
            "SELECT package, (SELECT label FROM app WHERE app.package=event.package) label, COUNT(*) lookups, " +
                "COUNT(DISTINCT domain) domains, SUM(company IS NOT NULL) tracker_hits FROM event " +
                "WHERE $bgW GROUP BY package ORDER BY lookups DESC, package",
            emptyList(),
        )
        val domains = q(
            "SELECT domain, MAX(company) company, COUNT(*) hits FROM event WHERE $bgW GROUP BY domain ORDER BY hits DESC, domain LIMIT 10",
            emptyList(),
        )
        return buildJsonObject {
            put("fn", "nightReport"); put("date", date); put("from_ms", from); put("to_ms", to)
            put("lookups", total.long("n")); put("background_lookups", total.long("bg"))
            put("apps", rows(apps)); put("top_domains", rows(domains))
            put("event_ids", ids("SELECT id FROM event WHERE $bgW ORDER BY id DESC LIMIT 50", emptyList()))
        }
    }

    fun compare(pkg: String, p1: Pair<Long, Long>, p2: Pair<Long, Long>, s: Scope = Scope()): JsonObject {
        val a = core(pkg, p1.first, p1.second, s)
        val b = core(pkg, p2.first, p2.second, s)
        fun companies(p: Pair<Long, Long>) = q(
            "SELECT DISTINCT company FROM event WHERE kind='dns' AND company IS NOT NULL AND package=? " +
                "AND ts>=${p.first} AND ts<${p.second}${s.sim}",
            listOf(pkg),
        ).map { it["company"] as String }.toSet()
        fun idsOf(p: Pair<Long, Long>) = ids(
            "SELECT id FROM event WHERE kind IN ('dns','tripwire') AND package=? AND ts>=${p.first} AND ts<${p.second}${s.sim} " +
                "ORDER BY id DESC LIMIT 50",
            listOf(pkg),
        )
        val c1 = companies(p1)
        val c2 = companies(p2)
        return buildJsonObject {
            put("fn", "compare"); put("package", pkg)
            put("period1", a + mapOf("from_ms" to p1.first, "to_ms" to p1.second))
            put("period2", b + mapOf("from_ms" to p2.first, "to_ms" to p2.second))
            put("delta", buildJsonObject { for (k in DELTA_KEYS) put(k, b.num(k) - a.num(k)) })
            put("new_companies", JsonArray((c2 - c1).sorted().map { JsonPrimitive(it) }))
            put("gone_companies", JsonArray((c1 - c2).sorted().map { JsonPrimitive(it) }))
            put("event_ids", JsonArray((idsOf(p2) + idsOf(p1)).take(50)))
        }
    }

    // ---- other read calls ----

    /** One entry per watched app (plus apps with data if include_unwatched), last 24 h. */
    fun garden(now: Long, s: Scope = Scope()): JsonObject {
        val since = now - DAY
        val e = "e.package=a.package AND e.ts>=$since${s.sim}"
        val where = if (s.includeUnwatched) "a.watched=1 OR a.package IN (SELECT DISTINCT package FROM event WHERE package IS NOT NULL)"
            else "a.watched=1${s.simIn("a.package")}"
        val apps = q(
            "SELECT a.package, a.label, a.seed, a.watched, a.watched_ts, o.preset_id, o.stage_shown, " +
                "(SELECT COUNT(*) FROM event e WHERE $e AND e.kind='dns') lookups_24h, " +
                "(SELECT COUNT(*) FROM event e WHERE $e AND e.kind='dns' AND e.company IS NOT NULL) tracker_hits_24h, " +
                "(SELECT COUNT(DISTINCT company) FROM event e WHERE $e AND e.kind='dns') companies_24h, " +
                "(SELECT COUNT(*) FROM event e WHERE $e AND e.kind='dns' AND e.verdict='blocked') blocked_24h, " +
                "(SELECT COUNT(*) FROM event e WHERE $e AND e.kind='anomaly') anomalies_24h, " +
                "(SELECT MAX(ts) FROM event e WHERE e.package=a.package AND e.kind IN ('dns','tripwire')${s.sim}) last_seen, " +
                "(SELECT COALESCE(SUM(t.rx_bytes + t.tx_bytes), 0) FROM traffic t WHERE t.package=a.package AND t.end_ts>$since) bytes_24h " +
                "FROM app a LEFT JOIN organism o ON o.package=a.package WHERE $where ORDER BY a.watched DESC, a.label COLLATE NOCASE",
            emptyList(),
        )
        return buildJsonObject {
            put("apps", rows(apps))
            put("event_ids", ids("SELECT id FROM event WHERE kind='dns' AND ts>=$since${s.events} ORDER BY id DESC LIMIT 50", emptyList()))
        }
    }

    /** The raw log, newest first. Rows with a package carry watched 1|0 (0 = not currently watched). */
    fun events(limit: Int, pkg: String?, kind: String?, sinceMs: Long?, beforeId: Long?): JsonObject {
        val args = ArrayList<String>()
        val w = StringBuilder("1")
        if (pkg != null) { w.append(" AND e.package=?"); args += pkg }
        if (kind != null) { w.append(" AND e.kind=?"); args += kind }
        if (sinceMs != null) w.append(" AND e.ts>=$sinceMs")
        if (beforeId != null) w.append(" AND e.id<$beforeId")
        val evs = q(
            "SELECT e.*, a.watched AS watched FROM event e LEFT JOIN app a ON a.package=e.package " +
                "WHERE $w ORDER BY e.id DESC LIMIT ${limit.coerceIn(1, 500)}",
            args,
        )
        return buildJsonObject { put("events", rows(evs)) }
    }

    /** One event with its app, and for anomalies the evidence events. */
    fun record(id: Long): JsonObject {
        val r = q(
            "SELECT e.*, a.label AS app_label, a.watched AS watched FROM event e LEFT JOIN app a ON a.package=e.package WHERE e.id=$id",
            emptyList(),
        ).firstOrNull() ?: throw IllegalArgumentException("not_found")
        val evidenceIds = if (r["kind"] == "anomaly") (r["note"] as String?).orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() } else emptyList()
        val evidence = if (evidenceIds.isEmpty()) emptyList()
            else q("SELECT * FROM event WHERE id IN (${evidenceIds.joinToString(",")}) ORDER BY id", emptyList())
        return buildJsonObject {
            put("record", r.toJson())
            put("evidence", rows(evidence))
            put("event_ids", JsonArray((listOf(id) + evidenceIds).take(50).map { JsonPrimitive(it) }))
        }
    }

    // ---- helpers ----

    private fun core(pkg: String, from: Long, to: Long, s: Scope): Row {
        val w = "package=? AND ts>=$from AND ts<$to${s.sim}"
        val e = q(
            "SELECT COUNT(*) lookups, COUNT(DISTINCT domain) unique_domains, COALESCE(SUM(company IS NOT NULL),0) tracker_hits, " +
                "COUNT(DISTINCT company) companies, COALESCE(SUM(verdict='blocked'),0) blocked, COALESCE(SUM(fg=1),0) fg, " +
                "COALESCE(SUM(fg=0),0) bg, COALESCE(SUM(fg IS NULL),0) fg_unknown, MIN(ts) first_seen, MAX(ts) last_seen " +
                "FROM event WHERE kind='dns' AND $w",
            listOf(pkg),
        ).first()
        val t = q(
            "SELECT COALESCE(SUM(rx_bytes),0) rx_bytes, COALESCE(SUM(tx_bytes),0) tx_bytes, COALESCE(SUM(rx_packets),0) rx_packets, " +
                "COALESCE(SUM(tx_packets),0) tx_packets FROM traffic WHERE package=? AND start_ts<$to AND end_ts>$from",
            listOf(pkg),
        ).first()
        val trip = q("SELECT COUNT(*) n FROM event WHERE kind='tripwire' AND $w", listOf(pkg)).first()
        return e + t + ("tripwire_hits" to trip.long("n"))
    }

    fun nightRange(date: String): Pair<Long, Long> {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(date.trim()) ?: throw IllegalArgumentException("bad_date")
        val (y, mo, d) = m.destructured
        val c = Calendar.getInstance(tz).apply { clear(); set(y.toInt(), mo.toInt() - 1, d.toInt(), 0, 0, 0) }
        val from = c.timeInMillis
        c.set(Calendar.HOUR_OF_DAY, 6)
        return from to c.timeInMillis
    }

    private fun ids(sql: String, args: List<String>) = JsonArray(q(sql, args).map { JsonPrimitive(it.long("id")) })
    private fun rows(rs: List<Row>) = JsonArray(rs.map { it.toJson() })
    private fun marks(xs: List<*>) = xs.joinToString(",") { "?" }
    private fun Row?.long(k: String) = (this?.get(k) as Number?)?.toLong() ?: 0L
    private fun Row.num(k: String) = (this[k] as Number?)?.toLong() ?: 0L
    private fun kotlinx.serialization.json.JsonObjectBuilder.put(k: String, r: Row) = put(k, r.toJson())

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
    private fun JsonObject.req(k: String) = str(k) ?: throw IllegalArgumentException("missing_$k")
    private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull == true
    private fun period(a: JsonObject, k: String): Pair<Long, Long> {
        val p = a[k]?.jsonObject ?: throw IllegalArgumentException("missing_$k")
        val from = p.long("from_ms") ?: throw IllegalArgumentException("missing_$k")
        val to = p.long("to_ms") ?: throw IllegalArgumentException("missing_$k")
        require(to > from) { "bad_$k" }
        return from to to
    }

    companion object {
        const val HOUR = 3600_000L
        const val DAY = 24 * HOUR
        private val DELTA_KEYS = listOf(
            "lookups", "unique_domains", "tracker_hits", "companies", "blocked", "bg", "tripwire_hits", "rx_bytes", "tx_bytes",
        )
    }
}
