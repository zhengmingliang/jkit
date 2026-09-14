package com.alianga.jkit.sql;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wall 表黑白名单、WHERE 必含列、表数量上限、恒真 LIKE / XOR。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class SqlWallPolicyTest {

    @Test
    public void denyTablesBlacklist() {
        SqlWallConfig cfg = SqlWallConfig.defaults().denyTables("mysql.user", "secret");
        assertTrue(SQL.wall("SELECT id FROM t_order", SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT * FROM secret", SqlDialect.MYSQL, cfg).violations().contains("deny-table"));
        assertTrue(SQL.wall("SELECT * FROM mysql.user", SqlDialect.MYSQL, cfg).violations()
                .contains("deny-table"));
        assertTrue(SQL.wall("INSERT INTO SECRET (id) VALUES (1)", SqlDialect.MYSQL, cfg)
                .violations().contains("deny-table"));
    }

    @Test
    public void allowTablesWhitelist() {
        SqlWallConfig cfg = SqlWallConfig.defaults().allowTables("t_order", "t_item");
        assertTrue(SQL.wall("SELECT id FROM t_order o JOIN t_item i ON o.id = i.oid",
                SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT id FROM t_user", SqlDialect.MYSQL, cfg).violations()
                .contains("allow-table"));
        assertTrue(SQL.wall("SELECT 1", SqlDialect.MYSQL, cfg).passed());
    }

    @Test
    public void allowTablesSkipsCteName() {
        SqlWallConfig cfg = SqlWallConfig.defaults().allowTables("t_order");
        assertTrue(SQL.wall("WITH w AS (SELECT id FROM t_order WHERE tenant_id = 1) SELECT id FROM w",
                SqlDialect.MYSQL, cfg).passed());
    }

    @Test
    public void requireWhereColumnOnSelectUpdateDelete() {
        SqlWallConfig cfg = SqlWallConfig.defaults().requireWhereColumns("tenant_id");
        assertTrue(SQL.wall("SELECT id FROM t_order WHERE tenant_id = 1", SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT id FROM t_order o JOIN t_item i ON o.tenant_id = i.tenant_id",
                SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT id FROM t_order", SqlDialect.MYSQL, cfg).violations()
                .contains("missing-where-column"));
        assertTrue(SQL.wall("UPDATE t_order SET x = 1 WHERE id = 1", SqlDialect.MYSQL, cfg)
                .violations().contains("missing-where-column"));
        assertTrue(SQL.wall("UPDATE t_order SET x = 1 WHERE tenant_id = 1 AND id = 1",
                SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("DELETE FROM t_order WHERE tenant_id = 9", SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT 1", SqlDialect.MYSQL, cfg).passed());
    }

    @Test
    public void requireWhereColumnLooksAtSubquery() {
        SqlWallConfig cfg = SqlWallConfig.defaults().requireWhereColumns("tenant_id");
        assertTrue(SQL.wall(
                "SELECT id FROM t_user WHERE id IN (SELECT uid FROM t_order WHERE tenant_id = 1) "
                        + "AND tenant_id = 1",
                SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall(
                "SELECT id FROM t_user WHERE id IN (SELECT uid FROM t_order) AND tenant_id = 1",
                SqlDialect.MYSQL, cfg).violations().contains("missing-where-column"));
    }

    @Test
    public void maxTablesCountsPhysicalSources() {
        SqlWallConfig cfg = SqlWallConfig.defaults().maxTables(2);
        assertTrue(SQL.wall("SELECT * FROM a JOIN b ON a.id = b.id", SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT * FROM a JOIN b ON a.id = b.id JOIN c ON b.id = c.id",
                SqlDialect.MYSQL, cfg).violations().contains("too-many-tables"));
        assertTrue(SQL.wall("SELECT * FROM a WHERE id IN (SELECT id FROM b UNION SELECT id FROM c)",
                SqlDialect.MYSQL, cfg).violations().contains("too-many-tables"));
    }

    @Test
    public void likePercentIsAlwaysTrue() {
        assertTrue(SQL.wall("SELECT * FROM t WHERE name LIKE '%'").violations()
                .contains("always-true-condition"));
        assertTrue(SQL.wall("SELECT * FROM t WHERE name LIKE '%x%'").passed());
    }

    @Test
    public void xorTautology() {
        assertTrue(SQL.wall("SELECT * FROM t WHERE id = 1 XOR 1 = 1").violations()
                .contains("always-true-condition"));
    }

    @Test
    public void stringEqTautologyInOr() {
        assertTrue(SQL.wall("SELECT * FROM t WHERE name = 'x' OR 'a' = 'a'").violations()
                .contains("always-true-condition"));
    }

    @Test
    public void defaultsDoNotEnablePolicy() {
        assertTrue(SQL.wall("SELECT * FROM mysql.user").passed());
        assertTrue(SQL.wall("SELECT * FROM a JOIN b ON a.id = b.id JOIN c ON b.id = c.id").passed());
    }

    @Test
    public void noneClearsPolicy() {
        SqlWallConfig cfg = SqlWallConfig.none().denyTables("secret").allowTables("t").maxTables(1)
                .requireWhereColumns("tenant_id");
        // none() resets; subsequent setters re-enable. Prove none() itself is empty:
        SqlWallConfig empty = SqlWallConfig.none();
        assertTrue(empty.denyTables().isEmpty());
        assertTrue(empty.allowTables().isEmpty());
        assertTrue(empty.requireWhereColumns().isEmpty());
        assertTrue(empty.maxTables() == 0);
        assertTrue(SQL.wall("SELECT * FROM secret", SqlDialect.MYSQL, empty).passed());
        assertTrue(cfg.denyTables().contains("secret"));
    }
}
