package app.mycelium.app.garden

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mycelium.app.Api
import app.mycelium.app.R
import app.mycelium.app.fullTime
import app.mycelium.app.obj
import app.mycelium.app.objects
import app.mycelium.app.startOfToday
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.Glyphs
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LoadingState
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.LocalSounds
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelGlyph
import app.mycelium.app.ui.StatLine
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a signal is, for its icon, color and filter chip. [id] matches signal_kinds in packet_types.json. All glyphs (8x8) live here. */
enum class Signal(val id: String, @param:StringRes val label: Int, val glyph: List<String>) {
    ADS("ads", R.string.sig_ads, listOf("......X.", "....XXX.", "XXXXX.X.", "XX..X.X.", "XXXXX.X.", ".X..XXX.", ".X....X.", "........")),
    ANALYTICS("analytics", R.string.sig_analytics, Glyphs.bars),
    FINGERPRINTING("fingerprinting", R.string.sig_fingerprinting, listOf("..XXXX..", ".X....X.", "X..XX..X", "X.X..X.X", "X.X..X.X", "X.X.X..X", "..X.X.X.", "....X...")),
    SOCIAL("social", R.string.sig_social, listOf(".XX..XX.", "XXXXXXXX", "XXXXXXXX", "XXXXXXXX", ".XXXXXX.", "..XXXX..", "...XX...", "........")),
    TRACKER("tracker", R.string.sig_tracker, Glyphs.target),
    BLOCKED("blocked", R.string.sig_blocked, listOf("..XXXX..", ".XX..XX.", "XXX...XX", "X.XX...X", "X..XX..X", "X...XX.X", ".XX..XX.", "..XXXX..")),
    TRIPWIRE("tripwire", R.string.sig_tripwire, listOf("....XX..", "...XX...", "..XX....", ".XXXXXX.", "....XX..", "...XX...", "..XX....", ".X......")),
    ANOMALY("anomaly", R.string.sig_anomaly, listOf("...XX...", "...XX...", "...XX...", "...XX...", "...XX...", "........", "...XX...", "...XX...")),
    BENIGN("benign", R.string.sig_benign, listOf("........", "........", "..XXXX..", ".XXXXXX.", ".XXXXXX.", "..XXXX..", "........", "........")),
}

fun signalOf(e: JSONObject): Signal {
    val kind = e.optString("kind")
    return when {
        kind == "anomaly" -> Signal.ANOMALY
        kind == "tripwire" -> Signal.TRIPWIRE
        kind == "block" || e.optString("verdict") == "blocked" -> Signal.BLOCKED
        e.optString("company").isNotEmpty() -> when (e.optString("category")) {
            "ads" -> Signal.ADS; "analytics" -> Signal.ANALYTICS; "fingerprinting" -> Signal.FINGERPRINTING; "social" -> Signal.SOCIAL
            else -> Signal.TRACKER
        }
        else -> Signal.BENIGN
    }
}

/** Colors and labels from packet_types.json (call("packet_types")): signal kinds and packet types; unknown ids get its neutral color. */
private class Colors(val kinds: Map<String, Color>, val typeLabels: Map<String, String>, val neutral: Color?) {
    fun of(s: Signal, fallback: Color) = kinds[s.id] ?: neutral ?: fallback
}

