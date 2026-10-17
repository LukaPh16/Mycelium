package app.mycelium.engine

import app.mycelium.engine.db.SchemaSql
import app.mycelium.engine.stats.Query
import app.mycelium.engine.stats.Stats
import java.io.File
import java.sql.DriverManager

/** In-memory SQLite (test-only sqlite-jdbc) with docs/schema.sql and a small known data set. */
class FixtureDb {
    val conn = DriverManager.getConnection("jdbc:sqlite::memory:")

    /** Mirrors Android rawQuery: every arg bound as a string. */
    val query: Query = { sql, args ->
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setString(i + 1, a) }
            st.executeQuery().use { rs ->
                val md = rs.metaData
                buildList {
                    while (rs.next()) add((1..md.columnCount).associate { c ->
                        md.getColumnLabel(c) to when (val v = rs.getObject(c)) { is Int -> v.toLong(); else -> v }
                    })
                }
            }
        }
    }

    fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    fun ev(ts: Long, kind: String, pkg: String?, domain: String? = null, company: String? = null, category: String? = null,
           verdict: String? = "allowed", fg: Int? = null, sim: Int = 0, note: String? = null, port: Int? = null) {
        fun s(v: Any?) = when (v) { null -> "NULL"; is String -> "'$v'"; else -> v.toString() }
        exec("INSERT INTO event(ts,kind,package,how,domain,company,category,verdict,fg,sim,note,port) VALUES(" +
            listOf(ts, kind, pkg, if (pkg != null) "uid_dns" else null, domain, company, category, verdict, fg, sim, note, port)
                .joinToString(",") { s(it) } + ")")
    }

    init {
        SchemaSql.split(File(System.getProperty("schemaPath")!!).readText()).forEach(::exec)
        exec("INSERT INTO app(package,label,watched,watched_ts,seed) VALUES('com.a','Alpha',1,0,'a1'),('com.b','Beta',1,0,'b2'),('com.c','Gamma',0,0,'c3')")
        exec("INSERT INTO organism(package,preset_id,chosen_ts) VALUES('com.a','preset07',0)")
        ev(B + 12 * H, "dns", "com.a", "doubleclick.net", "Google", "ads", fg = 1)                          // 1
        ev(B + 12 * H + 60_000, "dns", "com.a", "ad.doubleclick.net", "Google", "ads", "blocked", fg = 0)    // 2
        ev(B + 13 * H, "dns", "com.a", "example.com", category = "unknown", fg = 1)                          // 3
        ev(B + 3 * H, "dns", "com.b", "t.appsflyer.com", "AppsFlyer", "analytics", fg = 0)                   // 4 night bg
        ev(B + 4 * H, "dns", "com.b", "example.org", category = "unknown", fg = 0)                           // 5 night bg
        ev(B + 12 * H, "dns", "com.c", "doubleclick.net", "Google", "ads", fg = 1)                           // 6 unwatched
        ev(B + 12 * H + 120_000, "tripwire", "com.a", verdict = "blocked", port = 443)                       // 7
        ev(B + 12 * H, "dns", "com.a", "x.sim", fg = 1, sim = 1)                                             // 8 simulated
        ev(B + 14 * H, "anomaly", "com.a", company = "Google", category = "new_company", verdict = null, note = "1,2")  // 9
        ev(B + 11 * H, "vpn", null, verdict = null, note = "started")                                        // 10
        ev(B - 2 * D + 12 * H, "dns", "com.a", "doubleclick.net", "Google", "ads", fg = 1)                   // 11 earlier period
        exec("INSERT INTO traffic VALUES('com.a',10001,${B + 10 * H},${B + 12 * H},1000,200,1,'wifi',10,5)")
        exec("INSERT INTO traffic VALUES('com.a',10001,${B + 12 * H},${B + 14 * H},500,100,0,'cell',4,2)")
        exec("INSERT INTO traffic VALUES('com.b',10002,${B + 10 * H},${B + 12 * H},5000,0,1,'wifi',50,0)")
        exec("INSERT INTO traffic VALUES('com.c',10003,${B + 10 * H},${B + 12 * H},99999,0,1,'wifi',1,1)")
    }

    fun close() = conn.close()

    companion object {
        const val H = Stats.HOUR
        const val D = Stats.DAY
        const val B = 1_760_000_000_000L / D * D          // 2025-10-09 00:00 UTC
    }
}
