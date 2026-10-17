package app.mycelium.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class RouterTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day0 = 1_760_000_000_000L / 86_400_000L * 86_400_000L
    private val now = day0 + 15 * 3600_000L + 20 * 60_000L      // 15:20 UTC
    private val apps = listOf(
        AppRef("com.android.chrome", "Chrome", watched = true),
        AppRef("com.instagram.android", "Instagram", watched = false),
        AppRef("com.zhiliaoapp.musically", "TikTok", watched = true),
        AppRef("com.google.android.youtube", "YouTube", watched = true),
    )
    private val companies = listOf("Google", "Meta", "AppsFlyer", "ByteDance", "X")

    private fun route(q: String) = Router.route(q, apps, companies, now, utc)

    @Test fun fifteenSampleQuestions() {
        val cases = listOf(
            "What did Chrome do today?" to "app_summary",
            "Which app talks the most?" to "top_apps",
            "Which companies tracked me this week?" to "top_companies",
            "When did Chrome last contact doubleclick.net?" to "when_contacted",
            "When did anything talk to Facebook?" to "when_contacted",
            "What happened recently?" to "what_happened_recently",
            "What happened last night?" to "night_report",
            "What got blocked today?" to "blocked_report",
            "How much data did Chrome use yesterday?" to "bytes_report",
            "Compare TikTok today with yesterday" to "compare",
            "Anything unusual in the last hour?" to "anomaly_report",
            "help" to "help",
            "What's the weather in Paris?" to "out_of_scope",
            "Can you read my messages from Chrome?" to "limits",
            "watch Instagram" to "watch_app",
            "stop watching TikTok" to "unwatch_app",
            "tell me about chorme" to "app_summary",
            "What was going on at 14:30?" to "what_happened_recently",
            // the Chat tab's suggestion chips
            "What just happened?" to "what_happened_recently",
            "Who sent the most data today?" to "bytes_report",
            "Which apps talk to Meta?" to "when_contacted",
            "What did I block?" to "blocked_report",
        )
        for ((q, intent) in cases) assertEquals(q, intent, route(q).intent)
    }

    @Test fun slots() {
        assertEquals("com.android.chrome", route("What did Chrome do today?").app?.pkg)
        assertEquals("today", route("What did Chrome do today?").time?.kind)
        assertEquals("doubleclick.net", route("When did Chrome last contact doubleclick.net?").domain)
        assertEquals("Meta", route("When did anything talk to Facebook?").company)
        assertEquals("com.instagram.android", route("watch Instagram").app?.pkg)
        assertEquals("com.zhiliaoapp.musically", route("stop watching TikTok").app?.pkg)
        assertEquals("com.android.chrome", route("tell me about chorme").app?.pkg)        // fuzzy, swapped letters
        assertEquals("com.google.android.youtube", route("what did youtub do").app?.pkg)   // fuzzy, edit distance 1
        assertEquals(null, route("What's the weather in Paris?").app)
        assertEquals("bytes", route("Which app used the most data?").by)
        assertEquals("trackers", route("Which app has the most trackers?").by)
    }

    @Test fun timeSlots() {
        val h = 3600_000L
        assertEquals(day0 to now, route("today").time!!.let { it.from to it.to })
        assertEquals(day0 - 24 * h to day0, route("yesterday").time!!.let { it.from to it.to })
        assertEquals(day0 to day0 + 6 * h, route("last night").time!!.let { it.from to it.to })
        assertEquals(now - h to now, route("in the last hour").time!!.let { it.from to it.to })
        assertEquals(now - 7 * 24 * h, route("this week").time!!.from)
        val at = route("what happened at 14:30").time!!
        assertEquals(day0 + 14 * h + 30 * 60_000, at.atMs)
        assertEquals("around 14:30", at.label)
        assertEquals(day0 - 24 * h + 16 * h, route("what happened at 16:00").time!!.atMs)   // later than now -> yesterday
    }

    @Test fun companyNameThatIsAlsoAnAppPrefersTheApp() {
        val withGoogleApp = apps + AppRef("com.google.android.googlequicksearchbox", "Google", watched = true)
        val p = Router.route("What did Google do today?", withGoogleApp, companies, now, utc)
        assertEquals("app_summary", p.intent)
        assertEquals(null, p.company)
    }

    @Test fun editDistanceCountsSwapsAsOne() {
        assertEquals(0, Router.editDistance("chrome", "chrome"))
        assertEquals(1, Router.editDistance("chorme", "chrome"))
        assertEquals(3, Router.editDistance("kitten", "sitting"))
    }
}
