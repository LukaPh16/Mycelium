package app.mycelium.engine.dns

/** Pure-Kotlin IPv4/UDP/DNS/TCP packet code. IPv4 addresses are Ints (big-endian). IPv6 input -> null. */

class DnsQuery(
    val id: Int, val qname: String, val qtype: Int,
    val srcIp: Int, val dstIp: Int, val srcPort: Int, val dstPort: Int,
    val rd: Boolean, val question: ByteArray,   // raw question section (name+type+class)
    val payload: ByteArray,                     // whole DNS message, for forwarding upstream
)

class TcpSyn(val srcIp: Int, val dstIp: Int, val srcPort: Int, val dstPort: Int, val seq: Long)

object Packets {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41
    const val TYPE_HTTPS = 65
    const val MAX_TTL = 10

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
    private fun i32(b: ByteArray, i: Int) = (u16(b, i) shl 16) or u16(b, i + 2)
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
    private fun put32(b: ByteArray, i: Int, v: Int) { put16(b, i, v ushr 16); put16(b, i + 2, v and 0xFFFF) }

    fun ipToString(ip: Int) = "${ip ushr 24 and 255}.${ip ushr 16 and 255}.${ip ushr 8 and 255}.${ip and 255}"

    // ---- checksums ----

    /** One's-complement sum of 16-bit words (odd trailing byte padded with 0), folded, not inverted. */
    private fun sum16(b: ByteArray, off: Int, len: Int, init: Long = 0): Long {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) { s += u16(b, i); i += 2 }
        if (i < end) s += u8(b, i) shl 8
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return s
    }

    fun ipChecksum(b: ByteArray, off: Int, len: Int): Int = (sum16(b, off, len).inv() and 0xFFFF).toInt()

    /** Checksum of a TCP/UDP segment at [off] of length [len], with the IPv4 pseudo header. */
    private fun l4Checksum(b: ByteArray, off: Int, len: Int, src: Int, dst: Int, proto: Int): Int {
        var s = (src ushr 16).toLong() + (src and 0xFFFF) + (dst ushr 16) + (dst and 0xFFFF) + proto + len
        s = sum16(b, off, len, s)
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    // ---- IPv4 header ----

    /** Returns IHL in bytes if [p] is an unfragmented IPv4 packet of protocol [proto] with a sane length; else -1. */
    private fun ipv4Header(p: ByteArray, proto: Int): Int {
        if (p.size < 20 || u8(p, 0) shr 4 != 4) return -1
        val ihl = (u8(p, 0) and 15) * 4
        if (ihl < 20 || p.size < ihl) return -1
        if (u8(p, 9) != proto) return -1
        if (u16(p, 6) and 0x3FFF != 0) return -1          // MF flag or fragment offset
        val total = u16(p, 2)
        if (total < ihl || total > p.size) return -1
        return ihl
    }

    private fun writeIpv4(p: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000)   // DF
        p[8] = 64
        p[9] = proto.toByte()
        put32(p, 12, src)
        put32(p, 16, dst)
        put16(p, 10, ipChecksum(p, 0, 20))
    }

    // ---- DNS query ----

    fun parseDnsQuery(p: ByteArray): DnsQuery? {
        val ihl = ipv4Header(p, 17)
        if (ihl < 0 || p.size < ihl + 8) return null
        val udpLen = u16(p, ihl + 4)
        val total = u16(p, 2)
        if (udpLen < 8 || ihl + udpLen > total) return null
        val d = ihl + 8
        val end = ihl + udpLen
        if (end - d < 12) return null
        val flags = u16(p, d + 2)
        if (flags and 0x8000 != 0) return null            // a response, not a query
        if ((flags shr 11) and 15 != 0) return null       // not a standard query
        if (u16(p, d + 4) < 1) return null
        var i = d + 12
        val name = StringBuilder()
        while (true) {
            if (i >= end) return null
            val n = u8(p, i)
            if (n == 0) { i++; break }
            if (n and 0xC0 != 0 || i + 1 + n > end) return null
            if (name.isNotEmpty()) name.append('.')
            for (k in 1..n) name.append(u8(p, i + k).toChar().lowercaseChar())
            i += 1 + n
        }
        if (i + 4 > end) return null
        return DnsQuery(
            id = u16(p, d), qname = name.toString(), qtype = u16(p, i),
            srcIp = i32(p, 12), dstIp = i32(p, 16), srcPort = u16(p, ihl), dstPort = u16(p, ihl + 2),
            rd = flags and 0x0100 != 0, question = p.copyOfRange(d + 12, i + 4), payload = p.copyOfRange(d, end),
        )
    }

    // ---- DNS replies ----

    private fun dnsHeader(q: DnsQuery, an: Int, out: ByteArray) {
        put16(out, 0, q.id)
        put16(out, 2, 0x8080 or (if (q.rd) 0x0100 else 0))   // QR + RA (+ RD echoed), rcode 0
        put16(out, 4, 1)
        put16(out, 6, an)
    }

    /** DNS payload answering with A records for [ips] (an empty list gives NODATA). */
    fun buildAnswerA(q: DnsQuery, ips: List<Int>, ttl: Int = MAX_TTL): ByteArray {
        val out = ByteArray(12 + q.question.size + ips.size * 16)
        dnsHeader(q, ips.size, out)
        q.question.copyInto(out, 12)
        var o = 12 + q.question.size
        for (ip in ips) {
            put16(out, o, 0xC00C); put16(out, o + 2, TYPE_A); put16(out, o + 4, 1)
            put32(out, o + 6, ttl); put16(out, o + 10, 4); put32(out, o + 12, ip)
            o += 16
        }
        return out
    }

    /** Empty NOERROR answer, used for AAAA and HTTPS(65). */
    fun buildNoData(q: DnsQuery): ByteArray = buildAnswerA(q, emptyList())

    /** Position after the name at [i] (labels or compression pointer), or -1. */
    private fun skipName(b: ByteArray, start: Int): Int {
        var i = start
        while (i < b.size) {
            val n = u8(b, i)
            if (n == 0) return i + 1
            if (n and 0xC0 == 0xC0) return if (i + 2 <= b.size) i + 2 else -1
            if (n and 0xC0 != 0) return -1
            i += 1 + n
        }
        return -1
    }

    private inline fun walkRecords(b: ByteArray, onRecord: (type: Int, ttlOff: Int, rdOff: Int, rdLen: Int, isAnswer: Boolean) -> Unit): Boolean {
        if (b.size < 12) return false
        val qd = u16(b, 4)
        val counts = intArrayOf(u16(b, 6), u16(b, 8), u16(b, 10))
        var i = 12
        repeat(qd) { i = skipName(b, i); if (i < 0 || i + 4 > b.size) return false; i += 4 }
        for ((section, n) in counts.withIndex()) {
            repeat(n) {
                i = skipName(b, i)
                if (i < 0 || i + 10 > b.size) return false
                val rdLen = u16(b, i + 8)
                if (i + 10 + rdLen > b.size) return false
                onRecord(u16(b, i), i + 4, i + 10, rdLen, section == 0)
                i += 10 + rdLen
            }
        }
        return true
    }

    /** Copy of an upstream DNS reply with every TTL capped at [maxTtl]. OPT pseudo-records are left alone. Null if malformed. */
    fun rewriteTtls(reply: ByteArray, maxTtl: Int = MAX_TTL): ByteArray? {
        val out = reply.copyOf()
        val ok = walkRecords(out) { type, ttlOff, _, _, _ ->
            if (type != TYPE_OPT) {
                val ttl = u32(out, ttlOff)
                if (ttl > maxTtl) put32(out, ttlOff, maxTtl)
            }
        }
        return if (ok) out else null
    }

    /** IPs (A and AAAA) in the answer section of a DNS reply, as strings. Empty if malformed. */
    fun extractIps(reply: ByteArray): List<String> {
        val ips = mutableListOf<String>()
        walkRecords(reply) { type, _, rd, len, answer ->
            if (answer && type == TYPE_A && len == 4) ips += ipToString(i32(reply, rd))
            if (answer && type == TYPE_AAAA && len == 16) {
                ips += (0 until 8).joinToString(":") { "%x".format(u16(reply, rd + it * 2)) }
            }
        }
        return ips
    }

    // ---- UDP reply ----

    /** Full IPv4/UDP packet carrying [dns] back to the sender of [q]. */
    fun buildUdpReply(q: DnsQuery, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val p = ByteArray(20 + udpLen)
        writeIpv4(p, p.size, 17, q.dstIp, q.srcIp)
        put16(p, 20, q.dstPort); put16(p, 22, q.srcPort); put16(p, 24, udpLen)
        dns.copyInto(p, 28)
        val c = l4Checksum(p, 20, udpLen, q.dstIp, q.srcIp, 17)
        put16(p, 26, if (c == 0) 0xFFFF else c)
        return p
    }

    // ---- TCP ----

    /** SYN without ACK from an IPv4/TCP packet. */
    fun parseTcpSyn(p: ByteArray): TcpSyn? {
        val ihl = ipv4Header(p, 6)
        if (ihl < 0 || u16(p, 2) < ihl + 20) return null
        val flags = u8(p, ihl + 13)
        if (flags and 0x02 == 0 || flags and 0x10 != 0 || flags and 0x04 != 0) return null
        return TcpSyn(i32(p, 12), i32(p, 16), u16(p, ihl), u16(p, ihl + 2), u32(p, ihl + 4))
    }

    /** RST+ACK answering [s]: seq 0, ack = seq + 1. */
    fun buildTcpRst(s: TcpSyn): ByteArray {
        val p = ByteArray(40)
        writeIpv4(p, 40, 6, s.dstIp, s.srcIp)
        put16(p, 20, s.dstPort); put16(p, 22, s.srcPort)
        put32(p, 24, 0)
        put32(p, 28, ((s.seq + 1) and 0xFFFFFFFFL).toInt())
        p[32] = 0x50                // data offset 5 words
        p[33] = 0x14                // RST | ACK
        put16(p, 36, l4Checksum(p, 20, 20, s.dstIp, s.srcIp, 6))
        return p
    }
}
