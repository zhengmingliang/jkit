package com.alianga.jkit.sql.auto;

import com.alianga.jkit.log.Log;
import com.alianga.jkit.sql.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 多实例并发冷启动保护：执行 DDL 前在当前连接上取一个会话级元数据锁，
 * 让同时启动的实例串行化，避免「都读到表不存在、都去 CREATE」的竞态。
 *
 * <p>按方言选择实现：MySQL {@code GET_LOCK} / {@code RELEASE_LOCK}，
 * PostgreSQL 系 {@code pg_advisory_lock} / {@code pg_advisory_unlock}。
 * 不支持的方言（H2、Oracle、达梦等）跳过并返回 {@code false}，行为与
 * {@code lock(false)} 一致。锁绑定在用于执行 DDL 的同一连接上，
 * 连接关闭即释放，调用方仍建议显式 {@link #release}。</p>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlAutoLock {
    private static final Log LOG = Log.get(SqlAutoLock.class);

    /** MySQL / PG 共用的锁键（PG 侧用 hashtext 转成整数）。 */
    static final String LOCK_KEY = "jkit_sql_auto";

    private SqlAutoLock() {
    }

    /**
     * 判断方言是否支持元数据锁。
     *
     * @param dialect 方言
     * @return 支持返回 {@code true}
     */
    public static boolean supports(SqlDialect dialect) {
        return dialect == SqlDialect.MYSQL || dialect == SqlDialect.POSTGRES;
    }

    /**
     * PG 轮询间隔毫秒（{@link #acquire} 在 PG 分支用 {@code pg_try_advisory_lock}
     * 立即返回 + 客户端轮询实现 timeout 语义）。
     */
    private static final long PG_POLL_INTERVAL_MS = 200L;

    /**
     * 在连接上取锁。
     *
     * <p>MySQL 分支：{@code SELECT GET_LOCK(key, timeoutSeconds)}，服务器端超时。</p>
     *
     * <p>PostgreSQL 分支：原实现用 {@code pg_advisory_lock(hashtext(...))} 是无限等待，
     * 完全忽略 {@code timeoutSeconds}，导致锁被异常持有的实例上启动线程会永久阻塞，
     * 调用方拿到的 {@code false} 永远不会触发。本实现改用
     * {@code pg_try_advisory_lock(hashtext(...))}（立即返回 true/false）+ 客户端轮询，
     * 在 {@code timeoutSeconds} 内最多尝试 {@code timeoutSeconds * 1000 / PG_POLL_INTERVAL_MS}
     * 次；任何一次成功即返回 true，超时返回 false。</p>
     *
     * @param connection 连接（应与后续执行 DDL 的连接相同）
     * @param dialect 方言
     * @param timeoutSeconds 取锁等待秒数
     * @return 取到返回 {@code true}；方言不支持或取锁失败返回 {@code false}
     */
    public static boolean acquire(Connection connection, SqlDialect dialect, int timeoutSeconds) {
        if (connection == null || !supports(dialect)) {
            return false;
        }
        if (dialect == SqlDialect.MYSQL) {
            String sql = "SELECT GET_LOCK('" + LOCK_KEY + "', " + timeoutSeconds + ")";
            try {
                return queryOne(connection, sql);
            } catch (SQLException e) {
                LOG.warn("acquire metadata lock failed: {}", e.getMessage());
                return false;
            }
        }
        // PostgreSQL：pg_try_advisory_lock 立即返回，客户端轮询实现 timeout
        String sql = "SELECT pg_try_advisory_lock(hashtext('" + LOCK_KEY + "'))";
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutSeconds) * 1000L;
        while (true) {
            try {
                if (queryOne(connection, sql)) {
                    return true;
                }
            } catch (SQLException e) {
                LOG.warn("acquire metadata lock failed: {}", e.getMessage());
                return false;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(PG_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 释放锁。仅方言支持时有意义，失败只记日志不抛出。
     *
     * @param connection 取锁时的连接
     * @param dialect 方言
     */
    public static void release(Connection connection, SqlDialect dialect) {
        if (connection == null || !supports(dialect)) {
            return;
        }
        String sql = dialect == SqlDialect.MYSQL
                ? "SELECT RELEASE_LOCK('" + LOCK_KEY + "')"
                : "SELECT pg_advisory_unlock(hashtext('" + LOCK_KEY + "'))";
        try {
            queryOne(connection, sql);
        } catch (SQLException e) {
            LOG.warn("release metadata lock failed: {}", e.getMessage());
        }
    }

    private static boolean queryOne(Connection connection, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            ResultSet rs = ps.executeQuery();
            try {
                if (rs.next()) {
                    Object v = rs.getObject(1);
                    if (v == null) {
                        // pg_advisory_lock / pg_advisory_unlock 返回 void（NULL 结果），视为成功
                        return true;
                    }
                    if (v instanceof Number) {
                        // MySQL GET_LOCK / RELEASE_LOCK 成功返回 1
                        return ((Number) v).intValue() != 0;
                    }
                    String s = String.valueOf(v);
                    // 部分驱动把 void 映射成空串，同样视为成功
                    return s.isEmpty() || Boolean.parseBoolean(s);
                }
                return false;
            } finally {
                rs.close();
            }
        } finally {
            ps.close();
        }
    }
}
