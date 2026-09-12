package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link SqlDialect#fromName} 别名与分页/拼接能力矩阵。
 *
 * @author 郑明亮
 */
public class SqlDialectTest {

    @Test
    public void fromNameAliases() {
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName(null));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName(""));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("TiDB"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("percona"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("singlestore"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("tdsql"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("cockroachdb"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("redshift"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("opengauss"));
        assertEquals(SqlDialect.DAMENG, SqlDialect.fromName("dameng"));
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("oceanbase_oracle"));
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("oracle11"));
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("11g"));
        assertEquals(SqlDialect.ORACLE12, SqlDialect.fromName("oracle12"));
        assertEquals(SqlDialect.ORACLE12, SqlDialect.fromName("oracle12c"));
        assertEquals(SqlDialect.ORACLE12, SqlDialect.fromName("19c"));
        assertEquals(SqlDialect.DAMENG, SqlDialect.fromName("dm"));
        assertEquals(SqlDialect.DAMENG, SqlDialect.fromName("dm8"));
        assertEquals(SqlDialect.SQLSERVER, SqlDialect.fromName("sybase"));
        assertEquals(SqlDialect.SQLSERVER, SqlDialect.fromName("azuresql"));
        assertEquals(SqlDialect.H2, SqlDialect.fromName("h2"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("unknown-dialect-xyz"));

        // 国产与主流新增：引擎兼容重命名走别名，能力不同的进一等枚举
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("goldendb"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("selectdb"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("analyticdb"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("highgo"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("uxdb"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("mogdb"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("ivorysql"));
        assertEquals(SqlDialect.DB2, SqlDialect.fromName("db2"));
        assertEquals(SqlDialect.DB2, SqlDialect.fromName("db2luw"));
        assertEquals(SqlDialect.SQLITE, SqlDialect.fromName("sqlite"));
        assertEquals(SqlDialect.SQLITE, SqlDialect.fromName("sqlite3"));
        assertEquals(SqlDialect.HIVE, SqlDialect.fromName("hive"));
        assertEquals(SqlDialect.HIVE, SqlDialect.fromName("odps"));
        assertEquals(SqlDialect.HIVE, SqlDialect.fromName("maxcompute"));
        assertEquals(SqlDialect.CLICKHOUSE, SqlDialect.fromName("clickhouse"));
        assertEquals(SqlDialect.CLICKHOUSE, SqlDialect.fromName("ck"));
        assertEquals(SqlDialect.PRESTO, SqlDialect.fromName("presto"));
        assertEquals(SqlDialect.PRESTO, SqlDialect.fromName("trino"));

        // common-model（icell）数据源类型对齐：argo→HIVE（Transwarp Hive JDBC）、
        // xcloud→POSTGRES（行云：双引号 + LIMIT/OFFSET）、gbase8a→MYSQL、gbase8s→SQLITE（LIMIT 无 FETCH）
        assertEquals(SqlDialect.HIVE, SqlDialect.fromName("argo"));
        assertEquals(SqlDialect.HIVE, SqlDialect.fromName("argodb"));
        assertEquals(SqlDialect.POSTGRES, SqlDialect.fromName("xcloud"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("gbase8a"));
        assertEquals(SqlDialect.SQLITE, SqlDialect.fromName("gbase8s"));
    }

    @Test
    public void capabilityMatrix() {
        assertTrue(SqlDialect.MYSQL.supportsLimitOffset());
        assertFalse(SqlDialect.MYSQL.supportsTop());
        assertFalse(SqlDialect.MYSQL.supportsFetchFirst());
        assertFalse(SqlDialect.MYSQL.supportsRownum());
        assertTrue(SqlDialect.MYSQL.pipesAsOr());
        assertFalse(SqlDialect.MYSQL.pipesAreConcat());
        assertEquals("LIMIT", SqlDialect.MYSQL.preferredLimitStyle());

        assertTrue(SqlDialect.POSTGRES.supportsLimitOffset());
        assertTrue(SqlDialect.POSTGRES.supportsFetchFirst());
        assertFalse(SqlDialect.POSTGRES.supportsTop());
        assertTrue(SqlDialect.POSTGRES.pipesAreConcat());

        assertFalse(SqlDialect.ORACLE.supportsLimitOffset());
        assertFalse(SqlDialect.ORACLE.supportsFetchFirst());
        assertTrue(SqlDialect.ORACLE.supportsRownum());
        assertEquals("ROWNUM", SqlDialect.ORACLE.preferredLimitStyle());

        assertFalse(SqlDialect.ORACLE12.supportsLimitOffset());
        assertTrue(SqlDialect.ORACLE12.supportsFetchFirst());
        assertTrue(SqlDialect.ORACLE12.supportsRownum());
        assertEquals("LIMIT", SqlDialect.ORACLE12.preferredLimitStyle());

        assertTrue(SqlDialect.DAMENG.supportsLimitOffset());
        assertFalse(SqlDialect.DAMENG.supportsRownum());
        assertFalse(SqlDialect.DAMENG.supportsFetchFirst());
        assertTrue(SqlDialect.DAMENG.pipesAreConcat());
        assertEquals("LIMIT", SqlDialect.DAMENG.preferredLimitStyle());
        assertEquals('"', SqlDialect.DAMENG.identQuoteOpen());

        assertTrue(SqlDialect.SQLSERVER.supportsTop());
        assertFalse(SqlDialect.SQLSERVER.supportsLimitOffset());
        assertTrue(SqlDialect.SQLSERVER.supportsFetchFirst());
        assertEquals("TOP", SqlDialect.SQLSERVER.preferredLimitStyle());

        assertTrue(SqlDialect.H2.supportsLimitOffset());
        assertTrue(SqlDialect.H2.hashLineComment());
        assertTrue(SqlDialect.ANSI.supportsLimitOffset());
        assertTrue(SqlDialect.ANSI.supportsFetchFirst());

        // DB2：仅 FETCH FIRST，无 LIMIT/ROWNUM
        assertFalse(SqlDialect.DB2.supportsLimitOffset());
        assertTrue(SqlDialect.DB2.supportsFetchFirst());
        assertFalse(SqlDialect.DB2.supportsRownum());
        assertFalse(SqlDialect.DB2.supportsTop());
        assertTrue(SqlDialect.DB2.pipesAreConcat());
        assertEquals("LIMIT", SqlDialect.DB2.preferredLimitStyle());

        // SQLite / Presto：LIMIT 族，无 FETCH FIRST
        assertTrue(SqlDialect.SQLITE.supportsLimitOffset());
        assertFalse(SqlDialect.SQLITE.supportsFetchFirst());
        assertTrue(SqlDialect.PRESTO.supportsLimitOffset());
        assertFalse(SqlDialect.PRESTO.supportsFetchFirst());
        assertTrue(SqlDialect.PRESTO.pipesAreConcat());

        // Hive：反引号、拼接、双引号字符串、仅 LIMIT
        assertEquals('`', SqlDialect.HIVE.identQuoteOpen());
        assertTrue(SqlDialect.HIVE.pipesAreConcat());
        assertTrue(SqlDialect.HIVE.doubleQuoteIsString());
        assertTrue(SqlDialect.HIVE.supportsLimitOffset());
        assertFalse(SqlDialect.HIVE.supportsCommaLimitOffset());

        // ClickHouse：反引号、双引号是标识符、逗号 LIMIT
        assertEquals('`', SqlDialect.CLICKHOUSE.identQuoteOpen());
        assertFalse(SqlDialect.CLICKHOUSE.doubleQuoteIsString());
        assertTrue(SqlDialect.CLICKHOUSE.pipesAreConcat());
        assertTrue(SqlDialect.CLICKHOUSE.supportsCommaLimitOffset());
    }

    @Test
    public void quoteIdentIsSingleSource() {
        assertEquals("`user`", SqlDialect.MYSQL.quoteIdent("user"));
        assertEquals("\"user\"", SqlDialect.POSTGRES.quoteIdent("user"));
        assertEquals("[user]", SqlDialect.SQLSERVER.quoteIdent("user"));
        assertEquals("[a]]b]", SqlDialect.SQLSERVER.quoteIdent("a]b"));
        assertEquals("\"a\"\"b\"", SqlDialect.ORACLE.quoteIdent("a\"b"));
        assertEquals('`', SqlDialect.MYSQL.identQuoteOpen());
        assertEquals(']', SqlDialect.SQLSERVER.identQuoteClose());
    }

    @Test
    public void addLimitUsesDialectCapabilities() {
        String mysql = SQL.toSqlString(SQL.addLimit(SQL.parse("SELECT * FROM t"), 10, SqlDialect.MYSQL));
        assertTrue(mysql.toUpperCase().contains("LIMIT"));

        String ss = SQL.toSqlString(
                SQL.addLimit(SQL.parse("SELECT * FROM t", SqlDialect.SQLSERVER), 10, SqlDialect.SQLSERVER),
                SqlDialect.SQLSERVER);
        assertTrue(ss.toUpperCase(), ss.toUpperCase().contains("TOP"));
        assertFalse(ss.toUpperCase().contains("LIMIT"));
    }
}
