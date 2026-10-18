package app.mycelium.engine.chat

import java.util.Calendar
import java.util.TimeZone

data class AppRef(val pkg: String, val label: String, val watched: Boolean)

/** [kind]: at | hour | night | yesterday | today | week. [atMs]: the hh:mm asked about. */
data class TimeSlot(val from: Long, val to: Long, val label: String, val kind: String, val atMs: Long? = null)

data class Parsed(
    val intent: String,
    val app: AppRef? = null,
    val time: TimeSlot? = null,
    val domain: String? = null,
    val company: String? = null,
    val by: String = "lookups",
)

/** Keyword rules + fuzzy app-name matching. No model: same input, same answer, on every phone. */
object Router {
    private const val H = 3600_000L
    private const val D = 24 * H

    private val COMPANY_ALIASES = mapOf("facebook" to "Meta", "instagram ads" to "Meta", "twitter" to "X", "tiktok ads" to "ByteDance")

    /** Common question words never fuzzy-match an app name. */
    private val STOP = setOf(
        "what", "when", "which", "does", "today", "yesterday", "night", "last", "hour", "week", "this", "that", "data",
        "much", "many", "contact", "contacted", "talk", "talks", "talked", "apps", "most", "companies", "company",
        "trackers", "tracker", "tracked", "blocked", "block", "happened", "recently", "recent", "unusual", "compare",
        "with", "about", "tell", "anything", "used", "going", "show", "give", "have", "been", "from", "they", "there",
        "their", "phone", "time", "times", "lookups", "lookup", "domain", "domains", "morning", "minutes", "help",
        "watch", "watching", "stop", "start", "please", "could", "would", "should", "read", "messages", "message",
        "more", "less", "than", "versus", "ever", "over", "doing", "into", "send", "sent", "know", "where", "whats",
    )
    private val GENERIC = setOf("mobile", "lite", "apps", "beta", "free", "plus", "with", "the", "for", "and")

