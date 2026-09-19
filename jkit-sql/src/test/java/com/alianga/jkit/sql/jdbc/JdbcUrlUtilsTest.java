package com.alianga.jkit.sql.jdbc;

import com.alianga.jkit.sql.SqlDialect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JDBC URL 解析、方言与驱动推断。
 *
 * @author 郑明亮
 */
public class JdbcUrlUtilsTest {

    @Test
    public void parseMysqlCluster() {
        JdbcUrlInfo info = JdbcUrlUtils.parse(
                "jdbc:mysql:replication://master:3306,slave1:3307,slave2:3308/db?useSSL=false");
        assertEquals("mysql", info.getDbType());
        assertEquals("db", info.getDatabaseName());
        assertEquals("master", info.getHost());
        assertEquals(Integer.valueOf(3306), info.getPort());
        assertEquals(3, info.getNodes().size());
        assertEquals("false", info.getParameters().get("useSSL"));
        assertEquals("replication", info.getParameters().get("subProtocol"));
    }

    @Test
    public void parsePostgresHaAndSchema() {
        JdbcUrlInfo info = JdbcUrlUtils.parse(
                "jdbc:postgresql://primary:5432,standby:5432/orders?currentSchema=sales");
        assertEquals("postgresql", info.getDbType());
        assertEquals("orders", info.getDatabaseName());
        assertEquals(2, info.getNodes().size());
        assertEquals("sales", info.getSchema());
    }

    @Test
    public void parsePostgresDefaultsPublic() {
        assertEquals("public", JdbcUrlUtils.schema("jdbc:postgresql://192.168.1.100:5432/orders"));
        assertEquals("public", JdbcUrlUtils.schema(
                "jdbc:postgresql://192.168.1.100:5432/orders?currentSchema=public"));
    }

    @Test
    public void parseCurrentSchemaTakesFirst() {
        assertEquals("app", JdbcUrlUtils.schema(
                "jdbc:postgresql://h:5432/db?currentSchema=app,public"));
    }

    @Test
    public void parseOracleSidAndService() {
        JdbcUrlInfo sid = JdbcUrlUtils.parse("jdbc:oracle:thin:@127.0.0.1:1521:ORCL");
        assertEquals("oracle", sid.getDbType());
        assertEquals("ORCL", sid.getDatabaseName());
        assertEquals("127.0.0.1", sid.getHost());
        assertEquals(Integer.valueOf(1521), sid.getPort());

        JdbcUrlInfo svc = JdbcUrlUtils.parse("jdbc:oracle:thin:@//localhost:1521/XEPDB1");
        assertEquals("XEPDB1", svc.getDatabaseName());
        assertEquals("localhost", svc.getHost());
    }

