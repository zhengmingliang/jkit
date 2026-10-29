package com.alianga.jkit.sql.auto;

import com.alianga.jkit.log.Log;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 按计划执行 DDL。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAutoExecutor {
    private static final Log LOG = Log.get(SqlAutoExecutor.class);

    private SqlAutoExecutor() {
    }

    /**
     * @param connection 连接
     * @param plan 计划
     * @param options 选项
     * @return 实际执行成功的变更
     */
    public static List<SqlAutoChange> execute(Connection connection, SqlAutoPlan plan,
                                              SqlAutoOptions options) {
        return executeDetailed(connection, plan, options).succeeded;
    }

    /**
     * 与 {@link #execute} 相同，但返回更详细的结果：成功的变更 + 失败但被跳过
     * （failFast=false 时）的变更。失败信息对运维可见，便于排查"部分失败被静默忽略"。
     *
     * @param connection 连接
     * @param plan 计划
     * @param options 选项
     * @return 详细执行结果
     * @since 2.0.5
     */
    public static SqlAutoExecuteResult executeDetailed(Connection connection, SqlAutoPlan plan,
                                                       SqlAutoOptions options) {
        List<SqlAutoChange> succeeded = new ArrayList<SqlAutoChange>(4);
        List<SqlAutoChange> failed = new ArrayList<SqlAutoChange>(4);
        if (plan == null || plan.isEmpty()) {
            return new SqlAutoExecuteResult(succeeded, failed);
        }
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        if (opt.dryRun()) {
            if (opt.showSql()) {
                List<SqlAutoChange> changes = plan.changes();
                for (int i = 0; i < changes.size(); i++) {
                    SqlAutoChange c = changes.get(i);
                    if (c.sql().length() > 0) {
                        LOG.info("[dry-run] {}", c.sql());
                    }
                }
            }
            return new SqlAutoExecuteResult(succeeded, failed);
        }
        if (connection == null) {
            return new SqlAutoExecuteResult(succeeded, failed);
        }
        List<SqlAutoChange> changes = plan.changes();
        for (int i = 0; i < changes.size(); i++) {
            SqlAutoChange change = changes.get(i);
            if (change.kind() == SqlAutoChange.Kind.VALIDATE || change.sql().isEmpty()) {
                continue;
            }
            if (opt.showSql()) {
                LOG.info("{}", change.sql());
            }
            try {
                run(connection, change.sql());
                succeeded.add(change);
            } catch (SQLException e) {
                if (opt.failFast()) {
                    throw new SqlAutoException("execute failed: " + change.sql(), e);
                }
                LOG.warn("skip failed SQL: {} ({})", change.sql(), e.getMessage());
                failed.add(change);
            }
        }
        return new SqlAutoExecuteResult(succeeded, failed);
    }

    /**
     * 详细执行结果。
     */
    public static final class SqlAutoExecuteResult {
        /** 实际执行成功的变更。 */
        public final List<SqlAutoChange> succeeded;
        /** 执行失败但被跳过（failFast=false 时）。 */
        public final List<SqlAutoChange> failed;

        SqlAutoExecuteResult(List<SqlAutoChange> succeeded, List<SqlAutoChange> failed) {
            this.succeeded = succeeded;
            this.failed = failed;
        }

        /**
         * @return 是否有失败但被跳过的变更
         */
        public boolean hasFailures() {
            return !failed.isEmpty();
        }
    }

    private static void run(Connection connection, String sql) throws SQLException {
        Statement st = connection.createStatement();
        try {
            st.execute(sql);
        } finally {
            st.close();
        }
    }
}
