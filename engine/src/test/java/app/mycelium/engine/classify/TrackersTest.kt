package app.mycelium.engine.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrackersTest {
    private val trackers = Trackers.parse(File(System.getProperty("trackersPath")!!).readText())

    @Test fun assetHasAbout150KnownDomains() {
        assertTrue("size ${trackers.size}", trackers.size >= 150)
        for (d in listOf("doubleclick.net", "appsflyer.com", "adjust.com", "branch.io", "applovin.com", "mixpanel.com",
            "amplitude.com", "sentry.io", "crashlytics.com", "onesignal.com", "mc.yandex.ru", "criteo.com", "taboola.com",
            "outbrain.com", "graph.facebook.com", "supersonicads.com", "unityads.unity3d.com", "segment.io", "pangle.io")) {
            assertTrue(d, trackers.match(d) != null)
        }
    }

    @Test fun exactMatch() {
        assertEquals(Trackers.Match("doubleclick.net", "Google", "ads"), trackers.match("doubleclick.net"))
    }

    @Test fun suffixMatchWalksUpLabels() {
        assertEquals("doubleclick.net", trackers.match("googleads.g.doubleclick.net")!!.suffix)
        assertEquals("AppsFlyer", trackers.match("t.appsflyer.com")!!.company)
        assertEquals("Yandex", trackers.match("MC.Yandex.RU.")!!.company)   // case and trailing dot
    }

    @Test fun mostSpecificSuffixWins() {
        val t = Trackers(mapOf("example.com" to Trackers.Entry("A", "ads"), "x.example.com" to Trackers.Entry("B", "analytics")))
        assertEquals("B", t.match("y.x.example.com")!!.company)
        assertEquals("A", t.match("z.example.com")!!.company)
    }

    @Test fun noPartialLabelMatch() {
        assertNull(trackers.match("notdoubleclick.net"))
        assertNull(trackers.match("doubleclick.net.evil.org"))
        assertNull(trackers.match("example.com"))
        assertNull(trackers.match("net"))
    }

    @Test fun facebookAppDomainItselfIsNotListed() = assertNull(trackers.match("facebook.com"))

    @Test fun lookupsAreFast() {
        val start = System.nanoTime()
        repeat(100_000) { trackers.match("a.b.c.googleads.g.doubleclick.net") }
        val perLookupUs = (System.nanoTime() - start) / 100_000 / 1000.0
        assertTrue("$perLookupUs us per lookup", perLookupUs < 20)
    }

    @Test fun suffixesSequence() {
        assertEquals(listOf("a.b.com", "b.com", "com"), suffixes("A.b.com.").toList())
    }
}
