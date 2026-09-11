package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link SqlDialectWrapper} 能力覆写：解析 / 改写 / 分页全链路吃覆写结果。
 *
 * @author 郑明亮
 */
public class SqlDialectWrapperTest {

    @Test
    public void delegatesToBaseDialect() {
        SqlDialectSpec w = new SqlDialectWrapper(SqlDialect.SQLSERVER);
        assertTrue(w.supportsTop());
        assertFalse(w.supportsLimitOffset());
        assertEquals('[', w.identQuoteOpen());
        assertEquals("TOP", w.preferredLimitStyle());
        assertEquals("[user]", w.quoteIdent("user"));
    }

    @Test
    public void nullBaseFallsBackToMysql() {
        SqlDialectSpec w = new SqlDialectWrapper(null);
        assertTrue(w.pipesAsOr());
        assertTrue(w.backslashEscapes());
        assertTrue(w.doubleQuoteIsString());
    }

    /**
     * MySQL + ANSI_QUOTES：双引号从字符串变回标识符。
     */
    @Test
    public void overrideDoubleQuoteIsStringAffectsParsing() {
        SqlDialectSpec ansiQuotes = new SqlDialectWrapper(SqlDialect.MYSQL) {
            @Override
            public boolean doubleQuoteIsString() {
                return false;
            }
        };
        String sql = SQL.toSqlString(SQL.parse("SELECT \"id\" FROM t", ansiQuotes));
        // 双引号按标识符解析：MYSQL 回写变反引号；若是字符串则应输出 'id'
        assertTrue(sql, sql.contains("`id`"));
        assertFalse(sql, sql.contains("'id'"));
    }

    /**
     * MySQL + NO_BACKSLASH_ESCAPES：'a\'b' 不再吃掉引号。
     */
    @Test
    public void overrideBackslashEscapesAffectsLexer() {
        SqlDialectSpec noBackslash = new SqlDialectWrapper(SqlDialect.MYSQL) {
            @Override
            public boolean backslashEscapes() {
                return false;
            }
        };
        String sql = SQL.toSqlString(SQL.parse("SELECT 'a\\' FROM t", noBackslash));
        // 字符串在 'a\' 处闭合，FROM 前仍是字符串的一部分或独立 STRING，不应抛错
        assertTrue(sql.toUpperCase(), sql.toUpperCase().contains("FROM"));
    }

    /**
     * PostgreSQL 关闭 ~ 正则：裸 TILDE 不再是操作符，解析报错。
     */
    @Test
    public void overrideTildeRegexAffectsParser() {
        String sql = "SELECT * FROM t WHERE name ~ '^a'";
        SQL.parse(sql, SqlDialect.POSTGRES);
        SqlDialectSpec noTilde = new SqlDialectWrapper(SqlDialect.POSTGRES) {
            @Override
            public boolean supportsTildeRegex() {
                return false;
            }
        };
        try {
            SQL.parse(sql, noTilde);
            fail("expected SqlParseException");
        } catch (SqlParseException expected) {
            // 预期：~ 不再按正则操作符解析
        }
    }

    /**
     * ANSI 开方括号标识符：[c] 按 SQL Server 风格标识符解析。
     */
    @Test
    public void overrideBracketIdentifiersAffectsLexer() {
        SqlDialectSpec bracket = new SqlDialectWrapper(SqlDialect.ANSI) {
            @Override
            public boolean bracketIdentifiers() {
                return true;
            }
        };
        String sql = SQL.toSqlString(SQL.parse("SELECT [c] FROM t", bracket));
        assertFalse(sql.contains("["));
    }

    /**
     * MySQL 关闭逗号分页：LIMIT offset, count 变回 OFFSET 风格。
     */
    @Test
    public void overrideCommaLimitAffectsRewrite() {
        String base = "SELECT * FROM t";
        String comma = SQL.toSqlString(SQL.setPage(SQL.parse(base), 2, 10, SqlDialect.MYSQL));
        assertTrue(comma, comma.toUpperCase().contains("LIMIT 10,10"));

        SqlDialectSpec noComma = new SqlDialectWrapper(SqlDialect.MYSQL) {
            @Override
            public boolean supportsCommaLimitOffset() {
                return false;
            }
        };
        String offset = SQL.toSqlString(SQL.setPage(SQL.parse(base), 2, 10, noComma));
        assertTrue(offset, offset.toUpperCase().contains("LIMIT 10 OFFSET 10"));
    }

    /**
     * 只覆写原语时，派生方法走接口默认实现，不能再委托给基方言的旧值。
     */
    @Test
    public void derivedMethodsFollowOverriddenPrimitives() {
        SqlDialectSpec brackets = new SqlDialectWrapper(SqlDialect.MYSQL) {
            @Override
            public char identQuoteOpen() {
                return '[';
            }
        };
        assertEquals(']', brackets.identQuoteClose());
        assertEquals("[user]", brackets.quoteIdent("user"));

        SqlDialectSpec concatPipes = new SqlDialectWrapper(SqlDialect.MYSQL) {
            @Override
            public boolean pipesAsOr() {
                return false;
            }
        };
        assertTrue(concatPipes.pipesAreConcat());

        SqlDialectSpec top = new SqlDialectWrapper(SqlDialect.MYSQL) {
            @Override
            public boolean supportsTop() {
                return true;
            }

            @Override
            public boolean supportsLimitOffset() {
                return false;
            }
        };
        assertEquals("TOP", top.preferredLimitStyle());
    }
}
