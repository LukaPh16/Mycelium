package app.mycelium.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun ToolsTab() {
    var sql by rememberSaveable { mutableStateOf("SELECT kind, COUNT(*) n FROM event GROUP BY kind") }
    var sqlOut by remember { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("stats") }
    var args by rememberSaveable { mutableStateOf("""{"fn":"topApps","by":"bytes"}""") }
    var callOut by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }        // not saveable: the key must not land in saved instance state
    var aiStatus by remember { mutableStateOf(Api.call("ai_status").toString()) }
    val scope = rememberCoroutineScope()
    val mono = TextStyle(fontFamily = FontFamily.Monospace)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SQL (read-only, one SELECT/WITH, max 500 rows)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(sql, { sql = it }, Modifier.fillMaxWidth(), textStyle = mono, minLines = 3)
        Button(onClick = { scope.launch { sqlOut = table(Api.io("sql", obj("query" to sql))) } }) { Text("Run SQL") }
        if (sqlOut.isNotEmpty()) Text(sqlOut, Modifier.horizontalScroll(rememberScrollState()), style = mono.merge(MaterialTheme.typography.bodySmall))
        HorizontalDivider()
        Text("Raw call", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(method, { method = it }, Modifier.fillMaxWidth(), label = { Text("method") }, singleLine = true, textStyle = mono)
        OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text("args JSON") }, textStyle = mono, minLines = 2)
        Button(onClick = {
            scope.launch { callOut = pretty(withContext(Dispatchers.IO) { MyceliumEngine.call(method.trim(), args.ifBlank { "{}" }) }) }
        }) { Text("Call") }
        if (callOut.isNotEmpty()) Text(callOut, style = mono.merge(MaterialTheme.typography.bodySmall))
        HorizontalDivider()
        Text("Cloud AI key (optional)", style = MaterialTheme.typography.titleMedium)
        Text(aiStatus, style = mono.merge(MaterialTheme.typography.bodySmall))
        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("Anthropic API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = apiKey.isNotBlank(), onClick = {
                scope.launch { Api.io("ai_key", obj("key" to apiKey.trim())); apiKey = ""; aiStatus = Api.io("ai_status").toString() }
            }) { Text("Save key") }
            OutlinedButton(onClick = { scope.launch { Api.io("ai_key", obj("clear" to true)); aiStatus = Api.io("ai_status").toString() } }) { Text("Clear key") }
        }
        HorizontalDivider()
        Text("Simulation (demo1: 5 apps, about 100 s at 1x; rows are marked sim=1)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (speed in listOf(1, 4)) OutlinedButton(onClick = {
                scope.launch { message = Api.io("simulate", obj("script" to "demo1", "speed" to speed)).toString() }
            }) { Text("Start ${speed}x") }
            Button(onClick = { scope.launch { message = Api.io("simulate", obj("stop" to true)).toString() } }) { Text("Stop") }
        }
        HorizontalDivider()
        Text("Database", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { message = Api.io("export_db").toString(2) } }) { Text("Export DB") }
            Button(onClick = { confirmWipe = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("Wipe data")
            }
        }
        if (message.isNotEmpty()) Text(message, style = mono.merge(MaterialTheme.typography.bodySmall))
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        title = { Text("Wipe all captured data?") },
        text = { Text("Deletes events, Sanctuary queue, traffic and chat. App selection, block rules and organisms stay.") },
        confirmButton = {
            TextButton(onClick = {
                confirmWipe = false
                scope.launch { message = Api.io("wipe", obj("confirm" to true)).toString() }
            }) { Text("Wipe") }
        },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
    )
}

/** Plain text table of a call("sql") result. */
private fun table(r: JSONObject): String {
    if (r.has("error")) return r.toString(2)
    val cols = r.optJSONArray("columns")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    val rows = r.optJSONArray("rows").objects().map { row -> cols.map { row.opt(it)?.toString() ?: "NULL" } }
    val widths = cols.indices.map { i -> (rows.map { it[i].length } + cols[i].length).max().coerceAtMost(40) }
    fun line(cells: List<String>) = cells.mapIndexed { i, c -> c.take(40).padEnd(widths[i]) }.joinToString(" | ")
    return (listOf(line(cols), widths.joinToString("-+-") { "-".repeat(it) }) + rows.map(::line)).joinToString("\n") +
        "\n${rows.size} rows" + if (r.optBoolean("truncated")) " (truncated at 500)" else ""
}
