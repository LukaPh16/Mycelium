package app.mycelium.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.Role
import app.mycelium.app.ui.ChoiceRow
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.Protection
import app.mycelium.app.ui.SettingRow
import app.mycelium.app.ui.TextSize
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
fun SettingsScreen(prefs: UiPrefs, protection: Protection, start: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val go = LocalGo.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var pickCreature by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(Unit) { presets = Api.io("presets").optJSONArray("presets").objects() }
    val creatureName = presets.firstOrNull { it.getString("id") == prefs.newCreature }?.optString("name") ?: stringResource(R.string.set_creature_surprise)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_look))
            SettingRow(stringResource(R.string.set_theme), Info(R.string.info_theme_t, R.string.info_theme_b))
            ChoiceRow(listOf(ThemeMode.AUTO to stringResource(R.string.set_theme_auto), ThemeMode.DAY to stringResource(R.string.set_theme_day),
                ThemeMode.NIGHT to stringResource(R.string.set_theme_night)), prefs.theme, prefs::chooseTheme)
            SettingRow(stringResource(R.string.set_creature), Info(R.string.info_creature_t, R.string.info_creature_b), hint = creatureName,
                onClick = { pickCreature = true })
            SettingRow(stringResource(R.string.set_text_size), Info(R.string.info_text_size_t, R.string.info_text_size_b))
            ChoiceRow(listOf(TextSize.SMALL to stringResource(R.string.set_text_s), TextSize.NORMAL to stringResource(R.string.set_text_m),
                TextSize.LARGE to stringResource(R.string.set_text_l), TextSize.HUGE to stringResource(R.string.set_text_xl)), prefs.textSize, prefs::chooseTextSize)
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_motion))
            ToggleRow(stringResource(R.string.set_reduce_motion), prefs.reduceMotion, prefs::chooseReduceMotion, Info(R.string.info_reduce_motion_t, R.string.info_reduce_motion_b))
            ToggleRow(stringResource(R.string.set_saver), prefs.batterySaver, prefs::chooseBatterySaver, Info(R.string.info_saver_t, R.string.info_saver_b))
        }
        WeatherPanel(prefs)
        SoundPanel(prefs)
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_protection))
            SettingRow(stringResource(R.string.set_protection_now), Info(R.string.info_protection_t, R.string.info_protection_b), hint = protectionLabel(protection))
            if (protection == Protection.Paused) PixelButton(stringResource(R.string.prot_start), start, Modifier.fillMaxWidth())
            else if (protection != Protection.Demo) PixelButton(stringResource(R.string.prot_pause), {
                Api.call("vpn", obj("action" to "stop")); toast(ctx.getString(R.string.prot_paused_toast))
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            SettingRow(stringResource(R.string.nav_setup), Info(R.string.info_setup_t, R.string.info_setup_b), onClick = { go(Routes.SETUP) })
            ShooedList()
        }
        AiKeyPanel()
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_data))
            SettingRow(stringResource(R.string.set_privacy), Info(R.string.info_privacy_t, R.string.info_privacy_b), onClick = { go(Routes.ABOUT) })
            SettingRow(stringResource(R.string.set_export), Info(R.string.info_export_t, R.string.info_export_b), onClick = {
                scope.launch {
                    val r = Api.io("export_db")
                    toast(if (r.has("error")) ctx.getString(R.string.set_export_failed) else ctx.getString(R.string.set_exported, r.optString("path")))
                }
            })
            SettingRow(stringResource(R.string.set_wipe), Info(R.string.info_wipe_t, R.string.info_wipe_b), onClick = { confirmWipe = true })
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_advanced))
            ToggleRow(stringResource(R.string.set_advanced_switch), prefs.advanced, prefs::showAdvanced, Info(R.string.info_advanced_t, R.string.info_advanced_b))
            if (prefs.advanced) PixelButton(stringResource(R.string.set_open_advanced), { go(Routes.ADVANCED) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.set_about))
            SettingRow(stringResource(R.string.nav_help), Info(R.string.info_help_t, R.string.info_help_b), onClick = { go(Routes.HELP) })
            SettingRow(stringResource(R.string.nav_limits), Info(R.string.info_limits_t, R.string.info_limits_b), onClick = { go(Routes.LIMITS) })
            SettingRow(stringResource(R.string.nav_about), Info(R.string.info_about_t, R.string.info_about_b), onClick = { go(Routes.ABOUT) })
            SettingRow(stringResource(R.string.nav_licenses), Info(R.string.info_licenses_t, R.string.info_licenses_b), onClick = { go(Routes.LICENSES) })
        }
    }
    if (pickCreature) PixelDialog(stringResource(R.string.set_creature), onDismiss = { pickCreature = false },
        actions = { PixelButton(stringResource(R.string.close), { pickCreature = false }, kind = ButtonKind.Secondary) }) {
        PixelButton(stringResource(R.string.set_creature_surprise), { prefs.chooseNewCreature(""); pickCreature = false }, Modifier.fillMaxWidth(),
            kind = if (prefs.newCreature.isEmpty()) ButtonKind.Primary else ButtonKind.Secondary)
        for (p in presets) {
            val id = p.getString("id")
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { prefs.chooseNewCreature(id); pickCreature = false }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PresetThumb(p.getJSONObject("preset"), boxDp = 56)
                Spacer(Modifier.width(12.dp))
                Text(p.optString("name") + if (id == prefs.newCreature) " ✓" else "", style = MaterialTheme.typography.titleSmall, color = pal.ink)
            }
        }
    }
    if (confirmWipe) PixelDialog(stringResource(R.string.set_wipe_q), onDismiss = { confirmWipe = false }, actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PixelButton(stringResource(R.string.cancel), { confirmWipe = false }, kind = ButtonKind.Secondary)
            PixelButton(stringResource(R.string.set_wipe_ok), {
                confirmWipe = false
                scope.launch {
                    val r = Api.io("wipe", obj("confirm" to true))
                    toast(ctx.getString(if (r.has("error")) R.string.set_wipe_failed else R.string.set_wiped))
                }
            }, kind = ButtonKind.Danger)
        }
    }) { Text(stringResource(R.string.set_wipe_body), style = MaterialTheme.typography.bodyMedium, color = pal.ink) }
}

