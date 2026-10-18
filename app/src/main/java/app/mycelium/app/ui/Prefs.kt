package app.mycelium.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class TextSize(val scale: Float) { SMALL(0.9f), NORMAL(1f), LARGE(1.15f), HUGE(1.3f) }

/** UI-only settings (look, motion, advanced tools). Engine settings live in the engine. */
class UiPrefs(context: Context) {
    private val sp = context.getSharedPreferences("mycelium_ui", Context.MODE_PRIVATE)

    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(sp.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.AUTO))
        private set
    var textSize by mutableStateOf(runCatching { TextSize.valueOf(sp.getString("text_size", null) ?: "") }.getOrDefault(TextSize.NORMAL))
        private set
    var reduceMotion by mutableStateOf(sp.getBoolean("reduce_motion", false))
        private set
    var batterySaver by mutableStateOf(sp.getBoolean("battery_saver", false))
        private set
    /** Creature for newly watched apps; empty = a surprise (the engine picks one from the app's seed). */
    var newCreature by mutableStateOf(sp.getString("new_creature", "") ?: "")
        private set
    var advanced by mutableStateOf(sp.getBoolean("advanced", false))
        private set
    var muted by mutableStateOf(sp.getBoolean("muted", false))
        private set
    var masterVolume by mutableStateOf(sp.getInt("master_volume", 80))
        private set
    var effectsOn by mutableStateOf(sp.getBoolean("effects_on", true))
        private set
    var effectsVolume by mutableStateOf(sp.getInt("effects_volume", 90))
        private set
    var ambienceOn by mutableStateOf(sp.getBoolean("ambience_on", true))
        private set
    var ambienceVolume by mutableStateOf(sp.getInt("ambience_volume", 50))
        private set

    fun chooseTheme(mode: ThemeMode) { theme = mode; sp.edit().putString("theme", mode.name).apply() }
    fun chooseTextSize(size: TextSize) { textSize = size; sp.edit().putString("text_size", size.name).apply() }
    fun chooseReduceMotion(on: Boolean) { reduceMotion = on; sp.edit().putBoolean("reduce_motion", on).apply() }
    fun chooseBatterySaver(on: Boolean) { batterySaver = on; sp.edit().putBoolean("battery_saver", on).apply() }
    fun chooseNewCreature(presetId: String) { newCreature = presetId; sp.edit().putString("new_creature", presetId).apply() }
    fun chooseMuted(on: Boolean) { muted = on; sp.edit().putBoolean("muted", on).apply() }
    fun chooseMasterVolume(v: Int) { masterVolume = v; sp.edit().putInt("master_volume", v).apply() }
    fun chooseEffectsOn(on: Boolean) { effectsOn = on; sp.edit().putBoolean("effects_on", on).apply() }
    fun chooseEffectsVolume(v: Int) { effectsVolume = v; sp.edit().putInt("effects_volume", v).apply() }
    fun chooseAmbienceOn(on: Boolean) { ambienceOn = on; sp.edit().putBoolean("ambience_on", on).apply() }
    fun chooseAmbienceVolume(v: Int) { ambienceVolume = v; sp.edit().putInt("ambience_volume", v).apply() }
    fun showAdvanced(on: Boolean) { advanced = on; sp.edit().putBoolean("advanced", on).apply() }
}
