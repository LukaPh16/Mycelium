package app.mycelium.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.StatLine
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import org.json.JSONObject

/** Packet type colors from packet_types.json (the only source of colors), loaded once. */
@Composable
fun rememberTypeColors(): Map<String, Color> {
    var colors by remember { mutableStateOf(emptyMap<String, Color>()) }
    LaunchedEffect(Unit) {
        colors = Api.io("packet_types").optJSONArray("types").objects()
            .associate { it.getString("id") to Color(android.graphics.Color.parseColor(it.getString("color"))) }
    }
    return colors
}

/** App labels by package: installed apps plus every app Mycelium knows (also demo apps). */
@Composable
fun rememberLabels(): Map<String, String> {
    var labels by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        val known = Api.io("garden", obj("include_unwatched" to true)).optJSONArray("apps").objects()
            .associate { it.getString("package") to it.optString("label", it.getString("package")) }
        val installed = Api.io("apps", obj("include_system" to true)).optJSONArray("apps").objects()
            .associate { it.getString("package") to it.getString("label") }
        labels = known + installed
    }
    return labels
}

@Composable
fun kindLabel(kind: String): String = stringResource(
    when (kind) {
        "dns" -> R.string.kind_dns; "tripwire" -> R.string.kind_tripwire; "block" -> R.string.kind_block
        "unblock" -> R.string.kind_unblock; "anomaly" -> R.string.kind_anomaly; "ai" -> R.string.kind_ai
        else -> R.string.kind_vpn
    },
)

@Composable
fun verdictLabel(v: String): String? = when (v) {
    "allowed" -> stringResource(R.string.verdict_allowed)
    "blocked" -> stringResource(R.string.verdict_blocked)
    "offline" -> stringResource(R.string.verdict_offline)
    "error" -> stringResource(R.string.verdict_error)
    else -> null
}

@Composable
fun ActivityScreen(feed: List<JSONObject>, advanced: Boolean) {
    val pal = LocalPal.current
    val colors = rememberTypeColors()
    val labels = rememberLabels()
    var filter by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<JSONObject?>(null) }
    val pkgs = feed.mapNotNull { it.optString("package").ifEmpty { null } }.distinct().sortedBy { labels[it] ?: it }
    val rows = if (filter == null) feed else feed.filter { it.optString("package") == filter }
    val go = LocalGo.current
    if (feed.isEmpty()) {
        EmptyState(R.drawable.sprite_mushroom_sleepy, stringResource(R.string.act_empty_title), stringResource(R.string.act_empty_body),
            stringResource(R.string.home_choose), { go(Routes.APPS) }, Modifier.padding(top = 48.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.act_count, rows.size, rows.size), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, color = pal.ink)
            Box {
                PixelButton(filter?.let { labels[it] ?: shortPkg(it) } ?: stringResource(R.string.act_all_apps), { menu = true }, kind = ButtonKind.Secondary)
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.act_all_apps)) }, onClick = { filter = null; menu = false })
                    for (p in pkgs) DropdownMenuItem(text = { Text(labels[p] ?: p) }, onClick = { filter = p; menu = false })
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.optLong("id") }) { e -> EventRow(e, colors, labels) { shown = e } }
        }
    }
    shown?.let { e -> EventDialog(e, colors, labels, advanced) { shown = null } }
}

@Composable
private fun EventRow(e: JSONObject, colors: Map<String, Color>, labels: Map<String, String>, onClick: () -> Unit) {
    val pal = LocalPal.current
    val kind = e.optString("kind")
    val what = e.optString("domain").ifEmpty { e.optString("company") }.ifEmpty { e.optString("note") }
        .ifEmpty { if (e.has("port")) "port ${e.optInt("port")}" else kindLabel(kind) }
    val verdict = e.optString("verdict")
    val dot = colors[e.optString("ptype")] ?: if (kind == "anomaly") pal.berry else pal.inkMuted
    val app = e.optString("package").ifEmpty { null }?.let { labels[it] ?: shortPkg(it) }
        ?: if (kind == "dns" || kind == "tripwire") stringResource(R.string.act_unknown_app) else kindLabel(kind)
    Row(
        Modifier.fillMaxWidth().pixelPanel(PanelStyle.Quiet, pal, pixel()).clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) { drawRect(dot) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(what, style = MaterialTheme.typography.bodyMedium, color = if (verdict == "blocked" || kind == "anomaly") pal.berry else pal.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = listOfNotNull(app, e.optString("company").ifEmpty { null }, verdictLabel(verdict)).joinToString(" · ")
            Text(tags, style = MaterialTheme.typography.bodySmall, color = pal.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(hhmmss(e.optLong("ts")).take(5), style = MaterialTheme.typography.labelMedium, color = pal.inkMuted)
    }
}

@Composable
internal fun EventDialog(e: JSONObject, colors: Map<String, Color>, labels: Map<String, String>, advanced: Boolean, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    val how = when (e.optString("how")) {
        "uid_dns" -> stringResource(R.string.how_uid_dns); "uid_tcp" -> stringResource(R.string.how_uid_tcp)
        "guess_fg" -> stringResource(R.string.how_guess_fg); "" -> null; else -> stringResource(R.string.how_unknown)
    }
    PixelDialog(stringResource(R.string.act_detail_title), onDismiss, actions = {
        PixelButton(stringResource(R.string.close), onDismiss, kind = ButtonKind.Secondary)
    }) {
        Text(kindLabel(e.optString("kind")), style = MaterialTheme.typography.titleMedium, color = pal.ink)
        StatLine(stringResource(R.string.act_time), fullTime(e.optLong("ts")))
        e.optString("package").ifEmpty { null }?.let { p ->
            StatLine(stringResource(R.string.act_app), (labels[p] ?: p) + if (e.optInt("watched", 1) == 0) " (" + stringResource(R.string.act_not_watched) + ")" else "")
        }
        e.optString("domain").ifEmpty { null }?.let { StatLine(stringResource(R.string.act_domain), it) }
        e.optString("ptype").ifEmpty { null }?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.act_type), Modifier.weight(1f), color = pal.inkMuted, style = MaterialTheme.typography.bodyMedium)
                Canvas(Modifier.size(10.dp)) { drawRect(colors[t] ?: Color.Gray) }
                Spacer(Modifier.width(6.dp))
                Text(t, color = pal.ink, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
            }
        }
        e.optString("company").ifEmpty { null }?.let { StatLine(stringResource(R.string.act_company), it) }
        e.optString("category").ifEmpty { null }?.let { StatLine(stringResource(R.string.act_category), it) }
        if (e.has("port")) StatLine(stringResource(R.string.act_port), e.optInt("port").toString())
        verdictLabel(e.optString("verdict"))?.let { StatLine(stringResource(R.string.act_result), it) }
        how?.let { StatLine(stringResource(R.string.act_how), it, Info(R.string.info_how_t, R.string.info_how_b)) }
        e.optString("note").ifEmpty { null }?.let { if (e.optString("kind") != "dns") StatLine(stringResource(R.string.act_note), it) }
        if (advanced) {
            Text(stringResource(R.string.act_raw), style = MaterialTheme.typography.titleSmall, color = pal.inkMuted)
            Text(e.toString(2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pal.ink)
        }
    }
}