@Composable
fun protectionLabel(p: Protection): String = stringResource(when (p) {
    Protection.Protected -> R.string.status_protected
    Protection.Paused -> R.string.status_paused
    Protection.SafeMode -> R.string.safe_mode_long
    Protection.Demo -> R.string.status_demo
})

/** Label on the left, "i", and a switch on the right; the whole row toggles. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, info: Info) {
    val pal = LocalPal.current
    val sounds = app.mycelium.app.ui.LocalSounds.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .toggleable(checked, role = Role.Switch) { sounds?.play(if (it) "ui_toggle_on" else "ui_toggle_off"); onChange(it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = pal.ink)
        InfoButton(info)
        Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = pal.leaf, checkedThumbColor = pal.paper, uncheckedTrackColor = pal.paperEdge))
    }
}

/** Weather on the terrarium window and the season of its plants: chosen here, or the local weather (opt-in). */
@Composable
private fun WeatherPanel(prefs: UiPrefs) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var live by remember { mutableStateOf(app.mycelium.app.garden.Weather.cached(ctx)) }
    var looking by remember { mutableStateOf(false) }
    fun lookUp() { scope.launch { looking = true; live = app.mycelium.app.garden.Weather.refresh(ctx); looking = false } }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { prefs.chooseLiveWeather(true); lookUp() } else toast(ctx.getString(R.string.weather_denied))
    }
    PixelPanel(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.set_weather), Info(R.string.info_weather_t, R.string.info_weather_b))
        SettingRow(stringResource(R.string.set_weather_sky), null)
        ChoiceRow(listOf("AUTO" to stringResource(R.string.weather_auto), "CLEAR" to stringResource(R.string.weather_clear), "CLOUDY" to stringResource(R.string.weather_cloudy)),
            prefs.weatherMode, prefs::chooseWeather)
        ChoiceRow(listOf("RAIN" to stringResource(R.string.weather_rain), "STORM" to stringResource(R.string.weather_storm),
            "SNOW" to stringResource(R.string.weather_snow), "FOG" to stringResource(R.string.weather_fog)), prefs.weatherMode, prefs::chooseWeather)
        SettingRow(stringResource(R.string.set_weather_season), Info(R.string.info_season_t, R.string.info_season_b))
        ChoiceRow(listOf("AUTO" to stringResource(R.string.weather_auto), "SPRING" to stringResource(R.string.season_spring), "SUMMER" to stringResource(R.string.season_summer)),
            prefs.seasonMode, prefs::chooseSeason)
        ChoiceRow(listOf("AUTUMN" to stringResource(R.string.season_autumn), "WINTER" to stringResource(R.string.season_winter)), prefs.seasonMode, prefs::chooseSeason)
        ToggleRow(stringResource(R.string.set_weather_live), prefs.liveWeather, { on ->
            if (!on) { prefs.chooseLiveWeather(false); app.mycelium.app.garden.Weather.forget(ctx); live = null }
            else if (app.mycelium.app.garden.Weather.hasPermission(ctx)) { prefs.chooseLiveWeather(true); lookUp() }
            else ask.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }, Info(R.string.info_weather_live_t, R.string.info_weather_live_b))
        val status = when {
            !prefs.liveWeather -> stringResource(R.string.weather_live_off)
            looking -> stringResource(R.string.weather_looking)
            live == null -> stringResource(R.string.weather_unavailable)
            else -> stringResource(R.string.weather_live_now, weatherName(live!!.sky), live!!.tempC.toInt(), app.mycelium.app.ago(live!!.at))
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        if (prefs.liveWeather && prefs.weatherMode != "AUTO") Text(stringResource(R.string.weather_manual_wins), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
    }
}

