package com.alianga.jkit.sql.auto;

import com.alianga.jkit.sql.SqlDialect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * URL / 产品名推断方言。
 *
 * @author 郑明亮
 */
public class SqlAutoDialectsTest {

    @Test
    public void fromUrl() {
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromUrl("jdbc:mysql://localhost:3306/db"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromUrl("jdbc:gbase://127.0.0.1:5258/edbsource"));
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromUrl("jdbc:postgresql://localhost/db"));
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromUrl("jdbc:gaussdb://localhost:5433/postgres"));
        assertEquals(SqlDialect.ORACLE, SqlAutoDialects.fromUrl("jdbc:oracle:thin:@//localhost:1521/orcl"));
        assertEquals(SqlDialect.SQLSERVER, SqlAutoDialects.fromUrl("jdbc:sqlserver://localhost;database=db"));
        assertEquals(SqlDialect.H2, SqlAutoDialects.fromUrl("jdbc:h2:mem:demo"));
        assertEquals(SqlDialect.SQLITE, SqlAutoDialects.fromUrl("jdbc:sqlite:file.db"));
        assertEquals(SqlDialect.DAMENG, SqlAutoDialects.fromUrl("jdbc:dm://localhost:5236"));
        assertNull(SqlAutoDialects.fromUrl(null));
        assertNull(SqlAutoDialects.fromUrl("not-jdbc"));
    }

    @Test
    public void driverForUrl() {
        assertEquals("org.h2.Driver", SqlAutoDialects.driverForUrl("jdbc:h2:mem:x"));
        assertEquals("org.postgresql.Driver", SqlAutoDialects.driverForUrl("jdbc:postgresql://h/db"));
        assertEquals("org.opengauss.Driver", SqlAutoDialects.driverForUrl("jdbc:gaussdb://localhost:5433/postgres"));
        assertEquals("com.gbase.jdbc.Driver", SqlAutoDialects.driverForUrl("jdbc:gbase://127.0.0.1:5258/edbsource"));
        assertNull(SqlAutoDialects.driverForUrl(null));
    }

    @Test
    public void resolvePrefersOption() {
        SqlAutoOptions opt = SqlAutoOptions.defaults().dialect(SqlDialect.POSTGRES).url("jdbc:h2:mem:x");
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.resolve(opt, null));
    }

    @Test
    public void resolveFromUrlWhenNoDialect() {
        SqlAutoOptions opt = SqlAutoOptions.defaults().url("jdbc:h2:mem:x");
        assertEquals(SqlDialect.H2, SqlAutoDialects.resolve(opt, null));
    }

    // ============ P0-11: fromProduct / fromProductStrict 不再误判为 MySQL ============

    @Test
    public void fromProductKingbaseIsPostgresNotMysql() {
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProduct("KingbaseES"));
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProduct("kingbasees"));
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProduct("KingBase ES"));
    }

    @Test
    public void fromProductOpenGaussIsPostgresNotMysql() {
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProduct("openGauss"));
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProduct("GaussDB"));
    }

    @Test
    public void fromProductOceanBaseIsMysql() {
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("OceanBase"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("oceanbase"));
    }

    @Test
    public void fromProductOceanBaseOracleIsOracle() {
        assertEquals(SqlDialect.ORACLE, SqlAutoDialects.fromProduct("OceanBase_Oracle"));
        // 归一化需折叠下划线与空格：这些形态必须走 Oracle 专有分支而不是
        // 被通用 oracle 分支或 MYSQL 分支的 oceanbase 抢走（strict 下同样成立）
        assertEquals(SqlDialect.ORACLE, SqlAutoDialects.fromProductStrict("OceanBase Oracle"));
        assertEquals(SqlDialect.ORACLE, SqlAutoDialects.fromProductStrict("oceanbase_oracle"));
        assertEquals(SqlDialect.ORACLE, SqlAutoDialects.fromProductStrict("OceanBase_Oracle_4.2"));
    }

    @Test
    public void fromProductNormalizesUnderscores() {
        assertEquals(SqlDialect.SQLSERVER, SqlAutoDialects.fromProduct("sql_server"));
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProduct("open_gauss"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("great_sql"));
    }

    @Test
    public void fromProductSqlServer() {
        assertEquals(SqlDialect.SQLSERVER, SqlAutoDialects.fromProduct("Microsoft SQL Server"));
        assertEquals(SqlDialect.SQLSERVER, SqlAutoDialects.fromProduct("SQLServer"));
    }

    @Test
    public void fromProductKnownLegacyMysql() {
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("MySQL"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("MariaDB"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("TiDB"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("PolarDB"));
    }

    @Test
    public void fromProductClassic() {
        assertEquals(SqlDialect.ORACLE, SqlAutoDialects.fromProduct("Oracle"));
        assertEquals(SqlDialect.DB2, SqlAutoDialects.fromProduct("DB2/LINUXX8664"));
        assertEquals(SqlDialect.H2, SqlAutoDialects.fromProduct("H2"));
        assertEquals(SqlDialect.CLICKHOUSE, SqlAutoDialects.fromProduct("ClickHouse"));
        assertEquals(SqlDialect.DAMENG, SqlAutoDialects.fromProduct("DM DBMS"));
    }

    /**
     * 未知产品名仍回落 MySQL（向后兼容）。
     */
    @Test
    public void fromProductUnknownFallsBackToMysql() {
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("NotARealDatabase"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("some-new-future-db"));
    }

    /**
     * null / 空字符串产品名回落 MySQL（向后兼容）。
     */
    @Test
    public void fromProductNullAndEmptyFallsBackToMysql() {
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct(null));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct(""));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProduct("   "));
    }

    /**
     * 严格变体：无法识别时返回 null 而不是 MySQL。
     */
    @Test
    public void fromProductStrictReturnsNullForUnknown() {
        org.junit.Assert.assertNull(SqlAutoDialects.fromProductStrict("NotARealDatabase"));
        org.junit.Assert.assertNull(SqlAutoDialects.fromProductStrict(null));
        org.junit.Assert.assertNull(SqlAutoDialects.fromProductStrict(""));
        org.junit.Assert.assertNull(SqlAutoDialects.fromProductStrict("   "));
    }

    /**
     * 严格变体：识别到的产品名与非严格一致。
     */
    @Test
    public void fromProductStrictMatchesIdentified() {
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProductStrict("KingbaseES"));
        assertEquals(SqlDialect.POSTGRES, SqlAutoDialects.fromProductStrict("openGauss"));
        assertEquals(SqlDialect.SQLSERVER, SqlAutoDialects.fromProductStrict("Microsoft SQL Server"));
        assertEquals(SqlDialect.ORACLE, SqlAutoDialects.fromProductStrict("Oracle"));
        assertEquals(SqlDialect.MYSQL, SqlAutoDialects.fromProductStrict("MySQL"));
        assertEquals(SqlDialect.H2, SqlAutoDialects.fromProductStrict("H2"));
        assertEquals(SqlDialect.DAMENG, SqlAutoDialects.fromProductStrict("DM DBMS"));
    }

    /**
     * fromProduct 必须与 fromProductStrict 识别路径一致：同一识别能命中的输入，
     * 两者应得到同一方言；只有"不识别"才会有 MySQL vs null 的差异。
     */
    @Test
    public void fromProductAndStrictAreConsistentWhenIdentified() {
        String[] products = {
                "MySQL", "KingbaseES", "openGauss", "OceanBase",
                "Microsoft SQL Server", "Oracle", "H2", "ClickHouse",
                "DM DBMS", "MariaDB", "PolarDB", "TiDB", "greenplum",
        };
        for (String p : products) {
            SqlDialect a = SqlAutoDialects.fromProduct(p);
            SqlDialect b = SqlAutoDialects.fromProductStrict(p);
            assertEquals("mismatch for " + p, a, b);
        }
    }
}
