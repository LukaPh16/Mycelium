package app.mycelium.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun LiveTab(feed: List<JSONObject>) {
    var filter by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<JSONObject?>(null) }
    val pkgs = feed.mapNotNull { it.optString("package").ifEmpty { null } }.distinct().sorted()
    val rows = if (filter == null) feed else feed.filter { it.optString("package") == filter }
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${rows.size} events", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { menu = true }) { Text(filter?.let(::shortPkg) ?: "All apps") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("All apps") }, onClick = { filter = null; menu = false })
                    for (p in pkgs) DropdownMenuItem(text = { Text(p) }, onClick = { filter = p; menu = false })
                }
            }
        }
        LazyColumn {
            items(rows, key = { it.optLong("id") }) { e ->
                EventRow(e, Modifier.clickable { shown = e })
                HorizontalDivider()
            }
        }
    }
    shown?.let { e ->
        AlertDialog(
            onDismissRequest = { shown = null },
            confirmButton = { TextButton(onClick = { shown = null }) { Text("Close") } },
            title = { Text("Event #${e.optLong("id")}") },
            text = { Text(e.toString(2), Modifier.verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace) },
        )
    }
}

@Composable
private fun EventRow(e: JSONObject, modifier: Modifier) {
    val kind = e.optString("kind")
    val what = e.optString("domain").ifEmpty { e.optString("note") }.ifEmpty { if (e.has("port")) "port ${e.optInt("port")}" else kind }
    val verdict = e.optString("verdict")
    val color = if (verdict == "blocked" || kind == "anomaly") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row {
            Text(hhmmss(e.optLong("ts")), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text("  " + e.optString("package").ifEmpty { "?" }.let(::shortPkg), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text("  $what", color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        val tags = listOf(e.optString("ptype").ifEmpty { kind }, e.optString("company"), e.optString("category"), verdict, e.optString("how"),
            if (e.optInt("sim") == 1) "sim" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ") + if (e.optInt("watched", 1) == 0) " · not currently watched" else ""
        Text(tags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
