package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.SqlDialectWrapper;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.DialectTypeForm;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 自定义方言实现 {@link SqlDialectSpec}，不必改 {@link SqlDialect} 枚举。
 *
 * @author 郑明亮
 */
public class CustomDialectSpecTest {

    /**
     * 只覆写引号的包装方言，类型表复用基方言。
     */
    @Test
    public void wrapperReusesTypeFamily() {
        SqlDialectSpec ansiQuotesMysql = new SqlDialectWrapper(SqlDialect.MYSQL) {
            @Override
            public boolean doubleQuoteIsString() {
                return false;
            }
        };
        assertEquals("MYSQL", ansiQuotesMysql.dialectId());
        assertEquals(SqlDialect.MYSQL, ansiQuotesMysql.typeFamily());
        String pg = SQL.convert("CREATE TABLE t (id INT, name VARCHAR(32))",
                SqlDialect.MYSQL, ansiQuotesMysql);
        assertTrue(pg.toUpperCase(), pg.toUpperCase().contains("INT"));
        String toPg = SQL.convert("CREATE TABLE t (id INT)", ansiQuotesMysql, SqlDialect.POSTGRES);
        assertTrue(toPg, toPg.toUpperCase().contains("INTEGER"));
    }

    /**
     * 全新方言：分页/引号自定，类型族复用 PostgreSQL。
     */
    @Test
    public void implementSpecWithoutEnum() {
        SqlDialectSpec gaussLite = new SqlDialectSpec() {
            @Override
            public String dialectId() {
                return "gauss-lite";
            }

            @Override
            public SqlDialect typeFamily() {
                return SqlDialect.POSTGRES;
            }
        };
        String sql = SQL.convert("CREATE TABLE t (id INT NOT NULL, flag TINYINT(1))",
                SqlDialect.MYSQL, gaussLite);
        String u = sql.toUpperCase();
        assertTrue(sql, u.contains("INTEGER"));
        assertTrue(sql, u.contains("BOOLEAN"));
        SQL.parse(sql, gaussLite);
    }

    /**
     * 自定义 dialectId 单独登记类型（测试用非 freeze 表）。
     */
    @Test
    public void extraDialectIdTypeTable() {
        SqlDataTypeRegistry r = new SqlDataTypeRegistry();
        r.register(CanonicalType.INT, "minidb", DialectTypeForm.of("INT32"));
        r.register(CanonicalType.VARCHAR, "minidb", DialectTypeForm.of("TEXT(%d)"));
        SqlDialectSpec mini = new SqlDialectSpec() {
            @Override
            public String dialectId() {
                return "minidb";
            }

            @Override
            public SqlDialect typeFamily() {
                return SqlDialect.MYSQL;
            }
        };
        assertEquals("INT32", r.toDialect(CanonicalType.INT, mini, null, null));
        assertEquals("TEXT(20)", r.toDialect(CanonicalType.VARCHAR, mini, Integer.valueOf(20), null));
        assertEquals(CanonicalType.INT, r.fromDialect("INT32", mini));
        assertEquals("BIGINT",
                SqlDataTypeRegistry.builtins().toDialect(CanonicalType.BIGINT, mini, null, null));
    }
}
