package app.mycelium.app

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ChoiceRow
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.ErrorState
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LoadingState
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PixelPanel
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.pixel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HOUR = 3600_000L
private const val DAY = 24 * HOUR

/** Everything Insights shows for one period, loaded in one go. */
private class Summary(
    val busiest: JSONObject?, val snoopiest: JSONObject?, val nightContacts: Long, val nightApp: String?,
    val bytes: Long, val buckets: List<JSONObject>, val bucketMs: Long, val from: Long, val usageAccess: Boolean,
)

private suspend fun load(week: Boolean): Summary? {
    val to = System.currentTimeMillis()
    val from = if (week) startOfToday() - 6 * DAY else to - DAY
    fun args(vararg kv: Pair<String, Any?>) = obj("from_ms" to from, "to_ms" to to, *kv)
    val busy = Api.io("stats", args("fn" to "topApps", "by" to "lookups", "limit" to 1))
    if (busy.has("error")) return null
    val snoop = Api.io("stats", args("fn" to "topApps", "by" to "trackers", "limit" to 1))
    val bytes = Api.io("stats", args("fn" to "topApps", "by" to "bytes", "limit" to 50)).optJSONArray("apps").objects().sumOf { it.optLong("value") }
    // Night: 00:00-06:00 of each day in the period (today's early morning for "Day").
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    var night = 0L
    val nightApps = HashMap<String, Long>()
    for (d in 0 until if (week) 7 else 1) {
        val r = Api.io("stats", obj("fn" to "nightReport", "date" to fmt.format(Date(startOfToday() - d * DAY))))
        night += r.optLong("lookups")
        for (a in r.optJSONArray("apps").objects()) {
            val name = a.optString("label").ifEmpty { shortPkg(a.optString("package")) }
            nightApps[name] = (nightApps[name] ?: 0L) + a.optLong("lookups", a.optLong("value"))
        }
    }
    val bucketMs = if (week) DAY else HOUR
    val tl = Api.io("stats", args("fn" to "timeline", "bucket_ms" to bucketMs)).optJSONArray("buckets").objects()
    return Summary(
        busy.optJSONArray("apps").objects().firstOrNull()?.takeIf { it.optLong("value") > 0 },
        snoop.optJSONArray("apps").objects().firstOrNull()?.takeIf { it.optLong("value") > 0 },
        night, nightApps.maxByOrNull { it.value }?.key, bytes, tl, bucketMs, from, Api.io("usage_access").optBoolean("granted"),
    )
}

