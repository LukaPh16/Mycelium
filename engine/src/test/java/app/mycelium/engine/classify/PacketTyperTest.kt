package app.mycelium.engine.classify

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PacketTyperTest {
    private val typer = PacketTyper.parse(File(System.getProperty("packetTypesPath")!!).readText())

    @Test fun exactTypeListInFileOrder() = assertEquals(
        listOf("dns", "tracker", "https", "http", "tcp", "quic", "udp", "dot", "doh", "ntp", "mdns", "stun", "push", "icmp", "os_packets", "other"),
        typer.ids,
    )

    @Test fun trackerBeatsProtocol() {
        assertEquals("tracker", typer.type("tripwire", "tcp", 443, "doubleclick.net", "Google"))
        assertEquals("tracker", typer.type("dns", "udp", 53, "doubleclick.net", "Google"))
        assertEquals("https", typer.type("tripwire", "tcp", 443, null, null))
        assertEquals("dns", typer.type("dns", "udp", 53, "example.com", null))
    }

    @Test fun portsComeFromTheFileAndMostSpecificRuleWins() {
        assertEquals("http", typer.type(null, "tcp", 8080, null, null))
        assertEquals("dot", typer.type(null, "tcp", 853, null, null))            // beats generic "tcp" listed earlier
        assertEquals("push", typer.type(null, "tcp", 5229, null, null))          // range 5228-5230
        assertEquals("stun", typer.type(null, "udp", 19302, null, null))
        assertEquals("quic", typer.type(null, "udp", 443, null, null))
        assertEquals("ntp", typer.type(null, "UDP", 123, null, null))
        assertEquals("mdns", typer.type(null, "udp", 1900, null, null))
        assertEquals("icmp", typer.type(null, "icmpv6", null, null, null))
        assertEquals("doh", typer.type(null, "tcp", 443, "dns.google", null))   // named host beats plain https
        assertEquals("doh", typer.type(null, "tcp", 443, "mozilla.cloudflare-dns.com", null))
        assertEquals("os_packets", typer.type(null, null, null, null, null, source = "os_stats"))
    }

    @Test fun unlistedPortOrProtocol() {
        assertEquals("tcp", typer.type(null, "tcp", 9999, null, null))           // "TCP, any other port"
        assertEquals("udp", typer.type(null, "udp", 9999, null, null))
        assertEquals("other", typer.type(null, "sctp", 9999, null, null))
        assertEquals("other", typer.type(null, null, null, null, null))
    }

    @Test fun unknownIdIsNeutral() {
        assertEquals("#9AA0A6", typer.neutral)
        assertEquals("#9AA0A6", typer.color("carrier-pigeon"))
        assertEquals("#9AA0A6", typer.color(null))
        assertEquals("#FF5252", typer.color("tracker"))
        assertEquals("other", typer.known("carrier-pigeon"))
        assertEquals("other", PacketTyper(emptyList(), "#000", emptySet()).type("dns", "udp", 53, null, null))
    }
}