    @Test
    public void parseOracleRac() {
        JdbcUrlInfo info = JdbcUrlUtils.parse(
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=rac1)(PORT=1521))"
                        + "(ADDRESS=(PROTOCOL=TCP)(HOST=rac2)(PORT=1521))(LOAD_BALANCE=on)"
                        + "(CONNECT_DATA=(SERVICE_NAME=orcl.rac)))");
        assertEquals("oracle", info.getDbType());
        assertEquals(2, info.getNodes().size());
        assertEquals("rac1", info.getNodes().get(0).getHost());
        assertEquals("orcl.rac", info.getDatabaseName());
    }

    @Test
    public void parseSqlServer() {
        JdbcUrlInfo info = JdbcUrlUtils.parse(
                "jdbc:sqlserver://localhost:1433;databaseName=HR_DB;user=admin;encrypt=true");
        assertEquals("sqlserver", info.getDbType());
        assertEquals("HR_DB", info.getDatabaseName());
        assertEquals("dbo", info.getSchema());
        assertEquals("true", info.getParameters().get("encrypt"));
    }

    @Test
    public void parseSqlServerDatabaseAlias() {
        JdbcUrlInfo info = JdbcUrlUtils.parse("jdbc:sqlserver://localhost;database=db");
        assertEquals("db", info.getDatabaseName());
        assertEquals("dbo", info.getSchema());
    }

    @Test
    public void parseH2MemAndFile() {
        JdbcUrlInfo mem = JdbcUrlUtils.parse("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
        assertEquals("h2", mem.getDbType());
        assertEquals("testdb", mem.getDatabaseName());
        assertEquals("mem", mem.getParameters().get("connectionMode"));
        assertEquals("MySQL", mem.getParameters().get("MODE"));

        JdbcUrlInfo file = JdbcUrlUtils.parse(
                "jdbc:h2:/opt/data/desktop;AUTO_SERVER=TRUE;MODE=MySQL");
        assertEquals("file", file.getParameters().get("connectionMode"));
        assertEquals("TRUE", file.getParameters().get("AUTO_SERVER"));
    }

    @Test
    public void parseGaussAndDamengSchema() {
        assertEquals("test", JdbcUrlUtils.schema(
                "jdbc:gaussdb://192.168.1.100:5432/production;currentSchema=test"));
        assertEquals("public", JdbcUrlUtils.schema("jdbc:gaussdb://localhost:5432/mydb"));
        assertEquals("test", JdbcUrlUtils.schema(
                "jdbc:dm://dbserver.com:5236/appdb?schema=test"));
        assertEquals("appdb", JdbcUrlUtils.databaseName("jdbc:dm://localhost:5236/appdb"));
    }

    @Test
    public void parseSqliteAndHsqldb() {
        JdbcUrlInfo sqlite = JdbcUrlUtils.parse("jdbc:sqlite:file.db");
        assertEquals("sqlite", sqlite.getDbType());
        assertEquals("file.db", sqlite.getDatabaseName());

        JdbcUrlInfo hsql = JdbcUrlUtils.parse("jdbc:hsqldb:hsql://localhost:9001/mydb");
        assertEquals("hsqldb", hsql.getDbType());
        assertEquals("/mydb", hsql.getDatabaseName());
        assertEquals("localhost", hsql.getHost());
    }

    @Test
    public void tryParseBadUrl() {
        assertNull(JdbcUrlUtils.tryParse(null));
        assertNull(JdbcUrlUtils.tryParse(""));
        assertNull(JdbcUrlUtils.tryParse("not-jdbc"));
        assertNull(JdbcUrlUtils.schema(null));
    }

    @Test
    public void fromUrlDialects() {
        assertEquals(SqlDialect.MYSQL, JdbcUrlUtils.fromUrl("jdbc:mysql://localhost:3306/db"));
        assertEquals(SqlDialect.MYSQL, JdbcUrlUtils.fromUrl("jdbc:gbase://127.0.0.1:5258/edbsource"));
        assertEquals(SqlDialect.MYSQL, JdbcUrlUtils.fromUrl("jdbc:tidb://127.0.0.1:4000/test"));
        assertEquals(SqlDialect.POSTGRES, JdbcUrlUtils.fromUrl("jdbc:postgresql://localhost/db"));
        assertEquals(SqlDialect.POSTGRES, JdbcUrlUtils.fromUrl("jdbc:gaussdb://localhost:5433/postgres"));
        assertEquals(SqlDialect.POSTGRES, JdbcUrlUtils.fromUrl("jdbc:opengauss://localhost:5433/postgres"));
        assertEquals(SqlDialect.POSTGRES, JdbcUrlUtils.fromUrl("jdbc:kingbase8://localhost:54321/db"));
        assertEquals(SqlDialect.ORACLE, JdbcUrlUtils.fromUrl("jdbc:oracle:thin:@//localhost:1521/orcl"));
        assertEquals(SqlDialect.ORACLE, JdbcUrlUtils.fromUrl("jdbc:oscar://localhost:2003/osrdb"));
        assertEquals(SqlDialect.SQLSERVER, JdbcUrlUtils.fromUrl("jdbc:sqlserver://localhost;database=db"));
        assertEquals(SqlDialect.H2, JdbcUrlUtils.fromUrl("jdbc:h2:mem:demo"));
        assertEquals(SqlDialect.SQLITE, JdbcUrlUtils.fromUrl("jdbc:sqlite:file.db"));
        assertEquals(SqlDialect.DAMENG, JdbcUrlUtils.fromUrl("jdbc:dm://localhost:5236"));
        assertEquals(SqlDialect.HIVE, JdbcUrlUtils.fromUrl("jdbc:hive2://localhost:10000/default"));
        assertEquals(SqlDialect.CLICKHOUSE, JdbcUrlUtils.fromUrl("jdbc:clickhouse://localhost:8123/default"));
        assertEquals(SqlDialect.PRESTO, JdbcUrlUtils.fromUrl("jdbc:trino://localhost:8080"));
        assertNull(JdbcUrlUtils.fromUrl(null));
        assertNull(JdbcUrlUtils.fromUrl("not-jdbc"));
        assertNull(JdbcUrlUtils.fromUrl("jdbc:derby:memory:testdb"));
        assertNull(JdbcUrlUtils.fromUrl("jdbc:duckdb:/tmp/x"));
    }

    @Test
    public void getDbTypeNames() {
        assertEquals("mysql", JdbcUrlUtils.getDbType("jdbc:mariadb://h/db"));
        assertEquals("postgresql", JdbcUrlUtils.getDbType("jdbc:pgsql://h/db"));
        assertEquals("gaussdb", JdbcUrlUtils.getDbType("jdbc:opengauss://h/db"));
        assertEquals("kingbase", JdbcUrlUtils.getDbType("jdbc:kingbase8://h/db"));
        assertEquals("sqlserver", JdbcUrlUtils.getDbType("jdbc:jtds:sqlserver://h/db"));
        assertEquals("oracle", JdbcUrlUtils.getDbType("jdbc:oceanbase:oracle://h/db"));
        assertEquals("mysql", JdbcUrlUtils.getDbType("jdbc:oceanbase://h/db"));
        assertEquals("hive", JdbcUrlUtils.getDbType("jdbc:odps:http://h/db"));
        assertEquals("derby", JdbcUrlUtils.getDbType("jdbc:derby:memory:x"));
        assertNull(JdbcUrlUtils.getDbType("jdbc:unknown:foo"));
    }

    @Test
    public void driverForUrl() {
        assertEquals("org.h2.Driver", JdbcUrlUtils.driverForUrl("jdbc:h2:mem:x"));
        assertEquals("org.postgresql.Driver", JdbcUrlUtils.driverForUrl("jdbc:postgresql://h/db"));
        assertEquals("org.opengauss.Driver",
                JdbcUrlUtils.driverForUrl("jdbc:gaussdb://localhost:5433/postgres"));
        assertEquals("com.gbase.jdbc.Driver",
                JdbcUrlUtils.driverForUrl("jdbc:gbase://127.0.0.1:5258/edbsource"));
        assertEquals("dm.jdbc.driver.DmDriver", JdbcUrlUtils.driverForUrl("jdbc:dm://localhost:5236"));
        assertEquals("com.kingbase8.Driver", JdbcUrlUtils.driverForUrl("jdbc:kingbase8://h/db"));
        assertEquals("io.trino.jdbc.TrinoDriver", JdbcUrlUtils.driverForUrl("jdbc:trino://h"));
        assertEquals(JdbcUrlUtils.driverForUrl("jdbc:mysql://h/db"),
                JdbcUrlUtils.getDriverClassName("jdbc:mysql://h/db"));
        assertNotNull(JdbcUrlUtils.driverForUrl("jdbc:mysql://h/db"));
        assertTrue(JdbcUrlUtils.driverForUrl("jdbc:mysql://h/db").contains("mysql"));
        assertNull(JdbcUrlUtils.driverForUrl(null));
        assertNull(JdbcUrlUtils.driverForUrl("jdbc:unknown:foo"));
    }
}