@Composable
private fun rememberColors(): Colors {
    var c by remember { mutableStateOf(Colors(emptyMap(), emptyMap(), null)) }
    LaunchedEffect(Unit) {
        val r = Api.io("packet_types")
        fun parse(s: String?) = s?.takeIf { it.isNotEmpty() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        c = Colors(
            r.optJSONArray("signal_kinds").objects().mapNotNull { k -> parse(k.optString("color"))?.let { k.getString("id") to it } }.toMap(),
            r.optJSONArray("types").objects().associate { it.getString("id") to it.optString("label") },
            parse(r.optString("neutral")),
        )
    }
    return c
}

/** "1 h 12 min", "18 min", "42 s" (seconds only when asked or under a minute). */
@Composable
fun duration(ms: Long, seconds: Boolean = false): String {
    val s = ms / 1000
    val h = (s / 3600).toInt()
    val m = ((s % 3600) / 60).toInt()
    val sec = (s % 60).toInt()
    val hs = pluralStringResource(R.plurals.st_hours, h, h)
    val ms_ = pluralStringResource(R.plurals.st_minutes, m, m)
    val ss = pluralStringResource(R.plurals.st_seconds, sec, sec)
    return when {
        h > 0 -> stringResource(R.string.st_hm, hs, ms_) + if (seconds && sec > 0) " $ss" else ""
        m > 0 -> if (seconds && sec > 0) stringResource(R.string.st_hm, ms_, ss) else ms_
        else -> ss
    }
}

private fun dayStart(offset: Int): Long = Calendar.getInstance().apply { timeInMillis = startOfToday(); add(Calendar.DAY_OF_MONTH, offset) }.timeInMillis
private fun clock(ts: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(ts))      // with seconds, phone's format
private fun clockShort(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))
private fun hourLabel(ts: Long): String =
    SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "j"), Locale.getDefault()).format(Date(ts))

private class Share(val granted: Boolean, val ms: Long, val total: Long)

