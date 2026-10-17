package app.mycelium.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray

private class AppRow(val label: String, val pkg: String, val watched: Boolean, val lookups: Long)

/** Searchable app picker. Apply watches new apps before unwatching, so a swap never passes through 0 apps. */
@Composable
fun AppsScreen(onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<AppRow>()) }
    val selected = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(emptyList<AppRow>()) }   // newly watched apps still to get a preset
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onDone)
    if (picking.isNotEmpty()) {
        PresetPicker(picking.first(), onNext = { picking = picking.drop(1); if (picking.isEmpty()) onDone() })
        return
    }
    LaunchedEffect(query, showSystem) {
        rows = Api.io("apps", obj("query" to query, "include_system" to showSystem)).optJSONArray("apps").objects()
            .map { AppRow(it.getString("label"), it.getString("package"), it.getBoolean("watched"), it.getLong("lookups_24h")) }
        if (!loaded) { selected.addAll(rows.filter { it.watched }.map { it.pkg }); loaded = true }
    }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("← Back") }
            Text("Watching ${selected.size} apps", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search apps") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(showSystem, { showSystem = it })
            Text("Show system apps", Modifier.weight(1f))
            Button(enabled = loaded, onClick = {
                scope.launch {
                    val current = Api.io("watched").optJSONArray("apps").objects().map { it.getString("package") }.toSet()
                    val add = selected.toSet() - current
                    val remove = current - selected.toSet()
                    if (add.isNotEmpty()) message = Api.io("watch", obj("packages" to JSONArray(add.toList()))).toString()
                    if (remove.isNotEmpty()) message = Api.io("unwatch", obj("packages" to JSONArray(remove.toList()))).toString()
                    picking = rows.filter { it.pkg in add }
                    if (picking.isEmpty()) onDone()
                }
            }) { Text("Apply") }
        }
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth()) {
            items(rows, key = { it.pkg }) { r ->
                val on = r.pkg in selected
                Row(
                    Modifier.fillMaxWidth().clickable { if (on) selected.remove(r.pkg) else selected.add(r.pkg) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(on, { if (it) selected.add(r.pkg) else selected.remove(r.pkg) })
                    Column {
                        Text(r.label)
                        Text("${r.pkg} · ${r.lookups} lookups/24h", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Choose an organism for a newly watched app. "Keep" leaves the seed-picked default. */
@Composable
private fun PresetPicker(app: AppRow, onNext: () -> Unit) {
    var presets by remember { mutableStateOf(emptyList<JSONObject>()) }
    var current by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(app.pkg) {
        presets = Api.io("presets").optJSONArray("presets").objects()
        current = Api.io("terrarium", obj("app_id" to app.pkg)).optJSONArray("apps").objects().firstOrNull()?.optString("preset_id")
    }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Choose an organism for ${app.label}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onNext) { Text("Keep") }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(presets, key = { it.getString("id") }) { p ->
                val preset = p.getJSONObject("preset")
                Row(
                    Modifier.fillMaxWidth().clickable {
                        scope.launch { Api.io("set_preset", obj("app_id" to app.pkg, "preset_id" to p.getString("id"))); onNext() }
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetThumb(preset, 44.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(p.getString("name") + if (p.getString("id") == current) "  (current)" else "")
                        Text("${preset.optString("kingdom")} · ${p.getString("id")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Stage-1 thumbnail drawn from the preset's palette, kingdom and shape (the real sprites belong to the terrarium UI). */
@Composable
fun PresetThumb(preset: JSONObject, size: Dp) {
    val pal = preset.optJSONArray("palette")
    fun c(i: Int) = Color(android.graphics.Color.parseColor(pal?.optString(i) ?: "#9AA0A6"))
    val shape = preset.optJSONObject("shape")
    val branches = shape?.optInt("branches", 3) ?: 3
    val kingdom = preset.optString("kingdom")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(c(2), r)
        val base = Offset(center.x, center.y + r * 0.55f)
        val top = Offset(center.x, center.y - r * 0.15f)
        drawLine(c(0), base, top, strokeWidth = r * 0.12f, cap = StrokeCap.Round)
        when (kingdom) {
            "mushroom" -> drawArc(c(0), 180f, 180f, true, Offset(center.x - r * 0.5f, top.y - r * 0.3f), Size(r, r * 0.6f))
            else -> for (i in 0 until branches) {
                val a = Math.toRadians(-90.0 + (i - (branches - 1) / 2.0) * 35.0)
                val end = Offset(top.x + (cos(a) * r * 0.45f).toFloat(), top.y + (sin(a) * r * 0.45f).toFloat())
                drawLine(c(1), top, end, strokeWidth = r * 0.08f, cap = StrokeCap.Round)
                drawCircle(if (kingdom == "coral") c(0) else c(1), r * 0.09f, end)
            }
        }
    }
}

