package app.mycelium.engine.terrarium

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Emits {type:"terrarium_update", app_id, stage, stage_shown, counts} at most once per second per app, and at once
 * when the stage changes. Growth is tracked in memory between DB recomputes so a stage change is seen per packet.
 */
class TerrariumFeed(
    private val terrarium: Terrarium,
    private val emit: (String) -> Unit,
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "terrarium").apply { isDaemon = true } },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Seen(var growth: Double, var stage: Int, var thresholds: List<Double>, var emittedAt: Long, var scheduled: Boolean = false)

    private val seen = HashMap<String, Seen>()

    /** A packet row for [pkg] was written. */
    fun touch(pkg: String, ptype: String?) = exec.execute { onPacket(pkg, ptype) }

    /** Recompute and emit every app now (selection, preset, ack, traffic or simulation changed). */
    fun touchAll() = exec.execute { terrarium.list().forEach { publish(it) } }

    fun touchApp(pkg: String) = exec.execute { recompute(pkg) }

    private fun onPacket(pkg: String, ptype: String?) {
        val s = seen[pkg] ?: return recompute(pkg)
        s.growth += terrarium.weight(ptype)
        if (Terrarium.stage(s.growth, s.thresholds).first != s.stage) {
            // Predicted stage change: confirm against the DB; emit at once only if the stage really moved.
            val app = terrarium.forApp(pkg) ?: run { seen.remove(pkg); return }
            if (app.getValue("stage").jsonPrimitive.int != s.stage) return publish(app)
            s.growth = app.getValue("growth").jsonPrimitive.content.toDouble()
        }
        val wait = MIN_GAP_MS - (now() - s.emittedAt)
        when {
            wait <= 0 -> recompute(pkg)
            !s.scheduled -> {
                s.scheduled = true
                exec.schedule({ s.scheduled = false; recompute(pkg) }, wait, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun recompute(pkg: String) {
        val app = terrarium.forApp(pkg)
        if (app == null) { seen.remove(pkg); return }
        publish(app)
    }

    private fun publish(app: JsonObject) {
        val pkg = app.getValue("app_id").jsonPrimitive.content
        val stage = app.getValue("stage").jsonPrimitive.int
        val growth = app.getValue("growth").jsonPrimitive.content.toDouble()
        val thresholds = terrarium.thresholdsFor(app.getValue("preset_id").jsonPrimitive.content)
        seen.getOrPut(pkg) { Seen(growth, stage, thresholds, 0) }.apply {
            this.growth = growth; this.stage = stage; this.thresholds = thresholds; emittedAt = now()
        }
        emit(buildJsonObject {
            put("type", "terrarium_update")
            put("app_id", pkg)
            put("stage", stage)
            put("stage_shown", app.getValue("stage_shown"))
            put("counts", app.getValue("packet_counts_by_type"))
        }.toString())
    }

    companion object {
        const val MIN_GAP_MS = 1000L
    }
}
