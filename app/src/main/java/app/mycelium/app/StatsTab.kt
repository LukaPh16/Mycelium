package app.mycelium.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private class Period(val label: String, val ms: Long, val bucketMs: Long, val bucketLabel: String)

private val HOUR = 3600_000L
private val PERIODS = listOf(
    Period("24 h", 24 * HOUR, HOUR, "hour"),
    Period("7 d", 7 * 24 * HOUR, 6 * HOUR, "6 h"),
    Period("30 d", 30 * 24 * HOUR, 24 * HOUR, "day"),
)
private val BY = listOf("lookups", "trackers", "bytes", "background")

@Composable
fun StatsTab() {
    var watched by remember { mutableStateOf(emptyList<JSONObject>()) }
    var pkg by remember { mutableStateOf<String?>(null) }
    var period by remember { mutableStateOf(PERIODS[0]) }
    var by by remember { mutableStateOf("lookups") }
    var menu by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var timeline by remember { mutableStateOf(emptyList<JSONObject>()) }
    var topApps by remember { mutableStateOf(emptyList<JSONObject>()) }
    var topCompanies by remember { mutableStateOf(emptyList<JSONObject>()) }
    LaunchedEffect(Unit) {
        watched = Api.io("watched").optJSONArray("apps").objects()
        if (pkg == null) pkg = watched.firstOrNull()?.getString("package")
    }
    LaunchedEffect(pkg, period, by) {
        val to = System.currentTimeMillis()
        val from = to - period.ms
        fun args(vararg p: Pair<String, Any?>) = obj("from_ms" to from, "to_ms" to to, *p)
        summary = pkg?.let { Api.io("stats", args("fn" to "appSummary", "package" to it)) }
        timeline = Api.io("stats", args("fn" to "timeline", "bucket_ms" to period.bucketMs, "package" to pkg)).optJSONArray("buckets").objects()
        topApps = Api.io("stats", args("fn" to "topApps", "by" to by, "limit" to 10)).optJSONArray("apps").objects()
        topCompanies = Api.io("stats", args("fn" to "topCompanies", "limit" to 10)).optJSONArray("companies").objects()
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(watched.firstOrNull { it.getString("package") == pkg }?.getString("label") ?: "Pick app")
                }
                DropdownMenu(menu, { menu = false }) {
                    for (w in watched) DropdownMenuItem(text = { Text(w.getString("label")) }, onClick = { pkg = w.getString("package"); menu = false })
                }
            }
            for (p in PERIODS) Toggle(p.label, p == period) { period = p }
        }
        summary?.let { s ->
            if (s.has("error")) Text(s.toString()) else {
                Text(s.optString("label"), style = MaterialTheme.typography.titleMedium)
                Field("Lookups", "${s.optLong("lookups")} (${s.optLong("unique_domains")} domains)")
                Field("Tracker hits", "${s.optLong("tracker_hits")} (${s.optLong("companies")} companies)")
                Field("Blocked", "${s.optLong("blocked")} · tripwire ${s.optLong("tripwire_hits")}")
                Field("fg / bg / ?", "${s.optLong("fg")} / ${s.optLong("bg")} / ${s.optLong("fg_unknown")}")
                Field("Seen", "${dateTime(s.optLong("first_seen"))} → ${dateTime(s.optLong("last_seen"))}")
                Field("Bytes rx / tx", "${human(s.optLong("rx_bytes"))} / ${human(s.optLong("tx_bytes"))}")
                for (t in s.optJSONArray("trackers").objects()) {
                    Text("• ${t.getString("company")} (${t.optString("category")}) ${t.optLong("hits")}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        Text("Lookups per ${period.bucketLabel} (${if (pkg == null) "watched apps" else "selected app"}), red = tracker hits",
            style = MaterialTheme.typography.labelLarge)
        BarChart(timeline)
        HorizontalDivider()
        Text("Top apps", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (b in BY) Toggle(b, b == by) { by = b } }
        if (topApps.isEmpty()) Text("No data", style = MaterialTheme.typography.bodySmall)
        for (a in topApps) {
            val v = a.optLong("value")
            Field(a.optString("label", a.getString("package")), if (by == "bytes") human(v) else v.toString())
        }
        HorizontalDivider()
        Text("Top companies", style = MaterialTheme.typography.titleMedium)
        if (topCompanies.isEmpty()) Text("No data", style = MaterialTheme.typography.bodySmall)
        for (c in topCompanies) Field(c.getString("company"), "${c.optLong("hits")} hits · ${c.optLong("apps")} apps · ${c.optString("category")}")
    }
}

@Composable
private fun Toggle(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else OutlinedButton(onClick = onClick) { Text(label) }
}

@Composable
private fun BarChart(buckets: List<JSONObject>) {
    val bar = MaterialTheme.colorScheme.primary
    val tracker = MaterialTheme.colorScheme.error
    val max = (buckets.maxOfOrNull { it.optLong("lookups") } ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        if (buckets.isEmpty()) return@Canvas
        val w = size.width / buckets.size
        buckets.forEachIndexed { i, b ->
            for ((key, color) in listOf("lookups" to bar, "tracker_hits" to tracker)) {
                val h = size.height * b.optLong(key) / max
                drawRect(color, Offset(i * w + w * 0.1f, size.height - h), Size(w * 0.8f, h))
            }
        }
    }
    Text("max $max per bucket", style = MaterialTheme.typography.labelSmall)
}
