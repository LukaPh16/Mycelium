package app.mycelium.engine.classify

/** Block rules. scope domain (matches subdomains too) | company. package "*" = every app. */
class RuleSet(val rules: List<Rule>) {
    data class Rule(val scope: String, val value: String, val pkg: String = "*", val ts: Long = 0)

    private val domains = HashMap<String, MutableSet<String>>()
    private val companies = HashMap<String, MutableSet<String>>()

    init {
        for (r in rules) {
            val m = if (r.scope == SCOPE_DOMAIN) domains else companies
            m.getOrPut(key(r.scope, r.value)) { HashSet() } += r.pkg
        }
    }

    /**
     * The rule blocking this lookup, or null. Per-app rules only apply when the package came
     * from a uid lookup (uid_dns / uid_tcp), never from a foreground guess.
     */
    fun blocking(domain: String?, company: String?, pkg: String?, how: String?): Rule? {
        val perApp = pkg != null && (how == "uid_dns" || how == "uid_tcp")
        fun hit(pkgs: Set<String>?) = pkgs != null && ("*" in pkgs || (perApp && pkg in pkgs))
        if (domain != null) for (s in suffixes(domain)) {
            if (hit(domains[s])) return rules.first { it.scope == SCOPE_DOMAIN && key(it.scope, it.value) == s && (it.pkg == "*" || it.pkg == pkg) }
        }
        if (company != null) {
            val k = key(SCOPE_COMPANY, company)
            if (hit(companies[k])) return rules.first { it.scope == SCOPE_COMPANY && key(it.scope, it.value) == k && (it.pkg == "*" || it.pkg == pkg) }
        }
        return null
    }

    companion object {
        const val SCOPE_DOMAIN = "domain"
        const val SCOPE_COMPANY = "company"

        fun key(scope: String, value: String) = if (scope == SCOPE_DOMAIN) normalizeDomain(value) else value.trim().lowercase()
    }
}
