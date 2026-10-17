package app.mycelium.engine.vpn

/** Pure app-selection rules (CLAUDE.md "App selection"). */
object WatchRules {
    data class State(val watched: Boolean, val ts: Long?)

    /** [changes]: rows to write. [rejected]: package -> reason (own_package | not_installed). */
    data class Plan(val changes: Map<String, State>, val rejected: Map<String, String>)


    fun watch(cur: Map<String, State>, pkgs: Collection<String>, own: String, now: Long, installed: (String) -> Boolean): Plan {
        val changes = linkedMapOf<String, State>()
        val rejected = linkedMapOf<String, String>()
        for (p in pkgs.distinct()) when {
            p == own -> rejected[p] = "own_package"
            !installed(p) -> rejected[p] = "not_installed"
            cur[p]?.watched != true -> changes[p] = State(true, now)
        }
        return Plan(changes, rejected)
    }

    /** Unwatched apps keep their data and their last watched_ts. */
    fun unwatch(cur: Map<String, State>, pkgs: Collection<String>): Plan {
        val changes = linkedMapOf<String, State>()
        for (p in pkgs.distinct()) cur[p]?.takeIf { it.watched }?.let { changes[p] = State(false, it.ts) }
        return Plan(changes, emptyMap())
    }

    fun apply(cur: Map<String, State>, plan: Plan): Map<String, State> = cur + plan.changes

    fun watched(cur: Map<String, State>, own: String) = cur.filter { it.value.watched && it.key != own }.keys.toList()

    /** Never watch "all apps": an empty allowed list would route every app. */
    fun startError(watched: Collection<String>, own: String): String? =
        if (watched.none { it != own }) "no_apps_selected" else null

    /** Default organism: seed (16 hex) mod preset count. */
    fun pickPreset(seed: String, presets: List<String>): String =
        presets[(seed.toULong(16) % presets.size.toULong()).toInt()]
}
