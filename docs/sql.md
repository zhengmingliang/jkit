# SQL 解析模块

未完成项与给后续 agent 的开工顺序见 [next-plan.md](next-plan.md)（第 2 节是 SQL 主战场）。

`com.alianga:jkit-sql` 是零依赖的 SQL 解析器：手写词法（`char[]` + 关键字开地址哈希），递归下降生成 AST，支持格式化、表/列统计和改写。

设计上对标：

- **Druid SQL Parser**：手写解析、线程内复用 Parser、`SchemaStatVisitor` 式抽表列、生产环境吞吐
- **JSqlParser**：AST + Visitor、`TablesNamesFinder`、pretty/compact 回写

不执行 SQL，不引 JDBC 驱动。

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.1</version>
</dependency>
```

依赖 `com.alianga:jkit`（日志等），无其它第三方库。JDK 8+。

## 解析

```java
import com.alianga.jkit.sql.SQL;
import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlStatement;

SqlStatement stmt = SQL.parse(
        "SELECT u.id, b.name FROM users u "
      + "LEFT JOIN order_t b ON u.id = b.uid "
      + "WHERE u.age > 18");

stmt.type();            // SELECT
stmt.isReadOnly();      // true
SQL.tables(stmt);       // [users, order_t]

List<SqlStatement> batch = SQL.parseAll("SELECT 1; DELETE FROM t WHERE id=1");

// 容错多语句（审计）：失败条目为 SqlSimpleStatement，parseError() 有值，继续下一条
List<SqlStatement> audit = SQL.parseAll(sql, SqlDialect.MYSQL, true);

// 裸表达式（须消费完整输入；尾部垃圾抛 SqlParseException）
SqlExpr pred = SQL.parseExpr("tenant_id = ?");
SqlExpr fn = SQL.parseExpr("REPLACE(email, '@', '^-^')");
SqlExpr withOpt = SQL.parseExpr("age > @age@", SqlDialect.MYSQL,
        SqlParseOptions.defaults().placeholders(SqlPlaceholders.create().atWrapped()));
```

默认方言是 **MySQL**（GBase / MariaDB / TiDB 走同一套）。其它方言：

```java
SQL.parse(sql, SqlDialect.POSTGRES);
SQL.parse(sql, SqlDialect.ORACLE);    // 12c 以下：分页改写用 ROWNUM
SQL.parse(sql, SqlDialect.ORACLE12);  // 12c+：可用 OFFSET/FETCH
SQL.parse(sql, SqlDialect.SQLSERVER);
SQL.parse(sql, SqlDialect.ANSI);

SqlDialect.fromName("gbase");     // MYSQL
SqlDialect.fromName("gaussdb");   // POSTGRES
SqlDialect.fromName("dm");        // ORACLE（ROWNUM）
SqlDialect.fromName("oracle12");  // ORACLE12
SqlDialect.fromName("19c");       // ORACLE12
SqlDialect.fromName("tidb");      // MYSQL
SqlDialect.fromName("sqlite");    // ANSI

// 能力查询（改写/格式化单一事实来源）
SqlDialect.MYSQL.supportsLimitOffset();   // true
SqlDialect.SQLSERVER.supportsTop();       // true
SqlDialect.ORACLE.supportsFetchFirst();   // false（12c 以下）
SqlDialect.ORACLE12.supportsFetchFirst(); // true
SqlDialect.ORACLE.supportsRownum();       // true
SqlDialect.POSTGRES.pipesAreConcat();     // true
SqlDialect.MYSQL.quoteIdent("user");      // `user`
```

非法 SQL 抛 `SqlParseException`，带行号、列号和附近原文，不返回半棵树。

## 模板占位符（可选）

common-model 一类**模板 SQL**会用 `@age@`、`%s`、`<sheet>`、`<-sheet->` 等占位槽。默认解析**关闭**占位符（保持严格）；需要时通过 `SqlParseOptions.placeholders()` 显式打开：

```java
SqlParseOptions opt = SqlParseOptions.defaults()
        .placeholders(SqlPlaceholders.create()
                .atWrapped()    // @name@
                .printf()       // %s / %d / %f …
                .angle()        // <sheet>
                .arrowAngle()   // <-sheet->
                .add("{{*}}")); // 自定义：恰好一个 * 表示正文

