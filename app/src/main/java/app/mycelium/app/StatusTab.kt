package app.mycelium.app

import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun StatusTab(startVpn: ((String) -> Unit) -> Unit, onChooseApps: () -> Unit) {
    val stateJson by MyceliumEngine.state.collectAsState()
    val st = remember(stateJson) { JSONObject(stateJson) }
    var message by remember { mutableStateOf("") }
    var usage by remember { mutableStateOf(false) }
    var watching by remember { mutableStateOf("") }
    var how by remember { mutableStateOf("") }
    var perMin by remember { mutableIntStateOf(0) }
    val pageSize = remember { Os.sysconf(OsConstants._SC_PAGESIZE) }
    LaunchedEffect(Unit) {
        while (true) {
            usage = Api.io("usage_access").optBoolean("granted")
            watching = Api.io("watched").optJSONArray("apps").objects().joinToString(", ") { it.getString("label") }
            how = howLine(Api.io("how_breakdown"))
            val since = System.currentTimeMillis() - 60_000
            perMin = Api.io("events", obj("kind" to "dns", "since_ms" to since, "limit" to 500)).optJSONArray("events")?.length() ?: 0
            delay(3000)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mycelium debug", style = MaterialTheme.typography.titleLarge)
        Field("VPN", st.optString("vpn"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { startVpn { message = it } }) { Text("Start VPN") }
            OutlinedButton(onClick = { message = Api.call("vpn", obj("action" to "stop")).toString() }) { Text("Stop VPN") }
        }
        Field("Mode", st.optString("mode"))
        Field("Sanctuary queue", st.optInt("queue").toString())
        Field("AI", st.optString("ai"))
        Field("Watching", watching.ifEmpty { "no apps" })
        OutlinedButton(onClick = onChooseApps) { Text("Choose apps") }
        Field("Usage access", if (usage) "granted" else "not granted")
        if (!usage) OutlinedButton(onClick = { Api.call("usage_access_settings") }) { Text("Open Usage Access settings") }
        Field("Lookups/min", perMin.toString())
        Field("how", how)
        Field("Page size", "$pageSize bytes")
        Field("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (message.isNotEmpty()) Text(message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Field(name: String, value: String) {
    Row {
        Text(name, Modifier.width(132.dp), style = MaterialTheme.typography.labelLarge)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private fun howLine(o: JSONObject): String {
    val total = o.optLong("total")
    if (total == 0L) return "no lookups yet"
    val h = o.getJSONObject("how")
    return h.keys().asSequence().sortedByDescending { h.getLong(it) }
        .joinToString(" · ") { "$it ${h.getLong(it) * 100 / total}%" } + " (n=$total)"
}
