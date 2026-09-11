package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

/**
 * 分页解析与改写：getLimit/getOffset/setLimit/setOffset/setPage 跨方言。
 *
 * @author 郑明亮
 */
public class SqlPaginationTest {

    @Test
    public void getLimitAndOffsetFromMysql() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t LIMIT 10 OFFSET 20");
        assertEquals(Long.valueOf(10L), SQL.getLimit(stmt));
        assertEquals(Long.valueOf(20L), SQL.getOffset(stmt));

        SqlStatement comma = SQL.parse("SELECT * FROM t LIMIT 5, 15");
        assertEquals(Long.valueOf(15L), SQL.getLimit(comma));
        assertEquals(Long.valueOf(5L), SQL.getOffset(comma));
        String sql = "SELECT id, name FROM users WHERE age > 25\n" +
                "            UNION\n" +
                "            SELECT id, name FROM customers WHERE status = 'active'\n" +
                "            ORDER BY name\n" +
                "            LIMIT 100";
        System.out.println(sql);
        Long limit = SQL.getLimit(SQL.parse(sql, SqlDialect.MYSQL));
        assertEquals(Long.valueOf(100L), limit);
    }

    @Test
    public void getLimitFromTopAndFetch() {
        SqlStatement top = SQL.parse("SELECT TOP 7 * FROM t", SqlDialect.SQLSERVER);
        assertEquals(Long.valueOf(7L), SQL.getLimit(top));
        assertNull(SQL.getOffset(top));

        SqlStatement fetch = SQL.parse(
                "SELECT * FROM t OFFSET 3 ROWS FETCH FIRST 9 ROWS ONLY", SqlDialect.ORACLE12);
        assertEquals(Long.valueOf(9L), SQL.getLimit(fetch));
        assertEquals(Long.valueOf(3L), SQL.getOffset(fetch));
    }

    @Test
    public void setPageMysqlUsesLimitOffset() {
        SqlStatement original = SQL.parse("SELECT id FROM users WHERE status = 1");
        SqlStatement page2 = SQL.setPage(original, 2, 10, SqlDialect.MYSQL);
        assertNull("clone-then-mutate", ((SqlSelect) original).limit());
        assertEquals(Long.valueOf(10L), SQL.getLimit(page2));
        assertEquals(Long.valueOf(10L), SQL.getOffset(page2));
        String sql = SQL.toSqlString(page2, SqlDialect.MYSQL).toUpperCase();
        assertTrue(sql, sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("10"));
    }

    @Test
    public void setPagePostgresLimitOffset() {
        SqlStatement page = SQL.setPage(
                SQL.parse("SELECT * FROM t", SqlDialect.POSTGRES), 3, 5, SqlDialect.POSTGRES);
        assertEquals(Long.valueOf(5L), SQL.getLimit(page));
        assertEquals(Long.valueOf(10L), SQL.getOffset(page));
        String sql = SQL.toSqlString(page, SqlDialect.POSTGRES).toUpperCase();
        assertTrue(sql, sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("OFFSET"));
        assertFalse(sql, sql.contains("FETCH"));
    }

    @Test
    public void setPageSqlServerTopOrFetch() {
        SqlStatement p1 = SQL.setPage(
                SQL.parse("SELECT * FROM t", SqlDialect.SQLSERVER), 1, 20, SqlDialect.SQLSERVER);
        String s1 = SQL.toSqlString(p1, SqlDialect.SQLSERVER).toUpperCase();
        assertTrue(s1, s1.contains("TOP"));
        assertEquals(Long.valueOf(20L), SQL.getLimit(p1));

        SqlStatement p2 = SQL.setPage(
                SQL.parse("SELECT * FROM t", SqlDialect.SQLSERVER), 2, 20, SqlDialect.SQLSERVER);
        String s2 = SQL.toSqlString(p2, SqlDialect.SQLSERVER).toUpperCase();
        assertTrue(s2, s2.contains("OFFSET"));
        assertTrue(s2, s2.contains("FETCH"));
        assertFalse(s2, s2.contains("TOP"));
        assertEquals(Long.valueOf(20L), SQL.getLimit(p2));
        assertEquals(Long.valueOf(20L), SQL.getOffset(p2));
    }

    @Test
    public void setPageOracleFetchFirst() {
        SqlStatement page = SQL.setPage(
                SQL.parse("SELECT * FROM emp", SqlDialect.ORACLE12), 2, 8, SqlDialect.ORACLE12);
        assertEquals(Long.valueOf(8L), SQL.getLimit(page));
        assertEquals(Long.valueOf(8L), SQL.getOffset(page));
        String sql = SQL.toSqlString(page, SqlDialect.ORACLE12).toUpperCase();
        assertTrue(sql, sql.contains("FETCH"));
        assertTrue(sql, sql.contains("OFFSET"));
        SQL.parse(SQL.toSqlString(page, SqlDialect.ORACLE12), SqlDialect.ORACLE12);
    }

    @Test
    public void setPageOracleClassicUsesRownum() {
        SqlStatement page = SQL.setPage(
                SQL.parse("SELECT * FROM emp", SqlDialect.ORACLE), 2, 8, SqlDialect.ORACLE);
        assertEquals(Long.valueOf(8L), SQL.getLimit(page));
        assertEquals(Long.valueOf(8L), SQL.getOffset(page));
        String sql = SQL.toSqlString(page, SqlDialect.ORACLE).toUpperCase();
        assertFalse("pre-12c must not use OFFSET/FETCH", sql.contains("FETCH") || sql.contains("OFFSET"));
        assertTrue(sql, sql.contains("ROWNUM"));
        SQL.parse(SQL.toSqlString(page, SqlDialect.ORACLE), SqlDialect.ORACLE);
    }

    @Test
    public void setLimitReplacesExisting() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t LIMIT 5");
        SqlStatement replaced = SQL.setLimit(stmt, 99, SqlDialect.MYSQL);
        assertEquals(Long.valueOf(5L), SQL.getLimit(stmt));
        assertEquals(Long.valueOf(99L), SQL.getLimit(replaced));
    }

    @Test
    public void setOffsetKeepsLimit() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t LIMIT 10");
        SqlStatement withOff = SQL.setOffset(stmt, 30, SqlDialect.MYSQL);
        assertEquals(Long.valueOf(10L), SQL.getLimit(withOff));
        assertEquals(Long.valueOf(30L), SQL.getOffset(withOff));
    }

    @Test
    public void setLimitNegativeClears() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t LIMIT 10 OFFSET 2");
        SqlStatement cleared = SQL.setLimit(stmt, -1, SqlDialect.MYSQL);
        assertNull(SQL.getLimit(cleared));
        assertNull(SQL.getOffset(cleared));
    }

    @Test
    public void nonSelectReturnsNull() {
        SqlStatement del = SQL.parse("DELETE FROM t WHERE id = 1");
        assertNull(SQL.getLimit(del));
        assertNull(SQL.getOffset(del));
        assertNotNull(SQL.setPage(del, 1, 10));
    }

    @Test
    public void toSqlStringMysqlLimitToOracleRownum() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE age > 18 limit 0,10000");
        String sql = SQL.toSqlString(stmt, SqlDialect.ORACLE).toUpperCase();
        assertFalse("must not keep MySQL LIMIT", sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("ROWNUM"));
        assertTrue(sql, sql.contains("ROWNUM <= 10000") || sql.contains("ROWNUM<= 10000")
                || sql.contains("ROWNUM <=10000"));
        // 单层：无 RN / XXX
        assertFalse("offset=0 should be single-layer", sql.contains(" RN ") || sql.contains("AS RN"));
        // 原 AST 不被改写
        assertTrue(SQL.toSqlString(stmt, SqlDialect.MYSQL).toUpperCase().contains("LIMIT"));
    }

    @Test
    public void toSqlStringMysqlLimitOffsetToOracleNested() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE age > 18 LIMIT 10,20");
        String sql = SQL.toSqlString(stmt, SqlDialect.ORACLE).toUpperCase();
        assertFalse(sql, sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("ROWNUM"));
        assertTrue(sql, sql.contains("AS RN") || sql.contains(" RN"));
        assertTrue(sql, sql.contains("RN > 10") || sql.contains("RN> 10"));
        assertTrue(sql, sql.contains("ROWNUM <= 30") || sql.contains("ROWNUM<= 30"));
    }

    @Test
    public void toSqlStringMysqlLimitToOracle12Fetch() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE age > 18 limit 0,10000");
        String sql = SQL.toSqlString(stmt, SqlDialect.ORACLE12).toUpperCase();
        assertFalse(sql, sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("FETCH"));
        assertFalse("offset=0 need not emit OFFSET", sql.contains("OFFSET"));
        assertTrue(sql, sql.contains("10000"));
    }

    @Test
    public void toSqlStringMysqlLimitToSqlServerTop() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE age > 18 limit 0,10000");
        String sql = SQL.toSqlString(stmt, SqlDialect.SQLSERVER).toUpperCase();
        assertFalse(sql, sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("TOP"));
        assertTrue(sql, sql.contains("10000"));
    }

    @Test
    public void toSqlStringMysqlLimitOffsetToSqlServerFetch() {
        SqlStatement stmt = SQL.parse("SELECT id FROM t LIMIT 10,20");
        String sql = SQL.toSqlString(stmt, SqlDialect.SQLSERVER).toUpperCase();
        assertFalse(sql, sql.contains("LIMIT"));
        assertFalse(sql, sql.contains("TOP"));
        assertTrue(sql, sql.contains("OFFSET"));
        assertTrue(sql, sql.contains("FETCH"));
    }

    @Test
    public void setPageWithPriorMysqlLimitClearsInnerLimitOnOracle() {
        String src = "SELECT id, name FROM t_user WHERE age > 18 limit 0,10000";
        SqlStatement page = SQL.setPage(SQL.parse(src), 2, 30, SqlDialect.ORACLE);
        String sql = SQL.toSqlString(page, SqlDialect.ORACLE).toUpperCase();
        assertFalse("inner query must not retain LIMIT", sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("ROWNUM <= 60") || sql.contains("ROWNUM<= 60"));
        assertTrue(sql, sql.contains("RN > 30") || sql.contains("RN> 30"));
        String bare = SQL.toSqlString(
                SQL.setPage(SQL.parse("SELECT id, name FROM t_user WHERE age > 18"), 2, 30,
                        SqlDialect.ORACLE),
                SqlDialect.ORACLE).toUpperCase();
        // 结构应与无先验 LIMIT 的 setPage 一致（忽略空白）
        assertEquals(bare.replace(" ", ""), sql.replace(" ", ""));
    }

    @Test
    public void setLimitWithPriorMysqlLimitNoEmbeddedLimitOnOracle() {
        SqlStatement limited = SQL.setLimit(
                SQL.parse("SELECT id, name FROM t_user WHERE age > 18 limit 0,10000"),
                100, SqlDialect.ORACLE);
        String sql = SQL.toSqlString(limited, SqlDialect.ORACLE).toUpperCase();
        assertFalse(sql, sql.contains("LIMIT"));
        assertTrue(sql, sql.contains("ROWNUM <= 100") || sql.contains("ROWNUM<= 100"));
    }

    @Test
    public void adaptPaginationNoOpWithoutPaging() {
        SqlStatement stmt = SQL.parse("SELECT id FROM t_user WHERE age > 18");
        String before = SQL.toSqlString(stmt);
        String after = SQL.toSqlString(SQL.adaptPagination(stmt, SqlDialect.ORACLE));
        assertEquals(before.toUpperCase().replace(" ", ""),
                after.toUpperCase().replace(" ", ""));
    }

    @Test
    public void mysqlToSqlStringFidelityKeepsCommaLimit() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE age > 18 limit 0,10000");
        String sql = SQL.toSqlString(stmt, SqlDialect.MYSQL).toUpperCase();
        assertTrue(sql, sql.contains("LIMIT"));
        // 默认 MySQL 回写不因 adapt 丢掉逗号风格
        assertTrue(sql, sql.contains("0") && sql.contains("10000"));
    }

    @Test
    public void addAndRemoveSelectItem() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user");
        SqlStatement added = SQL.addSelectItem(stmt, "age");
        String a = SQL.toSqlString(added).toUpperCase();
        assertTrue(a, a.contains("AGE"));
        assertTrue("must clone", SQL.toSqlString(stmt).toUpperCase().indexOf("AGE") < 0
                || !SQL.toSqlString(stmt).toUpperCase().contains(", AGE"));
        assertFalse(SQL.toSqlString(stmt).toUpperCase().contains("AGE"));

        SqlStatement removed = SQL.removeSelectItem(added, "name");
        String r = SQL.toSqlString(removed).toUpperCase();
        assertFalse(r, r.contains("NAME"));
        assertTrue(r, r.contains("ID") && r.contains("AGE"));

        SqlStatement removedQualified = SQL.removeSelectItem(
                SQL.parse("SELECT t.id, t.name FROM t_user t"), "name");
        assertFalse(SQL.toSqlString(removedQualified).toUpperCase().contains("NAME"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void removeSelectItemRejectsEmptyList() {
        SQL.removeSelectItem(SQL.parse("SELECT id FROM t"), "id");
    }

}