SqlStatement stmt = SQL.parse(
        "select * from <20241230.1> where age > @age@ and name in (%s)",
        SqlDialect.MYSQL, opt);
```

也可用 `SqlPlaceholders.create().commonModelTemplates()` 一次打开上述四类内置预设。

规则摘要：

| 模式 | 含义 | 词法结果 |
|------|------|----------|
| `@*@` | 前后 `@` 包裹的标识体 | `IDENT`（可作列/值原子） |
| printf | `%` + 一个字母 | `IDENT` |
| `<*>` / `<-*->` | 表名占位（允许 `.` `-`） | `IDENT`（可作表名） |
| 自定义 `{{*}}` 等 | 非空前后缀 + 正文 | `IDENT` |

未配置时 `@age@` / `%s` / `<sheet>` 仍按原行为失败或拆成运算符。故意残缺的语句（如 `select * from`）即使开启占位符也会失败。

## 统计与改写


```java
SqlSchemaStat stat = SQL.stat(sql);
stat.tableNames();
stat.getColumns();
stat.getConditions();       // WHERE / JOIN ON / HAVING 紧凑片段
stat.getOrderByColumns();
stat.getGroupByColumns();
stat.getTables();           // Map<String, SqlTableAccess>，同表可 INSERT+SELECT

SqlStatement limited = SQL.addLimit(stmt, 100); // clone 后再补 LIMIT，不改原树
SQL.getLimit(stmt);                              // Long，来自 LIMIT/TOP
SQL.getOffset(stmt);
SqlStatement page = SQL.setPage(stmt, 2, 20, SqlDialect.MYSQL); // clone；offset=20
SQL.setLimit(stmt, 50, SqlDialect.POSTGRES);
SQL.setOffset(stmt, 10, SqlDialect.POSTGRES);
SqlStatement w = SQL.andWhere(stmt, "tenant_id = ?"); // 内部 parseExpr + clone 后再 AND WHERE
SqlStatement t2 = SQL.replaceTable(w, "users", "users_archive"); // clone
SqlStatement c2 = SQL.replaceColumn(t2, "name", "user_name");   // clone；跳过表名/表别名
SqlStatement copy = SQL.clone(stmt);
```

`addLimit`：已有 LIMIT/TOP 时不覆盖；SQL Server 写 `TOP`，其余写 `LIMIT`。
`andWhere` / `replaceTable` / `replaceColumn`：现与 `addLimit`/`setPage` 一样 **clone 后再改**（破坏性：旧代码若依赖就地修改需改用返回值）。
`setLimit` / `setOffset` / `setPage`：**替换**分页；`setPage(pageNo, pageSize)` 中 pageNo 从 1 起。
方言：MySQL/PG/H2/ANSI → `LIMIT`/`OFFSET`；SQL Server 第 1 页 `TOP`，其后 `OFFSET FETCH`；Oracle 裸 SELECT → `FETCH FIRST`（可带 `OFFSET`）。已存在的 Oracle `ROWNUM` 双层/`WHERE ROWNUM<=n` 与 SQL Server `row_number` 包装：`getLimit` 返回页大小，`setPage`/`setLimit` 只改数值边界（不叠 OFFSET/FETCH）。UNION 的 LIMIT 挂在集合运算链末端。

## 参数化 / Wall / 求值（P2）

```java
String finger = SQL.parameterize("SELECT * FROM t WHERE name = 'a' AND age = 1");
// SELECT * FROM t WHERE name = ? AND age = ?

List<Object> litValues = SQL.exportParameterValues(sql); // "a", 1 —— 不是 ?/:name
List<String> binds = SQL.parameters(sql);                 // "?", ":name"

SqlWallResult wall = SQL.wall(sql); // 默认不拦截解析；显式调用
wall.passed();
wall.violations(); // multi-statement / comment-bypass / always-true-condition / sleep-function / delete-without-where / update-without-where

Object v = SQL.eval(expr); // 仅字面量算术与比较；读列则 null

