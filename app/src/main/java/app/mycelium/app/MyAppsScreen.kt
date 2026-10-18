package app.mycelium.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.ErrorState
import app.mycelium.app.ui.LoadingState
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.Protection
import app.mycelium.app.ui.UiPrefs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelBitmap
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.SectionHeader
import app.mycelium.app.ui.SwitchRow
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private class AppRow(val label: String, val pkg: String, val watched: Boolean, val contacts: Long, val system: Boolean)

/** Choose the apps to watch: one big switch per app, saved at once. */
@Composable
fun MyAppsScreen(prefs: UiPrefs, protection: Protection) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<AppRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<AppRow?>(null) }
    var confirmLast by remember { mutableStateOf<AppRow?>(null) }
    var resetting by remember { mutableStateOf<AppRow?>(null) }
    val busy = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    picking?.let { r -> PresetPicker(r.pkg, r.label, onDone = { picking = null }); return }
    LaunchedEffect(query, showSystem, reload) {
        val r = Api.io("apps", obj("query" to query, "include_system" to showSystem))
        failed = r.has("error")
        rows = r.optJSONArray("apps").objects()
            .map { AppRow(it.getString("label"), it.getString("package"), it.getBoolean("watched"), it.optLong("lookups_24h"), it.optBoolean("system")) }
    }
    val watching = rows?.count { it.watched } ?: 0

    fun toggle(r: AppRow, on: Boolean) {
        scope.launch {
            busy += r.pkg
            val res = if (on) {
                val presets = if (prefs.newCreature.isNotEmpty()) obj(r.pkg to prefs.newCreature) else null
                Api.io("watch", obj("packages" to JSONArray(listOf(r.pkg)), "presets" to presets))
            } else Api.io("unwatch", obj("packages" to JSONArray(listOf(r.pkg))))
            val rejected = res.optJSONObject("rejected")?.optString(r.pkg).orEmpty()
            toast(when {
                res.has("error") || rejected.isNotEmpty() -> ctx.getString(R.string.apps_failed, r.label)
                on -> ctx.getString(R.string.apps_now_watching, r.label)
                else -> ctx.getString(R.string.apps_stopped_watching, r.label)
            })
            busy -= r.pkg
            // update this row in place: the list keeps its order, so it never jumps while you scroll
            if (!res.has("error") && rejected.isEmpty()) rows = rows?.map { if (it.pkg == r.pkg) AppRow(it.label, it.pkg, on, it.contacts, it.system) else it }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.apps_watching, watching, watching), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, color = pal.ink)
                InfoButton(Info(R.string.info_watching_t, R.string.info_watching_b))
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.apps_search)) }, singleLine = true)
            SwitchRow(stringResource(R.string.apps_show_system), showSystem, { showSystem = it }, info = Info(R.string.info_system_t, R.string.info_system_b))
        }
        val list = rows
        when {
            failed -> ErrorState(stringResource(R.string.apps_error_title), stringResource(R.string.apps_error_body), { reload++ })
            list == null -> LoadingState(stringResource(R.string.apps_loading))
            list.isEmpty() -> EmptyState(R.drawable.sprite_mushroom_wow, stringResource(R.string.apps_none_title),
                if (query.isBlank()) stringResource(R.string.apps_none) else stringResource(R.string.apps_no_match, query))
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.pkg }) { r ->
                    AppLine(r, enabled = r.pkg !in busy, onToggle = { on ->
                        if (!on && watching == 1 && protection != Protection.Paused) confirmLast = r else toggle(r, on)
                    }, onCreature = { picking = r }, onReset = { resetting = r })
                }
            }
        }
    }
    resetting?.let { r -> ResetRecordDialog(r.pkg, r.label) { done -> resetting = null; if (done) rows = rows?.map { if (it.pkg == r.pkg) AppRow(it.label, it.pkg, it.watched, 0, it.system) else it } } }
    confirmLast?.let { r ->
        PixelDialog(stringResource(R.string.apps_last_q), onDismiss = { confirmLast = null }, actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelButton(stringResource(R.string.not_now), { confirmLast = null }, kind = ButtonKind.Secondary)
                PixelButton(stringResource(R.string.apps_last_ok), { confirmLast = null; toggle(r, false) }, kind = ButtonKind.Danger)
            }
        }) { Text(stringResource(R.string.apps_last_body, r.label), style = MaterialTheme.typography.bodyMedium, color = pal.ink) }
    }
}

