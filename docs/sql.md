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
```

默认方言是 **MySQL**（GBase / MariaDB / TiDB 走同一套）。其它方言：

```java
SQL.parse(sql, SqlDialect.POSTGRES);
SQL.parse(sql, SqlDialect.ORACLE);
SQL.parse(sql, SqlDialect.SQLSERVER);
SQL.parse(sql, SqlDialect.ANSI);

SqlDialect.fromName("gbase");     // MYSQL
SqlDialect.fromName("gaussdb");   // POSTGRES
SqlDialect.fromName("dm");        // ORACLE
SqlDialect.fromName("tidb");      // MYSQL
SqlDialect.fromName("sqlite");    // ANSI

// 能力查询（改写/格式化单一事实来源）
SqlDialect.MYSQL.supportsLimitOffset();   // true
SqlDialect.SQLSERVER.supportsTop();       // true
SqlDialect.ORACLE.supportsFetchFirst();   // true
SqlDialect.ORACLE.supportsRownum();       // true
SqlDialect.POSTGRES.pipesAreConcat();     // true
SqlDialect.MYSQL.quoteIdent("user");      // `user`
```

非法 SQL 抛 `SqlParseException`，带行号、列号和附近原文，不返回半棵树。

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
SQL.andWhere(stmt, "tenant_id = ?");            // AND 到顶层 WHERE（就地）
SQL.replaceTable(stmt, "users", "users_archive");
SQL.replaceColumn(stmt, "name", "user_name");   // 跳过表名/表别名
SqlStatement copy = SQL.clone(stmt);
```

`addLimit`：已有 LIMIT/TOP 时不覆盖；SQL Server 写 `TOP`，其余写 `LIMIT`。
`setLimit` / `setOffset` / `setPage`：**替换**分页；`setPage(pageNo, pageSize)` 中 pageNo 从 1 起。
方言：MySQL/PG/H2/ANSI → `LIMIT`/`OFFSET`；SQL Server 第 1 页 `TOP`，其后 `OFFSET FETCH`；Oracle → `FETCH FIRST`（可带 `OFFSET`）。

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

## 参数抽取

```java
List<String> params = SQL.parameters("SELECT * FROM t WHERE id = ? AND name = :name");
// ["?", ":name"]  —— 绑定占位符

List<Object> literals = SQL.exportParameterValues("SELECT * FROM t WHERE name = 'a' AND age = 1");
// ["a", 1]  —— 字面量值（与 parameters 分立）
```

## v1 覆盖