stmt.accept(new SqlAstVisitor() {
    @Override protected boolean visitSelect(SqlSelect node) { return true; }
});
```

## 格式化

`format` / `toSqlString` 是 AST 回写（不保留空白与注释）。**语义往返**（`parse → format → parse`）保证 `type()`、`tables()`（忽略大小写）、`isReadOnly()` 与原文一致；黄金集 `SqlGoldenCorpusTest` 全覆盖。

```java
SQL.format(stmt);                           // 换行缩进
SQL.toSqlString(stmt);                      // 紧凑单行
SQL.format(stmt, SqlDialect.MYSQL, true);
```

带引号的标识符按方言回写：MySQL 反引号、PostgreSQL/Oracle/ANSI/H2 双引号、SQL Server `[]`。
`||` 按 AST 回写（`CONCAT`→`||`，MySQL 默认解析出的 `OR`→`OR`）。

回写是 pretty-print，**不保证注释和空白 round-trip**。

## 方言差异

| 点 | MYSQL | POSTGRES / ANSI / ORACLE |
| --- | --- | --- |
| 标识符 | 反引号 `` ` `` | 双引号 |
| 双引号 | 默认当字符串 | 当标识符 |
| `\|\|` | 逻辑 OR（`SqlParseOptions.pipesAsConcat(true)` 可改为拼接） | 字符串拼接 |
| `#` 行注释 | 是 | 否 |
| 分页 | `LIMIT` / `LIMIT off,n`（`supportsLimitOffset`） | PG/ANSI/H2：LIMIT+FETCH；Oracle：FETCH/ROWNUM；SQL Server：TOP + OFFSET FETCH |
| `\|\|` 能力 | `pipesAsOr()` | `pipesAreConcat()` |

## 快速构建（SqlBuilder）

```java
String sql = SqlBuilder.select("id", "name")
        .distinct()
        .from("users", "u")
        .where("u.status = 1")
        .and("u.age > 18")
        .leftJoin("orders", "u.id = orders.uid")
        .rightJoin("depts", "u.dept = depts.id")
        .with("c", "SELECT id FROM t WHERE active = 1")
        .groupBy("u.id")
        .having("count(1) > 1")
        .orderBy("u.id")
        .limit(10)
        .unionAll(SqlBuilder.select("id", "name").from("archive"))
        .toSql();

SqlBuilder.insertInto("t").columns("id", "name").values(1, "a").toSql();
SqlBuilder.update("t").set("name", "b").where("id = 1").toSql();
SqlBuilder.deleteFrom("t").where("id = 1").toSql();

// AST 级拼接（无字符串黑客）
SQL.and(SqlBuilder.parsePredicate("a=1"), SqlBuilder.parsePredicate("b=2"));
SQL.concat(Arrays.asList(SQL.parse("SELECT 1"), SQL.parse("SELECT 2")));
SQL.builder().from("t").where("id = ?").limit(5).toSql();
```

构建结果是 AST，再经 `SQL.format` / `toSqlString` 回写。

## 参数抽取

```java
List<String> params = SQL.parameters("SELECT * FROM t WHERE id = ? AND name = :name");
// ["?", ":name"]  —— 绑定占位符

List<Object> literals = SQL.exportParameterValues("SELECT * FROM t WHERE name = 'a' AND age = 1");
// ["a", 1]  —— 字面量值（与 parameters 分立）
```

## v1 覆盖