    fun route(text: String, apps: List<AppRef>, companies: Collection<String>, now: Long, tz: TimeZone): Parsed {
        val t = " " + text.lowercase().replace(Regex("[^a-z0-9.:'\\s-]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        fun has(vararg ks: String) = ks.any { t.contains(it) }

        val unwatch = Regex("\\b(stop watching|unwatch|stop monitoring)\\s+(.+)").find(t)
        val watch = Regex("^\\s*(please\\s+)?(start\\s+)?(watch|monitor)(ing)?\\s+(.+)").find(t)
        val target = unwatch?.groupValues?.get(2) ?: watch?.groupValues?.get(5)
        val app = matchApp(if (target != null) " $target " else t, apps)
        val time = parseTime(t, now, tz)
        val domain = Regex("\\b([a-z0-9-]+\\.)+[a-z]{2,}\\b").find(t)?.value
        val company = (companies.firstOrNull { c -> c.length >= 3 && word(t, c.lowercase()) }
            ?: COMPANY_ALIASES.entries.firstOrNull { word(t, it.key) }?.value)
            ?.takeUnless { app != null && it.equals(app.label, ignoreCase = true) }   // "Google" the app beats Google the company
        val target2 = domain != null || company != null

        val intent = when {
            has("password", "message", "content", "packet", "payload", "read my", "what did i type", "email", "photo", "chats ") -> "limits"
            unwatch != null -> "unwatch_app"
            watch != null -> "watch_app"
            has(" help ", "what can you", "what do you know", "how do you work", "what are you", "how does this work") -> "help"
            has("screen time", "screentime", "screen-time", "how long", "how much time", "on screen", "on my screen", "spent on", "spend on",
                "spent in", "usage time", "most used", "used the most", "use the most", "used most", "time on ", "time in ") -> "screen_time"
            has("compare", " vs ", " versus ") -> "compare"
            has("night", "overnight", "while i slept", "while i was asleep", " sleep") -> "night_report"
            has("anomal", "unusual", "strange", "weird", "suspicious", " odd ", "alert") -> "anomaly_report"
            has("block") -> "blocked_report"
            has(" data ", "bytes", "megabyte", "gigabyte", " mb ", " gb ", "bandwidth", "download", "upload", "traffic") -> "bytes_report"
            target2 -> "when_contacted"
            has("compan", "tracker", "parasite", "who tracks", "who is tracking", "spying", "spies") -> "top_companies"
            has("which app", "what app", "top app", " most", "busiest", "noisiest", "worst", "loudest", "chattiest") -> "top_apps"
            has("recent", "latest", "what happened", "just happened", "what's new", "whats new", "just now", "going on", "lately") || time?.kind == "at" ->
                "what_happened_recently"
            app != null -> "app_summary"
            else -> "out_of_scope"
        }
        val by = when {
            has("tracker") -> "trackers"
            has("background") -> "background"
            has(" data ", "bytes", "traffic", "bandwidth") -> "bytes"
            else -> "lookups"
        }
        return Parsed(intent, app, time, domain, company, by)
    }

    fun parseTime(t: String, now: Long, tz: TimeZone): TimeSlot? {
        val midnight = startOfDay(now, tz)
        val hm = Regex("(?<![\\d.:])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d])").find(t)
        fun has(vararg ks: String) = ks.any { t.contains(it) }
        return when {
            hm != null -> {
                var at = midnight + hm.groupValues[1].toLong() * H + hm.groupValues[2].toLong() * 60_000
                if (at > now) at = startOfDay(midnight - 1, tz) + (at - midnight)
                TimeSlot(at - 5 * 60_000, at + 5 * 60_000, "around %02d:%02d".format(hm.groupValues[1].toInt(), hm.groupValues[2].toInt()), "at", at)
            }
            has("last hour", "past hour", "this hour", "last 60 minutes") -> TimeSlot(now - H, now, "in the last hour", "hour")
            has("last night", "tonight", "overnight", "while i slept", "while i was asleep") ->
                TimeSlot(midnight, minOf(midnight + 6 * H, now), "last night", "night")
            has("yesterday") -> TimeSlot(startOfDay(midnight - 1, tz), midnight, "yesterday", "yesterday")
            has("today", "this morning", "so far") -> TimeSlot(midnight, now, "today", "today")
            has("this week", "past week", "last week", "last 7 days", "past 7 days") -> TimeSlot(now - 7 * D, now, "this week", "week")
            else -> null
        }
    }

    fun startOfDay(ts: Long, tz: TimeZone): Long = Calendar.getInstance(tz).apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Best app: whole label in text, else a label word within edit distance 1 (2 for long words). */
    fun matchApp(t: String, apps: List<AppRef>): AppRef? {
        val tokens = t.split(' ').map { it.trim('.', ':', '\'', '-') }.filter { it.length >= 4 && it !in STOP }
        var best: AppRef? = null
        var bestScore = 0
        for (a in apps) {
            val label = a.label.lowercase().trim()
            if (label.length < 2) continue
            var score = 0
            if (word(t, label)) score = 100 + label.length
            else {
                val words = label.split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in GENERIC }
                for (tok in tokens) for (w in words) {
                    val d = if (tok == w) 0 else editDistance(tok, w)
                    if (d <= if (w.length >= 7) 2 else 1) score = maxOf(score, 60 - 10 * d - (words.size - 1) * 5)
                }
            }
            if (score > bestScore || (score == bestScore && score > 0 && a.watched && best?.watched != true)) {
                best = a; bestScore = score
            }
        }
        return best?.takeIf { bestScore >= 40 }
    }

    private fun word(t: String, w: String) = Regex("(?<![a-z0-9])" + Regex.escape(w) + "(?![a-z0-9])").containsMatchIn(t)

    /** Edit distance where swapping two adjacent letters counts as one edit (optimal string alignment). */
    fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
