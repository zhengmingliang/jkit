# SQL 解析模块

未完成项与给后续 agent 的开工顺序见 [next-plan.md](https://github.com/zhengmingliang/jkit/blob/develop/docs/next-plan.md)（第 2 节是 SQL 主战场）。

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

`tables()` 只收**真实被访问的表**：DML/SELECT 的 FROM/JOIN/INTO；DDL 按对象类型区分——`DROP/ALTER/TRUNCATE/RENAME TABLE` 的表、`CREATE TABLE t2 LIKE 源表`、`CREATE TRIGGER ... ON t`、维护语句（`OPTIMIZE/ANALYZE/CHECK/REPAIR TABLE t, s`）的全组表；VIEW 名按既有语义计入。**不**计入：库名（`USE db`）、例程/索引/事件等对象名（`CREATE INDEX idx`）、CTE 名（`WITH w AS (...) SELECT FROM w`）、`CALL sp` / `DECLARE` / `GRANT` 的目标。

默认方言是 **MySQL**（GBase / MariaDB / TiDB 走同一套）。一等方言枚举：

```java
SQL.parse(sql, SqlDialect.POSTGRES);
SQL.parse(sql, SqlDialect.ORACLE);    // 12c 以下：分页改写用 ROWNUM
SQL.parse(sql, SqlDialect.ORACLE12);  // 12c+：可用 OFFSET/FETCH
SQL.parse(sql, SqlDialect.SQLSERVER);
SQL.parse(sql, SqlDialect.ANSI);
SQL.parse(sql, SqlDialect.H2);
SQL.parse(sql, SqlDialect.DB2);         // 仅 FETCH FIRST 分页
SQL.parse(sql, SqlDialect.SQLITE);      // LIMIT 族，无 FETCH FIRST
SQL.parse(sql, SqlDialect.HIVE);        // 反引号、|| 拼接；别名 maxcompute/odps
SQL.parse(sql, SqlDialect.CLICKHOUSE);  // 反引号、双引号也是标识符、逗号 LIMIT
SQL.parse(sql, SqlDialect.PRESTO);      // 双引号、|| 拼接；别名 trino

SqlDialect.fromName("gbase");     // MYSQL
SqlDialect.fromName("gaussdb");   // POSTGRES
SqlDialect.fromName("dm");        // ORACLE（ROWNUM）
SqlDialect.fromName("oracle12");  // ORACLE12
SqlDialect.fromName("19c");       // ORACLE12
SqlDialect.fromName("tidb");      // MYSQL
SqlDialect.fromName("sqlite");    // SQLITE
SqlDialect.fromName("db2");       // DB2
SqlDialect.fromName("hive");      // HIVE
SqlDialect.fromName("clickhouse");// CLICKHOUSE
SqlDialect.fromName("trino");     // PRESTO
// 国产与主流别名：goldendb/selectdb/analyticdb/matrixone/stonedb/oceanbase/polardb/tdsql/starrocks/doris → MYSQL；
// highgo/uxdb/mogdb/vastbase/antdb/ivorysql/kingbase/opengauss/greenplum → POSTGRES；dm/oscar → ORACLE
// common-model（icell）数据源对齐：argo/argodb → HIVE（Transwarp Hive JDBC）、xcloud → POSTGRES（行云）、
// gbase8a → MYSQL、gbase8s → SQLITE（双引号、LIMIT、无 FETCH）

// 能力查询（改写/格式化单一事实来源）
SqlDialect.MYSQL.supportsLimitOffset();   // true
SqlDialect.SQLSERVER.supportsTop();       // true
SqlDialect.ORACLE.supportsFetchFirst();   // false（12c 以下）
SqlDialect.ORACLE12.supportsFetchFirst(); // true
SqlDialect.ORACLE.supportsRownum();       // true
SqlDialect.POSTGRES.pipesAreConcat();     // true
SqlDialect.MYSQL.quoteIdent("user");      // `user`
```

内置方言不够用时，用 `SqlDialectWrapper` 基于某个方言微调个别能力（`SQL.parse*` / `format` / `setPage` / `wall` 等所有方言参数都接受 `SqlDialectSpec`）：

```java
// MySQL + ANSI_QUOTES：双引号是标识符不是字符串
SqlDialectSpec ansiQuotes = new SqlDialectWrapper(SqlDialect.MYSQL) {
    @Override
    public boolean doubleQuoteIsString() {
        return false;
    }
};
SQL.parse("SELECT \"id\" FROM t", ansiQuotes);  // "id" 按标识符解析
```

可覆写的能力覆盖解析到改写全链路：引号（`identQuoteOpen/Close`）、`||` 语义（`pipesAsOr`）、反斜杠转义（`backslashEscapes`）、方括号标识符（`bracketIdentifiers`）、`~` 正则（`supportsTildeRegex`）、`#` 注释（`hashLineComment`）、分页形态（`supportsLimitOffset/Top/FetchFirst/Rownum/CommaLimitOffset`）。直接实现 `SqlDialectSpec` 时未覆写的方法按 ANSI 基线取默认值。

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
| 自定义 <code v-pre>{{*}}</code> 等 | 非空前后缀 + 正文 | `IDENT` |

未配置时 `@age@` / `%s` / `<sheet>` 仍按原行为失败或拆成运算符。故意残缺的语句（如 `select * from`）即使开启占位符也会失败。

## 自定义语句解析器（SPI，可选）

内建分派未覆盖的语句（前导关键字不在 SELECT / INSERT / CREATE 等内建清单里，如 `BACKUP …` / `SIGNAL …`）默认抛 `unsupported statement`。需要接住这类语句时，通过 `SqlParseOptions.statementParsers()` 按前导关键字注册 `SqlStatementParser`（不区分大小写，仅兜内建未覆盖的关键字——注册 `SELECT` 不会覆盖内建解析）：

```java
SqlStatementParsers registry = SqlStatementParsers.create()
        .add("BACKUP", ctx -> {
            ctx.next(); // 消费 BACKUP 关键字
            String rest = ctx.consumeRest().trim(); // 剩余原文（保留原始间距）
            SqlSimpleStatement stmt = new SqlSimpleStatement();
            stmt.setText(rest.isEmpty() ? "BACKUP" : "BACKUP " + rest);
            return stmt;
        });

SqlParseOptions opt = SqlParseOptions.defaults().statementParsers(registry);
SqlStatement stmt = SQL.parse("BACKUP DATABASE shop TO DISK='/tmp/shop.bak'",
        SqlDialect.MYSQL, opt);
```

`SqlParseContext` 提供游标操作子集：`token()` / `dialect()` / `is(type)` / `isIdent(word)` / `match(type)` / `matchIdent(word)` / `next()` / `name()` / `atStmtBreak()` / `consumeRest()` / `error(message)`。实现约定：

- 进入 `parse` 时当前记号即注册关键字；实现负责把语句消费到 `atStmtBreak()`（终止符留给框架），留下未消费记号会得到带位置的明确错误。
- 返回 `null` 视为解析失败；抛出的 `SqlParseException` 在容错 `parseAll(..., true)` 下转为失败占位并继续。
- 同关键字后注册覆盖先注册；`SQL.parse` / `parseAll` / `parseExpr` 的 options 重载均生效。

## DELIMITER（批处理终止符）

MySQL 客户端的 `DELIMITER ;;` / `DELIMITER $` / `DELIMITER //` 等会解析为 `SqlSimpleStatement.OTHER`，并**切换**后续 `parseAll` / 过程体尾部的批处理终止符（默认 `;`）。`//` 与除法同形时，在语句终止处不再当二元运算符。过程体内部语句分隔仍用 `;`，不受客户端 DELIMITER 影响。

```java
List<SqlStatement> batch = SQL.parseAll(
        "DELIMITER ;;\n"
      + "CREATE PROCEDURE p() BEGIN SELECT 1; END;;\n"
      + "DELIMITER ;\n"
      + "CALL p()",
        SqlDialect.MYSQL);
```

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
SqlStatement c3 = SQL.addSelectItem(c2, "status");              // clone；追加 SELECT 列
SqlStatement c4 = SQL.removeSelectItem(c3, "name");             // clone；按简单列名移除（不可删光）
SqlStatement c5 = SQL.adaptPagination(c4, SqlDialect.ORACLE);   // clone；按方言适配分页
SqlStatement copy = SQL.clone(stmt); // AST 树拷贝（SqlAstCloner），不再 format→parse
```

`addLimit`：已有 LIMIT/TOP 时不覆盖；SQL Server 写 `TOP`，其余写 `LIMIT`。
`andWhere` / `replaceTable` / `replaceColumn` / `addSelectItem` / `removeSelectItem` / `adaptPagination`：现与 `addLimit`/`setPage` 一样 **clone 后再改**（破坏性：旧代码若依赖就地修改需改用返回值）。
`setLimit` / `setOffset` / `setPage`：**替换**分页；`setPage(pageNo, pageSize)` 中 pageNo 从 1 起。

### 改写规则链（可选）

多个改写（自定义 + 内建）需要按序组合时，用 `SqlRewrites` 组链、`SQL.rewrite` 执行——
自定义规则排在内建适配器之前即"前 hook"、之后即"后 hook"，`SQL.rewrite` 先深拷贝，原 AST 不受影响：

```java
SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
        .add(new TenantRule())                            // 前 hook：自定义规则
        .add(SqlRewrites.replaceTable("users", "users_2026")) // 内建适配器
        .add(SqlRewrites.andWhere(SQL.parseExpr("tenant_id = ?"))) // 内建适配器
        .add(SqlRewrites.addLimit(100, SqlDialect.MYSQL))); // 后 hook 位置随意
