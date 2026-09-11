package com.alianga.jkit.sql.schema;

import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.schema.model.ColumnConstraint;
import com.alianga.jkit.sql.schema.model.ColumnDefinition;
import com.alianga.jkit.sql.schema.model.SqlDataType;
import com.alianga.jkit.sql.schema.parse.SqlColumnDefinitionParser;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 列定义词法解析。
 *
 * @author 郑明亮
 */
public class SqlColumnDefinitionParserTest {

    @Test
    public void parseSimpleMysqlIntPrimaryKey() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY", SqlDialect.MYSQL);
        assertEquals("id", col.columnName());
        assertEquals("INT", col.dataType().rawTypeName().toUpperCase());
        assertFalse(col.tableConstraint());
        assertTrue(col.has(ColumnConstraint.NotNull.class));
        assertTrue(col.has(ColumnConstraint.AutoIncrement.class));
        assertTrue(col.has(ColumnConstraint.InlinePrimaryKey.class));
    }

    @Test
    public void parseVarcharWithPrecisionAndDefault() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "name VARCHAR(100) DEFAULT 'unknown' COMMENT 'display name'", SqlDialect.MYSQL);
        assertEquals("name", col.columnName());
        assertEquals("VARCHAR", col.dataType().rawTypeName().toUpperCase());
        assertEquals(Integer.valueOf(100), col.dataType().precision());
        assertNull(col.dataType().scale());
        ColumnConstraint.DefaultValue def = col.find(ColumnConstraint.DefaultValue.class);
        assertNotNull(def);
        assertTrue(def.rawText(), def.rawText().contains("unknown"));
        assertNotNull(def.expr());
        ColumnConstraint.Comment comment = col.find(ColumnConstraint.Comment.class);
        assertNotNull(comment);
        assertEquals("display name", comment.text());
    }

    @Test
    public void parseDecimalPrecisionScale() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "amount DECIMAL(10,2) NOT NULL DEFAULT 0", SqlDialect.MYSQL);
        assertEquals("amount", col.columnName());
        assertEquals(Integer.valueOf(10), col.dataType().precision());
        assertEquals(Integer.valueOf(2), col.dataType().scale());
        ColumnConstraint.DefaultValue def = col.find(ColumnConstraint.DefaultValue.class);
        assertNotNull(def);
        assertTrue(def.expr() instanceof SqlLiteral);
        assertEquals("0", ((SqlLiteral) def.expr()).value());
    }

    @Test
    public void parseUnsignedZerofill() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "age TINYINT(3) UNSIGNED ZEROFILL", SqlDialect.MYSQL);
        assertTrue(col.dataType().has(SqlDataType.TypeAttribute.UNSIGNED));
        assertTrue(col.dataType().has(SqlDataType.TypeAttribute.ZEROFILL));
        assertEquals(Integer.valueOf(3), col.dataType().precision());
    }

    @Test
    public void parseQuotedColumnName() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "`order` INT", SqlDialect.MYSQL);
        assertEquals("order", col.columnName());
    }

    @Test
    public void parseDoublePrecision() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "score DOUBLE PRECISION", SqlDialect.POSTGRES);
        assertEquals("DOUBLE PRECISION", col.dataType().rawTypeName().toUpperCase());
    }

    @Test
    public void parseCharacterVarying() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "title CHARACTER VARYING(64) COLLATE \"C\"", SqlDialect.POSTGRES);
        assertTrue(col.dataType().rawTypeName().toUpperCase().contains("VARYING"));
        assertEquals(Integer.valueOf(64), col.dataType().precision());
        assertNotNull(col.find(ColumnConstraint.Collation.class));
    }

    @Test
    public void parseTimestampWithTimeZone() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "ts TIMESTAMP WITH TIME ZONE", SqlDialect.POSTGRES);
        assertTrue(col.dataType().rawTypeName().toUpperCase().contains("TIME ZONE"));
    }

    @Test
    public void parseSerialBecomesIntegerPlusIdentity() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "id SERIAL PRIMARY KEY", SqlDialect.POSTGRES);
        assertEquals("INTEGER", col.dataType().rawTypeName().toUpperCase());
        assertTrue(col.has(ColumnConstraint.AutoIncrement.class));
        assertTrue(col.has(ColumnConstraint.InlinePrimaryKey.class));
    }

    @Test
    public void parseGeneratedAlwaysAsIdentity() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "id INTEGER GENERATED ALWAYS AS IDENTITY", SqlDialect.POSTGRES);
        ColumnConstraint.AutoIncrement auto = col.find(ColumnConstraint.AutoIncrement.class);
        assertNotNull(auto);
        assertEquals(ColumnConstraint.AutoIncrement.IdentityMode.ALWAYS, auto.mode());
    }

    @Test
    public void parseSqlServerIdentity() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "id INT IDENTITY(1,1) NOT NULL", SqlDialect.SQLSERVER);
        ColumnConstraint.AutoIncrement auto = col.find(ColumnConstraint.AutoIncrement.class);
        assertNotNull(auto);
        assertEquals(Integer.valueOf(1), auto.seed());
        assertEquals(Integer.valueOf(1), auto.increment());
        assertTrue(col.has(ColumnConstraint.NotNull.class));
    }

    @Test
    public void parseOnUpdateCurrentTimestamp() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP",
                SqlDialect.MYSQL);
        assertNotNull(col.find(ColumnConstraint.DefaultValue.class));
        assertNotNull(col.find(ColumnConstraint.OnUpdate.class));
    }

    @Test
    public void parseCharsetAndCollate() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "name VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin", SqlDialect.MYSQL);
        ColumnConstraint.CharacterSet cs = col.find(ColumnConstraint.CharacterSet.class);
        assertNotNull(cs);
        assertEquals("utf8mb4", cs.charset());
        assertEquals("utf8mb4_bin", col.find(ColumnConstraint.Collation.class).collation());
    }

    @Test
    public void parseNvarcharNational() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "title NVARCHAR(200)", SqlDialect.SQLSERVER);
        assertTrue(col.dataType().has(SqlDataType.TypeAttribute.NATIONAL));
    }

    @Test
    public void parseVarcharMax() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "body VARCHAR(MAX)", SqlDialect.SQLSERVER);
        assertTrue(col.dataType().rawTypeName().toUpperCase().contains("MAX"));
    }

    @Test
    public void parseTableConstraintPrimaryKey() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "PRIMARY KEY (id, name)", SqlDialect.MYSQL);
        assertTrue(col.tableConstraint());
        assertEquals("PRIMARY KEY (id, name)", col.rawText());
    }

    @Test
    public void parseTableConstraintForeignKey() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id)", SqlDialect.MYSQL);
        assertTrue(col.tableConstraint());
    }

    @Test
    public void parseUnknownDoesNotThrow() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "weird !!!", SqlDialect.MYSQL);
        assertNotNull(col);
        assertEquals("weird !!!", col.rawText());
    }

    @Test
    public void parseNullAndEmpty() {
        ColumnDefinition empty = SqlColumnDefinitionParser.parse("", SqlDialect.MYSQL);
        assertEquals("", empty.columnName());
        ColumnDefinition nil = SqlColumnDefinitionParser.parse(null, SqlDialect.MYSQL);
        assertEquals("", nil.columnName());
    }

    @Test
    public void parseFromCreateTable() {
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(
                "CREATE TABLE t ("
                        + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "name VARCHAR(32) DEFAULT 'x',"
                        + "KEY idx_name (name)"
                        + ")",
                SqlDialect.MYSQL);
        List<ColumnDefinition> cols = SqlColumnDefinitionParser.fromDdl(ddl, SqlDialect.MYSQL);
        assertEquals(3, cols.size());
        assertEquals("id", cols.get(0).columnName());
        assertTrue(cols.get(0).has(ColumnConstraint.AutoIncrement.class));
        assertEquals("name", cols.get(1).columnName());
        assertTrue(cols.get(2).tableConstraint());
    }

    @Test
    public void parseInlineUniqueAndNullable() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "email VARCHAR(128) NULL UNIQUE", SqlDialect.MYSQL);
        assertTrue(col.has(ColumnConstraint.Nullable.class));
        assertTrue(col.has(ColumnConstraint.InlineUnique.class));
    }

    @Test
    public void parseOracleNumber() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "amt NUMBER(10,2) NOT NULL", SqlDialect.ORACLE);
        assertEquals("NUMBER", col.dataType().rawTypeName().toUpperCase());
        assertEquals(Integer.valueOf(10), col.dataType().precision());
        assertEquals(Integer.valueOf(2), col.dataType().scale());
    }

    @Test
    public void parseBigserial() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse("id BIGSERIAL", SqlDialect.POSTGRES);
        assertEquals("BIGINT", col.dataType().rawTypeName().toUpperCase());
        assertTrue(col.has(ColumnConstraint.AutoIncrement.class));
    }

    @Test
    public void parseEnumDoesNotThrow() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "status ENUM('NEW','OLD') NOT NULL DEFAULT 'NEW'", SqlDialect.MYSQL);
        assertEquals("status", col.columnName());
        assertEquals("ENUM", col.dataType().rawTypeName().toUpperCase());
        assertTrue(col.has(ColumnConstraint.NotNull.class));
        assertNotNull(col.find(ColumnConstraint.DefaultValue.class));
    }

    @Test
    public void parseDefaultNull() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "note TEXT DEFAULT NULL", SqlDialect.MYSQL);
        ColumnConstraint.DefaultValue def = col.find(ColumnConstraint.DefaultValue.class);
        assertNotNull(def);
        assertTrue(def.expr() instanceof SqlLiteral);
        assertEquals(SqlLiteral.Kind.NULL, ((SqlLiteral) def.expr()).kind());
    }

    @Test
    public void parseGeneratedByDefault() {
        ColumnDefinition col = SqlColumnDefinitionParser.parse(
                "id NUMBER GENERATED BY DEFAULT AS IDENTITY", SqlDialect.ORACLE12);
        ColumnConstraint.AutoIncrement auto = col.find(ColumnConstraint.AutoIncrement.class);
        assertNotNull(auto);
        assertEquals(ColumnConstraint.AutoIncrement.IdentityMode.BY_DEFAULT, auto.mode());
    }
}
