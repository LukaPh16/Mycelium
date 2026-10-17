package app.mycelium.engine.stats

/** Gate for call("sql"): one SELECT/WITH statement, no ';', no PRAGMA/ATTACH or write keywords. */
object SqlGuard {
    const val MAX_ROWS = 500
    const val TIMEOUT_MS = 2000L

    private val literals = Regex("'(?:[^']|'')*'")
    private val comments = Regex("--[^\n]*|/\\*.*?(\\*/|$)", RegexOption.DOT_MATCHES_ALL)
    private val forbidden = Regex(
        "\\b(PRAGMA|ATTACH|DETACH|INSERT|UPDATE|DELETE|REPLACE|UPSERT|CREATE|DROP|ALTER|VACUUM|REINDEX|ANALYZE|SAVEPOINT|RELEASE|BEGIN|COMMIT|ROLLBACK|LOAD_EXTENSION)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Null if allowed, else an error code. */
    fun check(sql: String): String? {
        if (sql.isBlank()) return "empty_query"
        if (';' in sql) return "semicolon_not_allowed"
        val code = sql.replace(literals, "''").replace(comments, " ").trim()
        val first = code.takeWhile { it.isLetter() }.uppercase()
        if (first != "SELECT" && first != "WITH") return "only_select"
        if (forbidden.containsMatchIn(code)) return "forbidden_keyword"
        return null
    }
}
