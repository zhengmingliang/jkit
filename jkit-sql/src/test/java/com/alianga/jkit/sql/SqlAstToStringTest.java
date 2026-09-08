package com.alianga.jkit.sql;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlTableSource;

import org.junit.Test;

/**
 * 表达式 / 语句 AST {@code toString()} 输出可读 SQL，而非 {@code Class@hash}。
 *
 * @author 郑明亮
 */
public class SqlAstToStringTest {

    @Test
    public void exprToStringContainsReadableSql() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT id, name FROM users u WHERE u.age > 18 AND u.status = 'ok'");
        SqlExpr where = select.where();
        String s = where.toString();
        assertFalse("must not be Class@hash: " + s, s.contains("@"));
        assertTrue(s, s.contains("age"));
        assertTrue(s, s.contains(">") || s.contains("18"));
        assertTrue(s, s.contains("status") || s.contains("ok"));

        SqlExpr left = ((SqlBinaryExpr) where).left();
        String leftStr = left.toString();
        assertFalse(leftStr, leftStr.contains("@"));
        assertTrue(leftStr, leftStr.contains("age"));
    }

    @Test
    public void selectItemAndTableSourceToString() {
        SqlSelect select = (SqlSelect) SQL.parse("SELECT u.id AS uid FROM users u");
        SqlSelectItem item = select.selectItems().get(0);
        String itemSql = item.toString();
        assertFalse(itemSql, itemSql.contains("@"));
        assertTrue(itemSql, itemSql.contains("id"));
        assertTrue(itemSql, itemSql.toUpperCase().contains("AS") || itemSql.contains("uid"));

        SqlTableSource from = select.from();
        String fromSql = from.toString();
        assertFalse(fromSql, fromSql.contains("@"));
        assertTrue(fromSql, fromSql.contains("users"));
    }

    @Test
    public void statementAndLimitToString() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t WHERE id = 1 LIMIT 10 OFFSET 5");
        String stmtSql = stmt.toString();
        assertFalse(stmtSql, stmtSql.contains("@"));
        assertTrue(stmtSql.toUpperCase(), stmtSql.toUpperCase().contains("SELECT"));
        assertTrue(stmtSql, stmtSql.contains("t"));

        SqlLimit limit = ((SqlSelect) stmt).limit();
        String lim = limit.toString();
        assertFalse(lim, lim.contains("@"));
        assertTrue(lim.toUpperCase(), lim.toUpperCase().contains("LIMIT"));
        assertTrue(lim, lim.contains("10"));
    }

    @Test
    public void functionAndCaseExprToString() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT CASE WHEN a > 1 THEN 'x' ELSE 'y' END, COALESCE(b, 0) FROM t");
        String caseSql = select.selectItems().get(0).expr().toString();
        assertFalse(caseSql, caseSql.contains("@"));
        assertTrue(caseSql.toUpperCase(), caseSql.toUpperCase().contains("CASE"));
        assertTrue(caseSql, caseSql.contains("THEN") || caseSql.contains("x"));

        String fnSql = select.selectItems().get(1).expr().toString();
        assertFalse(fnSql, fnSql.contains("@"));
        assertTrue(fnSql.toUpperCase(), fnSql.toUpperCase().contains("COALESCE"));
    }
}
