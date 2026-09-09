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
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("dameng"));
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("oceanbase_oracle"));
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("oracle11"));
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("11g"));
        assertEquals(SqlDialect.ORACLE12, SqlDialect.fromName("oracle12"));
        assertEquals(SqlDialect.ORACLE12, SqlDialect.fromName("oracle12c"));
        assertEquals(SqlDialect.ORACLE12, SqlDialect.fromName("19c"));
        assertEquals(SqlDialect.SQLSERVER, SqlDialect.fromName("sybase"));
        assertEquals(SqlDialect.SQLSERVER, SqlDialect.fromName("azuresql"));
        assertEquals(SqlDialect.ANSI, SqlDialect.fromName("sqlite"));
        assertEquals(SqlDialect.ANSI, SqlDialect.fromName("db2"));
        assertEquals(SqlDialect.H2, SqlDialect.fromName("h2"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("unknown-dialect-xyz"));
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

        assertTrue(SqlDialect.SQLSERVER.supportsTop());
        assertFalse(SqlDialect.SQLSERVER.supportsLimitOffset());
        assertTrue(SqlDialect.SQLSERVER.supportsFetchFirst());
        assertEquals("TOP", SqlDialect.SQLSERVER.preferredLimitStyle());

        assertTrue(SqlDialect.H2.supportsLimitOffset());
        assertTrue(SqlDialect.H2.hashLineComment());
        assertTrue(SqlDialect.ANSI.supportsLimitOffset());
        assertTrue(SqlDialect.ANSI.supportsFetchFirst());
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
