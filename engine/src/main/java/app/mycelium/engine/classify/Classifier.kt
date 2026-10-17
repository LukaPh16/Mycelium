package app.mycelium.engine.classify

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.mycelium.engine.db.Writer

/** Tracker lookup plus block rules (rule table, cached as an immutable RuleSet). */
object Classifier {
    data class Verdict(val company: String?, val category: String?, val blockedBy: RuleSet.Rule?) {
        val tracker get() = category in Trackers.TRACKER_CATEGORIES
        fun row() = mapOf("company" to company, "category" to category)
    }

    lateinit var trackers: Trackers
        private set
    lateinit var typer: PacketTyper
        private set
    lateinit var packetTypesJson: String
        private set
    @Volatile var rules = RuleSet(emptyList())
        private set
    private lateinit var writer: Writer

    internal fun init(context: Context, writer: Writer, reader: SQLiteDatabase) {
        this.writer = writer
        trackers = Trackers.parse(context.assets.open("trackers.json").bufferedReader().use { it.readText() })
        packetTypesJson = context.assets.open("config/packet_types.json").bufferedReader().use { it.readText() }
        typer = PacketTyper.parse(packetTypesJson)
        rules = RuleSet(reader.rawQuery("SELECT scope, value, package, ts FROM rule", null).use { c ->
            buildList { while (c.moveToNext()) add(RuleSet.Rule(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
        })
    }

    /** Category is "unknown" for unlisted domains, null when there is no domain (tripwire). */
    fun classify(domain: String?, pkg: String?, how: String?): Verdict {
        val m = domain?.let(trackers::match)
        val category = m?.category ?: if (domain != null) "unknown" else null
        return Verdict(m?.company, category, rules.blocking(domain, m?.company, pkg, how))
    }

    @Synchronized fun block(scope: String, value: String, pkg: String): RuleSet.Rule {
        val stored = if (scope == RuleSet.SCOPE_DOMAIN) normalizeDomain(value) else value.trim()
        val existing = find(scope, value, pkg)
        val rule = RuleSet.Rule(scope, existing?.value ?: stored, pkg, System.currentTimeMillis())
        writer.runAndWait { d ->
            d.execSQL("INSERT OR REPLACE INTO rule(scope, value, package, ts) VALUES(?,?,?,?)", arrayOf<Any>(rule.scope, rule.value, rule.pkg, rule.ts))
        }
        rules = RuleSet(rules.rules.filter { it !== existing } + rule)
        return rule
    }

    /** Returns the removed rule, or null if there was none. */
    @Synchronized fun unblock(scope: String, value: String, pkg: String): RuleSet.Rule? {
        val existing = find(scope, value, pkg) ?: return null
        writer.runAndWait { d -> d.delete("rule", "scope=? AND value=? AND package=?", arrayOf(existing.scope, existing.value, existing.pkg)) }
        rules = RuleSet(rules.rules.filter { it !== existing })
        return existing
    }

    private fun find(scope: String, value: String, pkg: String) = rules.rules.firstOrNull {
        it.scope == scope && it.pkg == pkg && RuleSet.key(scope, it.value) == RuleSet.key(scope, value)
    }
}
