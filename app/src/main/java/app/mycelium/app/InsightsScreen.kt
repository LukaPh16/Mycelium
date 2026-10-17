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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.SectionHeader
import app.mycelium.app.ui.StatLine
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable
fun InsightsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, label) in listOf(R.string.ins_tab_garden, R.string.ins_tab_charts).withIndex()) {
                PixelButton(stringResource(label), { tab = i }, Modifier.weight(1f), kind = if (tab == i) ButtonKind.Primary else ButtonKind.Secondary)
            }
        }
        if (tab == 0) GardenTab() else ChartsTab()
    }
}

private class GardenApp(val app: JSONObject, val organism: JSONObject?, val parasites: List<JSONObject>)

@Composable
private fun GardenTab() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val toast = LocalToast.current
    val go = LocalGo.current
    var apps by remember { mutableStateOf<List<GardenApp>?>(null) }
    var rules by remember { mutableStateOf(emptyList<JSONObject>()) }
    var presets by remember { mutableStateOf(emptyMap<String, JSONObject>()) }
    var reload by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        presets = Api.io("presets").optJSONArray("presets").objects().associate { it.getString("id") to it.getJSONObject("preset") }
        while (true) {
            val organisms = Api.io("terrarium").optJSONArray("apps").objects().associateBy { it.getString("app_id") }
            apps = Api.io("garden").optJSONArray("apps").objects().map { a ->
                val pkg = a.getString("package")
                GardenApp(a, organisms[pkg], Api.io("stats", obj("fn" to "appSummary", "package" to pkg)).optJSONArray("trackers").objects())
            }
            rules = Api.io("rules").optJSONArray("rules").objects().filter { it.getString("scope") == "company" }
            delay(10_000)
        }
    }
    picking?.let { (pkg, label) -> PresetPicker(pkg, label, onDone = { picking = null; reload++ }); return }
    val list = apps ?: return
    if (list.isEmpty()) {
        EmptyState(R.drawable.sprite_mushroom_sleepy, stringResource(R.string.ins_empty_title), stringResource(R.string.ins_empty_body),
            stringResource(R.string.home_choose), { go(Routes.APPS) }, Modifier.padding(top = 32.dp))
        return
    }
    fun ruleFor(company: String, pkg: String) = rules.firstOrNull { it.getString("value").equals(company, true) && it.getString("package") == pkg }
        ?: rules.firstOrNull { it.getString("value").equals(company, true) && it.getString("package") == "*" }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(list, key = { it.app.getString("package") }) { g ->
            val a = g.app
            val pkg = a.getString("package")
            val label = a.optString("label", pkg)
            val o = g.organism
            PixelPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val preset = presets[o?.optString("preset_id")]
                    if (preset != null) PresetThumb(preset, boxDp = 72, stageNo = o?.optInt("stage", 1) ?: 1)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = pal.ink)
                        if (o != null) Text(stringResource(R.string.ins_stage, o.optInt("stage"), o.optInt("stage_count")),
                            style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                    }
                }
                if (o != null) {
                    val growth = o.optDouble("growth").roundToInt().toString()
                    StatLine(stringResource(R.string.ins_growth),
                        if (o.isNull("next_threshold")) stringResource(R.string.ins_growth_top, growth)
                        else stringResource(R.string.ins_growth_value, growth, o.optDouble("next_threshold").roundToInt().toString()),
                        Info(R.string.info_stage_t, R.string.info_stage_b))
                }
                StatLine(stringResource(R.string.ins_lookups_today), a.optLong("lookups_24h").toString(), Info(R.string.info_lookups_t, R.string.info_lookups_b))
                StatLine(stringResource(R.string.ins_trackers_today), a.optLong("tracker_hits_24h").toString(), Info(R.string.info_parasites_t, R.string.info_parasites_b))
                StatLine(stringResource(R.string.ins_blocked_today), a.optLong("blocked_24h").toString())
                StatLine(stringResource(R.string.ins_data_today), human(a.optLong("bytes_24h")), Info(R.string.info_data_t, R.string.info_data_b))
                StatLine(stringResource(R.string.ins_odd_today), a.optLong("anomalies_24h").toString(), Info(R.string.info_odd_t, R.string.info_odd_b))
                SectionHeader(stringResource(R.string.ins_parasites), Info(R.string.info_parasites_t, R.string.info_parasites_b))
                if (g.parasites.isEmpty()) Text(stringResource(R.string.ins_no_parasites), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                for (p in g.parasites) {
                    val company = p.getString("company")
                    val rule = ruleFor(company, pkg)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ins_parasite_line, company, p.optLong("hits").toInt()), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, color = if (rule != null) pal.inkMuted else pal.ink)
                        PixelButton(
                            stringResource(when { rule == null -> R.string.ins_block; rule.getString("package") == "*" -> R.string.ins_unblock_all; else -> R.string.ins_unblock }),
                            {
                                scope.launch {
                                    val args = obj("scope" to "company", "value" to company, "package" to (rule?.getString("package") ?: pkg))
                                    val r = Api.io(if (rule == null) "block" else "unblock", args)
                                    toast(when {
                                        r.has("error") -> ctx.getString(R.string.error_generic, r.optString("error"))
                                        rule == null -> ctx.getString(R.string.ins_blocked_toast, company, label)
                                        else -> ctx.getString(R.string.ins_unblocked_toast, company)
                                    })
                                    reload++
                                }
                            },
                            kind = if (rule == null) ButtonKind.Danger else ButtonKind.Secondary,
                        )
                    }
                }
                PixelButton(stringResource(R.string.ins_change_creature), { picking = pkg to label }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            }
        }
    }
}

