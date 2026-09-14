package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@code DATE_FORMAT} 格式符改写。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class DateFormatRewriteRuleTest {

    @Test
    public void mysqlToPostgresRewritesCommonPattern() {
        String pg = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s') FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("TO_CHAR"));
        assertTrue(pg, pg.contains("YYYY-MM-DD HH24:MI:SS"));
        assertFalse(pg, pg.contains("%Y"));
        SQL.parse(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void mysqlToOracle() {
        String ora = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(ora, ora.contains("TO_CHAR"));
        assertTrue(ora, ora.contains("YYYY-MM-DD"));
        SQL.parse(ora, SqlDialect.ORACLE);
    }

    @Test
    public void mysqlToSqliteSwapsArgsAndMinutes() {
        String sql = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s') FROM t",
                SqlDialect.MYSQL, SqlDialect.SQLITE);
        assertTrue(sql, sql.contains("strftime"));
        assertTrue(sql, sql.contains("%Y-%m-%d %H:%M:%S"));
        assertTrue(sql, sql.contains("strftime("));
        assertFalse(sql, sql.contains("%i"));
        SQL.parse(sql, SqlDialect.SQLITE);
    }

    @Test
    public void unknownSpecifierKeepsWarning() {
        ConversionResult r = SQL.convert("SELECT DATE_FORMAT(ts, '%Y %v') FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES,
                com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions.defaults());
        assertTrue(r.sql(), r.sql().contains("TO_CHAR"));
        assertTrue(r.sql(), r.sql().contains("%v") || r.sql().contains("YYYY"));
        boolean risk = false;
        for (int i = 0; i < r.report().warnings().size(); i++) {
            if (r.report().warnings().get(i).severity()
                    == com.alianga.jkit.sql.schema.convert.ConversionWarning.Severity.SEMANTIC_RISK) {
                risk = true;
            }
        }
        assertTrue(risk);
    }

    @Test
    public void dateFormatToDateFormatUnchanged() {
        String mysql = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
                SqlDialect.MYSQL, SqlDialect.MYSQL);
        assertTrue(mysql, mysql.toUpperCase().contains("DATE_FORMAT"));
    }

    @Test
    public void rewriteMysqlFormatUnit() {
        DateFormatRewriteRule.FormatRewrite r = DateFormatRewriteRule.rewriteMysqlFormat(
                "'%Y-%m-%d'", DateFormatRewriteRule.Style.TO_CHAR);
        assertEquals("'YYYY-MM-DD'", r.quoted);
        assertTrue(r.complete);
        DateFormatRewriteRule.FormatRewrite s = DateFormatRewriteRule.rewriteMysqlFormat(
                "'%H:%i:%s'", DateFormatRewriteRule.Style.STRFTIME);
        assertEquals("'%H:%M:%S'", s.quoted);
        assertTrue(s.complete);
    }
}
