package app.mycelium.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Details
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.StatLine
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Packet types from packet_types.json (the only source of colors and type names), loaded once. */
class PacketType(val label: String, val color: Color)

@Composable
fun rememberTypes(): Map<String, PacketType> {
    var types by remember { mutableStateOf(emptyMap<String, PacketType>()) }
    LaunchedEffect(Unit) {
        types = Api.io("packet_types").optJSONArray("types").objects()
            .associate { it.getString("id") to PacketType(it.optString("label"), Color(android.graphics.Color.parseColor(it.getString("color")))) }
    }
    return types
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

private val SNOOP_KINDS = setOf("ads", "analytics", "fingerprinting", "social")

fun isSnoop(e: JSONObject) = e.optString("company").isNotEmpty() && e.optString("category") in SNOOP_KINDS

/** One plain sentence for an event: what happened, without jargon. */
@Composable
fun eventSentence(e: JSONObject): String {
    val company = e.optString("company")
    val site = e.optString("domain").ifEmpty { null }?.let(::siteOf)
    val blocked = e.optString("verdict") == "blocked"
    return when (e.optString("kind")) {
        "dns" -> when {
            isSnoop(e) && blocked -> stringResource(R.string.ev_snoop_shooed, company)
            isSnoop(e) -> stringResource(R.string.ev_snoop, company)
            blocked -> stringResource(R.string.ev_site_blocked, site ?: "")
            else -> stringResource(R.string.ev_site, site ?: "")
        }
        "tripwire" -> stringResource(R.string.ev_tripwire)
        "block" -> stringResource(R.string.ev_block, company.ifEmpty { e.optString("domain") }.ifEmpty { e.optString("note") })
        "unblock" -> stringResource(R.string.ev_unblock, company.ifEmpty { e.optString("domain") }.ifEmpty { e.optString("note") })
        "anomaly" -> when (e.optString("category")) {
            "new_domains_burst" -> stringResource(R.string.ev_odd_burst)
            "night_background" -> stringResource(R.string.ev_odd_night)
            "new_company" -> stringResource(R.string.ev_odd_new_snoop, company)
            "tripwire_retry" -> stringResource(R.string.ev_odd_retry)
            else -> stringResource(R.string.ev_odd)
        }
        "ai" -> stringResource(R.string.ev_ai)
        else -> protectionSentence(e.optString("note"))
    }
}

/** The engine's protection notes ("started: 2 apps", "sanctuary: 4 caught, 1 trackers", ...) in plain words. */
@Composable
private fun protectionSentence(note: String): String = when {
    note.startsWith("started") -> stringResource(R.string.ev_prot_started)
    note.startsWith("watching: ") -> stringResource(R.string.ev_prot_watching, note.removePrefix("watching: ").substringBefore(" ("))
    note.startsWith("mode: sanctuary") -> stringResource(R.string.ev_prot_offline)
    note.startsWith("sanctuary: ") -> stringResource(R.string.ev_prot_back, note.removePrefix("sanctuary: ").substringBefore(" ").toIntOrNull() ?: 0)
    note.startsWith("revoked") -> stringResource(R.string.ev_prot_revoked)
    note.startsWith("dead") || note.startsWith("not started") -> stringResource(R.string.ev_prot_dead)
    note.startsWith("stopped") || note.startsWith("service destroyed") -> stringResource(R.string.ev_prot_paused)
    note.startsWith("simulation") -> stringResource(R.string.ev_prot_demo)
    else -> stringResource(R.string.ev_protection)
}

@Composable
fun eventColor(e: JSONObject, types: Map<String, PacketType>): Color {
    val pal = LocalPal.current
    return when (e.optString("kind")) {
        "anomaly" -> pal.berry
        "block", "unblock" -> pal.gold
        "dns", "tripwire" -> types[e.optString("ptype")]?.color ?: pal.inkMuted
        else -> pal.sky
    }
}

private class Group(val key: String, val events: List<JSONObject>)

@Composable
fun ActivityScreen(feed: List<JSONObject>, advanced: Boolean) {
    val pal = LocalPal.current
    val types = rememberTypes()
    val labels = rememberLabels()
    val go = LocalGo.current
    val open = remember { mutableStateListOf<String>() }
    var shoo by remember { mutableStateOf<JSONObject?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }   // keeps "2 min ago" fresh
    if (feed.isEmpty()) {
        EmptyState(R.drawable.sprite_mushroom_sleepy, stringResource(R.string.act_empty_title), stringResource(R.string.act_empty_body),
            stringResource(R.string.home_choose), { go(Routes.APPS) }, Modifier.padding(top = 48.dp))
        return
    }
    val own = stringResource(R.string.app_name)
    val groups = remember(feed.size, feed.firstOrNull()) {
        feed.groupBy { it.optString("package").ifEmpty { "" } }.map { (k, v) -> Group(k, v) }.sortedBy { it.key.isEmpty() }   // apps first
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.act_intro), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
                InfoButton(Info(R.string.info_contact_t, R.string.info_contact_b))
            }
        }
        for (g in groups) {
            val name = if (g.key.isEmpty()) own else labels[g.key] ?: shortPkg(g.key)
            val expanded = g.key in open
            item(key = "h:" + g.key) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = pal.ink)
                    Text(pluralStringResource(R.plurals.act_count, g.events.size, g.events.size), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                }
            }
            items(if (expanded) g.events else g.events.take(4), key = { it.optLong("id") }) { e ->
                EventCard(e, types, advanced, tick, onShoo = { shoo = e })
            }
            if (g.events.size > 4) item(key = "m:" + g.key) {
                PixelButton(
                    if (expanded) stringResource(R.string.act_show_less) else stringResource(R.string.act_show_all, g.events.size),
                    { if (expanded) open.remove(g.key) else open.add(g.key) }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet,
                )
            }
        }
    }
    shoo?.let { e -> ShooDialog(e.optString("package"), labels[e.optString("package")] ?: shortPkg(e.optString("package")), e.optString("company")) { shoo = null } }
}

