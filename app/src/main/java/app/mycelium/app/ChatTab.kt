package app.mycelium.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mycelium.engine.MyceliumEngine
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val SUGGESTIONS = listOf(
    "What just happened?", "Who sent the most data today?", "What happened last night?", "Which apps talk to Meta?", "What did I block?",
)

/** One chat bubble. Bot messages keep their sentences, each with its own citation ids. */
private class Msg(
    val role: String,
    val sentences: List<Pair<String, List<Long>>>,
    val engine: String? = null,
    val latencyMs: Long? = null,
    val fallback: String? = null,
    val action: Action? = null,
) {
    var actionNote by mutableStateOf<String?>(null)
}

/** A selection change the user must tap to confirm: the engine never changes the watch list by itself. */
private class Action(val method: String, val packages: List<String>, val label: String)

private fun user(text: String) = Msg("user", listOf(text to emptyList()))

private fun cites(a: JSONArray?): List<Long> = if (a == null) emptyList() else (0 until a.length()).map { a.getLong(it) }

private fun sentencesOf(a: JSONArray?) = a.objects().map { it.getString("text") to cites(it.optJSONArray("cites")) }

private fun fromHistory(t: JSONObject): Msg {
    if (t.optString("role") != "bot") return user(t.optString("text"))
    val stored = t.optString("sentences")
    val sentences = if (stored.isNotEmpty()) sentencesOf(JSONArray(stored))
        else listOf(t.optString("text") to t.optString("cites").split(',').mapNotNull { it.trim().toLongOrNull() })
    return Msg("bot", sentences, t.optString("engine").ifEmpty { null }, if (t.has("latency_ms")) t.getLong("latency_ms") else null)
}