- SELECT：列、`*`、`t.*`、DISTINCT / DISTINCT ON、TOP、`INTO` 表 / `@var` / `OUTFILE`（抽目标表进 `tables`/`INSERT`）、FROM（含 MySQL `PARTITION (p0,p1)` 表分区限定）、JOIN（INNER/LEFT/RIGHT/FULL/CROSS/NATURAL/STRAIGHT/逗号）、`CROSS APPLY` / `OUTER APPLY`、`LATERAL` 子查询/表函数、`UNNEST(...)` / `TABLE(fn(...))` / `OPENJSON(...) WITH (...)` 表函数、`(VALUES …) AS v(cols)`、ON/USING、WHERE、GROUP BY [WITH ROLLUP]、HAVING、`WINDOW … AS (…)`（可继承另一窗口名）、ORDER BY、LIMIT/OFFSET/`FETCH FIRST n ROWS ONLY`、FOR UPDATE [OF cols] [NOWAIT|SKIP LOCKED]、LOCK IN SHARE MODE、UNION/UNION ALL/INTERSECT/EXCEPT/MINUS、CONNECT BY / START WITH / PRIOR、WITH CTE
- 窗口函数：`OVER (PARTITION BY ... ORDER BY ... ROWS/RANGE BETWEEN ...)`、命名窗口引用 `OVER w`、SELECT 级 `WINDOW w AS (...)`（可多个；`w2 AS (w)` / `w2 AS (w ORDER BY …)` 继承）、`FILTER (WHERE ...)`
- 特殊函数：`EXTRACT(field FROM expr)`、`TRIM(BOTH/LEADING/TRAILING ... FROM expr)`、`SUBSTRING(expr FROM n FOR m)`、`POSITION(a IN b)`、`IF(a,b,c)`（MySQL）、`CONVERT(expr USING charset)` / `CONVERT(type, expr)`（SQL Server）、`GROUP_CONCAT(... ORDER BY ... SEPARATOR ...)`、`STRING_AGG(... ORDER BY ...)` / `WITHIN GROUP (ORDER BY ...)`、`MATCH (cols) AGAINST (...)`
- INSERT / REPLACE：列清单、VALUES 多行、INSERT SELECT、INSERT SET、ON DUPLICATE KEY UPDATE、PG `ON CONFLICT`（`DO NOTHING` / `DO UPDATE` / `ON CONSTRAINT`）、`RETURNING`（`*` 或多列列表）、SQL Server `OUTPUT` / `OUTPUT … INTO`、Oracle `INSERT ALL` / `INSERT FIRST`
- UPDATE / DELETE：JOIN、WHERE、ORDER BY、LIMIT、PG `UPDATE … FROM`、PG/MySQL `DELETE … USING`、`RETURNING`（多列）、SQL Server `OUTPUT` / `OUTPUT … INTO`（表 / `@var` / `#tmp`，进 `tables()`）
- MERGE：INTO / USING / ON、多个 `WHEN MATCHED [AND pred]`、`WHEN NOT MATCHED [BY TARGET|SOURCE]`、`OUTPUT` / `OUTPUT … INTO`
- DDL：CREATE/DROP/ALTER TABLE|VIEW|INDEX|DATABASE|PROCEDURE|FUNCTION|TRIGGER|EVENT（抽对象名；`CREATE OR REPLACE`；VIEW/CTAS 的 AS query；过程参数与 BEGIN…END 体进 tail；CREATE TABLE 列定义原文（`columnDefinitions`）+ ENGINE/CHARSET/COLLATE/COMMENT + 表级 FOREIGN KEY 引用表；ALTER ADD/DROP INDEX、RENAME TO、CHANGE/MODIFY 列定义、ADD CONSTRAINT）
- EXPLAIN / DESC、SET、USE、SHOW、CALL（实参进 AST）、TRUNCATE、GRANT / REVOKE（权限 + ON 对象名；收件人 `user@host` 紧凑回写；REVOKE 用 FROM）
- 过程块 / 维护 / 事务：`BEGIN … END` / 顶层匿名 `DECLARE … BEGIN … END` → `SqlBlockStatement`；会话式 `DECLARE x INT`（OTHER）；裸 `BEGIN` / `BEGIN WORK` / `START TRANSACTION` → `SqlStartTransactionStatement`（隔离级别 / READ WRITE|ONLY / WITH CONSISTENT SNAPSHOT）；`COMMIT` / `ROLLBACK [TO SAVEPOINT]` / `SAVEPOINT` / `RELEASE SAVEPOINT` → `SqlTransactionControlStatement`；`FLUSH …` → `SqlFlushStatement`（选项列表 / TABLES 表名）；`LOCK TABLES`/`UNLOCK TABLES` → `SqlLockTablesStatement`；`ANALYZE` / `VACUUM` / `OPTIMIZE|REPAIR|CHECK TABLE` → `SqlMaintenanceStatement`（tables + optionsRaw）；`COMMENT ON TABLE/COLUMN`；SQL Server `GO` 批分隔；PG `COPY … FROM|TO` → `SqlCopyStatement`（表/列/STDIN·PROGRAM·文件 + WITH 原文）；MySQL `LOAD DATA [LOCAL] INFILE … INTO TABLE` → `SqlLoadDataStatement`（文件/表/列 + FIELDS·LINES·IGNORE 原文）；MySQL 表 `HANDLER t OPEN|READ|CLOSE` → `SqlTableHandlerStatement`；`PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE` / `EXECUTE IMMEDIATE` → `SqlPrepareStatement`（名 / FROM·源 / USING）
- 表达式：字面量、绑定 `?` / `:name` / `@var`、算术比较、AND/OR/XOR/NOT、IN（含 `IN :name` / `IN ?` 无括号绑定列表）/BETWEEN/LIKE/ILIKE/REGEXP、IS NULL、`IS DISTINCT FROM` / `IS NOT DISTINCT FROM`、CASE、CAST / `::`、函数、EXISTS、子查询、`INTERVAL '1 day'` / `INTERVAL 1 DAY`、`X'FF'` / `0xFF`、行构造 `(a,b)`、JSON `->` `->>` `#>` `#>>`、数组下标 `arr[1]`、`= ANY/SOME/ALL (...)`；另含 PG `@>`/`<@`/`~`/`~*`、MySQL `FORCE INDEX FOR …`/`<=>`/`INSERT DELAYED`/`BINARY`、SQL Server `TOP WITH TIES`、`TABLESAMPLE`/`SAMPLE`、Oracle `(+)` 外连接后缀
- 注释：`--`、`/* */`、MySQL `#`；仅注释/空白的输入解析为 `OTHER` 空语句（不抛 empty SQL）；MySQL 可执行注释 `/*!40101 … */` 展开为内部 SQL（不整段丢弃）；优化器 hint `/*+ … */` 挂到 SELECT / 表并可 format 回写
- 标识符：MySQL 裸标识符允许数字开头（如 `32强国` / `1019使用`），整段不能只是数字；`32` / `32.5` / `32e1` / `0xFF` 仍为字面量；反引号形式原本即可
- 解析选项：`SqlParseOptions.keepComments(true)`（默认 false）时普通注释进入 `SqlStatement.comments()`，热路径默认仍丢弃；`SqlParseOptions.pipesAsConcat(true)` 让 MySQL 方言下 `||` 按拼接解析（等同 `PIPES_AS_CONCAT`）；`SqlParseOptions.placeholders()` 可配置模板占位（默认关闭，见「模板占位符」）；`SQL.parseAll(sql, dialect, true)` 容错多语句（失败占位 + `parseError`，供审计）

