package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlAllColumns;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link SQL#replaceSelectItem} / {@link SQL#expandStar}：列级脱敏与星号展开。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class SqlSelectListRewriterTest {

    private static String sql(SqlStatement stmt) {
        return SQL.toSqlString(stmt, SqlDialect.MYSQL);
    }

    private static Map<String, List<String>> customerCols() {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("t_customer", Arrays.asList("id", "name", "phone", "id_card"));
        return m;
    }

    @Test
    public void replaceKeepsOutputColumnName() {
        SqlStatement out = SQL.replaceSelectItem(
                SQL.parse("SELECT id, phone, name FROM t_customer"),
                "phone", "CONCAT(LEFT(phone, 3), '****')");
        String n = sql(out);
        assertTrue(n, n.contains("CONCAT(LEFT(phone, 3), '****') AS phone"));
        assertTrue(n, n.contains("name"));
        SQL.parse(n);
    }

    @Test
    public void replaceMatchesAlias() {
        SqlStatement out = SQL.replaceSelectItem(
                SQL.parse("SELECT phone AS mobile FROM t_customer"),
                "mobile", "REPEAT('*', 11)");
        assertTrue(sql(out), sql(out).contains("REPEAT('*', 11) AS mobile"));
    }

    @Test
    public void replaceQualifiedName() {
        SqlStatement out = SQL.replaceSelectItem(
                SQL.parse("SELECT c.phone, c.name FROM t_customer c"),
                "c.phone", "LEFT(c.phone, 3)");
        String n = sql(out);
        assertTrue(n, n.contains("LEFT(c.phone, 3) AS phone"));
        assertTrue(n, n.contains("c.name"));
    }

    @Test
    public void replaceDoesNotTouchUnqualifiedWhenQualifiedRequested() {
        SqlStatement out = SQL.replaceSelectItem(
                SQL.parse("SELECT phone FROM t_customer"),
                "c.phone", "LEFT(phone, 3)");
        assertFalse(sql(out), sql(out).contains("LEFT"));
    }

    @Test
    public void replaceAllOccurrencesIncludingUnionAndSubquery() {
        SqlStatement out = SQL.replaceSelectItem(
                SQL.parse("SELECT phone FROM t_customer UNION SELECT phone FROM t_backup"),
                "phone", "'****'");
        String n = sql(out);
        assertEquals(n, 2, countOf(n, "'****'"));
        SqlStatement nested = SQL.replaceSelectItem(
                SQL.parse("SELECT phone FROM (SELECT phone FROM t_customer) x"),
                "phone", "'x'");
        assertEquals(sql(nested), 2, countOf(sql(nested), "'x'"));
    }

    @Test
    public void replaceExplicitAliasEmptyDropsAlias() {
        SqlStatement out = SQL.replaceSelectItem(
                SQL.parse("SELECT phone FROM t_customer"),
                "phone", SQL.parseExpr("'hidden'"), "");
        assertFalse(sql(out), sql(out).contains(" AS "));
    }

    @Test
    public void replaceClonesOriginal() {
        SqlStatement orig = SQL.parse("SELECT id, phone FROM t_customer");
        String before = sql(orig);
        SqlStatement out = SQL.replaceSelectItem(orig, "phone", "'****'");
        assertNotSame(orig, out);
        assertEquals(before, sql(orig));
        assertTrue(sql(out).contains("'****'"));
    }

    @Test
    public void expandStarSingleTableUnqualified() {
        SqlStatement out = SQL.expandStar(SQL.parse("SELECT * FROM t_customer"), customerCols());
        String n = sql(out);
        assertFalse(hasStar((SqlSelect) out));
        assertTrue(n, n.contains("id"));
        assertTrue(n, n.contains("phone"));
        assertTrue(n, n.contains("id_card"));
        SQL.parse(n);
    }

    @Test
    public void expandQualifiedStar() {
        SqlStatement out = SQL.expandStar(
                SQL.parse("SELECT c.* FROM t_customer c JOIN t_order o ON c.id = o.cid"),
                customerCols());
        String n = sql(out);
        assertTrue(n, n.contains("c.id"));
        assertTrue(n, n.contains("c.phone"));
        assertFalse(n, n.contains("c.*") || n.trim().equals("*"));
        assertFalse("order table not in map and not requested", n.contains("o.id"));
    }

    @Test
    public void expandBareStarJoinQualifiesEachTable() {
        Map<String, List<String>> cols = new LinkedHashMap<String, List<String>>();
        cols.put("t_customer", Arrays.asList("id", "phone"));
        cols.put("t_order", Arrays.asList("id", "amt"));
        SqlStatement out = SQL.expandStar(
                SQL.parse("SELECT * FROM t_customer c JOIN t_order o ON c.id = o.cid"), cols);
        String n = sql(out);
        assertTrue(n, n.contains("c.id"));
        assertTrue(n, n.contains("c.phone"));
        assertTrue(n, n.contains("o.id"));
        assertTrue(n, n.contains("o.amt"));
        assertFalse(n, n.contains("*"));
    }

    @Test
    public void unknownTableLeavesStar() {
        SqlStatement out = SQL.expandStar(SQL.parse("SELECT * FROM t_unknown"), customerCols());
        assertTrue(sql(out), sql(out).contains("*"));
    }

    @Test
    public void expandThenReplaceMasksPhone() {
        SqlStatement expanded = SQL.expandStar(
                SQL.parse("SELECT * FROM t_customer"), customerCols());
        SqlStatement masked = SQL.replaceSelectItem(expanded, "phone",
                "CONCAT(LEFT(phone, 3), '****')");
        String n = sql(masked);
        assertTrue(n, n.contains("CONCAT(LEFT(phone, 3), '****') AS phone"));
        assertTrue(n, n.contains("id_card"));
        assertFalse(hasStar((SqlSelect) masked));
    }

    @Test
    public void expandThenRemoveSelectItem() {
        SqlStatement expanded = SQL.expandStar(
                SQL.parse("SELECT * FROM t_customer"), customerCols());
        SqlStatement cut = SQL.removeSelectItem(expanded, "id_card");
        String n = sql(cut).toUpperCase();
        assertFalse(n, n.contains("ID_CARD"));
        assertTrue(n, n.contains("PHONE"));
    }

    @Test
    public void subqueryStarUsesInnerProjection() {
        SqlStatement out = SQL.expandStar(
                SQL.parse("SELECT * FROM (SELECT id, name FROM t_customer) x"), customerCols());
        String n = sql(out);
        assertTrue(n, n.contains("x.id"));
        assertTrue(n, n.contains("x.name"));
        assertFalse(n, n.contains("phone"));
        assertFalse(hasStar((SqlSelect) out));
    }

    @Test
    public void nestedStarExpandsInnerFirst() {
        SqlStatement out = SQL.expandStar(
                SQL.parse("SELECT * FROM (SELECT * FROM t_customer) x"), customerCols());
        String n = sql(out);
        assertTrue(n, n.contains("x.id"));
        assertTrue(n, n.contains("x.phone"));
        assertFalse(hasStar((SqlSelect) out));
    }

    @Test
    public void rewriteChainExpandThenReplace() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t_customer");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.expandStar(customerCols()))
                .add(SqlRewrites.replaceSelectItem("phone",
                        SQL.parseExpr("CONCAT(LEFT(phone, 3), '****')"))));
        String n = sql(out);
        assertTrue(n, n.contains("CONCAT"));
        assertTrue(n, n.contains("AS phone"));
        assertFalse(sql(stmt).contains("CONCAT"));
    }

    @Test
    public void mixedListExpandsOnlyStar() {
        SqlStatement out = SQL.expandStar(
                SQL.parse("SELECT id, * FROM t_customer"), customerCols());
        SqlSelect select = (SqlSelect) out;
        assertEquals(5, select.selectItems().size());
        assertEquals("id", ((SqlIdentifier) select.selectItems().get(0).expr()).simpleName());
        assertFalse(hasStar(select));
    }

    @Test
    public void customResolver() {
        SqlColumnResolver resolver = new SqlColumnResolver() {
            /**
             * {@inheritDoc}
             */
            @Override
            public List<String> columnsOf(String tableSimpleName) {
                if ("t_customer".equalsIgnoreCase(tableSimpleName)) {
                    return Arrays.asList("id", "secret");
                }
                return null;
            }
        };
        SqlStatement out = SQL.expandStar(SQL.parse("SELECT * FROM t_customer"), resolver);
        assertTrue(sql(out).contains("secret"));
        assertEquals(2, ((SqlSelect) out).selectItems().size());
    }

    @Test
    public void nullsAreNoops() {
        SqlStatement stmt = SQL.parse("SELECT phone FROM t");
        assertSame(stmt, SQL.replaceSelectItem(stmt, "phone", (String) null));
        assertEquals(null, SQL.expandStar(null, customerCols()));
        assertEquals(sql(stmt), sql(SQL.replaceSelectItem(stmt, "", "'x'")));
    }

    private static boolean hasStar(SqlSelect select) {
        for (int i = 0; i < select.selectItems().size(); i++) {
            if (select.selectItems().get(i).expr() instanceof SqlAllColumns) {
                return true;
            }
        }
        return false;
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }
}
