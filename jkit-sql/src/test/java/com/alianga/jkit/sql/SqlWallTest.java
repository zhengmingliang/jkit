package com.alianga.jkit.sql;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SqlWall 可配置规则单测。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlWallTest {

    @Test
    public void defaultsKeepLegacyChecks() {
        assertTrue(SQL.wall("SELECT 1 FROM t WHERE id = 1").passed());
        assertTrue(SQL.wall("SELECT 1; DELETE FROM t WHERE id = 1").violations().contains("multi-statement"));
        assertTrue(SQL.wall("DELETE FROM t").violations().contains("delete-without-where"));
        assertTrue(SQL.wall("UPDATE t SET a = 1").violations().contains("update-without-where"));
        assertTrue(SQL.wall("SELECT SLEEP(5) FROM t").violations().contains("sleep-function"));
        assertTrue(SQL.wall("SELECT SLEEP(5) FROM t").violations().contains("dangerous-function"));
        assertTrue(SQL.wall("SELECT * FROM t WHERE id = 1 OR 1 = 1").violations().contains("always-true-condition"));
        assertTrue(SQL.wall("SELECT * FROM t WHERE name = 'x' --").violations().contains("comment-bypass"));
    }

    @Test
    public void denyDdl() {
        SqlWallResult drop = SQL.wall("DROP TABLE t");
        assertFalse(drop.passed());
        assertTrue(drop.violations().toString(), drop.violations().contains("deny-ddl"));

        SqlWallResult truncated = SQL.wall("TRUNCATE TABLE t");
        assertTrue(truncated.violations().contains("deny-ddl"));

        SqlWallResult alter = SQL.wall("ALTER TABLE t ADD COLUMN c INT");
        assertTrue(alter.violations().contains("deny-ddl"));

        SqlWallResult create = SQL.wall("CREATE TABLE t (id INT)");
        assertTrue(create.violations().contains("deny-ddl"));

        SqlWallConfig allow = SqlWallConfig.defaults().denyDdl(false);
        assertTrue(SQL.wall("DROP TABLE t", SqlDialect.MYSQL, allow).passed());
    }

    @Test
    public void denyDangerousFunctions() {
        assertTrue(SQL.wall("SELECT BENCHMARK(1000000, SHA1('x'))").violations().contains("dangerous-function"));
        assertTrue(SQL.wall("SELECT LOAD_FILE('/etc/passwd')").violations().contains("dangerous-function"));

        SqlWallConfig allow = SqlWallConfig.defaults().denyDangerousFunctions(false);
        assertTrue(SQL.wall("SELECT SLEEP(1)", SqlDialect.MYSQL, allow).passed());
    }

    @Test
    public void denyIntoOutfile() {
        SqlWallResult r = SQL.wall("SELECT a, b INTO OUTFILE '/tmp/a.csv' FROM t");
        assertTrue(r.violations().toString(), r.violations().contains("into-outfile"));

        SqlWallResult dump = SQL.wall("SELECT * FROM t LIMIT 10 INTO DUMPFILE '/tmp/c.bin'");
        assertTrue(dump.violations().contains("into-outfile"));

        SqlWallConfig allow = SqlWallConfig.defaults().denyIntoOutfile(false);
        assertTrue(SQL.wall("SELECT a INTO OUTFILE '/tmp/a.csv' FROM t", SqlDialect.MYSQL, allow).passed());
    }

    @Test
    public void denyUnionOptional() {
        String unionSql = "SELECT id FROM a UNION SELECT id FROM b";
        assertTrue("legitimate UNION must pass when denyUnion=false",
                SQL.wall(unionSql).passed());

        SqlWallConfig strict = SqlWallConfig.defaults().denyUnion(true);
        SqlWallResult denied = SQL.wall(unionSql, SqlDialect.MYSQL, strict);
        assertFalse(denied.passed());
        assertTrue(denied.violations().contains("deny-union"));
    }

    @Test
    public void denyInformationSchemaOptional() {
        String sql = "SELECT * FROM information_schema.tables";
        assertTrue(SQL.wall(sql).passed());

        SqlWallConfig cfg = SqlWallConfig.defaults().denyInformationSchema(true);
        SqlWallResult r = SQL.wall(sql, SqlDialect.MYSQL, cfg);
        assertTrue(r.violations().toString(), r.violations().contains("information-schema"));
    }

    @Test
    public void selectOnlyMode() {
        SqlWallConfig cfg = SqlWallConfig.defaults().selectOnly(true);
        assertTrue(SQL.wall("SELECT 1 FROM t", SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("WITH c AS (SELECT 1 AS x) SELECT * FROM c", SqlDialect.MYSQL, cfg).passed());

        SqlWallResult ins = SQL.wall("INSERT INTO t VALUES (1)", SqlDialect.MYSQL, cfg);
        assertTrue(ins.violations().contains("select-only"));

        SqlWallResult del = SQL.wall("DELETE FROM t WHERE id = 1", SqlDialect.MYSQL, cfg);
        assertTrue(del.violations().contains("select-only"));
    }

    @Test
    public void configNoneDisablesAll() {
        SqlWallConfig none = SqlWallConfig.none();
        assertTrue(SQL.wall("SELECT 1; DROP TABLE t", SqlDialect.MYSQL, none).passed());
        assertTrue(SQL.wall("DELETE FROM t", SqlDialect.MYSQL, none).passed());
        assertTrue(SQL.wall("SELECT SLEEP(1)", SqlDialect.MYSQL, none).passed());
    }
}
