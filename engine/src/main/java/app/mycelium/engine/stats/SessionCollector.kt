package app.mycelium.engine.stats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.mycelium.engine.attr.Foreground
import app.mycelium.engine.db.Writer
import app.mycelium.engine.db.toContentValues
import app.mycelium.engine.vpn.WatchList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground sessions of watched apps from UsageStatsManager events into the session table: every 15 min, on start,
 * when the VPN stops and before screen-time stats. Runs with or without the VPN; does nothing without usage access.
 * Reads from a cursor (the start of the earliest still-open session) and upserts, so re-runs never double count.
 * Also stores one anonymous whole-phone timeline (package '*': any app on screen, home screen and system UI not
 * counted, like Digital Wellbeing) so shares can be of total screen time. It never names unwatched apps.
 */
class SessionCollector(private val context: Context, private val writer: Writer) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val prefs = context.getSharedPreferences("mycelium_sessions", Context.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sessions").apply { isDaemon = true } }
    @Volatile private var lastRun = 0L

    fun start() {
        exec.scheduleWithFixedDelay(::safeCollect, 3, 15 * 60, TimeUnit.SECONDS)
    }

    fun collectSoon() = exec.execute(::safeCollect)

    /** Fresh enough for a screen-time read: collects at once if the last run is older than [maxAgeMs]. */
    fun collectIfStale(maxAgeMs: Long) {
        if (System.currentTimeMillis() - lastRun > maxAgeMs) safeCollect()
    }

    private fun safeCollect() {
        try { if (Foreground.granted(context)) collect() } catch (e: Exception) { Log.w("MyceliumSessions", "collect", e) }
    }

    /** Returns sessions written. Caller checks usage access first. */
    @Synchronized fun collect(): Int {
        val now = System.currentTimeMillis()
        lastRun = now
        val watched = WatchList.packages().toSet() - context.packageName
        if (watched.isEmpty()) return 0
        val cursor = prefs.getLong("cursor", 0L)
        val known = prefs.getStringSet("known", emptySet()).orEmpty()
        // first run, or a newly watched app: read as far back as the OS still keeps events
        val from = if (cursor <= 0L || !known.containsAll(watched + Sessions.DEVICE)) now - BACKFILL_MS else cursor
        val evs = read(from, now)
        val spans = Sessions.pair(evs, now).filter { it.pkg in watched } + Sessions.device(evs, notCounted(), now)
        writer.runAndWait { d ->
            d.beginTransaction()
            try {
                for (s in spans) d.insertWithOnConflict("session", null,
                    mapOf("package" to s.pkg, "start_ts" to s.start, "end_ts" to s.end, "sim" to 0).toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        val next = Sessions.cursor(spans, now)
        prefs.edit().putLong("cursor", next).putStringSet("known", watched + Sessions.DEVICE).apply()
        return spans.size
    }

    /**
     * Screen time of every app in [from, to) straight from Android, for chat questions about any app. Nothing is
     * stored. package -> ms, plus [Sessions.DEVICE] for the whole phone. Null without usage access.
     */
    fun live(from: Long, to: Long): Map<String, Long>? {
        if (!Foreground.granted(context)) return null
        val end = minOf(to, System.currentTimeMillis())
        if (end <= from) return emptyMap()
        val evs = read(from - LIVE_LEAD_MS, end)    // sessions that started before [from] still count from [from]
        val skip = notCounted()
        val spans = Sessions.pair(evs, end).filter { it.pkg !in skip } + Sessions.device(evs, skip, end)
        return spans.groupBy { it.pkg }.mapValues { (_, xs) ->
            Stats.used(xs.map { maxOf(it.start, from) to minOf(it.end, end) }.filter { it.second > it.first })
        }.filterValues { it > 0 }
    }

    private fun read(from: Long, to: Long): List<Sessions.Ev> {
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val evs = ArrayList<Sessions.Ev>()
        while (events.getNextEvent(e)) {
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> Sessions.Kind.OPEN
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> Sessions.Kind.CLOSE
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.DEVICE_SHUTDOWN -> Sessions.Kind.ALL_OFF
                else -> null
            } ?: continue
            evs += Sessions.Ev(e.timeStamp, kind, e.packageName.orEmpty(), e.className)
        }
        return evs
    }

    /** The home screen launcher(s) and system UI: on screen a lot, but not app use. */
    private fun notCounted(): Set<String> {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet() + "com.android.systemui"
    }

    private companion object {
        const val BACKFILL_MS = 10 * 24 * 3600_000L
        const val LIVE_LEAD_MS = 12 * 3600_000L
    }
}

/** Pure pairing of usage events into per-app foreground sessions (unit-tested without Android). */
object Sessions {
    enum class Kind { OPEN, CLOSE, ALL_OFF }
    data class Ev(val ts: Long, val kind: Kind, val pkg: String, val cls: String? = null)
    data class Span(val pkg: String, val start: Long, val end: Long, val open: Boolean = false)

    const val MERGE_GAP_MS = 5_000L
    /** Package value of the whole-phone screen-time rows. */
    const val DEVICE = "*"

    /** Whole-phone screen time: on screen while any counted app has an activity resumed. Rows use package [DEVICE]. */
    fun device(events: List<Ev>, notCounted: Set<String>, now: Long): List<Span> =
        pair(events.filter { it.kind == Kind.ALL_OFF || it.pkg !in notCounted }
            .map { if (it.kind == Kind.ALL_OFF) it else it.copy(pkg = DEVICE, cls = it.pkg + "/" + it.cls.orEmpty()) }, now)

    /**
     * An app is in the foreground while at least one of its activities is resumed. Screen off, keyguard and shutdown
     * close everything. Gaps under [gapMs] (switching between two activities of one app) are merged. Sessions still
     * open at the end run to [now] with open=true.
     */
    fun pair(events: List<Ev>, now: Long, gapMs: Long = MERGE_GAP_MS): List<Span> {
        val active = HashMap<String, MutableSet<String>>()
        val startOf = HashMap<String, Long>()
        val out = ArrayList<Span>()
        for (e in events.sortedBy { it.ts }) when (e.kind) {
            Kind.OPEN -> {
                if (e.pkg.isEmpty()) continue
                val set = active.getOrPut(e.pkg) { HashSet() }
                if (set.isEmpty()) startOf[e.pkg] = e.ts
                set += e.cls.orEmpty()
            }
            Kind.CLOSE -> {
                val set = active[e.pkg] ?: continue
                if (!set.remove(e.cls.orEmpty())) continue
                if (set.isEmpty()) out += Span(e.pkg, startOf.remove(e.pkg)!!, e.ts)
            }
            Kind.ALL_OFF -> {
                for ((p, set) in active) if (set.isNotEmpty()) out += Span(p, startOf.remove(p)!!, e.ts)
                active.clear()
            }
        }
        for ((p, set) in active) if (set.isNotEmpty()) out += Span(p, startOf.getValue(p), maxOf(now, startOf.getValue(p)), open = true)
        return merge(out, gapMs)
    }

    fun merge(spans: List<Span>, gapMs: Long = MERGE_GAP_MS): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans.sortedWith(compareBy({ it.pkg }, { it.start }))) {
            val last = out.lastOrNull()
            if (last != null && last.pkg == s.pkg && s.start - last.end < gapMs) {
                out[out.size - 1] = last.copy(end = maxOf(last.end, s.end), open = if (s.end >= last.end) s.open else last.open)
            } else out += s
        }
        return out
    }

    /** Where the next read starts: just before the earliest open session (so it is rebuilt with the same start), else [now]. */
    fun cursor(spans: List<Span>, now: Long): Long = spans.filter { it.open }.minOfOrNull { it.start - 1 } ?: now
}
