package com.alianga.jkit.sql;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

/**
 * 跨方言分页矩阵：同一业务句在 format / toSqlString / adaptPagination 路径下的形态断言。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlPaginationDialectMatrixTest {

    private static final String BASE =
            "SELECT id, name FROM t_user WHERE age > 18";
    private static final String LIMIT_0_10000 = BASE + " limit 0,10000";
    private static final String LIMIT_30_30 = BASE + " LIMIT 30,30";

    private static String compact(String sql) {
        return sql == null ? "" : sql.toUpperCase().replaceAll("\\s+", "");
    }

    private static void assertNoMysqlComma(String compactSql) {
        assertFalse("must not keep MySQL comma LIMIT",
                compactSql.matches(".*LIMIT\\d+,\\d+.*"));
    }

    private static void assertLimitOnly(String c, long n) {
        assertTrue(c, c.contains("LIMIT" + n));
        assertFalse(c, c.contains("OFFSET"));
        assertNoMysqlComma(c);
        assertFalse(c, c.contains("ROWNUM") || c.contains("FETCH") || c.contains("TOP"));
    }

    private static void assertLimitOffset(String c, long limit, long offset) {
        assertTrue(c, c.contains("LIMIT" + limit));
        assertTrue(c, c.contains("OFFSET" + offset));
        assertNoMysqlComma(c);
        assertFalse(c, c.contains("ROWNUM") || c.contains("FETCHFIRST") || c.contains("TOP"));
    }

    private static void assertOracleSimple(String c, long end) {
        assertTrue(c, c.contains("ROWNUM<=" + end) || c.contains("ROWNUM<=" + end));
        assertTrue(c, c.contains("ROWNUM"));
        assertFalse(c, c.contains("LIMIT") || c.contains("FETCH"));
        assertFalse("offset=0 single-layer", c.contains("ASRN") || c.contains(",RN"));
    }

    private static void assertOracleNested(String c, long end, long off) {
        assertTrue(c, c.contains("ROWNUM"));
        assertTrue(c, c.contains("ROWNUM<=" + end));
        assertTrue(c, c.contains("RN>" + off) || c.contains("RN>=" + (off + 1)));
        assertFalse(c, c.contains("LIMIT") || c.contains("FETCHFIRST"));
    }

    private static void assertFetch(String c, Long offset, long fetch) {
        assertTrue(c, c.contains("FETCH") && c.contains(String.valueOf(fetch)));
        if (offset == null || offset.longValue() == 0L) {
            assertFalse(c, c.contains("OFFSET"));
        } else {
            assertTrue(c, c.contains("OFFSET" + offset));
        }
        assertFalse(c, c.contains("LIMIT") || c.contains("ROWNUM") || c.contains("TOP"));
    }

    private static void assertTop(String c, long n) {
        assertTrue(c, c.contains("TOP" + n) || c.contains("TOP(" + n + ")"));
        assertFalse(c, c.contains("LIMIT") || c.contains("ROWNUM") || c.contains("OFFSET"));
    }

    private void assertDialect(SqlStatement stmt, SqlDialect dialect, long limit, long offset) {
        String viaFormat = compact(SQL.format(stmt, dialect));
        String viaToSql = compact(SQL.toSqlString(stmt, dialect));
        String viaAdapt = compact(SQL.toSqlString(SQL.adaptPagination(SQL.clone(stmt), dialect), dialect));
        assertForm(viaFormat, dialect, limit, offset);
        assertForm(viaToSql, dialect, limit, offset);
        assertForm(viaAdapt, dialect, limit, offset);
    }

    private void assertForm(String c, SqlDialect dialect, long limit, long offset) {
        switch (dialect) {
            case MYSQL:
                if (offset == 0L) {
                    // 逗号风格或 LIMIT n 均可；禁止 ROWNUM/FETCH/TOP
                    assertTrue(c, c.contains("LIMIT"));
                    assertTrue(c, c.contains(String.valueOf(limit)));
                    assertFalse(c, c.contains("ROWNUM") || c.contains("FETCH") || c.contains("TOP"));
                } else {
                    // 允许 LIMIT off,n 或 LIMIT n OFFSET m
                    assertTrue(c, c.contains(String.valueOf(limit)) && c.contains(String.valueOf(offset)));
                    assertTrue(c, c.contains("LIMIT"));
                    assertFalse(c, c.contains("ROWNUM") || c.contains("FETCHFIRST") || c.contains("TOP"));
                }
                break;
            case POSTGRES:
            case H2:
            case ANSI:
            case SQLITE:
            case PRESTO:
                if (offset == 0L) {
                    assertLimitOnly(c, limit);
                } else {
                    assertLimitOffset(c, limit, offset);
                }
                break;
            case CLICKHOUSE:
                assertTrue(c, c.contains("LIMIT"));
                assertTrue(c, c.contains(String.valueOf(limit)));
                if (offset > 0L) {
                    assertTrue(c, c.contains(String.valueOf(offset)));
                }
                assertFalse(c, c.contains("ROWNUM") || c.contains("FETCH") || c.contains("TOP"));
                break;
            case HIVE:
                // 现行为 supportsLimitOffset=true：有 offset 时输出 LIMIT n OFFSET m
                assertTrue(c, c.contains("LIMIT" + limit));
                if (offset > 0L) {
                    assertTrue("HIVE current behavior emits OFFSET", c.contains("OFFSET" + offset));
                } else {
                    assertFalse(c, c.contains("OFFSET"));
                }
                assertNoMysqlComma(c);
                assertFalse(c, c.contains("ROWNUM") || c.contains("FETCH") || c.contains("TOP"));
                break;
            case ORACLE:
                if (offset == 0L) {
                    assertOracleSimple(c, limit);
                } else {
                    assertOracleNested(c, offset + limit, offset);
                }
                break;
            case ORACLE12:
                assertFetch(c, offset == 0L ? null : Long.valueOf(offset), limit);
                break;
            case SQLSERVER:
                if (offset == 0L) {
                    assertTop(c, limit);
                } else {
                    assertFetch(c, Long.valueOf(offset), limit);
                }
                break;
            case DB2:
                assertFetch(c, offset == 0L ? null : Long.valueOf(offset), limit);
                break;
            default:
                throw new AssertionError("unexpected dialect " + dialect);
        }
    }

    private static final SqlDialect[] TARGETS = {
            SqlDialect.MYSQL,
            SqlDialect.POSTGRES,
            SqlDialect.H2,
            SqlDialect.ANSI,
            SqlDialect.SQLITE,
            SqlDialect.PRESTO,
            SqlDialect.CLICKHOUSE,
            SqlDialect.HIVE,
            SqlDialect.ORACLE,
            SqlDialect.ORACLE12,
            SqlDialect.SQLSERVER,
            SqlDialect.DB2,
    };

    @Test
    public void matrixMysqlLimit0DirectPaths() {
        SqlStatement stmt = SQL.parse(LIMIT_0_10000);
        for (SqlDialect d : TARGETS) {
            assertDialect(stmt, d, 10000L, 0L);
        }
    }

    @Test
    public void matrixMysqlLimit30_30DirectPaths() {
        SqlStatement stmt = SQL.parse(LIMIT_30_30);
        for (SqlDialect d : TARGETS) {
            assertDialect(stmt, d, 30L, 30L);
        }
    }

    @Test
    public void matrixBareSetPage2() {
        SqlStatement page = SQL.setPage(SQL.parse(BASE), 2, 30, SqlDialect.MYSQL);
        for (SqlDialect d : TARGETS) {
            assertDialect(page, d, 30L, 30L);
        }
    }

    @Test
    public void crossAdaptOracleThenFormatTargets() {
        SqlStatement oracle = SQL.adaptPagination(SQL.parse(LIMIT_0_10000), SqlDialect.ORACLE);
        String rownum = compact(SQL.toSqlString(oracle, SqlDialect.ORACLE));
        assertTrue(rownum, rownum.contains("ROWNUM"));

        assertForm(compact(SQL.format(oracle, SqlDialect.POSTGRES)), SqlDialect.POSTGRES, 10000L, 0L);
        assertForm(compact(SQL.format(oracle, SqlDialect.MYSQL)), SqlDialect.MYSQL, 10000L, 0L);
        assertForm(compact(SQL.format(oracle, SqlDialect.ORACLE12)), SqlDialect.ORACLE12, 10000L, 0L);
        assertForm(compact(SQL.format(oracle, SqlDialect.SQLSERVER)), SqlDialect.SQLSERVER, 10000L, 0L);
        assertForm(compact(SQL.format(oracle, SqlDialect.DB2)), SqlDialect.DB2, 10000L, 0L);
        assertForm(compact(SQL.format(oracle, SqlDialect.H2)), SqlDialect.H2, 10000L, 0L);
    }

    @Test
    public void crossAdaptOraclePage2ThenFormatPostgres() {
        SqlStatement oracle = SQL.setPage(SQL.parse(LIMIT_30_30), 2, 30, SqlDialect.ORACLE);
        assertForm(compact(SQL.format(oracle, SqlDialect.POSTGRES)), SqlDialect.POSTGRES, 30L, 30L);
        assertForm(compact(SQL.toSqlString(oracle, SqlDialect.POSTGRES)), SqlDialect.POSTGRES, 30L, 30L);
    }

    @Test
    public void crossAdaptOracle12ThenFormatOracle() {
        SqlStatement o12 = SQL.adaptPagination(SQL.parse(LIMIT_0_10000), SqlDialect.ORACLE12);
        assertForm(compact(SQL.format(o12, SqlDialect.ORACLE)), SqlDialect.ORACLE, 10000L, 0L);
    }

    @Test
    public void crossAdaptSqlServerThenFormatMysql() {
        SqlStatement ss = SQL.adaptPagination(SQL.parse(LIMIT_0_10000), SqlDialect.SQLSERVER);
        assertForm(compact(SQL.format(ss, SqlDialect.MYSQL)), SqlDialect.MYSQL, 10000L, 0L);
        SqlStatement ssOff = SQL.adaptPagination(SQL.parse(LIMIT_30_30), SqlDialect.SQLSERVER);
        assertForm(compact(SQL.format(ssOff, SqlDialect.MYSQL)), SqlDialect.MYSQL, 30L, 30L);
    }

    @Test
    public void setPagePriorLimitNoInnerResiduePerDialect() {
        for (SqlDialect d : TARGETS) {
            SqlStatement page = SQL.setPage(SQL.parse(LIMIT_0_10000), 2, 30, d);
            String c = compact(SQL.toSqlString(page, d));
            assertForm(c, d, 30L, 30L);
            if (d == SqlDialect.ORACLE) {
                assertFalse("inner must not retain LIMIT", c.contains("LIMIT"));
            }
        }
    }

    @Test
    public void userReproRewriteAdaptOracleFormatPostgres() {
        String out = SQL.format(
                SQL.rewrite(SQL.parse(LIMIT_0_10000),
                        SqlRewrites.create().add(SqlRewrites.adaptPagination(SqlDialect.ORACLE))),
                SqlDialect.POSTGRES);
        String c = compact(out);
        assertFalse(c, c.contains("ROWNUM"));
        assertLimitOnly(c, 10000L);
    }
}
