package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

/**
 * 对齐 common-model {@code PagerUtilsTest} 的 getLimit / setPage 期望（ROWNUM / row_number / UNION）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlPagerUtilsParityTest {

    private static final String ORACLE_NESTED =
            "select * from ( select XX.*, ROWNUM as RN from ( select KHLX, KPJ, MAX_NUM, MIN_NUM, SPJ from "
                    + "TABLE_5 where KPJ > ? ) XX where ROWNUM <= 110 ) XXX where RN > 10";

    private static final String ORACLE_SIMPLE =
            "select rownum ROW_NUM_ALIAS, a.* from (SELECT KHLX, KPJ, MAX_NUM, MIN_NUM, SPJ FROM TABLE_5 where KPJ "
                    + "> ?) a where rownum <= 10";

    private static final String SS_ROW_NUMBER =
            "select * from ( select KHLX, KPJ, MAX_NUM, MIN_NUM, SPJ , row_number() as ROWNUM from TABLE_5 where "
                    + "KPJ > ? ) XX where ROWNUM > 10 and ROWNUM <= 110";

    private static final String MYSQL_LIMIT_COMMA =
            "select group_concat(concat('T1.',a2,' = T2.',a2) separator ' AND ')  MERGE_ON from person_loan_0811 "
                    + "where a5='2692' limit 10,20";

    private static final String PG_LIMIT_OFFSET = "select * from t_user where id = 1 limit 20 offset 10";

    private static final String SS_TOP =
            "select top 100 KHLX, KPJ, MAX_NUM, MIN_NUM, SPJ from TABLE_5 where KPJ > ?";

    private static final String UNION_LIMIT =
            "SELECT id, name FROM users WHERE age > 25\n"
                    + "            UNION\n"
                    + "            SELECT id, name FROM customers WHERE status = 'active'\n"
                    + "            ORDER BY name\n"
                    + "            LIMIT 100";

    private static final String PAREN_UNION_ORDER =
            "(SELECT id, name FROM users) UNION (SELECT id, name FROM employees) ORDER BY name";

    @Test
    public void getLimitMatchesPagerUtilsSamples() {
        assertEquals(Long.valueOf(20L), SQL.getLimit(SQL.parse(MYSQL_LIMIT_COMMA, SqlDialect.MYSQL)));
        assertEquals(Long.valueOf(20L), SQL.getLimit(SQL.parse(PG_LIMIT_OFFSET, SqlDialect.POSTGRES)));
        assertEquals(Long.valueOf(10L), SQL.getLimit(SQL.parse(ORACLE_SIMPLE, SqlDialect.ORACLE)));
        assertEquals(Long.valueOf(100L), SQL.getLimit(SQL.parse(ORACLE_NESTED, SqlDialect.ORACLE)));
        assertEquals(Long.valueOf(100L), SQL.getLimit(SQL.parse(SS_ROW_NUMBER, SqlDialect.SQLSERVER)));
        assertEquals(Long.valueOf(100L), SQL.getLimit(SQL.parse(SS_TOP, SqlDialect.SQLSERVER)));
        assertEquals(Long.valueOf(100L), SQL.getLimit(SQL.parse(UNION_LIMIT, SqlDialect.MYSQL)));
    }

    @Test
    public void getOffsetFromRownumAndLimit() {
        assertEquals(Long.valueOf(10L), SQL.getOffset(SQL.parse(ORACLE_NESTED, SqlDialect.ORACLE)));
        assertEquals(Long.valueOf(10L), SQL.getOffset(SQL.parse(SS_ROW_NUMBER, SqlDialect.SQLSERVER)));
        assertEquals(Long.valueOf(10L), SQL.getOffset(SQL.parse(MYSQL_LIMIT_COMMA, SqlDialect.MYSQL)));
        assertNull(SQL.getOffset(SQL.parse(ORACLE_SIMPLE, SqlDialect.ORACLE)));
    }

    @Test
    public void withoutLimitReturnsNull() {
        String sql = "select * from t_user where id = 1";
        assertNull(SQL.getLimit(SQL.parse(sql, SqlDialect.MYSQL)));
        assertNull(SQL.getLimit(SQL.parse(sql, SqlDialect.ORACLE)));
        assertNull(SQL.getLimit(SQL.parse(sql, SqlDialect.SQLSERVER)));
    }

    @Test
    public void setPageRewritesOracleNestedBounds() {
        SqlStatement page = SQL.setPage(SQL.parse(ORACLE_NESTED, SqlDialect.ORACLE), 2, 100,
                SqlDialect.ORACLE);
        String out = SQL.toSqlString(page, SqlDialect.ORACLE).toUpperCase();
        assertFalse("must not stack OFFSET/FETCH on ROWNUM wrap", out.contains("OFFSET"));
        assertFalse(out, out.contains("FETCH"));
        assertTrue(out, out.contains("ROWNUM <= 200") || out.contains("ROWNUM <=200"));
        assertTrue(out, out.contains("RN > 100") || out.contains("RN >100"));
        assertEquals(Long.valueOf(100L), SQL.getLimit(page));
        assertEquals(Long.valueOf(100L), SQL.getOffset(page));
    }

    @Test
    public void setPageRewritesSqlServerRowNumberBounds() {
        SqlStatement page = SQL.setPage(SQL.parse(SS_ROW_NUMBER, SqlDialect.SQLSERVER), 3, 50,
                SqlDialect.SQLSERVER);
        String out = SQL.toSqlString(page, SqlDialect.SQLSERVER).toUpperCase();
        assertFalse(out, out.contains("OFFSET"));
        assertTrue(out, out.contains("ROWNUM > 100") || out.contains("ROWNUM >100"));
        assertTrue(out, out.contains("ROWNUM <= 150") || out.contains("ROWNUM <=150"));
        assertEquals(Long.valueOf(50L), SQL.getLimit(page));
        assertEquals(Long.valueOf(100L), SQL.getOffset(page));
    }

    @Test
    public void setPageOnOracleSimpleFirstPageUpdatesRownum() {
        SqlStatement page = SQL.setPage(SQL.parse(ORACLE_SIMPLE, SqlDialect.ORACLE), 1, 25,
                SqlDialect.ORACLE);
        String out = SQL.toSqlString(page, SqlDialect.ORACLE).toUpperCase();
        assertTrue(out, out.contains("ROWNUM <= 25") || out.contains("ROWNUM <=25"));
        assertFalse(out, out.contains("OFFSET"));
        assertEquals(Long.valueOf(25L), SQL.getLimit(page));
    }

    @Test
    public void setPageOnUnionAppliesToOuterLimit() {
        SqlStatement page = SQL.setPage(SQL.parse(UNION_LIMIT, SqlDialect.MYSQL), 2, 30, SqlDialect.MYSQL);
        String out = SQL.toSqlString(page, SqlDialect.MYSQL).toUpperCase();
        assertTrue(out, out.contains("UNION"));
        assertEquals(Long.valueOf(30L), SQL.getLimit(page));
        assertEquals(Long.valueOf(30L), SQL.getOffset(page));
        // LIMIT must not appear only on the first branch before UNION
        int unionAt = out.indexOf("UNION");
        int limitAt = out.lastIndexOf("LIMIT");
        assertTrue("LIMIT should be after UNION (whole query)", limitAt > unionAt);
    }

    @Test
    public void parseParenthesizedUnionOrderByOracle() {
        SqlStatement stmt = SQL.parse(PAREN_UNION_ORDER, SqlDialect.ORACLE);
        assertNotNull(stmt);
        String out = SQL.toSqlString(stmt, SqlDialect.ORACLE).toUpperCase();
        assertTrue(out, out.contains("UNION"));
        assertTrue(out, out.contains("ORDER"));
        SqlStatement page = SQL.setPage(stmt, 1, 10, SqlDialect.ORACLE);
        String paged = SQL.toSqlString(page, SqlDialect.ORACLE).toUpperCase();
        assertTrue(paged, paged.contains("FETCH") || paged.contains("ROWNUM"));
        assertEquals(Long.valueOf(10L), SQL.getLimit(page));
    }

    @Test
    public void userFourSqlsExplicit() {
        assertEquals(Long.valueOf(100L), SQL.getLimit(SQL.parse(ORACLE_NESTED, SqlDialect.ORACLE)));
        assertEquals(Long.valueOf(10L), SQL.getLimit(SQL.parse(ORACLE_SIMPLE, SqlDialect.ORACLE)));
        assertEquals(Long.valueOf(100L), SQL.getLimit(SQL.parse(SS_ROW_NUMBER, SqlDialect.SQLSERVER)));
        assertEquals(Long.valueOf(100L), SQL.getLimit(SQL.parse(UNION_LIMIT, SqlDialect.MYSQL)));
    }
}
