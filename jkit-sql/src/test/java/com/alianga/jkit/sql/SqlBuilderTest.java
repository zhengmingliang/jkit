package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * {@link SqlBuilder} 快速构建与谓词/语句拼接。
 *
 * @author 郑明亮
 */
public class SqlBuilderTest {

    @Test
    public void selectWhereAndLimit() {
        String sql = SqlBuilder.select("id", "name")
                .from("users", "u")
                .where("u.status = 1")
                .and("u.age > 18")
                .orderBy("u.id")
                .limit(10)
                .toSql();
        String upper = sql.toUpperCase();
        assertTrue(sql, upper.contains("SELECT"));
        assertTrue(sql, sql.contains("id"));
        assertTrue(sql, sql.contains("users"));
        assertTrue(sql, upper.contains("WHERE"));
        assertTrue(sql, upper.contains("AND"));
        assertTrue(sql, upper.contains("ORDER BY"));
        assertTrue(sql, upper.contains("LIMIT"));
        SQL.parse(sql);
    }

    @Test
    public void insertUpdateDelete() {
        String ins = SqlBuilder.insertInto("t")
                .columns("id", "name")
                .values(1, "alice")
                .toSql();
        assertTrue(ins.toUpperCase(), ins.toUpperCase().contains("INSERT"));
        assertTrue(ins, ins.contains("'alice'") || ins.contains("alice"));
        SQL.parse(ins);

        String upd = SqlBuilder.update("t")
                .set("name", "bob")
                .where("id = 1")
                .toSql();
        assertTrue(upd.toUpperCase(), upd.toUpperCase().contains("UPDATE"));
        assertTrue(upd.toUpperCase(), upd.toUpperCase().contains("SET"));
        SQL.parse(upd);

        String del = SqlBuilder.deleteFrom("t").where("id = 1").toSql();
        assertTrue(del.toUpperCase(), del.toUpperCase().contains("DELETE"));
        SQL.parse(del);
    }

    @Test
    public void andOrConcatHelpers() {
        SqlExpr a = SqlBuilder.parsePredicate("a = 1");
        SqlExpr b = SqlBuilder.parsePredicate("b = 2");
        SqlExpr and = SQL.and(a, b);
        assertNotNull(and);
        assertTrue(and instanceof SqlBinaryExpr);
        assertEquals(SqlBinaryOp.AND, ((SqlBinaryExpr) and).operator());

        SqlExpr or = SQL.or(a, b);
        assertEquals(SqlBinaryOp.OR, ((SqlBinaryExpr) or).operator());

        List<SqlStatement> batch = Arrays.asList(
                SQL.parse("SELECT 1"),
                SQL.parse("SELECT 2"));
        String joined = SQL.concat(batch);
        assertTrue(joined, joined.contains(";"));
        assertFalse(joined.contains("@"));
        assertEquals(2, SQL.parseAll(joined).size());
    }

    @Test
    public void builderProducesAstThenFormat() {
        SqlSelect select = SqlBuilder.select("*").from("emp").where("dept = 10").buildSelect();
        assertEquals(1, select.selectItems().size());
        assertNotNull(select.from());
        assertNotNull(select.where());
        String pretty = SQL.format(select);
        assertTrue(pretty.toUpperCase(), pretty.toUpperCase().contains("SELECT"));
    }

    @Test
    public void facadeBuilderEntry() {
        String sql = SQL.builder().from("t").where("id = ?").limit(5).toSql();
        assertTrue(sql.toUpperCase().contains("FROM"));
        assertTrue(sql.contains("?"));
    }

    @Test
    public void dialectAwareLimitOnBuilder() {
        String ss = SqlBuilder.select("id").from("t").limit(3).dialect(SqlDialect.SQLSERVER)
                .toSql(SqlDialect.SQLSERVER);
        assertTrue(ss.toUpperCase(), ss.toUpperCase().contains("TOP"));
    }
}
