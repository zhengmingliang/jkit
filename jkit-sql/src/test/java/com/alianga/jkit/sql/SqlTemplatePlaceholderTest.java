package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;

import org.junit.Test;


/**
 * 可配置模板占位符：默认关闭；启用后 {@code @*@}/{@code %s}/{@code <sheet>}/{@code <-…->} 可解析。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlTemplatePlaceholderTest {

    private static SqlParseOptions with(SqlPlaceholders placeholders) {
        return SqlParseOptions.defaults().placeholders(placeholders);
    }

    private static SqlStatement parseEnabled(String sql, SqlPlaceholders ph) {
        return SQL.parse(sql, SqlDialect.MYSQL, with(ph));
    }

    @Test
    public void defaultRejectsAtWrapped() {
        assertFails("select * from t where age > @age@");
    }

    @Test
    public void defaultRejectsPrintf() {
        assertFails("SELECT %s FROM dual");
    }

    @Test
    public void defaultRejectsAngleSheet() {
        assertFails("select * from <20241230.1>");
    }

    @Test
    public void atWrappedParsesComparison() {
        SqlStatement stmt = parseEnabled(
                "select * from test_table5 where age > @age@",
                SqlPlaceholders.create().atWrapped());
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("test_table5", SQL.tables(stmt).get(0));
    }

    @Test
    public void atWrappedParsesChineseColumnCompare() {
        SqlStatement stmt = parseEnabled(
                "select * from t_user where id = 1 and 人数 > @num@ limit 10, 20",
                SqlPlaceholders.create().atWrapped());
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("t_user", SQL.tables(stmt).get(0));
    }

    @Test
    public void printfInSelectAndAlias() {
        SqlStatement stmt = parseEnabled(
                "SELECT %s FROM (SELECT '20221111' AS %s) AS a",
                SqlPlaceholders.create().printf());
        assertEquals(SqlStatementType.SELECT, stmt.type());
    }

    @Test
    public void printfInInListAndGluedKeyword() {
        SqlStatement stmt = parseEnabled(
                "SELECT %sFROM dual WHERE name IN (%s)",
                SqlPlaceholders.create().printf());
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("dual", SQL.tables(stmt).get(0));
    }

    @Test
    public void angleSheetAsTable() {
        SqlStatement stmt = parseEnabled(
                "select * from <20241230.1>",
                SqlPlaceholders.create().angle());
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("<20241230.1>", SQL.tables(stmt).get(0));
    }

    @Test
    public void angleSheetWithHyphenAndUnderscore() {
        SqlStatement stmt = parseEnabled(
                "select * from <20241230_zml.sheet_1>",
                SqlPlaceholders.create().angle());
        assertEquals("<20241230_zml.sheet_1>", SQL.tables(stmt).get(0));
    }

    @Test
    public void arrowAngleSheetVariants() {
        SqlPlaceholders ph = SqlPlaceholders.create().arrowAngle();
        assertEquals("<-20241230.-1->",
                SQL.tables(parseEnabled("select * from <-20241230.-1->", ph)).get(0));
        assertEquals("<-20241230-.-1->",
                SQL.tables(parseEnabled("select * from <-20241230-.-1->", ph)).get(0));
    }

    @Test
    public void customMustachePattern() {
        SqlStatement stmt = parseEnabled(
                "select id from {{users}} where name = {{name}}",
                SqlPlaceholders.create().add("{{*}}"));
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertEquals("{{users}}", SQL.tables(stmt).get(0));
        SqlSchemaStat stat = SQL.stat(stmt);
        assertTrue(stat.tableNames().contains("{{users}}"));
    }

    @Test
    public void commonModelPresetParsesMixed() {
        SqlParseOptions opt = with(SqlPlaceholders.create().commonModelTemplates());
        SqlStatement a = SQL.parse("select * from t where age > @age@", SqlDialect.MYSQL, opt);
        SqlStatement b = SQL.parse("SELECT %s FROM <sheet_1>", SqlDialect.MYSQL, opt);
        SqlStatement c = SQL.parse("select * from <-20241230.1->", SqlDialect.MYSQL, opt);
        assertEquals(SqlStatementType.SELECT, a.type());
        assertEquals(SqlStatementType.SELECT, b.type());
        assertEquals(SqlStatementType.SELECT, c.type());
        assertEquals("<sheet_1>", SQL.tables(b).get(0));
    }

    @Test
    public void moduloStillWorksWhenPrintfEnabled() {
        SqlStatement stmt = parseEnabled(
                "select a % b from t",
                SqlPlaceholders.create().printf());
        assertEquals(SqlStatementType.SELECT, stmt.type());
    }

    @Test
    public void mysqlVariableStillWorksWhenAtWrappedEnabled() {
        // 无收尾 @ 时仍走 VARIABLE
        SqlStatement stmt = parseEnabled(
                "select @age from t",
                SqlPlaceholders.create().atWrapped());
        assertEquals(SqlStatementType.SELECT, stmt.type());
    }

    @Test
    public void intentionalIncompleteStillFails() {
        SqlParseOptions opt = with(SqlPlaceholders.create().commonModelTemplates());
        try {
            SQL.parse("select * from", SqlDialect.MYSQL, opt);
            fail("expected fail");
        } catch (SqlParseException ex) {
            // expected C-style incomplete
        }
    }

    private static void assertFails(String sql) {
        try {
            SQL.parse(sql, SqlDialect.MYSQL);
            fail("expected fail: " + sql);
        } catch (SqlParseException ex) {
            // expected
        }
    }
}
