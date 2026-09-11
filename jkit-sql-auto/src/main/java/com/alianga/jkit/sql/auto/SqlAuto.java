package com.alianga.jkit.sql.auto;

import com.alianga.jkit.log.Log;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.entity.SqlEntities;
import com.alianga.jkit.sql.entity.SqlEntityModel;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动时自动建表 / 更新表结构。扫描 {@code @SqlTable} / JPA / MyBatis-Plus 实体，
 * 对照 {@link java.sql.DatabaseMetaData} 生成并执行 DDL。
 *
 * <pre>{@code
 * SqlAuto.run(SqlAutoOptions.defaults()
 *         .url("jdbc:h2:mem:demo")
 *         .packages("com.example.entity"));
 * }</pre>
 *
 * <p>也可 {@link SqlAutoOptions#fromConfig()} 读 {@code jkit.sql.auto.*}。
 * 不引入 Spring / Hibernate；宿主自己在启动入口调用 {@link #run()}。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlAuto {
    private static final Log LOG = Log.get(SqlAuto.class);
    private static final AtomicBoolean DROP_HOOK = new AtomicBoolean();

    private SqlAuto() {
    }

    /**
     * 独立进程入口：读 {@code jkit.sql.auto.*} / {@code spring.datasource.*} 后建表。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        run();
    }

    /**
     * 从进程配置加载并执行。{@code jkit.sql.auto.enabled=false} 或模式 {@code none} 时直接返回空计划。
     *
     * @return 执行后的计划（dry-run 时也返回规划结果）
     */
    public static SqlAutoPlan run() {
        return run(SqlAutoOptions.fromConfig());
    }

    /**
     * @param options 选项
     * @return 计划
     */
    public static SqlAutoPlan run(SqlAutoOptions options) {
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        if (!opt.enabled() || opt.mode() == SqlAutoMode.NONE) {
            return SqlAutoPlan.empty();
        }
        List<Class<?>> types = collectEntities(opt);
        if (types.isEmpty()) {
            LOG.info("jkit-sql-auto: no entities, skip");
            return SqlAutoPlan.empty();
        }
        ConnectionHolder holder = open(opt);
        try {
            Connection conn = holder.connection;
            SqlDialect dialect = SqlAutoDialects.resolve(opt, conn);
            SqlAutoPlan plan = plan(types, conn, dialect, opt);
            if (opt.mode() == SqlAutoMode.VALIDATE) {
                List<SqlAutoChange> bad = plan.ofKind(SqlAutoChange.Kind.VALIDATE);
                if (!bad.isEmpty()) {
                    throw new SqlAutoException("schema validate failed: " + bad);
                }
                return plan;
            }
            SqlAutoExecutor.execute(conn, plan, opt);
            if (opt.mode() == SqlAutoMode.CREATE_DROP && !opt.dryRun()) {
                registerDropHook(types, opt, dialect, holder.owns);
            }
            return plan;
        } finally {
            holder.close();
        }
    }

    /**
     * 只规划不执行。
     *
     * @param options 选项
     * @return 计划
     */
    public static SqlAutoPlan plan(SqlAutoOptions options) {
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        List<Class<?>> types = collectEntities(opt);
        ConnectionHolder holder = open(opt);
        try {
            SqlDialect dialect = SqlAutoDialects.resolve(opt, holder.connection);
            return plan(types, holder.connection, dialect, opt);
        } finally {
            holder.close();
        }
    }

    /**
     * 已有连接上规划。
     *
     * @param types 实体
     * @param connection 连接
     * @param dialect 方言
     * @param options 选项
     * @return 计划
     */
    public static SqlAutoPlan plan(List<Class<?>> types, Connection connection, SqlDialect dialect,
                                   SqlAutoOptions options) {
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        SqlDialect d = dialect == null ? SqlAutoDialects.resolve(opt, connection) : dialect;
        List<Class<?>> ordered = SqlEntities.orderByForeignKeys(types);
        SqlAutoInspector inspector = new SqlAutoInspector(connection, opt, d);
        List<SqlAutoChange> changes = new ArrayList<SqlAutoChange>(8);
        Map<String, Class<?>> seen = new LinkedHashMap<String, Class<?>>(ordered.size());
        List<SqlEntityModel> models = new ArrayList<SqlEntityModel>(ordered.size());
        List<SqlAutoLiveTable> lives = new ArrayList<SqlAutoLiveTable>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Class<?> type = ordered.get(i);
            SqlEntityModel model = SqlEntities.inspect(type);
            String key = model.tableName().toLowerCase();
            if (seen.containsKey(key)) {
                continue;
            }
            seen.put(key, type);
            models.add(model);
            lives.add(inspector.inspect(model.tableName()));
        }
        if (opt.mode() == SqlAutoMode.CREATE || opt.mode() == SqlAutoMode.CREATE_DROP) {
            for (int i = models.size() - 1; i >= 0; i--) {
                if (lives.get(i) == null) {
                    continue;
                }
                String table = models.get(i).tableName();
                changes.add(new SqlAutoChange(SqlAutoChange.Kind.DROP_TABLE, table, "",
                        SqlAutoDdl.dropTableSql(table, d, opt)));
            }
        }
        for (int i = 0; i < models.size(); i++) {
            changes.addAll(SqlAutoDdl.planTable(models.get(i), lives.get(i), d, opt));
        }
        return new SqlAutoPlan(changes);
    }

    /**
     * 已有连接上执行。
     *
     * @param connection 连接
     * @param options 选项
     * @return 计划
     */
    public static SqlAutoPlan run(Connection connection, SqlAutoOptions options) {
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        if (!opt.enabled() || opt.mode() == SqlAutoMode.NONE) {
            return SqlAutoPlan.empty();
        }
        List<Class<?>> types = collectEntities(opt);
        SqlDialect dialect = SqlAutoDialects.resolve(opt, connection);
        SqlAutoPlan plan = plan(types, connection, dialect, opt);
        if (opt.mode() == SqlAutoMode.VALIDATE) {
            List<SqlAutoChange> bad = plan.ofKind(SqlAutoChange.Kind.VALIDATE);
            if (!bad.isEmpty()) {
                throw new SqlAutoException("schema validate failed: " + bad);
            }
            return plan;
        }
        SqlAutoExecutor.execute(connection, plan, opt);
        if (opt.mode() == SqlAutoMode.CREATE_DROP && !opt.dryRun()
                && (opt.dataSource() != null || (opt.url() != null && opt.url().length() > 0))) {
            registerDropHook(types, opt, dialect, true);
        }
        return plan;
    }

    /**
     * 按外键逆序删除托管表（存在才删；Oracle 无 {@code IF EXISTS}）。
     *
     * @param connection 连接
     * @param options 选项（用其中的实体 / 包 / 方言）
     * @return 删除计划
     */
    public static SqlAutoPlan drop(Connection connection, SqlAutoOptions options) {
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        List<Class<?>> types = collectEntities(opt);
        SqlDialect dialect = SqlAutoDialects.resolve(opt, connection);
        List<Class<?>> ordered = SqlEntities.orderByForeignKeys(types);
        List<SqlAutoChange> changes = new ArrayList<SqlAutoChange>(ordered.size());
        SqlAutoInspector inspector = new SqlAutoInspector(connection, opt, dialect);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            String table = SqlEntities.inspect(ordered.get(i)).tableName();
            if (inspector.inspect(table) == null) {
                continue;
            }
            changes.add(new SqlAutoChange(SqlAutoChange.Kind.DROP_TABLE, table, "",
                    SqlAutoDdl.dropTableSql(table, dialect, opt)));
        }
        SqlAutoPlan plan = new SqlAutoPlan(changes);
        SqlAutoExecutor.execute(connection, plan, opt);
        return plan;
    }

    /**
     * 打开连接后删除托管表。
     *
     * @param options 选项
     * @return 删除计划
     */
    public static SqlAutoPlan drop(SqlAutoOptions options) {
        SqlAutoOptions opt = options == null ? SqlAutoOptions.defaults() : options;
        ConnectionHolder holder = open(opt);
        try {
            return drop(holder.connection, opt);
        } finally {
            holder.close();
        }
    }

    static List<Class<?>> collectEntities(SqlAutoOptions options) {
        List<Class<?>> out = new ArrayList<Class<?>>(8);
        if (options.entities() != null) {
            out.addAll(options.entities());
        }
        if (options.packages() != null && !options.packages().isEmpty()) {
            List<Class<?>> scanned = SqlEntities.scan(options.packages());
            for (int i = 0; i < scanned.size(); i++) {
                Class<?> c = scanned.get(i);
                if (!containsType(out, c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static boolean containsType(List<Class<?>> list, Class<?> type) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == type) {
                return true;
            }
        }
        return false;
    }

    private static ConnectionHolder open(SqlAutoOptions options) {
        DataSource ds = options.dataSource();
        if (ds != null) {
            try {
                return new ConnectionHolder(ds.getConnection(), true);
            } catch (SQLException e) {
                throw new SqlAutoException("open DataSource failed", e);
            }
        }
        String url = options.url();
        if (url == null || url.isEmpty()) {
            throw new SqlAutoException("jkit.sql.auto.url / spring.datasource.url is required");
        }
        loadDriver(options);
        try {
            Connection conn = DriverManager.getConnection(url, options.username(), options.password());
            return new ConnectionHolder(conn, true);
        } catch (SQLException e) {
            throw new SqlAutoException("open JDBC connection failed: " + url, e);
        }
    }

    private static void loadDriver(SqlAutoOptions options) {
        String driver = options.driver();
        if (driver == null || driver.isEmpty()) {
            driver = SqlAutoDialects.driverForUrl(options.url());
        }
        if (driver == null) {
            return;
        }
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new SqlAutoException("JDBC driver not found: " + driver, e);
        }
    }

    private static void registerDropHook(final List<Class<?>> types, final SqlAutoOptions options,
                                         final SqlDialect dialect, boolean ownsConnection) {
        if (!ownsConnection && options.dataSource() == null && (options.url() == null || options.url().isEmpty())) {
            return;
        }
        if (!DROP_HOOK.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void run() {
                dropOnShutdown(types, options, dialect);
            }
        }, "jkit-sql-auto-create-drop"));
    }

    static void dropOnShutdown(List<Class<?>> types, SqlAutoOptions options, SqlDialect dialect) {
        ConnectionHolder holder = null;
        try {
            holder = open(options);
            List<Class<?>> ordered = SqlEntities.orderByForeignKeys(types);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                String table = SqlEntities.inspect(ordered.get(i)).tableName();
                String sql = SqlAutoDdl.dropTableSql(table, dialect, options);
                if (options.showSql()) {
                    LOG.info("{}", sql);
                }
                Statement st = null;
                try {
                    st = holder.connection.createStatement();
                    st.execute(sql);
                } catch (SQLException e) {
                    LOG.warn("drop {} failed: {}", table, e.getMessage());
                } finally {
                    if (st != null) {
                        try {
                            st.close();
                        } catch (SQLException ignored) {
                            // 忽略
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("create-drop shutdown failed: {}", e.getMessage());
        } finally {
            if (holder != null) {
                holder.close();
            }
        }
    }

    private static final class ConnectionHolder {
        private final Connection connection;
        private final boolean owns;

        private ConnectionHolder(Connection connection, boolean owns) {
            this.connection = connection;
            this.owns = owns;
        }

        private void close() {
            if (!owns || connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 忽略
            }
        }
    }
}