```

规则是 `SqlRewriteHook` 函数式接口：收当前语句、返回继续传递的语句（就地修改返回原对象、或整体替换均可；返回 `null` 抛 `IllegalArgumentException`）。
内建适配器与 `SqlRewriter` 对应静态方法等价（`addLimit`/`setLimit`/`setOffset`/`setPage`/`andWhere`/`replaceTable`/`replaceColumn`/`addSelectItem`/`removeSelectItem`/`adaptPagination`），就地作用于链上语句；
只改一条且要"clone 后再改"语义时直接用 `SQL` 的对应门面方法即可，不必进链。
方言：MySQL/PG/H2/ANSI → `LIMIT`/`OFFSET`；SQL Server 第 1 页 `TOP`，其后 `OFFSET FETCH`；**`SqlDialect.ORACLE`（12c 以下）** 裸 SELECT → **ROWNUM 包装**（单层 `WHERE ROWNUM<=n`，有 offset 时双层）；**`ORACLE12`（12c+）** → `OFFSET … FETCH FIRST … ROWS ONLY`。已存在的 Oracle `ROWNUM` 双层/`WHERE ROWNUM<=n` 与 SQL Server `row_number` 包装：`getLimit` 返回页大小，`setPage`/`setLimit` 只改数值边界（不叠 OFFSET/FETCH）。UNION 的 LIMIT 挂在集合运算链末端。`SqlBuilder.limit`/`offset`/`toSql(dialect)` 走同一套改写（`toSql` 的方言参数覆盖 builder 方言）。

**`format` / `toSqlString(..., dialect)` 按目标方言适配分页**：若 AST 上已有分页（MySQL `LIMIT` / TOP / ROWNUM / row_number / FETCH）与目标方言形态不兼容（含 MySQL 逗号 `LIMIT` → PG/ANSI 等），回写前仅在 `paginationNeedsAdapt` 为 true 时 `SQL.clone` 再 `adaptPagination`（同形态零额外开销；不改入参 AST）。例如 MySQL `LIMIT 0,10000` → 经典 ORACLE 单层 ROWNUM；`LIMIT 10,20` → 双层 RN；→ ORACLE12/DB2 用 OFFSET/FETCH；→ SQLSERVER offset=0 用 TOP、有 offset 用 OFFSET FETCH；ROWNUM → POSTGRES/MYSQL 还原为 `LIMIT`（offset=0 可省略 OFFSET，不保留逗号风格）。显式 `adaptPagination` 与 format 自动路径共用 `isPaginationFormCompatible`。`setPage`/`setLimit` 转经典 ORACLE 时也会清掉子查询内残留的旧 LIMIT/TOP。

## 参数化 / Wall / 求值（P2）

```java
String finger = SQL.parameterize("SELECT * FROM t WHERE name = 'a' AND age = 1");
// SELECT * FROM t WHERE name = ? AND age = ?

