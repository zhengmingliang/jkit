package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlOverExpr;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.ast.SqlWindowDefinition;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SQL 解析器功能与兼容性测试。
 *
 * @author 郑明亮
 */
public class SqlParserTest {

    /**
     * 基本 SELECT 与表列抽取。
     */
    @Test
    public void parseSimpleSelect() {
        SqlStatement stmt = SQL.parse(
                "SELECT u.id, u.name FROM users u WHERE u.age > 18 ORDER BY u.id DESC LIMIT 10");
        assertEquals(SqlStatementType.SELECT, stmt.type());
        assertTrue(stmt.isReadOnly());
        List<String> tables = SQL.tables(stmt);
        assertEquals(1, tables.size());
        assertEquals("users", tables.get(0));
        SqlSchemaStat stat = SQL.stat(stmt);
        assertTrue(stat.getColumns().contains("u.id") || stat.getColumns().contains("id"));
        String formatted = SQL.toSqlString(stmt);
        assertTrue(formatted, formatted.contains("SELECT"));
        assertTrue(formatted, formatted.contains("users"));
    }

    /**
     * JOIN / 子查询 / IN。
     */
    @Test
    public void parseJoinAndSubquery() {
        SqlStatement stmt = SQL.parse(
                "SELECT a.id, b.name FROM user a "
                        + "LEFT JOIN order_t b ON a.id = b.uid "
                        + "WHERE a.age > 18 AND a.status IN ('ok', 'wait') "
                        + "AND a.id IN (SELECT user_id FROM vip)");
        List<String> tables = SQL.tables(stmt);
        assertTrue(tables.toString(), tables.contains("user"));
        assertTrue(tables.toString(), tables.contains("order_t"));
        assertTrue(tables.toString(), tables.contains("vip"));
    }

    /**
     * INSERT / UPDATE / DELETE。
     */
    @Test
    public void parseDml() {
        SqlInsert insert = (SqlInsert) SQL.parse(
                "INSERT INTO t (id, name) VALUES (1, 'a'), (2, 'b') "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name)");
        assertEquals("t", insert.table().name().simpleName());
        assertEquals(2, insert.valuesList().size());
        assertFalse(insert.duplicateUpdates().isEmpty());

        SqlUpdate update = (SqlUpdate) SQL.parse("UPDATE t SET name = 'x' WHERE id = ?");
        assertEquals(1, update.setList().size());
        assertNotNull(update.where());

        SqlDelete delete = (SqlDelete) SQL.parse("DELETE FROM t WHERE id IN (1, 2) LIMIT 1");
        assertNotNull(delete.where());
        assertNotNull(delete.limit());
    }

    /**
     * WITH CTE。
     */
    @Test
    public void parseWith() {
        SqlStatement stmt = SQL.parse(
                "WITH c AS (SELECT id FROM t) SELECT * FROM c WHERE id > 1");
        assertFalse(stmt.withItems().isEmpty());
        assertEquals("c", stmt.withItems().get(0).name().simpleName());
        assertTrue(SQL.tables(stmt).contains("t"));
    }

    /**
     * UNION。
     */
    @Test
    public void parseUnion() {
        SqlSelect select = (SqlSelect) SQL.parse("SELECT 1 UNION ALL SELECT 2");
        assertNotNull(select.union());
        assertEquals("UNION ALL", select.unionOp());
    }

    /**
     * 改写：LIMIT / WHERE / 换表。
     */
    @Test
    public void rewrite() {
        SqlStatement stmt = SQL.parse("SELECT * FROM users WHERE status = 1");
        SQL.addLimit(stmt, 100);
        assertNotNull(((SqlSelect) stmt).limit());
        SQL.andWhere(stmt, "tenant_id = ?");
        String sql = SQL.toSqlString(stmt);
        assertTrue(sql, sql.contains("tenant_id"));
        SQL.replaceTable(stmt, "users", "users_archive");
        assertTrue(SQL.toSqlString(stmt).contains("users_archive"));
    }

    /**
     * GBase 系统表查询（真实语句）。
     */
    @Test
    public void parseGbase() {
        SqlStatement stmt = SQL.parse(
                "SELECT * FROM information_schema.CLUSTER_TABLE_SEGMENTS "
                        + "WHERE table_schema = 'eoai' AND table_name = 'fct_agt_savinf'");
        List<String> tables = SQL.tables(stmt);
        assertEquals("information_schema.CLUSTER_TABLE_SEGMENTS", tables.get(0));
    }

    /**
     * 多语句。
     */
    @Test
    public void parseAll() {
        List<SqlStatement> all = SQL.parseAll("SELECT 1; DELETE FROM t WHERE id=1;");
        assertEquals(2, all.size());
        assertTrue(all.get(0).isReadOnly());
        assertFalse(all.get(1).isReadOnly());
    }

