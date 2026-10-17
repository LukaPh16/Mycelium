package app.mycelium.engine.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

    /** Independent check: folded one's-complement sum over [len] bytes (+ pseudo header sum) must be 0xFFFF. */
    private fun sumOk(b: ByteArray, off: Int, len: Int, pseudo: Long = 0): Boolean {
        var s = pseudo
        var i = off
        while (i + 1 < off + len) { s += u16(b, i); i += 2 }
        if (i < off + len) s += u8(b, i) shl 8
        while (s > 0xFFFF) s = (s and 0xFFFF) + (s shr 16)
        return s == 0xFFFFL
    }
    private fun pseudo(b: ByteArray, proto: Int, len: Int) =
        (u16(b, 12) + u16(b, 14) + u16(b, 16) + u16(b, 18) + proto + len).toLong()

    // 10.0.0.2:40000 -> 10.0.0.1:53, id 0x1234, RD, example.com type A. Checksums left at 0 (unchecked on parse).
    private fun dnsQueryPacket(qtype: Int = 1, name: List<String> = listOf("Example", "com"), flags: Int = 0x0100): ByteArray {
        val q = ArrayList<Int>()
        for (l in name) { q += l.length; l.forEach { q += it.code } }
        q += 0; q += qtype shr 8; q += qtype and 255; q += 0; q += 1
        val dns = intArrayOf(0x12, 0x34, flags shr 8, flags and 255, 0, 1, 0, 0, 0, 0, 0, 0) + q.toIntArray()
        val udpLen = 8 + dns.size
        val total = 20 + udpLen
        val ip = intArrayOf(0x45, 0, total shr 8, total and 255, 0, 0, 0x40, 0, 64, 17, 0, 0, 10, 0, 0, 2, 10, 0, 0, 1)
        val udp = intArrayOf(0x9C, 0x40, 0, 53, udpLen shr 8, udpLen and 255, 0, 0)
        return bytes(*(ip + udp + dns))
    }

    @Test fun parsesDnsQuery() {
        val q = Packets.parseDnsQuery(dnsQueryPacket())!!
        assertEquals("example.com", q.qname)
        assertEquals(1, q.qtype)
        assertEquals(0x1234, q.id)
        assertEquals(40000, q.srcPort)
        assertEquals(53, q.dstPort)
        assertEquals("10.0.0.2", Packets.ipToString(q.srcIp))
        assertEquals("10.0.0.1", Packets.ipToString(q.dstIp))
        assertTrue(q.rd)
        assertEquals(17, q.question.size)
    }

    @Test fun parsesAaaaAndHttpsTypes() {
        assertEquals(28, Packets.parseDnsQuery(dnsQueryPacket(28))!!.qtype)
        assertEquals(65, Packets.parseDnsQuery(dnsQueryPacket(65))!!.qtype)
    }

    @Test fun rejectsNonQueries() {
        val ok = dnsQueryPacket()
        assertNull("ipv6", Packets.parseDnsQuery(ok.copyOf().also { it[0] = 0x60 }))
        assertNull("tcp", Packets.parseDnsQuery(ok.copyOf().also { it[9] = 6 }))
        assertNull("response", Packets.parseDnsQuery(dnsQueryPacket(flags = 0x8180)))
        assertNull("fragment", Packets.parseDnsQuery(ok.copyOf().also { it[6] = 0x20 }))
        assertNull("truncated", Packets.parseDnsQuery(ok.copyOfRange(0, 35)))
        assertNull("empty", Packets.parseDnsQuery(ByteArray(0)))
        assertNull("pointer in question", Packets.parseDnsQuery(ok.copyOf().also { it[40] = 0xC0.toByte() }))
    }

    @Test fun buildsAnswerA() {
        val q = Packets.parseDnsQuery(dnsQueryPacket())!!
        val r = Packets.buildAnswerA(q, listOf(0x01020304, 0x05060708), ttl = 7)
        assertEquals(0x1234, u16(r, 0))
        assertEquals(0x8180, u16(r, 2))
        assertEquals(1, u16(r, 4)); assertEquals(2, u16(r, 6))
        assertArrayEquals(q.question, r.copyOfRange(12, 29))
        assertEquals(0xC00C, u16(r, 29)); assertEquals(1, u16(r, 31)); assertEquals(7L, u32(r, 35))
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), Packets.extractIps(r))
    }

    @Test fun buildsNoData() {
        val q = Packets.parseDnsQuery(dnsQueryPacket(28))!!
        val r = Packets.buildNoData(q)
        assertEquals(29, r.size)
        assertEquals(0x8180, u16(r, 2))
        assertEquals(0, u16(r, 6))
        assertEquals(emptyList<String>(), Packets.extractIps(r))
    }

    // Upstream reply: question example.com A; answers: CNAME (ttl 3600) -> A 93.184.216.34 (ttl 300); authority NS ttl 86400; OPT ttl field 0x00008000.
    private val upstream = bytes(
        0x12, 0x34, 0x81, 0x80, 0, 1, 0, 2, 0, 1, 0, 1,
        7, 'e'.code, 'x'.code, 'a'.code, 'm'.code, 'p'.code, 'l'.code, 'e'.code, 3, 'c'.code, 'o'.code, 'm'.code, 0, 0, 1, 0, 1,
        0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 0x0E, 0x10, 0, 2, 0xC0, 0x0C,
        0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0x01, 0x2C, 0, 4, 93, 184, 216, 34,
        0xC0, 0x0C, 0, 2, 0, 1, 0, 1, 0x51, 0x80, 0, 2, 0xC0, 0x0C,
        0, 0, 41, 0x10, 0, 0, 0, 0x80, 0, 0, 0,
    )

    @Test fun rewritesAllTtlsExceptOpt() {
        val r = Packets.rewriteTtls(upstream)!!
        assertEquals(upstream.size, r.size)
        assertEquals(10L, u32(r, 35))                       // CNAME 3600 -> 10
        assertEquals(10L, u32(r, 49))                       // A 300 -> 10
        assertEquals(10L, u32(r, 65))                       // NS 86400 -> 10
        assertEquals(0x8000L, u32(r, r.size - 6))           // OPT flags untouched
        assertEquals(3600L, u32(upstream, 35))              // input not mutated
    }

    @Test fun keepsSmallerTtl() {
        val small = upstream.copyOf().also { it[35] = 0; it[36] = 0; it[37] = 0; it[38] = 4 }
        assertEquals(4L, u32(Packets.rewriteTtls(small)!!, 35))
    }

    @Test fun rewriteRejectsMalformed() {
        assertNull(Packets.rewriteTtls(upstream.copyOfRange(0, 50)))
        assertNull(Packets.rewriteTtls(ByteArray(5)))
    }

    @Test fun extractsAnsweredIps() {
        assertEquals(listOf("93.184.216.34"), Packets.extractIps(upstream))
        val aaaa = bytes(0x12, 0x34, 0x81, 0x80, 0, 0, 0, 1, 0, 0, 0, 0,
            0, 0, 28, 0, 1, 0, 0, 0, 5, 0, 16, 0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        assertEquals(listOf("2001:db8:0:0:0:0:0:1"), Packets.extractIps(aaaa))
    }

    @Test fun buildsUdpReplyWithValidChecksums() {
        val q = Packets.parseDnsQuery(dnsQueryPacket())!!
        val dns = Packets.buildAnswerA(q, listOf(0x01020304))
        val p = Packets.buildUdpReply(q, dns)
        assertEquals(20 + 8 + dns.size, p.size)
        assertEquals(p.size, u16(p, 2))
        assertEquals(0x45, u8(p, 0))
        assertEquals("10.0.0.1", Packets.ipToString((u16(p, 12) shl 16) or u16(p, 14)))
        assertEquals("10.0.0.2", Packets.ipToString((u16(p, 16) shl 16) or u16(p, 18)))
        assertEquals(53, u16(p, 20)); assertEquals(40000, u16(p, 22))
        assertEquals(8 + dns.size, u16(p, 24))
        assertTrue("ip checksum", sumOk(p, 0, 20))
        assertTrue("udp checksum", sumOk(p, 20, p.size - 20, pseudo(p, 17, p.size - 20)))
        assertNotNull(u16(p, 26).takeIf { it != 0 })
        assertArrayEquals(dns, p.copyOfRange(28, p.size))
    }

    @Test fun udpReplyWithOddLengthStillChecksums() {
        val q = Packets.parseDnsQuery(dnsQueryPacket(name = listOf("ab", "cd")))!!
        val p = Packets.buildUdpReply(q, Packets.buildAnswerA(q, listOf(1)))
        assertEquals(1, p.size % 2)
        assertTrue(sumOk(p, 20, p.size - 20, pseudo(p, 17, p.size - 20)))
    }

    // 10.0.0.2:50000 -> 93.184.216.34:443 SYN seq 0xFFFFFFFF, options MSS (header 24 bytes).
    private val syn = bytes(
        0x45, 0, 0, 44, 0, 0, 0x40, 0, 64, 6, 0, 0, 10, 0, 0, 2, 93, 184, 216, 34,
        0xC3, 0x50, 0x01, 0xBB, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0x60, 0x02, 0xFF, 0xFF, 0, 0, 0, 0,
        2, 4, 5, 0xB4,
    )

    @Test fun parsesSyn() {
        val s = Packets.parseTcpSyn(syn)!!
        assertEquals(50000, s.srcPort); assertEquals(443, s.dstPort)
        assertEquals("93.184.216.34", Packets.ipToString(s.dstIp))
        assertEquals(0xFFFFFFFFL, s.seq)
    }

    @Test fun rejectsNonSyn() {
        assertNull("syn-ack", Packets.parseTcpSyn(syn.copyOf().also { it[33] = 0x12 }))
        assertNull("ack only", Packets.parseTcpSyn(syn.copyOf().also { it[33] = 0x10 }))
        assertNull("rst", Packets.parseTcpSyn(syn.copyOf().also { it[33] = 0x06 }))
        assertNull("udp", Packets.parseTcpSyn(syn.copyOf().also { it[9] = 17 }))
        assertNull("ipv6", Packets.parseTcpSyn(syn.copyOf().also { it[0] = 0x60 }))
        assertNull("short", Packets.parseTcpSyn(syn.copyOfRange(0, 30)))
        assertFalse(Packets.parseTcpSyn(syn) == null)
    }

    @Test fun buildsRst() {
        val s = Packets.parseTcpSyn(syn)!!
        val p = Packets.buildTcpRst(s)
        assertEquals(40, p.size)
        assertEquals(6, u8(p, 9))
        assertEquals("93.184.216.34", Packets.ipToString((u16(p, 12) shl 16) or u16(p, 14)))
        assertEquals("10.0.0.2", Packets.ipToString((u16(p, 16) shl 16) or u16(p, 18)))
        assertEquals(443, u16(p, 20)); assertEquals(50000, u16(p, 22))
        assertEquals(0L, u32(p, 24))
        assertEquals(0L, u32(p, 28))                         // 0xFFFFFFFF + 1 wraps to 0
        assertEquals(0x14, u8(p, 33))                        // RST | ACK
        assertTrue(sumOk(p, 0, 20))
        assertTrue(sumOk(p, 20, 20, pseudo(p, 6, 20)))
    }

    @Test fun rstAckIsSeqPlusOne() {
        val p = Packets.buildTcpRst(TcpSyn(1, 2, 3, 4, 1000))
        assertEquals(1001L, u32(p, 28))
    }

    @Test fun checksumKnownVector() {
        // RFC 1071 example: words 0001 f203 f4f5 f6f7 -> sum ddf2, checksum 220d
        val b = bytes(0x00, 0x01, 0xf2, 0x03, 0xf4, 0xf5, 0xf6, 0xf7)
        assertEquals(0x220d, Packets.ipChecksum(b, 0, b.size))
    }
}