- SELECT：列、`*`、`t.*`、DISTINCT / DISTINCT ON、TOP、FROM、JOIN（INNER/LEFT/RIGHT/FULL/CROSS/NATURAL/STRAIGHT/逗号）、`CROSS APPLY` / `OUTER APPLY`、`LATERAL` 子查询/表函数、`UNNEST(...)` / `TABLE(fn(...))` 表函数、`(VALUES …) AS v(cols)`、ON/USING、WHERE、GROUP BY [WITH ROLLUP]、HAVING、`WINDOW … AS (…)`（可继承另一窗口名）、ORDER BY、LIMIT/OFFSET/`FETCH FIRST n ROWS ONLY`、FOR UPDATE [OF cols] [NOWAIT|SKIP LOCKED]、LOCK IN SHARE MODE、UNION/UNION ALL/INTERSECT/EXCEPT/MINUS、CONNECT BY / START WITH / PRIOR、WITH CTE
- 窗口函数：`OVER (PARTITION BY ... ORDER BY ... ROWS/RANGE BETWEEN ...)`、命名窗口引用 `OVER w`、SELECT 级 `WINDOW w AS (...)`（可多个；`w2 AS (w)` / `w2 AS (w ORDER BY …)` 继承）、`FILTER (WHERE ...)`
- 特殊函数：`EXTRACT(field FROM expr)`、`TRIM(BOTH/LEADING/TRAILING ... FROM expr)`、`SUBSTRING(expr FROM n FOR m)`、`POSITION(a IN b)`、`IF(a,b,c)`（MySQL）、`CONVERT(expr USING charset)` / `CONVERT(type, expr)`（SQL Server）、`GROUP_CONCAT(... ORDER BY ... SEPARATOR ...)`、`STRING_AGG(... ORDER BY ...)` / `WITHIN GROUP (ORDER BY ...)`、`MATCH (cols) AGAINST (...)`
- INSERT / REPLACE：列清单、VALUES 多行、INSERT SELECT、INSERT SET、ON DUPLICATE KEY UPDATE、PG `ON CONFLICT`（`DO NOTHING` / `DO UPDATE` / `ON CONSTRAINT`）、`RETURNING`（`*` 或多列列表）、SQL Server `OUTPUT`、Oracle `INSERT ALL` / `INSERT FIRST`
- UPDATE / DELETE：JOIN、WHERE、ORDER BY、LIMIT、PG `UPDATE … FROM`、PG/MySQL `DELETE … USING`、`RETURNING`（多列）、SQL Server `OUTPUT`
- MERGE：INTO / USING / ON、多个 `WHEN MATCHED [AND pred]`、`WHEN NOT MATCHED [BY TARGET|SOURCE]`、`OUTPUT`
- DDL：CREATE/DROP/ALTER TABLE|VIEW|INDEX|DATABASE|PROCEDURE|FUNCTION|TRIGGER|EVENT（抽对象名；`CREATE OR REPLACE`；VIEW/CTAS 的 AS query；过程参数与 BEGIN…END 体进 tail；CREATE TABLE ENGINE/CHARSET/COLLATE/COMMENT；ALTER ADD/DROP INDEX、RENAME TO）
- EXPLAIN / DESC、SET、USE、SHOW、CALL（实参进 AST）、TRUNCATE、GRANT
- 过程块 / 维护：`BEGIN … END`、`DECLARE`（OTHER）；`ANALYZE` / `VACUUM` / `OPTIMIZE|REPAIR|CHECK TABLE`；`COMMENT ON TABLE/COLUMN`；SQL Server `GO` 批分隔
- 表达式：字面量、绑定 `?` / `:name` / `@var`、算术比较、AND/OR/XOR/NOT、IN/BETWEEN/LIKE/ILIKE/REGEXP、IS NULL、`IS DISTINCT FROM` / `IS NOT DISTINCT FROM`、CASE、CAST / `::`、函数、EXISTS、子查询、`INTERVAL '1 day'` / `INTERVAL 1 DAY`、`X'FF'` / `0xFF`、行构造 `(a,b)`、JSON `->` `->>` `#>` `#>>`、数组下标 `arr[1]`、`= ANY/SOME/ALL (...)`
- 注释：`--`、`/* */`、MySQL `#`；MySQL 可执行注释 `/*!40101 … */` 展开为内部 SQL（不整段丢弃）；优化器 hint `/*+ … */` 挂到 SELECT / 表并可 format 回写
- 解析选项：`SqlParseOptions.keepComments(true)`（默认 false）时普通注释进入 `SqlStatement.comments()`，热路径默认仍丢弃；`SqlParseOptions.pipesAsConcat(true)` 让 MySQL 方言下 `||` 按拼接解析（等同 `PIPES_AS_CONCAT`）；`SQL.parseAll(sql, dialect, true)` 容错多语句（失败占位 + `parseError`，供审计）

明确未做：Oracle `(+)` 外连接、MySQL `PARTITION (p0,p1)` 表分区限定、过程体结构化执行、ALTER CHANGE/CONSTRAINT 全量建模、执行引擎、完整 Wall 规则集（仅提供 `SQL.wall` 子集）。未知函数按普通函数调用解析，不失败。

## 性能

手写词法 + `ThreadLocal` 复用 Parser。和 Druid / JSqlParser 的对比测试在上级目录 **`tools-test`**（不进本模块，以免引入第三方依赖）：

```text
cd ../tools-test
mvn -Dtest=SqlParserCompareTest test
```

本机一次实测（37 条对标 SQL，JDK 17）：

| | 解析成功率 | simple ns/op | join ns/op | window ns/op |
| --- | --- | --- | --- | --- |
| **jkit-sql** | **37/37 (100%)** | 1292 | 1791 | 763 |
| Druid 1.2.23 | 36/37 (97%) | 5185 | 5905 | 4537 |
| JSqlParser 4.9 | 35/37 (95%) | 208135 | 238283 | 296810 |

Druid 在 `DISTINCT ON` 上失败；JSqlParser 在 `LOCK IN SHARE MODE` 和 `[dbo].[user]` 上失败。吞吐是 warmup 后 2 万次的墙钟，不是 JMH，数量级可信：jkit 与 Druid 同属手写档，明显快于 JavaCC 的 JSqlParser。

## 语料与对比

- 模块内：`SqlGoldenCorpusTest`（约 165 条，含往返）、`CommonModelSqlCorpusTest`（从 `icell/common-model` 收获，87 条可解析）。
- 与 Druid / JSqlParser 对比只在上级工程 `tools-test` 的 `SqlParserCompareTest`（不进本库依赖）。
