package com.alianga.jkit.sql.auto;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 覆盖 P0-13：SqlAutoExecutor 在 failFast=false 时不再静默丢弃失败的变更。
 *
 * 修复前：failFast=false 时失败项只 LOG.warn 一次，调用方无法感知有几条失败，
 * 运维查 history 表会误以为"模式同步完整"。
 *
 * 修复后：新增 executeDetailed 返回 {succeeded, failed}；SqlAuto.run 在
 * failFast=false 且存在 failed 时打 WARN 日志列出所有失败项；history 表仍只记录
 * 成功的（其语义本来就是"实际执行成功的 DDL"，这不变）。
 */
public class SqlAutoExecutorTest {
    private String url;
    private Connection connection;

    @Before
    public void setUp() throws Exception {
        Class.forName("org.h2.Driver");
        url = "jdbc:h2:mem:exec_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true";
        connection = DriverManager.getConnection(url, "sa", "");
    }

    @After
    public void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    private SqlAutoPlan planOf(SqlAutoChange... changes) {
        return new SqlAutoPlan(Arrays.asList(changes));
    }

    private SqlAutoChange createTable(String name) {
        return new SqlAutoChange(SqlAutoChange.Kind.CREATE_TABLE, name, "",
                "CREATE TABLE " + name + " (ID BIGINT PRIMARY KEY)");
    }

    private SqlAutoChange invalidSql(String tableName) {
        // 语法错误，H2 会拒绝
        return new SqlAutoChange(SqlAutoChange.Kind.ADD_COLUMN, tableName, "",
                "THIS IS NOT SQL");
    }

    private int countHistoryRows() throws SQLException {
        Statement st = connection.createStatement();
        try {
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM jkit_schema_history");
            try {
                rs.next();
                return rs.getInt(1);
            } finally {
                rs.close();
            }
        } finally {
            st.close();
        }
    }

    /**
     * failFast=true：任何失败立即抛 SqlAutoException，不返回部分结果。
     */
    @Test
    public void failFastThrowsOnFirstFailure() {
        SqlAutoPlan plan = planOf(createTable("T1"), invalidSql("T1"), createTable("T2"));
        SqlAutoOptions opt = SqlAutoOptions.defaults().failFast(true);
        try {
            SqlAutoExecutor.executeDetailed(connection, plan, opt);
            fail("expected SqlAutoException");
        } catch (SqlAutoException expected) {
            // OK
        }
    }

    /**
     * failFast=false：失败的项进 failed 列表，成功的进 succeeded 列表。
     */
    @Test
    public void nonFailFastCollectsFailures() {
        SqlAutoPlan plan = planOf(createTable("T1"), invalidSql("T1"), createTable("T2"));
        SqlAutoOptions opt = SqlAutoOptions.defaults().failFast(false);
        SqlAutoExecutor.SqlAutoExecuteResult result =
                SqlAutoExecutor.executeDetailed(connection, plan, opt);

        assertNotNull(result);
        assertEquals("succeeded should have 2", 2, result.succeeded.size());
        assertEquals("failed should have 1", 1, result.failed.size());
        assertTrue(result.hasFailures());
        assertEquals("T1", result.failed.get(0).table());
    }

    /**
     * 旧 execute() 方法的行为不变：仍返回成功的列表（向后兼容）。
     */
    @Test
    public void legacyExecuteReturnsOnlySucceeded() {
        SqlAutoPlan plan = planOf(createTable("T1"), invalidSql("T1"), createTable("T2"));
        SqlAutoOptions opt = SqlAutoOptions.defaults().failFast(false);
        List<SqlAutoChange> succeeded = SqlAutoExecutor.execute(connection, plan, opt);

        assertEquals(2, succeeded.size());
        // 不包含失败项
        for (SqlAutoChange c : succeeded) {
            if ("THIS IS NOT SQL".equals(c.sql())) {
                fail("succeeded should not contain failed change");
            }
        }
    }

    /**
     * dryRun 模式下 both 都为空（原行为）。
     */
    @Test
    public void dryRunReturnsNoChanges() {
        SqlAutoPlan plan = planOf(createTable("T1"));
        SqlAutoOptions opt = SqlAutoOptions.defaults().dryRun(true);
        SqlAutoExecutor.SqlAutoExecuteResult result =
                SqlAutoExecutor.executeDetailed(connection, plan, opt);

        assertEquals(0, result.succeeded.size());
        assertEquals(0, result.failed.size());
        assertFalse(result.hasFailures());
    }

    /**
     * null connection：both 都为空（原行为）。
     */
    @Test
    public void nullConnectionReturnsNoChanges() {
        SqlAutoPlan plan = planOf(createTable("T1"));
        SqlAutoOptions opt = SqlAutoOptions.defaults();
        SqlAutoExecutor.SqlAutoExecuteResult result =
                SqlAutoExecutor.executeDetailed(null, plan, opt);

        assertEquals(0, result.succeeded.size());
        assertEquals(0, result.failed.size());
    }

    /**
     * 空 plan：both 都为空（原行为）。
     */
    @Test
    public void emptyPlanReturnsNoChanges() {
        SqlAutoOptions opt = SqlAutoOptions.defaults();
        SqlAutoExecutor.SqlAutoExecuteResult result =
                SqlAutoExecutor.executeDetailed(connection, SqlAutoPlan.empty(), opt);

        assertEquals(0, result.succeeded.size());
        assertEquals(0, result.failed.size());
    }

    /**
     * 集成：failFast=false + history=true，部分失败时 history 表只记录成功的；
     * 失败项被 WARN 但不应让 history 记录它们（保持 history 的语义 = 实际执行成功）。
     *
     * 注意：这里只验证 history 表行数 = 成功数；具体 WARN 日志由 SqlAuto.run 写入。
     */
    @Test
    public void historyRecordsOnlySucceeded() throws Exception {
        SqlAutoPlan plan = planOf(createTable("T1"), invalidSql("T1"), createTable("T2"));
        SqlAutoOptions opt = SqlAutoOptions.defaults()
                .failFast(false)
                .history(true)
                .showSql(false);

        SqlAutoExecutor.SqlAutoExecuteResult result =
                SqlAutoExecutor.executeDetailed(connection, plan, opt);

        // 显式调用 history 记录（与 SqlAuto.run 相同路径）
        SqlAutoHistory.record(connection, com.alianga.jkit.sql.SqlDialect.H2, "jkit_schema_history",
                "UPDATE", result.succeeded);

        // 应记录 2 条（T1 成功 + T2 成功），失败项不进 history
        assertEquals("history should have 2 rows (only succeeded)",
                2, countHistoryRows());

        // 再运行一次同一 plan（T1/T2 已存在），所有 CREATE 都失败
        SqlAutoPlan secondRun = planOf(createTable("T1"), createTable("T2"));
        SqlAutoExecutor.SqlAutoExecuteResult r2 =
                SqlAutoExecutor.executeDetailed(connection, secondRun, opt);
        assertEquals("both should fail (already exist)", 2, r2.failed.size());

        SqlAutoHistory.record(connection, com.alianga.jkit.sql.SqlDialect.H2, "jkit_schema_history",
                "UPDATE", r2.succeeded);
        // history 行数不变（没有成功项）
        assertEquals("history rows unchanged", 2, countHistoryRows());
    }
}
