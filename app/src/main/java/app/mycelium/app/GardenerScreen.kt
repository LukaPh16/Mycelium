package app.mycelium.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mycelium.app.ui.ButtonKind
import app.mycelium.app.ui.DialogBox
import app.mycelium.app.ui.Details
import app.mycelium.app.ui.LoadingState
import app.mycelium.app.ui.LocalReduceMotion
import app.mycelium.app.ui.EmptyState
import app.mycelium.app.ui.Info
import app.mycelium.app.ui.InfoButton
import app.mycelium.app.ui.LocalPal
import app.mycelium.app.ui.PanelStyle
import app.mycelium.app.ui.PixelButton
import app.mycelium.app.ui.PixelDialog
import app.mycelium.app.ui.PixelSprite
import app.mycelium.app.ui.SwitchRow
import app.mycelium.app.ui.pixel
import app.mycelium.app.ui.pixelPanel
import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One chat bubble; bot messages keep their sentences, each with its own evidence ids. */
private class Msg(
    val user: Boolean,
    val sentences: List<Pair<String, List<Long>>>,
    val engine: String? = null,
    val latencyMs: Long? = null,
    val fallback: String? = null,
    val action: Action? = null,
    val fresh: Boolean = false,
    val tips: Set<Int> = emptySet(),   // indexes of general-knowledge sentences (not facts about this phone)
) {
    var actionNote by mutableStateOf<String?>(null)
}

/** A watch-list change the user must tap to confirm: the engine never changes it by itself. */
private class Action(val method: String, val packages: List<String>, val label: String)

private fun cites(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getLong(it) }

private fun sentencesOf(a: JSONArray?) = a.objects().map { it.getString("text") to cites(it.optJSONArray("cites")) }

private fun tipsOf(a: JSONArray?) = a.objects().withIndex().filter { it.value.optString("kind") == "tip" }.map { it.index }.toSet()

private fun fromHistory(t: JSONObject): Msg {
    if (t.optString("role") != "bot") return Msg(true, listOf(t.optString("text") to emptyList()))
    val stored = t.optString("sentences")
    val s = if (stored.isNotEmpty()) sentencesOf(JSONArray(stored))
        else listOf(t.optString("text") to t.optString("cites").split(',').mapNotNull { it.trim().toLongOrNull() })
    return Msg(false, s, t.optString("engine").ifEmpty { null }, if (t.has("latency_ms")) t.getLong("latency_ms") else null,
        tips = if (stored.isNotEmpty()) tipsOf(JSONArray(stored)) else emptySet())
}

private fun fromResult(r: JSONObject): Msg {
    if (r.has("error") && !r.has("sentences")) return Msg(false, listOf(r.optString("error") to emptyList()))
    val app = r.optJSONObject("facts")?.optJSONObject("app")
    val confirm = r.optJSONObject("confirm_action")
    val action = when {
        confirm != null -> {
            val pkgs = confirm.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            Action(confirm.getString("method"), pkgs, app?.optString("label") ?: pkgs.joinToString())
        }
        r.has("suggest_watch") -> Action("watch", listOf(r.getString("suggest_watch")), app?.optString("label") ?: r.getString("suggest_watch"))
        else -> null
    }
    return Msg(false, sentencesOf(r.optJSONArray("sentences")), r.optString("engine").ifEmpty { null },
        if (r.has("latency_ms")) r.getLong("latency_ms") else null, r.optString("fallback_reason").ifEmpty { null }, action, fresh = true,
        tips = tipsOf(r.optJSONArray("sentences")))
}

