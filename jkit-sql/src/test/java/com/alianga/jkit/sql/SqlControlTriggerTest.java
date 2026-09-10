package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlControlStatement;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlDeclareStatement;
import com.alianga.jkit.sql.ast.SqlHandlerStatement;
import com.alianga.jkit.sql.ast.SqlTableHandlerStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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


    @Test
    public void triggerUpdateOfColumns() {
        String sql = "CREATE TRIGGER trg_bu BEFORE UPDATE OF a, b ON t FOR EACH ROW "
                + "BEGIN SET NEW.a = 1; END";
        SqlDdlStatement trg = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals("UPDATE", trg.triggerEvent());
        assertEquals(2, trg.triggerUpdateColumns().size());
        assertEquals("a", trg.triggerUpdateColumns().get(0).simpleName());
        assertEquals("b", trg.triggerUpdateColumns().get(1).simpleName());
        String formatted = SQL.toSqlString(trg, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("UPDATE"));
        assertTrue(formatted.toUpperCase().contains("OF"));
    }

    @Test
    public void eventStartsEndsEnableComment() {
        String sql = "CREATE EVENT ev_full ON SCHEDULE EVERY 1 DAY "
                + "STARTS '2026-01-01 00:00:00' ENDS '2026-12-31 23:59:59' "
                + "DISABLE COMMENT 'daily cleanup' "
                + "DO BEGIN DELETE FROM t WHERE id < 0; END";
        SqlDdlStatement ev = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals("EVERY", ev.eventScheduleKind());
        assertNotNull(ev.eventScheduleRaw());
        assertTrue(ev.eventScheduleRaw().toUpperCase().contains("DAY"));
        assertFalse("schedule should not swallow STARTS",
                ev.eventScheduleRaw().toUpperCase().contains("STARTS"));
        assertNotNull(ev.eventStarts());
        assertTrue(ev.eventStarts().contains("2026-01-01"));
        assertNotNull(ev.eventEnds());
        assertTrue(ev.eventEnds().contains("2026-12-31"));
        assertEquals(Boolean.FALSE, ev.eventEnabled());
        assertNotNull(ev.eventComment());
        assertTrue(ev.eventComment().contains("cleanup"));
        String formatted = SQL.toSqlString(ev, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("STARTS"));
        assertTrue(formatted.toUpperCase().contains("DISABLE"));
        assertTrue(formatted.toUpperCase().contains("COMMENT"));
        assertFalse(ev.eventDisableOnSlave());
        assertNull(ev.eventOnCompletion());
    }

    @Test
    public void eventOnCompletionAndDisableOnSlave() {
        String sql = "CREATE EVENT ev_slave ON SCHEDULE EVERY 1 HOUR "
                + "ON COMPLETION PRESERVE DISABLE ON SLAVE "
                + "DO SET @a = 1";
        SqlDdlStatement ev = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals("PRESERVE", ev.eventOnCompletion());
        assertEquals(Boolean.FALSE, ev.eventEnabled());
        assertTrue(ev.eventDisableOnSlave());
        String formatted = SQL.toSqlString(ev, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("ON COMPLETION PRESERVE"));
        assertTrue(formatted.toUpperCase().contains("DISABLE ON SLAVE"));

        SqlDdlStatement ev2 = (SqlDdlStatement) SQL.parse(
                "CREATE EVENT ev2 ON SCHEDULE AT CURRENT_TIMESTAMP "
                + "ON COMPLETION NOT PRESERVE ENABLE DO SELECT 1",
                SqlDialect.MYSQL);
        assertEquals("NOT PRESERVE", ev2.eventOnCompletion());
        assertEquals(Boolean.TRUE, ev2.eventEnabled());
        assertFalse(ev2.eventDisableOnSlave());
    }

    @Test
    public void procedureDeclareHandlerCursor() {
        String sql = "CREATE PROCEDURE sp_decl() BEGIN "
                + "DECLARE x INT DEFAULT 0; "
                + "DECLARE y, z VARCHAR(10); "
                + "DECLARE done INT DEFAULT 0; "
                + "DECLARE cur CURSOR FOR SELECT id FROM t; "
                + "DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1; "
                + "SET x = 1; "
                + "END";
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertTrue(ddl.bodyStatements().size() >= 5);
        boolean sawVar = false;
        boolean sawMulti = false;
        boolean sawCursor = false;
        boolean sawHandler = false;
        for (int i = 0; i < ddl.bodyStatements().size(); i++) {
            Object s = ddl.bodyStatements().get(i);
            if (s instanceof SqlDeclareStatement) {
                SqlDeclareStatement d = (SqlDeclareStatement) s;
                if (d.kind() == SqlDeclareStatement.Kind.VARIABLE && d.defaultValue() != null
                        && "x".equals(d.names().get(0).simpleName())) {
                    sawVar = true;
                    assertNotNull(d.typeRaw());
                    assertTrue(d.typeRaw().toUpperCase().contains("INT"));
                }
                if (d.kind() == SqlDeclareStatement.Kind.VARIABLE && d.names().size() >= 2) {
                    sawMulti = true;
                }
                if (d.kind() == SqlDeclareStatement.Kind.CURSOR) {
                    sawCursor = true;
                    assertEquals("cur", d.names().get(0).simpleName());
                    assertNotNull(d.cursorQuery());
                }
            }
            if (s instanceof SqlHandlerStatement) {
                SqlHandlerStatement h = (SqlHandlerStatement) s;
                sawHandler = true;
                assertEquals("CONTINUE", h.action());
                assertTrue(h.conditions().size() >= 1);
                assertTrue(h.conditions().get(0).toUpperCase().contains("NOT FOUND"));
                assertTrue(h.bodyStatements().size() >= 1);
            }
        }
        assertTrue("expected DECLARE var DEFAULT", sawVar);
        assertTrue("expected multi-var DECLARE", sawMulti);
        assertTrue("expected CURSOR", sawCursor);
        assertTrue("expected HANDLER", sawHandler);
        String formatted = SQL.toSqlString(ddl, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("DECLARE"));
        assertTrue(formatted.toUpperCase().contains("HANDLER"));
        assertTrue(formatted.toUpperCase().contains("CURSOR"));
    }

    @Test
    public void tableHandlerReadWhereLimit() {
        SqlTableHandlerStatement h = (SqlTableHandlerStatement) SQL.parse(
                "HANDLER t READ FIRST WHERE id > 0 LIMIT 10", SqlDialect.MYSQL);
        assertEquals("READ", h.operation());
        assertEquals("FIRST", h.readDirection());
        assertNotNull(h.where());
        assertNotNull(h.limit());
        assertTrue(SQL.tables(h).contains("t"));
        String formatted = SQL.toSqlString(h, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("HANDLER"));
        assertTrue(formatted.toUpperCase().contains("WHERE"));
        assertTrue(formatted.toUpperCase().contains("LIMIT"));
    }

}