/** On the creature card: this app's share of today's whole-phone screen time. Tapping opens the day graph. */
@Composable
fun ScreenTimeBar(pet: Pet) {
    val pal = LocalPal.current
    val sounds = LocalSounds.current
    var data by remember(pet.pkg) { mutableStateOf<Share?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(pet.pkg, open) {
        if (open) return@LaunchedEffect
        val granted = Api.io("usage_access").optBoolean("granted")
        val r = Api.io("stats", obj("fn" to "screenTime", "from_ms" to startOfToday(), "to_ms" to System.currentTimeMillis()))
        val ms = r.optJSONArray("apps").objects().firstOrNull { it.optString("package") == pet.pkg }?.optLong("ms") ?: 0L
        data = Share(granted, ms, maxOf(r.optLong("device_total_ms"), r.optLong("total_ms")))   // whole phone; watched apps until collected
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.st_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = pal.ink)
        InfoButton(Info(R.string.info_screen_t, R.string.info_screen_b))
    }
    val d = data
    if (d == null) {
        Text(stringResource(R.string.st_loading), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        return
    }
    val time = duration(d.ms)
    val pct = if (d.total > 0) (d.ms * 100 / d.total).toInt() else 0
    val total = duration(d.total)
    val share = if (d.ms > 0 && pct < 1) stringResource(R.string.st_share_small, total) else stringResource(R.string.st_share, pct, total)
    val line = when {
        d.total == 0L && !d.granted -> stringResource(R.string.st_no_access)
        d.total == 0L -> stringResource(R.string.st_none)
        else -> stringResource(R.string.st_line, time, share)
    }
    val cd = stringResource(R.string.st_bar_cd, pet.label, time, share)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { sounds?.play("ui_sheet_open"); open = true }
            .semantics(mergeDescendants = true) { contentDescription = cd }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShareBar(if (d.total > 0) d.ms.toFloat() / d.total else 0f, d.ms > 0, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PixelGlyph(Glyphs.chevron, pal.inkMuted, sizeDp = 16)
        }
        Text(line, style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
    }
    if (!d.granted) PixelButton(stringResource(R.string.st_grant), { Api.call("usage_access_settings") }, kind = ButtonKind.Secondary)
    if (open) UsageDayDialog(pet, onDismiss = { open = false })
}

/** GrowthBar's frame; the fill is [f] of the width, at least one pixel block when [some]. */
@Composable
private fun ShareBar(f: Float, some: Boolean, modifier: Modifier) {
    val pal = LocalPal.current
    val p = pixel()
    Canvas(modifier.padding(vertical = 4.dp).height(16.dp)) {
        drawRect(pal.woodDark, Offset.Zero, size)
        drawRect(pal.paperEdge, Offset(p, p), Size(size.width - 2 * p, size.height - 2 * p))
        var w = ((size.width - 2 * p) * f.coerceIn(0f, 1f) / p).toInt() * p
        if (some && w < p) w = p
        if (w > 0) {
            drawRect(pal.leaf, Offset(p, p), Size(w, size.height - 2 * p))
            drawRect(Color.White.copy(alpha = 0.3f), Offset(p, p), Size(w, p))
        }
    }
}


// ---- the day graph ----

private class Mark(val x: Float, val bg: Boolean, val events: List<JSONObject>)

/** Groups signals per lane while they are closer than [minDist] px, so hundreds never overlap. */
private fun cluster(signals: List<JSONObject>, x: (Long) -> Float, minDist: Float): List<Mark> =
    listOf(false, true).flatMap { bg ->
        val out = ArrayList<Mark>()
        var group = ArrayList<JSONObject>()
        var x0 = 0f
        for (e in signals.filter { (it.optInt("fg", -1) == 0) == bg }) {
            val ex = x(e.getLong("ts"))
            if (group.isNotEmpty() && ex - x0 > minDist) { out += Mark(group.map { x(it.getLong("ts")) }.average().toFloat(), bg, group); group = ArrayList() }
            if (group.isEmpty()) x0 = ex
            group += e
        }
        if (group.isNotEmpty()) out += Mark(group.map { x(it.getLong("ts")) }.average().toFloat(), bg, group)
        out
    }

/**
 * Full-screen page for one app and one day. Back steps out one level (details, then hour, then the page); swiping
 * the graph changes the day (or the hour when zoomed in); taps on the graph show details inline under it.
 */
@Composable
private fun UsageDayDialog(pet: Pet, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    var offset by remember { mutableIntStateOf(0) }            // 0 = today, -1 = yesterday, ...
    var day by remember { mutableStateOf<JSONObject?>(null) }
    var hour by remember { mutableStateOf<Int?>(null) }
    var shown by remember { mutableStateOf(Signal.entries.toSet() - Signal.BENIGN) }
    var picked by remember { mutableStateOf<List<JSONObject>?>(null) }    // tapped mark: one event or a group
    var detail by remember { mutableStateOf<JSONObject?>(null) }          // one event of a group
    val colors = rememberColors()
    LaunchedEffect(offset) {
        day = null; hour = null; picked = null; detail = null
        day = Api.io("stats", obj("fn" to "usageDay", "package" to pet.pkg, "from_ms" to dayStart(offset), "to_ms" to dayStart(offset + 1)))
    }
    val d = day
    val first = d?.takeIf { !it.isNull("first_ms") }?.optLong("first_ms")
    val canPrev = first != null && dayStart(offset) > first && offset > -60
    val back = {
        when {
            detail != null && (picked?.size ?: 0) > 1 -> detail = null
            picked != null -> { picked = null; detail = null }
            hour != null -> hour = null
            else -> onDismiss()
        }
    }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().pixelPanel(PanelStyle.Paper, pal, pixel()).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.back, stringResource(R.string.close), true, onDismiss)
                Text(stringResource(R.string.st_day_title, pet.label), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                InfoButton(Info(R.string.info_screen_t, R.string.info_screen_b))
            }
            DayHeader(offset, d?.optLong("total_ms"), canPrev, offset < 0, { offset-- }, { offset++ }, { offset = 0 })
            when {
                d == null -> LoadingState(stringResource(R.string.st_loading))
                d.has("error") -> Text(d.optString("error"), color = pal.berry)
                else -> DayBody(
                    d, hour, { hour = it; picked = null; detail = null }, shown, { shown = it }, colors,
                    picked, detail, onPick = { picked = it; detail = if (it.size == 1) it[0] else null }, onDetail = { detail = it },
                    onCloseSel = { picked = null; detail = null },
                    onSwipe = { dir ->
                        val h = hour
                        if (h != null) hour = (h + dir).coerceIn(0, d.optJSONArray("hours").objects().size - 1)
                        else if (dir < 0 && canPrev) offset-- else if (dir > 0 && offset < 0) offset++
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(offset: Int, totalMs: Long?, canPrev: Boolean, canNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val pal = LocalPal.current
    val from = dayStart(offset)
    val date = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), Locale.getDefault()).format(Date(from))
    val name = when (offset) { 0 -> stringResource(R.string.st_today) + " · " + date; -1 -> stringResource(R.string.st_yesterday) + " · " + date; else -> date }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyphs.chevronLeft, stringResource(R.string.st_prev), canPrev, onPrev)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = pal.ink)
            if (totalMs != null) Text(stringResource(R.string.st_total, duration(totalMs)), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
        }
        GlyphButton(Glyphs.chevron, stringResource(R.string.st_next), canNext, onNext)
        if (canNext) PixelButton(stringResource(R.string.st_today), onToday, kind = ButtonKind.Secondary, compact = true)
    }
}

