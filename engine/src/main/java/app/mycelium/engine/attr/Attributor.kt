package app.mycelium.engine.attr

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.system.OsConstants
import android.util.LruCache
import app.mycelium.engine.db.Row
import app.mycelium.engine.db.Writer
import app.mycelium.engine.db.toContentValues
import app.mycelium.engine.dns.DnsQuery
import app.mycelium.engine.dns.TcpSyn
import app.mycelium.engine.vpn.WatchList
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/** Finds which app sent a packet. Call before replying: the lookup needs the app's socket to still exist. */
class Attributor(context: Context, private val fg: Foreground, private val apps: Apps) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val pm = context.packageManager
    private val uidCache = LruCache<Int, String>(256)   // uid -> package ("" = none)

    fun udp(q: DnsQuery) = lookup(OsConstants.IPPROTO_UDP, q.srcIp, q.srcPort, q.dstIp, q.dstPort, "uid_dns")

    fun tcp(s: TcpSyn) = lookup(OsConstants.IPPROTO_TCP, s.srcIp, s.srcPort, s.dstIp, s.dstPort, "uid_tcp")

    private fun lookup(proto: Int, srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, how: String): Attribution.Result {
        val uid = try {
            cm.getConnectionOwnerUid(proto, sock(srcIp, srcPort), sock(dstIp, dstPort))
        } catch (_: Exception) { -1 }
        // Only watched apps are in the tunnel, so only a watched foreground app is a plausible guess.
        val r = Attribution.decide(uid, packageFor(uid), how, fg.current?.takeIf(WatchList::isWatched), fg.known)
        r.pkg?.let(apps::ensure)
        return r
    }

    private fun packageFor(uid: Int): String? {
        if (!Attribution.usableUid(uid)) return null
        val hit = uidCache.get(uid) ?: (pm.getPackagesForUid(uid)?.firstOrNull() ?: "").also { uidCache.put(uid, it) }
        return hit.ifEmpty { null }
    }

    private fun sock(ip: Int, port: Int) = InetSocketAddress(
        InetAddress.getByAddress(byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())), port,
    )
}

/** Upserts app rows on first sight (label, seed, system flag) and records last_used. */
class Apps(private val context: Context, private val writer: Writer, existing: Collection<String>) {
    private val pm = context.packageManager
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(existing) }
    private val labelCache = ConcurrentHashMap<String, String>()

    fun ensure(pkg: String) {
        if (!known.add(pkg)) return
        val row = describe(pkg)
        writer.run { d -> insert(d, row) }
    }

    fun touch(used: Map<String, Long>) {
        used.keys.forEach(::ensure)
        writer.run { d ->
            for ((p, t) in used) d.execSQL("UPDATE app SET last_used=MAX(COALESCE(last_used,0),?) WHERE package=?", arrayOf<Any>(t, p))
        }
    }

    /** Launchable packages, never our own. */
    fun launchable(): List<String> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }
            .distinct().filter { it != context.packageName }
    }

    /** Inserts every launchable app. Returns inserted and total counts. */
    fun seedLaunchable(): Pair<Int, Int> {
        val pkgs = launchable()
        val rows = pkgs.map(::describe)
        val n = writer.runAndWait { d ->
            d.beginTransaction()
            try {
                rows.count { insert(d, it) }.also { d.setTransactionSuccessful() }
            } finally {
                d.endTransaction()
            }
        }
        known.addAll(pkgs)
        return n to pkgs.size
    }

    private fun insert(d: SQLiteDatabase, row: Row) =
        d.insertWithOnConflict("app", null, row.toContentValues(), SQLiteDatabase.CONFLICT_IGNORE) != -1L

    fun describe(pkg: String): Row {
        val ai = try { pm.getApplicationInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        return mapOf(
            "package" to pkg, "uid" to ai?.uid, "label" to label(pkg, ai),
            "seed" to Attribution.seed(pkg), "system" to if (ai != null && isSystem(ai)) 1 else 0,
        )
    }

    fun label(pkg: String, ai: ApplicationInfo? = null): String = labelCache.getOrPut(pkg) {
        val info = ai ?: try { pm.getApplicationInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        info?.loadLabel(pm)?.toString() ?: pkg
    }

    /** Preinstalled and never updated (an updated Chrome counts as a user app). */
    fun isSystem(ai: ApplicationInfo) =
        ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
}