List<Object> litValues = SQL.exportParameterValues(sql); // "a", 1 —— 不是 ?/:name
List<String> binds = SQL.parameters(sql);                 // "?", ":name"

SqlWallResult wall = SQL.wall(sql); // 默认不拦截解析；显式调用
wall.passed();
wall.violations(); // multi-statement / comment-bypass / always-true-condition / sleep-function / delete-without-where / update-without-where

// 可配置规则（SqlWallConfig）；defaults() 打开安全关键项，denyUnion / denyInformationSchema / selectOnly 默认关
SqlWallConfig cfg = SqlWallConfig.defaults()
        .denyDdl(true)
        .denyDangerousFunctions(true)  // SLEEP / BENCHMARK / LOAD_FILE …
        .denyIntoOutfile(true)
        .selectOnly(false);
SqlWallResult w2 = SQL.wall(sql, SqlDialect.MYSQL, cfg);

// 自定义规则（SqlWallRule SPI）：在全部内置检查之后、按注册顺序执行，违规码自动去重
cfg.rules((statement, config, violations) -> {
    if (statement.type() != SqlStatementType.SELECT) {
        violations.add("non-select");
    }
});
SqlWallResult w3 = SQL.wall(sql, SqlDialect.MYSQL, cfg); // violations 可同时含内置码与 non-select

Object v = SQL.eval(expr); // 仅字面量算术与比较；读列则 null

stmt.accept(new SqlAstVisitor() {
    @Override protected boolean visitSelect(SqlSelect node) { return true; }
});
```

## 格式化

`format` / `toSqlString` 是 AST 回写（不保留空白与注释）；指定目标方言时若分页形态不兼容会先适配再回写（见上「统计与改写」）。**语义往返**（`parse → format → parse`）保证 `type()`、`tables()`（忽略大小写）、`isReadOnly()` 与原文一致；黄金集 `SqlGoldenCorpusTest` 全覆盖。**词级保真**由 `SqlRoundTripFidelityTest` 额外保证：回写文本与原文**归一化后逐字等价**（去注释 / 去全部空白 / 去独立 `AS` / 统一大小写，只容忍纯排版差异），覆盖 JOIN 修饰符、DDL 关键字、`RENAME` 多组、引号形态、DML 修饰符、JDBC 转义等 118 条坑位语料——回写**丢词**（如 `NATURAL LEFT JOIN` 丢 `LEFT`、`STRAIGHT_JOIN` 丢 `STRAIGHT`）会直接抓出，不会静默通过。同一方法在 `tools-test` 由 `SqlRoundTripFidelityCorpusTest` 批量应用到全部 379 条文件语料（另加语义等价写法归一与 5 条有据白名单，硬断言）。

```java
SQL.format(stmt);                           // 换行缩进
SQL.toSqlString(stmt);                      // 紧凑单行
SQL.format(stmt, SqlDialect.MYSQL, true);

// 强制给每个标识符段加方言引号（默认 false；不影响字面量/关键字/*/函数名）
SqlFormatOptions opts = SqlFormatOptions.defaults().quoteIdentifiers(true);
SQL.format(stmt, SqlDialect.MYSQL, opts);
SQL.toSqlString(stmt, SqlDialect.POSTGRES, opts);

// 关键字大小写策略（默认 AS_IS = formatter 原生输出，子句/运算符关键字大写）
SQL.format(stmt, SqlDialect.MYSQL, false,
        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)); // select ... from ... where ...
```

`keywordCase` 覆盖子句、DDL、事务控制与表达式运算符关键字（AND / OR / NOT / LIKE / IN / BETWEEN…）；不影响标识符、字符串字面量与 raw 直通原文（过程体等保真回写）。可与 `quoteIdentifiers` 叠加。

原文已带引号的标识符按方言回写：MySQL 反引号、PostgreSQL/Oracle/ANSI/H2 双引号、SQL Server `[]`。
开启 `quoteIdentifiers` 后，**未引号**的表/列名也会强制加同套引号。
`||` 按 AST 回写（`CONCAT`→`||`，MySQL 默认解析出的 `OR`→`OR`）。

回写是 pretty-print，**不保证注释和空白 round-trip**。

## 方言差异

| 点 | MYSQL | POSTGRES / ANSI / ORACLE |
| --- | --- | --- |
| 标识符 | 反引号 `` ` `` | 双引号 |
| 双引号 | 默认当字符串 | 当标识符 |
| `\|\|` | 逻辑 OR（`SqlParseOptions.pipesAsConcat(true)` 可改为拼接） | 字符串拼接 |
| `#` 行注释 | 是 | 否 |
| 分页 | `LIMIT` / `LIMIT off,n`（`supportsLimitOffset`） | PG/ANSI/H2：LIMIT+FETCH；**ORACLE**：ROWNUM；**ORACLE12**：OFFSET/FETCH；SQL Server：TOP + OFFSET FETCH |
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

// 分页按有效方言生成：ORACLE→ROWNUM，ORACLE12/SQLSERVER→OFFSET/FETCH，MySQL→LIMIT
SqlBuilder.select("*").from("t").limit(10).offset(20).toSql(SqlDialect.ORACLE);
SqlBuilder.select("*").from("t").limit(10).offset(20).toSql(SqlDialect.ORACLE12);