@Composable
fun GardenerScreen() {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val go = LocalGo.current
    val stateJson by MyceliumEngine.state.collectAsState()
    val aiState = remember(stateJson) { JSONObject(stateJson).optString("ai") }
    val aiReady = aiState == "ready"
    var askAi by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(aiReady) { if (!aiReady) askAi = false }
    val msgs = remember { mutableStateListOf<Msg>() }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var record by remember { mutableStateOf<JSONObject?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val types = rememberTypes()
    val labels = rememberLabels()
    val suggestions = stringArrayResource(R.array.chat_suggestions)

    LaunchedEffect(Unit) { msgs.addAll(Api.io("chat_history", obj("limit" to 100)).optJSONArray("turns").objects().map(::fromHistory)) }
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1 + if (busy) 1 else 0) }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        scope.launch {
            busy = true
            if (askAi) {
                val p = Api.io("ai_preview", obj("text" to q))
                if (p.has("error")) msgs += Msg(false, listOf(ctx.getString(R.string.error_generic, p.optString("error")) to emptyList())) else preview = p
            } else {
                msgs += Msg(true, listOf(q to emptyList()))
                input = ""
                msgs += fromResult(Api.io("chat", obj("text" to q)))
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (aiState == "no_key") Text(
                stringResource(R.string.chat_ai_needs),
                Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button) { go(Routes.SETTINGS) }.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = pal.inkMuted,
            ) else SwitchRow(stringResource(if (aiReady) R.string.chat_ask_ai else R.string.chat_ask_ai_offline), askAi, { askAi = it },
                Modifier.weight(1f), enabled = aiReady, info = Info(R.string.info_ask_ai_t, R.string.info_ask_ai_b))
            if (msgs.isNotEmpty()) PixelButton(stringResource(R.string.chat_clear), { confirmClear = true }, kind = ButtonKind.Quiet)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Greeting() }
            items(msgs) { m ->
                if (m.user) UserBubble(m.sentences.first().first)
                else BotBubble(m, onCite = { id -> scope.launch { record = Api.io("record", obj("id" to id)) } }) { a ->
                    scope.launch {
                        val r = Api.io(a.method, obj("packages" to JSONArray(a.packages)))
                        m.actionNote = if (r.has("error")) ctx.getString(R.string.error_generic, r.optString("error"))
                            else ctx.getString(if (a.method == "watch") R.string.chat_now_watching else R.string.chat_stopped_watching, a.label)
                    }
                }
            }
            if (busy) item { LoadingState(stringResource(R.string.chat_thinking)) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in suggestions) PixelButton(s, { send(s) }, kind = ButtonKind.Quiet, enabled = !busy)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.chat_hint)) }, maxLines = 3)
            PixelButton(stringResource(if (askAi) R.string.chat_preview else R.string.chat_send), { send(input) }, enabled = !busy && input.isNotBlank())
        }
    }

    preview?.let { p ->
        PixelDialog(stringResource(R.string.chat_preview_title), onDismiss = { preview = null }, actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(stringResource(R.string.cancel), { preview = null }, kind = ButtonKind.Secondary)
                PixelButton(stringResource(R.string.chat_send), {
                    preview = null
                    scope.launch {
                        busy = true
                        msgs += Msg(true, listOf(p.getJSONObject("payload").optString("question") to emptyList()))
                        input = ""
                        msgs += fromResult(Api.io("ai_ask", obj("preview_id" to p.getString("preview_id"))))
                        busy = false
                    }
                })
            }
        }) {
            Text(stringResource(R.string.chat_preview_body, p.optString("model"), p.optInt("payload_bytes")), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
            Text(stringResource(R.string.chat_preview_exact), style = MaterialTheme.typography.titleSmall, color = pal.ink)
            Text(p.getJSONObject("payload").toString(2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = pal.ink)
        }
    }
    record?.optJSONObject("record")?.let { rec -> EventDialog(rec, types, labels, advanced = false) { record = null } }
    if (confirmClear) PixelDialog(stringResource(R.string.chat_clear_q), onDismiss = { confirmClear = false }, actions = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton(stringResource(R.string.cancel), { confirmClear = false }, kind = ButtonKind.Secondary)
            PixelButton(stringResource(R.string.chat_clear), { confirmClear = false; scope.launch { Api.io("chat_clear"); msgs.clear() } }, kind = ButtonKind.Danger)
        }
    }) { Text(stringResource(R.string.chat_clear_body), color = pal.ink) }
}

