package app.mycelium.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

private class GardenApp(val app: JSONObject, val parasites: List<JSONObject>, val organism: JSONObject?)

@Composable
fun GardenTab() {
    var apps by remember { mutableStateOf<List<GardenApp>?>(null) }
    var rules by remember { mutableStateOf(emptyList<JSONObject>()) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var colors by remember { mutableStateOf(emptyMap<String, Color>()) }
    LaunchedEffect(Unit) {
        colors = Api.io("packet_types").optJSONArray("types").objects()
            .associate { it.getString("id") to Color(android.graphics.Color.parseColor(it.getString("color"))) }
    }
    LaunchedEffect(reload) {
        while (true) {
            val organisms = Api.io("terrarium").optJSONArray("apps").objects().associateBy { it.getString("app_id") }
            apps = Api.io("garden").optJSONArray("apps").objects().map { a ->
                val summary = Api.io("stats", obj("fn" to "appSummary", "package" to a.getString("package")))
                GardenApp(a, summary.optJSONArray("trackers").objects(), organisms[a.getString("package")])
            }
            rules = Api.io("rules").optJSONArray("rules").objects().filter { it.getString("scope") == "company" }
            delay(10_000)
        }
    }
    /** The company rule covering this app: its own, else the all-apps one. */
    fun ruleFor(company: String, pkg: String) = rules.firstOrNull { it.getString("value").equals(company, true) && it.getString("package") == pkg }
        ?: rules.firstOrNull { it.getString("value").equals(company, true) && it.getString("package") == "*" }

    val list = apps
    if (list == null) { Text("Loading…", Modifier.padding(top = 24.dp)); return }
    if (list.isEmpty()) { Text("No watched apps. Choose apps on the Status tab.", Modifier.padding(top = 24.dp)); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall) }
        items(list, key = { it.app.getString("package") }) { g ->
            val a = g.app
            val pkg = a.getString("package")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SeedGlyph(a.optString("seed", pkg), 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(a.optString("label", pkg), fontWeight = FontWeight.Bold)
                            Text("${a.optLong("lookups_24h")} lookups · ${human(a.optLong("bytes_24h"))} · ${a.optLong("tracker_hits_24h")} tracker hits",
                                style = MaterialTheme.typography.bodySmall)
                            Text("${a.optString("preset_id", "no organism")} · ${a.optLong("blocked_24h")} blocked · ${a.optLong("anomalies_24h")} anomalies · 24 h",
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    g.organism?.let { OrganismCounts(it, colors) }
                    if (g.parasites.isEmpty()) Text("No parasites seen in 24 h", style = MaterialTheme.typography.labelSmall)
                    for (p in g.parasites) {
                        val company = p.getString("company")
                        val rule = ruleFor(company, pkg)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$company · ${p.optString("category")} · ${p.optLong("hits")}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                scope.launch {
                                    val args = obj("scope" to "company", "value" to company, "package" to (rule?.getString("package") ?: pkg))
                                    message = Api.io(if (rule == null) "block" else "unblock", args).toString()
                                    reload++
                                }
                            }) { Text(if (rule == null) "Block" else if (rule.getString("package") == "*") "Unblock (all apps)" else "Unblock") }
                        }
                    }
                }
            }
        }
    }
}

/** Stage, growth and one colored bar per packet type with a non-zero count (colors from packet_types.json). */
@Composable
private fun OrganismCounts(o: JSONObject, colors: Map<String, Color>) {
    val next = if (o.isNull("next_threshold")) "top stage" else "next at ${o.optDouble("next_threshold").toLong()}"
    Text("Stage ${o.optInt("stage")}/${o.optInt("stage_count")} (shown ${o.optInt("stage_shown")}) · growth ${o.optDouble("growth")} · $next" +
        if (o.optLong("sanctuary_pending") > 0) " · ${o.optLong("sanctuary_pending")} waiting in Sanctuary" else "",
        style = MaterialTheme.typography.labelMedium)
    val counts = o.getJSONObject("packet_counts_by_type")
    val nonZero = counts.keys().asSequence().map { it to counts.getLong(it) }.filter { it.second > 0 }.toList()
    val max = (nonZero.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
    for ((type, n) in nonZero) Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width((4 + 116f * n / max).dp).height(8.dp).background(colors[type] ?: Color(0xFF9AA0A6)))
        Text("  $type $n", style = MaterialTheme.typography.labelSmall)
    }
}

/** Circle + N branches; hue, branch count, angle and lengths all come from the seed. */
@Composable
fun SeedGlyph(seed: String, size: Dp) {
    val v = seed.padEnd(16, '0').take(16).chunked(2).map { it.toIntOrNull(16) ?: 0 }
    val color = Color.hsv(((v[0] * 256 + v[1]) % 360).toFloat(), 0.55f, 0.75f)
    val branches = 3 + v[2] % 6
    val rotation = v[3] / 255.0 * 360.0
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        val stroke = 3.dp.toPx()
        for (i in 0 until branches) {
            val angle = Math.toRadians(rotation + i * 360.0 / branches)
            val len = r * (0.55f + v[(4 + i) % 8] / 255f * 0.4f)
            val end = Offset(center.x + (cos(angle) * len).toFloat(), center.y + (sin(angle) * len).toFloat())
            drawLine(color, center, end, strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(color, radius = stroke * 1.2f, center = end)
        }
        drawCircle(color, radius = r * 0.3f, center = center)
    }
}
