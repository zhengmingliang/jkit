package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlControlStatement;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 过程控制流 / FUNCTION RETURNS / TRIGGER 结构化。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlControlTriggerTest {

    @Test
    public void functionReturnsType() {
        SqlDdlStatement fn = (SqlDdlStatement) SQL.parse(
                "CREATE FUNCTION fn_one() RETURNS INT RETURN 1", SqlDialect.MYSQL);
        assertEquals("FUNCTION", fn.objectType());
        assertNotNull(fn.returnsType());
        assertTrue(fn.returnsType().toUpperCase().contains("INT"));
        assertTrue(fn.bodyStatements().size() >= 1);
        String formatted = SQL.toSqlString(fn, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("RETURNS"));
        assertTrue(formatted.toUpperCase().contains("INT"));
    }

    @Test
    public void procedureIfWhileStructured() {
        String sql = "CREATE PROCEDURE sp_ctrl(IN a INT) BEGIN "
                + "IF a > 0 THEN SET a = a - 1; ELSE SET a = 0; END IF; "
                + "WHILE a > 0 DO SET a = a - 1; END WHILE; "
                + "END";
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertTrue(ddl.bodyStatements().size() >= 2);
        boolean sawIf = false;
        boolean sawWhile = false;
        for (int i = 0; i < ddl.bodyStatements().size(); i++) {
            if (ddl.bodyStatements().get(i) instanceof SqlControlStatement) {
                SqlControlStatement c = (SqlControlStatement) ddl.bodyStatements().get(i);
                if (c.kind() == SqlControlStatement.Kind.IF) {
                    sawIf = true;
                    assertNotNull(c.condition());
                    assertTrue(c.bodyStatements().size() >= 1);
                    assertTrue(c.elseStatements().size() >= 1);
                }
                if (c.kind() == SqlControlStatement.Kind.WHILE) {
                    sawWhile = true;
                    assertNotNull(c.condition());
                    assertTrue(c.bodyStatements().size() >= 1);
                }
            }
        }
        assertTrue("expected IF", sawIf);
        assertTrue("expected WHILE", sawWhile);
        String formatted = SQL.toSqlString(ddl, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("IF"));
        assertTrue(formatted.toUpperCase().contains("WHILE"));
        assertTrue(formatted.toUpperCase().contains("END"));
    }

    @Test
    public void triggerTimingEventTableAndBody() {
        String sql = "CREATE TRIGGER trg_bi BEFORE INSERT ON t FOR EACH ROW "
                + "BEGIN SET NEW.id = 1; END";
        SqlDdlStatement trg = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals("TRIGGER", trg.objectType());
        assertEquals("BEFORE", trg.triggerTiming());
        assertEquals("INSERT", trg.triggerEvent());
        assertNotNull(trg.triggerTable());
        assertEquals("t", trg.triggerTable().simpleName());
        assertTrue(trg.bodyStatements().size() >= 1);
        assertEquals(SqlStatementType.SET, trg.bodyStatements().get(0).type());
        String formatted = SQL.toSqlString(trg, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("TRIGGER"));
        assertTrue(formatted.toUpperCase().contains("BEFORE"));
        assertTrue(formatted.toUpperCase().contains("INSERT"));
    }

    @Test
    public void legacySingleSetTriggerStillWorks() {
        SqlDdlStatement trg = (SqlDdlStatement) SQL.parse(
                "CREATE TRIGGER trg_bi BEFORE INSERT ON t FOR EACH ROW SET NEW.id = 1",
                SqlDialect.MYSQL);
        assertEquals("TRIGGER", trg.objectType());
        assertEquals("BEFORE", trg.triggerTiming());
        assertEquals("INSERT", trg.triggerEvent());
        assertNotNull(trg.triggerTable());
        assertTrue(trg.bodyStatements().size() >= 1);
    }

    @Test
    public void caseLeaveIterateReturnAndLabel() {
        String sql = "CREATE PROCEDURE sp_case(IN v INT) BEGIN "
                + "lab: LOOP "
                + "CASE v WHEN 1 THEN SET v = 2; WHEN 2 THEN LEAVE lab; ELSE ITERATE lab; END CASE; "
                + "END LOOP lab; "
                + "RETURN v; "
                + "END";
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertTrue(ddl.bodyStatements().size() >= 2);
        boolean sawLoop = false;
        boolean sawCase = false;
        boolean sawReturn = false;
        for (int i = 0; i < ddl.bodyStatements().size(); i++) {
            if (!(ddl.bodyStatements().get(i) instanceof SqlControlStatement)) {
                continue;
            }
            SqlControlStatement c = (SqlControlStatement) ddl.bodyStatements().get(i);
            if (c.kind() == SqlControlStatement.Kind.LOOP) {
                sawLoop = true;
                assertEquals("lab", c.label());
                assertTrue(c.bodyStatements().size() >= 1);
                SqlControlStatement cas = (SqlControlStatement) c.bodyStatements().get(0);
                assertEquals(SqlControlStatement.Kind.CASE, cas.kind());
                sawCase = true;
                assertNotNull(cas.condition());
                assertTrue(cas.elseIfs().size() >= 2);
                assertTrue(cas.elseStatements().size() >= 1);
                boolean sawLeave = false;
                boolean sawIterate = false;
                SqlControlStatement w1 = cas.elseIfs().get(1);
                assertTrue(w1.bodyStatements().size() >= 1);
                SqlControlStatement leave = (SqlControlStatement) w1.bodyStatements().get(0);
                assertEquals(SqlControlStatement.Kind.LEAVE, leave.kind());
                assertEquals("lab", leave.label());
                sawLeave = true;
                SqlControlStatement it = (SqlControlStatement) cas.elseStatements().get(0);
                assertEquals(SqlControlStatement.Kind.ITERATE, it.kind());
                assertEquals("lab", it.label());
                sawIterate = true;
                assertTrue(sawLeave && sawIterate);
            }
            if (c.kind() == SqlControlStatement.Kind.RETURN) {
                sawReturn = true;
                assertNotNull(c.condition());
            }
        }
        assertTrue("expected labeled LOOP", sawLoop);
        assertTrue("expected CASE", sawCase);
        assertTrue("expected RETURN", sawReturn);
        String formatted = SQL.toSqlString(ddl, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("CASE"));
        assertTrue(formatted.toUpperCase().contains("LEAVE"));
        assertTrue(formatted.toUpperCase().contains("RETURN"));
    }

    @Test
    public void triggerForEachFollows() {
        String sql = "CREATE TRIGGER trg_ai AFTER INSERT ON t FOR EACH ROW "
                + "FOLLOWS trg_bi BEGIN SET NEW.id = 1; END";
        SqlDdlStatement trg = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals("ROW", trg.triggerForEach());
        assertEquals("FOLLOWS", trg.triggerOrder());
        assertNotNull(trg.triggerOther());
        assertEquals("trg_bi", trg.triggerOther().simpleName());
        String formatted = SQL.toSqlString(trg, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("FOR EACH ROW"));
        assertTrue(formatted.toUpperCase().contains("FOLLOWS"));
    }

    @Test
    public void eventOnScheduleEvery() {
        String sql = "CREATE EVENT ev_hourly ON SCHEDULE EVERY 1 HOUR "
                + "DO BEGIN DELETE FROM t WHERE id < 0; END";
        SqlDdlStatement ev = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals("EVENT", ev.objectType());
        assertEquals("EVERY", ev.eventScheduleKind());
        assertNotNull(ev.eventScheduleRaw());
        assertTrue(ev.eventScheduleRaw().toUpperCase().contains("HOUR"));
        assertTrue(ev.bodyStatements().size() >= 1);
        String formatted = SQL.toSqlString(ev, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("SCHEDULE"));
        assertTrue(formatted.toUpperCase().contains("EVERY"));
    }

    @Test
    public void eventOnScheduleAt() {
        SqlDdlStatement ev = (SqlDdlStatement) SQL.parse(
                "CREATE EVENT ev_once ON SCHEDULE AT '2026-01-01 00:00:00' DO SET @a = 1",
                SqlDialect.MYSQL);
        assertEquals("AT", ev.eventScheduleKind());
        assertNotNull(ev.eventScheduleRaw());
        assertTrue(ev.eventScheduleRaw().contains("2026"));
        assertTrue(ev.bodyStatements().size() >= 1);
    }
}
