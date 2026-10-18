package app.mycelium.engine.ai

import app.mycelium.engine.FixtureDb
import app.mycelium.engine.FixtureDb.Companion.B
import app.mycelium.engine.FixtureDb.Companion.H
import app.mycelium.engine.chat.AppRef
import app.mycelium.engine.chat.ChatBot
import app.mycelium.engine.chat.TemplateWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.TimeZone

class CloudAiTest {
    private val key = "sk-ant-api03-TEST-SECRET-KEY-must-never-leak-0123456789"
    private val apps = listOf(AppRef("com.a", "Alpha", true), AppRef("com.b", "Beta", true), AppRef("com.c", "Gamma", false))
    private val companies = listOf("Google", "AppsFlyer", "Meta")
    private val now = B + 23 * H
    private val utc = TimeZone.getTimeZone("UTC")
    private lateinit var fx: FixtureDb
    private lateinit var bot: ChatBot
    private val logs = mutableListOf<String>()
    private val sent = mutableListOf<Pair<Map<String, String>, String>>()
    private var reply: () -> HttpReply = { ok(listOf("Alpha mushroom made 3 lookups today." to listOf(1L))) }

    private fun ai(timeoutMs: Long = 2000) = CloudAi(bot, fx.query, { _, h, body, _ -> sent += h to body; reply() }, { logs += it }, utc, timeoutMs)

    /** A Messages API response whose text block is the structured-output JSON. */
    private fun ok(sentences: List<Pair<String, List<Long>>>, stop: String = "end_turn"): HttpReply {
        val inner = buildJsonObject {
            put("sentences", JsonArray(sentences.map { (t, c) -> buildJsonObject { put("text", t); put("cites", JsonArray(c.map { JsonPrimitive(it) })) } }))
        }.toString()
        val msg = buildJsonObject {
            put("type", "message"); put("stop_reason", stop)
            put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", inner) })))
        }
        return HttpReply(200, msg.toString())
    }

    @Before fun setUp() {
        fx = FixtureDb()
        bot = ChatBot(fx.query, TemplateWriter.parse(File(System.getProperty("chatTemplatesPath")!!).readText()), utc)
    }

    @After fun tearDown() = fx.close()

    private fun previewId(c: CloudAi, q: String = "What did Alpha do today?") =
        c.preview(q, apps, companies, now, CloudAi.DEFAULT_MODEL).getValue("preview_id").jsonPrimitive.content

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    @Test fun cloudAnswerIsValidatedAndMarkedCloud() {
        val c = ai()
        val out = c.ask(previewId(c), key, online = true, apps, companies, now)
        assertEquals("cloud", out.result.s("engine"))
        assertEquals(0, out.result.getValue("dropped").jsonPrimitive.int)
        assertEquals("Alpha mushroom made 3 lookups today.", out.result.getValue("sentences").jsonArray[0].jsonObject.s("text"))
        assertTrue(out.event!!["note"].toString().contains("status=ok"))
        val (headers, body) = sent.single()
        assertEquals(key, headers["x-api-key"])                       // positive control: the key is only ever a header
        assertEquals("2023-06-01", headers["anthropic-version"])
        val req = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("claude-opus-5-5", req.s("model"))
        assertEquals("json_schema", req.getValue("output_config").jsonObject.getValue("format").jsonObject.s("type"))
    }

