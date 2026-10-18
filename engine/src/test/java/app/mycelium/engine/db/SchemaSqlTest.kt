package app.mycelium.engine.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SchemaSqlTest {
    private val sql = File(System.getProperty("schemaPath")!!).readText()

    @Test fun schemaParsesIntoStatements() {
        val stmts = SchemaSql.split(sql)
        val names = stmts.map { Regex("""^CREATE\s+(TABLE|INDEX|VIEW)\s+(\w+)""").find(it)!!.groupValues[2] }
        assertEquals(
            listOf("app", "event", "ix_event_ts", "ix_event_pkg", "rule", "traffic", "chat", "queue",
                "organism", "session", "ix_session_ts", "v_recent", "v_parasites", "v_night"),
            names,
        )
        stmts.forEach { assertTrue(it, !it.contains("--") && !it.endsWith(";")) }
    }

    @Test fun quotedSemicolonAndDashesStayInsideStatement() {
        assertEquals(listOf("SELECT 'a;--b'", "SELECT 1"), SchemaSql.split("SELECT 'a;--b'; -- c\nSELECT 1;"))
    }
}
