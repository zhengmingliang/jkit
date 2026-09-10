package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * 回写保真：{@code parse -> format} 的紧凑文本必须与原文语义等价。
 *
 * <p>与 {@link SqlGoldenCorpusTest} 的分工：黄金集只校验「能解析」以及
 * {@code type / isReadOnly / tables} 在二次解析后不漂移；这些断言都抓不到
 * 回写丢词的语义损坏——例如 {@code NATURAL LEFT JOIN} 回写成 {@code NATURAL JOIN}，
 * 两轮的 type 与 tables 完全一致，只有与原文逐字比对才能发现。</p>
 *
 * <p>归一化规则（消除纯排版差异与语义等价写法，只保留会改变语义的词法差异）：</p>
 * <ul>
 *   <li>去掉行注释与块注释</li>
 *   <li>去掉独立 {@code AS}——省略或补充别名关键字不改变语义</li>
 *   <li>去掉 {@code INNER} / {@code OUTER}——{@code JOIN} 默认即 inner，{@code LEFT} 即 left outer</li>
 *   <li>去掉 {@code ASC}——升序是默认排序方向</li>
 *   <li>{@code TRUNCATE TABLE t} 与 {@code TRUNCATE t} 视为等价</li>
 *   <li>去掉全部空白（于是 {@code DECIMAL(10 , 2)} 与 {@code DECIMAL(10,2)} 视为相同）</li>
 *   <li>统一大写</li>
 * </ul>
 *
 * <p>因此本测试只报「词变了」，不报「空格变了」，也不报上述等价改写。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@RunWith(Parameterized.class)
public class SqlRoundTripFidelityTest {
    private final String dialect;
    private final String sql;

    /**
     * @param dialect 方言名
     * @param sql SQL
     */
    public SqlRoundTripFidelityTest(String dialect, String sql) {
        this.dialect = dialect;
        this.sql = sql;
    }

