package app.mycelium.engine.db

/** Splits schema SQL into statements: strips `--` comments, splits on `;` outside quotes. */
object SchemaSql {
    fun split(sql: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                !inQuote && c == '-' && i + 1 < sql.length && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                    continue
                }
                c == '\'' -> { inQuote = !inQuote; cur.append(c) }
                !inQuote && c == ';' -> {
                    cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        check(!inQuote) { "unterminated quote in schema" }
        check(cur.isBlank()) { "trailing statement without ';'" }
        return out
    }
}
