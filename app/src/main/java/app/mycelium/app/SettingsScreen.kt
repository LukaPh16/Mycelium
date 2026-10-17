package app.mycelium.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.SectionHeader
import app.mycelium.app.ui.ThemeMode
import app.mycelium.app.ui.SwitchRow
import app.mycelium.app.ui.UiPrefs
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SettingsScreen(prefs: UiPrefs) {
    val pal = LocalPal.current
    val go = LocalGo.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_look), Info(R.string.info_theme_t, R.string.info_theme_b))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((mode, label) in listOf(ThemeMode.AUTO to R.string.set_theme_auto, ThemeMode.DAY to R.string.set_theme_day, ThemeMode.NIGHT to R.string.set_theme_night)) {
                    PixelButton(stringResource(label), { prefs.chooseTheme(mode) }, Modifier.weight(1f),
                        kind = if (prefs.theme == mode) ButtonKind.Primary else ButtonKind.Secondary)
                }
            }
        }
        AiKeyPanel()
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_advanced), Info(R.string.info_advanced_t, R.string.info_advanced_b))
            SwitchRow(stringResource(R.string.set_advanced_switch), prefs.advanced, { prefs.showAdvanced(it) }, Modifier.fillMaxWidth())
            if (prefs.advanced) PixelButton(stringResource(R.string.set_open_advanced), { go(Routes.ADVANCED) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_more))
            for ((route, label) in listOf(Routes.SETUP to R.string.nav_setup, Routes.HELP to R.string.nav_help, Routes.ABOUT to R.string.nav_about)) {
                PixelButton(stringResource(label), { go(route) }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        }
    }
}

/** Paste or remove the user's own AI key. The field is not saved with the screen, so the key never lands in saved state. */
@Composable
private fun AiKeyPanel() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(JSONObject()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { status = Api.io("ai_status") }
    val state = when (status.optString("state")) {
        "ready" -> stringResource(R.string.ai_state_ready)
        "offline" -> stringResource(R.string.ai_state_offline)
        else -> stringResource(R.string.ai_state_no_key)
    }
    PixelPanel(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.set_ai), Info(R.string.info_ai_t, R.string.info_ai_b))
        Text(stringResource(R.string.set_ai_state, state), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        Text(stringResource(R.string.set_ai_model, status.optString("model")), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.set_ai_key_hint)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(stringResource(R.string.set_ai_save), {
                scope.launch {
                    val r = Api.io("ai_key", obj("key" to key.trim()))
                    key = ""
                    toast(if (r.has("error")) ctx.getString(R.string.set_ai_bad, r.optString("error")) else ctx.getString(R.string.set_ai_saved))
                    status = Api.io("ai_status")
                }
            }, Modifier.weight(1f), enabled = key.isNotBlank())
            PixelButton(stringResource(R.string.set_ai_clear), {
                scope.launch { Api.io("ai_key", obj("clear" to true)); toast(ctx.getString(R.string.set_ai_removed)); status = Api.io("ai_status") }
            }, Modifier.weight(1f), kind = ButtonKind.Secondary)
        }
    }
}