    @Test fun keyNeverAppearsInAnyDbRowOrLogLine() {
        val c = ai()
        val results = mutableListOf<String>()
        val events = mutableListOf<Map<String, Any?>>()
        for (r in listOf<() -> HttpReply>(
            { ok(listOf("Alpha mushroom made 3 lookups today." to listOf(1L))) },
            { HttpReply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""") },
            { throw IOException("connection reset") },
            { HttpReply(200, "not json") },
        )) {
            reply = r
            val p = c.preview("What did Alpha do today?", apps, companies, now, CloudAi.DEFAULT_MODEL)
            results += p.toString()
            val out = c.ask(p.s("preview_id"), key, true, apps, companies, now)
            results += out.result.toString()
            events += out.event!!
        }
        // Write the ai events exactly as the engine would, then scan every column of every table.
        for (e in events) fx.exec("INSERT INTO event(ts,kind,note) VALUES(${e["ts"]},'ai','${e["note"]}')")
        fx.exec("INSERT INTO chat(ts,role,text,engine) VALUES($now,'bot','${results.last().replace("'", "''")}','template')")
        val tables = fx.query("SELECT name FROM sqlite_master WHERE type='table'", emptyList()).map { it["name"] as String }
        for (t in tables) for (row in fx.query("SELECT * FROM $t", emptyList())) {
            assertFalse("key in $t: $row", row.values.any { it?.toString()?.contains(key) == true })
        }
        assertEquals(4, logs.size)
        for (line in logs + results) assertFalse(line, line.contains(key) || line.contains("TEST-SECRET"))
        assertTrue(events.map { it["note"].toString() }.toString(), events.map { it["note"].toString().substringAfter("status=") } ==
            listOf("ok", "http_401", "network_error", "invalid_json"))
    }

    @Test fun previewPayloadIsCapped() {
        val c = ai()
        val rows = (1L..500L).map { i ->
            buildJsonObject { put("id", i); put("ts", B + i); put("domain", "d$i." + "x".repeat(400) + ".example"); put("package", "com.a") }
        }
        val template = buildJsonObject {
            put("intent", "what_happened_recently")
            put("facts", buildJsonObject {
                put("data", buildJsonObject { put("events", JsonArray(rows)); put("more", JsonArray(rows)) })
                put("evidence_ids", JsonArray((1L..11L).map { JsonPrimitive(it) }))
            })
        }
        val p = c.payload("What happened?", template)
        val bytes = p.toString().toByteArray().size
        assertTrue("payload $bytes bytes", bytes <= CloudAi.MAX_PAYLOAD_BYTES)
        var rowsLeft = 0
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> { if ("id" in e) rowsLeft++; e.values.forEach(::walk) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(p)
        assertTrue("rows $rowsLeft", rowsLeft <= CloudAi.MAX_ROWS)
        assertTrue(p.getValue("facts").toString().contains("ts_local"))     // local time strings for the model
        // A real preview stays well under the cap and only contains question + facts.
        val real = c.preview("What did Alpha do today?", apps, companies, now, CloudAi.DEFAULT_MODEL)
        assertTrue(real.getValue("payload_bytes").jsonPrimitive.int <= CloudAi.MAX_PAYLOAD_BYTES)
        assertEquals(setOf("question", "intent", "timezone", "facts", "evidence"), real.getValue("payload").jsonObject.keys)
        assertTrue(sent.isEmpty())                                          // preview never sends
    }

    @Test fun sendRefusedWithoutPreview() {
        val c = ai()
        assertEquals("preview_required", c.ask(null, key, true, apps, companies, now).result.s("error"))
        assertEquals("preview_required", c.ask("made-up-id", key, true, apps, companies, now).result.s("error"))
        val id = previewId(c)
        assertEquals("cloud", c.ask(id, key, true, apps, companies, now).result.s("engine"))
        assertEquals("preview_required", c.ask(id, key, true, apps, companies, now).result.s("error"))   // single use
        assertEquals(1, sent.size)
    }

    @Test fun validatorDropsFabricatedIdInCloudReply() {
        reply = { ok(listOf("Alpha mushroom made 3 lookups today." to listOf(1L), "Alpha mushroom also met Google." to listOf(999L))) }
        val c = ai()
        val out = c.ask(previewId(c), key, true, apps, companies, now).result
        assertEquals("cloud", out.s("engine"))
        assertEquals(1, out.getValue("dropped").jsonPrimitive.int)
        assertEquals(1, out.getValue("sentences").jsonArray.size)
    }

    @Test fun moreThanOneDroppedFallsBackToTemplate() {
        reply = { ok(listOf("Alpha mushroom made 3 lookups." to listOf(1L), "Alpha made 41 lookups." to listOf(1L), "Ghost." to listOf(999L))) }
        val c = ai()
        val out = c.ask(previewId(c), key, true, apps, companies, now).result
        assertEquals("template", out.s("engine"))
        assertEquals("validation_failed_2", out.s("fallback_reason"))
    }

    @Test fun fallbacksNeverSendWithoutKeyOrNetwork() {
        val c = ai()
        val noKey = c.ask(previewId(c), null, true, apps, companies, now)
        assertEquals("template" to "no_key", noKey.result.s("engine") to noKey.result.s("fallback_reason"))
        assertEquals(null, noKey.event)
        val offline = c.ask(previewId(c), key, false, apps, companies, now)
        assertEquals("template" to "offline", offline.result.s("engine") to offline.result.s("fallback_reason"))
        assertTrue(sent.isEmpty())
        assertTrue(offline.result.getValue("sentences").jsonArray.isNotEmpty())
    }

    @Test fun httpErrorRefusalBadJsonAndTimeoutFallBack() {
        for ((r, reason) in listOf<Pair<() -> HttpReply, String>>(
            { HttpReply(529, "{}") } to "http_529",
            { ok(listOf("x" to listOf(1L)), stop = "refusal") } to "refusal",
            { HttpReply(200, """{"content":[{"type":"text","text":"{not json"}]}""") } to "invalid_json",
            { Thread.sleep(1500); ok(emptyList()) } to "timeout",
        )) {
            reply = r
            val c = ai(timeoutMs = 300)
            val out = c.ask(previewId(c), key, true, apps, companies, now)
            assertEquals(reason, out.result.s("fallback_reason"))
            assertEquals("template", out.result.s("engine"))
            assertTrue(out.event!!["note"].toString().endsWith("status=$reason"))
        }
    }

    /** An OpenAI-style chat completion (OpenRouter) whose message content is the structured-output JSON. */
    private fun chatOk(sentences: List<Pair<String, List<Long>>>, finish: String = "stop", fenced: Boolean = false): HttpReply {
        val inner = buildJsonObject {
            put("sentences", JsonArray(sentences.map { (t, c) -> buildJsonObject { put("text", t); put("cites", JsonArray(c.map { JsonPrimitive(it) })) } }))
        }.toString()
        val msg = buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("finish_reason", finish)
                put("message", buildJsonObject { put("role", "assistant"); put("content", if (fenced) "```json\n$inner\n```" else inner) })
            })))
        }
        return HttpReply(200, msg.toString())
    }

    @Test fun openRouterUsesBearerKeyChatFormatAndIsValidated() {
        reply = { chatOk(listOf("Alpha mushroom made 3 lookups today." to listOf(1L)), fenced = true) }
        val c = ai()
        val id = c.preview("What did Alpha do today?", apps, companies, now, Provider.OPENROUTER.defaultModel, Provider.OPENROUTER)
            .getValue("preview_id").jsonPrimitive.content
        val out = c.ask(id, key, online = true, apps, companies, now)
        assertEquals("cloud", out.result.s("engine"))
        assertTrue(out.event!!["note"].toString().contains("provider=openrouter"))
        val (headers, body) = sent.single()
        assertEquals("Bearer $key", headers["authorization"])
        assertTrue("x-api-key" !in headers)
        val req = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("openrouter/auto", req.s("model"))
        assertEquals("system", req.getValue("messages").jsonArray[0].jsonObject.s("role"))
        assertEquals("json_schema", req.getValue("response_format").jsonObject.s("type"))
        assertTrue(key !in body)                                      // the key never goes into the body
    }

    @Test fun openRouterLengthAndBadJsonFallBack() {
        for ((r, reason) in listOf<Pair<() -> HttpReply, String>>(
            { chatOk(listOf("x" to listOf(1L)), finish = "length") } to "max_tokens",
            { HttpReply(200, """{"choices":[{"message":{"content":"nope"}}]}""") } to "invalid_json",
        )) {
            reply = r
            val c = ai()
            val id = c.preview("What did Alpha do today?", apps, companies, now, "openrouter/auto", Provider.OPENROUTER)
                .getValue("preview_id").jsonPrimitive.content
            assertEquals(reason, c.ask(id, key, true, apps, companies, now).result.s("fallback_reason"))
        }
    }

    @Test fun tipsAreKeptUncheckedButFactsStillValidated() {
        val inner = """{"sentences":[
            {"text":"Alpha mushroom made 3 lookups today.","cites":[1],"kind":"fact"},
            {"text":"Alpha made 999 lookups at 03:12.","cites":[987654],"kind":"fact"},
            {"text":"Braze is a marketing service that tracks how you use apps.","cites":[],"kind":"tip"}]}"""
        reply = { HttpReply(200, buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject { put("finish_reason", "stop"); put("message", buildJsonObject { put("content", inner) }) })))
        }.toString()) }
        val c = ai()
        val id = c.preview("What did Alpha do today?", apps, companies, now, "openrouter/auto", Provider.OPENROUTER).getValue("preview_id").jsonPrimitive.content
        val out = c.ask(id, key, true, apps, companies, now).result
        assertEquals("cloud", out.s("engine"))
        val kinds = out.getValue("sentences").jsonArray.map { it.jsonObject.s("kind") }
        assertEquals(listOf("fact", "tip"), kinds)                     // the fabricated fact is gone, the tip stays
        assertEquals(1, out.getValue("dropped").jsonPrimitive.int)
    }
}