@Composable
private fun GlyphButton(glyph: List<String>, label: String, enabled: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val sounds = LocalSounds.current
    Box(
        Modifier.size(48.dp).alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled, role = Role.Button) { sounds?.play("ui_tap"); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).pixelPanel(PanelStyle.Quiet, pal, pixel()), contentAlignment = Alignment.Center) { PixelGlyph(glyph, pal.ink, sizeDp = 16) }
    }
}

@Composable
private fun DayBody(
    d: JSONObject, hour: Int?, onHour: (Int?) -> Unit, shown: Set<Signal>, onShown: (Set<Signal>) -> Unit, colors: Colors,
    picked: List<JSONObject>?, detail: JSONObject?, onPick: (List<JSONObject>) -> Unit, onDetail: (JSONObject?) -> Unit,
    onCloseSel: () -> Unit, onSwipe: (Int) -> Unit, modifier: Modifier,
) {
    val pal = LocalPal.current
    val all = remember(d) { d.optJSONArray("signals").objects() }
    val sessions = remember(d) { d.optJSONArray("sessions").objects().map { it.getLong("start_ms") to it.getLong("end_ms") } }
    val hours = remember(d) { d.optJSONArray("hours").objects() }
    if (sessions.isEmpty() && all.isEmpty()) {
        Text(stringResource(R.string.st_day_empty), modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium, color = pal.inkMuted)
        return
    }
    val bucket = d.optLong("bucket_ms", 3_600_000L)
    val to = d.getLong("to_ms")
    val (v0, v1) = if (hour == null) d.getLong("from_ms") to to else hours[hour].getLong("start_ms").let { it to minOf(to, it + bucket) }
    val signals = remember(all, shown) { all.filter { signalOf(it) in shown } }
    val inView = signals.filter { it.getLong("ts") in v0 until v1 }
    val usedInView = sessions.sumOf { (a, b) -> maxOf(0L, minOf(b, v1) - maxOf(a, v0)) }
    val counts = remember(all) { all.groupingBy(::signalOf).eachCount() }
    val listed = picked?.takeIf { it.size > 1 && detail == null }?.sortedBy { it.getLong("ts") }

    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SumChip(duration(usedInView), pal.leaf, Modifier.weight(1f))
                SumChip(pluralStringResource(R.plurals.st_sum_signals, inView.size, inView.size), pal.ink, Modifier.weight(1f))
                val bg = inView.count { it.optInt("fg", -1) == 0 }
                SumChip(pluralStringResource(R.plurals.st_sum_bg, bg, bg), pal.berry, Modifier.weight(1f))
            }
        }
        if (hour != null) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.chevronLeft, stringResource(R.string.st_prev_hour), hour > 0) { onHour(hour - 1) }
                Text(stringResource(R.string.st_hour_view, clockShort(v0), clockShort(v1)), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, color = pal.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                GlyphButton(Glyphs.chevron, stringResource(R.string.st_next_hour), hour < hours.size - 1) { onHour(hour + 1) }
                PixelButton(stringResource(R.string.st_back_day), { onHour(null) }, kind = ButtonKind.Secondary, compact = true)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(6.dp)) {
                DayGraph(v0, v1, hour == null, hours, bucket, sessions, signals, colors, picked?.toSet().orEmpty(), { onHour(it) }, onPick, onSwipe)
            }
            Text(stringResource(if (hour == null) R.string.st_hint_day else R.string.st_hint_hour), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
            if (d.optBoolean("truncated")) Text(stringResource(R.string.st_truncated, all.size), style = MaterialTheme.typography.bodySmall, color = pal.berry)
        }
        // what was tapped: one signal's details, or the list of a group
        if (detail != null) item {
            SignalCard(detail, colors, backToList = picked?.size?.takeIf { it > 1 }, onBack = { onDetail(null) }, onClose = onCloseSel)
        }
        if (listed != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.st_cluster_title, listed.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = pal.ink)
                    GlyphButton(CLOSE_GLYPH, stringResource(R.string.st_close_sel), true, onCloseSel)
                }
            }
            items(listed.take(LIST_MAX), key = { it.getLong("id") }) { e -> SignalRow(e, colors) { onDetail(e) } }
            if (listed.size > LIST_MAX) item {
                Text(stringResource(R.string.st_more, listed.size - LIST_MAX), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
            }
        }
        item {
            Text(stringResource(R.string.st_filters), style = MaterialTheme.typography.labelLarge, color = pal.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in Signal.entries) {
                    val n = counts[s] ?: 0
                    if (n == 0 && s !in shown) continue
                    FilterChip(s, n, s in shown, colors.of(s, pal.inkMuted)) { onShown(if (s in shown) shown - s else shown + s) }
                }
            }
        }
        if (hour != null) item {
            Text(stringResource(R.string.st_sessions), style = MaterialTheme.typography.titleSmall, color = pal.ink)
            val inHour = sessions.mapNotNull { (a, b) -> (maxOf(a, v0) to minOf(b, v1)).takeIf { it.second > it.first } }
            if (inHour.isEmpty()) Text(stringResource(R.string.st_no_sessions), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
            for ((a, b) in inHour) Text(stringResource(R.string.st_session_row, clock(a), clock(b), duration(b - a, seconds = true)),
                style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        }
        item { Text(stringResource(R.string.st_honest), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted) }
    }
}

