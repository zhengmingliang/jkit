package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConverter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * 转换输出的**形状**校验：只 {@code contains} 关键字不足以发现输出非法 SQL
 * （{@code STRING_AGG(a, ,)}、{@code ALTER COLUMN c TYPE c INTEGER} 都能过 contains），
 * 本类按「归一化后的完整串 + 目标方言复解析」断言。
 *
 * @author 郑明亮
 */
public class SqlSchemaConvertOutputTest {

    private static String norm(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static void assertReparsable(String sql, SqlDialect target) {
        try {
            SQL.parse(sql, target);
        } catch (RuntimeException ex) {
            throw new AssertionError("转换输出无法被目标方言 " + target + " 解析: " + sql + " -> " + ex.getMessage());
        }
    }

    @Test
    public void alterModifyTypeDoesNotDuplicateColumnName() {
        ConversionResult r = SqlSchemaConverter.convert(
                "ALTER TABLE t MODIFY c INT NOT NULL", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(r.sql(), "ALTER TABLE t ALTER COLUMN c TYPE INTEGER", norm(r.sql()));
        assertReparsable(r.sql(), SqlDialect.POSTGRES);
    }

    @Test
    public void alterChangeTypeDoesNotDuplicateColumnName() {
        ConversionResult r = SqlSchemaConverter.convert(
                "ALTER TABLE t CHANGE a b VARCHAR(20) NOT NULL", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        // CHANGE 的 RENAME 走附录，主句只应保留新列的类型改写
        assertEquals(r.sql(), "ALTER TABLE t ALTER COLUMN b TYPE VARCHAR(20)", norm(r.sql()));
        assertReparsable(r.sql(), SqlDialect.POSTGRES);
    }

    @Test
    public void groupConcatDefaultSeparatorIsQuotedOnPostgres() {
        String pg = SQL.convert("SELECT GROUP_CONCAT(a) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(pg, "SELECT STRING_AGG(a, ',') FROM t", norm(pg));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void groupConcatDefaultSeparatorIsQuotedOnOracle() {
        String ora = SQL.convert("SELECT GROUP_CONCAT(a) FROM t", SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertFalse(ora, norm(ora).contains(", ,"));
        assertReparsable(ora, SqlDialect.ORACLE);
    }

    @Test
    public void groupConcatDefaultSeparatorIsQuotedOnSqlServer() {
        String ss = SQL.convert("SELECT GROUP_CONCAT(a) FROM t", SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        assertFalse(ss, norm(ss).contains(", ,"));
        assertReparsable(ss, SqlDialect.SQLSERVER);
    }

    @Test
    public void nowHasNoEmptyParensOnPostgres() {
        // CURRENT_TIMESTAMP 在 PG 是关键字，CURRENT_TIMESTAMP() 会报语法错
        String pg = SQL.convert("SELECT NOW() FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(pg, "SELECT CURRENT_TIMESTAMP FROM t", norm(pg));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void curdateAndCurtimeHaveNoEmptyParens() {
        String d = SQL.convert("SELECT CURDATE() FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(d, "SELECT CURRENT_DATE FROM t", norm(d));
        assertReparsable(d, SqlDialect.POSTGRES);
        String t = SQL.convert("SELECT CURTIME() FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(t, "SELECT CURRENT_TIME FROM t", norm(t));
        assertReparsable(t, SqlDialect.POSTGRES);
    }

    @Test
    public void nowWithPrecisionKeepsParens() {
        // MySQL NOW(3) → PG CURRENT_TIMESTAMP(3) 是合法的，精度参数必须保留
        String pg = SQL.convert("SELECT NOW(3) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(pg, "SELECT CURRENT_TIMESTAMP(3) FROM t", norm(pg));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void nowInWhereHasNoEmptyParensOnOracle() {
        String ora = SQL.convert("SELECT * FROM t WHERE created_at < NOW()",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertFalse(ora, norm(ora).toUpperCase().contains("CURRENT_TIMESTAMP()"));
        assertReparsable(ora, SqlDialect.ORACLE);
    }
}
