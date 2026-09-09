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
}
