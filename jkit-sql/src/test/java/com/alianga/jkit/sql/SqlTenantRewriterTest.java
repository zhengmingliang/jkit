package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlUpdate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SQL#injectTenant}：按表白名单注入，下钻 UNION / 子查询 / CTE，INSERT 补列。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class SqlTenantRewriterTest {

    private static String sql(SqlStatement stmt) {
        return SQL.toSqlString(stmt, SqlDialect.MYSQL);
    }

    private static String norm(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static SqlStatement inject(String source, Object value, String... tables) {
        return SQL.injectTenant(SQL.parse(source), "tenant_id", value, tables);
    }

    private static void assertValid(SqlStatement stmt, SqlDialect dialect) {
        SQL.parse(SQL.toSqlString(stmt, dialect), dialect);
    }

    @Test
    public void simpleSelectQualifiesTable() {
        SqlStatement out = inject("SELECT id FROM t_order WHERE status = 1", 100, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("t_order.tenant_id = 100"));
        assertTrue(n, n.contains("status = 1"));
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void aliasIsPreferredQualifier() {
        SqlStatement out = inject("SELECT id FROM t_order o WHERE status = 1", 100, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("o.tenant_id = 100"));
        assertFalse(n, n.contains("t_order.tenant_id"));
    }

    @Test
    public void joinInjectsEachWhitelistedTable() {
        SqlStatement out = inject(
                "SELECT o.id FROM t_order o JOIN t_item i ON o.id = i.oid", 7, "t_order", "t_item");
        String n = norm(sql(out));
        assertTrue(n, n.contains("o.tenant_id = 7"));
        assertTrue(n, n.contains("i.tenant_id = 7"));
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void whitelistSkipsOtherJoinTable() {
        SqlStatement out = inject(
                "SELECT o.id FROM t_order o JOIN t_item i ON o.id = i.oid", 7, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("o.tenant_id = 7"));
        assertFalse(n, n.contains("i.tenant_id"));
    }

    @Test
    public void emptyWhitelistInjectsAllPhysicalTables() {
        SqlStatement out = inject("SELECT o.id FROM t_order o JOIN t_item i ON o.id = i.oid", 1);
        String n = norm(sql(out));
        assertTrue(n, n.contains("o.tenant_id = 1"));
        assertTrue(n, n.contains("i.tenant_id = 1"));
    }

    @Test
    public void unionInjectsBothArms() {
        SqlStatement out = inject(
                "SELECT id FROM t_order UNION ALL SELECT id FROM t_order_archive", 9, "t_order",
                "t_order_archive");
        String n = norm(sql(out));
        assertTrue(n, n.contains("t_order.tenant_id = 9"));
        assertTrue(n, n.contains("t_order_archive.tenant_id = 9"));
        int first = n.indexOf("tenant_id = 9");
        int second = n.indexOf("tenant_id = 9", first + 1);
        assertTrue(n, second > first);
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void subqueryFromIsInjectedInside() {
        SqlStatement out = inject(
                "SELECT id FROM (SELECT id FROM t_order WHERE status = 1) x", 3, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("t_order.tenant_id = 3"));
        assertFalse("outer alias x is not a physical table", n.contains("x.tenant_id"));
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void nestedSubqueryEachLevel() {
        SqlStatement out = inject(
                "SELECT * FROM (SELECT * FROM (SELECT id FROM t_order) a) b", 4, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("t_order.tenant_id = 4"));
        assertEquals(n, 1, countOf(n, "tenant_id = 4"));
    }

    @Test
    public void existsSubqueryIsInjected() {
        SqlStatement out = inject(
                "SELECT id FROM t_user u WHERE EXISTS (SELECT 1 FROM t_order o WHERE o.uid = u.id)",
                5, "t_order", "t_user");
        String n = norm(sql(out));
        assertTrue(n, n.contains("u.tenant_id = 5"));
        assertTrue(n, n.contains("o.tenant_id = 5"));
    }

    @Test
    public void inSubqueryIsInjected() {
        SqlStatement out = inject(
                "SELECT id FROM t_user WHERE id IN (SELECT uid FROM t_order)", 6, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("t_order.tenant_id = 6"));
        assertFalse(n, n.contains("t_user.tenant_id"));
    }

    @Test
    public void cteInjectsBodyNotCteName() {
        SqlStatement out = inject(
                "WITH w AS (SELECT id FROM t_order) SELECT id FROM w", 8, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("t_order.tenant_id = 8"));
        assertFalse("CTE name is not a physical table", n.contains("w.tenant_id"));
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void ctePlusOuterPhysicalTable() {
        SqlStatement out = inject(
                "WITH w AS (SELECT id FROM t_order) SELECT w.id FROM w JOIN t_item i ON w.id = i.oid",
                2, "t_order", "t_item");
        String n = norm(sql(out));
        assertTrue(n, n.contains("t_order.tenant_id = 2"));
        assertTrue(n, n.contains("i.tenant_id = 2"));
        assertFalse(n, n.contains("w.tenant_id"));
    }

    @Test
    public void dualIsSkipped() {
        SqlStatement out = SQL.injectTenant(
                SQL.parse("SELECT 1 FROM dual", SqlDialect.ORACLE), "tenant_id", 1);
        String n = norm(SQL.toSqlString(out, SqlDialect.ORACLE));
        assertFalse(n, n.contains("tenant_id"));
    }

    @Test
    public void selectWithoutFromUnchanged() {
        SqlStatement out = inject("SELECT 1", 1, "t_order");
        assertFalse(sql(out).contains("tenant_id"));
    }

    @Test
    public void updateAndDelete() {
        SqlStatement upd = inject("UPDATE t_order SET status = 2 WHERE id = 1", 11, "t_order");
        assertTrue(sql(upd), sql(upd).contains("t_order.tenant_id = 11"));
        SqlStatement del = inject("DELETE FROM t_order WHERE id = 1", 11, "t_order");
        assertTrue(sql(del), sql(del).contains("t_order.tenant_id = 11"));
        assertValid(upd, SqlDialect.MYSQL);
        assertValid(del, SqlDialect.MYSQL);
    }

    @Test
    public void updateJoinInjectsBoth() {
        SqlStatement out = inject(
                "UPDATE t_order o JOIN t_item i ON o.id = i.oid SET i.qty = 1", 12, "t_order", "t_item");
        String n = norm(sql(out));
        assertTrue(n, n.contains("o.tenant_id = 12"));
        assertTrue(n, n.contains("i.tenant_id = 12"));
        assertTrue(((SqlUpdate) out).where() != null);
    }

    @Test
    public void insertValuesAppendsColumn() {
        SqlStatement out = inject("INSERT INTO t_order (id, name) VALUES (1, 'a'), (2, 'b')", 100,
                "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("tenant_id"));
        assertTrue(n, n.contains("100"));
        SqlInsert ins = (SqlInsert) out;
        assertEquals(3, ins.columns().size());
        assertEquals(3, ins.valuesList().get(0).size());
        assertEquals(3, ins.valuesList().get(1).size());
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void insertValuesReplacesExistingTenantColumn() {
        SqlStatement out = inject("INSERT INTO t_order (id, tenant_id) VALUES (1, 0)", 99, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("99"));
        assertFalse(n, n.contains("0") && n.contains("tenant_id") && n.matches(".*VALUES \\(1, 0\\).*"));
        SqlInsert ins = (SqlInsert) out;
        assertEquals(2, ins.columns().size());
        assertEquals("99", ins.valuesList().get(0).get(1).toString());
    }

    @Test
    public void insertSetAddsAssignment() {
        SqlStatement out = inject("INSERT INTO t_order SET id = 1, name = 'a'", 5, "t_order");
        String n = norm(sql(out));
        assertTrue(n, n.contains("tenant_id = 5"));
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void insertSelectAddsColumnAndWhere() {
        SqlStatement out = inject(
                "INSERT INTO t_order (id, name) SELECT id, name FROM staging", 77, "t_order", "staging");
        String n = norm(sql(out));
        assertTrue(n, n.contains("tenant_id"));
        assertTrue(n, n.contains("77"));
        assertTrue(n, n.contains("staging.tenant_id = 77"));
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void insertSelectUnionAddsValueToBothArms() {
        SqlStatement out = inject(
                "INSERT INTO t_order (id) SELECT id FROM a UNION SELECT id FROM b", 3, "t_order", "a",
                "b");
        String n = norm(sql(out));
        assertTrue(n, n.contains("SELECT id, 3 FROM a"));
        assertTrue(n, n.contains("SELECT id, 3 FROM b"));
        assertTrue(n, n.contains("a.tenant_id = 3"));
        assertTrue(n, n.contains("b.tenant_id = 3"));
        assertEquals(1, countOf(n.toLowerCase(), "tenant_id)"));
    }

    @Test
    public void insertWithoutColumnListDoesNotGuessPositions() {
        SqlStatement out = inject("INSERT INTO t_order VALUES (1, 'a')", 1, "t_order");
        String n = norm(sql(out));
        assertFalse("cannot safely append without a column list", n.contains("tenant_id"));
    }

    @Test
    public void insertTargetNotInWhitelistLeavesColumns() {
        SqlStatement out = inject("INSERT INTO t_order (id) VALUES (1)", 1, "t_other");
        assertFalse(sql(out).contains("tenant_id"));
    }

    @Test
    public void mergeAddsOnPredicates() {
        SqlStatement out = inject(
                "MERGE INTO t_order o USING staging s ON o.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET o.name = s.name "
                        + "WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)",
                4, "t_order", "staging");
        String n = norm(sql(out)).toLowerCase();
        assertTrue(n, n.contains("o.tenant_id = 4"));
        assertTrue(n, n.contains("s.tenant_id = 4"));
        SqlMerge merge = (SqlMerge) out;
        assertTrue(merge.whens().get(1).insert().columns().size() >= 3);
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void cloneDoesNotMutateOriginal() {
        SqlStatement orig = SQL.parse("SELECT id FROM t_order WHERE status = 1");
        String before = sql(orig);
        SqlStatement out = SQL.injectTenant(orig, "tenant_id", 1, "t_order");
        assertNotSame(orig, out);
        assertEquals(before, sql(orig));
        assertTrue(sql(out).contains("tenant_id"));
    }

    @Test
    public void stringValueIsQuotedNotParsed() {
        SqlStatement out = inject("SELECT id FROM t_order", "'; DROP TABLE t_order; --", "t_order");
        String n = sql(out);
        assertTrue("quote char doubled then wrapped: ''' ; DROP ...'",
                n.contains("'''; DROP TABLE t_order; --'"));
        assertEquals("payload must not split the statement", 1, SQL.parseAll(n).size());
        assertValid(out, SqlDialect.MYSQL);
    }

    @Test
    public void bindPlaceholder() {
        SqlStatement out = inject("SELECT id FROM t_order", "?", "t_order");
        assertTrue(sql(out), sql(out).contains("tenant_id = ?"));
        assertEquals(1, SQL.parameters(out).size());
    }

    @Test
    public void tableNameMatchingIgnoresCase() {
        SqlStatement out = inject("SELECT id FROM T_ORDER", 1, "t_order");
        assertTrue(sql(out).toLowerCase().contains("tenant_id = 1"));
    }

    @Test
    public void rewriteChainAdapter() {
        SqlStatement stmt = SQL.parse("SELECT id FROM t_order");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.injectTenant("tenant_id", SqlTenantRewriter.literalValue(42),
                        "t_order")));
        assertTrue(sql(out).contains("tenant_id = 42"));
        assertFalse(sql(stmt).contains("tenant_id"));
    }

    @Test
    public void collectionOverload() {
        SqlStatement out = SQL.injectTenant(SQL.parse("SELECT id FROM t_order"), "tenant_id",
                SqlTenantRewriter.literalValue(1), Collections.singletonList("t_order"));
        assertTrue(sql(out).contains("tenant_id = 1"));
        SqlStatement none = SQL.injectTenant(SQL.parse("SELECT id FROM t_order"), "tenant_id",
                SqlTenantRewriter.literalValue(1), Arrays.asList("nope"));
        assertFalse(sql(none).contains("tenant_id"));
    }

    @Test
    public void postgresAndOracleRoundTrip() {
        SqlStatement mysql = inject("SELECT id FROM t_order o WHERE o.status = 1", 9, "t_order");
        assertValid(mysql, SqlDialect.POSTGRES);
        assertValid(mysql, SqlDialect.ORACLE);
        assertValid(mysql, SqlDialect.SQLSERVER);
        assertValid(mysql, SqlDialect.DAMENG);
    }

    @Test
    public void nullStatementReturnsNull() {
        assertEquals(null, SQL.injectTenant(null, "tenant_id", 1, "t"));
    }

    @Test
    public void blankColumnRejected() {
        try {
            SQL.injectTenant(SQL.parse("SELECT 1"), "  ", 1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("tenant column"));
        }
    }

    @Test
    public void insertAllBranch() {
        SqlStatement stmt = SQL.parse(
                "INSERT ALL INTO t_order (id, name) VALUES (1, 'a') SELECT 1 FROM dual",
                SqlDialect.ORACLE);
        SqlStatement out = SQL.injectTenant(stmt, "tenant_id", 8, "t_order");
        String n = SQL.toSqlString(out, SqlDialect.ORACLE);
        assertTrue(n, n.toLowerCase().contains("tenant_id"));
        assertTrue(n, n.contains("8"));
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