// 强制标识符引号（可开可关；默认关）
SqlBuilder.select("id", "name").from("users").quoteIdentifiers(true).toSql();

SqlBuilder.insertInto("t").columns("id", "name").values(1, "a").toSql();
SqlBuilder.update("t").set("name", "b").where("id = 1").toSql();
SqlBuilder.deleteFrom("t").where("id = 1").toSql();

// AST 级拼接（无字符串黑客）
SQL.and(SqlBuilder.parsePredicate("a=1"), SqlBuilder.parsePredicate("b=2"));
SQL.concat(Arrays.asList(SQL.parse("SELECT 1"), SQL.parse("SELECT 2")));
SQL.builder().from("t").where("id = ?").limit(5).toSql();
```

构建结果是 AST，再经 `SQL.format` / `toSqlString` 回写。`toSql(dialect)` 的方言参数覆盖 builder 自身方言，并决定分页形态。

## 参数抽取

```java
List<String> params = SQL.parameters("SELECT * FROM t WHERE id = ? AND name = :name");
// ["?", ":name"]  —— 绑定占位符

List<Object> literals = SQL.exportParameterValues("SELECT * FROM t WHERE name = 'a' AND age = 1");
// ["a", 1]  —— 字面量值（与 parameters 分立）
```


## 别名 API 注意

SELECT 列表项与表源的别名用 **`alias()`** 读取：

- `SqlSelectItem.alias()` — 列别名（含点号限定如 `AS a.b`）
- `SqlTableSource.alias()` — 表/子查询/表函数别名

不要用 `SqlIdentifier.names()` 的下标去当「第几个别名」；`names()` 是限定名各段（`db.schema.table`），与别名无关。

## v1 覆盖

- SELECT：列、`*`、`t.*`、DISTINCT / DISTINCTROW / DISTINCT ON、HIGH_PRIORITY / STRAIGHT_JOIN 修饰符 / SQL_SMALL_RESULT / SQL_BIG_RESULT / SQL_BUFFER_RESULT / SQL_CACHE / SQL_NO_CACHE / SQL_CALC_FOUND_ROWS、TOP、`INTO` 表 / `@var` / 多变量 `INTO c,d` / `INTO (c,d)` / `OUTFILE`（抽目标表进 `tables`/`INSERT`）、ODPS `FORCE PARTITION / `SIGNED INTEGER` / `IGNORE NULLS` / `LIMIT BY` / jsonb `?` 'pt'` / `FORCE ALL PARTITIONS`、FROM（含 MySQL `PARTITION (p0,p1)` 表分区限定；Oracle `PARTITION BY (expr)` 分区外连接；别名后亦可 `FORCE/USE/IGNORE INDEX`）、JOIN（INNER/LEFT/RIGHT/FULL/CROSS/STRAIGHT/逗号；`LEFT|RIGHT ANTI|SEMI JOIN`；`NATURAL` 与连接类型**正交**）、SQL Server 表提示 `WITH (NOLOCK)` / `WITH (INDEX(ix))`（别名前后均可，原文保留）、`CROSS APPLY` / `OUTER APPLY`、`LATERAL` 子查询/表函数、`UNNEST(...) [WITH ORDINALITY]` / `TABLE(fn(...))` / `TABLE(SELECT…)` / `OPENJSON(...) WITH (...)` 表函数、`PIVOT` / `UNPIVOT [INCLUDE|EXCLUDE NULLS]`（含 `((SELECT…) PIVOT/UNPIVOT …)` 括号表源）、`(VALUES …) AS v(cols)`、括号集合运算子查询 `((SELECT…) UNION …)`、ON/USING、WHERE、GROUP BY [WITH ROLLUP|WITH CUBE|DISTINCT|GROUPING SETS（可多个逗号连接）]、HAVING、Teradata/Snowflake `QUALIFY` 窗口过滤、`WINDOW … AS (…)`（可继承另一窗口名）、ORDER BY、LIMIT/OFFSET/`FETCH FIRST n ROWS ONLY`、FOR UPDATE [OF cols] [NOWAIT|SKIP LOCKED]、LOCK IN SHARE MODE、UNION/UNION ALL/INTERSECT/EXCEPT/MINUS、CONNECT BY [NOCYCLE] / START WITH / PRIOR / `CONNECT_BY_ROOT`、WITH CTE（含 Oracle `SEARCH DEPTH|BREADTH FIRST BY … SET` / `CYCLE …`）；Hive UDTF 多列别名 `fn(...) AS (c0,c1)`
- Oracle / 时态：`MODEL` → `SqlModelClause`（PARTITION/DIMENSION/MEASURES/RULES；`RULES UPSERT SEQUENTIAL ORDER`；MEASURES 字面量/`AS` 别名；可位于 WHERE 后；`RULES` → `SqlModelRule`，`cellDims`/`cellDimExprs`，失败保留 `raw`）；`MATCH_RECOGNIZE` → `SqlMatchRecognize`（`PARTITION BY`/`ORDER BY`/`MEASURES`/`PATTERN` 字符串/`DEFINE`/`SUBSET`/`WITHIN`，`ROWS PER MATCH`/`AFTER MATCH` 字段；`PATTERN` 未建 DSL 树）；表级 `AS OF TIMESTAMP|SCN`、`VERSIONS BETWEEN TIMESTAMP|SCN … AND …`、SQL Server `FOR SYSTEM_TIME AS OF`；ClickHouse 参数化函数 `fn(params)(args)`
- 窗口函数：`OVER (PARTITION BY ... ORDER BY ... ROWS/RANGE BETWEEN ...)`、命名窗口引用 `OVER w`、SELECT 级 `WINDOW w AS (...)`（可多个；`w2 AS (w)` / `w2 AS (w ORDER BY …)` 继承）、`FILTER (WHERE ...)`、Spark `OVER (DISTRIBUTE BY … SORT BY …)`（`SqlOverExpr.sparkStyle`）
- 特殊函数：`EXTRACT(field FROM expr)`、`TRIM(BOTH/LEADING/TRAILING ... FROM expr)`、`SUBSTRING(expr FROM n FOR m)`、`POSITION(a IN b)`、`IF(a,b,c)`（MySQL）、`CONVERT(expr USING charset)` / `CONVERT(type, expr)`（SQL Server）、`GROUP_CONCAT(... ORDER BY ... SEPARATOR ...)`、`STRING_AGG(... ORDER BY ...)` / `WITHIN GROUP (ORDER BY ...)`、`MATCH (cols) AGAINST (...)`（含 `WITH QUERY EXPANSION`）、`WEIGHT_STRING(… AS CHAR(n) LEVEL n [DESC])`（MySQL 8）、SQL/JSON 构造器（`json_object`/`json_array`/`json_objectagg`/`json_arrayagg`/`json_table`，key:value / KEY…VALUE / ON NULL / UNIQUE KEYS / FORMAT JSON / COLUMNS…PATH 专用文法整体保留）、XML 系函数（`XMLSERIALIZE`/`XMLPARSE`/`XMLROOT`/`XMLAGG`/`XMLELEMENT`/`XMLFOREST`/`EXTRACTVALUE`）、`TRANSLATE(… USING CHAR_CS)`
- INSERT / REPLACE：列清单、VALUES 多行、INSERT SELECT、`INSERT … (WITH … SELECT …)`、INSERT SET、ON DUPLICATE KEY UPDATE、PG `ON CONFLICT`（`DO NOTHING` / `DO UPDATE` / `ON CONSTRAINT`）、`RETURNING`（`*` 或多列列表）、SQL Server `OUTPUT` / `OUTPUT … INTO`、Oracle `INSERT ALL` / `INSERT FIRST`；MySQL `LOW_PRIORITY` / `DELAYED` / `HIGH_PRIORITY` / `IGNORE` 可叠加并回写（REPLACE 亦支持 DELAYED）；Hive `INSERT OVERWRITE [TABLE] t [PARTITION (...)] SELECT …`（`SqlInsert.overwrite`/`tableKeyword`/`partitionRaw`）；ODPS `UPDATE/DELETE FORCE PARTITION …`
- UPDATE / DELETE：JOIN、WHERE、ORDER BY、LIMIT、PG `UPDATE … FROM`、PG/MySQL `DELETE … USING`、`RETURNING`（多列）、SQL Server `OUTPUT` / `OUTPUT … INTO`（表 / `@var` / `#tmp`，进 `tables()`）；MySQL `LOW_PRIORITY` / `QUICK` / `IGNORE` 修饰符保留并回写；MySQL 多表删除第二形式 `DELETE FROM a1, a2 USING …`（`SqlDelete.targets`）
- MERGE：INTO / USING / ON、多个 `WHEN MATCHED [AND pred]`、`WHEN NOT MATCHED [BY TARGET|SOURCE]`、`UPDATE … DELETE WHERE`、`INSERT … VALUES … WHERE`、`OUTPUT` / `OUTPUT … INTO`
- DDL：CREATE/DROP/ALTER TABLE|VIEW|INDEX|DATABASE|PROCEDURE|FUNCTION|TRIGGER|EVENT|USER（抽对象名；`CREATE OR REPLACE`；MySQL `ALGORITHM`/`DEFINER`/`SQL SECURITY`；VIEW/CTAS 的 AS query；过程/函数参数 → `SqlRoutineParam`，`FUNCTION RETURNS` → `returnsType`，BEGIN 体 → `bodyStatements`（保留 `bodyRaw`/`tail` 往返）；CREATE TABLE 列定义原文（`columnDefinitions`）+ ENGINE/CHARSET/COLLATE/COMMENT + 表级 FOREIGN KEY 引用表；`CREATE TABLE t2 LIKE t1` 抽源表进 `tables()`；ALTER ADD/DROP INDEX（含 `ADD UNIQUE KEY|INDEX` 保留 `UNIQUE`）、`DROP INDEX idx ON t`、RENAME TO、CHANGE/MODIFY 列定义、ADD CONSTRAINT）；独立语句 `RENAME TABLE a TO b[, c TO d]`（多组完整回写）；`CREATE/DROP USER 'u'@'%'` 账号原文保留（`userSpec`）；`CREATE TYPE … AS OBJECT/VARRAY/ENUM` 原文保留；`DROP … PURGE` / `DROP TABLESPACE … ENGINE`、`TRUNCATE … PURGE SNAPSHOT LOG` 尾段原文；MySQL 8 函数索引 `ADD KEY idx ((expr))`；CTAS 尾缀 `WITH [NO] DATA`
- `EXPLAIN`/`DESCRIBE` → `SqlExplainStatement`（ANALYZE/FORMAT/BUFFERS 等选项 + 嵌套 statement）、`SET` → `SqlSetStatement`（多赋值 / NAMES / CHARACTER SET / SESSION|GLOBAL）、USE、SHOW、CALL（实参进 AST）、TRUNCATE、GRANT / REVOKE（权限 + ON 对象名；收件人 `user@host` 紧凑回写；REVOKE 用 FROM）
- 过程块 / 维护 / 事务：`BEGIN … END` / 顶层匿名 `DECLARE … BEGIN … END` → `SqlBlockStatement`（支持 `EXCEPTION WHEN`、标签 `lab: BEGIN…END lab`）；PostgreSQL `DO $$…$$` / `DO $tag$…$tag$`；`IF…ELSIF/ELSEIF…END IF`；会话式 `DECLARE x INT`（OTHER）；过程体内 `DECLARE`/`CURSOR FOR` → `SqlDeclareStatement`，`CONTINUE|EXIT|UNDO HANDLER` → `SqlHandlerStatement`；`IF`/`WHILE`/`LOOP`/`REPEAT`/`CASE…END CASE`/`LEAVE`/`ITERATE`/`RETURN` → `SqlControlStatement`（可带循环标签）；`TRIGGER` 抽 `triggerTiming`/`triggerEvent`/`triggerTable`/`triggerUpdateColumns`/`FOR EACH`/`FOLLOWS|PRECEDES`；`EVENT` 抽 `ON SCHEDULE AT|EVERY`、`eventStarts`/`eventEnds`/`eventEnabled`/`eventComment`/`eventOnCompletion`/`eventDisableOnSlave`；裸 `BEGIN` / `BEGIN WORK` / `START TRANSACTION` → `SqlStartTransactionStatement`（隔离级别 / READ WRITE|ONLY / WITH CONSISTENT SNAPSHOT）；`COMMIT` / `ROLLBACK [TO SAVEPOINT]` / `SAVEPOINT` / `RELEASE SAVEPOINT` → `SqlTransactionControlStatement`；`FLUSH …` → `SqlFlushStatement`（选项列表 / TABLES 表名）；`LOCK TABLES`/`UNLOCK TABLES` → `SqlLockTablesStatement`；`ANALYZE` / `VACUUM` / `OPTIMIZE|REPAIR|CHECK TABLE` → `SqlMaintenanceStatement`（tables + optionsRaw）；`SHOW CREATE TABLE|VIEW|DATABASE` / `SHOW COLUMNS|INDEX|TABLES` → `SqlShowStatement`；`COMMENT ON TABLE|COLUMN|…` → `SqlCommentOnStatement`（objectKind/name/comment）；SQL Server `GO` 批分隔；PG `COPY … FROM|TO` → `SqlCopyStatement`（表/列/STDIN·PROGRAM·文件 + WITH 原文）；MySQL `LOAD DATA [LOCAL] INFILE … INTO TABLE` → `SqlLoadDataStatement`（文件/表/列 + FIELDS·LINES·IGNORE 原文）；MySQL 表 `HANDLER t OPEN|READ|CLOSE` → `SqlTableHandlerStatement`；`PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE` / `EXECUTE IMMEDIATE` → `SqlPrepareStatement`（名 / FROM·源 / USING）
- 表达式：字面量、绑定 `?` / `:name` / `:0` / `@var`、相邻字符串隐式拼接、算术比较、AND/OR/XOR/NOT、IN（含 `IN :name` / `IN ?` 无括号绑定列表）/BETWEEN/LIKE/ILIKE/`NOT ILIKE`/REGEXP、IS NULL、`IS DISTINCT FROM` / `IS NOT DISTINCT FROM`、CASE、CAST / `TRY_CAST` / `::`、函数（含 `USING charset`）、EXISTS、子查询、函数结果字段访问 `f(x).y`、`INTERVAL '1 day'` / `INTERVAL 1 DAY` / `INTERVAL … YEAR(n) TO MONTH`、`CAST(… AS INTERVAL DAY TO SECOND)`、`X'FF'` / `0xFF`、行构造 `(a,b)`、JSON `->` `->>` `#>` `#>>`、数组下标 `arr[1]`、PG 数组构造 `ARRAY[1,2,3]`（含 `ANY(ARRAY[...])`）、`= ANY/SOME/ALL (...)`；`INTERVAL` 复合单位（`HOUR_MINUTE`/`YEAR_MONTH` 等）与表达式值（`INTERVAL 6/4 HOUR_MINUTE`）；字符集前缀字面量（`_latin1'x'` / `_utf8mb4'…'` / `_binary'…'` / `_utf32 X'…'`）；`NOT REGEXP`；PL/SQL 游标属性 `SQL%FOUND` / `c1%NOTFOUND`；`count(UNIQUE …)`（等同 DISTINCT）；JDBC/ODBC 转义解包（`{fn …}` 函数、`{d|t|ts '…'}` 类型字面量、`{oj …}` JOIN、`{call …}`、`{escape …}`）；另含 PG `@>`/`<@`/`~`/`~*`、MySQL `FORCE INDEX FOR …`/`<=>`/`INSERT DELAYED`/`BINARY`、SQL Server `TOP WITH TIES`、`TABLESAMPLE`/`SAMPLE`、Oracle `(+)` 外连接后缀 / `CONNECT_BY_ROOT`
- 注释：`--`、`/* */`、MySQL `#`；仅注释/空白的输入解析为 `OTHER` 空语句（不抛 empty SQL）；MySQL 可执行注释 `/*!40101 … */` 展开为内部 SQL（不整段丢弃）；优化器 hint `/*+ … */` 挂到 SELECT / 表并可 format 回写；位置游离的 hint（如 WHERE 中的 `/*+TDDL:MASTER*/`、Trino `/*+joinMethod=…*/`）统一吸收并挂到 SELECT 回写
- 标识符：MySQL 裸标识符允许数字开头（如 `32强国` / `1019使用`），整段不能只是数字；`32` / `32.5` / `32e1` / `0xFF` 仍为字面量；反引号形式原本即可；限定名中点号后的数字开头段可解析（`t.1_id` / `a.32强国`；前导小数 `.5` 仍为 NUMBER）；点号后单引号名作引用标识符（`T.'Group'`）；SELECT 列表别名支持点号限定（`AS a.b`）；函数与表名支持 Oracle DB Link 后缀（`fn@dblink` / `t@dblink`，`SqlIdentifier.dblink`）
- 客户端 / 批处理：`DELIMITER xx` 切换 `parseAll` 终止符（见「DELIMITER」）；SQL Server `GO` 批分隔
- 解析选项：`SqlParseOptions.keepComments(true)`（默认 false）时普通注释进入 `SqlStatement.comments()`，热路径默认仍丢弃；`SqlParseOptions.pipesAsConcat(true)` 让 MySQL 方言下 `||` 按拼接解析（等同 `PIPES_AS_CONCAT`）；`SqlParseOptions.placeholders()` 可配置模板占位（默认关闭，见「模板占位符」）；`SQL.parseAll(sql, dialect, true)` 容错多语句（失败占位 + `parseError`，供审计）