private class Period(val label: Int, val ms: Long, val bucketMs: Long)

private const val HOUR = 3600_000L
private val PERIODS = listOf(
    Period(R.string.ins_period_day, 24 * HOUR, HOUR),
    Period(R.string.ins_period_week, 7 * 24 * HOUR, 6 * HOUR),
    Period(R.string.ins_period_month, 30 * 24 * HOUR, 24 * HOUR),
)
private val BY = listOf("lookups" to R.string.ins_by_lookups, "trackers" to R.string.ins_by_trackers, "bytes" to R.string.ins_by_bytes,
    "background" to R.string.ins_by_background)

@Composable
private fun ChartsTab() {
    val pal = LocalPal.current
    var watched by remember { mutableStateOf(emptyList<JSONObject>()) }
    var pkg by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableIntStateOf(0) }
    var by by rememberSaveable { mutableStateOf("lookups") }
    var menu by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var timeline by remember { mutableStateOf(emptyList<JSONObject>()) }
    var topApps by remember { mutableStateOf(emptyList<JSONObject>()) }
    var topCompanies by remember { mutableStateOf(emptyList<JSONObject>()) }
    val labels = rememberLabels()
    LaunchedEffect(Unit) {    // the terrarium list: watched apps, plus the demo's apps while a demo plays
        watched = Api.io("terrarium").optJSONArray("apps").objects().map { obj("package" to it.getString("app_id"), "label" to it.optString("label")) }
        if (pkg == null || watched.none { it.getString("package") == pkg }) pkg = watched.firstOrNull()?.getString("package")
    }
    LaunchedEffect(pkg, period, by) {
        val p = PERIODS[period]
        val to = System.currentTimeMillis()
        fun args(vararg kv: Pair<String, Any?>) = obj("from_ms" to to - p.ms, "to_ms" to to, *kv)
        summary = pkg?.let { Api.io("stats", args("fn" to "appSummary", "package" to it)) }
        timeline = Api.io("stats", args("fn" to "timeline", "bucket_ms" to p.bucketMs, "package" to pkg)).optJSONArray("buckets").objects()
        topApps = Api.io("stats", args("fn" to "topApps", "by" to by, "limit" to 5)).optJSONArray("apps").objects()
        topCompanies = Api.io("stats", args("fn" to "topCompanies", "limit" to 5)).optJSONArray("companies").objects()
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PixelButton(watched.firstOrNull { it.getString("package") == pkg }?.getString("label") ?: stringResource(R.string.ins_pick_app),
                    { menu = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                DropdownMenu(menu, { menu = false }) {
                    for (w in watched) DropdownMenuItem(text = { Text(w.getString("label")) }, onClick = { pkg = w.getString("package"); menu = false })
                }
            }
            for ((i, p) in PERIODS.withIndex()) PixelButton(stringResource(p.label), { period = i }, kind = if (period == i) ButtonKind.Primary else ButtonKind.Secondary)
        }
        summary?.takeIf { !it.has("error") }?.let { s ->
            PixelPanel(Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(R.string.ins_summary))
                StatLine(stringResource(R.string.ins_lookups), s.optLong("lookups").toString(), Info(R.string.info_lookups_t, R.string.info_lookups_b))
                StatLine(stringResource(R.string.ins_unique_domains), s.optLong("unique_domains").toString())
                StatLine(stringResource(R.string.ins_tracker_lookups), s.optLong("tracker_hits").toString(), Info(R.string.info_parasites_t, R.string.info_parasites_b))
                StatLine(stringResource(R.string.ins_companies), s.optLong("companies").toString())
                StatLine(stringResource(R.string.ins_blocked), s.optLong("blocked").toString())
                StatLine(stringResource(R.string.ins_fg_bg), "${s.optLong("fg")} / ${s.optLong("bg")}", Info(R.string.info_fgbg_t, R.string.info_fgbg_b))
                StatLine(stringResource(R.string.ins_seen), "${dateTime(s.optLong("first_seen"))} → ${dateTime(s.optLong("last_seen"))}")
                StatLine(stringResource(R.string.ins_bytes), "${human(s.optLong("rx_bytes"))} / ${human(s.optLong("tx_bytes"))}", Info(R.string.info_data_t, R.string.info_data_b))
            }
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.ins_timeline))
            BarChart(timeline)
            Text(stringResource(R.string.ins_timeline_note), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.ins_top_apps), Info(R.string.info_top_t, R.string.info_top_b))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((key, label) in BY) PixelButton(stringResource(label), { by = key }, Modifier.weight(1f),
                    kind = if (by == key) ButtonKind.Primary else ButtonKind.Quiet)
            }
            if (topApps.isEmpty()) Text(stringResource(R.string.ins_no_data), color = pal.inkMuted, style = MaterialTheme.typography.bodySmall)
            for (a in topApps) {
                val v = a.optLong("value")
                StatLine(a.optString("label").ifEmpty { labels[a.getString("package")] ?: a.getString("package") }, if (by == "bytes") human(v) else v.toString())
            }
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.ins_top_companies), Info(R.string.info_parasites_t, R.string.info_parasites_b))
            if (topCompanies.isEmpty()) Text(stringResource(R.string.ins_no_data), color = pal.inkMuted, style = MaterialTheme.typography.bodySmall)
            for (c in topCompanies) StatLine(c.getString("company"), stringResource(R.string.ins_company_line, c.optLong("hits").toInt(), c.optLong("apps").toInt()))
        }
    }
}

/** Pixel bars: green lookups with red tracker lookups in front, snapped to whole device pixels. */
@Composable
private fun BarChart(buckets: List<JSONObject>) {
    val pal = LocalPal.current
    val p = pixel()
    val max = (buckets.maxOfOrNull { it.optLong("lookups") } ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        drawRect(pal.paperEdge, Offset(0f, size.height - p), Size(size.width, p))
        if (buckets.isEmpty()) return@Canvas
        val w = (size.width / buckets.size).toInt().toFloat()
        buckets.forEachIndexed { i, b ->
            for ((key, color) in listOf("lookups" to pal.leaf, "tracker_hits" to pal.berry)) {
                val h = ((size.height - p) * b.optLong(key) / max / p).toInt() * p
                if (h > 0) drawRect(color, Offset(i * w + p, size.height - p - h), Size((w - 2 * p).coerceAtLeast(p), h))
            }
        }
    }
}
