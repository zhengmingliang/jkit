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

    @Test
    public void leftJoinGroupByHaving() {
        String sql = SqlBuilder.select("u.id", "count(1)")
                .from("users", "u")
                .leftJoin("orders", "u.id = orders.uid")
                .groupBy("u.id")
                .having("count(1) > 1")
                .toSql();
        String upper = sql.toUpperCase();
        assertTrue(sql, upper.contains("LEFT"));
        assertTrue(sql, upper.contains("JOIN"));
        assertTrue(sql, upper.contains("GROUP BY"));
        assertTrue(sql, upper.contains("HAVING"));
        SQL.parse(sql);
    }
    @Test
    public void rightJoinUnionWithDistinct() {
        String sql = SqlBuilder.select("u.id")
                .distinct()
                .from("users", "u")
                .rightJoin("depts", "u.dept = depts.id")
                .crossJoin("flags")
                .toSql();
        String upper = sql.toUpperCase();
        assertTrue(sql, upper.contains("DISTINCT"));
        assertTrue(sql, upper.contains("RIGHT"));
        assertTrue(sql, upper.contains("CROSS"));
        SQL.parse(sql);

        String unionSql = SqlBuilder.select("id").from("a")
                .union(SqlBuilder.select("id").from("b"))
                .unionAll(SqlBuilder.select("id").from("c"))
                .toSql();
        String uu = unionSql.toUpperCase();
        assertTrue(unionSql, uu.contains("UNION"));
        assertTrue(unionSql, uu.contains("UNION ALL"));
        SQL.parse(unionSql);

        String withSql = SqlBuilder.select("*").from("c")
                .with("c", "SELECT id FROM t WHERE active = 1")
                .toSql();
        assertTrue(withSql.toUpperCase(), withSql.toUpperCase().contains("WITH"));
        assertTrue(withSql, withSql.contains("c"));
        SQL.parse(withSql);

        String withBuilder = SqlBuilder.select("id").from("cte")
                .with("cte", SqlBuilder.select("id").from("src").where("x = 1"))
                .fullJoin("other", "cte.id = other.id")
                .toSql();
        assertTrue(withBuilder.toUpperCase(), withBuilder.toUpperCase().contains("FULL"));
        SQL.parse(withBuilder);
    }

    @Test
    public void limitOffsetDialectAwareOracleRownum() {
        // 用户复现：无先验 dialect，仅 toSql(ORACLE)
        String sql = SqlBuilder.select("id", "name", "age").from("t_user", "t")
                .where("id > 1").and("name like '%明%'").limit(10).offset(20)
                .toSql(SqlDialect.ORACLE);
        String upper = sql.toUpperCase();
        assertFalse("must not emit MySQL LIMIT for Oracle: " + sql, upper.contains("LIMIT"));
        assertTrue(sql, upper.contains("ROWNUM"));
        assertTrue(sql, upper.contains("RN") && upper.contains(">"));
        assertTrue(sql, upper.contains("ROWNUM") && (upper.contains("<=") || upper.contains("<")));
        // 与 SQL.setPage 同精神：offset 20 + limit 10 → RN > 20 且 ROWNUM <= 30
        assertTrue(sql, upper.contains("20"));
        assertTrue(sql, upper.contains("30"));
        SQL.parse(sql, SqlDialect.ORACLE);
    }

    @Test
    public void limitOffsetDialectAwareOracle12Fetch() {
        String sql = SqlBuilder.select("id", "name", "age").from("t_user", "t")
                .where("id > 1").and("name like '%明%'").limit(10).offset(20)
                .toSql(SqlDialect.ORACLE12);
        String upper = sql.toUpperCase();
        assertFalse("ORACLE12 must not use LIMIT keyword: " + sql, upper.contains("LIMIT"));
        assertTrue(sql, upper.contains("OFFSET"));
        assertTrue(sql, upper.contains("FETCH"));
        assertTrue(sql, upper.contains("20"));
        assertTrue(sql, upper.contains("10"));
        SQL.parse(sql, SqlDialect.ORACLE12);
    }

    @Test
    public void limitOffsetMysqlStillCommaOrLimitOffset() {
        String sql = SqlBuilder.select("id", "name", "age").from("t_user", "t")
                .where("id > 1").and("name like '%明%'").limit(10).offset(20)
                .toSql(SqlDialect.MYSQL);
        String upper = sql.toUpperCase();
        assertTrue(sql, upper.contains("LIMIT"));
        assertTrue(sql, sql.contains("20") && sql.contains("10"));
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    @Test
    public void limitOffsetSqlServerUsesFetch() {
        String sql = SqlBuilder.select("id").from("t").limit(10).offset(20)
                .toSql(SqlDialect.SQLSERVER);
        String upper = sql.toUpperCase();
        assertTrue(sql, upper.contains("OFFSET"));
        assertTrue(sql, upper.contains("FETCH"));
        assertFalse(sql, upper.contains("LIMIT"));
        SQL.parse(sql, SqlDialect.SQLSERVER);
    }

    @Test
    public void dialectOracleThenToSqlWithoutArg() {
        String sql = SqlBuilder.select("id", "name").from("t_user", "t")
                .where("id > 1").limit(10).offset(20)
                .dialect(SqlDialect.ORACLE)
                .toSql();
        String upper = sql.toUpperCase();
        assertFalse(sql, upper.contains("LIMIT"));
        assertTrue(sql, upper.contains("ROWNUM"));
        SQL.parse(sql, SqlDialect.ORACLE);
    }

}
