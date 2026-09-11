package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.auto.fixture.AutoOrg;
import com.alianga.jkit.sql.auto.fixture.AutoStaff;
import com.alianga.jkit.sql.auto.fixture.AutoUser;

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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * H2 上真实建表 / 加列 / 校验。
 *
 * @author 郑明亮
 */
public class SqlAutoH2Test {
    private String url;
    private Connection connection;

    /**
     * 打开独立内存库。
     *
     * @throws Exception 失败
     */
    @Before
    public void setUp() throws Exception {
        Class.forName("org.h2.Driver");
        url = "jdbc:h2:mem:auto_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=true";
        connection = DriverManager.getConnection(url, "sa", "");
    }

    /**
     * 关连接。
     *
     * @throws Exception 失败
     */
    @After
    public void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    public void createThenSkipOnSecondRun() {
        SqlAutoOptions opt = options().entities(AutoUser.class).mode(SqlAutoMode.UPDATE);
        SqlAutoPlan first = SqlAuto.run(connection, opt);
        assertTrue(first.toString(), !first.ofKind(SqlAutoChange.Kind.CREATE_TABLE).isEmpty());
        assertTrue(tableExists("AUTO_USER"));
        assertTrue(columnExists("AUTO_USER", "USER_NAME"));

        SqlAutoPlan second = SqlAuto.run(connection, opt);
        assertTrue(second.ofKind(SqlAutoChange.Kind.CREATE_TABLE).isEmpty());
        assertTrue(second.ofKind(SqlAutoChange.Kind.ADD_COLUMN).isEmpty());
    }

    @Test
    public void addColumnOnExistingTable() throws SQLException {
        exec("CREATE TABLE AUTO_USER (ID BIGINT PRIMARY KEY, USER_NAME VARCHAR(32) NOT NULL)");
        SqlAutoPlan plan = SqlAuto.run(connection, options().entities(AutoUser.class));
        assertTrue(plan.toString(), !plan.ofKind(SqlAutoChange.Kind.ADD_COLUMN).isEmpty());
        assertTrue(plan.ofKind(SqlAutoChange.Kind.CREATE_TABLE).isEmpty());
        assertTrue(columnExists("AUTO_USER", "EMAIL"));
        assertTrue(columnExists("AUTO_USER", "AGE"));
    }

    @Test
    public void validateMissingTableThrows() {
        try {
            SqlAuto.run(connection, options().entities(AutoUser.class).mode(SqlAutoMode.VALIDATE));
            fail("expected SqlAutoException");
        } catch (SqlAutoException e) {
            assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("validate"));
        }
    }

    @Test
    public void createDropAndFkOrder() {
        SqlAutoOptions opt = options()
                .entities(AutoStaff.class, AutoOrg.class)
                .mode(SqlAutoMode.CREATE);
        SqlAutoPlan plan = SqlAuto.run(connection, opt);
        assertTrue(tableExists("AUTO_ORG"));
        assertTrue(tableExists("AUTO_STAFF"));
        int orgAt = indexOfTable(plan, "auto_org");
        int staffAt = indexOfTable(plan, "auto_staff");
        assertTrue(plan.toString(), orgAt >= 0 && staffAt >= 0 && orgAt < staffAt);
        assertTrue(columnExists("AUTO_STAFF", "ORG_ID"));

        SqlAutoPlan again = SqlAuto.run(connection, opt);
        int dropStaff = -1;
        int dropOrg = -1;
        List<SqlAutoChange> drops = again.ofKind(SqlAutoChange.Kind.DROP_TABLE);
        for (int i = 0; i < drops.size(); i++) {
            if ("auto_staff".equalsIgnoreCase(drops.get(i).table())) {
                dropStaff = i;
            }
            if ("auto_org".equalsIgnoreCase(drops.get(i).table())) {
                dropOrg = i;
            }
        }
        assertTrue(again.toString(), dropStaff >= 0 && dropOrg >= 0 && dropStaff < dropOrg);
        assertTrue(tableExists("AUTO_ORG"));
        assertTrue(tableExists("AUTO_STAFF"));
    }

    @Test
    public void dryRunDoesNotCreate() {
        SqlAutoPlan plan = SqlAuto.run(connection, options()
                .entities(AutoUser.class)
                .dryRun(true));
        assertFalse(plan.ofKind(SqlAutoChange.Kind.CREATE_TABLE).isEmpty());
        assertFalse(tableExists("AUTO_USER"));
    }

    @Test
    public void fromUrlRun() {
        SqlAutoPlan plan = SqlAuto.run(options().entities(AutoUser.class).url(url).username("sa").password(""));
        assertTrue(tableExists("AUTO_USER"));
        assertFalse(plan.isEmpty());
    }

    @Test
    public void scanPackage() {
        SqlAutoPlan plan = SqlAuto.run(connection, options()
                .packages("com.alianga.jkit.sql.auto.fixture"));
        assertTrue(tableExists("AUTO_USER"));
        assertTrue(tableExists("AUTO_ORG"));
        assertTrue(tableExists("AUTO_STAFF"));
        assertFalse(plan.isEmpty());
    }

    @Test
    public void createThenDropOnShutdown() {
        SqlAutoOptions opt = options().entities(AutoUser.class).mode(SqlAutoMode.CREATE_DROP);
        SqlAuto.run(connection, opt);
        assertTrue(tableExists("AUTO_USER"));
        SqlAuto.dropOnShutdown(Arrays.<Class<?>>asList(AutoUser.class), opt, SqlDialect.H2);
        assertFalse(tableExists("AUTO_USER"));
    }

    @Test
    public void dropApiRemovesTable() {
        SqlAuto.run(connection, options().entities(AutoUser.class));
        assertTrue(tableExists("AUTO_USER"));
        SqlAutoPlan dropped = SqlAuto.drop(connection, options().entities(AutoUser.class));
        assertFalse(dropped.ofKind(SqlAutoChange.Kind.DROP_TABLE).isEmpty());
        assertFalse(tableExists("AUTO_USER"));
    }

    private SqlAutoOptions options() {
        return SqlAutoOptions.defaults()
                .url(url)
                .username("sa")
                .password("")
                .dialect(SqlDialect.H2)
                .showSql(false);
    }

    private boolean tableExists(String table) {
        try {
            ResultSet rs = connection.getMetaData().getTables(null, null, table, new String[] {"TABLE"});
            try {
                return rs.next();
            } finally {
                rs.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean columnExists(String table, String column) {
        try {
            ResultSet rs = connection.getMetaData().getColumns(null, null, table, column);
            try {
                return rs.next();
            } finally {
                rs.close();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void exec(String sql) throws SQLException {
        Statement st = connection.createStatement();
        try {
            st.execute(sql);
        } finally {
            st.close();
        }
    }

    private static int indexOfTable(SqlAutoPlan plan, String table) {
        List<SqlAutoChange> changes = plan.ofKind(SqlAutoChange.Kind.CREATE_TABLE);
        for (int i = 0; i < changes.size(); i++) {
            if (table.equalsIgnoreCase(changes.get(i).table())) {
                return i;
            }
        }
        return -1;
    }
}
