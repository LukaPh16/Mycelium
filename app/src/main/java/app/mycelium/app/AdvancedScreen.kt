package app.mycelium.app

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.SectionHeader
import app.mycelium.app.ui.StatLine
import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Tools for developers and curious users. Only reachable when Settings > Advanced is on. */
@Composable
fun AdvancedScreen() {
    val pal = LocalPal.current
    val sqlDefault = stringResource(R.string.adv_sql_default)
    var sql by rememberSaveable { mutableStateOf(sqlDefault) }
    var sqlOut by remember { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("stats") }
    var args by rememberSaveable { mutableStateOf("""{"fn":"topApps","by":"bytes"}""") }
    var callOut by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val mono = TextStyle(fontFamily = FontFamily.Monospace, color = pal.ink)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.adv_sim), Info(R.string.info_sim_t, R.string.info_sim_b))
            Text(stringResource(R.string.adv_sim_body), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(stringResource(R.string.adv_sim_1x), { scope.launch { message = Api.io("simulate", obj("script" to "demo1", "speed" to 1)).toString() } }, Modifier.weight(1f))
                PixelButton(stringResource(R.string.adv_sim_4x), { scope.launch { message = Api.io("simulate", obj("script" to "demo1", "speed" to 4)).toString() } }, Modifier.weight(1f))
                PixelButton(stringResource(R.string.sim_stop), { scope.launch { message = Api.io("simulate", obj("stop" to true)).toString() } }, Modifier.weight(1f), kind = ButtonKind.Secondary)
            }
        }
        Diagnostics()
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.adv_sql), Info(R.string.info_sql_t, R.string.info_sql_b))
            OutlinedTextField(sql, { sql = it }, Modifier.fillMaxWidth(), textStyle = mono, minLines = 3)
            PixelButton(stringResource(R.string.adv_run), { scope.launch { sqlOut = table(Api.io("sql", obj("query" to sql))) } })
            if (sqlOut.isNotEmpty()) Text(sqlOut, Modifier.horizontalScroll(rememberScrollState()), style = mono.merge(MaterialTheme.typography.bodySmall))
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.adv_raw))
            OutlinedTextField(method, { method = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.adv_method)) }, singleLine = true, textStyle = mono)
            OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.adv_args)) }, textStyle = mono, minLines = 2)
            PixelButton(stringResource(R.string.adv_call), {
                scope.launch { callOut = pretty(withContext(Dispatchers.IO) { MyceliumEngine.call(method.trim(), args.ifBlank { "{}" }) }) }
            })
            if (callOut.isNotEmpty()) Text(callOut, style = mono.merge(MaterialTheme.typography.bodySmall))
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.adv_db))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(stringResource(R.string.adv_export), { scope.launch { message = Api.io("export_db").toString(2) } }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                PixelButton(stringResource(R.string.adv_wipe), { confirmWipe = true }, Modifier.weight(1f), kind = ButtonKind.Danger)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(stringResource(R.string.adv_traffic), { scope.launch { message = Api.io("refresh_traffic").toString() } }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                PixelButton(stringResource(R.string.adv_seed), { scope.launch { message = Api.io("seed_apps").toString() } }, Modifier.weight(1f), kind = ButtonKind.Secondary)
            }
            if (message.isNotEmpty()) Text(message, style = mono.merge(MaterialTheme.typography.bodySmall))
        }
    }
    if (confirmWipe) PixelDialog(stringResource(R.string.adv_wipe_q), onDismiss = { confirmWipe = false }, actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(stringResource(R.string.cancel), { confirmWipe = false }, kind = ButtonKind.Secondary)
            PixelButton(stringResource(R.string.adv_wipe_confirm), {
                confirmWipe = false
                scope.launch { message = Api.io("wipe", obj("confirm" to true)).toString() }
            }, kind = ButtonKind.Danger)
        }
    }) { Text(stringResource(R.string.adv_wipe_body), color = pal.ink) }
}

@Composable
private fun Diagnostics() {
    val stateJson by MyceliumEngine.state.collectAsState()
    var perMin by remember { mutableIntStateOf(0) }
    var how by remember { mutableStateOf("") }
    val none = stringResource(R.string.adv_how_none)
    LaunchedEffect(Unit) {
        while (true) {
            perMin = Api.io("events", obj("kind" to "dns", "since_ms" to System.currentTimeMillis() - 60_000, "limit" to 500)).optJSONArray("events")?.length() ?: 0
            val o = Api.io("how_breakdown")
            val total = o.optLong("total")
            how = if (total == 0L) none else o.getJSONObject("how").let { h ->
                h.keys().asSequence().sortedByDescending { h.getLong(it) }.joinToString(" · ") { "$it ${h.getLong(it) * 100 / total}%" }
            }
            delay(3000)
        }
    }
    val pageSize = remember { Os.sysconf(OsConstants._SC_PAGESIZE) }
    PixelPanel(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.adv_diag))
        StatLine(stringResource(R.string.adv_rate), perMin.toString())
        StatLine(stringResource(R.string.adv_how), how, Info(R.string.info_how_t, R.string.info_how_b))
        StatLine(stringResource(R.string.adv_page_size), stringResource(R.string.adv_page_size_value, pageSize.toInt()))
        StatLine(stringResource(R.string.adv_android), stringResource(R.string.adv_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        StatLine(stringResource(R.string.adv_state), stateJson)
    }
}

/** Plain text table of a call("sql") result. */
private fun table(r: JSONObject): String {
    if (r.has("error")) return r.toString(2)
    val cols = r.optJSONArray("columns")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    val rows = r.optJSONArray("rows").objects().map { row -> cols.map { row.opt(it)?.toString() ?: "NULL" } }
    val widths = cols.indices.map { i -> (rows.map { it[i].length } + cols[i].length).max().coerceAtMost(40) }
    fun line(cells: List<String>) = cells.mapIndexed { i, c -> c.take(40).padEnd(widths[i]) }.joinToString(" | ")
    return (listOf(line(cols), widths.joinToString("-+-") { "-".repeat(it) }) + rows.map(::line)).joinToString("\n") +
        "\n${rows.size}" + if (r.optBoolean("truncated")) "+" else ""
}
