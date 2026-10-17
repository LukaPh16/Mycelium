package app.mycelium.engine.classify

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.mycelium.engine.db.Row
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Every 60 s: run the anomaly rules over the last 10 min and write kind='anomaly' events. */
class AnomalyJob(private val reader: SQLiteDatabase, private val write: (Row) -> Unit) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "anomaly-job").apply { isDaemon = true } }

    fun start() {
        exec.scheduleWithFixedDelay({
            try { run(System.currentTimeMillis()) } catch (e: Exception) { Log.w("MyceliumAnomaly", "job failed", e) }
        }, 60, 60, TimeUnit.SECONDS)
    }

    fun run(now: Long) {
        val from = now - Anomalies.WINDOW_MS
        val window = events("ts>=? AND ts<=? AND kind IN ('dns','tripwire') AND package IS NOT NULL AND sim=0", from, now)
        if (window.isEmpty()) return
        val blocks = events("kind='block' AND ts<=?", now)
        val recent = reader.rawQuery(
            "SELECT package, category, COALESCE(company,'') FROM event WHERE kind='anomaly' AND ts>=?",
            arrayOf((now - Anomalies.DEDUP_MS).toString()),
        ).use { c -> buildSet { while (c.moveToNext()) add(Anomalies.Key(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2))) } }

        val found = Anomalies.detect(
            window, blocks,
            knownDomain = { p, d -> seenBefore("domain", p, d, from) },
            knownCompany = { p, c -> seenBefore("company", p, c, from) },
            recent = recent,
        )
        for (f in found) write(mapOf(
            "ts" to now, "kind" to "anomaly", "package" to f.pkg, "category" to f.rule,
            "company" to f.company, "note" to f.evidence.joinToString(","),
        ))
    }

    private fun events(where: String, vararg args: Long): List<Anomalies.Ev> =
        reader.rawQuery("SELECT id, ts, kind, package, domain, company, fg FROM event WHERE $where",
            args.map { it.toString() }.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) add(Anomalies.Ev(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                    if (c.isNull(6)) null else c.getInt(6),
                ))
            }
        }

    private fun seenBefore(col: String, pkg: String, value: String, before: Long): Boolean =
        reader.rawQuery("SELECT 1 FROM event WHERE package=? AND kind='dns' AND $col=? AND ts<? LIMIT 1",
            arrayOf(pkg, value, before.toString())).use { it.moveToFirst() }
}
