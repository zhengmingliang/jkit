package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link SqlFormatOptions#keywordCase(SqlKeywordCase)}：关键字大小写策略（默认 {@link SqlKeywordCase#AS_IS}，
 * 即保持 formatter 原生输出——子句/运算符关键字大写）；不影响标识符、字符串字面量与 raw 直通原文。
 *
 * @author 郑明亮
 */
public class SqlFormatOptionsExtTest {

    @Test
    public void keywordCaseUpperLowerAsIs() {
        SqlStatement stmt = SQL.parse("select id, name from t_user where age > 18", SqlDialect.MYSQL);

        // 默认 AS_IS：formatter 原生输出（关键字大写）
        assertEquals("SELECT id, name FROM t_user WHERE age > 18",
                SQL.format(stmt, SqlDialect.MYSQL, false, SqlFormatOptions.defaults()));

        // UPPER：与默认一致（输入小写也能归一大写）
        assertEquals("SELECT id, name FROM t_user WHERE age > 18",
                SQL.format(stmt, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.UPPER)));

        // LOWER：关键字小写，标识符不动
        assertEquals("select id, name from t_user where age > 18",
                SQL.format(stmt, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)));
    }

    @Test
    public void keywordCaseCoversExpressionOperators() {
        SqlStatement stmt = SQL.parse(
                "select a from t where b in (1,2) and c like 'x' or not d between 1 and 2",
                SqlDialect.MYSQL);
        assertEquals("select a from t where b in (1, 2) and c like 'x' or not d between 1 and 2",
                SQL.format(stmt, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)));
    }

    @Test
    public void keywordCaseLeavesIdentifiersAndLiteralsAlone() {
        SqlStatement stmt = SQL.parse("SELECT Id FROM MyTable WHERE Name = 'MixedCase'",
                SqlDialect.MYSQL);
        assertEquals("select Id from MyTable where Name = 'MixedCase'",
                SQL.format(stmt, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)));
    }

    @Test
    public void keywordCaseAppliesToDdlKeywords() {
        SqlStatement ddl = SQL.parse("TRUNCATE TABLE t_user", SqlDialect.MYSQL);
        assertEquals("truncate table t_user",
                SQL.format(ddl, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)));
    }

    @Test
    public void keywordCaseAppliesToTxControl() {
        // 事务控制（COMMIT / ROLLBACK / SAVEPOINT / RELEASE）走 kind 回写，同样受策略影响
        SqlStatement commit = SQL.parse("COMMIT", SqlDialect.MYSQL);
        assertEquals("commit",
                SQL.format(commit, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)));

        SqlStatement rollback = SQL.parse("rollback", SqlDialect.MYSQL);
        assertEquals("ROLLBACK",
                SQL.format(rollback, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.UPPER)));
    }

    @Test
    public void nullKeywordCaseFallsBackAsIs() {
        SqlFormatOptions options = SqlFormatOptions.defaults().keywordCase(null);
        assertEquals(SqlKeywordCase.AS_IS, options.keywordCase());
        SqlStatement stmt = SQL.parse("select 1", SqlDialect.MYSQL);
        assertEquals("SELECT 1", SQL.format(stmt, SqlDialect.MYSQL, false, options));
    }

    @Test
    public void combinedWithQuoteIdentifiers() {
        SqlFormatOptions options = SqlFormatOptions.defaults()
                .keywordCase(SqlKeywordCase.LOWER)
                .quoteIdentifiers(true);
        assertEquals(SqlKeywordCase.LOWER, options.keywordCase());
        assertTrue(options.quoteIdentifiers());

        SqlStatement stmt = SQL.parse("select id from t where a = 1", SqlDialect.MYSQL);
        assertEquals("select `id` from `t` where `a` = 1",
                SQL.format(stmt, SqlDialect.MYSQL, false, options));
    }
}
