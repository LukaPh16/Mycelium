package app.mycelium.engine.vpn

import android.database.sqlite.SQLiteDatabase
import app.mycelium.engine.MyceliumEngine
import app.mycelium.engine.classify.Classifier
import app.mycelium.engine.classify.Trackers
import app.mycelium.engine.db.Row
import app.mycelium.engine.db.row
import app.mycelium.engine.db.toContentValues

/** Turns traffic caught offline (queue table) into event rows, on device. Never replays anything upstream. */
internal object Sanctuary {

    /** Runs on the writer thread. Returns the number of queue rows processed. */
    fun drain(db: SQLiteDatabase): Int {
        val queued = db.rawQuery("SELECT * FROM queue WHERE done=0 ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.row()) }
        }
        if (queued.isEmpty()) return 0
        val written = ArrayList<Pair<Long, Row>>()
        db.beginTransaction()
        try {
            for (q in queued) {
                val ev = toEvent(q)
                written += db.insertOrThrow("event", null, ev.toContentValues()) to ev
                db.execSQL("UPDATE queue SET done=1 WHERE id=?", arrayOf(q["id"]))
            }
            val trackers = written.count { it.second["category"] in Trackers.TRACKER_CATEGORIES }
            val summary: Row = mapOf(
                "ts" to System.currentTimeMillis(), "kind" to "vpn",
                "note" to "sanctuary: ${queued.size} caught, $trackers trackers",
            )
            written += db.insertOrThrow("event", null, summary.toContentValues()) to summary
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        written.forEach { (id, ev) -> MyceliumEngine.emitEvent(id, ev) }
        return queued.size
    }

    /** Attribution was captured at catch time; classification and block rules run now, on device. */
    private fun toEvent(q: Row): Row {
        val v = Classifier.classify(q["domain"] as String?, q["package"] as String?, q["how"] as String?)
        return mapOf(
            "ts" to q["ts"], "kind" to q["kind"],
            "ptype" to if (q["kind"] == "dns") Classifier.typer.type("dns", "udp", 53, q["domain"] as String?, v.company)
                else Classifier.typer.known(q["ptype"] as String?),
            "package" to q["package"],
            "how" to q["how"], "fg" to q["fg"], "domain" to q["domain"], "qtype" to q["qtype"],
            "port" to q["port"], "verdict" to if (v.blockedBy != null) "blocked" else "offline",
        ) + v.row()
    }
}