@Composable
private fun UserBubble(text: String) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(text, Modifier.widthIn(max = 300.dp).pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(12.dp),
            style = MaterialTheme.typography.bodyMedium, color = pal.ink)
    }
}

/** The Gardener says hello and what it can (and can't) answer. */
@Composable
private fun Greeting() {
    val pal = LocalPal.current
    Row(verticalAlignment = Alignment.Top) {
        PixelSprite(R.drawable.sprite_gardener, artPixelDp = 2f, modifier = Modifier.padding(top = 18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            DialogBox(stringResource(R.string.chat_gardener), scroll = false) {
                Text(stringResource(R.string.chat_hello), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
            }
        }
    }
}

@Composable
private fun BotBubble(m: Msg, onCite: (Long) -> Unit, onAction: (Action) -> Unit) {
    val pal = LocalPal.current
    val still = LocalReduceMotion.current
    val total = m.sentences.sumOf { it.first.length }
    var shown by remember(m) { mutableIntStateOf(if (m.fresh && !still) 0 else total) }
    LaunchedEffect(m) { while (shown < total) { delay(18); shown = (shown + 2).coerceAtMost(total) } }   // typewriter
    Row(verticalAlignment = Alignment.Top) {
        PixelSprite(R.drawable.sprite_gardener, artPixelDp = 1.5f, modifier = Modifier.padding(top = 22.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            DialogBox(stringResource(if (m.engine == "cloud") R.string.chat_gardener_ai else R.string.chat_gardener), scroll = false) {
                var left = shown
                for ((i, pair) in m.sentences.withIndex()) {
                    val text = pair.first
                    if (left <= 0) break
                    if (i in m.tips) Column(Modifier.fillMaxWidth().pixelPanel(PanelStyle.Quiet, pal, pixel()).padding(8.dp)) {
                        Text(stringResource(R.string.chat_tip), style = MaterialTheme.typography.labelLarge, color = if (pal.night) pal.gold else pal.wood)
                        Text(text.take(left), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
                    } else Text(text.take(left), style = MaterialTheme.typography.bodyMedium, color = pal.ink)
                    left -= text.length
                }
                if (shown < total) return@DialogBox
                m.action?.let { a ->
                    val note = m.actionNote
                    if (note == null) PixelButton(stringResource(if (a.method == "unwatch") R.string.chat_stop_watching else R.string.chat_start_watching, a.label), { onAction(a) })
                    else Text(note, style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                }
                m.fallback?.let { Text(stringResource(R.string.chat_fallback), style = MaterialTheme.typography.bodySmall, color = pal.berry) }
                val ids = m.sentences.flatMap { it.second }.distinct()
                val engine = when {
                    m.engine == "cloud" && m.latencyMs != null -> stringResource(R.string.chat_engine_cloud, m.latencyMs.toInt())
                    m.engine == "cloud" -> stringResource(R.string.chat_engine_cloud_plain)
                    m.latencyMs != null -> stringResource(R.string.chat_engine_template, m.latencyMs.toInt())
                    m.engine != null -> stringResource(R.string.chat_engine_template_plain)
                    else -> null
                }
                if (ids.isNotEmpty() || engine != null || m.fallback != null) Details {
                    if (ids.isNotEmpty()) {
                        Text(stringResource(R.string.chat_evidence_intro), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (id in ids) CiteChip(id) { onCite(id) }
                        }
                    }
                    m.fallback?.let { Text(stringResource(R.string.chat_fallback_reason, it), style = MaterialTheme.typography.bodySmall, color = pal.inkMuted) }
                    if (engine != null) Text(engine, style = MaterialTheme.typography.labelSmall, color = pal.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun CiteChip(id: Long, onClick: () -> Unit) {
    val pal = LocalPal.current
    val cd = stringResource(R.string.chat_evidence_cd, id.toInt())
    Text(
        "#$id",
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .pixelPanel(PanelStyle.Quiet, pal, pixel())
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = cd }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        style = MaterialTheme.typography.labelMedium, color = pal.ink,
    )
}
