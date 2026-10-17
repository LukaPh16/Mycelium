package app.mycelium.app

import android.graphics.BitmapFactory
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

private class AppRow(val label: String, val pkg: String, val watched: Boolean, val lookups: Long)

/** Choose the apps to watch. Saving watches new apps before unwatching, so a swap never passes through 0 apps. */
@Composable
fun MyAppsScreen() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<AppRow>()) }
    val selected = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(emptyList<AppRow>()) }
    val scope = rememberCoroutineScope()
    if (picking.isNotEmpty()) {
        PresetPicker(picking.first().pkg, picking.first().label, onDone = { picking = picking.drop(1) })
        return
    }
    LaunchedEffect(query, showSystem) {
        rows = Api.io("apps", obj("query" to query, "include_system" to showSystem)).optJSONArray("apps").objects()
            .map { AppRow(it.getString("label"), it.getString("package"), it.getBoolean("watched"), it.getLong("lookups_24h")) }
        if (!loaded) { selected.addAll(rows.filter { it.watched }.map { it.pkg }); loaded = true }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.apps_search)) }, singleLine = true)
            SwitchRow(stringResource(R.string.apps_show_system), showSystem, { showSystem = it }, info = Info(R.string.info_system_t, R.string.info_system_b))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            if (loaded && rows.isEmpty()) item {
                Text(if (query.isBlank()) stringResource(R.string.apps_none) else stringResource(R.string.apps_no_match, query),
                    Modifier.padding(16.dp), color = pal.inkMuted)
            }
            items(rows, key = { it.pkg }) { r ->
                val on = r.pkg in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .then(if (on) Modifier.pixelPanel(PanelStyle.Quiet, pal, pixel()) else Modifier)
                        .clickable(role = Role.Checkbox) { if (on) selected.remove(r.pkg) else selected.add(r.pkg) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, null, colors = CheckboxDefaults.colors(checkedColor = pal.leaf, checkmarkColor = pal.onAccent))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = MaterialTheme.typography.bodyLarge, color = pal.ink)
                        Text(pluralStringResource(R.plurals.apps_lookups, r.lookups.toInt(), r.lookups.toInt()),
                            style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().pixelPanel(PanelStyle.Wood, pal, pixel()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(pluralStringResource(R.plurals.apps_watching, selected.size, selected.size), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, color = if (pal.night) pal.ink else pal.onAccent)
            PixelButton(stringResource(R.string.apps_apply), enabled = loaded, onClick = {
                scope.launch {
                    val current = Api.io("watched").optJSONArray("apps").objects().map { it.getString("package") }.toSet()
                    val add = selected.toSet() - current
                    val remove = current - selected.toSet()
                    val r1 = if (add.isNotEmpty()) Api.io("watch", obj("packages" to JSONArray(add.toList()))) else JSONObject()
                    val r2 = if (remove.isNotEmpty()) Api.io("unwatch", obj("packages" to JSONArray(remove.toList()))) else JSONObject()
                    val err = r1.optString("error").ifEmpty { r2.optString("error") }
                    toast(if (err.isEmpty()) ctx.getString(R.string.apps_saved) else ctx.getString(R.string.error_generic, err))
                    picking = rows.filter { it.pkg in add }
                }
            })
        }
    }
}

/** Pick a creature for one app; shows what each preset grows into (its last stage sprite). */
@Composable
fun PresetPicker(pkg: String, label: String, onDone: () -> Unit) {
    val pal = LocalPal.current
    var presets by remember { mutableStateOf(emptyList<JSONObject>()) }
    var current by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pkg) {
        presets = Api.io("presets").optJSONArray("presets").objects()
        current = Api.io("terrarium", obj("app_id" to pkg)).optJSONArray("apps").objects().firstOrNull()?.optString("preset_id")
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preset_title, label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = pal.ink)
            InfoButton(Info(R.string.info_preset_t, R.string.info_preset_b))
        }
        PixelButton(stringResource(R.string.preset_keep), onDone, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
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
                    PresetThumb(p.getJSONObject("preset"), boxDp = 96, stageNo = Int.MAX_VALUE)   // what it grows into
                    Spacer(Modifier.width(14.dp))
                    Text(p.getString("name") + if (id == current) " " + stringResource(R.string.preset_current) else "",
                        style = MaterialTheme.typography.titleSmall, color = pal.ink)
                }
            }
        }
    }
}

private val thumbCache = HashMap<String, ImageBitmap?>()

/** First frame of the preset's strip for [stageNo], nearest-neighbor, in a fixed box. */
@Composable
fun PresetThumb(preset: JSONObject, boxDp: Int = 64, stageNo: Int = 1) {
    val ctx = LocalContext.current
    val id = preset.optString("id")
    val stages = preset.optJSONArray("stages")
    val stage = stages?.optJSONObject((stageNo - 1).coerceIn(0, maxOf(0, (stages.length()) - 1)))
    val key = "$id/${stage?.optString("file")}"
    val img = remember(key) {
        thumbCache.getOrPut(key) {
            runCatching {
                ctx.assets.open("organisms/$id/${stage?.optString("file") ?: "stage1.png"}").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    Box(Modifier.size(boxDp.dp), contentAlignment = Alignment.BottomCenter) {
        if (img != null && stage != null) {
            val fw = stage.optInt("frame_w", img.width)
            val fh = stage.optInt("frame_h", img.height)
            val fit = (boxDp.toFloat() / maxOf(fw, fh)).coerceAtLeast(0.5f)
            PixelBitmap(img, artPixelDp = fit, srcSize = IntSize(fw, fh))
        }
    }
}
