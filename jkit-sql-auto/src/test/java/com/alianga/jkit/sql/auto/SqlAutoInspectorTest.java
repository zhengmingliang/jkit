package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Inspector 解析 catalog / schema 的方式。PostgreSQL 等必须带上当前 schema，
 * 否则 {@code DatabaseMetaData.getTables} 会扫错命名空间或查不到表。
 *
 * @author 郑明亮
 */
public class SqlAutoInspectorTest {

    @Test
    public void postgresUsesConnectionSchema() {
        Recording rec = new Recording();
        rec.catalog = "mydb";
        rec.schema = "myschema";
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.POSTGRES);
        assertEquals("mydb", rec.tablesCatalog);
        assertEquals("myschema", rec.tablesSchema);
        assertEquals("auto_user", rec.tablesName);
    }

    @Test
    public void postgresDefaultsPublicWhenSchemaMissing() {
        Recording rec = new Recording();
        rec.catalog = "mydb";
        rec.schema = null;
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.POSTGRES);
        assertEquals("mydb", rec.tablesCatalog);
        assertEquals("public", rec.tablesSchema);
    }

    @Test
    public void configuredSchemaWinsOverConnection() {
        Recording rec = new Recording();
        rec.catalog = "mydb";
        rec.schema = "from_conn";
        inspect(rec, SqlAutoOptions.defaults().schema("from_opt"), SqlDialect.POSTGRES);
        assertEquals("from_opt", rec.tablesSchema);
    }

    @Test
    public void oracleFallsBackToUsername() {
        Recording rec = new Recording();
        rec.catalog = null;
        rec.schema = null;
        rec.userName = "scott";
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.ORACLE);
        assertEquals("SCOTT", rec.tablesSchema);
    }

    @Test
    public void damengFallsBackToUsername() {
        Recording rec = new Recording();
        rec.schema = null;
        rec.userName = "sysdba";
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.DAMENG);
        assertEquals("SYSDBA", rec.tablesSchema);
    }

    @Test
    public void sqlServerDefaultsDboWhenSchemaMissing() {
        Recording rec = new Recording();
        rec.catalog = "mydb";
        rec.schema = null;
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.SQLSERVER);
        assertEquals("dbo", rec.tablesSchema);
    }

    @Test
    public void db2FallsBackToUsername() {
        Recording rec = new Recording();
        rec.schema = null;
        rec.userName = "DB2INST1";
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.DB2);
        assertEquals("DB2INST1", rec.tablesSchema);
    }

    @Test
    public void mysqlLeavesSchemaNullWhenUnset() {
        Recording rec = new Recording();
        rec.catalog = "mydb";
        rec.schema = null;
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.MYSQL);
        assertEquals("mydb", rec.tablesCatalog);
        assertNull(rec.tablesSchema);
    }

    @Test
    public void mysqlUsesConnectionSchemaWhenPresent() {
        Recording rec = new Recording();
        rec.catalog = null;
        rec.schema = "mydb";
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.MYSQL);
        assertNull(rec.tablesCatalog);
        assertEquals("mydb", rec.tablesSchema);
    }

    @Test
    public void getSchemaUnsupportedFallsBackByDialect() {
        Recording rec = new Recording();
        rec.catalog = "mydb";
        rec.schemaUnsupported = true;
        inspect(rec, SqlAutoOptions.defaults(), SqlDialect.POSTGRES);
        assertEquals("public", rec.tablesSchema);
    }

    @Test
    public void dryRunReadsSchemaFromPostgresUrl() {
        SqlAutoOptions opt = SqlAutoOptions.defaults()
                .url("jdbc:postgresql://localhost:5432/orders?currentSchema=sales");
        SqlAutoInspector inspector = new SqlAutoInspector(null, opt, SqlDialect.POSTGRES);
        assertEquals("sales", inspector.resolvedSchema());
    }

    @Test
    public void dryRunPostgresUrlDefaultsPublic() {
        SqlAutoOptions opt = SqlAutoOptions.defaults()
                .url("jdbc:postgresql://localhost:5432/orders");
        SqlAutoInspector inspector = new SqlAutoInspector(null, opt, SqlDialect.POSTGRES);
        assertEquals("public", inspector.resolvedSchema());
    }

    @Test
    public void dryRunReadsSchemaFromGaussUrl() {
        SqlAutoOptions opt = SqlAutoOptions.defaults()
                .url("jdbc:gaussdb://192.168.1.100:5432/production;currentSchema=test");
        SqlAutoInspector inspector = new SqlAutoInspector(null, opt, SqlDialect.POSTGRES);
        assertEquals("test", inspector.resolvedSchema());
    }

    @Test
    public void dryRunMysqlUrlLeavesSchemaNull() {
        SqlAutoOptions opt = SqlAutoOptions.defaults()
                .url("jdbc:mysql://localhost:3306/my_db?useSSL=false");
        SqlAutoInspector inspector = new SqlAutoInspector(null, opt, SqlDialect.MYSQL);
        assertNull(inspector.resolvedSchema());
    }

    @Test
    public void getSchemaUnsupportedFallsBackToUrl() {
        Recording rec = new Recording();
        rec.catalog = "mydb";
        rec.schemaUnsupported = true;
        SqlAutoOptions opt = SqlAutoOptions.defaults()
                .url("jdbc:postgresql://h:5432/db?currentSchema=from_url");
        inspect(rec, opt, SqlDialect.POSTGRES);
        assertEquals("from_url", rec.tablesSchema);
    }

    private static void inspect(Recording rec, SqlAutoOptions options, SqlDialect dialect) {
        Connection connection = rec.connection();
        new SqlAutoInspector(connection, options, dialect).inspect("auto_user");
    }

    /**
     * 记下 {@code getTables} 实际收到的 catalog / schema / 表名。
     */
    private static final class Recording {
        String catalog;
        String schema;
        String userName;
        boolean schemaUnsupported;
        String tablesCatalog;
        String tablesSchema;
        String tablesName;

        Connection connection() {
            RecordingMeta meta = new RecordingMeta(this);
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, meta);
        }
    }

    private static final class RecordingMeta implements InvocationHandler {
        private final Recording rec;
        private final Object metaProxy;
        private final Object emptyRs;

        RecordingMeta(Recording rec) {
            this.rec = rec;
            this.metaProxy = Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[] {DatabaseMetaData.class}, this);
            this.emptyRs = Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[] {ResultSet.class}, this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getCatalog".equals(name)) {
                return rec.catalog;
            }
            if ("getSchema".equals(name)) {
                if (rec.schemaUnsupported) {
                    throw new SQLException("getSchema not supported");
                }
                return rec.schema;
            }
            if ("getMetaData".equals(name)) {
                return metaProxy;
            }
            if ("getUserName".equals(name)) {
                return rec.userName;
            }
            if ("storesUpperCaseIdentifiers".equals(name) || "storesLowerCaseIdentifiers".equals(name)) {
                return Boolean.FALSE;
            }
            if ("getTables".equals(name)) {
                rec.tablesCatalog = (String) args[0];
                rec.tablesSchema = (String) args[1];
                rec.tablesName = (String) args[2];
                return emptyRs;
            }
            if ("getColumns".equals(name) || "getIndexInfo".equals(name)) {
                return emptyRs;
            }
            if ("next".equals(name) || "wasNull".equals(name)) {
                return Boolean.FALSE;
            }
            if ("close".equals(name)) {
                return null;
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("toString".equals(name)) {
                return "recording";
            }
            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return Integer.valueOf(0);
            }
            if (rt == long.class) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }
}