@Composable
private fun EventCard(e: JSONObject, types: Map<String, PacketType>, advanced: Boolean, tick: Int, onShoo: () -> Unit) {
    val pal = LocalPal.current
    val color = eventColor(e, types)
    val snoop = isSnoop(e)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).pixelPanel(PanelStyle.Paper, pal, pixel()).padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        Canvas(Modifier.width(6.dp).fillMaxHeight().padding(vertical = 2.dp)) { drawRect(color) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (snoop) { app.mycelium.app.garden.SnoopIcon(e.optString("company"), 1.5f); Spacer(Modifier.width(8.dp)) }
                Text(eventSentence(e), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    color = if (e.optString("kind") == "anomaly") pal.berry else pal.ink)
            }
            val whenText = remember(tick, e.optLong("ts")) { ago(e.optLong("ts")) }
            val fg = when { !e.has("fg") -> null; e.optInt("fg") == 1 -> stringResource(R.string.act_fg); else -> stringResource(R.string.act_bg) }
            Text(listOfNotNull(whenText, fg, if (e.optString("verdict") == "offline") stringResource(R.string.safe_mode_long) else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
            Details { EventFacts(e, types, advanced) }
            if (snoop && e.optString("verdict") != "blocked" && e.optString("package").isNotEmpty()) {
                PixelButton(stringResource(R.string.shoo), onShoo, Modifier.padding(bottom = 8.dp), kind = ButtonKind.Danger)
            }
        }
    }
}

/** The technical facts behind an event, for the Details expander. */
@Composable
fun EventFacts(e: JSONObject, types: Map<String, PacketType>, advanced: Boolean) {
    val pal = LocalPal.current
    val how = when (e.optString("how")) {
        "uid_dns" -> stringResource(R.string.how_uid_dns); "uid_tcp" -> stringResource(R.string.how_uid_tcp)
        "guess_fg" -> stringResource(R.string.how_guess_fg); "" -> null; else -> stringResource(R.string.how_unknown)
    }
    StatLine(stringResource(R.string.act_time), fullTime(e.optLong("ts")))
    e.optString("domain").ifEmpty { null }?.let { StatLine(stringResource(R.string.act_domain), it) }
    e.optString("company").ifEmpty { null }?.let { StatLine(stringResource(R.string.act_company), it) }
    e.optString("category").ifEmpty { null }?.let { StatLine(stringResource(R.string.act_category), it) }
    e.optString("ptype").ifEmpty { null }?.let { StatLine(stringResource(R.string.act_type), types[it]?.label ?: it) }
    if (e.has("port")) StatLine(stringResource(R.string.act_port), e.optInt("port").toString())
    verdictLabel(e.optString("verdict"))?.let { StatLine(stringResource(R.string.act_result), it) }
    how?.let { StatLine(stringResource(R.string.act_how), it, Info(R.string.info_how_t, R.string.info_how_b)) }
    if (e.optInt("watched", 1) == 0) StatLine(stringResource(R.string.act_app), stringResource(R.string.act_not_watched))
    e.optString("note").ifEmpty { null }?.let { if (e.optString("kind") != "dns") StatLine(stringResource(R.string.act_note), it) }
    StatLine(stringResource(R.string.act_id), "#" + e.optLong("id"), Info(R.string.info_id_t, R.string.info_id_b))
    if (advanced) Text(e.toString(2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pal.ink)
}

@Composable
fun verdictLabel(v: String): String? = when (v) {
    "allowed" -> stringResource(R.string.verdict_allowed)
    "blocked" -> stringResource(R.string.verdict_blocked)
    "offline" -> stringResource(R.string.verdict_offline)
    "error" -> stringResource(R.string.verdict_error)
    else -> null
}

/** One event in a dialog (from a Gardener answer): the plain sentence first, facts behind Details. */
@Composable
internal fun EventDialog(e: JSONObject, types: Map<String, PacketType>, labels: Map<String, String>, advanced: Boolean, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    val app = e.optString("package").ifEmpty { null }?.let { labels[it] ?: e.optString("app_label").ifEmpty { shortPkg(it) } }
    PixelDialog(app ?: stringResource(R.string.app_name), onDismiss, actions = {
        PixelButton(stringResource(R.string.close), onDismiss, kind = ButtonKind.Secondary)
    }) {
        Text(eventSentence(e), style = MaterialTheme.typography.bodyLarge, color = pal.ink)
        Text(ago(e.optLong("ts")), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        Details { EventFacts(e, types, advanced) }
    }
}

/** "Shoo away" with a confirm step: blocks that Snoop (company) for this one app. */
@Composable
fun ShooDialog(pkg: String, appLabel: String, company: String, onShooed: () -> Unit = {}, onDone: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    PixelDialog(stringResource(R.string.shoo_q, company), onDismiss = onDone, actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PixelButton(stringResource(R.string.not_now), onDone, kind = ButtonKind.Secondary)
            PixelButton(stringResource(R.string.shoo), {
                scope.launch {
                    val r = Api.io("block", obj("scope" to "company", "value" to company, "package" to pkg))
                    toast(if (r.has("error")) ctx.getString(R.string.shoo_failed) else ctx.getString(R.string.shoo_done, company, appLabel))
                    if (!r.has("error")) onShooed()
                    onDone()
                }
            }, kind = ButtonKind.Danger)
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            app.mycelium.app.garden.SnoopIcon(company, 3f)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.shoo_body, company, appLabel), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
    }
}