private const val LIST_MAX = 200
private val CLOSE_GLYPH = listOf("XX....XX", ".XX..XX.", "..XXXX..", "...XX...", "...XX...", "..XXXX..", ".XX..XX.", "XX....XX")

@Composable
private fun SumChip(text: String, dot: Color, modifier: Modifier) {
    val pal = LocalPal.current
    Row(modifier.heightIn(min = 32.dp).pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawRect(dot) }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FilterChip(s: Signal, n: Int, on: Boolean, color: Color, onToggle: () -> Unit) {
    val pal = LocalPal.current
    val sounds = LocalSounds.current
    Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Checkbox) { sounds?.play(if (on) "ui_toggle_off" else "ui_toggle_on"); onToggle() }
        .semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
        Row(Modifier.alpha(if (on) 1f else 0.4f).pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            PixelGlyph(s.glyph, color, sizeDp = 16)
            Spacer(Modifier.width(6.dp))
            Text("${stringResource(s.label)} $n", style = MaterialTheme.typography.labelMedium, color = pal.ink)
        }
    }
}

/**
 * Two lanes of signal marks (background on top, tinted; on screen below) over green usage bars, with a local-time
 * axis. Marks are colored by signal kind; a group shows its count in the color of its most common kind.
 */
@Composable
private fun DayGraph(
    v0: Long, v1: Long, wholeDay: Boolean, hours: List<JSONObject>, bucket: Long, sessions: List<Pair<Long, Long>>,
    signals: List<JSONObject>, colors: Colors, selected: Set<JSONObject>, onHour: (Int) -> Unit, onPick: (List<JSONObject>) -> Unit,
    onSwipe: (Int) -> Unit,
) {
    val pal = LocalPal.current
    val p = pixel()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val laneH = with(density) { 44.dp.toPx() }
    val barTop = 2 * laneH + with(density) { 6.dp.toPx() }
    val barH = with(density) { 104.dp.toPx() }
    val axisH = with(density) { 24.dp.toPx() }
    val minDist = with(density) { 26.dp.toPx() }
    val hit = with(density) { 24.dp.toPx() }
    val swipe = with(density) { 56.dp.toPx() }
    val labelStyle = TextStyle(fontSize = 11.sp, color = pal.inkMuted)
    val signalNames = Signal.entries.associateWith { stringResource(it.label) }
    val fgName = stringResource(R.string.st_fg)
    val bgName = stringResource(R.string.st_bg)
    val unknownName = stringResource(R.string.st_unknown)
    val allowedName = stringResource(R.string.st_allowed)
    val blockedName = stringResource(R.string.st_blocked)
    val laneBg = stringResource(R.string.st_lane_bg)
    val laneFg = stringResource(R.string.st_lane_fg)
    val usageLabel = stringResource(R.string.st_legend_usage)
    val hourCd = hours.map { h ->
        val start = h.getLong("start_ms")
        stringResource(R.string.st_hour_cd, clockShort(start), duration(h.getLong("used_ms")), signals.count { it.getLong("ts") in start until start + bucket })
    }
    val res = LocalContext.current.resources
    val height = with(density) { (barTop + barH + axisH).toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val w = constraints.maxWidth.toFloat()
        val span = (v1 - v0).toDouble()
        fun x(ts: Long) = ((ts - v0) / span * w).toFloat()
        val marks = remember(signals, v0, v1, w) { cluster(signals.filter { it.getLong("ts") in v0 until v1 }, ::x, minDist) }
        Canvas(Modifier.fillMaxWidth().height(height)
            .pointerInput(marks, wholeDay, w) {
                detectTapGestures { o ->
                    val near = marks.filter { it.bg == (o.y < laneH) }.minByOrNull { abs(it.x - o.x) }
                    if (o.y < 2 * laneH && near != null && abs(near.x - o.x) <= hit) onPick(near.events)
                    else if (wholeDay && o.y >= 2 * laneH) {
                        val ts = v0 + (o.x / w * span).toLong()
                        hours.indexOfLast { it.getLong("start_ms") <= ts }.takeIf { it >= 0 }?.let(onHour)
                    }
                }
            }
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (abs(dx) > swipe) onSwipe(if (dx < 0) 1 else -1) }) { _, d -> dx += d }
            },
        ) {
            fun snap(v: Float) = (v / p).toInt() * p
            // lanes: background (tinted, the interesting one) and on screen
            drawRect(pal.berry.copy(alpha = 0.14f), Offset(0f, 0f), Size(w, laneH - p))
            drawRect(pal.paperEdge.copy(alpha = 0.30f), Offset(0f, laneH), Size(w, laneH - p))
            label(measurer, laneBg, 2 * p, p, labelStyle)
            label(measurer, laneFg, 2 * p, laneH + p, labelStyle)
            // usage: every hour has a faint tappable slot; green fill = minutes on screen (0-60), or the exact spans of the hour
            drawRect(pal.woodDark, Offset(0f, barTop + barH), Size(w, p))
            label(measurer, usageLabel, 2 * p, barTop + p, labelStyle)
            if (wholeDay) for (h in hours) {
                val s = h.getLong("start_ms")
                val x0 = snap(x(s)) + p
                val x1 = snap(x(minOf(v1, s + bucket))) - p
                if (x1 <= x0) continue
                drawRect(pal.paperEdge.copy(alpha = 0.25f), Offset(x0, barTop + 8 * p), Size(x1 - x0, barH - 8 * p))
                val used = h.getLong("used_ms")
                if (used > 0) {
                    val bh = maxOf(p, snap((barH - 8 * p) * (used.toFloat() / bucket).coerceIn(0f, 1f)))
                    val top = barTop + barH - bh
                    drawRect(pal.leaf, Offset(x0, top), Size(x1 - x0, bh))
                    drawRect(Color.White.copy(alpha = 0.3f), Offset(x0, top), Size(x1 - x0, p))
                }
            } else for ((a, b) in sessions) {
                if (b <= v0 || a >= v1) continue
                val x0 = snap(x(maxOf(a, v0)))
                val x1 = maxOf(x0 + p, snap(x(minOf(b, v1))))
                drawRect(pal.leaf, Offset(x0, barTop + 8 * p), Size(x1 - x0, barH - 8 * p))
                drawRect(Color.White.copy(alpha = 0.3f), Offset(x0, barTop + 8 * p), Size(x1 - x0, p))
            }
            // axis: whole day = tick each hour, local-time label every 3 h; hour = tick each 5 min, label each 15 min
            val ay = barTop + barH + p
            if (wholeDay) for (h in hours) {
                val s = h.getLong("start_ms")
                val tx = snap(x(s))
                drawRect(pal.woodDark, Offset(tx, ay), Size(p, 2 * p))
                if (Calendar.getInstance().apply { timeInMillis = s }.get(Calendar.HOUR_OF_DAY) % 3 == 0) {
                    drawRect(pal.woodDark, Offset(tx, ay), Size(p, 4 * p))
                    label(measurer, hourLabel(s), tx + p, ay + 4 * p, labelStyle, w)
                }
            } else {
                var t = v0
                var i = 0
                while (t <= v1) {
                    val tx = snap(x(t)).coerceAtMost(w - p)
                    drawRect(pal.woodDark, Offset(tx, ay), Size(p, if (i % 3 == 0) 4 * p else 2 * p))
                    if (i % 3 == 0 && t < v1) label(measurer, clockShort(t), tx + p, ay + 4 * p, labelStyle, w)
                    t += 5 * 60_000L; i++
                }
            }
            // signal marks
            for (m in marks) {
                val top = if (m.bg) 0f else laneH
                val kinds = m.events.groupingBy(::signalOf).eachCount()
                val c = colors.of(kinds.maxBy { it.value }.key, pal.inkMuted)
                val border = if (m.bg) pal.berry else pal.woodDark
                val cy = top + laneH / 2 + 3 * p
                val sel = m.events.any { it in selected }
                if (m.events.size == 1) {
                    val g = 10 * p
                    val gx = snap(m.x - g / 2)
                    val gy = snap(cy - g / 2)
                    if (sel) drawRect(pal.ink, Offset(gx - 2 * p, gy - 2 * p), Size(g + 4 * p, g + 4 * p))
                    drawRect(border, Offset(gx, gy), Size(g, g))
                    drawRect(pal.paper, Offset(gx + p, gy + p), Size(g - 2 * p, g - 2 * p))
                    glyph(signalOf(m.events[0]).glyph, c, gx + p, gy + p, p)
                } else {
                    val tl = measurer.measure(m.events.size.toString(), TextStyle(fontSize = 11.sp, color = pal.ink))
                    val bw = snap(maxOf(10 * p, tl.size.width + 6 * p))
                    val bh = 10 * p
                    val gx = snap(m.x - bw / 2)
                    val gy = snap(cy - bh / 2)
                    if (sel) drawRect(pal.ink, Offset(gx - 2 * p, gy - 2 * p), Size(bw + 4 * p, bh + 4 * p))
                    drawRect(border, Offset(gx, gy), Size(bw, bh))
                    drawRect(c, Offset(gx + p, gy + p), Size(bw - 2 * p, bh - 2 * p))
                    drawRect(pal.paper, Offset(gx + 2 * p, gy + 2 * p), Size(bw - 4 * p, bh - 4 * p))
                    drawText(tl, topLeft = Offset(gx + (bw - tl.size.width) / 2, gy + (bh - tl.size.height) / 2))
                }
            }
        }
        // TalkBack: one node per mark (48dp, overlaps allowed) and per hour bar; touches go to the canvas above
        val markPx = with(density) { 48.dp.roundToPx() }
        for (m in marks) {
            val desc = if (m.events.size == 1) m.events[0].let { e ->
                stringResource(R.string.st_signal_cd, listOfNotNull(e.optString("company").ifEmpty { null }, signalNames.getValue(signalOf(e))).joinToString(" "),
                    clock(e.getLong("ts")), when (e.optString("verdict")) { "blocked" -> blockedName; "allowed" -> allowedName; else -> unknownName },
                    when (e.optInt("fg", -1)) { 1 -> fgName; 0 -> bgName; else -> unknownName })
            } else res.getString(R.string.st_cluster_cd, m.events.size, clock(m.events[0].getLong("ts")))
            Box(Modifier.offset { IntOffset((m.x - markPx / 2f).roundToInt(), if (m.bg) 0 else laneH.roundToInt()) }.size(48.dp)
                .semantics { contentDescription = desc; role = Role.Button; onClick { onPick(m.events); true } })
        }
        if (wholeDay) hours.forEachIndexed { i, h ->
            val s = h.getLong("start_ms")
            val wd = with(density) { (x(minOf(v1, s + bucket)) - x(s)).toDp() }
            Box(Modifier.offset { IntOffset(x(s).roundToInt(), barTop.roundToInt()) }.size(wd, with(density) { barH.toDp() })
                .semantics { contentDescription = hourCd[i]; role = Role.Button; onClick { onHour(i); true } })
        }
    }
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle, maxX: Float = Float.MAX_VALUE) {
    val tl = measurer.measure(text, style)
    drawText(tl, topLeft = Offset(minOf(x, maxX - tl.size.width), y))
}