表达式另支持：`SUBSTR…FROM…FOR`、`DECIMAL/REAL/TIME ?` 等类型字面量、`SIMILAR TO`、`CONTAINS`、PG `@@` tsquery、`::type[]` 数组类型后缀；`SELECT … FOR JSON`；Informix `SKIP`/`FIRST`；`VALUES … UNION/ORDER/LIMIT`；Hive `LATERAL VIEW OUTER`；`AS` 后可用 `FULL`/`CROSS` 等关键字作别名。

明确未做：过程体**执行引擎**（AST 结构化已覆盖 DECLARE/HANDLER/控制流/TRIGGER/EVENT 等，但不解释执行）、完整 Wall 规则集（`SqlWallConfig` 提供可配置子集，非 Druid WallFilter 全量）、`MATCH_RECOGNIZE.PATTERN` 的 DSL 树（仍为字符串）。CREATE TABLE 列类型/约束已进 `columnDefinitions` 并可 format 往返。未知函数按普通函数调用解析，不失败。

## 跨方言类型转换（进行中）

表结构 / SQL 跨方言转换走 **Normal Form 中转**（canonical 类型，避免 N² pairwise 映射）。设计见 [sql-schema-converter-design.md](./sql-schema-converter-design.md)。

当前已落地 Phase 0–1（JDK 8，不改 `SqlDdlStatement.columnDefinitions()` 的 `List<String>` 签名）：