@Composable
fun InsightsScreen() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val go = LocalGo.current
    var week by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var watched by remember { mutableStateOf<Int?>(null) }
    var data by remember { mutableStateOf<Summary?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(week, reload) {
        watched = Api.io("watched").optJSONArray("apps")?.length() ?: 0
        data = null
        failed = false
        val s = load(week)
        if (s == null) failed = true else data = s
    }
    if (watched == 0) {
        EmptyState(R.drawable.sprite_mushroom_sleepy, stringResource(R.string.ins_empty_title), stringResource(R.string.ins_empty_body),
            stringResource(R.string.home_choose), { go(Routes.APPS) }, Modifier.padding(top = 32.dp))
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceRow(listOf(false to stringResource(R.string.ins_day), true to stringResource(R.string.ins_week)), week, { week = it })
        val s = data
        when {
            failed -> ErrorState(stringResource(R.string.ins_error_title), stringResource(R.string.ins_error_body), { reload++ })
            s == null -> LoadingState(stringResource(R.string.ins_loading))
            else -> {
                val period = stringResource(if (week) R.string.ins_this_week else R.string.ins_today)
                BigCard(R.drawable.sprite_mushroom_wow, stringResource(R.string.ins_busiest), Info(R.string.info_busiest_t, R.string.info_busiest_b),
                    s.busiest?.optString("label") ?: stringResource(R.string.ins_nobody),
                    s.busiest?.let { pluralStringResource(R.plurals.ins_busiest_line, it.optInt("value"), it.optInt("value"), period) }
                        ?: stringResource(R.string.ins_quiet, period))
                BigCard(R.drawable.sprite_snoop, stringResource(R.string.ins_snoopiest), Info(R.string.info_snoopiest_t, R.string.info_snoopiest_b),
                    s.snoopiest?.optString("label") ?: stringResource(R.string.ins_no_snoops_title),
                    s.snoopiest?.let { pluralStringResource(R.plurals.ins_snoopiest_line, it.optInt("value"), it.optInt("value"), period) }
                        ?: stringResource(R.string.ins_no_snoops, period))
                BigCard(R.drawable.sprite_mushroom_sleepy, stringResource(R.string.ins_night), Info(R.string.info_night_t, R.string.info_night_b),
                    pluralStringResource(R.plurals.ins_night_value, s.nightContacts.toInt(), s.nightContacts.toInt()),
                    when {
                        s.nightContacts == 0L -> stringResource(R.string.ins_night_none)
                        s.nightApp != null -> stringResource(R.string.ins_night_line, s.nightApp)
                        else -> stringResource(R.string.ins_night_some)
                    })
                val photos = (s.bytes / (3L * 1024 * 1024)).toInt()
                BigCard(R.drawable.sprite_mushroom_happy, stringResource(R.string.ins_data), Info(R.string.info_data_t, R.string.info_data_b),
                    if (s.bytes == 0L && !s.usageAccess) stringResource(R.string.ins_data_unknown) else Formatter.formatShortFileSize(ctx, s.bytes),
                    when {
                        s.bytes == 0L && !s.usageAccess -> stringResource(R.string.ins_data_needs_access)
                        photos > 0 -> pluralStringResource(R.plurals.ins_photos, photos, photos)
                        else -> stringResource(R.string.ins_data_small)
                    })
                PixelPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (week) R.string.ins_chart_week else R.string.ins_chart_day), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, color = pal.ink)
                        InfoButton(Info(R.string.info_chart_t, R.string.info_chart_b))
                    }
                    BarChart(s.buckets)
                    Row {
                        Text(axisLabel(s.from, week), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = pal.inkMuted)
                        Text(stringResource(R.string.ins_now), style = MaterialTheme.typography.labelSmall, color = pal.inkMuted)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Key(pal.leaf); Text(stringResource(R.string.ins_key_contacts), style = MaterialTheme.typography.bodySmall, color = pal.ink)
                        Spacer(Modifier.width(16.dp))
                        Key(pal.berry); Text(stringResource(R.string.ins_key_snoops), style = MaterialTheme.typography.bodySmall, color = pal.ink)
                    }
                }
            }
        }
    }
}

private fun axisLabel(from: Long, week: Boolean): String =
    SimpleDateFormat(if (week) "EEE" else "HH:mm", Locale.getDefault()).format(Date(from))

@Composable
private fun Key(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(12.dp)) { drawRect(color) }
    Spacer(Modifier.width(6.dp))
}

/** One big friendly fact: a sprite, a small title with an "i", a big answer and one line of plain words. */
@Composable
private fun BigCard(@DrawableRes sprite: Int, title: String, info: Info, value: String, line: String) {
    val pal = LocalPal.current
    PixelPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = pal.inkMuted)
            InfoButton(info)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelSprite(sprite, artPixelDp = if (sprite == R.drawable.sprite_snoop) 3f else 1.5f)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(value, style = MaterialTheme.typography.titleLarge, color = pal.ink)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = pal.ink)
            }
        }
    }
}

/** Pixel bars: green contacts with red Snoop visits in front, snapped to whole device pixels. */
@Composable
private fun BarChart(buckets: List<JSONObject>) {
    val pal = LocalPal.current
    val p = pixel()
    val max = (buckets.maxOfOrNull { it.optLong("lookups") } ?: 0L).coerceAtLeast(1L)
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
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