@Composable
private fun weatherName(s: app.mycelium.app.garden.Sky): String = stringResource(when (s) {
    app.mycelium.app.garden.Sky.CLEAR -> R.string.weather_clear; app.mycelium.app.garden.Sky.CLOUDY -> R.string.weather_cloudy
    app.mycelium.app.garden.Sky.RAIN -> R.string.weather_rain; app.mycelium.app.garden.Sky.STORM -> R.string.weather_storm
    app.mycelium.app.garden.Sky.SNOW -> R.string.weather_snow; app.mycelium.app.garden.Sky.FOG -> R.string.weather_fog
})

/** Sound: mute everything, a master volume, and effects / jar ambience each with a switch and a level. */
@Composable
private fun SoundPanel(prefs: UiPrefs) {
    val pal = LocalPal.current
    val sounds = app.mycelium.app.ui.LocalSounds.current
    PixelPanel(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.set_sound))
        ToggleRow(stringResource(R.string.set_mute), prefs.muted, prefs::chooseMuted, Info(R.string.info_mute_t, R.string.info_mute_b))
        VolumeRow(stringResource(R.string.set_master), prefs.masterVolume, !prefs.muted, Info(R.string.info_master_t, R.string.info_master_b),
            onChange = prefs::chooseMasterVolume) { sounds?.play("creature_mushroom") }
        ToggleRow(stringResource(R.string.set_effects), prefs.effectsOn, prefs::chooseEffectsOn, Info(R.string.info_effects_t, R.string.info_effects_b))
        VolumeRow(stringResource(R.string.set_effects_volume), prefs.effectsVolume, !prefs.muted && prefs.effectsOn, null,
            onChange = prefs::chooseEffectsVolume) { sounds?.play("heal") }
        ToggleRow(stringResource(R.string.set_ambience), prefs.ambienceOn, prefs::chooseAmbienceOn, Info(R.string.info_ambience_t, R.string.info_ambience_b))
        VolumeRow(stringResource(R.string.set_ambience_volume), prefs.ambienceVolume, !prefs.muted && prefs.ambienceOn, null,
            onChange = prefs::chooseAmbienceVolume) {}
        Text(stringResource(R.string.set_sound_note), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
    }
}