```java
import com.alianga.jkit.sql.schema.model.CanonicalType;
import com.alianga.jkit.sql.schema.model.ColumnDefinition;
import com.alianga.jkit.sql.schema.parse.SqlColumnDefinitionParser;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

SqlDdlStatement ddl = (SqlDdlStatement) SQL.parse(
        "CREATE TABLE t (id INT NOT NULL AUTO_INCREMENT, name VARCHAR(32))",
        SqlDialect.MYSQL);
List<ColumnDefinition> cols = SqlColumnDefinitionParser.fromDdl(ddl, SqlDialect.MYSQL);
// cols.get(0): name=id, type=INT, NOT NULL + AUTO_INCREMENT

SqlDataTypeRegistry types = SqlDataTypeRegistry.builtins();
types.convert("VARCHAR(100)", SqlDialect.MYSQL, SqlDialect.ORACLE); // VARCHAR2(100)
types.convert("DATETIME", SqlDialect.MYSQL, SqlDialect.POSTGRES);   // TIMESTAMP
types.fromDialect("TINYINT(1)", SqlDialect.MYSQL);                  // BOOLEAN
types.fromDialect("NUMBER(10,2)", SqlDialect.ORACLE);               // DECIMAL
```

未声明的类型碰撞由 `RegistryValidator` 在内置表构建时阻断；`RegistryValidationTest` 进 CI。

