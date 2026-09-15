package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SQL#inject} / {@link SqlInjectConfig}：全局表列配置与切面取值。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class SqlInjectConfigTest {

    /**
     * 清掉线程与全局配置，避免污染其它测试。
     */
    @After
    public void tearDown() {
        SqlInject.clear();
        SqlInject.setDefault(null);
    }

    @Test
    public void injectUsesGlobalTablesAndColumns() {
        SQL.injectConfig(SqlInjectConfig.create()
                .tables("t_order", "t_item")
                .add("deleted", 0)
                .add("tenant_id", 9));
        SqlStatement out = SQL.inject(SQL.parse(
                "SELECT id FROM t_order o JOIN t_item i ON o.id = i.oid"));
        String n = SQL.toSqlString(out);
        assertTrue(n, n.contains("o.deleted = 0"));
        assertTrue(n, n.contains("i.deleted = 0"));
        assertTrue(n, n.contains("o.tenant_id = 9"));
        assertTrue(n, n.contains("i.tenant_id = 9"));
    }

    @Test
    public void injectWithoutConfigThrows() {
        try {
            SQL.inject(SQL.parse("SELECT 1"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("SqlInjectConfig"));
        }
    }

    @Test
    public void threadCurrentOverridesDefault() {
        SQL.injectConfig(SqlInjectConfig.create().tables("t_order").add("tenant_id", 1));
        SqlInject.setCurrent(SqlInjectConfig.create().tables("t_order").add("tenant_id", 2));
        String n = SQL.toSqlString(SQL.inject(SQL.parse("SELECT id FROM t_order")));
        assertTrue(n, n.contains("tenant_id = 2"));
        assertFalse(n, n.contains("tenant_id = 1"));
        SqlInject.clear();
        String back = SQL.toSqlString(SQL.inject(SQL.parse("SELECT id FROM t_order")));
        assertTrue(back, back.contains("tenant_id = 1"));
    }

    @Test
    public void valueSupplierEvaluatedAtInjectTime() {
        final AtomicInteger id = new AtomicInteger(100);
        SQL.injectConfig(SqlInjectConfig.create()
                .tables("t_order")
                .add("tenant_id", new SqlInjectValue() {
                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public Object get() {
                        return Integer.valueOf(id.get());
                    }
                }));
        assertTrue(SQL.toSqlString(SQL.inject(SQL.parse("SELECT id FROM t_order")))
                .contains("tenant_id = 100"));
        id.set(200);
        assertTrue(SQL.toSqlString(SQL.inject(SQL.parse("SELECT id FROM t_order")))
                .contains("tenant_id = 200"));
    }

    @Test
    public void perColumnTablesOverrideGlobal() {
        SqlInjectConfig cfg = SqlInjectConfig.create()
                .tables("t_order", "t_item")
                .add("tenant_id", 1)
                .add("secret_flag", 1, "t_order");
        String n = SQL.toSqlString(SQL.inject(SQL.parse(
                "SELECT id FROM t_order o JOIN t_item i ON o.id = i.oid"), cfg));
        assertTrue(n, n.contains("o.tenant_id = 1"));
        assertTrue(n, n.contains("i.tenant_id = 1"));
        assertTrue(n, n.contains("o.secret_flag = 1"));
        assertFalse(n, n.contains("i.secret_flag"));
    }

    @Test
    public void injectSingleColumn() {
        SqlStatement out = SQL.inject(SQL.parse("SELECT id FROM t_order"), "tenant_id", 7, "t_order");
        assertTrue(SQL.toSqlString(out).contains("tenant_id = 7"));
    }

    @Test
    public void rewriteHookUsesConfig() {
        SqlInjectConfig cfg = SqlInjectConfig.create().tables("t_order").add("deleted", 0);
        SqlStatement out = SQL.rewrite(SQL.parse("SELECT id FROM t_order"),
                SqlRewrites.create().add(SqlRewrites.inject(cfg)));
        assertTrue(SQL.toSqlString(out).contains("deleted = 0"));
    }
}
