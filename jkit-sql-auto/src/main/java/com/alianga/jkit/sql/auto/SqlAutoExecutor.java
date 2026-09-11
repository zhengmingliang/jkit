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
        List<SqlAutoChange> done = new ArrayList<SqlAutoChange>(4);
        if (connection == null || plan == null || plan.isEmpty()) {
            return done;
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
            return done;
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
                done.add(change);
            } catch (SQLException e) {
                if (opt.failFast()) {
                    throw new SqlAutoException("execute failed: " + change.sql(), e);
                }
                LOG.warn("skip failed SQL: {} ({})", change.sql(), e.getMessage());
            }
        }
        return done;
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