Phase 2–3 已提供整句入口（CREATE TABLE 列类型/自增/UNSIGNED/默认值/表选项；其它语句按目标方言 format，分页复用现有适配）：

```java
String pg = SQL.convert(
        "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, flag TINYINT(1) DEFAULT 0)",
        SqlDialect.MYSQL, SqlDialect.POSTGRES);
// id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY, flag BOOLEAN DEFAULT false

ConversionResult r = SQL.convert(sql, SqlDialect.MYSQL, SqlDialect.ORACLE,
        SqlSchemaConvertOptions.defaults()
                .failOnSeverity(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED));
```

Oracle ≤11g 的自增会给出 `MANUAL_ACTION_REQUIRED`（需手工 SEQUENCE+TRIGGER），不会静默生成不完整 DDL。

查询函数已改写：

- `IF(a,b,c)` → `CASE WHEN`（非 MySQL）
- `NOW()` / `CURDATE()` / `CURTIME()`
- `GROUP_CONCAT` ↔ `STRING_AGG` / `LISTAGG`
- `IFNULL` / `NVL` / `ISNULL`（二元）按目标方言改名；`COALESCE` 为 PG/ANSI
- `CONCAT(a,b,c)` 在 Oracle 下改为 `||`（Oracle `CONCAT` 只接受两参数）
- `CAST` / `CONVERT(expr, type)` 的类型走 canonical 表
- MySQL `CONVERT(expr USING charset)` **不会**误映射成 CAST，只告警并保留原文

目标方言不支持的 MySQL 表内 `KEY`/`INDEX`/`FULLTEXT` 会从 `CREATE TABLE` 中去掉并告警（避免生成无法执行的 DDL）；`UNIQUE KEY` 改写为可移植的 `UNIQUE (...)`。

`SQL.convertBatch` 批量转换。`ALTER TABLE ADD/MODIFY/CHANGE` 会转换列类型（`CHANGE`/`MODIFY` 在非 MySQL 下告警：需手工改写成 `ALTER COLUMN`）。

转换结果会按目标方言再 parse 一遍作为语料回归。真实建表验证在上级目录 `tools-test`：

```text
cd ../tools-test
mvn -Dtest=CrossDialectDdlExecutionTest test   # 需要 Docker；没有则 skip
mvn -Dtest=LocalDatasourceConvertTest test     # 读 src/test/resources/datasource，连本机 MySQL/PG/Oracle
java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlSchemaConvertBenchmark -f 1 -wi 1 -i 1
```

当前 12 个一等方言见 [设计文档第四节](./sql-schema-converter-design.md)。**新产品不必改 `SqlDialect` 枚举**：实现 `SqlDialectSpec`（或 `SqlDialectWrapper`），用 `typeFamily()` 复用内置类型表，用 `dialectId()` + SPI 覆盖个别写法。`SQL.convert` / `SQL.parse` 都吃 `SqlDialectSpec`。