private fun fromResult(r: JSONObject): Msg {
    if (r.has("error") && !r.has("sentences")) return Msg("bot", listOf("Error: ${r.optString("error")}" to emptyList()))
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
    return Msg(
        "bot", sentencesOf(r.optJSONArray("sentences")), r.optString("engine").ifEmpty { null },
        if (r.has("latency_ms")) r.getLong("latency_ms") else null, r.optString("fallback_reason").ifEmpty { null }, action,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab() {
    val stateJson by MyceliumEngine.state.collectAsState()
    val aiReady = remember(stateJson) { JSONObject(stateJson).optString("ai") == "ready" }
    var askAi by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(aiReady) { if (!aiReady) askAi = false }
    val msgs = remember { mutableStateListOf<Msg>() }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var record by remember { mutableStateOf<JSONObject?>(null) }
    var settings by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    LaunchedEffect(Unit) {
        val turns = Api.io("chat_history", obj("limit" to 100)).optJSONArray("turns").objects()
        msgs.clear()
        msgs.addAll(turns.map(::fromHistory))
    }
    LaunchedEffect(msgs.size, busy) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }

    /** Template: answer at once. Ask AI: build the preview first; nothing is sent until Send in the sheet. */
    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || busy) return
        scope.launch {
            busy = true
            if (askAi) {
                val p = Api.io("ai_preview", obj("text" to q))
                if (p.has("error")) msgs += Msg("bot", listOf("Error: ${p.optString("error")}" to emptyList())) else preview = p
            } else {
                msgs += user(q)
                input = ""
                msgs += fromResult(Api.io("chat", obj("text" to q)))
            }
            busy = false
        }
    }

    fun sendPreview(p: JSONObject) {
        preview = null
        scope.launch {
            busy = true
            msgs += user(p.getJSONObject("payload").optString("question"))
            input = ""
            msgs += fromResult(Api.io("ai_ask", obj("preview_id" to p.getString("preview_id"))))
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Field notes", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("Ask AI", style = MaterialTheme.typography.labelLarge)
            Switch(askAi, { askAi = it }, Modifier.padding(horizontal = 6.dp), enabled = aiReady)
            TextButton(onClick = { settings = true }) { Text("Key") }
            TextButton(onClick = { confirmClear = true }) { Text("Clear") }
        }
        if (!aiReady) Text("Ask AI needs a saved key and a validated network. Answers come from this phone only.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs) { m -> if (m.role == "user") UserBubble(m) else BotBubble(m, onCite = { id -> scope.launch { record = Api.io("record", obj("id" to id)) } }) { a ->
                scope.launch {
                    val r = Api.io(a.method, obj("packages" to JSONArray(a.packages)))
                    m.actionNote = if (r.has("error")) "Failed: ${r.optString("error")}"
                        else if (a.method == "watch") "Now watching ${a.label}." else "Stopped watching ${a.label}."
                }
            } }
            if (busy) item { Text("…", Modifier.padding(8.dp), style = MaterialTheme.typography.titleLarge) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in SUGGESTIONS) SuggestionChip(onClick = { send(s) }, label = { Text(s) }, enabled = !busy)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask about this phone") }, maxLines = 3)
            Button(onClick = { send(input) }, enabled = !busy && input.isNotBlank()) { Text(if (askAi) "Preview" else "Send") }
        }
    }

    preview?.let { p ->
        ModalBottomSheet(onDismissRequest = { preview = null }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This will be sent to the AI provider", style = MaterialTheme.typography.titleMedium)
                Text("${p.optString("model")} · payload ${p.optInt("payload_bytes")} bytes · request ${p.optInt("request_bytes")} bytes. " +
                    "The request also carries fixed instructions and an answer format; your key goes only in a request header.",
                    style = MaterialTheme.typography.bodySmall)
                Text(p.getJSONObject("payload").toString(2), Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { preview = null }) { Text("Cancel") }
                    Button(onClick = { sendPreview(p) }) { Text("Send") }
                }
            }
        }
    }

    record?.let { r ->
        ModalBottomSheet(onDismissRequest = { record = null }) {
            val rec = r.optJSONObject("record")
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (rec != null) "Event #${rec.optLong("id")}" else "Event", style = MaterialTheme.typography.titleMedium)
                Text((rec ?: r).toString(2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                val evidence = r.optJSONArray("evidence")
                if (evidence != null && evidence.length() > 0) {
                    Text("Evidence", style = MaterialTheme.typography.titleSmall)
                    Text(evidence.toString(2), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (settings) KeySheet(onDismiss = { settings = false })

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear chat history?") },
        text = { Text("Deletes all questions and answers. Events and stats stay.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { Api.io("chat_clear"); msgs.clear() } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun UserBubble(m: Msg) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(m.sentences.firstOrNull()?.first.orEmpty(),
            Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)).padding(10.dp))
    }
}

@Composable
private fun BotBubble(m: Msg, onCite: (Long) -> Unit, onAction: (Action) -> Unit) {
    Card(Modifier.fillMaxWidth(0.95f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((text, cites) in m.sentences) {
                Text(text)
                if (cites.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (id in cites) SuggestionChip(onClick = { onCite(id) }, label = { Text("#$id", style = MaterialTheme.typography.labelSmall) })
                }
            }
            m.action?.let { a ->
                val note = m.actionNote
                if (note == null) Button(onClick = { onAction(a) }) { Text(if (a.method == "unwatch") "Stop watching ${a.label}" else "Start watching ${a.label}") }
                else Text(note, style = MaterialTheme.typography.bodySmall)
            }
            val meta = listOfNotNull(m.engine, m.latencyMs?.let { "$it ms" }, m.fallback?.let { "fell back: $it" }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Paste / clear the API key. The field is not saveable so the key never lands in saved instance state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeySheet(onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(Api.call("ai_status")) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cloud AI (optional)", style = MaterialTheme.typography.titleMedium)
            Text("State: ${status.optString("state")} · model ${status.optString("model")}", fontFamily = FontFamily.Monospace)
            Text("Your own Anthropic API key, stored encrypted on this phone. Nothing is sent unless you preview a question and tap Send.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = key.isNotBlank(), onClick = {
                    scope.launch {
                        val r = Api.io("ai_key", obj("key" to key.trim()))
                        key = ""
                        message = if (r.has("error")) "Not saved: ${r.optString("error")}" else "Key saved."
                        status = Api.io("ai_status")
                    }
                }) { Text("Save key") }
                OutlinedButton(onClick = {
                    scope.launch { Api.io("ai_key", obj("clear" to true)); message = "Key removed."; status = Api.io("ai_status") }
                }) { Text("Clear key") }
            }
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
