package app.mycelium.engine.chat

import app.mycelium.engine.FixtureDb
import app.mycelium.engine.FixtureDb.Companion.B
import app.mycelium.engine.FixtureDb.Companion.H
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** End to end on the fixture DB: every planned sentence must survive the validator (dropped = 0). */
class ChatBotTest {
    private lateinit var fx: FixtureDb
    private lateinit var bot: ChatBot
    private val apps = listOf(AppRef("com.a", "Alpha", true), AppRef("com.b", "Beta", true), AppRef("com.c", "Gamma", false))
    private val companies = listOf("Google", "AppsFlyer", "Meta")
    private val now = B + 23 * H

    @Before fun setUp() {
        fx = FixtureDb()
        bot = ChatBot(fx.query, TemplateWriter.parse(File(System.getProperty("chatTemplatesPath")!!).readText()), TimeZone.getTimeZone("UTC"))
    }

    @After fun tearDown() = fx.close()

    private fun ask(q: String): JsonObject = bot.answer(q, apps, companies, now)
    private fun JsonObject.texts() = getValue("sentences").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content }
    private fun JsonObject.cites() = getValue("sentences").jsonArray.flatMap { s -> s.jsonObject.getValue("cites").jsonArray.map { it.jsonPrimitive.content.toLong() } }
    private fun JsonObject.dropped() = getValue("dropped").jsonPrimitive.int

    private fun grounded(q: String, intent: String): JsonObject {
        val r = ask(q)
        assertEquals(q, intent, r.getValue("intent").jsonPrimitive.content)
        assertEquals("$q -> ${r.texts()}", 0, r.dropped())
        assertTrue(q, r.texts().isNotEmpty())
        assertEquals("template", r.getValue("engine").jsonPrimitive.content)
        return r
    }

    @Test fun appSummary() {
        val r = grounded("What did Alpha do today?", "app_summary")
        val t = r.texts()
        assertTrue(t.toString(), "3 lookups" in t[0] && "3 distinct domains" in t[0])
        assertTrue(t.toString(), t.any { "Google" in it })
        assertTrue(t.toString(), t.any { "13:00" in it })          // last seen
        assertTrue(r.cites().toSet().containsAll(listOf(1L, 2L, 3L)))
    }

    @Test fun speciesUsesSeedKingdom() {
        // seed 'a1' -> 0xa1 & 3 = 1 -> mushroom
        assertTrue(ask("What did Alpha do today?").texts()[0].contains("Alpha mushroom"))
    }

    @Test fun topAppsAndCompanies() {
        assertTrue(grounded("Which app was busiest today?", "top_apps").texts()[0].contains("Alpha"))
        val c = grounded("Which companies tracked me today?", "top_companies").texts()
        assertTrue(c.toString(), c[0].startsWith("Google") && "2 lookups" in c[0])
    }

    @Test fun whenContacted() {
        val t = grounded("When did Alpha contact doubleclick.net this week?", "when_contacted").texts()
        assertTrue(t.toString(), "3 times" in t[0] && "first at 12:00" in t[0])
        grounded("When did anything talk to AppsFlyer today?", "when_contacted")
    }

    @Test fun nightBlockedAnomaliesRecentCompare() {
        val night = grounded("What happened last night?", "night_report").texts()
        assertTrue(night.toString(), "00:00 and 06:00 on 2025-10-09" in night[0] && "2 lookups" in night[0])
        assertTrue(grounded("What got blocked today?", "blocked_report").texts()[0].startsWith("1 lookups were blocked"))
        assertTrue(grounded("Anything unusual today?", "anomaly_report").texts()[0].contains("first contact with Google"))
        assertTrue(grounded("What happened at 12:00?", "what_happened_recently").texts().any { "doubleclick.net" in it })
        grounded("Compare Alpha today with yesterday", "compare")
    }

    @Test fun bytesFromTraffic() {
        val t = grounded("How much data did Alpha use today?", "bytes_report").texts()
        assertTrue(t.toString(), "1 KB" in t[0])        // 1800 bytes
    }

    @Test fun notWatchedAppOffersWatch() {
        val r = grounded("Tell me about Gamma", "app_summary")
        assertEquals("com.c", r.getValue("suggest_watch").jsonPrimitive.content)
        assertTrue(r.texts()[0].contains("not being watched"))
        assertTrue(r.texts().any { "97 KB" in it })      // 99999 bytes from traffic
    }

    @Test fun watchNeedsConfirmation() {
        val r = grounded("watch Gamma", "watch_app")
        val action = r.getValue("confirm_action").jsonObject
        assertEquals("watch", action.getValue("method").jsonPrimitive.content)
        assertEquals("com.c", action.getValue("packages").jsonArray[0].jsonPrimitive.content)
    }

    @Test fun limitsNoDataAndOutOfScope() {
        assertTrue(grounded("Can you read my passwords?", "limits").texts()[0].contains("not contents"))
        assertTrue(grounded("When did Alpha contact example.net in the last hour?", "when_contacted").texts()[0].startsWith("I have no record of example.net"))
        assertEquals(listOf("I have no record of that. I only speak about what this phone has seen."), ask("What did Beta do in the last hour?").texts())
        grounded("What is the capital of France?", "out_of_scope")
    }
}
