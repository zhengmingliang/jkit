package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 黄金 SQL 集：必须能解析。覆盖 Druid / JSqlParser 文档和国内业务里的常见写法。
 *
 * @author 郑明亮
 */
@RunWith(Parameterized.class)
public class SqlGoldenCorpusTest {
    private final String dialect;
    private final String sql;

    /**
     * @param dialect 方言名
     * @param sql SQL
     */
    public SqlGoldenCorpusTest(String dialect, String sql) {
        this.dialect = dialect;
        this.sql = sql;
    }

    /**
     * @return 用例
     */
    @Parameterized.Parameters(name = "{0} :: {1}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {
                {"mysql", "SELECT 1"},
                {"mysql", "SELECT 1 FROM dual"},
                {"mysql", "SELECT * FROM t"},
                {"mysql", "SELECT t.* FROM t"},
                {"mysql", "SELECT DISTINCT a, b FROM t"},
                {"mysql", "SELECT a AS x, b y FROM t"},
                {"mysql", "SELECT COUNT(*), COUNT(DISTINCT id), SUM(amt) FROM t"},
                {"mysql", "SELECT * FROM t WHERE id BETWEEN 1 AND 10"},
                {"mysql", "SELECT * FROM t WHERE name LIKE 'a%' ESCAPE '/'"},
                {"mysql", "SELECT * FROM t WHERE name NOT LIKE 'a%'"},
                {"mysql", "SELECT * FROM t WHERE id NOT IN (1, 2, 3)"},
                {"mysql", "SELECT * FROM t WHERE id IS NULL AND name IS NOT NULL"},
                {"mysql", "SELECT * FROM t WHERE a AND NOT b OR c XOR d"},
                {"mysql", "SELECT * FROM t FORCE INDEX (idx_id) WHERE id = 1"},
                {"mysql", "SELECT * FROM t FORCE INDEX FOR JOIN (idx_id) WHERE id = 1"},
                {"mysql", "SELECT * FROM t USE INDEX FOR ORDER BY (idx_a) ORDER BY a"},
                {"mysql", "SELECT a FROM t IGNORE INDEX FOR GROUP BY (idx_a) GROUP BY a"},
                {"postgres", "SELECT * FROM t WHERE tags @> ARRAY['vip']"},
                {"postgres", "SELECT * FROM t WHERE tsrange(a, b) @> NOW()"},
                {"postgres", "SELECT * FROM t WHERE ARRAY[1] <@ tags"},
                {"postgres", "SELECT * FROM t WHERE name ~ '^[A-Z]'"},
                {"postgres", "SELECT * FROM t WHERE name ~* 'foo'"},
                {"postgres", "SELECT * FROM t WHERE name !~ 'bar'"},
                {"mysql", "SELECT * FROM a INNER JOIN b ON a.id = b.aid"},
                {"mysql", "SELECT * FROM a LEFT OUTER JOIN b ON a.id = b.aid"},
                {"mysql", "SELECT * FROM a RIGHT JOIN b USING (id)"},
                {"mysql", "SELECT * FROM a CROSS JOIN b"},
                {"mysql", "SELECT * FROM a NATURAL JOIN b"},
                {"mysql", "SELECT * FROM a STRAIGHT_JOIN b ON a.id = b.id"},
                {"mysql", "SELECT * FROM a, b, c WHERE a.id = b.id"},
                {"mysql", "SELECT * FROM (SELECT id FROM t) x"},
                {"mysql", "SELECT * FROM t WHERE id IN (SELECT id FROM s)"},
                {"mysql", "SELECT * FROM t WHERE EXISTS (SELECT 1 FROM s WHERE s.id = t.id)"},
                {"mysql", "SELECT * FROM t WHERE (a, b) IN ((1, 2), (3, 4))"},
                {"mysql", "SELECT CASE WHEN a > 0 THEN 1 WHEN a < 0 THEN -1 ELSE 0 END FROM t"},
                {"mysql", "SELECT CASE a WHEN 1 THEN 'x' ELSE 'y' END FROM t"},
                {"mysql", "SELECT CAST(id AS CHAR), id + 1, id DIV 2 FROM t"},
                {"mysql", "SELECT * FROM t GROUP BY a, b WITH ROLLUP"},
                {"mysql", "SELECT a, COUNT(*) FROM t GROUP BY a HAVING COUNT(*) > 1"},
                {"mysql", "SELECT * FROM t ORDER BY a DESC, b ASC LIMIT 10 OFFSET 20"},
                {"mysql", "SELECT * FROM t LIMIT 5, 10"},
                {"mysql", "SELECT * FROM t FOR UPDATE"},
                {"mysql", "SELECT * FROM t FOR UPDATE OF a, b SKIP LOCKED"},
                {"mysql", "SELECT * FROM t FOR UPDATE NOWAIT"},
                {"mysql", "SELECT * FROM t LOCK IN SHARE MODE"},
                {"mysql", "SELECT 1 UNION SELECT 2 UNION ALL SELECT 3"},
                {"mysql", "INSERT INTO t VALUES (1, 'a')"},
                {"mysql", "INSERT INTO t SELECT * FROM t"},
                {"mysql", "INSERT INTO t (id, name) VALUES (1, 'a'), (2, 'b')"},
                {"mysql", "INSERT INTO t SET id = 1, name = 'a'"},
                {"mysql", "INSERT IGNORE INTO t SELECT * FROM s"},
                {"mysql", "INSERT INTO t (id) VALUES (1) ON DUPLICATE KEY UPDATE id = id + 1"},
                {"mysql", "REPLACE INTO t (id, name) VALUES (1, 'a')"},
                {"mysql", "UPDATE t SET a = 1, b = b + 1 WHERE id = 1 ORDER BY id LIMIT 1"},
                {"mysql", "UPDATE t JOIN s ON t.id = s.id SET t.a = s.a"},
                {"mysql", "DELETE FROM t WHERE id = 1 LIMIT 1"},
                {"mysql", "DELETE t FROM t JOIN s ON t.id = s.id WHERE s.flag = 1"},
                {"mysql", "CREATE TABLE IF NOT EXISTS t (id INT PRIMARY KEY, name VARCHAR(32) NOT NULL)"},
                {"mysql", "CREATE TABLE t (id INT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='x'"},
                {"mysql", "CREATE TABLE t AS SELECT * FROM s"},
                {"mysql", "DROP TABLE IF EXISTS t, s"},
                {"mysql", "TRUNCATE TABLE t"},
                {"mysql", "ALTER TABLE t ADD COLUMN c INT"},
                {"mysql", "ALTER TABLE t DROP COLUMN c"},
                {"mysql", "ALTER TABLE t ADD INDEX idx_a (a, b)"},
                {"mysql", "ALTER TABLE t DROP INDEX idx_a"},
                {"mysql", "ALTER TABLE t RENAME TO t2"},
                {"mysql", "CREATE INDEX idx_name ON t (name)"},
                {"mysql", "EXPLAIN SELECT * FROM t WHERE id = 1"},
                {"mysql", "DESC t"},
                {"mysql", "SHOW TABLES"},
                {"mysql", "SHOW COLUMNS FROM t"},
                {"mysql", "SHOW CREATE TABLE abc"},
                {"mysql", "SHOW INDEX FROM t"},
                {"mysql", "SET names utf8mb4"},
                {"mysql", "SET @x = 1"},
                {"mysql", "USE app_db"},
                {"mysql", "SELECT * FROM t WHERE id = ? AND name = ?"},
                {"mysql", "-- line comment\nSELECT 1 /* block */ FROM t"},
                {"mysql", "SELECT `select` FROM `table` WHERE `from` = 1"},
                {"mysql", "SELECT * FROM t WHERE name = 'it''s ok'"},
                {"mysql", "WITH w AS (SELECT id FROM t) SELECT * FROM w"},
                {"mysql", "WITH RECURSIVE r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 5) "
                        + "SELECT * FROM r"},
                {"mysql", "SELECT ROW_NUMBER() OVER (PARTITION BY a ORDER BY b) FROM t"},
                {"mysql", "SELECT SUM(amt) OVER (ORDER BY d ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) FROM t"},
                {"mysql", "SELECT EXTRACT(YEAR FROM dt), TRIM(BOTH FROM name) FROM t"},
                {"mysql", "SELECT * FROM information_schema.CLUSTER_TABLE_SEGMENTS "
                        + "WHERE table_schema = 'eoai' AND table_name = 'fct_agt_savinf'"},
                {"postgres", "SELECT * FROM t WHERE a || b = 'ab'"},
                {"postgres", "SELECT * FROM t WHERE name ILIKE 'a%'"},
                {"postgres", "SELECT DISTINCT ON (id) * FROM t ORDER BY id, ts DESC"},
                {"postgres", "SELECT * FROM t LIMIT 10 OFFSET 5"},
                {"postgres", "SELECT * FROM t OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY"},
                {"postgres", "INSERT INTO t (id, name) VALUES (1, 'a') ON CONFLICT (id) DO NOTHING"},
                {"postgres", "INSERT INTO t (id, name) VALUES (1, 'a') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name"},
                {"postgres", "UPDATE t SET n = n + 1 WHERE id = 1 RETURNING *"},
                {"postgres", "DELETE FROM t WHERE id = 1 RETURNING id"},
                {"postgres", "SELECT id::int, created_at::date FROM t"},
                {"postgres", "SELECT COUNT(*) FILTER (WHERE active) FROM t"},
                {"oracle", "SELECT * FROM t WHERE ROWNUM <= 10"},
                {"oracle", "SELECT * FROM dual"},
                {"oracle", "SELECT * FROM t START WITH pid IS NULL CONNECT BY pid = PRIOR id"},
                {"oracle", "SELECT a FROM t MINUS SELECT a FROM s"},
                {"sqlserver", "SELECT TOP 10 * FROM t ORDER BY id"},
                {"sqlserver", "SELECT * FROM [dbo].[user] WHERE [id] = 1"},
                {"ansi", "SELECT * FROM \"User\" WHERE \"Id\" = 1"},
                {"mysql", "SELECT id, SUM(x) OVER w FROM t WINDOW w AS (PARTITION BY a ORDER BY b)"},
                {"mysql", "SELECT SUM(x) OVER w1, AVG(x) OVER w2 FROM t "
                        + "WINDOW w1 AS (PARTITION BY a), w2 AS (ORDER BY b)"},
                {"postgres", "SELECT * FROM t, LATERAL (SELECT id FROM s WHERE s.tid = t.id) x"},
                {"postgres", "SELECT * FROM t LEFT JOIN LATERAL (SELECT 1 AS n) x ON true"},
                {"sqlserver", "SELECT * FROM a CROSS APPLY (SELECT TOP 1 id FROM b WHERE b.aid = a.id) x"},
                {"sqlserver", "SELECT * FROM a OUTER APPLY (SELECT id FROM b WHERE b.aid = a.id) x"},
                {"mysql", "SELECT SUM(x) OVER w2 FROM t WINDOW w AS (PARTITION BY a), w2 AS (w ORDER BY b)"},
                {"postgres", "SELECT RANK() OVER w2 FROM t WINDOW w AS (ORDER BY a), w2 AS (w)"},
                {"postgres", "SELECT * FROM UNNEST(arr) AS u(x)"},
                {"oracle", "SELECT * FROM TABLE(fn(1, 2)) t"},
                {"postgres", "SELECT * FROM (VALUES (1), (2)) AS v(id)"},
                {"postgres", "SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS v(id, name)"},
                {"mysql", "SELECT GROUP_CONCAT(name ORDER BY id SEPARATOR ',') FROM t"},
                {"mysql", "SELECT GROUP_CONCAT(DISTINCT name SEPARATOR ';') FROM t"},
                {"postgres", "SELECT STRING_AGG(name, ',' ORDER BY id) FROM t"},
                {"postgres", "SELECT STRING_AGG(name, ',') WITHIN GROUP (ORDER BY id) FROM t"},
                {"mysql", "SELECT IF(a > 0, 'y', 'n') FROM t"},
                {"mysql", "SELECT CONVERT(name USING utf8mb4) FROM t"},
                {"sqlserver", "SELECT CONVERT(varchar(10), name) FROM t"},
                {"postgres", "SELECT data -> 'a', data ->> 'b' FROM t"},
                {"postgres", "SELECT data #> '{a}', data #>> '{a,b}' FROM t"},
                {"mysql", "SELECT * FROM t WHERE MATCH(title, body) AGAINST ('foo' IN BOOLEAN MODE)"},
                {"mysql", "SELECT * FROM t WHERE MATCH(title) AGAINST ('bar')"},
                {"postgres", "SELECT * FROM t WHERE a IS DISTINCT FROM b"},
                {"postgres", "SELECT * FROM t WHERE a IS NOT DISTINCT FROM b"},
                {"postgres", "SELECT arr[1], arr[2][3] FROM t"},
                {"postgres", "SELECT * FROM t WHERE x = ANY(SELECT id FROM s)"},
                {"postgres", "SELECT * FROM t WHERE x = SOME(arr)"},
                {"postgres", "SELECT * FROM t WHERE x <> ALL(arr)"},
                {"postgres", "SELECT INTERVAL '1 day', X'FF'"},
                {"mysql", "SELECT INTERVAL 1 DAY, 0xFF, X'AB'"},
                {"oracle", "INSERT ALL INTO t1 (id) VALUES (id) INTO t2 (id) VALUES (id) SELECT id FROM src"},
                {"oracle", "INSERT FIRST WHEN id > 0 THEN INTO t (id) VALUES (id) ELSE INTO t0 (id) VALUES (id) SELECT id FROM src"},
                {"postgres", "INSERT INTO t (id, name) SELECT id, name FROM s ON CONFLICT (id) DO NOTHING"},
                {"postgres", "INSERT INTO t (id) SELECT id FROM s ON CONFLICT ON CONSTRAINT t_pkey DO UPDATE SET id = EXCLUDED.id"},
                {"postgres", "UPDATE t SET a = s.a FROM s WHERE t.id = s.id"},
                {"postgres", "UPDATE t SET a = s.a FROM s JOIN u ON s.uid = u.id WHERE t.id = s.id"},
                {"postgres", "DELETE FROM t USING s WHERE t.id = s.id"},
                {"postgres", "DELETE FROM t USING s, u WHERE t.id = s.id AND s.uid = u.id"},
                {"mysql", "DELETE t USING t JOIN s ON t.id = s.id WHERE s.flag = 1"},
                {"ansi", "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND t.flag = 1 THEN UPDATE SET t.a = s.a WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a)"},
                {"ansi", "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.a = s.a WHEN NOT MATCHED BY SOURCE THEN DELETE"},
                {"sqlserver", "INSERT INTO t (id) OUTPUT INSERTED.id VALUES (1)"},
                {"sqlserver", "UPDATE t SET name = 'x' OUTPUT INSERTED.name, DELETED.name WHERE id = 1"},
                {"sqlserver", "DELETE FROM t OUTPUT DELETED.* WHERE id = 1"},
                {"sqlserver", "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET t.a = s.a WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a) OUTPUT INSERTED.*, DELETED.*"},
                {"mysql", "CREATE VIEW v_user AS SELECT id, name FROM t"},
                {"mysql", "CREATE OR REPLACE VIEW v_user AS SELECT id, name FROM t WHERE active = 1"},
                {"mysql", "CREATE PROCEDURE sp_add(IN a INT) BEGIN SELECT a; END"},
                {"mysql", "CREATE FUNCTION fn_one() RETURNS INT RETURN 1"},
                {"mysql", "CREATE TRIGGER trg_bi BEFORE INSERT ON t FOR EACH ROW SET NEW.id = NEW.id"},
                {"mysql", "CREATE EVENT ev_daily ON SCHEDULE EVERY 1 DAY DO SELECT 1"},
                {"mysql", "BEGIN SELECT 1; END"},
                {"mysql", "DECLARE x INT DEFAULT 0"},
                {"mysql", "CALL sp_add(1, 'x')"},
                {"mysql", "CALL sp_noop()"},
                {"mysql", "ANALYZE TABLE t"},
                {"mysql", "OPTIMIZE TABLE t, s"},
                {"mysql", "REPAIR TABLE t"},
                {"mysql", "CHECK TABLE t"},
                {"postgres", "VACUUM ANALYZE t"},
                {"postgres", "ANALYZE t"},
                {"postgres", "COMMENT ON TABLE t IS 'users'"},
                {"postgres", "COMMENT ON COLUMN t.id IS 'pk'"},
                {"sqlserver", "SELECT TOP 3 * FROM t ORDER BY id"},
                {"mysql", "/*!40101 SET NAMES utf8 */"},
                {"mysql", "SELECT /*!50000 DISTINCT */ id FROM t"},
                {"mysql", "SELECT /*+ INDEX(t idx_id) */ id FROM t"},
                {"mysql", "SELECT id FROM t /*+ INDEX(t idx_name) */ WHERE id = 1"},
                {"mysql", "SELECT 1 || 0 AS flag"},
                {"postgres", "INSERT INTO t (id, name) VALUES (1, 'a') RETURNING id, name"},
                {"postgres", "UPDATE t SET name = 'x' WHERE id = 1 RETURNING id, name"},
                {"postgres", "DELETE FROM t WHERE id = 1 RETURNING id, name"},
                {"oracle", "SELECT * FROM t FETCH FIRST 10 ROWS ONLY"},
                {"oracle", "SELECT id FROM emp ORDER BY id FETCH FIRST 5 ROWS ONLY"},
                {"sqlserver", "SELECT * FROM a OUTER APPLY (SELECT id FROM b WHERE b.aid = a.id) y"},
        });
    }

    /**
     * 每条黄金 SQL 都能解析，且能回写成非空文本。
     */
    @Test
    public void parsesAndFormats() {
        SqlDialect d = SqlDialect.fromName(dialect);
        SqlStatement stmt = SQL.parse(sql, d);
        assertNotNull(sql, stmt);
        String out = SQL.toSqlString(stmt, d);
        assertTrue(sql + " => " + out, out != null && out.length() > 0);
    }

    /**
     * P0.2：parse → format → parse 语义往返。不要求空白/注释一致，但 type / tables / isReadOnly 必须稳定。
     */
    @Test
    public void formatRoundTripPreservesSemantics() {
        SqlDialect d = SqlDialect.fromName(dialect);
        SqlStatement first = SQL.parse(sql, d);
        String formatted = SQL.toSqlString(first, d);
        SqlStatement second = SQL.parse(formatted, d);
        assertEquals(sql + " type after format: " + formatted, first.type(), second.type());
        assertEquals(sql + " isReadOnly after format: " + formatted,
                first.isReadOnly(), second.isReadOnly());
        assertEquals(sql + " tables after format: " + formatted,
                normalizeTables(SQL.tables(first)), normalizeTables(SQL.tables(second)));
    }

    private static List<String> normalizeTables(List<String> tables) {
        List<String> out = new ArrayList<String>(tables.size());
        for (int i = 0; i < tables.size(); i++) {
            out.add(tables.get(i).toLowerCase(Locale.ROOT));
        }
        Collections.sort(out);
        return out;
    }
}