`ConversionResult.sqlWithExtras()` 含附录 `CREATE INDEX` / Oracle SEQUENCE。`DATE_FORMAT` 经函数 SPI 改为 `TO_CHAR`。函数也可 `SqlSchemaConverterProvider.registerFunctions`。`VARCHAR` 超长默认提升为 TEXT/CLOB。

如何加类型别名、覆盖某方言写法、加 canonical 类型、加数据库、加函数改写，见 [第九节](./sql-schema-converter-design.md)。

## 实体扫描生成 DDL / DML

对标 data-set `EntityScanner`：扫描包下带 `@SqlTable` 或 JPA `@Entity` 的类（不依赖 Spring / JPA 编译），再按方言生成建表与增删改查。Java 类型走 canonical 类型表。

```java
import com.alianga.jkit.sql.entity.SqlEntities;
import com.alianga.jkit.sql.entity.SqlTable;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlColumn;

@SqlTable(name = "demo_user")
public class DemoUser {
    @SqlId @SqlGenerated Long id;
    @SqlColumn(name = "user_name", length = 32, nullable = false) String name;
    Integer age;
}

List<Class<?>> entities = SqlEntities.scan("com.example.entity");
String ddl = SqlEntities.createTable(DemoUser.class, SqlDialect.POSTGRES);
// CREATE TABLE demo_user (id BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY, ...)
String ins = SqlEntities.insert(user, SqlDialect.MYSQL);
String upd = SqlEntities.updateById(user, SqlDialect.MYSQL);
String del = SqlEntities.deleteById(DemoUser.class, 1L, SqlDialect.MYSQL);
String sel = SqlEntities.selectById(DemoUser.class, 1L, SqlDialect.MYSQL);
```

有 `javax.persistence` / `jakarta.persistence` 时同样识别 `@Entity` `@Table` `@Column` `@Id` `@GeneratedValue` `@Transient` `@Lob`（反射按类名，无编译依赖）。

## 性能

手写词法 + `ThreadLocal` 复用 Parser。和 Druid / JSqlParser 的对比测试在上级目录 **`tools-test`**（不进本模块，以免引入第三方依赖）：

```text
cd ../tools-test
mvn -Dtest=SqlParserCompareTest test
```

`tools-test` 文件语料 `sql-corpus.txt`（约 **379** 条）上 **jkit 379/379（100%）**；内嵌 CORPUS（约 64 条）亦全绿。竞品缺口随样例变化（Druid 常见挂 `DISTINCT ON` / WINDOW 继承 / UNNEST；JSqlParser 常见挂 `LOCK IN SHARE MODE` / `[dbo].[user]` / WINDOW 继承）。

吞吐以 **JMH** 为准（`tools-test` 的 `SqlParseBenchmark`）。正式轮实测（fork=2、warmup=5、iteration=5、Cnt=10，avgt，ns/op，越小越好；2026-09-10，i9-13900HX / OpenJDK 17.0.11）：

| 引擎 | SIMPLE（单表查询） | JOIN（双表连接） | WINDOW（窗口函数） |
| --- | ---: | ---: | ---: |
| **jkit-sql** | **508** | **1,073** | **657** |
| Druid 1.2.23 | 1,274（2.5×） | 3,667（3.4×） | 3,023（4.6×） |
| JSqlParser 4.9 | 220,445（434×） | 252,464（235×） | 301,220（459×） |

jkit 与 Druid 同属手写解析档，且稳定快 **2.5~4.6 倍**；JavaCC 生成的 JSqlParser 慢两个数量级以上。复现（约 30 分钟）：

```text
cd ../tools-test
mvn -DskipTests package
java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlParseBenchmark -f 2 -wi 5 -i 5
```

| | 解析成功率（文件语料） | 备注 |
| --- | --- | --- |
| **jkit-sql** | **379/379 (100%)** | 模块内黄金集约 216 条（含往返，`SqlGoldenCorpusTest` 约 432 断言）+ 回写保真 118 条（`SqlRoundTripFidelityTest`）+ tools-test 批量保真 379 条（`SqlRoundTripFidelityCorpusTest`）；`mvn -pl jkit-sql test` **889** 条 |
| Druid 1.2.23 | 低于 jkit（缺口见 `target/sql-compare-fail.txt`） | 对比不进本库依赖 |
| JSqlParser 4.9 | 低于 jkit | 同上 |

## 语料与对比

- 模块内：`SqlGoldenCorpusTest`（约 **216** 条，含往返）、`SqlRoundTripFidelityTest`（**66** 条，回写与原文归一化后逐字比对）、`CommonModelSqlCorpusTest`（从 `icell/common-model` 收获，87 条可解析）、`Complex100GiantsTest` / `SqlModelMatchDeepenTest`（MODEL / MATCH_RECOGNIZE 结构化字段）。
- 与 Druid / JSqlParser 对比只在上级工程 `tools-test` 的 `SqlParserCompareTest`（成功率 + 表名集合差分 + JMH；不进本库依赖）。
- 回写保真批量验收：`tools-test` 的 `SqlRoundTripFidelityCorpusTest`（379 条文件语料批量归一化逐字比对，硬断言；5 条有据白名单，报告在 `target/sql-fidelity-report.txt`）。
- 外部语料批量验收（同在 `tools-test`，不进本库依赖）：
  - `ExternalSqlCorpusTest` — bird / Spider / complex100 等 jkit 解析成功率（soft-assert）
  - `ExternalSqlCorpusCompareTest` — jkit vs Druid vs JSqlParser 正确率 + 速度；报告在 `target/sql-corpus-reports/`
  - 语料说明见 `tools-test/src/test/resources/sql-corpora/README.md`（近期对比：bird/spider_ddl/dev/train* / complex100 均为 100%；spider_test ≈ 99.63%）

```text
cd ../tools-test
mvn -Dtest=ExternalSqlCorpusTest test
mvn -Dtest=ExternalSqlCorpusCompareTest test
```
