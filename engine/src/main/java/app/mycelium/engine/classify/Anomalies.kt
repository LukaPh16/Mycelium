package app.mycelium.engine.classify

import java.util.Calendar
import java.util.TimeZone

/** Pure anomaly rules over a 10-minute window of attributed events. */
object Anomalies {
    const val WINDOW_MS = 10 * 60_000L
    const val DEDUP_MS = 3600_000L
    private const val MAX_EVIDENCE = 20

    data class Ev(
        val id: Long, val ts: Long, val kind: String, val pkg: String?,
        val domain: String? = null, val company: String? = null, val fg: Int? = null,
    )

    /** [company] is set for new_company, which is de-duplicated per company. */
    data class Found(val pkg: String, val rule: String, val evidence: List<Long>, val company: String? = null) {
        val key get() = Key(pkg, rule, company.orEmpty())
    }

    data class Key(val pkg: String, val rule: String, val subject: String)

    /**
     * [window]: dns/tripwire events of the last 10 min with a package. [blocks]: kind='block' events up to now
     * (pkg null = all apps). [knownDomain]/[knownCompany]: seen by that app before the window.
     * [recent]: anomaly keys already written in the last hour.
     */
    fun detect(
        window: List<Ev>, blocks: List<Ev>,
        knownDomain: (pkg: String, domain: String) -> Boolean,
        knownCompany: (pkg: String, company: String) -> Boolean,
        recent: Set<Key>, tz: TimeZone = TimeZone.getDefault(),
    ): List<Found> {
        val out = ArrayList<Found>()
        for ((pkg, evs) in window.filter { it.pkg != null }.groupBy { it.pkg!! }) {
            val dns = evs.filter { it.kind == "dns" }.sortedBy { it.ts }

            val firstOfNew = dns.filter { it.domain != null }.groupBy { it.domain!! }
                .filterKeys { !knownDomain(pkg, it) }.values.map { it.first().id }
            if (firstOfNew.size >= 5) out += Found(pkg, "new_domains_burst", firstOfNew)

            val night = dns.filter { it.fg == 0 && hour(it.ts, tz) < 6 }
            if (night.size >= 3) out += Found(pkg, "night_background", night.map { it.id })

            dns.filter { it.company != null }.groupBy { it.company!! }
                .filterKeys { !knownCompany(pkg, it) }
                .forEach { (company, hits) -> out += Found(pkg, "new_company", hits.map { it.id }, company) }

            val firstBlock = blocks.filter { it.pkg == null || it.pkg == pkg }.minOfOrNull { it.ts }
            if (firstBlock != null) {
                val retries = evs.filter { it.kind == "tripwire" && it.ts > firstBlock }
                if (retries.size >= 2) out += Found(pkg, "tripwire_retry", retries.map { it.id })
            }
        }
        return out.filter { it.key !in recent }.map { it.copy(evidence = it.evidence.take(MAX_EVIDENCE)) }
    }

    private fun hour(ts: Long, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)
}
