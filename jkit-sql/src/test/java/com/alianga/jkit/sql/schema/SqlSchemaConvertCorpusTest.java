package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConverter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 跨方言转换黄金语料：关键片段必须出现/不得出现，告警级别符合预期。
 *
 * @author 郑明亮
 */
@RunWith(Parameterized.class)
public class SqlSchemaConvertCorpusTest {
    private final SqlDialect source;
    private final SqlDialect target;
    private final String sql;
    private final String[] mustHave;
    private final String[] mustNot;
    private final ConversionWarning.Severity minWarn;

    /**
     * @param source 源方言
     * @param target 目标方言
     * @param sql 源 SQL
     * @param mustHave 目标 SQL 必须含有的片段（大写比较），可空
     * @param mustNot 目标 SQL 不得含有的片段，可空
     * @param minWarn 期望至少出现的告警级别，可空
     */
    public SqlSchemaConvertCorpusTest(SqlDialect source, SqlDialect target, String sql,
                                      String[] mustHave, String[] mustNot,
                                      ConversionWarning.Severity minWarn) {
        this.source = source;
        this.target = target;
        this.sql = sql;
        this.mustHave = mustHave;
        this.mustNot = mustNot;
        this.minWarn = minWarn;
    }

    /**
     * @return 语料
     */
    @Parameterized.Parameters(name = "{0}->{1} :: {2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {
                        SqlDialect.MYSQL, SqlDialect.POSTGRES,
                        "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(32) NOT NULL)",
                        new String[] {"INTEGER", "GENERATED ALWAYS AS IDENTITY", "VARCHAR(32)", "PRIMARY KEY"},
                        new String[] {"AUTO_INCREMENT"},
                        null
                },
                {
                        SqlDialect.MYSQL, SqlDialect.ORACLE,
                        "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                        new String[] {"NUMBER(10)", "PRIMARY KEY"},
                        new String[] {"AUTO_INCREMENT", "IDENTITY"},
                        ConversionWarning.Severity.MANUAL_ACTION_REQUIRED
                },
                {
                        SqlDialect.MYSQL, SqlDialect.ORACLE12,
                        "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                        new String[] {"NUMBER(10)", "GENERATED ALWAYS AS IDENTITY"},
                        new String[] {"AUTO_INCREMENT"},
                        null
                },
                {
                        SqlDialect.MYSQL, SqlDialect.SQLSERVER,
                        "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                        new String[] {"IDENTITY(1,1)"},
                        new String[] {"AUTO_INCREMENT"},
                        null
                },
                {
                        SqlDialect.MYSQL, SqlDialect.POSTGRES,
                        "CREATE TABLE t (flag TINYINT(1) DEFAULT 0, ts DATETIME, amt DECIMAL(10,2))",
                        new String[] {"BOOLEAN", "TIMESTAMP", "NUMERIC(10,2)"},
                        new String[] {"TINYINT", "DATETIME", "DECIMAL"},
                        null
                },
                {
                        SqlDialect.MYSQL, SqlDialect.POSTGRES,
                        "SELECT CONVERT(name USING utf8mb4) FROM t",
                        new String[] {"CONVERT"},
                        new String[] {"CAST"},
                        ConversionWarning.Severity.SEMANTIC_RISK
                },
                {
                        SqlDialect.MYSQL, SqlDialect.POSTGRES,
                        "SELECT CAST(id AS INT), IF(a>0,1,0), GROUP_CONCAT(n SEPARATOR ',') FROM t",
                        new String[] {"INTEGER", "CASE", "STRING_AGG"},
                        new String[] {"GROUP_CONCAT", "SEPARATOR"},
                        null
                },
                {
                        SqlDialect.MYSQL, SqlDialect.ORACLE,
                        "SELECT GROUP_CONCAT(n SEPARATOR ',') FROM t",
                        new String[] {"LISTAGG", "WITHIN"},
                        new String[] {"GROUP_CONCAT", "SEPARATOR"},
                        null
                },
        });
    }

    @Test
    public void converts() {
        ConversionResult r = SqlSchemaConverter.convert(sql, source, target);
        String u = r.sql().toUpperCase(Locale.ROOT);
        if (mustHave != null) {
            for (int i = 0; i < mustHave.length; i++) {
                assertTrue(source + "->" + target + " missing " + mustHave[i] + " in " + r.sql(),
                        u.contains(mustHave[i].toUpperCase(Locale.ROOT)));
            }
        }
        if (mustNot != null) {
            for (int i = 0; i < mustNot.length; i++) {
                assertFalse(source + "->" + target + " unexpected " + mustNot[i] + " in " + r.sql(),
                        u.contains(mustNot[i].toUpperCase(Locale.ROOT)));
            }
        }
        if (minWarn != null) {
            assertTrue("expected warning " + minWarn + " for " + sql,
                    r.report().hasSeverityAtLeast(minWarn));
        }
        com.alianga.jkit.sql.SQL.parse(r.sql(), target);
    }
}
