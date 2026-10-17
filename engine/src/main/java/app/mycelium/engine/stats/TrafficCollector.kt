package app.mycelium.engine.stats

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.util.Log
import app.mycelium.engine.attr.Foreground
import app.mycelium.engine.db.Row
import app.mycelium.engine.db.Writer
import app.mycelium.engine.db.toContentValues
import app.mycelium.engine.vpn.WatchList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Per-app bytes and packets from NetworkStatsManager (last 48 h, wifi + cell, fg/bg) into the traffic table. */
class TrafficCollector(private val context: Context, private val writer: Writer) {
    private val nsm = context.getSystemService(NetworkStatsManager::class.java)
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "traffic").apply { isDaemon = true } }

    fun start() {
        exec.scheduleWithFixedDelay({
            try { if (Foreground.granted(context)) collect() } catch (e: Exception) { Log.w("MyceliumTraffic", "collect", e) }
        }, 5, 15 * 60, TimeUnit.SECONDS)
    }

    /** Returns rows written. Caller checks usage access first. */
    @Synchronized fun collect(): Int {
        val end = System.currentTimeMillis()
        val start = end - 48 * 3600_000L
        val rows = ArrayList<Row>()
        for (pkg in WatchList.packages()) {
            val uid = try { context.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Exception) { continue }
            for ((type, net) in NETS) for ((state, fg) in STATES) {
                val stats = try {
                    nsm.queryDetailsForUidTagState(type, null, start, end, uid, NetworkStats.Bucket.TAG_NONE, state)
                } catch (e: Exception) {
                    Log.w("MyceliumTraffic", "query $pkg $net", e)
                    continue
                }
                try {
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        rows += mapOf(
                            "package" to pkg, "uid" to uid, "start_ts" to b.startTimeStamp, "end_ts" to b.endTimeStamp,
                            "rx_bytes" to b.rxBytes, "tx_bytes" to b.txBytes, "fg" to fg, "net" to net,
                            "rx_packets" to b.rxPackets, "tx_packets" to b.txPackets,
                        )
                    }
                } finally {
                    stats.close()
                }
            }
        }
        writer.runAndWait { d ->
            d.beginTransaction()
            try {
                for (r in rows) d.insertWithOnConflict("traffic", null, r.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        return rows.size
    }

    private companion object {
        @Suppress("DEPRECATION")   // NetworkStatsManager queries still take the legacy network type ids
        val NETS = listOf(ConnectivityManager.TYPE_WIFI to "wifi", ConnectivityManager.TYPE_MOBILE to "cell")
        val STATES = listOf(NetworkStats.Bucket.STATE_FOREGROUND to 1, NetworkStats.Bucket.STATE_DEFAULT to 0)
    }
}
