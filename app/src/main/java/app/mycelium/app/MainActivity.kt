package app.mycelium.app

import android.Manifest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mycelium.engine.MyceliumEngine
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var onConsent: ((Boolean) -> Unit)? = null
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onConsent?.invoke(it.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        MyceliumEngine.init(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { MaterialTheme { App(::startVpn) } }
    }

    /** call("vpn",{start}); on needs_consent shows the system dialog and retries once granted. */
    private fun startVpn(report: (String) -> Unit) {
        val r = Api.call("vpn", obj("action" to "start"))
        val intent = if (r.optBoolean("needs_consent")) VpnService.prepare(this) else null
        if (intent == null) { report(r.toString()); return }
        onConsent = { ok -> report(if (ok) Api.call("vpn", obj("action" to "start")).toString() else "VPN consent denied") }
        consent.launch(intent)
    }
}

private val TABS = listOf("Status" to "◉", "Live" to "≋", "Terrarium" to "❦", "Garden" to "✿", "Stats" to "▥", "Tools" to "⚙", "Chat" to "✉")

@Composable
private fun App(startVpn: ((String) -> Unit) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Status") }
    var choosingApps by rememberSaveable { mutableStateOf(false) }
    val feed = remember { mutableStateListOf<JSONObject>() }
    LaunchedEffect(Unit) {
        feed.addAll(Api.io("events", obj("limit" to 200)).optJSONArray("events").objects())
        MyceliumEngine.events.collect {
            val o = JSONObject(it)
            when (o.optString("type")) {
                "event" -> { feed.add(0, o.getJSONObject("row")); while (feed.size > 300) feed.removeAt(feed.lastIndex) }
                "wiped" -> feed.clear()
            }
        }
    }
    Scaffold(
        bottomBar = {
            if (!choosingApps) NavigationBar {
                for ((name, glyph) in TABS) NavigationBarItem(
                    selected = tab == name, onClick = { tab = name },
                    icon = { Text(glyph, style = MaterialTheme.typography.titleLarge) },
                    label = { Text(name, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                )
            }
        },
    ) { pad ->
        val stateJson by MyceliumEngine.state.collectAsState()
        val simulating = remember(stateJson) { JSONObject(stateJson).optString("mode") == "simulate" }
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (simulating) Text(
                "SIMULATION · scripted demo data, not this phone's traffic",
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error).padding(vertical = 6.dp, horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold,
            )
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (choosingApps) AppsScreen(onDone = { choosingApps = false })
                else when (tab) {
                    "Status" -> StatusTab(startVpn, onChooseApps = { choosingApps = true })
                    "Live" -> LiveTab(feed)
                    "Terrarium" -> TerrariumTab()
                    "Garden" -> GardenTab()
                    "Stats" -> StatsTab()
                    "Tools" -> ToolsTab()
                    else -> ChatTab()
                }
            }
        }
    }
}
