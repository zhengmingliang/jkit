package com.alianga.jkit.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlDdlStatement;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
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
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
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
        SqlStatement withWhere = SQL.andWhere(stmt, "tenant_id = ?");
        assertTrue("andWhere must clone", !SQL.toSqlString(stmt).contains("tenant_id"));
        String sql = SQL.toSqlString(withWhere);
        assertTrue(sql, sql.contains("tenant_id"));
        assertTrue(sql, sql.contains("status"));
        SqlStatement replaced = SQL.replaceTable(withWhere, "users", "users_archive");
        assertTrue(SQL.toSqlString(replaced).contains("users_archive"));
        assertTrue("replaceTable must clone", SQL.toSqlString(withWhere).contains("users"));
        assertTrue(!SQL.toSqlString(withWhere).contains("users_archive"));
    }

    /**
     * MySQL {@code FROM t PARTITION (p0, p1)} 表分区限定；往返后仍为分区而非别名。
     */
    @Test
    public void mysqlTablePartitionQualifier() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT * FROM t PARTITION (p0, p1) WHERE id > 0");
        assertTrue(select.from() instanceof com.alianga.jkit.sql.ast.SqlTable);
        com.alianga.jkit.sql.ast.SqlTable table =
                (com.alianga.jkit.sql.ast.SqlTable) select.from();
        assertEquals(2, table.partitions().size());
        assertEquals("p0", table.partitions().get(0).qualifiedName());
        assertEquals("p1", table.partitions().get(1).qualifiedName());
        assertTrue("PARTITION must not be alias", table.alias() == null
                || !"PARTITION".equalsIgnoreCase(table.alias()));
        String out = SQL.toSqlString(select);
        assertTrue(out, out.toUpperCase().contains("PARTITION"));
        assertTrue(out, out.contains("p0") && out.contains("p1"));
        SqlSelect again = (SqlSelect) SQL.parse(out);
        com.alianga.jkit.sql.ast.SqlTable t2 =
                (com.alianga.jkit.sql.ast.SqlTable) again.from();
        assertEquals(2, t2.partitions().size());
        assertEquals(java.util.Arrays.asList("t"), SQL.tables(again));
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
        com.alianga.jkit.sql.ast.SqlDdlStatement ddl =
                (com.alianga.jkit.sql.ast.SqlDdlStatement) create;
        assertEquals(2, ddl.columns().size());
        assertEquals(2, ddl.columnDefinitions().size());
        assertTrue(ddl.columnDefinitions().get(0),
                ddl.columnDefinitions().get(0).toUpperCase().contains("INT"));
        assertTrue(ddl.columnDefinitions().get(0),
                ddl.columnDefinitions().get(0).toUpperCase().contains("PRIMARY"));
        assertTrue(ddl.columnDefinitions().get(1),
                ddl.columnDefinitions().get(1).toUpperCase().contains("VARCHAR"));
        String formatted = SQL.toSqlString(create);
        assertTrue(formatted, formatted.toUpperCase().contains("INT"));
        assertTrue(formatted, formatted.toUpperCase().contains("VARCHAR"));
        assertTrue(formatted, formatted.toUpperCase().contains("PRIMARY"));
        assertFalse(formatted, formatted.contains("(id, name)")
                || formatted.replace(" ", "").contains("(id,name)"));
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
     * {@link SQL#parseExpr}：裸表达式入口（字面量/列/运算/函数/CASE/绑定；拒残缺与尾部垃圾）。
     */
    @Test
    public void parseBareExpr() {
        assertEquals("1", SQL.parseExpr("1").toString().replace(" ", ""));
        assertTrue(SQL.parseExpr("42").toString().contains("42"));
        assertTrue(SQL.parseExpr("'hello'").toString().contains("hello"));
        SqlExpr col = SQL.parseExpr("users.id");
        assertTrue(col instanceof SqlIdentifier);
        assertEquals("users.id", ((SqlIdentifier) col).qualifiedName());

        SqlExpr bin = SQL.parseExpr("a + b * 2");
        assertTrue(bin instanceof SqlBinaryExpr);
        assertTrue(SQL.parseExpr("x > 0 AND y IS NULL").toString().toUpperCase().contains("AND"));

        SqlExpr fn = SQL.parseExpr("REPLACE(email, '@', '^-^')");
        assertTrue(fn instanceof SqlFunctionExpr);
        SqlFunctionExpr replace = (SqlFunctionExpr) fn;
        assertEquals("REPLACE", replace.name().simpleName().toUpperCase());
        assertEquals(3, replace.arguments().size());
        String fnSql = fn.toString();
        assertTrue(fnSql, fnSql.contains("REPLACE"));
        assertTrue(fnSql, fnSql.contains("email"));

        SqlExpr lookbehind = SQL.parseExpr("REPLACE(email, '(?<=.).*(?=com)', '*')");
        assertTrue(lookbehind instanceof SqlFunctionExpr);
        assertEquals(3, ((SqlFunctionExpr) lookbehind).arguments().size());
        String lb = lookbehind.toString();
        assertTrue(lb, lb.contains("REPLACE"));
        assertTrue(lb, lb.contains("email"));

        SqlExpr cse = SQL.parseExpr("CASE WHEN x > 0 THEN 'a' ELSE 'b' END");
        assertTrue(cse instanceof SqlCaseExpr);

        SqlExpr named = SQL.parseExpr("name = :name");
        assertTrue(named.toString().contains(":name") || named.toString().contains("name"));
        SqlExpr qmark = SQL.parseExpr("id = ?");
        assertTrue(qmark.toString().contains("?"));

        SqlExpr withDialect = SQL.parseExpr("a || b", SqlDialect.POSTGRES);
        assertNotNull(withDialect);
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().atWrapped());
        SqlExpr withOpt = SQL.parseExpr("age > @age@", SqlDialect.MYSQL, opt);
        assertNotNull(withOpt);
        assertTrue(withOpt.toString().contains("age") || withOpt.toString().contains("@age@"));

        try {
            SQL.parseExpr("a +");
            fail("expected incomplete expr");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(), expected.line() >= 1);
        }
        try {
            SQL.parseExpr("a + b FROM t");
            fail("expected trailing garbage");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unexpected")
                    || expected.getMessage().contains("FROM"));
        }
        try {
            SQL.parseExpr("1; SELECT 2");
            fail("expected trailing junk after expr");
        } catch (SqlParseException expected) {
            // ok
        }
        try {
            SQL.parseExpr("   ");
            fail("expected empty");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("empty"));
        }

        // andWhere 经 parseExpr 路径行为不变
        SqlStatement stmt = SQL.parse("SELECT * FROM t WHERE a = 1");
        SqlStatement with = SQL.andWhere(stmt, "tenant_id = ?");
        assertTrue(SQL.toSqlString(with).contains("tenant_id"));
        assertFalse(SQL.toSqlString(stmt).contains("tenant_id"));
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
     * P1.4：SQL Server OUTPUT INSERTED.* / DELETED.*；OUTPUT INTO 表 / @var / #tmp。
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

        SqlInsert intoTbl = (SqlInsert) SQL.parse(
                "INSERT INTO t (id) OUTPUT INSERTED.id INTO dbo.archive VALUES (1)",
                SqlDialect.SQLSERVER);
        assertNotNull(intoTbl.outputInto());
        assertEquals("dbo.archive", intoTbl.outputInto().name().qualifiedName());
        assertTrue(SQL.tables(intoTbl).toString().toLowerCase(),
                SQL.tables(intoTbl).toString().toLowerCase().contains("archive"));
        String fInto = SQL.toSqlString(intoTbl, SqlDialect.SQLSERVER);
        assertTrue(fInto, fInto.toUpperCase().contains("INTO"));
        assertTrue(fInto, fInto.contains("archive"));
        SqlInsert again = (SqlInsert) SQL.parse(fInto, SqlDialect.SQLSERVER);
        assertEquals("dbo.archive", again.outputInto().name().qualifiedName());

        SqlInsert intoVar = (SqlInsert) SQL.parse(
                "INSERT INTO t (id) OUTPUT INSERTED.id INTO @out VALUES (1)",
                SqlDialect.SQLSERVER);
        assertEquals("@out", intoVar.outputInto().name().qualifiedName());
        assertTrue(SQL.tables(intoVar).toString(), SQL.tables(intoVar).toString().contains("@out"));
        String fVar = SQL.toSqlString(intoVar, SqlDialect.SQLSERVER);
        assertTrue(fVar, fVar.contains("@out"));
        assertFalse(fVar, fVar.contains("@ out"));

        SqlDelete intoTmp = (SqlDelete) SQL.parse(
                "DELETE FROM t OUTPUT DELETED.* INTO #tmp WHERE id = 1",
                SqlDialect.SQLSERVER);
        assertEquals("#tmp", intoTmp.outputInto().name().qualifiedName());
        assertTrue(SQL.tables(intoTmp).toString(), SQL.tables(intoTmp).toString().contains("#tmp"));
        String fTmp = SQL.toSqlString(intoTmp, SqlDialect.SQLSERVER);
        assertTrue(fTmp, fTmp.contains("#tmp"));
        SqlDelete againTmp = (SqlDelete) SQL.parse(fTmp, SqlDialect.SQLSERVER);
        assertEquals("#tmp", againTmp.outputInto().name().qualifiedName());

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


    /**
     * P3.1：方言标识符引号回写（MySQL 反引号 / PG 双引号 / SQL Server []）。
     */
    @Test
    public void p31DialectQuoting() {
        SqlSelect mysql = (SqlSelect) SQL.parse("SELECT `order` FROM `user`");
        String fm = SQL.toSqlString(mysql, SqlDialect.MYSQL);
        assertTrue(fm, fm.contains("`order`"));
        assertTrue(fm, fm.contains("`user`"));
        SQL.parse(fm, SqlDialect.MYSQL);

        SqlSelect pg = (SqlSelect) SQL.parse("SELECT \"Order\" FROM \"User\"", SqlDialect.POSTGRES);
        String fp = SQL.toSqlString(pg, SqlDialect.POSTGRES);
        assertTrue(fp, fp.contains("\"Order\""));
        assertTrue(fp, fp.contains("\"User\""));
        SQL.parse(fp, SqlDialect.POSTGRES);

        SqlSelect ss = (SqlSelect) SQL.parse("SELECT [Order] FROM [User]", SqlDialect.SQLSERVER);
        String fs = SQL.toSqlString(ss, SqlDialect.SQLSERVER);
        assertTrue(fs, fs.contains("[Order]"));
        assertTrue(fs, fs.contains("[User]"));
        SQL.parse(fs, SqlDialect.SQLSERVER);
    }

    /**
     * P3.1：容错 parseAll — 失败语句记 SqlSimpleStatement + parseError，继续下一条。
     */
    @Test
    public void p31ParseAllTolerant() {
        String batch = "SELECT 1; !!!; SELECT 2";
        List<SqlStatement> all = SQL.parseAll(batch, SqlDialect.MYSQL, true);
        assertEquals(3, all.size());
        assertEquals(SqlStatementType.SELECT, all.get(0).type());
        assertTrue(all.get(1) instanceof SqlSimpleStatement);
        SqlSimpleStatement bad = (SqlSimpleStatement) all.get(1);
        assertTrue(bad.hasParseError());
        assertNotNull(bad.parseError());
        assertEquals(SqlStatementType.OTHER, bad.type());
        assertEquals(SqlStatementType.SELECT, all.get(2).type());

        // 默认仍整批抛错
        try {
            SQL.parseAll(batch, SqlDialect.MYSQL, false);
            fail("expected SqlParseException");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().length() > 0);
        }
    }

    /**
     * MySQL {@code <=>} 空安全相等。
     */
    @Test
    public void mysqlNullSafeEqual() {
        SqlSelect s = (SqlSelect) SQL.parse("SELECT * FROM t WHERE a <=> b");
        assertEquals(SqlBinaryOp.NULL_SAFE_EQ,
                ((SqlBinaryExpr) s.where()).operator());
        assertTrue(SQL.toSqlString(s).contains("<=>"));
    }

    /**
     * MySQL {@code INSERT DELAYED}。
     */
    @Test
    public void mysqlInsertDelayed() {
        SqlInsert ins = (SqlInsert) SQL.parse("INSERT DELAYED INTO t (id) VALUES (1)");
        assertTrue(ins.delayed());
        String out = SQL.toSqlString(ins);
        assertTrue(out, out.toUpperCase().contains("DELAYED"));
        SqlInsert again = (SqlInsert) SQL.parse(out);
        assertTrue(again.delayed());
    }

    /**
     * MySQL {@code BINARY 'abc'} 前缀强制二进制比较。
     */
    @Test
    public void mysqlBinaryPrefix() {
        SqlSelect s = (SqlSelect) SQL.parse("SELECT * FROM t WHERE BINARY 'abc' = name");
        SqlBinaryExpr where = (SqlBinaryExpr) s.where();
        assertTrue(where.left() instanceof SqlUnaryExpr);
        assertEquals(SqlUnaryExpr.Op.BINARY, ((SqlUnaryExpr) where.left()).operator());
        String out = SQL.toSqlString(s);
        assertTrue(out, out.toUpperCase().contains("BINARY"));
    }

    /**
     * SQL Server {@code TOP (n) WITH TIES}。
     */
    @Test
    public void sqlServerTopWithTies() {
        SqlSelect s = (SqlSelect) SQL.parse(
                "SELECT TOP (10) WITH TIES * FROM t ORDER BY id", SqlDialect.SQLSERVER);
        assertNotNull(s.top());
        assertTrue(s.topWithTies());
        String out = SQL.toSqlString(s, SqlDialect.SQLSERVER);
        assertTrue(out, out.toUpperCase().contains("WITH TIES"));
        SqlSelect again = (SqlSelect) SQL.parse(out, SqlDialect.SQLSERVER);
        assertTrue(again.topWithTies());
    }

    /**
     * PG {@code TABLESAMPLE} / Oracle {@code SAMPLE(n)}。
     */
    @Test
    public void tableSampleClauses() {
        SqlSelect pg = (SqlSelect) SQL.parse(
                "SELECT * FROM t TABLESAMPLE SYSTEM (10)", SqlDialect.POSTGRES);
        assertNotNull(((SqlTable) pg.from()).sampleClause());
        assertTrue(((SqlTable) pg.from()).sampleClause().toUpperCase().contains("TABLESAMPLE"));

        SqlSelect ora = (SqlSelect) SQL.parse(
                "SELECT * FROM t SAMPLE (5)", SqlDialect.ORACLE);
        assertNotNull(((SqlTable) ora.from()).sampleClause());
        assertTrue(((SqlTable) ora.from()).sampleClause().toUpperCase().contains("SAMPLE"));
    }

    /**
     * PG {@code @>} / {@code <@} 包含运算符；勿再被词法当成 VARIABLE。
     */
    @Test
    public void postgresContainsOperators() {
        SqlSelect s1 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE tags @> ARRAY['vip']", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.CONTAINS,
                ((SqlBinaryExpr) s1.where()).operator());
        String f1 = SQL.toSqlString(s1, SqlDialect.POSTGRES);
        assertTrue(f1, f1.contains("@>"));

        SqlSelect s2 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE tsrange(a, b) @> NOW()", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.CONTAINS,
                ((SqlBinaryExpr) s2.where()).operator());

        SqlSelect s3 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE ARRAY['vip'] <@ tags", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.CONTAINED_BY,
                ((SqlBinaryExpr) s3.where()).operator());
        assertTrue(SQL.toSqlString(s3, SqlDialect.POSTGRES).contains("<@"));
    }

    /**
     * PG {@code ~} / {@code ~*} / {@code !~} 正则运算符（对比 MySQL 一元 {@code ~}）。
     */
    @Test
    public void postgresRegexOperators() {
        SqlSelect s1 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE name ~ '^[A-Z]'", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.REGEX_MATCH,
                ((SqlBinaryExpr) s1.where()).operator());

        SqlSelect s2 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE name ~* 'foo'", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.REGEX_MATCH_CI,
                ((SqlBinaryExpr) s2.where()).operator());

        SqlSelect s3 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE name !~ 'bar'", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.REGEX_NOT_MATCH,
                ((SqlBinaryExpr) s3.where()).operator());

        SqlSelect s4 = (SqlSelect) SQL.parse(
                "SELECT * FROM t WHERE name !~* 'baz'", SqlDialect.POSTGRES);
        assertEquals(SqlBinaryOp.REGEX_NOT_MATCH_CI,
                ((SqlBinaryExpr) s4.where()).operator());

        // MySQL 仍把 ~ 当按位取反
        SqlSelect mysql = (SqlSelect) SQL.parse("SELECT ~1 FROM dual");
        assertTrue(mysql.selectItems().get(0).expr() instanceof com.alianga.jkit.sql.ast.SqlUnaryExpr);
    }

    /**
     * MySQL {@code FORCE/USE/IGNORE INDEX FOR JOIN|ORDER BY|GROUP BY}，勿误吃进 FOR UPDATE。
     */
    @Test
    public void mysqlForceIndexForJoin() {
        SqlSelect s = (SqlSelect) SQL.parse(
                "SELECT * FROM t FORCE INDEX FOR JOIN (idx) WHERE id = 1");
        SqlTable table = (SqlTable) s.from();
        assertNotNull(table.indexHint());
        assertTrue(table.indexHint(), table.indexHint().toUpperCase().contains("FOR JOIN"));
        assertTrue(table.indexHint(), table.indexHint().contains("idx"));
        assertNotNull(s.where());
        assertFalse(s.forUpdate());
        String out = SQL.toSqlString(s);
        assertTrue(out, out.toUpperCase().contains("FOR JOIN"));

        SqlSelect order = (SqlSelect) SQL.parse(
                "SELECT * FROM t USE INDEX FOR ORDER BY (idx_a) ORDER BY a");
        assertTrue(((SqlTable) order.from()).indexHint().toUpperCase().contains("ORDER BY"));

        SqlSelect group = (SqlSelect) SQL.parse(
                "SELECT a, COUNT(*) FROM t IGNORE INDEX FOR GROUP BY (idx_a) GROUP BY a");
        assertTrue(((SqlTable) group.from()).indexHint().toUpperCase().contains("GROUP BY"));
    }

    /**
     * P3.1：|| 按 AST 回写 — MySQL 默认 OR；pipesAsConcat / PG 为 CONCAT→||。
     */
    @Test
    public void p31PipesFormatSemantics() {
        SqlSelect orStmt = (SqlSelect) SQL.parse("SELECT 1 || 0 FROM t");
        assertEquals(SqlBinaryOp.OR,
                ((SqlBinaryExpr) orStmt.selectItems().get(0).expr()).operator());
        assertTrue(SQL.toSqlString(orStmt).contains("OR"));

        SqlParseOptions opts = SqlParseOptions.defaults().pipesAsConcat(true);
        SqlSelect concat = (SqlSelect) SQL.parse("SELECT 'a' || 'b' FROM t", SqlDialect.MYSQL, opts);
        assertEquals(SqlBinaryOp.CONCAT,
                ((SqlBinaryExpr) concat.selectItems().get(0).expr()).operator());
        assertTrue(SQL.toSqlString(concat, SqlDialect.MYSQL).contains("||"));
    }

    /**
     * 仅注释 / 空白：不抛 empty SQL，归为 OTHER。
     */
    @Test
    public void commentOnlyParsesAsEmptyOther() {
        SqlStatement stmt = SQL.parse("-- line comment SELECT 1 /* block */ FROM t");
        assertEquals(SqlStatementType.OTHER, stmt.type());
        assertTrue(stmt instanceof SqlSimpleStatement);

        SqlStatement blank = SQL.parse("   /* block only */  ");
        assertEquals(SqlStatementType.OTHER, blank.type());
        assertTrue(SQL.parseAll("-- x").isEmpty());
    }

    /**
     * PG COPY … STDIN：OTHER + 抽表名。
     */
    @Test
    public void copyFromStdin() {
        SqlSimpleStatement copy = (SqlSimpleStatement) SQL.parse(
                "COPY t FROM STDIN WITH (FORMAT csv)", SqlDialect.POSTGRES);
        assertEquals(SqlStatementType.OTHER, copy.type());
        assertEquals("t", copy.name().simpleName());
        assertTrue(copy.text().toUpperCase().contains("STDIN"));
        assertTrue(SQL.tables(copy).contains("t"));
    }

    /**
     * SQL Server OPENJSON … WITH (schema)。
     */
    @Test
    public void openJsonWithClause() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT * FROM OPENJSON(@json) WITH (id int '$.id', name nvarchar(50) '$.name')",
                SqlDialect.SQLSERVER);
        assertTrue(select.from() instanceof SqlFunctionTable);
        SqlFunctionTable ft = (SqlFunctionTable) select.from();
        assertNotNull(ft.withDefinition());
        assertTrue(ft.withDefinition().contains("$.id"));
        String fmt = SQL.toSqlString(select, SqlDialect.SQLSERVER);
        assertTrue(fmt.toUpperCase().contains("OPENJSON"));
        assertTrue(fmt.toUpperCase().contains("WITH"));
    }

    /**
     * MySQL HANDLER 批：OTHER + 抽表。
     */
    @Test
    public void handlerBatch() {
        java.util.List<SqlStatement> all = SQL.parseAll(
                "HANDLER t OPEN; HANDLER t READ FIRST; HANDLER t CLOSE");
        assertEquals(3, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(SqlStatementType.OTHER, all.get(i).type());
            assertTrue(SQL.tables(all.get(i)).contains("t"));
        }
        assertTrue(((SqlSimpleStatement) all.get(1)).text().toUpperCase().contains("READ"));
    }

    /**
     * Oracle (+) 外连接后缀。
     */
    @Test
    public void oracleOuterJoinPlus() {
        SqlSelect select = (SqlSelect) SQL.parse(
                "SELECT e.ename, d.dname FROM emp e, dept d WHERE e.deptno = d.deptno(+)",
                SqlDialect.ORACLE);
        SqlBinaryExpr where = (SqlBinaryExpr) select.where();
        assertTrue(where.right() instanceof SqlUnaryExpr);
        SqlUnaryExpr u = (SqlUnaryExpr) where.right();
        assertEquals(SqlUnaryExpr.Op.ORACLE_OUTER_JOIN, u.operator());
        String fmt = SQL.toSqlString(select, SqlDialect.ORACLE);
        assertTrue(fmt.contains("(+)"));
        SqlSelect again = (SqlSelect) SQL.parse(fmt, SqlDialect.ORACLE);
        assertTrue(((SqlBinaryExpr) again.where()).right() instanceof SqlUnaryExpr);
    }

    /**
     * MySQL PREPARE / EXECUTE / DEALLOCATE。
     */
    @Test
    public void prepareExecuteDeallocate() {
        java.util.List<SqlStatement> all = SQL.parseAll(
                "PREPARE stmt FROM 'SELECT * FROM t WHERE id = ?'; "
                        + "EXECUTE stmt USING @id; DEALLOCATE PREPARE stmt");
        assertEquals(3, all.size());
        assertEquals(SqlStatementType.OTHER, all.get(0).type());
        assertEquals("stmt", ((SqlSimpleStatement) all.get(0)).name().simpleName());
        assertTrue(((SqlSimpleStatement) all.get(0)).text().toUpperCase().startsWith("PREPARE"));
        assertTrue(((SqlSimpleStatement) all.get(1)).text().toUpperCase().startsWith("EXECUTE"));
        assertTrue(((SqlSimpleStatement) all.get(2)).text().toUpperCase().contains("DEALLOCATE"));
    }


    /**
     * P0.4 余量：ALTER CHANGE 抽旧/新列名与列定义。
     */
    @Test
    public void alterChangeColumnDefinition() {
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(
                "ALTER TABLE t CHANGE COLUMN old_c new_c INT NOT NULL DEFAULT 0");
        assertEquals("CHANGE COLUMN", ddl.alterAction());
        assertEquals(2, ddl.columns().size());
        assertEquals("old_c", ddl.columns().get(0).qualifiedName());
        assertEquals("new_c", ddl.columns().get(1).qualifiedName());
        assertNotNull(ddl.columnDefinition());
        assertTrue(ddl.columnDefinition(), ddl.columnDefinition().toUpperCase().contains("INT"));
        String out = SQL.toSqlString(ddl);
        assertTrue(out, out.contains("CHANGE"));
        assertTrue(out, out.contains("old_c") && out.contains("new_c"));
        SqlDdlStatement again = (SqlDdlStatement) SQL.parse(out);
        assertEquals("old_c", again.columns().get(0).qualifiedName());
        assertEquals("new_c", again.columns().get(1).qualifiedName());
        assertNotNull(again.columnDefinition());
    }

    /**
     * P0.4 余量：ALTER ADD CONSTRAINT FOREIGN KEY + 引用表。
     */
    @Test
    public void alterAddConstraintForeignKey() {
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(
                "ALTER TABLE child ADD CONSTRAINT fk_parent FOREIGN KEY (pid) REFERENCES parent (id)");
        assertEquals("ADD CONSTRAINT", ddl.alterAction());
        assertEquals("fk_parent", ddl.constraintName().qualifiedName());
        assertEquals("FOREIGN KEY", ddl.constraintType());
        assertEquals(1, ddl.indexColumns().size());
        assertEquals("pid", ddl.indexColumns().get(0).qualifiedName());
        assertEquals(1, ddl.referencedTables().size());
        assertEquals("parent", ddl.referencedTables().get(0).qualifiedName());
        String out = SQL.toSqlString(ddl);
        assertTrue(out, out.contains("FOREIGN KEY"));
        assertTrue(out, out.contains("REFERENCES"));
        SqlDdlStatement again = (SqlDdlStatement) SQL.parse(out);
        assertEquals("parent", again.referencedTables().get(0).qualifiedName());
        assertTrue(SQL.tables(ddl).toString(), SQL.tables(ddl).contains("parent"));
    }

    /**
     * P0.4 余量：CREATE TABLE 表级 FOREIGN KEY 抽引用表。
     */
    @Test
    public void createTableForeignKeyReferencedTable() {
        SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(
                "CREATE TABLE child (id INT, pid INT, FOREIGN KEY (pid) REFERENCES parent(id))");
        assertEquals(2, ddl.columns().size());
        assertEquals(1, ddl.referencedTables().size());
        assertEquals("parent", ddl.referencedTables().get(0).qualifiedName());
        assertTrue(SQL.tables(ddl).toString().toLowerCase(),
                SQL.tables(ddl).toString().toLowerCase().contains("parent"));
    }

    /**
     * P0.4 余量：GRANT 抽权限与对象名。
     */
    @Test
    public void grantPrivilegesAndObject() {
        SqlSimpleStatement g = (SqlSimpleStatement) SQL.parse(
                "GRANT SELECT, INSERT ON db.t TO 'u'@'%'");
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.GRANT, g.type());
        assertNotNull(g.privileges());
        assertTrue(g.privileges(), g.privileges().toUpperCase().contains("SELECT"));
        assertTrue(g.privileges(), g.privileges().toUpperCase().contains("INSERT"));
        assertEquals("db.t", g.name().qualifiedName());
        assertNotNull(g.text());
        assertTrue(g.text(), g.text().toUpperCase().contains("TO"));
        String out = SQL.toSqlString(g);
        assertTrue(out, out.toUpperCase().startsWith("GRANT"));
        assertTrue(out, out.toUpperCase().contains("ON"));
        assertTrue(out, out.contains("'u'@'%'"));
        assertFalse(out, out.contains("'u' @"));
        SqlSimpleStatement again = (SqlSimpleStatement) SQL.parse(out);
        assertEquals("db.t", again.name().qualifiedName());
        assertTrue(again.privileges().toUpperCase().contains("SELECT"));

        SqlSimpleStatement host = (SqlSimpleStatement) SQL.parse(
                "GRANT SELECT ON db.t TO u@localhost");
        String hostOut = SQL.toSqlString(host);
        assertTrue(hostOut, hostOut.contains("u@localhost"));
        assertFalse(hostOut, hostOut.contains("u @"));

        SqlSimpleStatement all = (SqlSimpleStatement) SQL.parse(
                "GRANT ALL PRIVILEGES ON *.* TO admin");
        assertTrue(all.privileges().toUpperCase(), all.privileges().toUpperCase().contains("ALL"));
        assertEquals("*.*", all.name().qualifiedName());
    }

    /**
     * REVOKE 镜像 GRANT：权限 + ON 对象 + FROM 用户。
     */
    @Test
    public void revokePrivilegesAndObject() {
        SqlSimpleStatement r = (SqlSimpleStatement) SQL.parse(
                "REVOKE SELECT, INSERT ON db.t FROM 'u'@'%'");
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.REVOKE, r.type());
        assertNotNull(r.privileges());
        assertTrue(r.privileges(), r.privileges().toUpperCase().contains("SELECT"));
        assertTrue(r.privileges(), r.privileges().toUpperCase().contains("INSERT"));
        assertEquals("db.t", r.name().qualifiedName());
        assertNotNull(r.text());
        assertTrue(r.text(), r.text().toUpperCase().contains("FROM"));
        String out = SQL.toSqlString(r);
        assertTrue(out, out.toUpperCase().startsWith("REVOKE"));
        assertTrue(out, out.contains("'u'@'%'"));
        assertFalse(out, out.contains("'u' @"));
        SqlSimpleStatement again = (SqlSimpleStatement) SQL.parse(out);
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.REVOKE, again.type());
        assertEquals("db.t", again.name().qualifiedName());

        SqlSimpleStatement all = (SqlSimpleStatement) SQL.parse(
                "REVOKE ALL PRIVILEGES ON *.* FROM admin");
        assertTrue(all.privileges().toUpperCase().contains("ALL"));
        assertEquals("*.*", all.name().qualifiedName());
        assertTrue(all.text().toUpperCase().contains("FROM"));
    }

    /**
     * FLUSH PRIVILEGES / TABLES 等 → OTHER + 完整 text。
     */
    @Test
    public void flushPrivilegesAndTables() {
        SqlSimpleStatement flush = (SqlSimpleStatement) SQL.parse("FLUSH PRIVILEGES");
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.OTHER, flush.type());
        assertTrue(flush.text(), flush.text().toUpperCase().startsWith("FLUSH"));
        assertTrue(flush.text().toUpperCase().contains("PRIVILEGES"));
        assertTrue(SQL.toSqlString(flush).toUpperCase().contains("FLUSH"));

        SqlSimpleStatement tables = (SqlSimpleStatement) SQL.parse("FLUSH TABLES");
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.OTHER, tables.type());
        assertTrue(tables.text().toUpperCase().contains("TABLES"));

        SqlSimpleStatement logs = (SqlSimpleStatement) SQL.parse("FLUSH LOGS");
        assertTrue(logs.text().toUpperCase().contains("LOGS"));
    }

    /**
     * START TRANSACTION / COMMIT / ROLLBACK / SAVEPOINT；BEGIN…END 过程块不破坏。
     */
    @Test
    public void transactionAndBeginBlock() {
        SqlSimpleStatement start = (SqlSimpleStatement) SQL.parse("START TRANSACTION");
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.OTHER, start.type());
        assertTrue(start.text().toUpperCase().contains("START"));
        assertTrue(start.text().toUpperCase().contains("TRANSACTION"));

        SqlSimpleStatement beginTx = (SqlSimpleStatement) SQL.parse("BEGIN WORK");
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.OTHER, beginTx.type());
        assertTrue(beginTx.text().toUpperCase().startsWith("BEGIN"));

        SqlSimpleStatement commit = (SqlSimpleStatement) SQL.parse("COMMIT");
        assertTrue(commit.text().equalsIgnoreCase("COMMIT")
                || commit.text().toUpperCase().startsWith("COMMIT"));

        SqlSimpleStatement rollback = (SqlSimpleStatement) SQL.parse("ROLLBACK TO SAVEPOINT sp1");
        assertTrue(rollback.text().toUpperCase().startsWith("ROLLBACK"));
        assertTrue(rollback.text().toUpperCase().contains("SAVEPOINT"));

        SqlSimpleStatement sp = (SqlSimpleStatement) SQL.parse("SAVEPOINT sp1");
        assertEquals("sp1", sp.name().simpleName());
        assertTrue(sp.text().toUpperCase().contains("SAVEPOINT"));

        // 过程块仍可用
        SqlSimpleStatement block = (SqlSimpleStatement) SQL.parse("BEGIN SELECT 1; END");
        assertTrue(block.text(), block.text().toUpperCase().contains("SELECT"));
        assertTrue(block.text().toUpperCase().contains("END"));

        java.util.List<SqlStatement> batch = SQL.parseAll(
                "UPDATE t SET a = 1; FLUSH PRIVILEGES");
        assertEquals(2, batch.size());
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.UPDATE, batch.get(0).type());
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.OTHER, batch.get(1).type());
        assertTrue(((SqlSimpleStatement) batch.get(1)).text().toUpperCase().contains("FLUSH"));

        java.util.List<SqlStatement> txBatch = SQL.parseAll(
                "START TRANSACTION; INSERT INTO t (id) VALUES (1); COMMIT");
        assertEquals(3, txBatch.size());
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.OTHER, txBatch.get(0).type());
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.INSERT, txBatch.get(1).type());
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.OTHER, txBatch.get(2).type());
    }

    /**
     * SELECT … INTO table FROM … / INTO @var / INTO OUTFILE。
     */
    @Test
    public void selectIntoTableVarAndOutfile() {
        SqlSelect intoTbl = (SqlSelect) SQL.parse("SELECT id, name INTO dest FROM src WHERE id > 0");
        assertNotNull(intoTbl.intoTable());
        assertEquals("dest", intoTbl.intoTable().name().qualifiedName());
        assertEquals("src", intoTbl.from() instanceof com.alianga.jkit.sql.ast.SqlTable
                ? ((com.alianga.jkit.sql.ast.SqlTable) intoTbl.from()).name().qualifiedName()
                : null);
        java.util.List<String> tables = SQL.tables(intoTbl);
        assertTrue(tables.toString(), tables.contains("dest"));
        assertTrue(tables.toString(), tables.contains("src"));
        SqlSchemaStat stat = SQL.stat(intoTbl);
        assertTrue(stat.getTables().get("dest").toString(),
                stat.getTables().get("dest").contains(
                        com.alianga.jkit.sql.ast.SqlStatementType.INSERT));
        assertTrue(stat.getTables().get("src").toString(),
                stat.getTables().get("src").contains(
                        com.alianga.jkit.sql.ast.SqlStatementType.SELECT));
        String formatted = SQL.toSqlString(intoTbl);
        assertTrue(formatted.toUpperCase().contains("INTO"));
        assertTrue(formatted.toUpperCase().contains("DEST"));
        SqlSelect again = (SqlSelect) SQL.parse(formatted);
        assertEquals("dest", again.intoTable().name().qualifiedName());

        SqlSelect intoVar = (SqlSelect) SQL.parse("SELECT id INTO @id FROM t LIMIT 1");
        assertNull(intoVar.intoTable());
        assertEquals(1, intoVar.intoVariables().size());
        String varOut = SQL.toSqlString(intoVar);
        assertTrue(varOut, varOut.contains("@id") || varOut.toUpperCase().contains("INTO"));

        SqlSelect outfile = (SqlSelect) SQL.parse(
                "SELECT a, b INTO OUTFILE '/tmp/a.csv' FROM t");
        assertEquals("OUTFILE", outfile.intoFileKind());
        assertNotNull(outfile.intoOutfile());
        assertTrue(outfile.intoOutfile().contains("/tmp/a.csv")
                || outfile.intoOutfile().contains("tmp"));
        String of = SQL.toSqlString(outfile);
        assertTrue(of.toUpperCase().contains("OUTFILE"));
    }

    /**
     * 数字开头裸标识符（MySQL：可数字开头但不能纯数字）；不破坏字面量。
     */
    @Test
    public void digitLeadingBareIdentifiers() {
        SqlSelect s = (SqlSelect) SQL.parse(
                "SELECT DISTINCT 1019使用.年 FROM 1019使用 WHERE 1 = 1 ORDER BY 1019使用.省份 DESC");
        assertEquals(SqlStatementType.SELECT, s.type());
        assertTrue(SQL.tables(s).toString(), SQL.tables(s).contains("1019使用"));

        SqlSelect join = (SqlSelect) SQL.parse(
                "select 32强国.* from 32强国 left join 32强国家 on 32强国.国家= 32强国家.国家 "
                        + "where 32强国家.分组 = 'A' or 32强国家.计算2 > ? limit 5");
        assertEquals(SqlStatementType.SELECT, join.type());
        assertTrue(SQL.tables(join).toString(), SQL.tables(join).contains("32强国"));
        assertTrue(SQL.tables(join).toString(), SQL.tables(join).contains("32强国家"));

        // 纯数字 / 小数 / 科学计数 / 十六进制仍为字面量
        SqlSelect lit = (SqlSelect) SQL.parse("SELECT 32, 32.5, 32e1, 0xFF FROM t");
        assertEquals(SqlStatementType.SELECT, lit.type());
        assertEquals("t", SQL.tables(lit).iterator().next());

        // 前导小数点仍为 NUMBER
        SqlSelect leadDot = (SqlSelect) SQL.parse("SELECT .5, .52, .52e1 FROM t");
        assertEquals(SqlStatementType.SELECT, leadDot.type());
        assertEquals("SELECT .5, .52, .52e1 FROM t", SQL.toSqlString(leadDot).replace("\n", " ").replace("  ", " ").trim()
                .replaceAll("\\s+", " "));
    }

    /**
     * 限定名中点号后的数字开头标识符（t.1_id / test.52_user），勿把 .52 当成小数。
     */
    @Test
    public void digitLeadingQualifiedIdentifiers() {
        String userSql = "select 1_id,`name` from test.52_user t where age > 20 and t.1_id is not null";
        SqlSelect user = (SqlSelect) SQL.parse(userSql);
        assertEquals(SqlStatementType.SELECT, user.type());
        assertTrue(SQL.tables(user).toString(), SQL.tables(user).contains("test.52_user"));
        String roundTrip = SQL.toSqlString(user);
        assertTrue(roundTrip, roundTrip.contains("1_id") || roundTrip.contains("`1_id`"));
        assertTrue(roundTrip, roundTrip.contains("52_user") || roundTrip.contains("`52_user`"));
        // 再 parse 一次确认 format 往返
        assertEquals(SqlStatementType.SELECT, SQL.parse(roundTrip).type());

        SqlSelect col = (SqlSelect) SQL.parse("select t.1_id from t");
        assertEquals(SqlStatementType.SELECT, col.type());
        assertTrue(SQL.toSqlString(col), SQL.toSqlString(col).contains("1_id"));

        SqlSelect tbl = (SqlSelect) SQL.parse("select * from test.52_user");
        assertEquals(SqlStatementType.SELECT, tbl.type());
        assertTrue(SQL.tables(tbl).toString(), SQL.tables(tbl).contains("test.52_user"));

        SqlSelect cjk = (SqlSelect) SQL.parse("select a.32强国 from t a");
        assertEquals(SqlStatementType.SELECT, cjk.type());
        assertTrue(SQL.toSqlString(cjk), SQL.toSqlString(cjk).contains("32强国"));

        // 反引号形式原本就可解析
        SqlSelect quoted = (SqlSelect) SQL.parse("select * from `test`.`52_user`");
        assertEquals(SqlStatementType.SELECT, quoted.type());
        assertTrue(SQL.tables(quoted).toString(), SQL.tables(quoted).contains("test.52_user")
                || SQL.tables(quoted).toString().contains("52_user"));

        // 裸数字开头标识符仍 OK
        SqlSelect bare = (SqlSelect) SQL.parse("select 1_id from t");
        assertEquals(SqlStatementType.SELECT, bare.type());

        // 纯小数字面量仍为 NUMBER
        assertEquals(SqlStatementType.SELECT, SQL.parse("SELECT .5").type());
        assertEquals(SqlStatementType.SELECT, SQL.parse("SELECT 1.5").type());
    }

    /**
     * IN :name / IN ? 可不写括号（单一绑定即整个列表）；IN (:name) 仍可用。
     */
    @Test
    public void inBindWithoutParentheses() {
        SqlSelect named = (SqlSelect) SQL.parse(
                "select * from table where (type in :types and source = :source) "
                        + "or ( source != :source and price >= :minPrice and price <= :maxPrice)");
        assertEquals(SqlStatementType.SELECT, named.type());
        assertNotNull(named.where());

        SqlSelect pos = (SqlSelect) SQL.parse("SELECT * FROM t WHERE id IN ?");
        assertEquals(SqlStatementType.SELECT, pos.type());

        SqlSelect paren = (SqlSelect) SQL.parse("SELECT * FROM t WHERE id IN (:ids)");
        assertEquals(SqlStatementType.SELECT, paren.type());
        String formatted = SQL.toSqlString(named);
        assertTrue(formatted.toUpperCase().contains("IN"));
    }

    /**
     * MySQL LOAD DATA [LOCAL] INFILE … INTO TABLE … → OTHER，抽表名。
     */
    @Test
    public void loadDataInfile() {
        SqlSimpleStatement load = (SqlSimpleStatement) SQL.parse(
                "LOAD DATA INFILE '/tmp/a.csv' INTO TABLE stg.foo "
                        + "FIELDS TERMINATED BY ','");
        assertEquals(SqlStatementType.OTHER, load.type());
        assertNotNull(load.name());
        assertEquals("stg.foo", load.name().qualifiedName());
        assertTrue(load.text().toUpperCase().startsWith("LOAD"));
        assertTrue(load.text().toUpperCase().contains("INFILE"));

        SqlSimpleStatement local = (SqlSimpleStatement) SQL.parse(
                "LOAD DATA LOCAL INFILE 'x.dat' INTO TABLE t");
        assertEquals(SqlStatementType.OTHER, local.type());
        assertEquals("t", local.name().simpleName());
    }

    /**
     * 顶层匿名 DECLARE BEGIN … END;（PL/SQL）；不破坏 DECLARE x INT / BEGIN WORK。
     */
    @Test
    public void anonymousDeclareBeginEnd() {
        SqlSimpleStatement anon = (SqlSimpleStatement) SQL.parse(
                "declare\n"
                + "begin\n"
                + "  test_procedure();\n"
                + "end;");
        assertEquals(SqlStatementType.OTHER, anon.type());
        assertTrue(anon.text().toUpperCase().contains("DECLARE"));
        assertTrue(anon.text().toUpperCase().contains("BEGIN"));
        assertTrue(anon.text().toUpperCase().contains("END"));

        SqlSimpleStatement compact = (SqlSimpleStatement) SQL.parse(
                "DECLARE BEGIN NULL; END;");
        assertEquals(SqlStatementType.OTHER, compact.type());
        assertTrue(compact.text().toUpperCase().contains("BEGIN"));

        SqlSimpleStatement decl = (SqlSimpleStatement) SQL.parse("DECLARE x INT DEFAULT 1");
        assertEquals("x", decl.name().simpleName());

        SqlSimpleStatement beginTx = (SqlSimpleStatement) SQL.parse("BEGIN WORK");
        assertTrue(beginTx.text().toUpperCase().contains("WORK"));
    }

    /**
     * LOCK TABLES / UNLOCK TABLES → OTHER；抽第一张表名。
     */
    @Test
    public void lockUnlockTables() {
        SqlSimpleStatement lock = (SqlSimpleStatement) SQL.parse(
                "LOCK TABLES t READ, u WRITE");
        assertEquals(SqlStatementType.OTHER, lock.type());
        assertNotNull(lock.text());
        assertTrue(lock.text().toUpperCase().startsWith("LOCK"));
        assertTrue(lock.text().toUpperCase().contains("TABLES"));
        assertEquals("t", lock.name().qualifiedName());
        assertTrue(SQL.tables(lock).toString(), SQL.tables(lock).contains("t"));
        assertTrue(SQL.toSqlString(lock).toUpperCase().contains("LOCK"));

        SqlSimpleStatement lockAs = (SqlSimpleStatement) SQL.parse(
                "LOCK TABLES db.t AS a READ LOCAL");
        assertEquals("db.t", lockAs.name().qualifiedName());
        assertTrue(lockAs.text().toUpperCase().contains("READ"));

        SqlSimpleStatement unlock = (SqlSimpleStatement) SQL.parse("UNLOCK TABLES");
        assertEquals(SqlStatementType.OTHER, unlock.type());
        assertTrue(unlock.text().toUpperCase().startsWith("UNLOCK"));
        assertTrue(unlock.text().toUpperCase().contains("TABLES"));

        java.util.List<SqlStatement> batch = SQL.parseAll(
                "LOCK TABLES t WRITE; UPDATE t SET a = 1; UNLOCK TABLES");
        assertEquals(3, batch.size());
        assertEquals(SqlStatementType.OTHER, batch.get(0).type());
        assertEquals(SqlStatementType.UPDATE, batch.get(1).type());
        assertEquals(SqlStatementType.OTHER, batch.get(2).type());
    }

    /**
     * SELECT … FROM … INTO @var / OUTFILE / DUMPFILE（INTO 在 FROM 之后）。
     */
    @Test
    public void selectFromThenInto() {
        SqlSelect intoVar = (SqlSelect) SQL.parse(
                "SELECT id, name FROM t WHERE id = 1 INTO @id, @name");
        assertNull(intoVar.intoTable());
        assertEquals(2, intoVar.intoVariables().size());
        assertEquals("t", ((com.alianga.jkit.sql.ast.SqlTable) intoVar.from()).name().qualifiedName());
        String varOut = SQL.toSqlString(intoVar);
        assertTrue(varOut.toUpperCase().contains("INTO"));
        SqlSelect againVar = (SqlSelect) SQL.parse(varOut);
        assertEquals(2, againVar.intoVariables().size());

        SqlSelect outfile = (SqlSelect) SQL.parse(
                "SELECT a, b FROM t ORDER BY a INTO OUTFILE '/tmp/b.csv'");
        assertEquals("OUTFILE", outfile.intoFileKind());
        assertNotNull(outfile.intoOutfile());
        assertTrue(outfile.intoOutfile().contains("/tmp/b.csv")
                || outfile.intoOutfile().contains("tmp"));
        assertTrue(SQL.toSqlString(outfile).toUpperCase().contains("OUTFILE"));

        SqlSelect dump = (SqlSelect) SQL.parse(
                "SELECT * FROM t LIMIT 10 INTO DUMPFILE '/tmp/c.bin'");
        assertEquals("DUMPFILE", dump.intoFileKind());
        assertNotNull(dump.intoOutfile());
    }

    /**
     * MySQL 客户端 DELIMITER：OTHER 占位，并切换批处理终止符（;; ↔ ; / $）。
     */
    @Test
    public void delimiterClientCommand() {
        SqlSimpleStatement d1 = (SqlSimpleStatement) SQL.parse("DELIMITER ;;");
        assertEquals(SqlStatementType.OTHER, d1.type());
        assertTrue(d1.text().toUpperCase().startsWith("DELIMITER"));
        assertTrue(d1.text().contains(";;"));

        SqlSimpleStatement d2 = (SqlSimpleStatement) SQL.parse("DELIMITER ;");
        assertEquals(SqlStatementType.OTHER, d2.type());
        assertTrue(d2.text().toUpperCase().startsWith("DELIMITER"));

        SqlSimpleStatement d3 = (SqlSimpleStatement) SQL.parse("DELIMITER $");
        assertEquals(SqlStatementType.OTHER, d3.type());
        assertTrue(d3.text().toUpperCase().contains("DELIMITER"));
        assertTrue(d3.text().contains("$"));

        // DELIMITER ;; 后单分号不再切分；须以 ;; 结束语句，再切回 ;
        java.util.List<SqlStatement> batch = SQL.parseAll(
                "DELIMITER ;;\n"
                        + "SELECT 1;;\n"
                        + "DELIMITER ;\n"
                        + "SELECT 2;");
        assertEquals(4, batch.size());
        assertEquals(SqlStatementType.OTHER, batch.get(0).type());
        assertTrue(((SqlSimpleStatement) batch.get(0)).text().contains(";;"));
        assertEquals(SqlStatementType.SELECT, batch.get(1).type());
        assertEquals(SqlStatementType.OTHER, batch.get(2).type());
        assertEquals(SqlStatementType.SELECT, batch.get(3).type());

        // 定界符前留空白，避免 1$ 被词法粘成 IDENT（$ 是 identPart）
        java.util.List<SqlStatement> dollar = SQL.parseAll(
                "DELIMITER $\n"
                        + "SELECT 1 $\n"
                        + "DELIMITER ;\n"
                        + "SELECT 2;");
        assertEquals(4, dollar.size());
        assertEquals(SqlStatementType.SELECT, dollar.get(1).type());
        assertEquals(SqlStatementType.SELECT, dollar.get(3).type());

        // // 前须留空白，避免 1// 被当成除法
        java.util.List<SqlStatement> slash = SQL.parseAll(
                "DELIMITER //\n"
                        + "SELECT 1 //\n"
                        + "DELIMITER ;\n"
                        + "SELECT 2;");
        assertEquals(4, slash.size());
        assertEquals(SqlStatementType.SELECT, slash.get(1).type());
        assertEquals(SqlStatementType.SELECT, slash.get(3).type());
    }

    /**
     * SET PASSWORD [FOR user] = '…' 可解析（SET + text）。
     */
    /**
     * CREATE DEFINER=… PROCEDURE + DELIMITER 批（Joplin 存储过程语料最小集）。
     */
    @Test
    public void createDefinerProcedureWithDelimiter() {
        java.util.List<SqlStatement> batch = SQL.parseAll(
                "DROP PROCEDURE IF EXISTS `proc_adder`;\n"
                        + "DELIMITER ;;\n"
                        + "CREATE DEFINER=`root`@`localhost` PROCEDURE `proc_adder`"
                        + "(IN a int, IN b int, OUT sum int)\n"
                        + "BEGIN\n"
                        + "    DECLARE c int;\n"
                        + "    set sum = a + b;\n"
                        + "END\n"
                        + ";;\n"
                        + "DELIMITER ;");
        // DROP ; + DELIMITER ;; + CREATE…END;; + DELIMITER ;
        assertEquals(4, batch.size());
        assertEquals(SqlStatementType.DROP, batch.get(0).type());
        assertEquals(SqlStatementType.OTHER, batch.get(1).type());
        assertTrue(((SqlSimpleStatement) batch.get(1)).text().contains(";;"));
        assertEquals(SqlStatementType.CREATE, batch.get(2).type());
        com.alianga.jkit.sql.ast.SqlDdlStatement ddl =
                (com.alianga.jkit.sql.ast.SqlDdlStatement) batch.get(2);
        assertEquals("PROCEDURE", ddl.objectType());
        assertEquals("proc_adder", ddl.names().get(0).simpleName());
        assertNotNull(ddl.tail());
        assertTrue(ddl.tail().toUpperCase().contains("BEGIN"));
        assertEquals(SqlStatementType.OTHER, batch.get(3).type());
        assertTrue(((SqlSimpleStatement) batch.get(3)).text().toUpperCase().startsWith("DELIMITER"));
    }

    /**
     * 词法 peek 不得污染 parser 当前记号：DATE/TIMESTAMP 函数、MIN(Date)、列名 percent、SQLite trim(X,Y)。
     */
    @Test
    public void dateTimestampFunctionAndPercentIdentAndSqliteTrim() {
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT date(rental_date) FROM rental").type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT DATE() FROM t").type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT datetime(x, 'localtime') FROM t").type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT MIN(Date) FROM works").type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT T2.percent FROM Vote AS T2").type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT SUM(IIF(timestamp = 'x', 1, 0)) FROM events").type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT REPLACE(trim(total_gross, '$'), ',', '') FROM movies").type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT strftime('%J', date('now')) FROM t").type());
        // 类型字面量 DATE '...' 仍可用
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT DATE '2020-01-01' FROM dual", SqlDialect.POSTGRES).type());
        // TOP n PERCENT 仍可用
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT TOP 10 PERCENT id FROM t", SqlDialect.SQLSERVER).type());
    }


    /**
     * 词形式 MINUS 不作二元减号；Oracle KEEP (DENSE_RANK ...) 挂在聚合后。
     */
    @Test
    public void oracleMinusSetOpAndKeepDenseRank() {
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT a FROM t WHERE x != 'y' MINUS SELECT b FROM t2", SqlDialect.ORACLE).type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT 1 FROM t MINUS SELECT 2 FROM t", SqlDialect.ORACLE).type());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT MAX(LANGUAGE) KEEP (DENSE_RANK LAST ORDER BY PERCENTAGE) FROM t",
                        SqlDialect.ORACLE).type());
        // 符号减号仍可用
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT 1 - 2 FROM t").type());
    }


    /**
     * SELECT 列表别名允许点号限定名（AS a.b / 隐式 a.b）。
     */
    @Test
    public void dottedSelectAlias() {
        SqlSelect s = (SqlSelect) SQL.parse("SELECT T2.NAME AS MUSICAL.NAME FROM MUSICAL.ACTOR T2");
        assertEquals(SqlStatementType.SELECT, s.type());
        assertEquals("MUSICAL.NAME", s.selectItems().get(0).alias());
        assertEquals(SqlStatementType.SELECT,
                SQL.parse("SELECT a AS b.c FROM t").type());
    }

    @Test
    public void setPassword() {
        SqlSimpleStatement forUser = (SqlSimpleStatement) SQL.parse(
                "SET PASSWORD FOR myuser = 'mypass'");
        assertEquals(SqlStatementType.SET, forUser.type());
        assertNotNull(forUser.text());
        assertTrue(forUser.text().toUpperCase().contains("PASSWORD"));
        assertTrue(forUser.text().toUpperCase().contains("FOR"));
        String out = SQL.toSqlString(forUser);
        assertTrue(out.toUpperCase().startsWith("SET"));
        assertTrue(out.toUpperCase().contains("PASSWORD"));

        SqlSimpleStatement plain = (SqlSimpleStatement) SQL.parse(
                "SET PASSWORD = 'secret'");
        assertEquals(SqlStatementType.SET, plain.type());
        assertTrue(plain.text().toUpperCase().contains("PASSWORD"));
        assertTrue(SQL.toSqlString(plain).toUpperCase().contains("PASSWORD"));
    }


}

