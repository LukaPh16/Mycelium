package app.mycelium.engine.ai

import app.mycelium.engine.chat.AppRef
import app.mycelium.engine.chat.ChatBot
import app.mycelium.engine.chat.Sentence
import app.mycelium.engine.chat.Validator
import app.mycelium.engine.db.Row
import app.mycelium.engine.db.toJson
import app.mycelium.engine.stats.Query
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Cloud providers for the user's own key. OpenRouter speaks the OpenAI chat format and routes to many models. */
enum class Provider(val id: String, val url: String, val defaultModel: String) {
    OPENROUTER("openrouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/auto"),
    ANTHROPIC("anthropic", "https://api.anthropic.com/v1/messages", "claude-opus-5-5");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OPENROUTER
    }
}

/**
 * Optional cloud analysis with the user's own key (OpenRouter or Anthropic). Never automatic: [preview] builds the exact payload on
 * device (question + capped FACTS); only [ask] with that preview's id sends it. The reply goes through the same
 * Validator as the template chatbot; any failure falls back to the template answer.
 */
class CloudAi(
    private val bot: ChatBot,
    private val q: Query,
    private val transport: Transport,
    private val log: (String) -> Unit,
    private val tz: TimeZone = TimeZone.getDefault(),
    private val timeoutMs: Long = 20_000,
    private val simulating: () -> Set<String>? = { null },
) {
    private class Preview(
        val question: String, val payload: JsonObject, val body: String, val template: JsonObject, val model: String, val created: Long,
        val provider: Provider,
    )

    /** [event] is the kind='ai' row to write when something was sent (null when nothing left the device). */
    class Outcome(val result: JsonObject, val event: Row?, val question: String?)

    private val previews = LinkedHashMap<String, Preview>()
    private val exec = Executors.newCachedThreadPool { r -> Thread(r, "cloud-ai").apply { isDaemon = true } }

    /** Runs router + stats on device and returns exactly what [ask] would send. Nothing leaves the device here. */
    fun preview(text: String, apps: List<AppRef>, companies: Collection<String>, now: Long, model: String,
                provider: Provider = Provider.ANTHROPIC): JsonObject {
        val question = text.trim().take(MAX_QUESTION)
        val template = bot.answer(question, apps, companies, now)
        val payload = payload(question, template)
        val body = requestBody(model, payload, provider)
        val id = UUID.randomUUID().toString()
        synchronized(previews) {
            previews.entries.removeIf { now - it.value.created > TTL_MS }
            while (previews.size >= MAX_PREVIEWS) previews.remove(previews.keys.first())
            previews[id] = Preview(question, payload, body, template, model, now, provider)
        }
        return buildJsonObject {
            put("preview_id", id)
            put("provider", provider.id)
            put("model", model)
            put("payload_bytes", payload.toString().toByteArray().size)
            put("request_bytes", body.toByteArray().size)
            put("payload", payload)
            put("request", Json.parseToJsonElement(body))
            put("intent", template.getValue("intent"))
            put("template", template.getValue("sentences"))
        }
    }

    /** Sends a previewed payload once. Refused without a valid preview id; no key / offline -> template, nothing sent. */
    fun ask(previewId: String?, key: String?, online: Boolean, apps: List<AppRef>, companies: Collection<String>, now: Long): Outcome {
        val p = synchronized(previews) { previewId?.let { previews.remove(it) } }
            ?: return Outcome(error("preview_required"), null, null)
        if (now - p.created > TTL_MS) return Outcome(error("preview_expired"), null, null)
        if (key.isNullOrBlank()) return Outcome(fallback(p, "no_key"), null, p.question)
        if (!online) return Outcome(fallback(p, "offline"), null, p.question)

        val started = System.nanoTime()
        var status: String
        var result: JsonObject
        try {
            val reply = callWithTimeout(key, p.model, p.body, p.provider)
            if (reply.status != 200) {
                status = "http_${reply.status}"
                result = fallback(p, status)
            } else {
                val parsed = if (p.provider == Provider.ANTHROPIC) parse(reply.body) else parseChat(reply.body)
                if (parsed.first == null) {
                    status = parsed.second
                    result = fallback(p, status)
                } else {
                    val (kept, dropped) = check(parsed.first!!, p.payload, apps, companies)
                    if (dropped > 1 || kept.isEmpty()) {
                        status = "validation_failed_$dropped"
                        result = fallback(p, status)
                    } else {
                        status = "ok"
                        result = buildJsonObject {
                            put("sentences", JsonArray(kept.map { (s, kind) ->
                                buildJsonObject { put("text", s.text); put("cites", JsonArray(s.cites.map { JsonPrimitive(it) })); put("kind", kind) }
                            }))
                            put("facts", p.payload)
                            put("intent", p.template.getValue("intent"))
                            put("engine", "cloud")
                            put("model", p.model)
                            put("dropped", dropped)
                        }
                    }
                }
            }
        } catch (_: TimeoutException) {
            status = "timeout"
            result = fallback(p, status)
        } catch (e: Exception) {
            status = "network_error"
            result = fallback(p, status)
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        val bytes = p.body.toByteArray().size
        val note = "bytes=$bytes provider=${p.provider.id} model=${p.model} latency_ms=$latency status=$status"
        log("ai send $note")
        return Outcome(
            JsonObject(result + ("latency_ms" to JsonPrimitive(latency))),
            mapOf("ts" to now, "kind" to "ai", "note" to note),
            p.question,
        )
    }

    // ---- payload ----

    /** {question, intent, timezone, facts, evidence}: max 50 event rows, max ~8 KB, local HH:mm next to every timestamp. */
    fun payload(question: String, template: JsonObject): JsonObject {
        val facts = template.getValue("facts").jsonObject
        val evidenceIds = facts["evidence_ids"]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
        val evidence = if (evidenceIds.isEmpty()) emptyList()
            else q("SELECT id, ts, kind, package, domain, company, category, verdict, fg, port FROM event WHERE id IN (${evidenceIds.joinToString(",")}) ORDER BY id", emptyList())
        var p: JsonElement = buildJsonObject {
            put("question", question)
            put("intent", template.getValue("intent"))
            put("timezone", tz.id)
            put("facts", localize(facts))
            put("evidence", JsonArray(evidence.map { localize(it.toJson()) }))
        }
        p = capRows(p, intArrayOf(MAX_ROWS))
        while (p.toString().toByteArray().size > MAX_PAYLOAD_BYTES) {
            val largest = largestArray(p) ?: break
            p = replace(p, largest, JsonArray(largest.take(largest.size / 2)))
        }
        return p.jsonObject
    }

    private val hm get() = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
    private val day get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }

    /** Adds "<key>_local": "yyyy-MM-dd HH:mm" beside epoch-ms values so the model never converts times itself. */
    private fun localize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(buildMap {
            for ((k, v) in e) {
                put(k, localize(v))
                val ms = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                if (ms != null && ms > 1_000_000_000_000L) put("${k}_local", JsonPrimitive("${day.format(ms)} ${hm.format(ms)}"))
            }
        })
        is JsonArray -> JsonArray(e.map(::localize))
        is JsonPrimitive -> if (e.isString && (e.contentOrNull?.length ?: 0) > MAX_STRING) JsonPrimitive(e.content.take(MAX_STRING)) else e
    }

    /** Keeps at most [budget] row objects (objects with an "id") across the whole payload, in document order. */
    private fun capRows(e: JsonElement, budget: IntArray): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { capRows(it.value, budget) })
        is JsonArray -> JsonArray(e.mapNotNull { item ->
            if (item is JsonObject && "id" in item) { if (budget[0] <= 0) null else { budget[0]--; capRows(item, budget) } }
            else capRows(item, budget)
        }.take(MAX_ROWS))
        else -> e
    }

    private fun largestArray(e: JsonElement): JsonArray? {
        var best: JsonArray? = null
        var bestLen = 0
        fun walk(x: JsonElement) {
            when (x) {
                is JsonObject -> x.values.forEach(::walk)
                is JsonArray -> {
                    val len = x.toString().length
                    if (x.isNotEmpty() && len > bestLen) { best = x; bestLen = len }
                    x.forEach(::walk)
                }
                else -> Unit
            }
        }
        walk(e)
        return best
    }

    private fun replace(e: JsonElement, target: JsonArray, with: JsonArray): JsonElement = when {
        e === target -> with
        e is JsonObject -> JsonObject(e.mapValues { replace(it.value, target, with) })
        e is JsonArray -> JsonArray(e.map { replace(it, target, with) })
        else -> e
    }

    // ---- request / reply ----

    fun requestBody(model: String, payload: JsonObject, provider: Provider = Provider.ANTHROPIC): String =
        if (provider == Provider.ANTHROPIC) anthropicBody(model, payload) else chatBody(model, payload)

    /** OpenAI-style chat completion (OpenRouter): system + user message, strict JSON schema output. */
    private fun chatBody(model: String, payload: JsonObject): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 4096)
        put("response_format", buildJsonObject {
            put("type", "json_schema")
            put("json_schema", buildJsonObject { put("name", "answer"); put("strict", true); put("schema", Json.parseToJsonElement(SCHEMA)) })
        })
        put("messages", JsonArray(listOf(
            buildJsonObject { put("role", "system"); put("content", SYSTEM) },
            buildJsonObject { put("role", "user"); put("content", "FACTS:\n$payload") },
        )))
    }.toString()

    private fun anthropicBody(model: String, payload: JsonObject): String = buildJsonObject {
        put("model", model)
        put("max_tokens", 4096)
        put("system", SYSTEM)
        put("output_config", buildJsonObject {
            put("effort", "low")
            put("format", buildJsonObject { put("type", "json_schema"); put("schema", Json.parseToJsonElement(SCHEMA)) })
        })
        if (model in FALLBACK_MODELS) put("fallbacks", "default")
        put("messages", JsonArray(listOf(buildJsonObject {
            put("role", "user")
            put("content", "FACTS:\n$payload")
        })))
    }.toString()

    fun headers(key: String, model: String, provider: Provider = Provider.ANTHROPIC): Map<String, String> = buildMap {
        put("content-type", "application/json")
        if (provider != Provider.ANTHROPIC) {
            put("authorization", "Bearer $key")
            put("x-title", "Mycelium")
            return@buildMap
        }
        put("x-api-key", key)
        put("anthropic-version", "2023-06-01")
        if (model in FALLBACK_MODELS) put("anthropic-beta", "server-side-fallback-2026-07-01")
    }

    private fun callWithTimeout(key: String, model: String, body: String, provider: Provider): HttpReply {
        val f = CompletableFuture.supplyAsync({ transport(provider.url, headers(key, model, provider), body, timeoutMs.toInt()) }, exec)
        return try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    /** Sentences with their kind ("fact" | "tip"), or null and a status (refusal, max_tokens, invalid_json). */
    private fun parse(body: String): Pair<List<Pair<Sentence, String>>?, String> = try {
        val msg = Json.parseToJsonElement(body).jsonObject
        when (msg["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "refusal" -> null to "refusal"
            "max_tokens" -> null to "max_tokens"
            else -> sentencesOf(msg.getValue("content").jsonArray.map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "text" }
                .joinToString("") { it.getValue("text").jsonPrimitive.content })
        }
    } catch (_: Exception) {
        null to "invalid_json"
    }

    /** OpenAI-style reply: choices[0].message.content holds the JSON (sometimes fenced); refusal / length map like Anthropic. */
    private fun parseChat(body: String): Pair<List<Pair<Sentence, String>>?, String> = try {
        val choice = Json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray[0].jsonObject
        val msg = choice.getValue("message").jsonObject
        when {
            msg["refusal"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true -> null to "refusal"
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length" -> null to "max_tokens"
            else -> {
                val raw = msg.getValue("content").jsonPrimitive.content
                val text = raw.substring(raw.indexOf('{').coerceAtLeast(0), (raw.lastIndexOf('}') + 1).coerceAtLeast(0))
                sentencesOf(text)
            }
        }
    } catch (_: Exception) {
        null to "invalid_json"
    }

    private fun sentencesOf(text: String): Pair<List<Pair<Sentence, String>>?, String> {
        val out = Json.parseToJsonElement(text).jsonObject.getValue("sentences").jsonArray.map { s ->
            val o = s.jsonObject
            val kind = if (o["kind"]?.jsonPrimitive?.contentOrNull == "tip") "tip" else "fact"
            Sentence(o.getValue("text").jsonPrimitive.content.trim(), o["cites"]?.jsonArray?.map { it.jsonPrimitive.content.toLong() }.orEmpty()) to kind
        }
        return if (out.isEmpty()) null to "invalid_json" else out to "ok"
    }

    /**
     * Facts about this phone: cites must be ids present in the payload, then the Step 8 Validator applies.
     * Tips (general knowledge, clearly labelled in the app) carry no cites and are not checked against FACTS; at most
     * [MAX_TIPS], each capped in length. Returns kept sentences in order and the number of dropped facts.
     */
    private fun check(sentences: List<Pair<Sentence, String>>, payload: JsonObject, apps: List<AppRef>, companies: Collection<String>): Pair<List<Pair<Sentence, String>>, Int> {
        val allowed = ids(payload)
        val facts = sentences.filter { it.second == "fact" }.map { it.first }
        val known = facts.filter { s -> s.cites.all { it in allowed } }
        val v = Validator.validate(known, payload, ::citedRows, apps.associate { it.label.lowercase() to it.pkg }, companies, tz,
            allowSim = simulating() != null)
        val keptFacts = v.kept.toSet()
        var tips = 0
        val kept = sentences.mapNotNull { (s, kind) ->
            when {
                kind == "fact" -> if (s in keptFacts) s to kind else null
                tips < MAX_TIPS && s.text.isNotBlank() -> { tips++; Sentence(s.text.take(MAX_TIP_CHARS), emptyList()) to kind }
                else -> null
            }
        }
        return kept to (facts.size - v.kept.size)
    }

    /** Event ids in the payload: "id" fields, "*_id" fields and every element of an "event_ids"/"evidence_ids" array. */
    private fun ids(e: JsonElement): Set<Long> {
        val out = HashSet<Long>()
        fun walk(x: JsonElement) {
            when (x) {
                is JsonObject -> for ((k, v) in x) {
                    if ((k == "id" || k.endsWith("_id")) && v is JsonPrimitive) v.longOrNull?.let(out::add)
                    if ((k == "event_ids" || k == "evidence_ids") && v is JsonArray) v.forEach { (it as? JsonPrimitive)?.longOrNull?.let(out::add) }
                    walk(v)
                }
                is JsonArray -> x.forEach(::walk)
                else -> Unit
            }
        }
        walk(e)
        return out
    }

    private fun citedRows(ids: List<Long>): Map<Long, Row> =
        if (ids.isEmpty()) emptyMap()
        else q("SELECT id, ts, package, company, sim FROM event WHERE id IN (${ids.joinToString(",")})", emptyList())
            .associateBy { (it["id"] as Number).toLong() }

    private fun fallback(p: Preview, reason: String) = JsonObject(p.template + mapOf(
        "engine" to JsonPrimitive("template"), "fallback_reason" to JsonPrimitive(reason),
    ))

    private fun error(code: String) = buildJsonObject { put("error", code) }

    companion object {
        const val URL = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_MODEL = "claude-opus-5-5"   // Anthropic default; OpenRouter uses Provider.OPENROUTER.defaultModel
        const val MAX_PAYLOAD_BYTES = 8192
        const val MAX_ROWS = 50
        private const val MAX_STRING = 300
        private const val MAX_QUESTION = 500
        private const val MAX_PREVIEWS = 20
        private const val TTL_MS = 10 * 60_000L
        private const val MAX_TIPS = 8
        private const val MAX_TIP_CHARS = 500

        /** Models that accept server-side refusal fallbacks ("default" form). */
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")

        private val SCHEMA = """
            {"type":"object","properties":{"sentences":{"type":"array","items":{"type":"object",
            "properties":{"text":{"type":"string"},"cites":{"type":"array","items":{"type":"integer"}},"kind":{"type":"string","enum":["fact","tip"]}},
            "required":["text","cites","kind"],"additionalProperties":false}}},"required":["sentences"],"additionalProperties":false}
        """.trimIndent()

        private val SYSTEM = """
            You are the Gardener, a friendly, plain-spoken guide inside Mycelium, an Android app that shows which websites and
            services the user's chosen apps contact (DNS lookups), which of them are trackers ("Snoops"), and lets the user
            block ("shoo away") them. Everything is recorded on the phone. The user message has their question, then FACTS JSON.
            Reply with sentences of two kinds:
            - kind "fact": something about THIS phone. Use only numbers, times, dates, app names, company names and domains that
              appear in FACTS; never compute new numbers. Write times as the HH:mm part of *_local fields. Every fact cites, in
              "cites", the event ids from FACTS that support it ("id", "evidence_ids", "event_ids"). Byte totals may have empty cites.
            - kind "tip": general knowledge or advice, with empty "cites". Explain what a company or tracker is and what it usually
              collects, why an app might contact it, what the user can do (shoo it away from the garden, check Setup Check, limit
              permissions or ad tracking in Android settings), or explain a Mycelium word. Tips must never state numbers, times or
              claims about this phone's activity.
            Answer the question first. Be warm and clear, no jargon (say "contacted" not "DNS lookup", "Snoop" for a tracker).
            Normally 2 to 5 sentences. If the user asks to explain, asks "why", "what is" or "in depth", give a fuller explanation
            (up to 10 sentences, mostly tips). If FACTS do not cover the question, say so in one fact-free tip and still help with
            general guidance. Mycelium never sees contents, only names of websites, and does not stop all tracking.
            Reply only with JSON that matches the schema.
        """.trimIndent()
    }
}
