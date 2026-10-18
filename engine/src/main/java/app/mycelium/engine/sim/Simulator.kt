package app.mycelium.engine.sim

import android.content.Context
import android.util.Log
import app.mycelium.engine.classify.Classifier
import app.mycelium.engine.classify.RuleSet
import app.mycelium.engine.db.Row
import java.util.TimeZone
import kotlin.concurrent.thread

/**
 * Replays a demo script through the real classifier and DB path (rows marked sim=1) while mode=simulate.
 * Foreground lines also become sim=1 screen-time sessions.
 * [packages] is non-null for as long as simulate mode lasts (including after the script ends) until [stop].
 */
class Simulator(
    private val context: Context,
    private val write: (Row) -> Unit,
    private val ensureApps: (List<Pair<String, String>>) -> Unit,
    private val onChange: () -> Unit,
    /** Upserts a sim=1 session row (package, start, end). */
    private val writeSession: (String, Long, Long) -> Unit = { _, _, _ -> },
) {
    @Volatile var packages: Set<String>? = null
        private set
    @Volatile var script: String? = null
        private set
    private var worker: Thread? = null

    @Synchronized fun start(name: String, speed: Double): SimScript {
        require(Regex("[a-z0-9_]+").matches(name)) { "unknown_script" }
        val s = try {
            SimScript.parse(context.assets.open("sim/$name.json").bufferedReader().use { it.readText() })
        } catch (_: java.io.IOException) {
            throw IllegalArgumentException("unknown_script")
        }
        halt()
        ensureApps(s.apps)
        packages = s.packages
        script = name
        onChange()
        val pace = speed.coerceIn(0.25, 50.0)
        worker = thread(name = "sim", isDaemon = true) { replay(s, pace) }
        return s
    }

    /** Leaves simulate mode; the live network mode is shown again. Sim rows stay in the DB, marked sim=1. */
    @Synchronized fun stop() {
        halt()
        packages = null
        script = null
        onChange()
    }

    private fun halt() {
        worker?.interrupt()
        worker?.join(1000)
        worker = null
    }

    private fun replay(s: SimScript, speed: Double) {
        val simRules = ArrayList<RuleSet.Rule>()
        val tz = TimeZone.getDefault()
        val fg = SimScript.FgTrack()
        write(mapOf("ts" to System.currentTimeMillis(), "kind" to "vpn", "note" to "simulation ${s.name} started, speed $speed", "sim" to 1))
        try {
            for (line in s.events) {
                Thread.sleep((line.dtMs / speed).toLong())
                val now = System.currentTimeMillis()
                write(SimScript.row(line, now, tz, Classifier::classify, Classifier.typer, simRules))
                fg.feed(line, now).forEach { (p, a, b) -> writeSession(p, a, b) }
            }
            fg.end(System.currentTimeMillis()).forEach { (p, a, b) -> writeSession(p, a, b) }
            write(mapOf("ts" to System.currentTimeMillis(), "kind" to "vpn", "note" to "simulation ${s.name} finished", "sim" to 1))
        } catch (_: InterruptedException) {
            fg.end(System.currentTimeMillis()).forEach { (p, a, b) -> writeSession(p, a, b) }
            Log.i("MyceliumSim", "replay stopped")
        }
    }
}
