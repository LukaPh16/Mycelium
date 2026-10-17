package app.mycelium.engine.classify

import app.mycelium.engine.classify.RuleSet.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleSetTest {
    @Test fun globalDomainRuleBlocksSubdomainsForEveryone() {
        val r = RuleSet(listOf(Rule("domain", "doubleclick.net")))
        assertEquals("doubleclick.net", r.blocking("ad.doubleclick.net", null, "com.a", "uid_dns")!!.value)
        assertEquals("doubleclick.net", r.blocking("doubleclick.net", null, null, "unknown")!!.value)
        assertNull(r.blocking("notdoubleclick.net", null, "com.a", "uid_dns"))
    }

    @Test fun companyRule() {
        val r = RuleSet(listOf(Rule("company", "Google")))
        assertEquals("Google", r.blocking("x.example", "google", "com.a", "guess_fg")!!.value)
        assertNull(r.blocking("x.example", "Meta", "com.a", "uid_dns"))
        assertNull(r.blocking("x.example", null, "com.a", "uid_dns"))
    }

    @Test fun perAppRuleOnlyForThatApp() {
        val r = RuleSet(listOf(Rule("domain", "appsflyer.com", "com.a")))
        assertEquals("com.a", r.blocking("t.appsflyer.com", null, "com.a", "uid_dns")!!.pkg)
        assertEquals("com.a", r.blocking("t.appsflyer.com", null, "com.a", "uid_tcp")!!.pkg)
        assertNull(r.blocking("t.appsflyer.com", null, "com.b", "uid_dns"))
    }

    @Test fun perAppRuleIgnoredForGuessedOrUnknownAttribution() {
        val r = RuleSet(listOf(Rule("domain", "appsflyer.com", "com.a"), Rule("company", "Meta", "com.a")))
        assertNull(r.blocking("t.appsflyer.com", null, "com.a", "guess_fg"))
        assertNull(r.blocking("t.appsflyer.com", null, "com.a", "unknown"))
        assertNull(r.blocking("graph.facebook.com", "Meta", "com.a", "guess_fg"))
        assertNull(r.blocking("t.appsflyer.com", null, null, "unknown"))
    }

    @Test fun globalAndPerAppRulesCoexist() {
        val r = RuleSet(listOf(Rule("domain", "x.com", "com.a"), Rule("domain", "x.com", "*")))
        assertEquals("*", r.blocking("x.com", null, "com.b", "guess_fg")!!.pkg)
    }

    @Test fun emptyRulesBlockNothing() = assertNull(RuleSet(emptyList()).blocking("doubleclick.net", "Google", "com.a", "uid_dns"))
}
