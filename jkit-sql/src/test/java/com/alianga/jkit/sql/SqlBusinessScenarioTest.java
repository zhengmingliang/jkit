package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlEntities;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;
import com.alianga.jkit.sql.jdbc.JdbcUrlInfo;
import com.alianga.jkit.sql.jdbc.JdbcUrlUtils;
import com.alianga.jkit.sql.schema.convert.ConversionResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 业务场景样例集：每个场景对应一类真实业务诉求，配至少两个最佳实践案例。
 * 新场景先在这里落地验证，再同步进 docs/sql.md 的「业务场景」章节。
 *
 * <p>场景清单：
 * <ol>
 *   <li>SQL 审计与依赖分析</li>
 *   <li>读写分离路由</li>
 *   <li>SQL 注入防护（参数化 / bind）</li>
 *   <li>SQL 防火墙（Wall）</li>
 *   <li>跨方言数据库迁移</li>
 *   <li>多方言分页适配</li>
 *   <li>多租户改写（分表 + 租户过滤）</li>
 *   <li>数据脱敏与列级权限</li>
 *   <li>动态 SQL 构建（零字符串拼接）</li>
 *   <li>实体驱动多方言建表</li>
 *   <li>SQL 格式化与规范统一</li>
 *   <li>遗留模板占位符迁移（含 MyBatis）</li>
 *   <li>表达式预计算（规则引擎 / 预览）</li>
 *   <li>安全改写不污染原语句</li>
 *   <li>多数据源方言自动识别（JdbcUrlUtils）</li>
 *   <li>报表函数跨方言改写（DATE_FORMAT）</li>
 *   <li>动态表名 / 分表安全绑定</li>
 *   <li>低代码查询沙箱（Wall 表策略）</li>
 * </ol>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class SqlBusinessScenarioTest {

    private static String norm(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------
    // 场景 1：SQL 审计与依赖分析——上线前扫描 SQL 涉及哪些表哪些列，做影响面评估
    // ------------------------------------------------------------------

    @Test
    public void auditExtractsTableAndColumnDependencies() {
        SqlStatement stmt = SQL.parse(
                "SELECT u.id, u.name FROM users u JOIN orders o ON u.id = o.uid WHERE o.amount > 100");
        List<String> tables = SQL.tables(stmt);
        assertTrue(tables.toString(), tables.contains("users"));
        assertTrue(tables.toString(), tables.contains("orders"));
        assertTrue(SQL.stat(stmt).getColumns().toString(), SQL.stat(stmt).getColumns().contains("o.amount"));
    }

    @Test
    public void auditFlagsWriteOperationsBeforeRelease() {
        assertTrue("SELECT 是只读语句", SQL.parse("SELECT * FROM t WHERE id = 1").isReadOnly());
        assertFalse("UPDATE 应被标记为写操作", SQL.parse("UPDATE t SET a = 1 WHERE id = 2").isReadOnly());
        assertFalse("DELETE 应被标记为写操作", SQL.parse("DELETE FROM t WHERE id = 2").isReadOnly());
        // 审计视角：全量脚本扫描统计写语句数量
        List<SqlStatement> stmts = SQL.parseAll("SELECT 1; UPDATE t SET a = 1 WHERE id = 2");
        long writes = 0;
        for (SqlStatement s : stmts) {
            if (!s.isReadOnly()) {
                writes++;
            }
        }
        assertEquals(1, writes);
    }

    // ------------------------------------------------------------------
    // 场景 2：读写分离路由——代理层按语句类型分流主从
    // ------------------------------------------------------------------

    @Test
    public void routerSendsSelectToReplica() {
        SqlStatement stmt = SQL.parse("SELECT * FROM t_user WHERE id = 1");
        assertTrue("SELECT 应路由到只读副本", stmt.isReadOnly());
    }

    @Test
    public void routerSendsWriteAndSelectForUpdateToPrimary() {
        // SELECT ... FOR UPDATE 持锁，必须走主库
        SqlStatement forUpdate = SQL.parse("SELECT * FROM t_user WHERE id = 1 FOR UPDATE");
        assertFalse("SELECT FOR UPDATE 应路由到主库", forUpdate.isReadOnly());
        SqlStatement insert = SQL.parse("INSERT INTO t_user(id, name) VALUES (1, 'a')");
        assertFalse("INSERT 应路由到主库", insert.isReadOnly());
    }

    // ------------------------------------------------------------------
    // 场景 3：SQL 注入防护——把外部拼接的字面量收编成绑定参数
    // ------------------------------------------------------------------

    @Test
    public void parameterizeConvertsInlineLiteralsToBinds() {
        String out = SQL.parameterize(
                "SELECT id FROM t_user WHERE name = 'alice' AND age = 18 AND deleted = false");
        assertTrue(out, out.contains("name = ?"));
        assertTrue(out, out.contains("age = ?"));
        assertFalse(out, out.contains("'alice'"));
    }

    @Test
    public void exportParameterValuesFeedsPreparedStatement() {
        // 拦截到字符串拼接的 SQL 后：值导出成绑定值，语句参数化，两步即可安全下发
        SqlStatement stmt = SQL.parse(
                "SELECT * FROM t_user WHERE name = 'alice' AND age = 18 AND id = ?");
        List<Object> values = SQL.exportParameterValues(stmt);
        assertEquals(2, values.size());
        assertEquals("alice", values.get(0));
        assertEquals(Integer.valueOf(18), values.get(1));
        String parameterized = SQL.parameterize(stmt);
        assertFalse(parameterized, parameterized.contains("'alice'"));
    }

    @Test
    public void bindFillsPlaceholdersWithoutSqlInjection() {
        String payload = "'; DROP TABLE t_user; --";
        String sql = SQL.bind("SELECT * FROM t_user WHERE name = ?", payload);
        assertTrue(sql, sql.contains("'''; DROP TABLE t_user; --'"));
        assertEquals(1, SQL.parseAll(sql).size());
        SQL.parse(sql, SqlDialect.MYSQL);

        String inList = SQL.bind("SELECT * FROM t WHERE id IN ?", Arrays.asList(1, 2, 3));
        assertTrue(inList, inList.contains("IN (1, 2, 3)") || inList.contains("IN(1, 2, 3)"));
        SQL.parse(inList, SqlDialect.MYSQL);
    }

    @Test
    public void bindNamedFillsValuesAndFormulas() {
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("id", Integer.valueOf(7));
        vals.put("ts", SQL.parseExpr("NOW()"));
        String sql = SQL.bindNamed(
                "SELECT * FROM t_user WHERE id = :id AND created_at > :ts", vals);
        assertTrue(sql, sql.contains("id = 7"));
        assertTrue(sql, sql.contains("NOW()"));
        assertFalse(sql, sql.contains("'NOW()'"));
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    // ------------------------------------------------------------------
    // 场景 4：SQL 防火墙——对用户可编辑查询 / 开放接口做危险语句拦截
    // ------------------------------------------------------------------

    @Test
    public void wallBlocksInjectionPatternsAndFullTableWrites() {
        assertTrue(SQL.wall("SELECT 1; DELETE FROM t WHERE id = 1").violations().contains("multi-statement"));
        assertTrue(SQL.wall("DELETE FROM t").violations().contains("delete-without-where"));
        assertTrue(SQL.wall("UPDATE t SET a = 1").violations().contains("update-without-where"));
        assertTrue(SQL.wall("SELECT SLEEP(5) FROM t").violations().contains("dangerous-function"));
        assertTrue(SQL.wall("SELECT * FROM t WHERE name = 'x' --").violations().contains("comment-bypass"));
        assertTrue(SQL.wall("SELECT * FROM t WHERE name LIKE '%'").violations()
                .contains("always-true-condition"));
        assertTrue(SQL.wall("SELECT * FROM t WHERE id = 1 XOR 1 = 1").violations()
                .contains("always-true-condition"));
        assertTrue("合法查询应放行", SQL.wall("SELECT * FROM t WHERE id = 1").passed());
    }

    @Test
    public void wallConfigAllowsControlledDdlForOpsChannel() {
        // 运维通道需要放行 DDL 时按配置开启，默认仍然拦截
        assertTrue(SQL.wall("DROP TABLE t").violations().contains("deny-ddl"));
        SqlWallConfig allowDdl = SqlWallConfig.defaults().denyDdl(false);
        assertTrue(SQL.wall("DROP TABLE t", SqlDialect.MYSQL, allowDdl).passed());
    }

    // ------------------------------------------------------------------
    // 场景 5：跨方言数据库迁移——MySQL 存量 DDL / DML 翻译到目标库
    // ------------------------------------------------------------------

    @Test
    public void migrationTranslatesMysqlDdlToPostgres() {
        ConversionResult r = com.alianga.jkit.sql.schema.convert.SqlSchemaConverter.convert(
                "ALTER TABLE t MODIFY c INT NOT NULL", SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals("ALTER TABLE t ALTER COLUMN c TYPE INTEGER", norm(r.sql()));
        // 输出必须能被目标方言再解析，形成验证闭环
        SQL.parse(r.sql(), SqlDialect.POSTGRES);
    }

    @Test
    public void migrationBatchConvertsWithFunctionRewrites() {
        List<ConversionResult> results = SQL.convertBatch(
                Arrays.asList(
                        "SELECT GROUP_CONCAT(name) FROM t",
                        "SELECT IFNULL(a, b) FROM t"),
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertEquals(2, results.size());
        assertEquals("SELECT STRING_AGG(name, ',') FROM t", norm(results.get(0).sql()));
        SQL.parse(results.get(0).sql(), SqlDialect.POSTGRES);
        SQL.parse(results.get(1).sql(), SqlDialect.POSTGRES);
    }

    // ------------------------------------------------------------------
    // 场景 6：多方言分页——同一份业务查询按目标方言生成分页语句
    // ------------------------------------------------------------------

    @Test
    public void paginationOnMysqlAndPostgresUsesLimitOffset() {
        SqlStatement page = SQL.setPage(
                SQL.parse("SELECT id FROM orders WHERE status = 1"), 2, 10, SqlDialect.MYSQL);
        assertEquals(Long.valueOf(10L), SQL.getLimit(page));
        assertEquals(Long.valueOf(10L), SQL.getOffset(page));

        SqlStatement pg = SQL.setPage(
                SQL.parse("SELECT id FROM orders WHERE status = 1", SqlDialect.POSTGRES),
                3, 5, SqlDialect.POSTGRES);
        String sql = SQL.toSqlString(pg, SqlDialect.POSTGRES).toUpperCase();
        assertTrue(sql, sql.contains("LIMIT") && sql.contains("OFFSET"));
    }

    @Test
    public void paginationOnOracleClassicUsesRownum() {
        // 12c 前的 Oracle 没有 OFFSET/FETCH，必须翻成 ROWNUM 子查询
        SqlStatement page = SQL.setPage(
                SQL.parse("SELECT * FROM emp", SqlDialect.ORACLE), 2, 8, SqlDialect.ORACLE);
        String sql = SQL.toSqlString(page, SqlDialect.ORACLE).toUpperCase();
        assertFalse("Oracle 经典分页不应出现 OFFSET/FETCH", sql.contains("OFFSET"));
        assertTrue(sql, sql.contains("ROWNUM"));
        // 输出可被 Oracle 复解析
        SQL.parse(SQL.toSqlString(page, SqlDialect.ORACLE), SqlDialect.ORACLE);
    }

    // ------------------------------------------------------------------
    // 场景 7：多租户改写——按租户分表 + 自动注入租户过滤条件
    // ------------------------------------------------------------------

    @Test
    public void shardRoutingRewritesTableSuffix() {
        SqlStatement stmt = SQL.parse("SELECT id, name FROM t_user WHERE status = 1");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.replaceTable("t_user", "t_user_2026")));
        String sql = norm(SQL.toSqlString(out));
        assertTrue(sql, sql.contains("FROM t_user_2026"));
        assertFalse(sql, sql.contains("t_user "));
    }

    @Test
    public void injectAddsRowPredicate() {
        // 网关层给匹配表白名单的查询追加行级条件（列名自定）
        SqlStatement stmt = SQL.parse("SELECT id FROM t_order WHERE status = 1");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.andWhere(SQL.parseExpr("tenant_id = 100"))));
        String sql = norm(SQL.toSqlString(out));
        assertTrue(sql, sql.contains("tenant_id = 100"));
        assertTrue(sql, sql.contains("status = 1"));
        // 改写发生在 clone 上，原语句不被动过
        assertFalse(norm(SQL.toSqlString(stmt)), norm(SQL.toSqlString(stmt)).contains("tenant_id"));
    }

    @Test
    public void injectConfigAddsRowFiltersOnWhitelistedTables() {
        SQL.injectConfig(SqlInjectConfig.create()
                .tables("t_order")
                .add("tenant_id", 100)
                .add("deleted", 0));
        try {
            SqlStatement out = SQL.inject(SQL.parse("SELECT id FROM t_order WHERE status = 1"));
            String sql = norm(SQL.toSqlString(out));
            assertTrue(sql, sql.contains("tenant_id = 100"));
            assertTrue(sql, sql.contains("deleted = 0"));
            assertTrue(sql, sql.contains("status = 1"));
            SQL.parse(sql, SqlDialect.MYSQL);
        } finally {
            SqlInject.clear();
            SqlInject.setDefault(null);
        }
    }

    // ------------------------------------------------------------------
    // 场景 8：数据脱敏与列级权限——对外接口裁掉敏感列 / 适配物理列改名
    // ------------------------------------------------------------------

    @Test
    public void maskingRemovesSensitiveColumns() {
        SqlStatement stmt = SQL.parse("SELECT id, name, phone, id_card FROM t_customer WHERE id = 1");
        SqlStatement out = SQL.removeSelectItem(SQL.clone(stmt), "phone");
        out = SQL.removeSelectItem(out, "id_card");
        String sql = norm(SQL.toSqlString(out)).toUpperCase();
        assertFalse(sql, sql.contains("PHONE"));
        assertFalse(sql, sql.contains("ID_CARD"));
        assertTrue(sql, sql.contains("NAME"));
    }

    @Test
    public void columnMappingAdaptsPhysicalRename() {
        // 库表重构（name → user_name）后，旧 SQL 不改代码直接改写适配
        SqlStatement stmt = SQL.parse("SELECT name FROM t_user WHERE name = 'a'");
        SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
                .add(SqlRewrites.replaceColumn("name", "user_name")));
        String sql = norm(SQL.toSqlString(out));
        assertTrue(sql, sql.contains("user_name"));
        assertFalse(sql, sql.contains(" name"));
    }

    @Test
    public void expandStarThenReplaceSelectItemsMasksManyColumns() {
        Map<String, List<String>> cols = new LinkedHashMap<String, List<String>>();
        cols.put("t_customer", Arrays.asList("id", "name", "phone", "id_card"));
        Map<String, String> masks = new LinkedHashMap<String, String>();
        masks.put("phone", "CONCAT(LEFT(phone, 3), '****')");
        masks.put("id_card", "'****'");
        SqlStatement masked = SQL.replaceSelectItems(
                SQL.expandStar(SQL.parse("SELECT * FROM t_customer"), cols), masks);
        String sql = SQL.toSqlString(masked);
        assertFalse(sql, sql.contains("SELECT *"));
        assertTrue(sql, sql.contains("CONCAT"));
        assertTrue(sql, sql.contains("'****'"));
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    // ------------------------------------------------------------------
    // 场景 9：动态 SQL 构建——替代字符串拼接，杜绝注入与语法错
    // ------------------------------------------------------------------

    @Test
    public void builderComposesConditionalSelect() {
        String sql = SqlBuilder.select("id", "name")
                .from("users")
                .where("status = 1")
                .and("age > 18")
                .orderBy("id")
                .limit(10)
                .toSql();
        String n = norm(sql);
        assertTrue(n, n.contains("SELECT id, name FROM users"));
        assertTrue(n, n.contains("WHERE status = 1 AND age > 18"));
        assertTrue(n, n.contains("ORDER BY id"));
        assertTrue(n, n.contains("LIMIT 10"));
        // 产物是合法 SQL
        SQL.parse(sql);
    }

    @Test
    public void builderComposesInsertAndUpdateSafely() {
        String insert = SqlBuilder.insertInto("t_user")
                .columns("id", "name")
                .values(1L, "alice")
                .toSql();
        String nInsert = norm(insert);
        assertTrue(nInsert, nInsert.startsWith("INSERT INTO t_user(id, name) VALUES (1, 'alice')"));
        SQL.parse(insert);

        String update = SqlBuilder.update("t_user")
                .set("name", "bob")
                .where("id = 1")
                .toSql();
        String n = norm(update);
        assertTrue(n, n.startsWith("UPDATE t_user SET name = 'bob' WHERE id = 1"));
        SQL.parse(update);
    }

    // ------------------------------------------------------------------
    // 场景 10：实体驱动多方言建表——一套注解实体生成各库 DDL
    // ------------------------------------------------------------------

    @Test
    public void entityGeneratesMysqlTable() {
        @SqlTable(name = "t_member", comment = "会员表")
        class Member {
            @SqlId
            Long id;
            @SqlColumn(comment = "昵称")
            String nick;
        }
        String ddl = SqlEntities.createTable(Member.class, SqlDialect.MYSQL);
        assertTrue(ddl, ddl.contains("CREATE TABLE t_member"));
        assertTrue(ddl, ddl.contains("COMMENT '会员表'"));
        assertTrue(ddl, ddl.contains("COMMENT '昵称'"));
        SQL.parse(ddl, SqlDialect.MYSQL);
    }

    @Test
    public void entityGeneratesPostgresCommentOnStyle() {
        @SqlTable(name = "t_member", comment = "会员表")
        class Member {
            @SqlId
            Long id;
            @SqlColumn(comment = "昵称")
            String nick;
        }
        String ddl = SqlEntities.createTable(Member.class, SqlDialect.POSTGRES);
        // PG 建表语句不带 COMMENT，注释走独立的 COMMENT ON
        assertTrue(ddl, ddl.contains("COMMENT ON TABLE t_member IS '会员表'"));
        assertTrue(ddl, ddl.contains("COMMENT ON COLUMN t_member.nick IS '昵称'"));
    }

    @Test
    public void entityGeneratesOracleSequenceAndSqlServerComment() {
        @SqlTable(name = "t_member", comment = "会员表")
        class Member {
            @SqlId
            @SqlGenerated
            Long id;
            @SqlColumn(comment = "昵称")
            String nick;
        }
        String oracle = SqlEntities.createTable(Member.class, SqlDialect.ORACLE);
        assertTrue(oracle, oracle.contains("CREATE SEQUENCE t_member_id_seq"));
        assertTrue(oracle, oracle.toUpperCase().contains("TRIGGER"));
        assertTrue(oracle, oracle.contains("COMMENT ON TABLE t_member IS '会员表'"));

        String mssql = SqlEntities.createTable(Member.class, SqlDialect.SQLSERVER);
        assertTrue(mssql, mssql.contains("sp_addextendedproperty"));
        assertTrue(mssql, mssql.contains("会员表"));
        assertTrue(mssql, mssql.contains("昵称"));
    }

    // ------------------------------------------------------------------
    // 场景 11：SQL 格式化与规范统一——日志脱乱、评审 diff、关键字归一
    // ------------------------------------------------------------------

    @Test
    public void formattingNormalizesKeywordCase() {
        SqlStatement stmt = SQL.parse("select Id, Name from MyTable where Age > 18", SqlDialect.MYSQL);
        assertEquals("SELECT Id, Name FROM MyTable WHERE Age > 18",
                SQL.format(stmt, SqlDialect.MYSQL, false, SqlFormatOptions.defaults()));
        assertEquals("select Id, Name from MyTable where Age > 18",
                SQL.format(stmt, SqlDialect.MYSQL, false,
                        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)));
    }

    @Test
    public void prettyFormatBreaksClausesOntoLines() {
        String ugly = "select id,name,phone from t_user where status=1 and age>18 order by id desc limit 10";
        String pretty = SQL.format(ugly);
        assertTrue("美化输出应含换行", pretty.contains("\n"));
        // 往返稳定：美化后再解析语义不变
        SqlStatement first = SQL.parse(ugly);
        SqlStatement second = SQL.parse(pretty);
        assertEquals(first.isReadOnly(), second.isReadOnly());
        assertEquals(SQL.tables(first), SQL.tables(second));
    }

    @Test
    public void prettyFormatCreateTableBreaksColumnsOntoLines() {
        String sql = "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32) NOT NULL, age INT)";
        String pretty = SQL.format(sql + ";");
        assertTrue(pretty, pretty.contains("\n"));
        assertTrue(pretty, pretty.contains("  id INT PRIMARY KEY"));
        assertTrue(pretty, pretty.contains("  name VARCHAR(32) NOT NULL"));
        assertTrue(pretty, pretty.contains("  age INT"));
        String compact = SQL.toSqlString(SQL.parse(sql));
        assertFalse(compact, compact.contains("\n"));
        assertTrue(compact, compact.contains("id INT PRIMARY KEY, name VARCHAR(32) NOT NULL, age INT"));
        SqlStatement roundTrip = SQL.parse(pretty);
        assertEquals(SQL.tables(SQL.parse(sql)), SQL.tables(roundTrip));
    }

    @Test
    public void addCommentAndToStringUseMysqlQuotes() {
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        Map<String, Object> vals = Collections.<String, Object>singletonMap("table", Integer.valueOf(10086));
        SqlStatement bound = SQL.bindNamed(
                SQL.parse("SELECT * FROM #{table}", SqlDialect.MYSQL, opt),
                SqlDialect.MYSQL, vals);
        bound.addComment("我是注释");
        String sql = bound.toString();
        assertTrue(sql, sql.contains("`10086`"));
        assertFalse(sql, sql.contains("\"10086\""));
        assertTrue(sql, sql.contains("/*") && sql.contains("我是注释"));
        assertFalse(sql, sql.startsWith("我是注释"));
        SQL.parse(sql, SqlDialect.MYSQL);
        assertTrue(SQL.toSqlString(bound, SqlDialect.ORACLE).contains("\"10086\""));
        assertTrue(SQL.toSqlString(bound, SqlDialect.SQLSERVER).contains("[10086]"));
    }

    // ------------------------------------------------------------------
    // 场景 12：遗留模板占位符迁移——@xx@ / %s 风格 SQL 收编解析
    // ------------------------------------------------------------------

    @Test
    public void atWrappedPlaceholdersParseLegacyTemplates() {
        SqlStatement stmt = SQL.parse("select * from t_user where id = 1 and age > @minAge@ limit 10",
                SqlDialect.MYSQL, SqlParseOptions.defaults().placeholders(SqlPlaceholders.create().atWrapped()));
        assertEquals("t_user", SQL.tables(stmt).get(0));
        assertEquals(Long.valueOf(10L), SQL.getLimit(stmt));
    }

    @Test
    public void printfPlaceholdersParseDynamicColumns() {
        SqlStatement stmt = SQL.parse("SELECT %s FROM (SELECT '20221111' AS %s) AS a",
                SqlDialect.MYSQL, SqlParseOptions.defaults().placeholders(SqlPlaceholders.create().printf()));
        assertEquals(com.alianga.jkit.sql.ast.SqlStatementType.SELECT, stmt.type());
        // FROM 的是子查询派生表，无物理表依赖
        assertTrue(SQL.tables(stmt).toString(), SQL.tables(stmt).isEmpty());
    }

    @Test
    public void mybatisPlaceholdersParseAndBindByPropertyName() {
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        SqlStatement parsed = SQL.parse(
                "SELECT * FROM ${table} WHERE id = #{id, jdbcType=INTEGER} AND name = #{user.name}",
                SqlDialect.MYSQL, opt);
        assertEquals("${table}", SQL.tables(parsed).get(0));

        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("table", "t_user");
        vals.put("id", Integer.valueOf(7));
        vals.put("user.name", "bob");
        String sql = SQL.bindNamed(
                "SELECT * FROM ${table} WHERE id = #{id, jdbcType=INTEGER} AND name = #{user.name}",
                SqlDialect.MYSQL, opt, vals);
        assertTrue(sql, sql.contains("FROM t_user") || sql.contains("FROM `t_user`"));
        assertTrue(sql, sql.contains("id = 7"));
        assertTrue(sql, sql.contains("'bob'"));
        assertFalse(sql, sql.contains("#{id"));
        assertFalse(sql, sql.contains("${table}"));
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    // ------------------------------------------------------------------
    // 场景 13：表达式预计算——规则引擎 / 前端预览里先算常量折叠
    // ------------------------------------------------------------------

    @Test
    public void evalComputesConstantFoldedExpressions() {
        SqlStatement stmt = SQL.parse("SELECT 1 + 2 * 3");
        Object v = SQL.eval(((SqlSelect) stmt).selectItems().get(0).expr());
        assertEquals("7", String.valueOf(v));
    }

    @Test
    public void evalKeepsColumnRefsAsUnknown() {
        SqlStatement stmt = SQL.parse("SELECT price * 0.8 FROM t");
        // 引用列的表达式算不出值，返回 null 而不是报错，方便上层决定是否回退
        assertNull(SQL.eval(((SqlSelect) stmt).selectItems().get(0).expr()));
    }

    // ------------------------------------------------------------------
    // 场景 14（综合）：安全改写不污染原语句——网关缓存场景
    // ------------------------------------------------------------------

    @Test
    public void cloneKeepsOriginalStatementReusable() {
        SqlStatement original = SQL.parse("SELECT id, name FROM t_user WHERE status = 1");
        // removeSelectItem 自带 clone-then-mutate：返回新语句，原语句不被动过
        SqlStatement masked = SQL.removeSelectItem(original, "name");
        // 原语句可继续复用（例如命中缓存的原始模板）
        assertTrue(norm(SQL.toSqlString(original)).contains("name"));
        assertFalse(norm(SQL.toSqlString(masked)).contains("name"));
    }

    @Test
    public void pageCloneThenMutateDoesNotTouchOriginal() {
        SqlStatement original = SQL.parse("SELECT id FROM users WHERE status = 1");
        SQL.setPage(original, 2, 10, SqlDialect.MYSQL);
        assertNull("clone-then-mutate：分页改写不应改动原语句",
                ((SqlSelect) original).limit());
    }

    // ------------------------------------------------------------------
    // 场景 15：多数据源方言自动识别——URL 推断方言 / schema / 驱动，不硬编码
    // ------------------------------------------------------------------

    @Test
    public void jdbcUrlPicksDialectForParseAndFormat() {
        SqlDialect dialect = JdbcUrlUtils.fromUrl(
                "jdbc:postgresql://primary:5432/orders?currentSchema=sales");
        assertEquals(SqlDialect.POSTGRES, dialect);
        SqlStatement stmt = SQL.parse("SELECT id FROM t WHERE name = 'a' || 'b'", dialect);
        String sql = SQL.toSqlString(stmt, dialect);
        assertTrue(sql, sql.contains("||"));
        SQL.parse(sql, dialect);
        assertEquals(SqlDialect.DAMENG, JdbcUrlUtils.fromUrl("jdbc:dm://localhost:5236"));
        assertEquals(SqlDialect.MYSQL, JdbcUrlUtils.fromUrl("jdbc:tidb://127.0.0.1:4000/test"));
        assertNull(JdbcUrlUtils.fromUrl("jdbc:unknown:foo"));
    }

    @Test
    public void jdbcUrlExposesSchemaDriverAndHaNodes() {
        JdbcUrlInfo info = JdbcUrlUtils.parse(
                "jdbc:postgresql://primary:5432,standby:5432/orders?currentSchema=sales");
        assertEquals("postgresql", info.getDbType());
        assertEquals("orders", info.getDatabaseName());
        assertEquals("sales", info.getSchema());
        assertEquals(2, info.getNodes().size());
        assertEquals("dm.jdbc.driver.DmDriver", JdbcUrlUtils.driverForUrl("jdbc:dm://localhost:5236"));
        assertEquals("public", JdbcUrlUtils.schema("jdbc:postgresql://h/db"));
    }

    // ------------------------------------------------------------------
    // 场景 16：报表 SQL 跨方言函数改写——DATE_FORMAT 跟目标库走
    // ------------------------------------------------------------------

    @Test
    public void reportDateFormatConvertsToPgAndOracle() {
        String pg = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s') FROM t",
                SqlDialect.MYSQL, SqlDialect.POSTGRES);
        assertTrue(pg, pg.toUpperCase().contains("TO_CHAR"));
        assertTrue(pg, pg.contains("YYYY-MM-DD HH24:MI:SS"));
        SQL.parse(pg, SqlDialect.POSTGRES);

        String ora = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d') FROM t",
                SqlDialect.MYSQL, SqlDialect.ORACLE);
        assertTrue(ora, ora.contains("TO_CHAR"));
        assertTrue(ora, ora.contains("YYYY-MM-DD"));
        SQL.parse(ora, SqlDialect.ORACLE);
    }

    @Test
    public void reportDateFormatConvertsToSqliteStrftime() {
        String sql = SQL.convert("SELECT DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s') FROM t",
                SqlDialect.MYSQL, SqlDialect.SQLITE);
        assertTrue(sql, sql.contains("strftime"));
        assertTrue(sql, sql.contains("%Y-%m-%d %H:%M:%S"));
        SQL.parse(sql, SqlDialect.SQLITE);
    }

    // ------------------------------------------------------------------
    // 场景 17：动态表名 / 分表安全绑定——表名当标识符，不进字符串字面量
    // ------------------------------------------------------------------

    @Test
    public void dynamicTableNameBindsAsIdentifier() {
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        Map<String, Object> vals = new LinkedHashMap<String, Object>();
        vals.put("table", "t_user_2026");
        vals.put("id", Integer.valueOf(1));
        String sql = SQL.bindNamed("SELECT * FROM ${table} WHERE id = #{id}",
                SqlDialect.MYSQL, opt, vals);
        assertTrue(sql, sql.contains("FROM t_user_2026") || sql.contains("FROM `t_user_2026`"));
        assertFalse("table must not become a string literal", sql.contains("FROM 't_user_2026'"));
        SQL.parse(sql, SqlDialect.MYSQL);
    }

    @Test
    public void numericAndHostileTableNamesStayQuotedIdentifiers() {
        SqlParseOptions opt = SqlParseOptions.defaults()
                .placeholders(SqlPlaceholders.create().mybatis());
        String numeric = SQL.bindNamed("SELECT * FROM #{table}", SqlDialect.MYSQL, opt,
                Collections.<String, Object>singletonMap("table", Integer.valueOf(10086)));
        assertTrue(numeric, numeric.contains("`10086`"));
        SQL.parse(numeric, SqlDialect.MYSQL);

        String hostile = SQL.bindNamed("SELECT * FROM #{table}", SqlDialect.MYSQL, opt,
                Collections.<String, Object>singletonMap("table", "t; DROP TABLE x"));
        assertEquals(1, SQL.parseAll(hostile).size());
        assertTrue(hostile, hostile.contains("`"));
        SQL.parse(hostile, SqlDialect.MYSQL);
    }

    // ------------------------------------------------------------------
    // 场景 18：低代码查询沙箱——表白名单、WHERE 必含列、表数量上限
    // ------------------------------------------------------------------

    @Test
    public void sandboxAllowAndDenyTables() {
        SqlWallConfig cfg = SqlWallConfig.defaults()
                .allowTables("t_order", "t_item")
                .denyTables("mysql.user", "secret");
        assertTrue(SQL.wall("SELECT id FROM t_order o JOIN t_item i ON o.id = i.oid",
                SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT id FROM t_user", SqlDialect.MYSQL, cfg).violations()
                .contains("allow-table"));
        assertTrue(SQL.wall("SELECT * FROM secret", SqlDialect.MYSQL, cfg).violations()
                .contains("deny-table"));
    }

    @Test
    public void sandboxRequiresWhereColumnAndCapsJoinWidth() {
        SqlWallConfig cfg = SqlWallConfig.defaults()
                .requireWhereColumns("tenant_id")
                .maxTables(2);
        assertTrue(SQL.wall("SELECT id FROM t_order WHERE tenant_id = 1",
                SqlDialect.MYSQL, cfg).passed());
        assertTrue(SQL.wall("SELECT id FROM t_order", SqlDialect.MYSQL, cfg).violations()
                .contains("missing-where-column"));
        assertTrue(SQL.wall("SELECT * FROM a JOIN b ON a.id = b.id JOIN c ON b.id = c.id",
                SqlDialect.MYSQL, cfg).violations().contains("too-many-tables"));
    }
}