private fun DrawScope.glyph(rows: List<String>, color: Color, x: Float, y: Float, cell: Float) {
    for ((r, row) in rows.withIndex()) for ((cIdx, ch) in row.withIndex()) {
        if (ch != '.') drawRect(color, Offset(x + cIdx * cell, y + r * cell), Size(cell, cell))
    }
}

@Composable
private fun SignalRow(e: JSONObject, colors: Colors, onClick: () -> Unit) {
    val pal = LocalPal.current
    val s = signalOf(e)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pixelPanel(PanelStyle.Quiet, pal, pixel()).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelGlyph(s.glyph, colors.of(s, pal.inkMuted), sizeDp = 16)
        Spacer(Modifier.width(8.dp))
        Text(clock(e.getLong("ts")), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
        Spacer(Modifier.width(8.dp))
        Text(e.optString("company").ifEmpty { e.optString("domain") }.ifEmpty { stringResource(s.label) }, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (e.optInt("fg", -1) == 0) Text(stringResource(R.string.st_bg), style = MaterialTheme.typography.labelSmall, color = pal.berry)
        Spacer(Modifier.width(4.dp))
        PixelGlyph(Glyphs.chevron, pal.inkMuted, sizeDp = 12)
    }
}

/** Details of one signal, shown inline under the graph. */
@Composable
private fun SignalCard(e: JSONObject, colors: Colors, backToList: Int?, onBack: () -> Unit, onClose: () -> Unit) {
    val pal = LocalPal.current
    val s = signalOf(e)
    val ptype = e.optString("ptype").ifEmpty { null }
    Column(Modifier.fillMaxWidth().pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (backToList != null) GlyphButton(Glyphs.chevronLeft, stringResource(R.string.st_back_list, backToList), true, onBack)
            PixelGlyph(s.glyph, colors.of(s, pal.inkMuted), sizeDp = 20)
            Spacer(Modifier.width(8.dp))
            Text(e.optString("company").ifEmpty { stringResource(s.label) }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = pal.ink)
            GlyphButton(CLOSE_GLYPH, stringResource(R.string.st_close_sel), true, onClose)
        }
        StatLine(stringResource(R.string.st_time), fullTime(e.getLong("ts")))
        e.optString("domain").ifEmpty { null }?.let { StatLine(stringResource(R.string.st_domain), it) }
        StatLine(stringResource(R.string.st_category), stringResource(s.label) + (e.optString("category").ifEmpty { null }?.let { " ($it)" } ?: ""))
        StatLine(stringResource(R.string.st_ptype), colors.typeLabels[ptype] ?: ptype ?: "-")
        e.optString("verdict").ifEmpty { null }?.let { StatLine(stringResource(R.string.st_verdict), stringResource(if (it == "blocked") R.string.st_blocked else R.string.st_allowed)) }
        StatLine(stringResource(R.string.st_state), stringResource(when (e.optInt("fg", -1)) { 1 -> R.string.st_fg; 0 -> R.string.st_bg; else -> R.string.st_unknown }))
        StatLine(stringResource(R.string.st_event_id), stringResource(R.string.st_event_id_v, e.getLong("id")))
    }
}
