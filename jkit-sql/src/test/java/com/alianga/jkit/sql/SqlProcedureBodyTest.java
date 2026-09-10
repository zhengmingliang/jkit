package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatementType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * CREATE PROCEDURE 参数与 BEGIN 体语句列表。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlProcedureBodyTest {

    @Test
    public void procedureParamsAndBodyStatements() {
        String sql = "CREATE PROCEDURE sp_demo(IN a INT, OUT b INT) "
                + "BEGIN "
                + "SELECT a; "
                + "INSERT INTO t VALUES (a); "
                + "SET b = a; "
                + "END";
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(sql, SqlDialect.MYSQL);
        assertEquals("PROCEDURE", ddl.objectType());
        assertEquals(2, ddl.parameters().size());
        assertEquals("IN", ddl.parameters().get(0).mode());
        assertEquals("a", ddl.parameters().get(0).name().simpleName());
        assertTrue(ddl.parameters().get(0).typeRaw().toUpperCase().contains("INT"));
        assertEquals("OUT", ddl.parameters().get(1).mode());
        assertEquals("b", ddl.parameters().get(1).name().simpleName());

        assertTrue(ddl.bodyStatements().size() >= 2);
        assertTrue(ddl.bodyStatements().get(0) instanceof SqlSelect
                || ddl.bodyStatements().get(0).type() == SqlStatementType.SELECT);
        boolean sawInsert = false;
        boolean sawSet = false;
        for (int i = 0; i < ddl.bodyStatements().size(); i++) {
            if (ddl.bodyStatements().get(i) instanceof SqlInsert) {
                sawInsert = true;
            }
            if (ddl.bodyStatements().get(i).type() == SqlStatementType.SET
                    || (ddl.bodyStatements().get(i).toString() != null
                    && ddl.bodyStatements().get(i).toString().toUpperCase().contains("SET"))) {
                sawSet = true;
            }
        }
        assertTrue("expected INSERT in body", sawInsert);
        assertTrue("expected SET in body", sawSet);
        assertNotNull(ddl.bodyRaw());
        assertTrue(ddl.tail().contains("BEGIN"));

        String formatted = SQL.toSqlString(ddl, SqlDialect.MYSQL);
        assertTrue(formatted.toUpperCase().contains("PROCEDURE"));
        assertTrue(formatted.toUpperCase().contains("BEGIN"));
        assertTrue(formatted.toUpperCase().contains("END"));
    }

    @Test
    public void legacySingleSelectProcedureStillWorks() {
        SqlDdlStatement proc = (SqlDdlStatement) SQL.parse(
                "CREATE PROCEDURE sp_add(IN a INT) BEGIN SELECT a; END");
        assertEquals(1, proc.parameters().size());
        assertEquals("IN", proc.parameters().get(0).mode());
        assertEquals(1, proc.bodyStatements().size());
        assertTrue(proc.bodyStatements().get(0) instanceof SqlSelect);
    }
}
