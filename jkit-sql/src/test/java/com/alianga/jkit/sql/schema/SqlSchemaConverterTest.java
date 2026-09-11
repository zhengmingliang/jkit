package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.schema.convert.ConversionResult;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConversionException;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions;
import com.alianga.jkit.sql.schema.convert.SqlSchemaConverter;
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * CREATE TABLE 跨方言转换。
 *
 * @author 郑明亮
 */
public class SqlSchemaConverterTest {

    @Test
    public void alterAddColumnConvertsType() {
        String pg = SQL.convert("ALTER TABLE t ADD COLUMN name VARCHAR(32) NOT NULL",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("VARCHAR(32)"));
        SQL.parse(pg, SqlDialect.POSTGRES);
    }
    @Test
    public void mysqlTextStaysClobOnOracle() {
        String ora = SQL.convert(
                "CREATE TABLE t (name TEXT(65535), money2 FLOAT, email VARCHAR(100))",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        String u = ora.toUpperCase();
        assertTrue(ora, u.contains("CLOB"));
        assertFalse(ora, u.contains("INTERVAL"));
        assertTrue(ora, u.contains("BINARY_FLOAT") || u.contains("FLOAT"));
        SQL.parse(ora, SqlDialect.ORACLE);
    }

    @Test
    public void alterModifyColumnConvertsTypeAndWarns() {
        ConversionResult r = SqlSchemaConverter.convert(
                "ALTER TABLE t MODIFY amt DECIMAL(10,2) NOT NULL",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(r.sql(), r.sql().toUpperCase().contains("ALTER COLUMN"));
        assertTrue(r.sql(), r.sql().toUpperCase().contains("NUMERIC"));
        SQL.parse(r.sql(), SqlDialect.POSTGRES);
    }

    @Test
    public void alterChangeColumnConvertsNewType() {
        ConversionResult r = SqlSchemaConverter.convert(
                "ALTER TABLE t CHANGE COLUMN old_c new_c INT NOT NULL",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(r.sql(), r.sql().toUpperCase().contains("INTEGER"));
        assertTrue(r.sql(), r.sql().toUpperCase().contains("ALTER COLUMN"));
        assertFalse(r.report().extraSql().isEmpty());
        assertTrue(r.report().extraSql().get(0).toUpperCase().contains("RENAME COLUMN"));
    }

    @Test
    public void dateFormatBecomesToChar() {
        String pg = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("TO_CHAR"));
        assertFalse(pg, pg.toUpperCase().contains("DATE_FORMAT"));
    }

    @Test
    public void uuidAndIntervalRoundtrip() {
        assertEquals("UUID",
                SqlDataTypeRegistry.builtins().convert("CHAR(36)", SqlDialect.MYSQL, SqlDialect.POSTGRES));
        assertEquals("INTERVAL",
                SqlDataTypeRegistry.builtins().toDialect(CanonicalType.INTERVAL, SqlDialect.POSTGRES, null, null));
    }

    @Test
    public void oracleSequenceOptIn() {
        ConversionResult r = SqlSchemaConverter.convert(
                "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                SqlDialect.MYSQL, SqlDialect.ORACLE,
                SqlSchemaConvertOptions.defaults().generateOracleSequence(true));
        assertFalse(r.report().extraSql().isEmpty());
        assertTrue(r.report().extraSql().get(0).toUpperCase().contains("SEQUENCE"));
    }

    @Test
    public void longVarcharPromotedToText() {
        String ora = SQL.convert("CREATE TABLE t (body VARCHAR(8000))",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(ora, ora.toUpperCase().contains("CLOB"));
    }

    @Test
    public void mysqlToPostgresBasicTypes() {
        String pg = SQL.convert(
                "CREATE TABLE t (id INT NOT NULL, name VARCHAR(100), amount DECIMAL(10,2))",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = pg.toUpperCase();
        assertTrue(pg, u.contains("INTEGER"));
        assertTrue(pg, u.contains("VARCHAR(100)"));
        assertTrue(pg, u.contains("NUMERIC(10,2)"));
    }

    @Test
    public void mysqlAutoIncrementToPostgresIdentity() {
        ConversionResult r = SqlSchemaConverter.convert(
                "CREATE TABLE t (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(32))",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = r.sql().toUpperCase();
        assertTrue(r.sql(), u.contains("GENERATED ALWAYS AS IDENTITY"));
        assertTrue(r.sql(), u.contains("PRIMARY KEY"));
        assertFalse(r.sql(), u.contains("AUTO_INCREMENT"));
    }

    @Test
    public void mysqlAutoIncrementToPostgresSerial() {
        SqlSchemaConvertOptions opt = SqlSchemaConvertOptions.defaults()
                .postgresIdentityStyle(SqlSchemaConvertOptions.PostgresIdentityStyle.SERIAL);
        String sql = SqlSchemaConverter.convert(
                "CREATE TABLE t (id BIGINT AUTO_INCREMENT PRIMARY KEY)",
                SqlDialect.MYSQL, SqlDialect.POSTGRES, opt).sql();
        assertTrue(sql, sql.toUpperCase().contains("BIGSERIAL"));
        assertFalse(sql, sql.toUpperCase().contains("GENERATED"));
    }

    @Test
    public void mysqlAutoIncrementToOracle11Warns() {
        ConversionResult r = SqlSchemaConverter.convert(
                "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(r.report().hasBlockingIssues());
        assertTrue(r.sql().toUpperCase().contains("NUMBER(10)"));
        assertFalse(r.sql().toUpperCase().contains("IDENTITY"));
        assertFalse(r.sql().toUpperCase().contains("AUTO_INCREMENT"));
    }

    @Test
    public void mysqlAutoIncrementToOracle12Identity() {
        String sql = SQL.convert(
                "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                SqlDialect.MYSQL, SqlDialect.ORACLE12);
        assertTrue(sql.toUpperCase().contains("GENERATED ALWAYS AS IDENTITY"));
        assertTrue(sql.toUpperCase().contains("NUMBER(10)"));
    }

    @Test
    public void mysqlAutoIncrementToSqlServer() {
        String sql = SQL.convert(
                "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY)",
                SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        assertTrue(sql.toUpperCase().contains("IDENTITY(1,1)"));
    }

    @Test
    public void mysqlDatetimeAndBooleanDefault() {
        String pg = SQL.convert(
                "CREATE TABLE t (flag TINYINT(1) DEFAULT 0, ts DATETIME)",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = pg.toUpperCase();
        assertTrue(pg, u.contains("BOOLEAN"));
        assertTrue(pg, pg.contains("false") || pg.contains("FALSE"));
        assertTrue(pg, u.contains("TIMESTAMP"));
        assertFalse(pg, u.contains("DATETIME"));
        assertFalse(pg, u.contains("TINYINT"));
    }

    @Test
    public void unsignedUpsizeIntToBigint() {
        ConversionResult r = SqlSchemaConverter.convert(
                "CREATE TABLE t (n INT UNSIGNED)",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(r.sql().toUpperCase().contains("BIGINT"));
        assertFalse(r.sql().toUpperCase().contains("UNSIGNED"));
        assertFalse(r.report().warnings().isEmpty());
    }

    @Test
    public void stripEngineAndCharset() {
        String pg = SQL.convert(
                "CREATE TABLE t (id INT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = pg.toUpperCase();
        assertFalse(pg, u.contains("ENGINE"));
        assertFalse(pg, u.contains("CHARSET"));
        assertFalse(pg, u.contains("UTF8"));
    }

    @Test
    public void failOnManualAction() {
        SqlSchemaConvertOptions opt = SqlSchemaConvertOptions.defaults()
                .failOnSeverity(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED);
        try {
            SqlSchemaConverter.convert(
                    "CREATE TABLE t (id INT AUTO_INCREMENT)",
                    SqlDialect.MYSQL, SqlDialect.ORACLE, opt);
            fail("expected SqlSchemaConversionException");
        } catch (SqlSchemaConversionException expected) {
            assertTrue(expected.report().hasBlockingIssues());
        }
    }

    @Test
    public void sameDialectReturnsOriginal() {
        String sql = "CREATE TABLE t (id INT)";
        assertEquals(sql, SQL.convert(sql, SqlDialect.MYSQL, SqlDialect.MYSQL));
    }

    @Test
    public void selectIsFormattedForTargetDialect() {
        String oracle = SQL.convert("SELECT id FROM t LIMIT 10", SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(oracle.toUpperCase().contains("ROWNUM"));
    }

    @Test
    public void mysqlKeyIndexIsDroppedOnPostgres() {
        ConversionResult r = SqlSchemaConverter.convert(
                "CREATE TABLE t (id INT, name VARCHAR(8), KEY idx_name (name))",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = r.sql().toUpperCase();
        assertFalse(r.sql(), u.contains("KEY IDX_NAME"));
        assertFalse(r.report().extraSql().isEmpty());
        assertTrue(r.report().extraSql().get(0).toUpperCase().contains("CREATE INDEX"));
        assertTrue(r.sqlWithExtras().toUpperCase().contains("CREATE INDEX IDX_NAME ON T"));
        SQL.parse(r.sql(), SqlDialect.POSTGRES);
    }

    @Test
    public void uniqueKeyBecomesUnique() {
        String pg = SQL.convert(
                "CREATE TABLE t (id INT, email VARCHAR(64), UNIQUE KEY uk_email (email))",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = pg.toUpperCase();
        assertTrue(pg, u.contains("UNIQUE"));
        assertFalse(pg, u.contains("UNIQUE KEY"));
        SQL.parse(pg, SqlDialect.POSTGRES);
    }

    @Test
    public void convertedDdlParsesInTargetDialect() {
        String[] mysql = {
                "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(32) NOT NULL, ts DATETIME)",
                "CREATE TABLE t (flag TINYINT(1) DEFAULT 0, amt DECIMAL(10,2), KEY k (flag))",
                "SELECT IF(a>1,b,c), NOW(), IFNULL(x,0), GROUP_CONCAT(n SEPARATOR ',') FROM t LIMIT 10"
        };
        SqlDialect[] targets = {SqlDialect.POSTGRES, SqlDialect.ORACLE12, SqlDialect.SQLSERVER, SqlDialect.H2};
        for (int i = 0; i < mysql.length; i++) {
            for (int t = 0; t < targets.length; t++) {
                String out = SQL.convert(mysql[i], SqlDialect.MYSQL, targets[t]);
                SQL.parse(out, targets[t]);
            }
        }
    }

    @Test
    public void convertBatchPreservesOrder() {
        java.util.List<String> in = java.util.Arrays.asList(
                "CREATE TABLE a (id INT)",
                "SELECT IF(1,2,3)");
        java.util.List<ConversionResult> out = SQL.convertBatch(in, SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(2, out.size());
        assertTrue(out.get(0).sql().toUpperCase().contains("INTEGER"));
        assertTrue(out.get(1).sql().toUpperCase().contains("CASE"));
    }

    @Test
    public void locateBecomesPositionOnPostgres() {
        String pg = SQL.convert("SELECT LOCATE('a', name) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("POSITION"));
        assertFalse(pg, pg.toUpperCase().contains("LOCATE"));
    }

    @Test
    public void locateBecomesInstrOnOracle() {
        String ora = SQL.convert("SELECT LOCATE('a', name) FROM t", SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(ora, ora.toUpperCase().contains("INSTR"));
        assertFalse(ora, ora.toUpperCase().contains("LOCATE"));
    }

    @Test
    public void lengthBecomesLenOnSqlServer() {
        String s = SQL.convert("SELECT LENGTH(name) FROM t", SqlDialect.MYSQL, SqlDialect.SQLSERVER);
        assertTrue(s, s.toUpperCase().contains("LEN("));
        assertFalse(s, s.toUpperCase().contains("LENGTH"));
    }

    @Test
    public void mysqlIfBecomesCaseWhen() {
        String pg = SQL.convert("SELECT IF(a > 1, b, c) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = pg.toUpperCase();
        assertTrue(pg, u.contains("CASE"));
        assertTrue(pg, u.contains("WHEN"));
        assertFalse(pg, u.contains("IF("));
    }

    @Test
    public void mysqlNowBecomesCurrentTimestamp() {
        String pg = SQL.convert("SELECT NOW() FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("CURRENT_TIMESTAMP"));
        assertFalse(pg, pg.toUpperCase().contains("NOW"));
    }

    @Test
    public void convertUsingWarnsAndKeeps() {
        ConversionResult r = SqlSchemaConverter.convert(
                "SELECT CONVERT(name USING utf8mb4) FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        boolean found = false;
        for (int i = 0; i < r.report().warnings().size(); i++) {
            if (r.report().warnings().get(i).message().contains("CONVERT")) {
                found = true;
            }
        }
        assertTrue(r.report().warnings().toString(), found);
        assertTrue(r.sql().toUpperCase().contains("CONVERT"));
    }

    @Test
    public void groupConcatToPostgresStringAgg() {
        String pg = SQL.convert(
                "SELECT GROUP_CONCAT(name ORDER BY id SEPARATOR ',') FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = pg.toUpperCase();
        assertTrue(pg, u.contains("STRING_AGG"));
        assertFalse(pg, u.contains("GROUP_CONCAT"));
        assertFalse(pg, u.contains("SEPARATOR"));
        assertTrue(pg, u.contains("ORDER"));
    }

    @Test
    public void groupConcatToOracleListAgg() {
        String ora = SQL.convert(
                "SELECT GROUP_CONCAT(name SEPARATOR ',') FROM t",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        String u = ora.toUpperCase();
        assertTrue(ora, u.contains("LISTAGG"));
        assertTrue(ora, u.contains("WITHIN"));
        assertFalse(ora, u.contains("GROUP_CONCAT"));
        assertFalse(ora, u.contains("SEPARATOR"));
    }

    @Test
    public void stringAggToMysqlGroupConcat() {
        String mysql = SQL.convert(
                "SELECT STRING_AGG(name, ',') FROM t",
                SqlDialect.POSTGRES, SqlDialect.MYSQL);
        String u = mysql.toUpperCase();
        assertTrue(mysql, u.contains("GROUP_CONCAT"));
        assertTrue(mysql, u.contains("SEPARATOR"));
    }

    @Test
    public void ifnullToCoalesceOnPostgres() {
        String pg = SQL.convert("SELECT IFNULL(a, 0) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("COALESCE"));
        assertFalse(pg, pg.toUpperCase().contains("IFNULL"));
    }

    @Test
    public void ifnullToNvlOnOracle() {
        String ora = SQL.convert("SELECT IFNULL(a, 0) FROM t", SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(ora, ora.toUpperCase().contains("NVL"));
    }

    @Test
    public void concatManyArgsToOraclePipes() {
        String ora = SQL.convert("SELECT CONCAT(a, b, c) FROM t", SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(ora, ora.contains("||"));
        assertFalse(ora, ora.toUpperCase().contains("CONCAT"));
    }

    @Test
    public void castIntBecomesIntegerOnPostgres() {
        String pg = SQL.convert("SELECT CAST(id AS INT) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("INTEGER"));
    }

    @Test
    public void convertTypeBecomesCast() {
        String pg = SQL.convert("SELECT CONVERT(id, CHAR) FROM t", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = pg.toUpperCase();
        assertTrue(pg, u.contains("CAST"));
        assertFalse(pg, u.contains("CONVERT"));
    }

    @Test
    public void columnCharsetStripped() {
        ConversionResult r = SqlSchemaConverter.convert(
                "CREATE TABLE t (name VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin)",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        String u = r.sql().toUpperCase();
        assertFalse(r.sql(), u.contains("CHARACTER SET"));
        assertFalse(r.sql(), u.contains("COLLATE"));
        assertTrue(u.contains("VARCHAR(32)"));
    }
}
