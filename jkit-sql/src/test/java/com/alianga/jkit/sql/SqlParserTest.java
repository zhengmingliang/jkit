package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlOverExpr;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlSimpleStatement;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlStatementType;
import com.alianga.jkit.sql.ast.SqlSubqueryTable;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.ast.SqlValuesTable;
import com.alianga.jkit.sql.ast.SqlWindowDefinition;

import org.junit.Test;

import java.util.List;
import java.util.Set;

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

    @Test
    public void parseComplicateSql() {
        SqlStatement statement = SQL.parse(
                "select * from JER_CASPINFO where isremark='0'  and (isCustomer='0' or isCustomer='' or isCustomer " +
                        "is null)  and reportUnitCode = '8600000011'  and bbq = '202601--'  and taskCode = 'TM2_1'  order by " +
                        "createdatetime desc");
        SqlSchemaStat stat = SQL.stat(statement);
        Set<String> columns = stat.getColumns();
        System.out.println("columns = " + columns);
        List<String> conditions = stat.getConditions();
        List<String> groupByColumns = stat.getGroupByColumns();
        List<String> orderByColumns = stat.getOrderByColumns();
        System.out.println("conditions = " + conditions);
        System.out.println("groupByColumns = " + groupByColumns);
        System.out.println("orderByColumns = " + orderByColumns);
        SqlSelect select = (SqlSelect) statement;
        System.out.println(SQL.toSqlString(SqlRewriter.addLimit(statement, 100, SqlDialect.ORACLE)));
        System.out.println(select.limit());
        System.out.println(SQL.toSqlString(statement));
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
        SqlStatement limited = SQL.addLimit(stmt, 100);
        assertNotNull(((SqlSelect) limited).limit());
        assertTrue("addLimit must clone", ((SqlSelect) stmt).limit() == null);
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

    /**
     * P1.4：Oracle INSERT ALL / INSERT FIRST。
     */
    @Test
    public void parseOracleInsertAllFirst() {
        SqlInsert all = (SqlInsert) SQL.parse(
                "INSERT ALL INTO t1 (id, name) VALUES (s.id, s.name) "
                        + "INTO t2 (id) VALUES (s.id) SELECT id, name FROM src s",
                SqlDialect.ORACLE);
        assertTrue(all.insertAll());
        assertEquals(2, all.branches().size());
        assertEquals("t1", all.branches().get(0).table().name().simpleName());
        assertNotNull(all.query());
        String f1 = SQL.toSqlString(all, SqlDialect.ORACLE);
        assertTrue(f1, f1.contains("INSERT ALL"));
        assertTrue(f1, f1.contains("INTO t1"));
        SQL.parse(f1, SqlDialect.ORACLE);

        SqlInsert first = (SqlInsert) SQL.parse(
                "INSERT FIRST WHEN id > 10 THEN INTO hi (id) VALUES (id) "
                        + "WHEN id > 0 THEN INTO mid (id) VALUES (id) "
                        + "ELSE INTO lo (id) VALUES (id) SELECT id FROM src",
                SqlDialect.ORACLE);
        assertTrue(first.insertFirst());
        assertEquals(3, first.branches().size());
        assertTrue(first.branches().get(2).elseBranch());
        assertNotNull(first.branches().get(0).when());
        String f2 = SQL.toSqlString(first, SqlDialect.ORACLE);
        assertTrue(f2, f2.contains("INSERT FIRST"));
        assertTrue(f2, f2.contains("ELSE"));
        SQL.parse(f2, SqlDialect.ORACLE);
    }

    /**
     * P1.4：PG INSERT … SELECT … ON CONFLICT（含 ON CONSTRAINT）。
     */
    @Test
    public void parsePgInsertSelectOnConflict() {
        SqlInsert nothing = (SqlInsert) SQL.parse(
                "INSERT INTO t (id, name) SELECT id, name FROM s ON CONFLICT (id) DO NOTHING",
                SqlDialect.POSTGRES);
        assertNotNull(nothing.query());
        assertTrue(nothing.onConflict());
        assertTrue(nothing.conflictDoNothing());
        String f1 = SQL.toSqlString(nothing, SqlDialect.POSTGRES);
        assertTrue(f1, f1.contains("ON CONFLICT"));
        assertTrue(f1, f1.contains("DO NOTHING"));
        SQL.parse(f1, SqlDialect.POSTGRES);

        SqlInsert upd = (SqlInsert) SQL.parse(
                "INSERT INTO t (id, name) SELECT id, name FROM s "
                        + "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
                SqlDialect.POSTGRES);
        assertFalse(upd.conflictDoNothing());
        assertEquals(1, upd.duplicateUpdates().size());
        SQL.parse(SQL.toSqlString(upd, SqlDialect.POSTGRES), SqlDialect.POSTGRES);

        SqlInsert cons = (SqlInsert) SQL.parse(
                "INSERT INTO t (id) VALUES (1) ON CONFLICT ON CONSTRAINT t_pkey DO NOTHING",
                SqlDialect.POSTGRES);
        assertNotNull(cons.conflictConstraint());
        assertEquals("t_pkey", cons.conflictConstraint().simpleName());
        String f3 = SQL.toSqlString(cons, SqlDialect.POSTGRES);
        assertTrue(f3, f3.contains("ON CONSTRAINT"));
        SQL.parse(f3, SqlDialect.POSTGRES);
    }

    /**
     * P1.4：PG UPDATE … FROM；DELETE … USING。
     */
    @Test
    public void parsePgUpdateFromAndDeleteUsing() {
        SqlUpdate upd = (SqlUpdate) SQL.parse(
                "UPDATE t SET a = s.a FROM s WHERE t.id = s.id",
                SqlDialect.POSTGRES);
        assertNotNull(upd.from());
        String f1 = SQL.toSqlString(upd, SqlDialect.POSTGRES);
        assertTrue(f1, f1.contains("FROM"));
        SQL.parse(f1, SqlDialect.POSTGRES);

        SqlUpdate upd2 = (SqlUpdate) SQL.parse(
                "UPDATE t SET a = s.a FROM s JOIN u ON s.uid = u.id WHERE t.id = s.id",
                SqlDialect.POSTGRES);
        assertNotNull(upd2.from());
        SQL.parse(SQL.toSqlString(upd2, SqlDialect.POSTGRES), SqlDialect.POSTGRES);

        SqlDelete del = (SqlDelete) SQL.parse(
                "DELETE FROM t USING s WHERE t.id = s.id",
                SqlDialect.POSTGRES);
        assertTrue(del.usingKeyword());
        assertNotNull(del.from());
        String f2 = SQL.toSqlString(del, SqlDialect.POSTGRES);
        assertTrue(f2, f2.contains("USING"));
        assertTrue(f2, f2.contains("DELETE FROM"));
        SQL.parse(f2, SqlDialect.POSTGRES);

        SqlDelete del2 = (SqlDelete) SQL.parse(
                "DELETE FROM t USING s, u WHERE t.id = s.id AND s.uid = u.id",
                SqlDialect.POSTGRES);
        assertTrue(del2.usingKeyword());
        SQL.parse(SQL.toSqlString(del2, SqlDialect.POSTGRES), SqlDialect.POSTGRES);

        // MySQL USING 多表删除仍可用
        SqlDelete mysql = (SqlDelete) SQL.parse(
                "DELETE t USING t JOIN s ON t.id = s.id WHERE s.flag = 1");
        assertTrue(mysql.usingKeyword());
        String f3 = SQL.toSqlString(mysql);
        assertTrue(f3, f3.contains("USING"));
        SQL.parse(f3);
    }

    /**
     * P1.4：MERGE 多 WHEN MATCHED AND、NOT MATCHED BY SOURCE。
     */
    @Test
    public void parseMergeMultipleWhenAndBySource() {
        SqlMerge merge = (SqlMerge) SQL.parse(
                "MERGE INTO t USING s ON t.id = s.id "
                        + "WHEN MATCHED AND t.flag = 1 THEN UPDATE SET t.a = s.a "
                        + "WHEN MATCHED AND t.flag = 0 THEN DELETE "
                        + "WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a) "
                        + "WHEN NOT MATCHED BY SOURCE THEN DELETE");
        assertEquals(4, merge.whens().size());
        assertEquals(SqlMergeWhen.MatchKind.MATCHED, merge.whens().get(0).kind());
        assertNotNull(merge.whens().get(0).andPredicate());
        assertTrue(merge.whens().get(1).delete());
        assertEquals(SqlMergeWhen.MatchKind.NOT_MATCHED, merge.whens().get(2).kind());
        assertEquals(SqlMergeWhen.MatchKind.NOT_MATCHED_BY_SOURCE, merge.whens().get(3).kind());
        String f = SQL.toSqlString(merge);
        assertTrue(f, f.contains("AND"));
        assertTrue(f, f.contains("BY SOURCE"));
        assertFalse(f, f.contains("INSERT INTO ("));
        SQL.parse(f);

        SqlMerge m2 = (SqlMerge) SQL.parse(
                "MERGE INTO tgt USING src ON tgt.id = src.id "
                        + "WHEN NOT MATCHED BY TARGET THEN INSERT (id) VALUES (src.id) "
                        + "WHEN MATCHED THEN UPDATE SET tgt.n = src.n");
        assertEquals(SqlMergeWhen.MatchKind.NOT_MATCHED_BY_TARGET, m2.whens().get(0).kind());
        SQL.parse(SQL.toSqlString(m2));
    }

    /**
     * P1.4：SQL Server OUTPUT INSERTED.* / DELETED.*。
     */
    @Test
    public void parseSqlServerOutputClause() {
        SqlInsert ins = (SqlInsert) SQL.parse(
                "INSERT INTO t (id, name) OUTPUT INSERTED.id, INSERTED.name VALUES (1, 'a')",
                SqlDialect.SQLSERVER);
        assertEquals(2, ins.output().size());
        String f1 = SQL.toSqlString(ins, SqlDialect.SQLSERVER);
        assertTrue(f1, f1.contains("OUTPUT"));
        assertTrue(f1, f1.contains("INSERTED"));
        SQL.parse(f1, SqlDialect.SQLSERVER);

        SqlUpdate upd = (SqlUpdate) SQL.parse(
                "UPDATE t SET name = 'x' OUTPUT INSERTED.name, DELETED.name WHERE id = 1",
                SqlDialect.SQLSERVER);
        assertEquals(2, upd.output().size());
        SQL.parse(SQL.toSqlString(upd, SqlDialect.SQLSERVER), SqlDialect.SQLSERVER);

        SqlDelete del = (SqlDelete) SQL.parse(
                "DELETE FROM t OUTPUT DELETED.* WHERE id = 1",
                SqlDialect.SQLSERVER);
        assertEquals(1, del.output().size());
        String f3 = SQL.toSqlString(del, SqlDialect.SQLSERVER);
        assertTrue(f3, f3.contains("DELETED"));
        SQL.parse(f3, SqlDialect.SQLSERVER);

        SqlMerge merge = (SqlMerge) SQL.parse(
                "MERGE INTO t USING s ON t.id = s.id "
                        + "WHEN MATCHED THEN UPDATE SET t.a = s.a "
                        + "WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a) "
                        + "OUTPUT INSERTED.*, DELETED.*",
                SqlDialect.SQLSERVER);
        assertEquals(2, merge.output().size());
        String f4 = SQL.toSqlString(merge, SqlDialect.SQLSERVER);
        assertTrue(f4, f4.contains("OUTPUT"));
        SQL.parse(f4, SqlDialect.SQLSERVER);
    }


    /**
     * P1.5：CREATE VIEW / CREATE OR REPLACE VIEW。
     */
    @Test
    public void p15CreateViewAndOrReplace() {
        SqlDdlStatement view = (SqlDdlStatement) SQL.parse("CREATE VIEW v AS SELECT id FROM t");
        assertEquals("VIEW", view.objectType());
        assertFalse(view.orReplace());
        assertNotNull(view.query());
        assertTrue(SQL.tables(view).contains("v"));
        assertTrue(SQL.tables(view).contains("t"));

        SqlDdlStatement replace = (SqlDdlStatement) SQL.parse(
                "CREATE OR REPLACE VIEW v AS SELECT 1 AS n");
        assertTrue(replace.orReplace());
        String fmt = SQL.toSqlString(replace);
        assertTrue(fmt, fmt.contains("OR REPLACE"));
        assertEquals(SqlStatementType.CREATE, SQL.parse(fmt).type());
    }

    /**
     * P1.5：CREATE PROCEDURE / FUNCTION / TRIGGER / EVENT 抽名 + tail，不抛 unsupported。
     */
    @Test
    public void p15CreateRoutineAndEvent() {
        SqlDdlStatement proc = (SqlDdlStatement) SQL.parse(
                "CREATE PROCEDURE sp_add(IN a INT) BEGIN SELECT a; END");
        assertEquals("PROCEDURE", proc.objectType());
        assertEquals("sp_add", proc.names().get(0).simpleName());
        assertNotNull(proc.tail());
        assertTrue(proc.tail(), proc.tail().contains("BEGIN"));

        SqlDdlStatement fn = (SqlDdlStatement) SQL.parse(
                "CREATE FUNCTION fn_one() RETURNS INT RETURN 1");
        assertEquals("FUNCTION", fn.objectType());
        assertEquals("fn_one", fn.names().get(0).simpleName());

        SqlDdlStatement trg = (SqlDdlStatement) SQL.parse(
                "CREATE TRIGGER trg_bi BEFORE INSERT ON t FOR EACH ROW SET NEW.id = 1");
        assertEquals("TRIGGER", trg.objectType());
        assertEquals("trg_bi", trg.names().get(0).simpleName());

        SqlDdlStatement ev = (SqlDdlStatement) SQL.parse(
                "CREATE EVENT ev_daily ON SCHEDULE EVERY 1 DAY DO SELECT 1");
        assertEquals("EVENT", ev.objectType());
        assertEquals("ev_daily", ev.names().get(0).simpleName());
        assertEquals(1, ev.names().size());
    }

    /**
     * P1.5：BEGIN … END / DECLARE 作为 OTHER，批处理不挂。
     */
    @Test
    public void p15BeginDeclareBatch() {
        SqlSimpleStatement block = (SqlSimpleStatement) SQL.parse("BEGIN SELECT 1; END");
        assertEquals(SqlStatementType.OTHER, block.type());
        assertTrue(block.text(), block.text().startsWith("BEGIN"));
        assertTrue(block.text(), block.text().contains("END"));

        SqlSimpleStatement decl = (SqlSimpleStatement) SQL.parse("DECLARE x INT DEFAULT 1");
        assertEquals(SqlStatementType.OTHER, decl.type());
        assertEquals("x", decl.name().simpleName());

        List<SqlStatement> batch = SQL.parseAll("BEGIN SELECT 1; END; SELECT 2");
        assertEquals(2, batch.size());
        assertEquals(SqlStatementType.OTHER, batch.get(0).type());
        assertEquals(SqlStatementType.SELECT, batch.get(1).type());
    }

    /**
     * P1.5：CALL 实参进 AST。
     */
    @Test
    public void p15CallArguments() {
        SqlSimpleStatement call = (SqlSimpleStatement) SQL.parse("CALL sp_add(1, 'a')");
        assertEquals(SqlStatementType.CALL, call.type());
        assertEquals("sp_add", call.name().simpleName());
        assertTrue(call.withArguments());
        assertEquals(2, call.arguments().size());
        String fmt = SQL.toSqlString(call);
        assertTrue(fmt, fmt.contains("CALL sp_add(1, 'a')") || fmt.contains("CALL sp_add(1,'a')"));
        assertFalse(fmt.contains(" = "));

        SqlSimpleStatement empty = (SqlSimpleStatement) SQL.parse("CALL sp_noop()");
        assertTrue(empty.withArguments());
        assertEquals(0, empty.arguments().size());
        assertTrue(SQL.toSqlString(empty).contains("()"));
    }

    /**
     * P1.5：ANALYZE / VACUUM / OPTIMIZE / REPAIR / CHECK TABLE。
     */
    @Test
    public void p15MaintenanceStatements() {
        SqlSimpleStatement analyze = (SqlSimpleStatement) SQL.parse("ANALYZE TABLE t");
        assertEquals(SqlStatementType.OTHER, analyze.type());
        assertEquals("t", analyze.name().simpleName());
        assertTrue(analyze.text().startsWith("ANALYZE"));

        SqlSimpleStatement vacuum = (SqlSimpleStatement) SQL.parse("VACUUM ANALYZE t", SqlDialect.POSTGRES);
        assertEquals("t", vacuum.name().simpleName());

        assertEquals("t", ((SqlSimpleStatement) SQL.parse("OPTIMIZE TABLE t")).name().simpleName());
        assertEquals("t", ((SqlSimpleStatement) SQL.parse("REPAIR TABLE t")).name().simpleName());
        assertEquals("t", ((SqlSimpleStatement) SQL.parse("CHECK TABLE t")).name().simpleName());
    }

    /**
     * P1.5：COMMENT ON TABLE/COLUMN。
     */
    @Test
    public void p15CommentOn() {
        SqlSimpleStatement table = (SqlSimpleStatement) SQL.parse(
                "COMMENT ON TABLE t IS 'users'", SqlDialect.POSTGRES);
        assertEquals(SqlStatementType.OTHER, table.type());
        assertEquals("t", table.name().simpleName());
        assertTrue(table.text(), table.text().contains("COMMENT ON TABLE"));

        SqlSimpleStatement col = (SqlSimpleStatement) SQL.parse(
                "COMMENT ON COLUMN t.id IS 'pk'", SqlDialect.POSTGRES);
        assertEquals("t.id", col.name().qualifiedName());
    }

    /**
     * P1.5：GO 作为 SQL Server 批分隔（同分号）。
     */
    @Test
    public void p15GoBatchSeparator() {
        List<SqlStatement> batch = SQL.parseAll("SELECT 1\nGO\nSELECT 2", SqlDialect.SQLSERVER);
        assertEquals(2, batch.size());
        assertEquals(SqlStatementType.SELECT, batch.get(0).type());
        assertEquals(SqlStatementType.SELECT, batch.get(1).type());
        assertFalse(SQL.toSqlString(batch.get(0)).toUpperCase().contains(" GO"));
    }

    /**
     * P1.6：MySQL 可执行注释展开为内部 SQL，不整段丢弃。
     */
    @Test
    public void p16ExecutableComments() {
        SqlStatement set = SQL.parse("/*!40101 SET NAMES utf8 */");
        assertEquals(SqlStatementType.SET, set.type());
        String fmt = SQL.toSqlString(set);
        assertTrue(fmt, fmt.toUpperCase().contains("SET"));
        assertTrue(fmt, fmt.toUpperCase().contains("NAMES"));

        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT /*!50000 DISTINCT */ id FROM t /*!40101 USE INDEX (PRIMARY) */");
        assertTrue(select.distinct());
        assertEquals("t", ((SqlTable) select.from()).name().simpleName());
        // USE INDEX 在可执行注释展开后应由 parseTableHints 吃掉
        assertNotNull(((SqlTable) select.from()).indexHint());
        SQL.parse(SQL.toSqlString(select));
    }

    /**
     * P1.6：优化器 hint 挂到 SELECT / 表，format 可输出。
     */
    @Test
    public void p16OptimizerHints() {
        SqlSelect select = (SqlSelect) SQL.parse("SELECT /*+ INDEX(t idx_id) */ id FROM t");
        assertEquals(1, select.hints().size());
        assertTrue(select.hints().get(0), select.hints().get(0).contains("INDEX"));
        String fmt = SQL.toSqlString(select);
        assertTrue(fmt, fmt.contains("/*+"));
        assertTrue(fmt, fmt.contains("INDEX"));
        SqlSelect again = (SqlSelect) SQL.parse(fmt);
        assertEquals(1, again.hints().size());

        SqlSelect tableHint = (SqlSelect) SQL.parse(
                "SELECT id FROM t /*+ INDEX(t idx_name) */ WHERE id = 1");
        SqlTable table = (SqlTable) tableHint.from();
        assertNotNull(table.optimizerHint());
        assertTrue(table.optimizerHint(), table.optimizerHint().contains("INDEX"));
        String tfmt = SQL.toSqlString(tableHint);
        assertTrue(tfmt, tfmt.contains("/*+"));
        SqlSelect tableAgain = (SqlSelect) SQL.parse(tfmt);
        assertNotNull(((SqlTable) tableAgain.from()).optimizerHint());
    }

    /**
     * P1.6：keepComments 默认 false；开启后语句前注释进 AST。
     */
    @Test
    public void p16KeepComments() {
        SqlSelect hot = (SqlSelect) SQL.parse("-- dropped\nSELECT 1 FROM t /* also dropped */");
        assertTrue(hot.comments().isEmpty());
        assertEquals(0, hot.hints().size());

        SqlParseOptions opts = SqlParseOptions.defaults().keepComments(true);
        SqlSelect kept = (SqlSelect) SQL.parse("-- keep me\nSELECT 1 /* block */ FROM t",
                SqlDialect.MYSQL, opts);
        assertFalse(kept.comments().isEmpty());
        boolean foundLine = false;
        boolean foundBlock = false;
        for (String c : kept.comments()) {
            if (c.contains("keep me")) {
                foundLine = true;
            }
            if (c.contains("block")) {
                foundBlock = true;
            }
        }
        assertTrue("line comment kept", foundLine);
        assertTrue("block comment kept", foundBlock);
        String fmt = SQL.toSqlString(kept);
        assertTrue(fmt, fmt.contains("keep me") || fmt.contains("--"));
    }

    /**
     * P1.7：MySQL PIPES_AS_CONCAT — 默认 || 为 OR，选项开启后为拼接。
     */
    @Test
    public void p17MysqlPipesAsConcat() {
        SqlSelect orDefault = (SqlSelect) SQL.parse("SELECT 'a' || 'b' FROM t");
        assertTrue(orDefault.selectItems().get(0).expr() instanceof SqlBinaryExpr);
        assertEquals(SqlBinaryOp.OR,
                ((SqlBinaryExpr) orDefault.selectItems().get(0).expr()).operator());
        String fOr = SQL.toSqlString(orDefault);
        assertTrue(fOr, fOr.contains(" OR "));

        SqlParseOptions opts = SqlParseOptions.defaults().pipesAsConcat(true);
        SqlSelect concat = (SqlSelect) SQL.parse("SELECT 'a' || 'b' FROM t", SqlDialect.MYSQL, opts);
        assertEquals(SqlBinaryOp.CONCAT,
                ((SqlBinaryExpr) concat.selectItems().get(0).expr()).operator());
        String fConcat = SQL.toSqlString(concat, SqlDialect.MYSQL);
        assertTrue(fConcat, fConcat.contains("||"));
        SQL.parse(fConcat, SqlDialect.POSTGRES);

        SqlSelect stillOr = (SqlSelect) SQL.parse("SELECT 1 || 0", SqlDialect.MYSQL,
                SqlParseOptions.defaults().pipesAsConcat(false));
        assertEquals(SqlBinaryOp.OR,
                ((SqlBinaryExpr) stillOr.selectItems().get(0).expr()).operator());
    }

    /**
     * P1.7：PG RETURNING 多列列表（INSERT/UPDATE/DELETE）format 往返。
     */
    @Test
    public void p17PgReturningMultiColumn() {
        SqlInsert ins = (SqlInsert) SQL.parse(
                "INSERT INTO t (id, name) VALUES (1, 'a') RETURNING id, name",
                SqlDialect.POSTGRES);
        assertTrue(ins.returning() instanceof SqlListExpr);
        assertEquals(2, ((SqlListExpr) ins.returning()).items().size());
        String fi = SQL.toSqlString(ins, SqlDialect.POSTGRES);
        assertTrue(fi, fi.contains("RETURNING"));
        assertTrue(fi, fi.contains("id") && fi.contains("name"));
        assertFalse(fi, fi.contains("RETURNING ("));
        SqlInsert ins2 = (SqlInsert) SQL.parse(fi, SqlDialect.POSTGRES);
        assertTrue(ins2.returning() instanceof SqlListExpr);

        SqlUpdate upd = (SqlUpdate) SQL.parse(
                "UPDATE t SET name = 'x' WHERE id = 1 RETURNING id, name, updated_at",
                SqlDialect.POSTGRES);
        assertEquals(3, ((SqlListExpr) upd.returning()).items().size());
        String fu = SQL.toSqlString(upd, SqlDialect.POSTGRES);
        assertFalse(fu, fu.contains("RETURNING ("));
        SQL.parse(fu, SqlDialect.POSTGRES);

        SqlDelete del = (SqlDelete) SQL.parse(
                "DELETE FROM t WHERE id = 1 RETURNING id, name",
                SqlDialect.POSTGRES);
        assertEquals(2, ((SqlListExpr) del.returning()).items().size());
        String fd = SQL.toSqlString(del, SqlDialect.POSTGRES);
        assertTrue(fd, fd.contains("RETURNING id"));
        SQL.parse(fd, SqlDialect.POSTGRES);
    }

    /**
     * P1.7：已有能力验收 — ON CONFLICT ON CONSTRAINT、Oracle FETCH/MINUS、SS OUTPUT/APPLY。
     */
    @Test
    public void p17DialectMatrixAlreadyPresent() {
        SqlInsert cons = (SqlInsert) SQL.parse(
                "INSERT INTO t (id) VALUES (1) ON CONFLICT ON CONSTRAINT t_pkey DO NOTHING",
                SqlDialect.POSTGRES);
        assertNotNull(cons.conflictConstraint());
        assertEquals("t_pkey", cons.conflictConstraint().simpleName());

        SqlSelect fetch = (SqlSelect) SQL.parse(
                "SELECT * FROM t FETCH FIRST 10 ROWS ONLY", SqlDialect.ORACLE);
        assertNotNull(fetch.limit());
        assertTrue(fetch.limit().fetchStyle());
        assertEquals("10", ((SqlLiteral) fetch.limit().rowCount()).value());
        String ff = SQL.toSqlString(fetch, SqlDialect.ORACLE);
        assertTrue(ff, ff.contains("FETCH FIRST"));
        assertTrue(ff, ff.contains("ROWS ONLY"));
        SQL.parse(ff, SqlDialect.ORACLE);

        SqlSelect fetchOrd = (SqlSelect) SQL.parse(
                "SELECT id FROM emp ORDER BY id FETCH FIRST 5 ROWS ONLY", SqlDialect.ORACLE);
        assertTrue(fetchOrd.limit().fetchStyle());
        String fo = SQL.toSqlString(fetchOrd, SqlDialect.ORACLE);
        assertTrue(fo, fo.contains("FETCH FIRST 5"));
        SQL.parse(fo, SqlDialect.ORACLE);

        SqlSelect minus = (SqlSelect) SQL.parse(
                "SELECT a FROM t MINUS SELECT a FROM s", SqlDialect.ORACLE);
        assertEquals("MINUS", minus.unionOp());
        assertNotNull(minus.union());
        SQL.parse(SQL.toSqlString(minus, SqlDialect.ORACLE), SqlDialect.ORACLE);

        SqlSelect apply = (SqlSelect) SQL.parse(
                "SELECT * FROM a CROSS APPLY (SELECT TOP 1 id FROM b WHERE b.aid = a.id) x",
                SqlDialect.SQLSERVER);
        assertEquals(SqlJoin.Type.CROSS_APPLY, ((SqlJoin) apply.from()).joinType());

        SqlInsert out = (SqlInsert) SQL.parse(
                "INSERT INTO t (id) OUTPUT INSERTED.id VALUES (1)", SqlDialect.SQLSERVER);
        assertEquals(1, out.output().size());

        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("dameng"));
        assertEquals(SqlDialect.ORACLE, SqlDialect.fromName("dm"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromName("gbase"));
    }
}

