package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConverter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void dateDiffTruncatesToDateOnPostgres() {
        // MySQL DATEDIFF 只比日期部分返回天数；裸减法对 TIMESTAMP 在 PG 得 interval，语义不对
        String pg = SQL.convert("SELECT DATEDIFF(a, b) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(pg, "SELECT (CAST(a AS DATE) - CAST(b AS DATE)) FROM t", norm(pg));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void dateDiffTruncatesToDateOnOracle() {
        String ora = SQL.convert("SELECT DATEDIFF(a, b) FROM t", SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertEquals(ora, "SELECT (TRUNC(a) - TRUNC(b)) FROM t", norm(ora));
        assertReparsable(ora, SqlDialect.ORACLE);
    }

    @Test
    public void dateAddIntervalIsQuotedOnPostgres() {
        // PG 的 interval 字面量必须是带引号字符串，INTERVAL 3 day 会报语法错
        String pg = SQL.convert("SELECT DATE_ADD(a, INTERVAL 3 DAY) FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(pg, "SELECT (CAST(a AS DATE) + INTERVAL '3 day') FROM t", norm(pg));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void dateAddOnOracleUsesStandardIntervalLiteral() {
        String ora = SQL.convert("SELECT DATE_ADD(a, INTERVAL 3 DAY) FROM t",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertEquals(ora, "SELECT (CAST(a AS DATE) + INTERVAL '3' DAY) FROM t", norm(ora));
    }

    @Test
    public void dateAddOnSqlServerUsesDateAddFunction() {
        String ss = SQL.convert("SELECT DATE_ADD(a, INTERVAL 3 DAY) FROM t",
                SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        assertEquals(ss, "SELECT DATEADD(day, 3, a) FROM t", norm(ss));
    }

    @Test
    public void dateSubOnSqlServerUsesNegativeAmount() {
        String ss = SQL.convert("SELECT DATE_SUB(a, INTERVAL 3 DAY) FROM t",
                SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        assertEquals(ss, "SELECT DATEADD(day, -3, a) FROM t", norm(ss));
    }

    @Test
    public void dateAddOnSqliteUsesDateTimeFunction() {
        String lite = SQL.convert("SELECT DATE_ADD(a, INTERVAL 3 DAY) FROM t",
                SqlDialect.MYSQL, SqlDialect.SQLITE);
        assertEquals(lite, "SELECT datetime(a, '+3 days') FROM t", norm(lite));
    }

    @Test
    public void dateAddOnDb2WarnsInsteadOfEmittingInterval() {
        // DB2 要写 a + 3 DAYS（labeled duration），没有 INTERVAL 字面量；
        // 现有 AST 无法干净表达 "3 DAYS"，故保留原文但必须告警，不能静默产出
        ConversionResult r = SqlSchemaConverter.convert("SELECT DATE_ADD(a, INTERVAL 3 DAY) FROM t",
                SqlDialect.MYSQL, SqlDialect.DB2);
        assertTrue("DB2 未告警却输出了 INTERVAL: " + r.sql(),
                r.report().hasSeverityAtLeast(ConversionWarning.Severity.SEMANTIC_RISK));
    }

    @Test
    public void sqliteAutoIncrementWithoutPrimaryKeyWarns() {
        // AUTOINCREMENT 在 SQLite 只能挂 PRIMARY KEY，无主键时必须告警而不是悄悄丢掉
        ConversionResult r = SqlSchemaConverter.convert("CREATE TABLE t (id BIGINT AUTO_INCREMENT)",
                SqlDialect.MYSQL, SqlDialect.SQLITE);
        assertTrue("无主键却没告警，AUTOINCREMENT 被静默丢弃: " + r.sql(),
                r.report().hasSeverityAtLeast(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED));
    }

    @Test
    public void sqliteAutoIncrementFollowsPrimaryKey() {
        // SQLite 要求 AUTOINCREMENT 紧跟 PRIMARY KEY 之后，写反了建表就报错
        String lite = SQL.convert("CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                SqlDialect.MYSQL, SqlDialect.SQLITE);
        assertEquals(lite, "CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)", norm(lite));
        assertReparsable(lite, SqlDialect.SQLITE);
    }

    @Test
    public void dateAddExpandsWithParentheses() {
        // 函数展开成运算符后必须套括号，否则 DATE_ADD(a, INTERVAL 1 DAY) * 2
        // 会变成 a + INTERVAL ... * 2，乘法优先级更高，语义整个变掉
        String pg = SQL.convert("SELECT DATE_ADD(a, INTERVAL 1 DAY) * 2 FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String n = norm(pg);
        assertTrue(pg, n.startsWith("SELECT (CAST(a AS DATE) +"));
        assertTrue(pg, n.contains(") * 2"));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void dateSubExpandsWithParentheses() {
        String pg = SQL.convert("SELECT DATE_SUB(a, INTERVAL 1 DAY) + 1 FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String n = norm(pg);
        assertTrue(pg, n.startsWith("SELECT (CAST(a AS DATE) -"));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void dateDiffWithExpressionArgsStaysValid() {
        String pg = SQL.convert("SELECT DATEDIFF(NOW(), created_at) FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(pg, "SELECT (CAST(CURRENT_TIMESTAMP AS DATE) - CAST(created_at AS DATE)) FROM t",
                norm(pg));
        assertReparsable(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void nowInWhereHasNoEmptyParensOnOracle() {
        String ora = SQL.convert("SELECT * FROM t WHERE created_at < NOW()",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertFalse(ora, norm(ora).toUpperCase().contains("CURRENT_TIMESTAMP()"));
        assertReparsable(ora, SqlDialect.ORACLE);
    }

    @Test
    public void uuidOnPostgresWarnsWithoutGuessingEquivalent() {
        // PG 是 gen_random_uuid()，名字与语义细节都不同，不猜等价，只告警
        ConversionResult r = SqlSchemaConverter.convert("SELECT UUID() FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue("PG 未对 UUID() 告警: " + r.sql(),
                r.report().hasSeverityAtLeast(ConversionWarning.Severity.SEMANTIC_RISK));
        assertEquals("SELECT UUID() FROM t", norm(r.sql()));
    }

    @Test
    public void randOnPostgresWarns() {
        ConversionResult r = SqlSchemaConverter.convert("SELECT * FROM t ORDER BY RAND() LIMIT 1",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue("PG 未对 RAND() 告警: " + r.sql(),
                r.report().hasSeverityAtLeast(ConversionWarning.Severity.SEMANTIC_RISK));
    }

    @Test
    public void randOnSqlServerIsSameNameAndSilent() {
        // SQL Server 有同名 RAND()，不该告警
        ConversionResult r = SqlSchemaConverter.convert("SELECT RAND()",
                SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        assertFalse("SQLSERVER 同名 RAND() 不应告警",
                r.report().hasSeverityAtLeast(ConversionWarning.Severity.SEMANTIC_RISK));
        assertEquals("SELECT RAND()", norm(r.sql()));
    }

    @Test
    public void lastInsertIdOnOracleWarns() {
        // LAST_INSERT_ID 是连接级会话状态，Oracle/PG 里要用 RETURNING/序列，无法函数级等价
        ConversionResult r = SqlSchemaConverter.convert("SELECT LAST_INSERT_ID()",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue("Oracle 未对 LAST_INSERT_ID() 告警: " + r.sql(),
                r.report().hasSeverityAtLeast(ConversionWarning.Severity.SEMANTIC_RISK));
    }

    @Test
    public void pgConcatOperatorBecomesConcatOnMysql() {
        // MySQL 的 || 默认是逻辑或，直译会把拼接变成布尔判断
        ConversionResult r = SqlSchemaConverter.convert("SELECT a || b FROM t",
                SqlDialect.POSTGRES, SqlDialect.MYSQL);
        assertEquals("SELECT CONCAT(a, b) FROM t", norm(r.sql()));
        assertReparsable(r.sql(), SqlDialect.MYSQL);
    }

    @Test
    public void pgConcatOperatorBecomesConcatOnSqlServerWithWarn() {
        // SQL Server 没有 ||；CONCAT() 存在但把 NULL 当空串，与 || 的 NULL 传播不同，需告警
        ConversionResult r = SqlSchemaConverter.convert("SELECT a || b FROM t",
                SqlDialect.POSTGRES, SqlDialect.SQLSERVER);
        assertEquals("SELECT CONCAT(a, b) FROM t", norm(r.sql()));
        assertTrue("SQL Server 的 CONCAT 语义差异未告警: " + r.sql(),
                r.report().hasSeverityAtLeast(ConversionWarning.Severity.SEMANTIC_RISK));
        assertReparsable(r.sql(), SqlDialect.SQLSERVER);
    }

    @Test
    public void nestedConcatOperatorFlattensIntoOneCall() {
        // 注意不要用 first/last 这类保留字当列名，MySQL 侧复解析会挂
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT fname || ' ' || lname FROM t", SqlDialect.POSTGRES, SqlDialect.MYSQL);
        assertEquals("SELECT CONCAT(fname, ' ', lname) FROM t", norm(r.sql()));
        assertReparsable(r.sql(), SqlDialect.MYSQL);
    }

    @Test
    public void concatOperatorSurvivesOnDialectsThatSupportIt() {
        // PG / Oracle / SQLite / DB2 的 || 就是拼接，不该被改写
        for (SqlDialect target : new SqlDialect[]{SqlDialect.POSTGRES, SqlDialect.ORACLE,
                SqlDialect.SQLITE, SqlDialect.DB2}) {
            ConversionResult r = SqlSchemaConverter.convert("SELECT a || b FROM t",
                    SqlDialect.POSTGRES, target);
            assertEquals("目标 " + target + " 不该改写 ||", "SELECT a || b FROM t", norm(r.sql()));
            assertReparsable(r.sql(), target);
        }
    }

    @Test
    public void concatInsideOtherExpressionsIsRewritten() {
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT UPPER(a || b) FROM t WHERE a || b = 'xy'", SqlDialect.POSTGRES, SqlDialect.MYSQL);
        assertEquals("SELECT UPPER(CONCAT(a, b)) FROM t WHERE CONCAT(a, b) = 'xy'", norm(r.sql()));
    }
}