@Composable
private fun AppLine(r: AppRow, enabled: Boolean, onToggle: (Boolean) -> Unit, onCreature: () -> Unit, onReset: () -> Unit) {
    val pal = LocalPal.current
    Row(
        Modifier
            .fillMaxWidth()
            .pixelPanel(if (r.watched) PanelStyle.Paper else PanelStyle.Quiet, pal, pixel())
            .toggleable(r.watched, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.label, style = MaterialTheme.typography.bodyLarge, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (r.watched) pluralStringResource(R.plurals.apps_contacts, r.contacts.toInt(), r.contacts.toInt())
                else stringResource(if (r.system) R.string.apps_system_app else R.string.apps_not_watched),
                style = MaterialTheme.typography.bodySmall, color = pal.inkMuted,
            )
            if (r.watched) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for ((label, action) in listOf(R.string.apps_change_creature to onCreature, R.string.reset_record to onReset)) Text(
                    stringResource(label),
                    Modifier.defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button, onClick = action).padding(vertical = 14.dp),
                    style = MaterialTheme.typography.labelLarge, color = if (pal.night) pal.gold else pal.wood,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(r.watched, null, enabled = enabled, modifier = Modifier.scale(1.25f),
            colors = SwitchDefaults.colors(checkedTrackColor = pal.leaf, checkedThumbColor = pal.paper, uncheckedTrackColor = pal.paperEdge))
    }
}

/** Pick a creature for one app; shows what each preset grows into (its last stage sprite). */
@Composable
fun PresetPicker(pkg: String, label: String, onDone: () -> Unit) {
    val pal = LocalPal.current
    var presets by remember { mutableStateOf(emptyList<JSONObject>()) }
    var current by remember { mutableStateOf<String?>(null) }
    var look by remember { mutableStateOf<String?>(null) }
    val atlas = app.mycelium.app.garden.SpriteAtlas.of(LocalContext.current)
    val scope = rememberCoroutineScope()
    LaunchedEffect(pkg) {
        presets = Api.io("presets").optJSONArray("presets").objects()
        current = Api.io("terrarium", obj("app_id" to pkg)).optJSONArray("apps").objects().firstOrNull()?.optString("preset_id")
        val seed = Api.io("garden", obj("include_unwatched" to true)).optJSONArray("apps").objects()
            .firstOrNull { it.optString("package") == pkg }?.optString("seed").orEmpty().ifEmpty { pkg }
        look = app.mycelium.app.garden.lookFor(atlas, seed)   // preview in this app's own colors
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preset_title, label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = pal.ink)
            InfoButton(Info(R.string.info_preset_t, R.string.info_preset_b))
        }
        PixelButton(stringResource(R.string.preset_keep), onDone, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        if (presets.isEmpty()) LoadingState(stringResource(R.string.loading))
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(presets, key = { it.getString("id") }) { p ->
                val id = p.getString("id")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .then(if (id == current) Modifier.pixelPanel(PanelStyle.Quiet, pal, pixel()) else Modifier)
                        .clickable(role = Role.Button) { scope.launch { Api.io("set_preset", obj("app_id" to pkg, "preset_id" to id)); onDone() } }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetThumb(p.getJSONObject("preset"), boxDp = 96, look = look)   // what it grows into
                    Spacer(Modifier.width(16.dp))
                    Text(p.getString("name") + if (id == current) " " + stringResource(R.string.preset_current) else "",
                        style = MaterialTheme.typography.titleSmall, color = pal.ink)
                }
            }
        }
    }
}

/** What a creature type looks like (the terrarium art, adult stage), in an app's own colors when [look] is given. */
@Composable
fun PresetThumb(preset: JSONObject, boxDp: Int = 64, stageNo: Int = 3, look: String? = null) {
    val atlas = app.mycelium.app.garden.SpriteAtlas.of(LocalContext.current)
    val id = preset.optString("id")
    val s = remember(id, stageNo, look) {
        atlas.creature("creature_${id}_s${stageNo.coerceIn(1, 3)}", look ?: atlas.looks.firstOrNull().orEmpty(), 0, 0)
    }
    Box(Modifier.size(boxDp.dp), contentAlignment = Alignment.BottomCenter) {
        PixelBitmap(s.image, artPixelDp = boxDp.toFloat() / maxOf(s.w, s.h), srcSize = IntSize(s.w, s.h))
    }
}

/** "Reset this app's record": deletes what Mycelium noticed about one app; its creature starts over. */
@Composable
fun ResetRecordDialog(pkg: String, label: String, onDone: (Boolean) -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    PixelDialog(stringResource(R.string.reset_record_q, label), onDismiss = { onDone(false) }, actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PixelButton(stringResource(R.string.cancel), { onDone(false) }, kind = ButtonKind.Secondary)
            PixelButton(stringResource(R.string.reset_record_ok), {
                scope.launch {
                    val r = Api.io("wipe", obj("confirm" to true, "package" to pkg))
                    toast(if (r.has("error")) ctx.getString(R.string.reset_record_failed) else ctx.getString(R.string.reset_record_done, label))
                    onDone(!r.has("error"))
                }
            }, kind = ButtonKind.Danger)
        }
    }) { Text(stringResource(R.string.reset_record_body, label), style = MaterialTheme.typography.bodyMedium, color = pal.ink) }
}
