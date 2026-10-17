package app.mycelium.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI-only settings (theme, advanced tools). Engine settings live in the engine. */
class UiPrefs(context: Context) {
    private val sp = context.getSharedPreferences("mycelium_ui", Context.MODE_PRIVATE)

    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(sp.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.AUTO))
        private set
    var advanced by mutableStateOf(sp.getBoolean("advanced", false))
        private set

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        sp.edit().putString("theme", mode.name).apply()
    }

    fun showAdvanced(on: Boolean) {
        advanced = on
        sp.edit().putBoolean("advanced", on).apply()
    }
}
