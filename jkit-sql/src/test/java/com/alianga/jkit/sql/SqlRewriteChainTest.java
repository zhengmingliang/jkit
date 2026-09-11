package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SqlRewrites} 改写规则链：按添加顺序执行、内建适配器与自定义规则可混排（前/后 hook）、
 * 门面 {@link SQL#rewrite} 先深拷贝再改。
 *
 * @author 郑明亮
 */
public class SqlRewriteChainTest {

    private static String compact(SqlStatement stmt) {
        return SQL.format(stmt, SqlDialect.MYSQL, false);
    }

    @Test
    public void rulesRunInAddOrder() {
        final List<String> order = new ArrayList<String>();
        SqlRewrites chain = SqlRewrites.create()
                .add(new OrderMarker(order, "first"))
                .add(new OrderMarker(order, "second"))
                .add(new OrderMarker(order, "third"));
        SQL.rewrite(SQL.parse("SELECT 1"), chain);
        assertEquals(3, order.size());
        assertEquals("first,second,third", join(order));
    }

    /** 记录执行顺序的规则。 */
    private static final class OrderMarker implements SqlRewriteHook {
        private final List<String> sink;
        private final String name;

        OrderMarker(List<String> sink, String name) {
            this.sink = sink;
            this.name = name;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public SqlStatement apply(SqlStatement statement) {
            sink.add(name);
            return statement;
        }
    }

    @Test
    public void builtinAdaptersCompose() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE age > 18");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.replaceTable("t_user", "t_user_2026"))
                .add(SqlRewrites.replaceColumn("name", "user_name"))
                .add(SqlRewrites.andWhere(SQL.parseExpr("tenant_id = 1")))
                .add(SqlRewrites.addLimit(100, SqlDialect.MYSQL)));
        String sql = compact(out);
        assertTrue(sql, sql.contains("FROM t_user_2026"));
        assertTrue(sql, sql.contains("user_name"));
        assertTrue(sql, sql.contains("tenant_id = 1"));
        assertTrue(sql, sql.contains("LIMIT 100"));
    }

    @Test
    public void rewriteClonesOriginalTree() {
        SqlStatement stmt = SQL.parse("SELECT id FROM t_user");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.setPage(3, 20, SqlDialect.MYSQL)));
        assertNotSame(stmt, out);
        // 原 AST 不动
        assertFalse(compact(stmt).contains("LIMIT"));
        assertTrue(compact(stmt).contains("FROM t_user"));
        // 改写结果：pageNo=3, pageSize=20 → rowCount=20, offset=40
        assertEquals(Long.valueOf(20L), SQL.getLimit(out));
        assertEquals(Long.valueOf(40L), SQL.getOffset(out));
    }

    @Test
    public void setPageAndOffsetAdaptersWork() {
        SqlStatement stmt = SQL.parse("SELECT id FROM t");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.setPage(2, 10, SqlDialect.MYSQL))
                .add(SqlRewrites.setOffset(5, SqlDialect.MYSQL)));
        // setOffset 在有 LIMIT 时保留行数
        assertEquals(Long.valueOf(10L), SQL.getLimit(out));
        assertEquals(Long.valueOf(5L), SQL.getOffset(out));
    }

    @Test
    public void hookMayReplaceStatementEntirely() {
        final SqlStatement replacement = SQL.parse("SELECT 42");
        SqlStatement stmt = SQL.parse("SELECT id FROM t");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(new SqlRewriteHook() {
                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public SqlStatement apply(SqlStatement statement) {
                        return replacement;
                    }
                })
                .add(SqlRewrites.addLimit(7, SqlDialect.MYSQL)));
        assertEquals("SELECT 42 LIMIT 7", compact(out));
    }

    @Test
    public void positionMattersBeforeAndAfterBuiltin() {
        // 前 hook 先补 LIMIT 5，内建 addLimit(100) 因已存在而不动
        SqlStatement a = SQL.parse("SELECT id FROM t");
        String beforeWins = compact(SQL.rewrite(a, SqlRewrites.create()
                .add(SqlRewrites.addLimit(5, SqlDialect.MYSQL))
                .add(SqlRewrites.addLimit(100, SqlDialect.MYSQL))));
        assertTrue(beforeWins, beforeWins.contains("LIMIT 5"));

        // 后 hook 用 setLimit 覆盖内建 addLimit 的结果
        SqlStatement b = SQL.parse("SELECT id FROM t");
        String afterWins = compact(SQL.rewrite(b, SqlRewrites.create()
                .add(SqlRewrites.addLimit(100, SqlDialect.MYSQL))
                .add(SqlRewrites.setLimit(5, SqlDialect.MYSQL))));
        assertTrue(afterWins, afterWins.contains("LIMIT 5"));
    }

    @Test
    public void nullHookResultFails() {
        SqlStatement stmt = SQL.parse("SELECT 1");
        try {
            SQL.rewrite(stmt, SqlRewrites.create().add(new SqlRewriteHook() {
                /**
                 * {@inheritDoc}
                 */
                @Override
                public SqlStatement apply(SqlStatement statement) {
                    return null;
                }
            }));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("null"));
        }
    }

    @Test
    public void nullOrEmptyChainReturnsStatementUntouched() {
        SqlStatement stmt = SQL.parse("SELECT 1");
        assertSame(stmt, SQL.rewrite(stmt, null));
        assertSame(stmt, SQL.rewrite(stmt, SqlRewrites.none()));
        // null 语句直接返回 null，不进链
        assertNull(SQL.rewrite(null, SqlRewrites.create()
                .add(SqlRewrites.addLimit(1, SqlDialect.MYSQL))));
    }

    @Test
    public void registrySemantics() {
        try {
            SqlRewrites.create().add(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("required"));
        }
        SqlRewrites chain = SqlRewrites.create()
                .add(SqlRewrites.addLimit(1, SqlDialect.MYSQL))
                .add(SqlRewrites.setLimit(2, SqlDialect.MYSQL));
        assertEquals(2, chain.hooks().size());
        try {
            chain.hooks().add(SqlRewrites.setLimit(3, SqlDialect.MYSQL));
            fail("expected unmodifiable list");
        } catch (UnsupportedOperationException ex) {
            // 只读视图
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    @Test
    public void adaptPaginationAndSelectItemHooks() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE age > 18 limit 0,10000");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.removeSelectItem("name"))
                .add(SqlRewrites.addSelectItem("status"))
                .add(SqlRewrites.adaptPagination(SqlDialect.ORACLE)));
        String sql = SQL.toSqlString(out, SqlDialect.ORACLE).toUpperCase();
        assertTrue(sql, sql.contains("ROWNUM"));
        assertFalse(sql, sql.contains("LIMIT"));
        assertFalse(sql, sql.contains("NAME"));
        assertTrue(sql, sql.contains("STATUS"));
        assertTrue(sql, sql.contains("ID"));
        // 原树不变
        assertTrue(SQL.toSqlString(stmt).toUpperCase().contains("LIMIT"));
        assertTrue(SQL.toSqlString(stmt).toUpperCase().contains("NAME"));
    }

}
