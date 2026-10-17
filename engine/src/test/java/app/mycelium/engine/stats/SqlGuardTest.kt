package app.mycelium.engine.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SqlGuardTest {
    @Test fun allowsSelectAndWith() {
        assertNull(SqlGuard.check("SELECT * FROM event"))
        assertNull(SqlGuard.check("  select package, count(*) from event group by package"))
        assertNull(SqlGuard.check("WITH x AS (SELECT 1 a) SELECT a FROM x"))
        assertNull(SqlGuard.check("SELECT * FROM event WHERE note = 'delete me, update, pragma'"))   // keywords inside literals are fine
        assertNull(SqlGuard.check("SELECT * FROM event -- just a comment"))
    }

    @Test fun rejectsWritesAndSchemaChanges() {
        for (sql in listOf(
            "DELETE FROM event", "INSERT INTO rule VALUES('domain','x','*',0)", "UPDATE app SET watched=1",
            "DROP TABLE event", "CREATE TABLE x(a)", "ALTER TABLE event ADD COLUMN x", "REPLACE INTO rule VALUES(1,2,3,4)",
            "VACUUM", "BEGIN",
        )) assertEquals(sql, "only_select", SqlGuard.check(sql))
        assertEquals("forbidden_keyword", SqlGuard.check("WITH x AS (SELECT 1) DELETE FROM event"))
        assertEquals("forbidden_keyword", SqlGuard.check("WITH x AS (SELECT 1) INSERT INTO rule SELECT * FROM x"))
        assertEquals("forbidden_keyword", SqlGuard.check("SELECT load_extension('evil')"))
    }

    @Test fun rejectsSemicolonsPragmaAttach() {
        assertEquals("semicolon_not_allowed", SqlGuard.check("SELECT 1; DROP TABLE event"))
        assertEquals("semicolon_not_allowed", SqlGuard.check("SELECT 1;"))
        assertEquals("only_select", SqlGuard.check("PRAGMA table_info(event)"))
        assertEquals("only_select", SqlGuard.check("ATTACH DATABASE '/sdcard/x.db' AS x"))
        assertEquals("only_select", SqlGuard.check("/* hi */ PRAGMA journal_mode"))
        assertEquals("forbidden_keyword", SqlGuard.check("SELECT * FROM pragma_table_info('event') WHERE 1 AND pragma"))
        assertEquals("empty_query", SqlGuard.check("   "))
    }
}
