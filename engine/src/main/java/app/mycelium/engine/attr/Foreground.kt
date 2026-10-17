package app.mycelium.engine.attr

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Polls UsageStatsManager every 2 s: current foreground package and per-app last_used. */
class Foreground(private val context: Context, private val onUsed: (Map<String, Long>) -> Unit) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var exec: ScheduledExecutorService? = null
    private var since = 0L

    @Volatile var current: String? = null
        private set
    @Volatile var known = false
        private set

    @Synchronized fun start() {
        if (exec != null) return
        since = System.currentTimeMillis() - LOOKBACK_MS
        exec = Executors.newSingleThreadScheduledExecutor().also { it.scheduleWithFixedDelay(::tick, 0, 2, TimeUnit.SECONDS) }
    }

    @Synchronized fun stop() {
        exec?.shutdownNow()
        exec = null
        current = null
        known = false
    }

    private fun tick() {
        try {
            known = granted(context)
            if (!known) { current = null; return }
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(since, now)
            since = now
            val e = UsageEvents.Event()
            val used = HashMap<String, Long>()
            while (events.getNextEvent(e)) when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> { current = e.packageName; used[e.packageName] = e.timeStamp }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == current) current = null
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> current = null
            }
            if (used.isNotEmpty()) onUsed(used)
        } catch (e: Exception) {
            Log.w("MyceliumFg", "usage poll", e)
        }
    }

    companion object {
        private const val LOOKBACK_MS = 60 * 60 * 1000L

        fun granted(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            } else mode == AppOpsManager.MODE_ALLOWED
        }

        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
