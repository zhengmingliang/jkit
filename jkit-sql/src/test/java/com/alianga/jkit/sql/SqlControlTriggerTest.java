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
}
