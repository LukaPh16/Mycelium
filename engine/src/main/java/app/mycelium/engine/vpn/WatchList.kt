package app.mycelium.engine.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import app.mycelium.engine.attr.Apps
import app.mycelium.engine.attr.Attribution
import app.mycelium.engine.db.Writer

/** The user's selection, backed by app.watched / app.watched_ts, cached in memory for the VPN. */
object WatchList {
    private lateinit var context: Context
    private lateinit var writer: Writer
    private lateinit var apps: Apps
    @Volatile private var state: Map<String, WatchRules.State> = emptyMap()
    @Volatile private var labels: Map<String, String> = emptyMap()
    /** Preset ids from organisms/index.json; the seed picks one when the user gives none. */
    @Volatile internal var presetIds: List<String> = listOf("preset01")

    internal fun init(context: Context, writer: Writer, apps: Apps, reader: SQLiteDatabase) {
        this.context = context
        this.writer = writer
        this.apps = apps
        val s = HashMap<String, WatchRules.State>()
        val l = HashMap<String, String>()
        reader.rawQuery("SELECT package, label, watched, watched_ts FROM app", null).use { c ->
            while (c.moveToNext()) {
                s[c.getString(0)] = WatchRules.State(c.getInt(2) == 1, if (c.isNull(3)) null else c.getLong(3))
                l[c.getString(0)] = c.getString(1) ?: c.getString(0)
            }
        }
        state = s
        labels = l
    }

    fun packages(): List<String> = WatchRules.watched(state, context.packageName)
    fun isWatched(pkg: String) = state[pkg]?.watched == true
    fun label(pkg: String) = labels[pkg] ?: pkg
    fun startError(): String? = WatchRules.startError(packages(), context.packageName)

    /** Returns the plan applied, plus whether any organism changed. */
    @Synchronized internal fun watch(pkgs: List<String>, presets: Map<String, String>): Pair<WatchRules.Plan, Boolean> {
        val now = System.currentTimeMillis()
        val plan = WatchRules.watch(state, pkgs, context.packageName, now, ::installed)
        val next = WatchRules.apply(state, plan)
        val organisms = pkgs.distinct().filter { next[it]?.watched == true }
        plan.changes.keys.forEach(apps::ensure)   // queued before the job below on the single writer
        val newLabels = writer.runAndWait { d ->
            d.beginTransaction()
            try {
                write(d, plan)
                for (p in organisms) {
                    val given = presets[p]
                    if (given != null) {
                        d.execSQL(
                            "INSERT INTO organism(package, preset_id, chosen_ts) VALUES(?,?,?) " +
                                "ON CONFLICT(package) DO UPDATE SET preset_id=excluded.preset_id, chosen_ts=excluded.chosen_ts",
                            arrayOf<Any>(p, given, now),
                        )
                    } else {
                        d.execSQL(
                            "INSERT OR IGNORE INTO organism(package, preset_id, chosen_ts) VALUES(?,?,?)",
                            arrayOf<Any>(p, WatchRules.pickPreset(Attribution.seed(p), presetIds), now),
                        )
                    }
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            readLabels(d, plan.changes.keys)
        }
        state = next
        labels = labels + newLabels
        return plan to organisms.any { it in presets }
    }

    @Synchronized internal fun unwatch(pkgs: List<String>): WatchRules.Plan {
        val plan = WatchRules.unwatch(state, pkgs)
        writer.runAndWait { d -> write(d, plan) }
        state = WatchRules.apply(state, plan)
        return plan
    }

    private fun write(d: SQLiteDatabase, plan: WatchRules.Plan) {
        for ((p, s) in plan.changes) {
            d.execSQL("UPDATE app SET watched=?, watched_ts=? WHERE package=?", arrayOf<Any?>(if (s.watched) 1 else 0, s.ts, p))
        }
    }

    private fun readLabels(d: SQLiteDatabase, pkgs: Collection<String>): Map<String, String> = pkgs.associateWith { p ->
        d.rawQuery("SELECT label FROM app WHERE package=?", arrayOf(p)).use { c -> if (c.moveToFirst()) c.getString(0) ?: p else p }
    }

    private fun installed(pkg: String) = try {
        context.packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