/** A pixel-themed volume slider 0..100 with its value; [preview] plays when the thumb is let go. */
@Composable
private fun VolumeRow(label: String, value: Int, enabled: Boolean, info: Info?, onChange: (Int) -> Unit, preview: () -> Unit) {
    val pal = LocalPal.current
    var v by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (enabled) pal.ink else pal.inkMuted)
            Text(stringResource(R.string.set_volume_value, v.toInt()), style = MaterialTheme.typography.labelLarge, color = pal.inkMuted)
            if (info != null) InfoButton(info) else Spacer(Modifier.width(8.dp))
        }
        androidx.compose.material3.Slider(
            value = v, onValueChange = { v = it }, valueRange = 0f..100f, steps = 9, enabled = enabled,
            onValueChangeFinished = { onChange(v.toInt()); preview() },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = pal.leaf, activeTrackColor = pal.leaf, inactiveTrackColor = pal.paperEdge,
                activeTickColor = pal.moss, inactiveTickColor = pal.woodLight, disabledThumbColor = pal.inkMuted,
                disabledActiveTrackColor = pal.inkMuted, disabledInactiveTrackColor = pal.paperEdge,
            ),
        )
    }
}

/** Snoops the user shooed away, each with "Let back in". */
@Composable
private fun ShooedList() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val labels = rememberLabels()
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<JSONObject>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { rules = Api.io("rules").optJSONArray("rules").objects() }
    SettingRow(stringResource(R.string.set_shooed), Info(R.string.info_shooed_t, R.string.info_shooed_b))
    val list = rules ?: return
    if (list.isEmpty()) Text(stringResource(R.string.set_shooed_none), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
    for (r in list) {
        val pkg = r.getString("package")
        val where = if (pkg == "*") stringResource(R.string.set_shooed_everywhere) else labels[pkg] ?: shortPkg(pkg)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.getString("value"), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
                Text(where, style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
            }
            PixelButton(stringResource(R.string.set_let_back), {
                scope.launch {
                    val res = Api.io("unblock", obj("scope" to r.getString("scope"), "value" to r.getString("value"), "package" to pkg))
                    toast(if (res.has("error")) ctx.getString(R.string.set_let_back_failed) else ctx.getString(R.string.set_let_back_done, r.getString("value")))
                    reload++
                }
            }, kind = ButtonKind.Secondary)
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
    var model by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(JSONObject()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        status = Api.io("ai_status")
        if (status.optString("provider") != "openrouter") { Api.io("ai_settings", obj("provider" to "openrouter")); status = Api.io("ai_status") }   // OpenRouter only
        model = status.optString("model")
    }
    val state = when (status.optString("state")) {
        "ready" -> stringResource(R.string.ai_state_ready)
        "offline" -> stringResource(R.string.ai_state_offline)
        else -> stringResource(R.string.ai_state_no_key)
    }
    PixelPanel(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.set_ai), Info(R.string.info_ai_t, R.string.info_ai_b))
        Text(stringResource(R.string.set_ai_intro), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        Text(stringResource(R.string.set_ai_state, state), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.set_ai_model_hint)) }, singleLine = true)
        if (model.trim() != status.optString("model") && model.isNotBlank()) PixelButton(stringResource(R.string.set_ai_use_model), {
            scope.launch {
                status = Api.io("ai_settings", obj("model" to model.trim())).let { Api.io("ai_status") }
                toast(ctx.getString(R.string.set_ai_model_saved, status.optString("model")))
            }
        }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.set_ai_key_hint)) },
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
