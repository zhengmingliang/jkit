package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConverter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 转换输出的**卫生矩阵**：源 SQL × 目标方言，逐条校验
 *
 * <ol>
 *   <li>输出能被目标方言重新解析；解析不了时必须至少带 {@code SEMANTIC_RISK} 告警
 *       （保留原文导致目标库跑不了，属于必须显式告知用户的情况）；</li>
 *   <li>不含明显的畸形片段：空参数 {@code (a, ,)}、重复列名 {@code ALTER COLUMN c TYPE c ...}；</li>
 *   <li>转换本身不抛异常。</li>
 * </ol>
 *
 * <p>只 {@code contains} 关键字的断言抓不到 {@code STRING_AGG(a, ,)} 这类非法输出
 * （关键字确实存在，语句却是坏的），本类补的是这一层。</p>
 *
 * @author 郑明亮
 */
@RunWith(Parameterized.class)
public class SqlSchemaConvertSanityTest {

    private static final Pattern EMPTY_ARG = Pattern.compile(",\\s*,|\\(\\s*,|,\\s*\\)");
    private static final Pattern DUP_ALTER_COLUMN =
            Pattern.compile("ALTER\\s+COLUMN\\s+(\\w+)\\s+TYPE\\s+\\1\\b", Pattern.CASE_INSENSITIVE);

    private static final String[] SOURCES = {
            "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(32) NOT NULL)",
            "CREATE TABLE t (flag TINYINT(1) DEFAULT 0, ts DATETIME, amt DECIMAL(10,2), body TEXT)",
            "CREATE TABLE t (id INT, KEY idx_name (name))",
            "ALTER TABLE t ADD COLUMN c VARCHAR(10) NOT NULL",
            "ALTER TABLE t MODIFY c INT NOT NULL",
            "ALTER TABLE t CHANGE a b VARCHAR(20) NOT NULL",
            "ALTER TABLE t MODIFY amt DECIMAL(10,2) NOT NULL DEFAULT 0",
            "CREATE INDEX idx ON t (a) USING BTREE",
            "SELECT id, name FROM t WHERE id > 1 LIMIT 10",
            "SELECT * FROM t LIMIT 10, 20",
            "SELECT IF(a > 0, 1, 0) FROM t",
            "SELECT IFNULL(a, 0), COALESCE(b, 1) FROM t",
            "SELECT NOW(), CURDATE() FROM t",
            "SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
            "SELECT DATE_ADD(ts, INTERVAL 7 DAY) FROM t",
            "SELECT GROUP_CONCAT(name) FROM t",
            "SELECT GROUP_CONCAT(name SEPARATOR '|') FROM t",
            "SELECT GROUP_CONCAT(DISTINCT name ORDER BY id) FROM t",
            "SELECT CAST(id AS CHAR), CONVERT(name USING utf8mb4) FROM t",
            "SELECT SUBSTRING_INDEX(a, ',', 2) FROM t",
            "SELECT t.a FROM t JOIN u ON IF(t.x, 1, 0) = 1",
            "UPDATE t SET a = 1 WHERE b = NOW()",
            "DELETE FROM t WHERE created_at < NOW()",
            "INSERT INTO t (a, b) VALUES (1, NOW())",
    };

    private final String sql;
    private final SqlDialect target;

    /**
     * @param sql 源 SQL
     * @param target 目标方言
     */
    public SqlSchemaConvertSanityTest(String sql, SqlDialect target) {
        this.sql = sql;
        this.target = target;
    }

    /**
     * @return 源 SQL × 目标方言矩阵
     */
    @Parameterized.Parameters(name = "{1} :: {0}")
    public static Collection<Object[]> data() {
        List<SqlDialect> targets = Arrays.asList(
                SqlDialect.POSTGRES, SqlDialect.ORACLE, SqlDialect.ORACLE12,
                SqlDialect.SQLSERVER, SqlDialect.H2, SqlDialect.ANSI,
                SqlDialect.SQLITE, SqlDialect.DB2, SqlDialect.PRESTO);
        List<Object[]> rows = new ArrayList<Object[]>(SOURCES.length * targets.size());
        for (int i = 0; i < SOURCES.length; i++) {
            for (int j = 0; j < targets.size(); j++) {
                rows.add(new Object[] {SOURCES[i], targets.get(j)});
            }
        }
        return rows;
    }

    @Test
    public void outputIsWellFormed() {
        ConversionResult r;
        try {
            r = SqlSchemaConverter.convert(sql, SqlDialect.MYSQL, target);
        } catch (RuntimeException ex) {
            throw new AssertionError("转换抛异常 " + SqlDialect.MYSQL + "->" + target + ": " + sql, ex);
        }
        String out = r.sql();
        String where = SqlDialect.MYSQL + "->" + target + " :: " + sql + " => " + out;

        assertTrue("空参数列表 " + where, !EMPTY_ARG.matcher(out).find());
        assertTrue("ALTER COLUMN 列名重复 " + where, !DUP_ALTER_COLUMN.matcher(out).find());

        try {
            SQL.parse(out, target);
        } catch (RuntimeException ex) {
            // 保留原文导致目标库跑不了是允许的，但必须显式告警，不能静默产出
            if (!r.report().hasSeverityAtLeast(ConversionWarning.Severity.SEMANTIC_RISK)) {
                fail("输出无法被目标方言解析且未告警（" + ex.getMessage() + "） " + where);
            }
        }
    }
}