    /**
     * MySQL {@code ||} 为 OR；PG 为拼接。
     */
    @Test
    public void dialectPipes() {
        SqlSelect mysql = (SqlSelect) SQL.parse("SELECT 1 FROM t WHERE a || b", SqlDialect.MYSQL);
        assertNotNull(mysql.where());
        SqlSelect pg = (SqlSelect) SQL.parse("SELECT 1 FROM t WHERE a || b", SqlDialect.POSTGRES);
        assertNotNull(pg.where());
        assertTrue(SQL.toSqlString(pg, SqlDialect.POSTGRES).contains("||"));
    }

    /**
     * CREATE / DROP。
     */
    @Test
    public void parseDdl() {
        SqlStatement create = SQL.parse(
                "CREATE TABLE IF NOT EXISTS t (id INT PRIMARY KEY, name VARCHAR(32))");
        assertEquals(SqlStatementType.CREATE, create.type());
        SqlStatement drop = SQL.parse("DROP TABLE IF EXISTS t, s");
        assertEquals(SqlStatementType.DROP, drop.type());
    }

    /**
     * CASE / 函数 / 绑定变量。
     */
    @Test
    public void parseExpr() {
        SqlStatement stmt = SQL.parse(
                "SELECT COUNT(DISTINCT id), CASE WHEN x > 0 THEN 'a' ELSE 'b' END "
                        + "FROM t WHERE name LIKE :name AND id = ?");
        assertEquals(SqlStatementType.SELECT, stmt.type());
        String sql = SQL.toSqlString(stmt);
        assertTrue(sql, sql.contains("COUNT"));
        assertTrue(sql, sql.contains("CASE"));
    }

    /**
     * 非法 SQL 带行列号。
     */
    @Test
    public void parseErrorPosition() {
        try {
            SQL.parse("SELEKT * FROM t");
        } catch (SqlParseException e) {
            assertTrue(e.getMessage(), e.line() >= 1);
            assertTrue(e.column() >= 1);
            return;
        }
        throw new AssertionError("expected SqlParseException");
    }

    /**
     * 注释与引号标识符。
     */
    @Test
    public void commentsAndQuotes() {
        SqlStatement stmt = SQL.parse(
                "SELECT `id` -- comment\n FROM `user` /* block */ WHERE id = 'it''s'");
        assertEquals("user", SQL.tables(stmt).get(0));
    }