    /**
     * @return 用例
     */
    @Parameterized.Parameters(name = "{0} :: {1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {"mysql", "SELECT * FROM t WHERE a = 1"},
                {"mysql", "SELECT a, b FROM t ORDER BY a DESC LIMIT 10"},
                {"mysql", "SELECT * FROM t WHERE a IN (1, 2, 3) AND b = 'x'"},
                {"mysql", "SELECT * FROM t WHERE a BETWEEN 1 AND 10"},
                {"mysql", "SELECT * FROM t WHERE a LIKE '%x%' ESCAPE '!'"},
                {"mysql", "SELECT DISTINCT a, b FROM t"},
                {"mysql", "SELECT a, COUNT(*) AS c FROM t GROUP BY a HAVING COUNT(*) > 1"},
                {"mysql", "SELECT CASE WHEN a > 1 THEN 'x' ELSE 'y' END FROM t"},
                {"mysql", "SELECT * FROM (SELECT * FROM t) x"},
                {"mysql", "WITH cte AS (SELECT * FROM t) SELECT * FROM cte"},
                {"mysql", "SELECT * FROM t1 UNION ALL SELECT * FROM t2"},
                {"mysql", "SELECT a, ROW_NUMBER() OVER (PARTITION BY b ORDER BY c) AS rn FROM t"},
                {"mysql", "SELECT CAST(a AS DECIMAL(10, 2)) FROM t"},

                {"mysql", "SELECT * FROM t1 LEFT JOIN t2 ON t1.a = t2.a"},
                {"mysql", "SELECT * FROM t1 RIGHT JOIN t2 ON t1.a = t2.a"},
                {"mysql", "SELECT * FROM t1 FULL JOIN t2 ON t1.a = t2.a"},
                {"mysql", "SELECT * FROM t1 INNER JOIN t2 ON t1.a = t2.a"},
                {"mysql", "SELECT * FROM t1 CROSS JOIN t2"},
                {"mysql", "SELECT * FROM t1 NATURAL JOIN t2"},
                {"mysql", "SELECT * FROM t1 NATURAL LEFT JOIN t2"},
                {"mysql", "SELECT * FROM t1 NATURAL RIGHT JOIN t2"},
                {"mysql", "SELECT * FROM t1 NATURAL FULL JOIN t2"},
                {"mysql", "SELECT * FROM t1 NATURAL INNER JOIN t2"},
                {"mysql", "SELECT * FROM t1 LEFT OUTER JOIN t2 ON t1.a = t2.a"},
                {"postgres", "SELECT * FROM t1 FULL JOIN t2 USING (a)"},
                {"mysql", "SELECT * FROM t1, t2 WHERE t1.a = t2.a"},

                {"mysql", "INSERT INTO t (a, b) VALUES (1, 'x')"},
                {"mysql", "INSERT INTO t (a) VALUES (1), (2), (3)"},
                {"mysql", "INSERT INTO t (a) SELECT a FROM s"},
                {"mysql", "INSERT INTO t (a) VALUES (1) ON DUPLICATE KEY UPDATE a = a + 1"},
                {"mysql", "UPDATE t SET a = 1 WHERE b = 2"},
                {"mysql", "UPDATE t SET a = 1, b = 2 WHERE c = 3"},
                {"mysql", "DELETE FROM t WHERE a = 1"},
                {"mysql", "REPLACE INTO t (a) VALUES (1)"},

                {"mysql", "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(20))"},
                {"mysql", "CREATE TABLE IF NOT EXISTS t (id INT)"},
                {"mysql", "DROP TABLE IF EXISTS t"},
                {"mysql", "TRUNCATE TABLE t"},
                {"mysql", "ALTER TABLE t ADD COLUMN a INT NOT NULL DEFAULT 0"},
                {"mysql", "ALTER TABLE t DROP COLUMN a"},
                {"mysql", "ALTER TABLE t MODIFY a VARCHAR(10)"},
                {"mysql", "ALTER TABLE t ADD KEY k (a)"},
                {"mysql", "ALTER TABLE t ADD UNIQUE KEY uk (a)"},
                {"mysql", "ALTER TABLE t ADD UNIQUE INDEX uk (a)"},
                {"mysql", "CREATE INDEX idx ON t (name)"},
                {"mysql", "DROP INDEX idx ON t"},
                {"mysql", "RENAME TABLE a TO b"},
                {"mysql", "RENAME TABLE a TO b, c TO d"},
                {"mysql", "ALTER TABLE t RENAME TO t2"},
                {"mysql", "CREATE TABLE t2 LIKE t1"},
                {"mysql", "TRUNCATE TABLE t"},
                {"mysql", "DROP USER 'u'@'%'"},
                {"mysql", "CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW SET NEW.a = 1"},
                {"mysql", "CREATE VIEW v AS SELECT * FROM t"},
                {"mysql", "CREATE USER 'u'@'%' IDENTIFIED BY 'p'"},
                {"mysql", "GRANT SELECT, INSERT ON db1.* TO 'u'@'%'"},

                {"postgres", "INSERT INTO t (a) VALUES (1) ON CONFLICT (a) DO UPDATE SET a = EXCLUDED.a"},
                {"postgres", "SELECT * FROM t WHERE a IS NULL"},
                {"postgres", "SELECT ARRAY[1, 2, 3]"},
                {"postgres", "SELECT * FROM t WHERE a = ANY(ARRAY[1, 2])"},
                {"postgres", "SELECT * FROM t WHERE a = ANY(ARRAY[1])"},

                {"oracle", "SELECT * FROM t WHERE ROWNUM <= 10"},
                {"sqlserver", "SELECT TOP 10 * FROM t"},
                {"sqlserver", "SELECT * FROM t WITH (NOLOCK)"},
                {"sqlserver", "SELECT * FROM t AS x WITH (NOLOCK)"},
                {"sqlserver", "SELECT * FROM t WITH (INDEX(ix))"},
        });
    }

    /**
     * 回写文本归一化后必须与原文一致。
     */
    @Test
    public void formatKeepsOriginalWords() {
        SqlDialect d = SqlDialect.fromName(dialect);
        SqlStatement stmt = SQL.parse(sql, d);
        String formatted = SQL.toSqlString(stmt);
        assertEquals(sql + " -> " + formatted, normalize(sql), normalize(formatted));
    }

    private static String normalize(String s) {
        String r = s.replaceAll("--[^\\n]*", " ").replaceAll("/\\*[\\s\\S]*?\\*/", " ");
        r = r.replaceAll("(?i)\\s+AS\\s+", " ").replaceAll("(?i)\\bAS\\b", " ");
        r = r.replaceAll("(?i)\\bINNER\\b", " ").replaceAll("(?i)\\bOUTER\\b", " ");
        r = r.replaceAll("(?i)\\bASC\\b", " ");
        r = r.replaceAll("(?i)\\bTRUNCATE\\s+TABLE\\b", "TRUNCATE");
        r = r.replaceAll("\\s+", "");
        return r.toUpperCase(Locale.ROOT);
    }
}
