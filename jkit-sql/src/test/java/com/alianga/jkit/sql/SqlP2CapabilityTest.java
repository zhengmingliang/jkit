package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.visitor.SqlAstVisitor;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * P2 能力对标：parameterize / export / wall / clone / eval / SqlAstVisitor / replaceColumn。
 *
 * @author 郑明亮
 */
public class SqlP2CapabilityTest {

    @Test
    public void parameterizeReplacesLiterals() {
        String out = SQL.parameterize("SELECT id FROM t WHERE name = 'a' AND age = 1 AND ok = true");
        assertTrue(out, out.contains("name = ?"));
        assertTrue(out, out.contains("age = ?"));
        assertTrue(out, out.contains("ok = ?") || out.contains("ok = ?"));
        assertFalse(out, out.contains("'a'"));
        assertFalse("should not contain bare 1 as literal context only", out.matches("(?s).*\\bage = 1\\b.*"));
    }

    @Test
    public void exportParameterValuesDistinctFromParameters() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t WHERE name = 'alice' AND age = 18 AND id = ?");
        List<Object> values = SQL.exportParameterValues(stmt);
        assertEquals(2, values.size());
        assertEquals("alice", values.get(0));
        assertEquals(Integer.valueOf(18), values.get(1));

        List<String> binds = SQL.parameters(stmt);
        assertEquals(1, binds.size());
        assertEquals("?", binds.get(0));
    }

    @Test
    public void wallDetectsMultiStatementAndDangerousOps() {
        assertTrue(SQL.wall("SELECT 1 FROM t WHERE id = 1").passed());

        SqlWallResult multi = SQL.wall("SELECT 1; DELETE FROM t WHERE id = 1");
        assertFalse(multi.passed());
        assertTrue(multi.violations().toString(), multi.violations().contains("multi-statement"));

        SqlWallResult noWhere = SQL.wall("DELETE FROM t");
        assertTrue(noWhere.violations().toString(), noWhere.violations().contains("delete-without-where"));

        SqlWallResult upd = SQL.wall("UPDATE t SET a = 1");
        assertTrue(upd.violations().toString(), upd.violations().contains("update-without-where"));

        SqlWallResult sleep = SQL.wall("SELECT SLEEP(5) FROM t");
        assertTrue(sleep.violations().toString(), sleep.violations().contains("sleep-function"));

        SqlWallResult always = SQL.wall("SELECT * FROM t WHERE id = 1 OR 1 = 1");
        assertTrue(always.violations().toString(), always.violations().contains("always-true-condition"));

        SqlWallResult comment = SQL.wall("SELECT * FROM t WHERE name = 'x' OR id = 1 --");
        assertTrue(comment.violations().toString(), comment.violations().contains("comment-bypass"));
    }

    @Test
    public void cloneThenAddLimitDoesNotMutateOriginal() {
        SqlStatement original = SQL.parse("SELECT * FROM users WHERE status = 1");
        SqlStatement copy = SQL.clone(original);
        assertNotSame(original, copy);
        assertEquals(SQL.toSqlString(original), SQL.toSqlString(copy));

        SqlStatement limited = SQL.addLimit(original, 50);
        assertNull(((SqlSelect) original).limit());
        assertNotNull(((SqlSelect) limited).limit());
        assertTrue(SQL.toSqlString(limited).toUpperCase().contains("LIMIT"));
    }

    @Test
    public void evalLiteralArithmeticAndCompare() {
        SqlSelect select = (SqlSelect) SQL.parse("SELECT 1 + 2 * 3");
        Object sum = SQL.eval(select.selectItems().get(0).expr());
        assertNotNull(sum);
        assertEquals(0, new BigDecimal("7").compareTo(new BigDecimal(sum.toString())));

        Object eq = SQL.eval(SqlBinaryExpr.of(
                SqlLiteral.of(SqlLiteral.Kind.NUMBER, "1"),
                SqlBinaryOp.EQ,
                SqlLiteral.of(SqlLiteral.Kind.NUMBER, "1")));
        assertEquals(Boolean.TRUE, eq);

        SqlSelect withCol = (SqlSelect) SQL.parse("SELECT a + 1");
        assertNull(SQL.eval(withCol.selectItems().get(0).expr()));
    }

    @Test
    public void sqlAstVisitorTypedDispatch() {
        final List<String> hits = new ArrayList<String>();
        SqlStatement stmt = SQL.parse("SELECT id FROM users WHERE age > 18");
        stmt.accept(new SqlAstVisitor() {
            @Override
            protected boolean visitSelect(SqlSelect node) {
                hits.add("select");
                return true;
            }

            @Override
            protected boolean visitTable(com.alianga.jkit.sql.ast.SqlTable node) {
                hits.add("table:" + node.name().simpleName());
                return true;
            }

            @Override
            protected boolean visitLiteral(SqlLiteral node) {
                hits.add("lit:" + node.value());
                return true;
            }
        });
        assertTrue(hits.toString(), hits.contains("select"));
        assertTrue(hits.toString(), hits.contains("table:users"));
        assertTrue(hits.toString(), hits.contains("lit:18"));
    }

    @Test
    public void replaceColumnSymmetricToReplaceTable() {
        SqlStatement stmt = SQL.parse("SELECT u.name, u.age FROM users u WHERE u.name = 'x'");
        SQL.replaceColumn(stmt, "name", "user_name");
        String sql = SQL.toSqlString(stmt);
        assertTrue(sql, sql.contains("user_name"));
        assertFalse("table name must stay", sql.toLowerCase().contains("from user_name"));
        assertTrue(sql, sql.toLowerCase().contains("from users"));
    }
}
