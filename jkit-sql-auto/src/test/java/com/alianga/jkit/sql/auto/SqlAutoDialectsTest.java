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
}
