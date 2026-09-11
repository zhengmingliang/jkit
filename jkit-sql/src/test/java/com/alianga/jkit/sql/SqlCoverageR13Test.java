package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBlockStatement;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import org.junit.Assert;
import org.junit.Test;

/**
 * R13：PL/pgSQL DO $$ / ELSIF / EXCEPTION、以及 inline/druid 高收益长尾。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlCoverageR13Test {

    @Test
    public void doDollarQuoteBlock() {
        SqlStatement stmt = SQL.parse("DO $$ BEGIN NULL; END $$", SqlDialect.POSTGRES);
        Assert.assertTrue(stmt instanceof SqlSimpleStatement || stmt instanceof SqlBlockStatement);
    }

    @Test
    public void doDollarWithExceptionAndElsif() {
        String sql = "DO $body$ BEGIN "
                + "IF 1 = 1 THEN NULL; "
                + "ELSIF 1 = 2 THEN NULL; "
                + "ELSE NULL; END IF; "
                + "EXCEPTION WHEN OTHERS THEN NULL; "
                + "END $body$";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.POSTGRES);
        Assert.assertNotNull(stmt);
    }

    @Test
    public void anonymousBeginExceptionWhen() {
        String sql = "BEGIN "
                + "INSERT INTO t VALUES (1); "
                + "EXCEPTION WHEN unique_violation THEN NULL; "
                + "WHEN OTHERS THEN RAISE; "
                + "END";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.POSTGRES);
        Assert.assertTrue(stmt instanceof SqlBlockStatement);
        SqlBlockStatement block = (SqlBlockStatement) stmt;
        Assert.assertFalse(block.bodyStatements().isEmpty());
    }

    @Test
    public void elsifInProcedureBody() {
        String sql = "CREATE PROCEDURE p() BEGIN "
                + "IF a > 1 THEN SET a = 1; "
                + "ELSEIF a > 0 THEN SET a = 0; "
                + "ELSE SET a = -1; END IF; END";
        SqlStatement stmt = SQL.parse(sql, SqlDialect.MYSQL);
        Assert.assertTrue(stmt instanceof SqlDdlStatement);
    }

    @Test
    public void aliasKeywordAfterAs() {
        SqlSelect select = (SqlSelect) SQL.parse("SELECT 1 AS full, name AS cross FROM t", SqlDialect.MYSQL);
        Assert.assertEquals(2, select.selectItems().size());
        Assert.assertEquals("full", select.selectItems().get(0).alias());
        Assert.assertEquals("cross", select.selectItems().get(1).alias());
    }

    @Test
    public void substrFromFor() {
        SQL.parse("SELECT SUBSTR('12345678' FROM 2 FOR 4)", SqlDialect.MYSQL);
    }

    @Test
    public void typedLiterals() {
        SQL.parse("SELECT ceiling(DECIMAL '123456789012345678')", SqlDialect.POSTGRES);
        SQL.parse("SELECT * FROM t WHERE REAL '2019.3' = c", SqlDialect.POSTGRES);
        SQL.parse("SELECT * FROM t WHERE added_time > TIME ? AND added_time < TIME ?", SqlDialect.MYSQL);
    }

    @Test
    public void similarToKeyword() {
        SQL.parse("SELECT * FROM t WHERE w_id SIMILAR TO '/foo/'", SqlDialect.POSTGRES);
        SQL.parse("SELECT * FROM t WHERE (w_id SIMILAR TO '/foo/')", SqlDialect.POSTGRES);
    }

    @Test
    public void postgresArrayTypeCast() {
        SQL.parse("SELECT ARRAY[1, 2]::text[] FROM t", SqlDialect.POSTGRES);
        SQL.parse("SELECT ARRAY[]::text[] FROM t", SqlDialect.POSTGRES);
    }

    @Test
    public void insertReplacePriorityChain() {
        SqlInsert ins = (SqlInsert) SQL.parse(
                "INSERT LOW_PRIORITY DELAYED HIGH_PRIORITY IGNORE INTO t (a) VALUES (1)",
                SqlDialect.MYSQL);
        Assert.assertTrue(ins.lowPriority());
        Assert.assertTrue(ins.delayed());
        Assert.assertTrue(ins.highPriority());
        Assert.assertTrue(ins.ignore());
        SQL.parse("REPLACE DELAYED INTO t SET a = 1", SqlDialect.MYSQL);
    }

    @Test
    public void createViewAlgorithmDefiner() {
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(
                "CREATE ALGORITHM=UNDEFINED DEFINER=`root`@`%` SQL SECURITY DEFINER "
                        + "VIEW v AS SELECT 1",
                SqlDialect.MYSQL);
        Assert.assertEquals("VIEW", ddl.objectType());
    }

    @Test
    public void createProcedureDefinerAtPercent() {
        SQL.parse("CREATE DEFINER=test@% PROCEDURE p() BEGIN DECLARE x INT; END", SqlDialect.MYSQL);
    }

    @Test
    public void groupByAscDesc() {
        SQL.parse("SELECT a FROM t GROUP BY a ASC, b DESC", SqlDialect.MYSQL);
    }

    @Test
    public void informixSkipFirst() {
        SQL.parse("SELECT SKIP 10 FIRST 5 * FROM t", SqlDialect.MYSQL);
        SQL.parse("SELECT SKIP skipVar c1, c2 FROM t", SqlDialect.MYSQL);
    }

    @Test
    public void forJson() {
        SQL.parse("SELECT * FROM t FOR JSON AUTO, ROOT('x')", SqlDialect.SQLSERVER);
    }

    @Test
    public void valuesOrderLimit() {
        SQL.parse("VALUES (1), (2) ORDER BY 1 LIMIT 1", SqlDialect.POSTGRES);
    }

    @Test
    public void keywordColumnsWithoutParen() {
        SQL.parse("SELECT cast, extract, first FROM tableName", SqlDialect.MYSQL);
    }

    @Test
    public void labeledBeginLeave() {
        String sql = "CREATE PROCEDURE sp_name(level int, age int) BEGIN "
                + "DECLARE x, y, z INT; "
                + "lable_1: BEGIN INSERT INTO test VALUES (id, age); LEAVE lable_1; END lable_1; "
                + "END";
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    @Test
    public void containsPredicate() {
        SQL.parse("SELECT shid FROM t WHERE rate_tags CONTAINS ('520')", SqlDialect.MYSQL);
    }

    @Test
    public void tsqueryAtAt() {
        SQL.parse("SELECT * FROM team WHERE search_column @@ to_tsquery('x')", SqlDialect.POSTGRES);
    }

    @Test
    public void inListOfParenthesizedSubqueries() {
        SQL.parse("SELECT * FROM dual WHERE a IN ((SELECT id1), (SELECT id2))", SqlDialect.ORACLE);
    }

    @Test
    public void lateralViewOuter() {
        SQL.parse("SELECT * FROM person LATERAL VIEW OUTER EXPLODE(ARRAY(30, 60)) AS c_age",
                SqlDialect.HIVE);
    }

    @Test
    public void valuesUnionInCte() {
        SQL.parse("WITH w (col1) AS (VALUES ('Header1') UNION ALL SELECT a FROM tab) SELECT * FROM w",
                SqlDialect.POSTGRES);
    }

}
