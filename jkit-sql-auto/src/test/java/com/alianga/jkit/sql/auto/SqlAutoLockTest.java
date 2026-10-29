package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖 P0-12：SqlAutoLock PG 分支不再无限等待，改用 pg_try_advisory_lock +
 * 客户端轮询实现 timeoutSeconds 语义。
 */
public class SqlAutoLockTest {

    /**
     * 生成一个模拟 Connection，其 prepareStatement 返回 mock PS，mock PS 的
     * executeQuery 每调用一次依次从 results 取一个值；取完后重复最后一个值。
     * 同时记录每次执行的 SQL，便于断言 PG 分支使用了 pg_try_advisory_lock。
     */
    private static Connection mockConnection(final List<String> sqls, final List<Object> results,
                                             final AtomicInteger callCount) {
        return (Connection) Proxy.newProxyInstance(
                SqlAutoLockTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("prepareStatement".equals(method.getName())) {
                            final String sql = (String) args[0];
                            synchronized (sqls) {
                                sqls.add(sql);
                            }
                            return mockPs(results, callCount);
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return defaultReturn(method.getReturnType());
                    }
                });
    }

    private static PreparedStatement mockPs(final List<Object> results, final AtomicInteger callCount) {
        return (PreparedStatement) Proxy.newProxyInstance(
                SqlAutoLockTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("executeQuery".equals(method.getName())) {
                            final int idx = callCount.getAndIncrement();
                            Object v;
                            synchronized (results) {
                                if (results.isEmpty()) {
                                    v = null;
                                } else {
                                    v = results.get(Math.min(idx, results.size() - 1));
                                }
                            }
                            return mockRs(v);
                        }
                        if ("close".equals(method.getName())) {
                            return null;
                        }
                        return defaultReturn(method.getReturnType());
                    }
                });
    }

    private static ResultSet mockRs(final Object value) {
        return (ResultSet) Proxy.newProxyInstance(
                SqlAutoLockTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                new InvocationHandler() {
                    private boolean consumed;
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String n = method.getName();
                        if ("next".equals(n)) {
                            if (!consumed) {
                                consumed = true;
                                return true;
                            }
                            return false;
                        }
                        if ("getObject".equals(n)) {
                            return value;
                        }
                        if ("close".equals(n)) {
                            return null;
                        }
                        return defaultReturn(method.getReturnType());
                    }
                });
    }

    private static Object defaultReturn(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    /**
     * MySQL 分支：单次调用 GET_LOCK，不做轮询。
     */
    @Test
    public void mysqlUsesGetLock() throws SQLException {
        List<String> sqls = new ArrayList<String>();
        List<Object> results = new ArrayList<Object>();
        results.add(1);
        AtomicInteger count = new AtomicInteger();
        Connection conn = mockConnection(sqls, results, count);

        boolean ok = SqlAutoLock.acquire(conn, SqlDialect.MYSQL, 5);
        assertTrue(ok);
        assertEquals("MySQL 只执行一次 GET_LOCK", 1, count.get());
        assertTrue("MySQL 应使用 GET_LOCK, 实际: " + sqls,
                sqls.get(0).contains("GET_LOCK"));
    }

    /**
     * MySQL GET_LOCK 返回 0（超时）→ false，不做轮询。
     */
    @Test
    public void mysqlGetLockTimeoutReturnsFalse() throws SQLException {
        List<String> sqls = new ArrayList<String>();
        List<Object> results = new ArrayList<Object>();
        results.add(0);
        AtomicInteger count = new AtomicInteger();
        Connection conn = mockConnection(sqls, results, count);

        boolean ok = SqlAutoLock.acquire(conn, SqlDialect.MYSQL, 1);
        assertFalse(ok);
        assertEquals("MySQL 不应轮询", 1, count.get());
    }

    /**
     * PG 分支：使用 pg_try_advisory_lock（而非 pg_advisory_lock），且在 timeout=0 时
     * 只尝试一次立即返回。
     */
    @Test
    public void postgresUsesTryAdvisoryLock() throws SQLException {
        List<String> sqls = new ArrayList<String>();
        List<Object> results = new ArrayList<Object>();
        results.add(false);
        AtomicInteger count = new AtomicInteger();
        Connection conn = mockConnection(sqls, results, count);

        boolean ok = SqlAutoLock.acquire(conn, SqlDialect.POSTGRES, 0);
        assertFalse(ok);
        assertEquals("timeout=0 时应只尝试一次", 1, count.get());
        assertTrue("PG 应使用 pg_try_advisory_lock，实际: " + sqls,
                sqls.get(0).contains("pg_try_advisory_lock"));
        assertFalse("PG 不应使用无限等待的 pg_advisory_lock( 直接调用",
                sqls.get(0).contains("pg_advisory_lock("));
    }

    /**
     * PG 分支：第一次返回 false，第二次返回 true → true，且轮询次数 >= 2。
     */
    @Test
    public void postgresRetriesUntilAcquired() throws SQLException {
        List<String> sqls = new ArrayList<String>();
        List<Object> results = new ArrayList<Object>();
        results.add(false);
        results.add(false);
        results.add(true);
        AtomicInteger count = new AtomicInteger();
        Connection conn = mockConnection(sqls, results, count);

        boolean ok = SqlAutoLock.acquire(conn, SqlDialect.POSTGRES, 5);
        assertTrue(ok);
        assertEquals("应轮询到第 3 次成功", 3, count.get());
    }

    /**
     * PG 分支：全部返回 false 时在 timeout 内超时（保守：把 timeout 设 1s，验证
     * 轮询次数 >= 2 但没有无限等待）。
     */
    @Test(timeout = 10000)
    public void postgresTimesOutAfterDeadline() throws SQLException {
        List<String> sqls = new ArrayList<String>();
        List<Object> results = new ArrayList<Object>();
        results.add(false);   // 永远返回 false
        AtomicInteger count = new AtomicInteger();
        Connection conn = mockConnection(sqls, results, count);

        long start = System.currentTimeMillis();
        boolean ok = SqlAutoLock.acquire(conn, SqlDialect.POSTGRES, 1);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(ok);
        assertTrue("应至少尝试 2 次（1s 内 / 200ms 轮询）: " + count.get(), count.get() >= 2);
        assertTrue("应在 timeout + 1s 缓冲内返回，实际: " + elapsed + "ms", elapsed < 2000);
    }

    /**
     * 不支持的方言（H2/Oracle/Dameng 等）直接返回 false，不执行任何 SQL。
     */
    @Test
    public void unsupportedDialectReturnsFalseWithoutSql() {
        List<String> sqls = new ArrayList<String>();
        List<Object> results = new ArrayList<Object>();
        AtomicInteger count = new AtomicInteger();
        Connection conn = mockConnection(sqls, results, count);

        assertFalse(SqlAutoLock.acquire(conn, SqlDialect.H2, 5));
        assertFalse(SqlAutoLock.acquire(conn, SqlDialect.ORACLE, 5));
        assertFalse(SqlAutoLock.acquire(conn, SqlDialect.DAMENG, 5));
        assertEquals("不支持的方言不应执行 SQL", 0, sqls.size());
    }

    /**
     * supports() 方法明确 MYSQL / POSTGRES 为 true，其它 false。
     */
    @Test
    public void supportsOnlyMysqlAndPostgres() {
        assertTrue(SqlAutoLock.supports(SqlDialect.MYSQL));
        assertTrue(SqlAutoLock.supports(SqlDialect.POSTGRES));
        assertFalse(SqlAutoLock.supports(SqlDialect.ORACLE));
        assertFalse(SqlAutoLock.supports(SqlDialect.H2));
        assertFalse(SqlAutoLock.supports(SqlDialect.SQLSERVER));
        assertFalse(SqlAutoLock.supports(SqlDialect.DAMENG));
    }

    /**
     * null connection 直接返回 false（原有行为）。
     */
    @Test
    public void nullConnectionReturnsFalse() {
        assertFalse(SqlAutoLock.acquire(null, SqlDialect.MYSQL, 5));
        assertFalse(SqlAutoLock.acquire(null, SqlDialect.POSTGRES, 5));
    }
}
