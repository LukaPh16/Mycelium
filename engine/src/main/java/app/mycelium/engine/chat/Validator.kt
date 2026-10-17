package app.mycelium.engine.chat

import app.mycelium.engine.db.Row
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Drops any sentence that is not grounded: a cited id that does not exist, an app or company named in the
 * sentence that is not in its cited rows, a time/date that matches neither a cited row nor FACTS, or any other
 * number that is not a value in FACTS.
 */
object Validator {
    data class Result(val kept: List<Sentence>, val dropped: Int)

    private val TIME = Regex("(?<![\\d:])([01]\\d|2[0-3]):([0-5]\\d)(?![\\d:])")
    private val DATE = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
    private val DOMAIN = Regex("\\b([a-z0-9-]+\\.)+[a-z]{2,}\\b", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")

    /**
     * [rows]: id -> row (id, ts, package, company, sim) for the ids that exist. [labels]: lowercase app label -> package.
     * [allowSim]: only in simulate mode may a sentence cite a sim=1 row.
     */
    fun validate(
        sentences: List<Sentence>, facts: JsonObject, rows: (List<Long>) -> Map<Long, Row>,
        labels: Map<String, String>, companies: Collection<String>, tz: TimeZone, allowSim: Boolean = false,
    ): Result {
        val numbers = HashSet<String>()
        val epochs = ArrayList<Long>()
        walk(facts) { p ->
            p.longOrNull?.let { numbers += norm(it.toString()); if (it > 1_000_000_000_000L) epochs += it }
                ?: p.doubleOrNull?.let { numbers += norm(it.toString()); numbers += norm("%.1f".format(Locale.US, it)) }
        }
        val hm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tz }
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val kept = sentences.filter { s ->
            val cited = rows(s.cites)
            (allowSim || cited.values.none { (it["sim"] as Number?)?.toLong() == 1L }) && ok(s, cited, numbers, epochs, labels, companies, hm, day)
        }
        return Result(kept, sentences.size - kept.size)
    }

    private fun ok(
        s: Sentence, cited: Map<Long, Row>, numbers: Set<String>, epochs: List<Long>, labels: Map<String, String>,
        companies: Collection<String>, hm: SimpleDateFormat, day: SimpleDateFormat,
    ): Boolean {
        if (s.cites.any { it !in cited }) return false
        var text = s.text
        val rowTs = cited.values.mapNotNull { (it["ts"] as Number?)?.toLong() }
        if (s.cites.isNotEmpty()) {
            for ((label, pkg) in labels) {
                if (label.length >= 3 && word(text, label) && cited.values.none { it["package"] == pkg }) return false
            }
            for (c in companies) {
                if (c.length >= 3 && word(text, c) && cited.values.none { (it["company"] as String?).equals(c, true) }) return false
            }
        }
        val okTimes = (rowTs + epochs).map { hm.format(it) }.toSet()
        for (m in TIME.findAll(text)) if (m.value !in okTimes) return false
        val okDates = (rowTs + epochs).map { day.format(it) }.toSet()
        for (m in DATE.findAll(text)) if (m.value !in okDates) return false
        text = text.replace(TIME, " ").replace(DATE, " ").replace(DOMAIN, " ")
        for (name in labels.keys + companies) if (name.length >= 2) text = text.replace(Regex("(?i)(?<![a-z0-9])" + Regex.escape(name) + "(?![a-z0-9])"), " ")
        return NUMBER.findAll(text).all { norm(it.value) in numbers }
    }

    private fun walk(e: JsonElement, f: (JsonPrimitive) -> Unit) {
        when (e) {
            is JsonObject -> e.values.forEach { walk(it, f) }
            is JsonArray -> e.forEach { walk(it, f) }
            is JsonPrimitive -> if (!e.isString) f(e)
        }
    }

    private fun norm(n: String) = try { BigDecimal(n).stripTrailingZeros().toPlainString() } catch (_: Exception) { n }

    private fun word(t: String, w: String) = Regex("(?i)(?<![a-z0-9])" + Regex.escape(w) + "(?![a-z0-9])").containsMatchIn(t)
}