明确未做：过程体结构化执行、执行引擎、完整 Wall 规则集（仅提供 `SQL.wall` 子集）。CREATE TABLE 列类型/约束已进 `columnDefinitions` 并可 format 往返。未知函数按普通函数调用解析，不失败。

## 性能

手写词法 + `ThreadLocal` 复用 Parser。和 Druid / JSqlParser 的对比测试在上级目录 **`tools-test`**（不进本模块，以免引入第三方依赖）：

```text
cd ../tools-test
mvn -Dtest=SqlParserCompareTest test
```

`tools-test` 文件语料 `sql-corpus.txt`（约 **379** 条）上 **jkit 379/379（100%）**；内嵌 CORPUS（约 64 条）亦全绿。竞品缺口随样例变化（Druid 常见挂 `DISTINCT ON` / WINDOW 继承 / UNNEST；JSqlParser 常见挂 `LOCK IN SHARE MODE` / `[dbo].[user]` / WINDOW 继承）。

吞吐请以 **JMH** 为准（`tools-test` 的 `SqlParseBenchmark`，fork≥2）；墙钟 for 循环仅作数量级参考：jkit 与 Druid 同属手写档，明显快于 JavaCC 的 JSqlParser。

| | 解析成功率（文件语料） | 备注 |
| --- | --- | --- |
| **jkit-sql** | **379/379 (100%)** | 模块内黄金集约 216 条（含往返）；`mvn -pl jkit-sql test` 约 **647** 条 |
| Druid 1.2.23 | 低于 jkit（缺口见 `target/sql-compare-fail.txt`） | 对比不进本库依赖 |
| JSqlParser 4.9 | 低于 jkit | 同上 |

## 语料与对比

- 模块内：`SqlGoldenCorpusTest`（约 **216** 条，含往返）、`CommonModelSqlCorpusTest`（从 `icell/common-model` 收获，87 条可解析）。
- 与 Druid / JSqlParser 对比只在上级工程 `tools-test` 的 `SqlParserCompareTest`（成功率 + 表名集合差分 + JMH；不进本库依赖）。
