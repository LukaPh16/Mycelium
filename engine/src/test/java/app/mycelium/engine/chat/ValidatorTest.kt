package app.mycelium.engine.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class ValidatorTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val noon = 1_760_000_000_000L / 86_400_000L * 86_400_000L + 12 * 3600_000L
    private val facts = buildJsonObject {
        put("data", buildJsonObject { put("lookups", 12); put("tracker_hits", 3); put("from_ms", noon - 3600_000L) })
        put("derived", buildJsonObject { put("total", 12.3) })
    }
    private val rows = mapOf(
        1L to mapOf<String, Any?>("id" to 1L, "ts" to noon, "package" to "com.a", "company" to "Google"),
        2L to mapOf<String, Any?>("id" to 2L, "ts" to noon + 60_000, "package" to "com.b", "company" to null),
    )
    private val labels = mapOf("alpha" to "com.a", "beta" to "com.b")

    private fun check(text: String, vararg cites: Long) =
        Validator.validate(listOf(Sentence(text, cites.toList())), facts, { ids -> rows.filterKeys { it in ids } }, labels, listOf("Google", "Meta"), utc)

    @Test fun keepsGroundedSentence() = assertEquals(0, check("Alpha fern made 12 lookups, 3 to Google.", 1).dropped)

    @Test fun dropsFabricatedNumber() = assertEquals(1, check("Alpha fern made 13 lookups.", 1).dropped)

    @Test fun dropsFabricatedId() = assertEquals(1, check("Alpha fern made 12 lookups.", 999).dropped)

    @Test fun dropsWrongAppOrCompany() {
        assertEquals(1, check("Beta made 12 lookups.", 1).dropped)
        assertEquals(1, check("Alpha contacted Meta 3 times.", 1).dropped)
        assertEquals(0, check("Alpha and Beta made 12 lookups.", 1, 2).dropped)
    }

    @Test fun timesMustMatchCitedRowsOrFacts() {
        assertEquals(0, check("At 12:00, Alpha looked up example.com.", 1).dropped)
        assertEquals(0, check("At 11:00 the window opened.").dropped)              // facts from_ms
        assertEquals(1, check("At 12:07, Alpha looked up example.com.", 1).dropped)
    }

    @Test fun decimalsAndDatesAndDomains() {
        assertEquals(0, check("It moved 12.3 MB.").dropped)
        assertEquals(1, check("It moved 12.4 MB.").dropped)
        assertEquals(0, check("On 2025-10-09, Alpha looked up 2mdn.net 12 times.", 1).dropped)
        assertEquals(1, check("On 2025-10-10, Alpha looked up 2mdn.net.", 1).dropped)
    }

    @Test fun countsEveryDroppedSentence() {
        val r = Validator.validate(
            listOf(Sentence("Alpha made 12 lookups.", listOf(1)), Sentence("Alpha made 99 lookups.", listOf(1)), Sentence("Ghost row.", listOf(7))),
            facts, { ids -> rows.filterKeys { it in ids } }, labels, emptyList(), utc,
        )
        assertEquals(2, r.dropped)
        assertEquals(listOf("Alpha made 12 lookups."), r.kept.map { it.text })
    }
}