    /**
     * 窗口函数 OVER PARTITION BY / ORDER BY / ROWS 帧。
     */
    @Test
    public void parseWindowFunction() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT id, ROW_NUMBER() OVER (PARTITION BY dept ORDER BY score DESC "
                        + "ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) rn FROM emp");
        SqlSelectItem item = select.selectItems().get(1);
        assertTrue(item.expr() instanceof SqlFunctionExpr);
        SqlFunctionExpr fn = (SqlFunctionExpr) item.expr();
        assertTrue(fn.over() instanceof SqlOverExpr);
        SqlOverExpr over = (SqlOverExpr) fn.over();
        assertEquals(1, over.partitionBy().size());
        assertEquals(1, over.orderBy().size());
        assertEquals("ROWS", over.frameUnit());
        assertEquals("UNBOUNDED PRECEDING", over.frameStart());
        assertEquals("CURRENT ROW", over.frameEnd());
        String sql = SQL.toSqlString(select);
        assertTrue(sql, sql.contains("OVER"));
        assertTrue(sql, sql.contains("PARTITION"));
    }

    /**
     * EXTRACT / TRIM / SUBSTRING 特殊语法。
     */
    @Test
    public void parseSpecialFunctions() {
        SQL.parse("SELECT EXTRACT(YEAR FROM created_at), TRIM(BOTH ' ' FROM name), "
                + "SUBSTRING(name FROM 1 FOR 2) FROM t");
        SQL.parse("SELECT COUNT(*) FILTER (WHERE status = 1) FROM t");
    }

    /**
     * INSERT SELECT、PG ON CONFLICT、行构造 IN（Druid 能解析、旧版 JSqlParser 常挂）。
     */
    @Test
    public void parseInsertSelectConflictAndRowIn() {
        SqlInsert insertSelect = (SqlInsert) SQL.parse("INSERT INTO dest (id, name) SELECT id, name FROM src");
        assertNotNull(insertSelect.query());
        assertTrue(SQL.tables(insertSelect).contains("dest"));
        assertTrue(SQL.tables(insertSelect).contains("src"));

        SqlInsert conflict = (SqlInsert) SQL.parse(
                "INSERT INTO t (id, name) VALUES (1, 'a') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
                SqlDialect.POSTGRES);
        assertTrue(conflict.onConflict());
        assertEquals("id", conflict.conflictTarget().get(0).simpleName());

        SQL.parse("SELECT * FROM biz WHERE (ta_code, manager_code) IN ((?, ?), (?, ?))");
    }

    /**
     * DISTINCT ON、RETURNING、参数抽取。
     */
    @Test
    public void parsePgAndParameters() {
        SqlSelect distinctOn = (SqlSelect) SQL.parse(
                "SELECT DISTINCT ON (user_id) user_id, created_at FROM log ORDER BY user_id, created_at DESC",
                SqlDialect.POSTGRES);
        assertEquals(1, distinctOn.distinctOn().size());

        SqlUpdate upd = (SqlUpdate) SQL.parse(
                "UPDATE t SET n = n + 1 WHERE id = :id RETURNING n", SqlDialect.POSTGRES);
        assertNotNull(upd.returning());

        List<String> params = SQL.parameters("SELECT * FROM t WHERE id = ? AND name = :name AND age = ?");
        assertEquals(3, params.size());
        assertEquals("?", params.get(0));
        assertEquals(":name", params.get(1));
        assertEquals("?", params.get(2));
    }

    /**
     * SHOW CREATE TABLE / SHOW COLUMNS FROM 抽表名。
     */
    @Test
    public void parseShowCreateTable() {
        assertEquals("abc", SQL.tables("SHOW CREATE TABLE abc").get(0));
        assertEquals("t", SQL.tables("SHOW COLUMNS FROM t").get(0));
        SQL.parse("SELECT DATE '2020-01-01', TIMESTAMP '2020-01-01 00:00:00' FROM dual",
                SqlDialect.POSTGRES);
    }

    /**
     * ALTER TABLE ADD COLUMN 抽列名。
     */
    @Test
    public void parseAlterAddColumn() {
        com.alianga.jkit.sql.ast.SqlDdlStatement ddl =
                (com.alianga.jkit.sql.ast.SqlDdlStatement) SQL.parse(
                        "ALTER TABLE users ADD COLUMN age INT DEFAULT 0");
        assertEquals(SqlStatementType.ALTER, ddl.type());
        assertEquals("users", ddl.names().get(0).simpleName());
        assertEquals("age", ddl.columns().get(0).simpleName());
    }

    /**
     * 简单 SQL 解析应达到生产可用吞吐（对标 Druid 手写解析器这一档，而不是 JavaCC）。
     */
    @Test
    public void parseThroughput() {
        String sql = "SELECT id, name, age FROM user WHERE id = ?";
        SQL.parse(sql);
        int n = 20000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            SQL.parse(sql);
        }
        long ns = System.nanoTime() - start;
        long per = ns / n;
        System.out.println("jkit-sql parse ns/op=" + per + " totalMs=" + (ns / 1_000_000));
        assertTrue("too slow: " + per + " ns/op", per < 200_000L);
    }
    /**
     * P0.3：SchemaStat 收集 WHERE / ORDER BY / GROUP BY。
     */
    @Test
    public void schemaStatConditionsOrderGroup() {
        SqlSchemaStat stat = SQL.stat("SELECT a FROM t WHERE a > 1 GROUP BY a ORDER BY b");
        assertTrue(stat.getConditions().toString(), stat.getConditions().contains("a > 1"));
        assertTrue(stat.getOrderByColumns().toString(), stat.getOrderByColumns().contains("b"));
        assertTrue(stat.getGroupByColumns().toString(), stat.getGroupByColumns().contains("a"));
        assertEquals("t", stat.tableNames().get(0));
    }



    /**
     * P0.3：同一表 INSERT…SELECT 同时记写与读。
     */
    @Test
    public void schemaStatMultiAccessInsertSelect() {
        SqlSchemaStat stat = SQL.stat("INSERT INTO t SELECT * FROM t");
        assertEquals(1, stat.tableNames().size());
        assertEquals("t", stat.tableNames().get(0));
        SqlTableAccess access = stat.getTables().get("t");
        assertNotNull(access);
        assertTrue(access.toString(), access.contains(SqlStatementType.INSERT));
        assertTrue(access.toString(), access.contains(SqlStatementType.SELECT));
        assertTrue(access.isRead());
        assertTrue(access.isWrite());
    }

    /**
     * P0.4：ALTER ADD INDEX 结构化并回写。
     */
    @Test
    public void alterAddIndexAstAndFormat() {
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse("ALTER TABLE t ADD INDEX idx_a (a, b)");
        assertEquals("ADD INDEX", ddl.alterAction());
        assertEquals("idx_a", ddl.indexName().qualifiedName());
        assertEquals(2, ddl.indexColumns().size());
        assertEquals("a", ddl.indexColumns().get(0).qualifiedName());
        assertEquals("b", ddl.indexColumns().get(1).qualifiedName());
        String out = SQL.toSqlString(ddl);
        assertTrue(out, out.contains("ADD INDEX"));
        assertTrue(out, out.contains("idx_a"));
        assertTrue(out, out.contains("a") && out.contains("b"));
        SqlDdlStatement again = (SqlDdlStatement) SQL.parse(out);
        assertEquals("ADD INDEX", again.alterAction());
        assertEquals("idx_a", again.indexName().qualifiedName());
    }

    /**
     * P0.4：ALTER DROP INDEX / RENAME TO。
     */
    @Test
    public void alterDropIndexAndRenameTo() {
        SqlDdlStatement drop = (SqlDdlStatement) SQL.parse("ALTER TABLE t DROP INDEX idx_a");
        assertEquals("DROP INDEX", drop.alterAction());
        assertEquals("idx_a", drop.indexName().qualifiedName());
        String dropOut = SQL.toSqlString(drop);
        assertTrue(dropOut, dropOut.contains("DROP INDEX"));
        assertEquals("DROP INDEX", ((SqlDdlStatement) SQL.parse(dropOut)).alterAction());

        SqlDdlStatement rename = (SqlDdlStatement) SQL.parse("ALTER TABLE t RENAME TO t2");
        assertEquals("RENAME TO", rename.alterAction());
        assertEquals("t2", rename.renameTo().qualifiedName());
        String renameOut = SQL.toSqlString(rename);
        assertTrue(renameOut, renameOut.contains("RENAME TO"));
        assertEquals("t2", ((SqlDdlStatement) SQL.parse(renameOut)).renameTo().qualifiedName());
    }

    /**
     * P0.4：CREATE TABLE ENGINE / CHARSET / COMMENT，PARTITION 留 tail。
     */
    @Test
    public void createTableEngineCharsetComment() {
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(
                "CREATE TABLE t (id INT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='hi' "
                        + "PARTITION BY HASH(id)");
        assertEquals("InnoDB", ddl.engine());
        assertEquals("utf8mb4", ddl.charset());
        assertEquals("'hi'", ddl.comment());
        assertNotNull(ddl.tail());
        assertTrue(ddl.tail(), ddl.tail().toUpperCase().contains("PARTITION"));
        String out = SQL.toSqlString(ddl);
        assertTrue(out, out.contains("ENGINE=InnoDB"));
        assertTrue(out, out.contains("CHARSET=utf8mb4"));
        assertTrue(out, out.contains("COMMENT='hi'"));
        SqlDdlStatement again = (SqlDdlStatement) SQL.parse(out);
        assertEquals("InnoDB", again.engine());
        assertEquals("utf8mb4", again.charset());
        assertEquals("'hi'", again.comment());
    }

    /**
     * P0.4：FOR UPDATE OF … SKIP LOCKED 结构化。
     */
    @Test
    public void forUpdateOfSkipLocked() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT * FROM t FOR UPDATE OF a, b SKIP LOCKED");
        assertTrue(select.forUpdate());
        assertEquals(2, select.forUpdateOf().size());
        assertEquals("a", select.forUpdateOf().get(0).qualifiedName());
        assertEquals("b", select.forUpdateOf().get(1).qualifiedName());
        assertEquals("SKIP LOCKED", select.forUpdateWait());
        String out = SQL.toSqlString(select);
        assertTrue(out, out.contains("FOR UPDATE"));
        assertTrue(out, out.contains("OF"));
        assertTrue(out, out.contains("SKIP LOCKED"));
        SqlSelect again = (SqlSelect) SQL.parse(out);
        assertEquals("SKIP LOCKED", again.forUpdateWait());
        assertEquals(2, again.forUpdateOf().size());

        SqlSelect nowait = (SqlSelect) SQL.parse("SELECT id FROM t FOR UPDATE NOWAIT");
        assertEquals("NOWAIT", nowait.forUpdateWait());
        assertTrue(SQL.toSqlString(nowait).contains("NOWAIT"));
    }

    /**
     * SELECT 级 WINDOW 子句：命名窗口定义 + OVER 引用，format 往返。
     */
    @Test
    public void parseSelectLevelWindow() {
        String sql = "SELECT id, SUM(x) OVER w FROM t WINDOW w AS (PARTITION BY a ORDER BY b)";
        SqlSelect select = (SqlSelect) SQL.parse(sql);
        assertEquals(1, select.windows().size());
        SqlWindowDefinition window = select.windows().get(0);
        assertEquals("w", window.name().simpleName());
        assertNotNull(window.spec());
        assertEquals(1, window.spec().partitionBy().size());
        assertEquals(1, window.spec().orderBy().size());
        SqlFunctionExpr fn = (SqlFunctionExpr) select.selectItems().get(1).expr();
        SqlOverExpr over = (SqlOverExpr) fn.over();
        assertEquals("w", over.windowName().simpleName());
        String formatted = SQL.toSqlString(select);
        assertTrue(formatted, formatted.contains("WINDOW"));
        assertTrue(formatted, formatted.contains("OVER"));
        SqlSelect again = (SqlSelect) SQL.parse(formatted);
        assertEquals(1, again.windows().size());
        assertEquals("w", again.windows().get(0).name().simpleName());
    }

    /**
     * 多个 WINDOW 定义。
     */
    @Test
    public void parseMultipleWindows() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT SUM(x) OVER w1, AVG(x) OVER w2 FROM t "
                        + "WINDOW w1 AS (PARTITION BY a), w2 AS (ORDER BY b)");
        assertEquals(2, select.windows().size());
        assertEquals("w1", select.windows().get(0).name().simpleName());
        assertEquals("w2", select.windows().get(1).name().simpleName());
    }

    /**
     * PostgreSQL LATERAL 子查询。
     */
    @Test
    public void parseLateralSubquery() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT * FROM t, LATERAL (SELECT id FROM s WHERE s.tid = t.id) x",
                SqlDialect.POSTGRES);
        assertTrue(select.from() instanceof SqlJoin);
        SqlJoin join = (SqlJoin) select.from();
        assertEquals(SqlJoin.Type.COMMA, join.joinType());
        assertTrue(join.right() instanceof SqlSubqueryTable);
        SqlSubqueryTable sub = (SqlSubqueryTable) join.right();
        assertTrue(sub.lateral());
        assertEquals("x", sub.alias());
        String formatted = SQL.toSqlString(select, SqlDialect.POSTGRES);
        assertTrue(formatted, formatted.contains("LATERAL"));
        SqlSelect again = (SqlSelect) SQL.parse(formatted, SqlDialect.POSTGRES);
        SqlJoin join2 = (SqlJoin) again.from();
        assertTrue(((SqlSubqueryTable) join2.right()).lateral());
    }

    /**
     * SQL Server CROSS APPLY / OUTER APPLY。
     */
    @Test
    public void parseApplyJoins() {
        SqlSelect cross = (SqlSelect) SQL.parse(
                "SELECT * FROM a CROSS APPLY (SELECT TOP 1 id FROM b WHERE b.aid = a.id) x",
                SqlDialect.SQLSERVER);
        SqlJoin j1 = (SqlJoin) cross.from();
        assertEquals(SqlJoin.Type.CROSS_APPLY, j1.joinType());
        assertTrue(j1.right() instanceof SqlSubqueryTable);
        String f1 = SQL.toSqlString(cross, SqlDialect.SQLSERVER);
        assertTrue(f1, f1.contains("CROSS APPLY") || (f1.contains("CROSS") && f1.contains("APPLY")));
        SqlSelect again1 = (SqlSelect) SQL.parse(f1, SqlDialect.SQLSERVER);
        assertEquals(SqlJoin.Type.CROSS_APPLY, ((SqlJoin) again1.from()).joinType());

        SqlSelect outer = (SqlSelect) SQL.parse(
                "SELECT * FROM a OUTER APPLY (SELECT id FROM b WHERE b.aid = a.id) x",
                SqlDialect.SQLSERVER);
        assertEquals(SqlJoin.Type.OUTER_APPLY, ((SqlJoin) outer.from()).joinType());
        String f2 = SQL.toSqlString(outer, SqlDialect.SQLSERVER);
        SqlSelect again2 = (SqlSelect) SQL.parse(f2, SqlDialect.SQLSERVER);
        assertEquals(SqlJoin.Type.OUTER_APPLY, ((SqlJoin) again2.from()).joinType());
    }



    /**
     * WINDOW 继承另一窗口名：{@code w2 AS (w ORDER BY b)} / {@code w3 AS (w)}。
     */
    @Test
    public void parseWindowInheritName() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT SUM(x) OVER w2 FROM t "
                        + "WINDOW w AS (PARTITION BY a), w2 AS (w ORDER BY b)");
        assertEquals(2, select.windows().size());
        SqlOverExpr w2 = select.windows().get(1).spec();
        assertEquals("w", w2.existingWindowName().simpleName());
        assertEquals(1, w2.orderBy().size());
        assertTrue(w2.partitionBy().isEmpty());
        String formatted = SQL.toSqlString(select);
        assertTrue(formatted, formatted.contains("w2"));
        SqlSelect again = (SqlSelect) SQL.parse(formatted);
        assertEquals("w", again.windows().get(1).spec().existingWindowName().simpleName());
        assertEquals(1, again.windows().get(1).spec().orderBy().size());

        SqlSelect only = (SqlSelect) SQL.parse(
                "SELECT RANK() OVER w2 FROM t WINDOW w AS (ORDER BY a), w2 AS (w)");
        assertEquals("w", only.windows().get(1).spec().existingWindowName().simpleName());
        String f2 = SQL.toSqlString(only);
        SqlSelect again2 = (SqlSelect) SQL.parse(f2);
        assertEquals("w", again2.windows().get(1).spec().existingWindowName().simpleName());
    }

    /**
     * FROM UNNEST / TABLE(...) 表函数。
     */
    @Test
    public void parseTableFunctions() {
        SqlSelect unnest = (SqlSelect) SQL.parse(
                "SELECT * FROM UNNEST(arr) AS u(x)", SqlDialect.POSTGRES);
        assertTrue(unnest.from() instanceof SqlFunctionTable);
        SqlFunctionTable ft = (SqlFunctionTable) unnest.from();
        assertFalse(ft.tableKeyword());
        assertEquals("u", ft.alias());
        assertEquals(1, ft.columnAliases().size());
        assertEquals("x", ft.columnAliases().get(0).simpleName());
        assertTrue(ft.function() instanceof SqlFunctionExpr);
        assertEquals("UNNEST", ((SqlFunctionExpr) ft.function()).name().simpleName());
        String f1 = SQL.toSqlString(unnest, SqlDialect.POSTGRES);
        assertTrue(f1, f1.contains("UNNEST"));
        assertTrue(f1, f1.contains("u(x)") || (f1.contains("u") && f1.contains("(x)")));
        SqlSelect again1 = (SqlSelect) SQL.parse(f1, SqlDialect.POSTGRES);
        assertTrue(again1.from() instanceof SqlFunctionTable);
        assertEquals("x", ((SqlFunctionTable) again1.from()).columnAliases().get(0).simpleName());

        SqlSelect tableFn = (SqlSelect) SQL.parse(
                "SELECT * FROM TABLE(fn(1, 2)) t", SqlDialect.ORACLE);
        assertTrue(tableFn.from() instanceof SqlFunctionTable);
        SqlFunctionTable oft = (SqlFunctionTable) tableFn.from();
        assertTrue(oft.tableKeyword());
        assertEquals("t", oft.alias());
        String f2 = SQL.toSqlString(tableFn, SqlDialect.ORACLE);
        assertTrue(f2, f2.contains("TABLE"));
        SqlSelect again2 = (SqlSelect) SQL.parse(f2, SqlDialect.ORACLE);
        assertTrue(((SqlFunctionTable) again2.from()).tableKeyword());

        SqlSelect lateral = (SqlSelect) SQL.parse(
                "SELECT * FROM t, LATERAL UNNEST(t.arr) u", SqlDialect.POSTGRES);
        SqlJoin join = (SqlJoin) lateral.from();
        assertTrue(join.right() instanceof SqlFunctionTable);
        assertTrue(((SqlFunctionTable) join.right()).lateral());
    }

    /**
     * FROM (VALUES ...) AS v(cols) 行构造与列别名。
     */
    @Test
    public void parseValuesTableWithColumnAliases() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT * FROM (VALUES (1), (2)) AS v(id)", SqlDialect.POSTGRES);
        assertTrue(select.from() instanceof SqlValuesTable);
        SqlValuesTable vt = (SqlValuesTable) select.from();
        assertEquals(2, vt.rows().size());
        assertEquals("v", vt.alias());
        assertEquals(1, vt.columnAliases().size());
        assertEquals("id", vt.columnAliases().get(0).simpleName());
        String formatted = SQL.toSqlString(select, SqlDialect.POSTGRES);
        assertTrue(formatted, formatted.contains("VALUES"));
        assertTrue(formatted, formatted.contains("v"));
        SqlSelect again = (SqlSelect) SQL.parse(formatted, SqlDialect.POSTGRES);
        assertTrue(again.from() instanceof SqlValuesTable);
        SqlValuesTable vt2 = (SqlValuesTable) again.from();
        assertEquals(2, vt2.rows().size());
        assertEquals("id", vt2.columnAliases().get(0).simpleName());

        SqlSelect multi = (SqlSelect) SQL.parse(
                "SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS v(id, name)", SqlDialect.POSTGRES);
        SqlValuesTable m = (SqlValuesTable) multi.from();
        assertEquals(2, m.rows().size());
        assertEquals(2, m.columnAliases().size());
        String f2 = SQL.toSqlString(multi, SqlDialect.POSTGRES);
        SqlSelect again2 = (SqlSelect) SQL.parse(f2, SqlDialect.POSTGRES);
        assertEquals(2, ((SqlValuesTable) again2.from()).columnAliases().size());
    }


    /**
     * P1.3：GROUP_CONCAT / STRING_AGG 的 ORDER BY、SEPARATOR、WITHIN GROUP。
     */
    @Test
    public void parseGroupConcatAndStringAgg() {
        SqlSelect gc = (SqlSelect) SQL.parse(
                "SELECT GROUP_CONCAT(name ORDER BY id SEPARATOR ',') FROM t");
        SqlFunctionExpr fn = (SqlFunctionExpr) gc.selectItems().get(0).expr();
        assertEquals("GROUP_CONCAT", fn.name().simpleName());
        assertEquals(1, fn.orderBy().size());
        assertNotNull(fn.separator());
        String f1 = SQL.toSqlString(gc);
        assertTrue(f1, f1.contains("SEPARATOR"));
        assertTrue(f1, f1.contains("ORDER"));
        SQL.parse(f1);

        SqlSelect gc2 = (SqlSelect) SQL.parse(
                "SELECT GROUP_CONCAT(DISTINCT name SEPARATOR ';') FROM t");
        SqlFunctionExpr fn2 = (SqlFunctionExpr) gc2.selectItems().get(0).expr();
        assertTrue(fn2.distinct());
        assertNotNull(fn2.separator());
        SQL.parse(SQL.toSqlString(gc2));

        SqlSelect agg = (SqlSelect) SQL.parse(
                "SELECT STRING_AGG(name, ',' ORDER BY id) FROM t", SqlDialect.POSTGRES);
        SqlFunctionExpr sfn = (SqlFunctionExpr) agg.selectItems().get(0).expr();
        assertEquals(2, sfn.arguments().size());
        assertEquals(1, sfn.orderBy().size());
        assertFalse(sfn.withinGroup());
        String f3 = SQL.toSqlString(agg, SqlDialect.POSTGRES);
        assertTrue(f3, f3.contains("ORDER"));
        SQL.parse(f3, SqlDialect.POSTGRES);

        SqlSelect within = (SqlSelect) SQL.parse(
                "SELECT STRING_AGG(name, ',') WITHIN GROUP (ORDER BY id) FROM t",
                SqlDialect.POSTGRES);
        SqlFunctionExpr wfn = (SqlFunctionExpr) within.selectItems().get(0).expr();
        assertTrue(wfn.withinGroup());
        assertEquals(1, wfn.orderBy().size());
        String f4 = SQL.toSqlString(within, SqlDialect.POSTGRES);
        assertTrue(f4, f4.contains("WITHIN"));
        SqlSelect again = (SqlSelect) SQL.parse(f4, SqlDialect.POSTGRES);
        assertTrue(((SqlFunctionExpr) again.selectItems().get(0).expr()).withinGroup());
    }

    /**
     * P1.3：IF 关键字函数、CONVERT USING / SQL Server CONVERT。
     */
    @Test
    public void parseIfAndConvert() {
        SqlSelect iff = (SqlSelect) SQL.parse("SELECT IF(a > 0, 'y', 'n') FROM t");
        assertEquals("IF", ((SqlFunctionExpr) iff.selectItems().get(0).expr()).name().simpleName());
        String f1 = SQL.toSqlString(iff);
        assertTrue(f1, f1.contains("IF("));
        SQL.parse(f1);

        SqlSelect conv = (SqlSelect) SQL.parse("SELECT CONVERT(name USING utf8) FROM t");
        SqlFunctionExpr cfn = (SqlFunctionExpr) conv.selectItems().get(0).expr();
        assertTrue(cfn.usingCharset());
        String f2 = SQL.toSqlString(conv);
        assertTrue(f2, f2.contains("USING"));
        SqlSelect again = (SqlSelect) SQL.parse(f2);
        assertTrue(((SqlFunctionExpr) again.selectItems().get(0).expr()).usingCharset());

        SqlSelect ss = (SqlSelect) SQL.parse(
                "SELECT CONVERT(varchar(20), name) FROM t", SqlDialect.SQLSERVER);
        String f3 = SQL.toSqlString(ss, SqlDialect.SQLSERVER);
        assertTrue(f3, f3.contains("CONVERT"));
        SQL.parse(f3, SqlDialect.SQLSERVER);
    }

    /**
     * P1.3：JSON -&gt; / -&gt;&gt; / #&gt; / #&gt;&gt; 保留运算符原文。
     */
    @Test
    public void parseJsonPathOps() {
        SqlSelect a = (SqlSelect) SQL.parse("SELECT data -> 'a', data ->> 'b' FROM t", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.JSON_ARROW,
                ((SqlBinaryExpr) a.selectItems().get(0).expr()).operator());
        assertEquals(SqlBinaryOp.JSON_ARROW_TEXT,
                ((SqlBinaryExpr) a.selectItems().get(1).expr()).operator());
        String fa = SQL.toSqlString(a, SqlDialect.POSTGRES);
        assertTrue(fa, fa.contains("->"));
        assertTrue(fa, fa.contains("->>"));
        SQL.parse(fa, SqlDialect.POSTGRES);

        SqlSelect b = (SqlSelect) SQL.parse(
                "SELECT data #> '{x}', data #>> '{x,y}' FROM t", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.JSON_PATH,
                ((SqlBinaryExpr) b.selectItems().get(0).expr()).operator());
        assertEquals(SqlBinaryOp.JSON_PATH_TEXT,
                ((SqlBinaryExpr) b.selectItems().get(1).expr()).operator());
        String fb = SQL.toSqlString(b, SqlDialect.POSTGRES);
        assertTrue(fb, fb.contains("#>"));
        assertTrue(fb, fb.contains("#>>"));
        SQL.parse(fb, SqlDialect.POSTGRES);
    }

    /**
     * P1.3：MATCH ... AGAINST 全文检索。
     */
    @Test
    public void parseMatchAgainst() {
        SqlSelect s = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE MATCH(title, body) AGAINST ('foo' IN BOOLEAN MODE)");
        SqlFunctionExpr match = (SqlFunctionExpr) s.where();
        assertEquals("MATCH", match.name().simpleName());
        assertEquals(2, match.arguments().size());
        assertNotNull(match.against());
        assertTrue(match.againstModifier(), match.againstModifier().contains("BOOLEAN"));
        String f = SQL.toSqlString(s);
        assertTrue(f, f.contains("AGAINST"));
        SqlSelect again = (SqlSelect) SQL.parse(f);
        assertNotNull(((SqlFunctionExpr) again.where()).against());

        SqlSelect s2 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE MATCH(title) AGAINST ('bar')");
        assertNotNull(((SqlFunctionExpr) s2.where()).against());
        SQL.parse(SQL.toSqlString(s2));
    }

    /**
     * P1.3：IS DISTINCT FROM / 数组下标 / ANY·SOME·ALL 子查询。
     */
    @Test
    public void parseDistinctFromArrayAny() {
        SqlSelect d = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE a IS DISTINCT FROM b", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.IS_DISTINCT_FROM, ((SqlBinaryExpr) d.where()).operator());
        SqlSelect d2 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE a IS NOT DISTINCT FROM b", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.IS_NOT_DISTINCT_FROM, ((SqlBinaryExpr) d2.where()).operator());
        SQL.parse(SQL.toSqlString(d, SqlDialect.POSTGRES), SqlDialect.POSTGRES);
        SQL.parse(SQL.toSqlString(d2, SqlDialect.POSTGRES), SqlDialect.POSTGRES);

        SqlSelect arr = (SqlSelect) SQL.parse("SELECT arr[1], arr[1][2] FROM t", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.SUBSCRIPT,
                ((SqlBinaryExpr) arr.selectItems().get(0).expr()).operator());
        SqlBinaryExpr nested = (SqlBinaryExpr) arr.selectItems().get(1).expr();
        assertEquals(SqlBinaryOp.SUBSCRIPT, nested.operator());
        assertEquals(SqlBinaryOp.SUBSCRIPT, ((SqlBinaryExpr) nested.left()).operator());
        String fa = SQL.toSqlString(arr, SqlDialect.POSTGRES);
        assertTrue(fa, fa.contains("arr[1]"));
        SQL.parse(fa, SqlDialect.POSTGRES);

        SqlSelect any = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE x = ANY(SELECT id FROM s)", SqlDialect.POSTGRES);
        String fany = SQL.toSqlString(any, SqlDialect.POSTGRES);
        assertTrue(fany, fany.contains("ANY"));
        SQL.parse(fany, SqlDialect.POSTGRES);

        SqlSelect some = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE x = SOME(SELECT id FROM s)", SqlDialect.POSTGRES);
        SQL.parse(SQL.toSqlString(some, SqlDialect.POSTGRES), SqlDialect.POSTGRES);

        SqlSelect all = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE x <> ALL(arr)", SqlDialect.POSTGRES);
        SQL.parse(SQL.toSqlString(all, SqlDialect.POSTGRES), SqlDialect.POSTGRES);
    }

    /**
     * P1.3：INTERVAL / X'FF' 字面量往返。
     */
    @Test
    public void parseIntervalAndHexLiterals() {
        SqlSelect iv = (SqlSelect) SQL.parse("SELECT INTERVAL '1 day'", SqlDialect.POSTGRES);
        String f1 = SQL.toSqlString(iv, SqlDialect.POSTGRES);
        assertTrue(f1, f1.contains("INTERVAL"));
        assertFalse(f1, f1.contains("INTERVAL("));
        SQL.parse(f1, SqlDialect.POSTGRES);

        SqlSelect iv2 = (SqlSelect) SQL.parse("SELECT INTERVAL 1 DAY");
        String f2 = SQL.toSqlString(iv2);
        assertTrue(f2, f2.contains("INTERVAL"));
        SQL.parse(f2);

        SqlSelect hex = (SqlSelect) SQL.parse("SELECT X'FF', 0xFF");
        String f3 = SQL.toSqlString(hex);
        assertTrue(f3, f3.contains("X'FF'") || f3.contains("X'ff'") || f3.toUpperCase().contains("X'FF'"));
        SQL.parse(f3);
    }


}
