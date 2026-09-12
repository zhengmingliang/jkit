# jkit 后续计划（内部，给后续 AI agent）

**本文只给仓库维护者和后续 agent 看，不是用户文档。** 禁止在 README、`docs/sql.md`、`docs/en/sql.md`、`jkit-sql/README.md`、站点侧边栏或任何对外页面放入口。vitepress `srcExclude` 已排除本文件。

当前待办从 **第 11 节** 读起。第 1–10 节是已完成历史，不要重做、不要推翻。不要把第三方库引进 `jkit-sql` / `jkit-core`。

- 仓库：`/opt/workspace/zml/jkit`，父 POM `jkit-parent` **2.0.1**
- 对比测试工程：`/opt/workspace/zml/tools-test`（可以引 Druid / JSqlParser）
- 用户要求：每次回复用 `jkit-notify` SMTP 再发一封到 `mpro@vip.qq.com`（凭证在 `/opt/workspace/zml/z-notify-hub/z-notify.db` 的 `email-aliyun`，收件人历史测试为 `mpro@vip.qq.com`）。发信脚本曾放在 `/tmp/jkit-mail-send/`，不在 git 里。

---

## 0. 硬约束

1. **零第三方依赖**：`jkit-core`、`jkit-sql`、`jkit-notify` 的运行时 `<dependencies>` 不得引入 Druid、JSqlParser、POI、Hibernate Validator 等。JUnit 仅 test。
2. **JDK 8**：无 `var`、`List.of`、`String.isBlank`、switch 表达式。
3. Checkstyle：`checkstyle/check-style.xml`。行宽 160 error / 120 warning；ImportOrder 组 `*,javax,java`；禁止 tab；NeedBraces；字段不要显式赋默认值（`= null` / `= 0` / `= false`）。
4. 公开 API 中文 javadoc，`@author 郑明亮`。**`jkit-sql` 收口为 `@since 2.0.1`**（新模块随父 POM 2.0.1 交付，勿改标其它版本号）。其它模块在已发布的 2.0.1 之上若再加未发布公开 API，等真正升版时再标对应 `@since`，不要回写进历史 2.0.1。
5. 对比测试、JMH、引入 Druid/JSqlParser **只允许**在 `/opt/workspace/zml/tools-test`，禁止写进 `jkit-sql` 的 POM。
6. 改 SQL 解析器后：`mvn -pl jkit-sql test` 必须绿；再 `mvn -pl jkit-sql,jkit-core install -DskipTests`，然后 `cd ../tools-test && mvn -Dtest=SqlParserCompareTest test`。

---

## 1. 已经完成（不要重做、不要推翻重写）

### 1.1 `jkit-sql`（新模块 `com.alianga:jkit-sql`）

入口 `com.alianga.jkit.sql.SQL`：

`parse` / `parseAll` / `format` / `toSqlString` / `tables` / `stat` / `addLimit` / `getLimit`/`setPage`… / `andWhere` / `replaceTable` / `replaceColumn` / `parameters` / `parameterize` / `exportParameterValues` / `wall` / `clone` / `eval` / `isReadOnly` / `SqlBuilder` / `and`/`or`/`concat`

已实现：

- 手写 lexer（`char[]` + `SqlKeywords` 开地址哈希）+ `ThreadLocal` 复用 `SqlParser`
- 方言：MYSQL（默认，GBase/MariaDB/TiDB 映射过来）、POSTGRES、ORACLE、SQLSERVER、ANSI、H2
- SELECT：JOIN（INNER/LEFT/RIGHT/FULL/CROSS/NATURAL/STRAIGHT/逗号）、UNION 族、WITH/RECURSIVE、DISTINCT ON、LIMIT/OFFSET/FETCH、TOP、FOR UPDATE、LOCK IN SHARE MODE、CONNECT BY / START WITH / PRIOR、`t.*`
- 窗口：`OVER (PARTITION BY … ORDER BY … ROWS/RANGE BETWEEN …)`、命名窗口、SELECT 级 WINDOW、窗口继承、`FILTER (WHERE …)`
- DML：INSERT/REPLACE（VALUES 多行、INSERT SELECT、INSERT SET、ON DUPLICATE KEY、ON CONFLICT DO UPDATE/NOTHING/ON CONSTRAINT、RETURNING、OUTPUT、Oracle INSERT ALL/FIRST）、UPDATE/DELETE（JOIN、FROM、USING、LIMIT、RETURNING、OUTPUT）、MERGE（多 WHEN AND / BY SOURCE|TARGET、OUTPUT）
- 表达式：CASE、CAST / `::`、IN/BETWEEN/LIKE/ILIKE/REGEXP、`IS [NOT] DISTINCT FROM`、`?` / `:name` / `@var`、EXTRACT/TRIM/SUBSTRING/POSITION/IF/CONVERT/GROUP_CONCAT/STRING_AGG/MATCH AGAINST、JSON `->`/`->>`/`#>`/`#>>`、数组下标、ANY/SOME/ALL、INTERVAL/HEX、行构造 `(a,b) IN ((?,?))`
- DDL：CREATE/DROP/ALTER/TRUNCATE 抽对象名；ALTER ADD/DROP/MODIFY/CHANGE 抽列名；其余进 `SqlDdlStatement.tail`
- SHOW CREATE TABLE|VIEW|DATABASE / SHOW COLUMNS|INDEX|TABLES → `SqlShowStatement`
- 类型字面量：`DATE '2020-01-01'`
- `SqlParseException` 带行号/列号/片段
- 模块内测试：`SqlParserTest` + `SqlGoldenCorpusTest`（黄金集约 206 条 + 模块测试合计约 613）

关键文件：

| 路径 | 职责 |
| --- | --- |
| `jkit-sql/src/main/java/com/alianga/jkit/sql/SQL.java` | 门面 |
| `SqlParser.java` | 递归下降，已经很大，优先拆分而不是继续无限膨胀 |
| `SqlLexer.java` / `SqlKeywords.java` / `SqlTokenType.java` | 词法 |
| `ast/*` | AST |
| `SqlFormatter.java` / `SqlRewriter.java` / `SqlSchemaStat.java` | 回写、改写、抽表列 |
| `docs/sql.md` | 用户文档 |

已知对比结果（`tools-test`）：文件语料 **jkit 379/379（100%）**；内嵌 CORPUS 亦全绿。竞品缺口见 `target/sql-compare-fail.txt`。

正式吞吐用 JMH（`SqlParseBenchmark`，fork≥2）；墙钟 for 循环仅数量级参考。

### 1.2 本仓库其它已有能力（不要当成新需求）

HTTP（含 SSE merge、curl 执行、负载均衡、Nacos）、JSON、YAML、配置、CSV、表达式、notify、curl-codegen。详见 README。

---

## 2. `jkit-sql` 待完善（主战场）

按对用户价值排序。每条都写了验收标准，做完在本文件对应条目标「完成」并补测试。

### P0 — 正确性与可维护性（先做这些）

#### P0.1 拆 `SqlParser.java` ✅ 完成（2026-09-09）

已拆（包内协作，共享 `SqlParser` 记号游标；公开 API 不变）：

- `SqlParser`：语句分发 + WITH + 杂项语句 + 记号工具
- `SqlSelectParser` / `SqlDmlParser` / `SqlDdlParser` / `SqlExprParser`

验收：`mvn -pl jkit-sql test` 全绿（613）；公开 API 不变；纯重构无语法变更。

#### P0.2 parse → format → parse 语义往返 ✅ 完成（2026-09-09）

现在回写是 pretty-print，**不保证空白/注释**，但往返后应：

- `type()` 相同
- `SQL.tables()` 相同（忽略大小写）
- `isReadOnly()` 相同

对 `SqlGoldenCorpusTest` 每条做第二轮 parse。失败的记入 corpus 的 skip 或修 formatter。

验收：黄金集往返无 type/tables 漂移。

**已做**：修 `SqlFormatter`（CREATE INDEX `ON`、SHOW 子句、`SET NAMES` 无等号、EXTRACT/TRIM/SUBSTRING/POSITION 的 FROM/FOR/IN）+ `parseShow` 保留完整子句；黄金集往返断言全绿（持续追加 P0.4 样例）。另收获 common-model 语料 87 条可解析（`CommonModelSqlCorpusTest`）。

#### P0.3 `SqlSchemaStat` 补条件 / 排序 / 分组 ✅ 完成（2026-09-09）

对标 Druid `SchemaStatVisitor`：

- WHERE / JOIN ON / HAVING 条件原文或结构化列表 ✅
- `getOrderByColumns()` / `getGroupByColumns()` ✅
- 表访问类型：`getTables()` → `Map<String, SqlTableAccess>`，同表可合并多种类型；进入嵌套 `SqlSelect` 时记 `SELECT`。`INSERT INTO t SELECT * FROM t` → `INSERT+SELECT` ✅

验收：条件/order/group ✅；多访问类型 ✅。

#### P0.4 把 `consumeRawUntilSemi` 的尾巴逐步变成 AST ✅ 验收最小集完成（2026-09-09）

| 位置 | 状态 |
| --- | --- |
| CREATE TABLE ENGINE / CHARSET / COLLATE / COMMENT | ✅ 结构化；PARTITION 等仍 `tail` |
| ALTER ADD/DROP INDEX、RENAME TO | ✅ 结构化并可 format；ADD UNIQUE INDEX 亦支持 |
| FOR UPDATE OF … NOWAIT / SKIP LOCKED | ✅ `forUpdateOf` + `forUpdateWait`；未知残余仍可 `forUpdateTail` |
| ALTER CHANGE 全列定义 / ADD CONSTRAINT | ✅ CHANGE/MODIFY → columns + columnDefinition；ADD CONSTRAINT/FK/PK/UNIQUE/CHECK |
| GRANT / SHOW CREATE VIEW/DATABASE | ✅ GRANT 抽 privileges + 对象名（`user@host` 紧凑）；SHOW CREATE TABLE/VIEW/DATABASE/COLUMNS/INDEX → `SqlShowStatement`（objectType + name）✅ |
| WITHIN GROUP order-by | ✅ `STRING_AGG`/`GROUP_CONCAT` 已结构化；余量仅其它聚合的 `aggOption` 字符串 |
| CREATE TABLE 表级 FOREIGN KEY 引用表 | ✅ `referencedTables`；列定义原文 `columnDefinitions` 可 format 往返 |

验收三条（ADD INDEX + ENGINE + SKIP LOCKED）✅。

### P1 — 语法覆盖（对标 Druid/JSqlParser 仍缺的）

每加一类语法：黄金集 + `tools-test` corpus 至少加 2 条。

#### P1.1 SELECT 级 `WINDOW w AS (...)` ✅ 完成（2026-09-09）

```sql
SELECT id, sum(x) OVER w FROM t WINDOW w AS (PARTITION BY a ORDER BY b)
```

现已支持 SELECT 级命名窗口定义（可多个）；内联 `OVER (...)` 与 `OVER w` 保留。黄金集 + 单测覆盖 format 往返。

**WINDOW 继承** ✅（2026-09-09）：`WINDOW w2 AS (w)` / `w2 AS (w ORDER BY b)`（`SqlOverExpr.existingWindowName`）。

#### P1.2 更多 JOIN / 表源

- `CROSS APPLY` / `OUTER APPLY`（SQL Server）✅
- `LATERAL` 子查询（PG）✅（`SqlSubqueryTable.lateral`；JOIN LATERAL / 逗号 LATERAL）
- `TABLE(fn())` / `UNNEST` ✅（`SqlFunctionTable`；一般 `fn(...)` 表函数；可 `LATERAL`）
- `FROM (VALUES (1),(2)) AS v(id)` 列清单 ✅（`SqlValuesTable` + `columnAliases`）
- Oracle `(+)` 外连接 ✅（`SqlUnaryExpr.Op.ORACLE_OUTER_JOIN` 后缀，format `col(+)`）
- MySQL `PARTITION (p0, p1)` 表分区限定 ✅（`SqlTable.partitions`）

#### P1.3 函数与表达式 ✅ 完成（2026-09-09）

- `GROUP_CONCAT` / `STRING_AGG` 的 `ORDER BY` / `SEPARATOR` ✅（含 PG `WITHIN GROUP (ORDER BY …)` 结构化）
- `IF(a,b,c)`（MySQL，`IF` 是关键字，确认函数调用路径）✅
- `CONVERT(expr USING charset)` vs SQL Server `CONVERT(type, expr)` ✅
- `JSON_EXTRACT` / `->` `->>` 已有 JSON_OP，补 `#>` `#>>` 与路径字面量 ✅（`SqlBinaryOp.JSON_ARROW/_TEXT/JSON_PATH/_TEXT`）
- `MATCH (cols) AGAINST (...)` 全文 ✅
- `IS DISTINCT FROM`（PG）✅（含 `IS NOT DISTINCT FROM`）
- 数组 `col[1]`、PG `ANY(array)` ✅（`SOME`/`ALL` 子查询参数；SQL Server 仍用 `[]` 引号标识符）
- 类型字面量补 `INTERVAL '1 day'` 与 `X'FF'`（lexer 已有 HEX）✅

#### P1.4 DML 边角 ✅ 完成（2026-09-09）

- Oracle `INSERT ALL` / `INSERT FIRST` ✅（`SqlInsert.insertAll/insertFirst` + `SqlInsertBranch`）
- PG `INSERT … SELECT … ON CONFLICT` ✅（`DO NOTHING` / `DO UPDATE`；`ON CONFLICT ON CONSTRAINT name`）
- `UPDATE … FROM`（PG）✅（`SqlUpdate.from`）
- `DELETE … USING`（PG，MySQL USING 已有一部分）✅（`SqlDelete.usingKeyword`；`DELETE FROM t USING …`）
- MERGE：多个 `WHEN MATCHED AND <pred>`、`WHEN NOT MATCHED BY SOURCE` ✅（`SqlMergeWhen`；含 `BY TARGET`）
- `OUTPUT INSERTED.*`（SQL Server）✅（INSERT/UPDATE/DELETE/MERGE 的 `output` 列表；`OUTPUT … INTO tbl` 表名跳过未结构化）

#### P1.5 DDL / 过程（解析能过 + 抽对象名即可，不必执行） ✅ 完成（2026-09-09）

- `CREATE VIEW` / `CREATE OR REPLACE VIEW` ✅（`orReplace`；AS query）
- `CREATE PROCEDURE` / `FUNCTION` / `TRIGGER` / `EVENT` ✅（抽名；参数与过程体进 `tail`，BEGIN/END 内允许分号）
- `BEGIN … END`、`DECLARE` ✅（`SqlSimpleStatement.OTHER`，吃到匹配 END）
- `CALL proc(a,b)` ✅（`arguments` + `withArguments`；format `CALL p(a, b)`）
- `ANALYZE` / `VACUUM` / `OPTIMIZE TABLE` / `REPAIR` / `CHECK TABLE` ✅（OTHER + 抽表名）
- `COMMENT ON TABLE/COLUMN` ✅（`SqlCommentOnStatement`：objectKind / name / comment）
- `SET` 多赋值 / NAMES / CHARACTER SET ✅（`SqlSetStatement`：scope / setKind / assignments）
- `EXPLAIN` / `DESCRIBE` ✅（`SqlExplainStatement`：analyze / format / options + statement）
- `GO` ✅（SQL Server 批分隔，同分号；`isAliasStop` 避免当别名）

#### P1.6 注释与提示 ✅ 完成（2026-09-09）

- MySQL 可执行注释 `/*!40101 SET … */`：展开为内部 SQL，而不是整段丢掉 ✅
- 优化器 hint `/*+ INDEX(t idx) */`：挂到 SELECT/表上，format 可输出 ✅（`SqlSelect.hints` / `SqlTable.optimizerHint`）
- 解析器可选 `keepComments`（默认 false，避免热路径变慢）✅（`SqlParseOptions` + `SqlStatement.comments()`）

#### P1.7 方言矩阵（先 MYSQL/PG，再 Oracle/SQLServer） ✅ 完成（2026-09-09）

不要一上来做 Hive/ClickHouse/ODPS（Druid 有 30 种方言，那是深坑）。

缺的方言行为：

- MYSQL：`||` 已当 OR；补 `PIPES_AS_CONCAT` 作为 parse 选项 ✅（`SqlParseOptions.pipesAsConcat`）
- PG：`RETURNING *`、`ILIKE`、`::` 已有；补 `RETURNING` 多列列表 ✅；`ON CONFLICT ON CONSTRAINT name` ✅（P1.4 已有，本 pass 验收）
- Oracle：`DUAL`、`ROWNUM`、`CONNECT BY` 已有；补 `FETCH FIRST n ROWS ONLY` ✅（`SqlLimit.fetchStyle` + FETCH 回写）；`MINUS` ✅（原先已有，本 pass 验收）
- SQL Server：`TOP`、`[]` 已有；补 `OUTPUT`、`APPLY` ✅（P1.2/P1.4 已有，本 pass 验收）
- 达梦/GBase：GBase 仍映射 MYSQL；达梦已独立为 `DAMENG`（IDENTITY + LIMIT）

### P2 — 能力对标（Druid 常用 Visitor） ✅ 完成（2026-09-09）

均保持零依赖，API 放 `SQL` 门面。

| 能力 | 对标 | 说明 |
| --- | --- | --- |
| 参数化归一 | Druid `ParameterizedOutputVisitor` | ✅ `SQL.parameterize(sql)` → 字面量变 `?`（STRING/NUMBER/BOOLEAN/HEX/BIT；NULL/绑定保留） |
| 导出参数值 | `ExportParameterVisitor` | ✅ `SQL.exportParameterValues` 抽出 `'a'` / `1`（去引号）；与 `parameters()` 绑定占位符分立 |
| 注入/危险操作 | `WallFilter` 子集 | ✅ `SQL.wall(sql)` → `SqlWallResult`：multi-statement、comment-bypass、always-true、SLEEP、DELETE/UPDATE without WHERE。默认不接入解析 |
| 表达式求值常量折 | `EvalVisitor` 子集 | ✅ `SQL.eval(expr)` / `SqlEval`：字面量算术/比较/AND/OR/NOT，无反射 |
| AST clone | JSqlParser `DeParser` 配套 | ✅ `SQL.clone(stmt[, dialect])`（format→parse）；`addLimit` 改为 clone-then-mutate |
| 按类型 Visitor | Druid `visit(SQLSelect)` | ✅ `SqlAstVisitor` 类型分发；保留 `SqlVisitor` / `SqlVisitorAdapter` |
| 列改写 | — | ✅ `SQL.replaceColumn(stmt, from, to)` 对称 `replaceTable`（跳过 `SqlTable` 子树） |

`addLimit` / `andWhere` / `replaceTable` / `replaceColumn` 已改为 **clone 后再改**；单测断言原树不变。

### P3.x — 后 P3 产品化（2026-09-09 已完成）

- ✅ AST `toString()` → 紧凑 SQL（`SqlNode` + formatter 覆盖 SelectItem/Limit/Join 等）
- ✅ `SqlDialect` 别名 + `supportsLimitOffset/Top/FetchFirst/Rownum` / `pipesAreConcat` / `quoteIdent`
- ✅ 分页 get/set/setPage（方言感知；含 ROWNUM/row_number 识别与边界改写、UNION 末端）
- ✅ `SqlBuilder` + `SQL.and`/`or`/`concat`

### P3 — 工程与性能（P3.1 / P3.2 已完成；lexer intern 延期）

- **JMH 对比** ✅（P3.2，2026-09-09）：`tools-test` 增加 `com.alianga.test.sql.jmh.SqlParseBenchmark`（simple / join / window × jkit/druid/jsql），JMH 1.37 + exec 插件。正式数字用 fork≥2，勿用墙钟 for 循环。
  - Smoke：`cd tools-test && mvn -DskipTests package && java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlParseBenchmark -f 1 -wi 1 -i 1`
  - Full：`java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlParseBenchmark -f 2 -wi 5 -i 5`
- **扩 corpus** ✅（P3.2）：`tools-test/src/test/resources/sql-corpus.txt`（~379 条，`dialect | SQL`，`#` 注释；来自 CompareTest / Golden / common-model / 安全样例）。`SqlParserCompareTest#corpusFileSuccessRates` 统计三家成功率，缺口写入 `target/sql-compare-fail.txt`。**不要**把 Druid 测试 jar 拷进 jkit。
- Lexer：关键字哈希已无字符串分配；短 ident intern 轻量优化 **延期**（需 profiling，不阻塞 P3.2）。
- `SqlFormatter` 按方言输出反引号 / 双引号 / `[]`，`||` 按 AST 回写 ✅（P3.1，2026-09-09）。
- `parseAlias` 删除未使用的 `inFrom` ✅（P3.1）。
- 多语句：`SQL.parseAll(sql, dialect, true)` 容错 ✅（P3.1）；失败记 `SqlSimpleStatement` + `parseError`，继续下一条。
- 发布（已定）：父版本 **2.0.1**；新模块 `jkit-sql` **随 2.0.1 一并交付**（同 parent 版本下的新 artifact）。javadoc / CHANGELOG / README 依赖示例一律 `2.0.1`。

---


### 语料 6 缺口（2026-09-09） ✅

`tools-test` corpus 原 jkit=373/379，已补：

1. 仅注释/空白 → `SQL.parse` 返回 `OTHER` 空语句（不再抛 empty SQL）
2. PG `COPY t FROM STDIN WITH (FORMAT csv)` → OTHER + 表名
3. SQL Server `OPENJSON(@json) WITH (...)` → `SqlFunctionTable.withDefinition`
4. MySQL `HANDLER t OPEN|READ|CLOSE` → OTHER + 表名（批处理 parseAll）
5. Oracle `col(+)` 外连接 → `ORACLE_OUTER_JOIN`
6. MySQL `PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE` → OTHER + 名

仍跳过：无（表 PARTITION 已落地）。

### 语法缺口（2026-09-09 修过一部分）

- ✅ PG `@>` / `<@`、`~`/`~*`/`!~`；MySQL `FORCE INDEX FOR JOIN|ORDER BY|GROUP BY`
- ✅ MySQL `<=>` / `INSERT DELAYED` / `BINARY expr`；SQL Server `TOP (n) WITH TIES`；PG `TABLESAMPLE` / Oracle `SAMPLE(n)`
- ✅ Oracle `(+)`；✅ MySQL `PARTITION (p0, p1)` 表分区限定
- ✅ 语料剩余缺口：comment-only→OTHER；PG `COPY … STDIN`；SQL Server `OPENJSON … WITH`；MySQL `HANDLER` / `PREPARE|EXECUTE|DEALLOCATE`（OTHER+抽名）

## 3. 对比测试工程（`/opt/workspace/zml/tools-test`）

已加依赖：`jkit-sql:2.0.1`、`druid:1.2.23`、`jsqlparser:4.9`。  
测试类：`com.alianga.test.sql.SqlParserCompareTest`。

后续可做：

1. ~~JMH 正式吞吐~~ ✅ P3.2（见上）
2. ~~corpus 文件化 + `target/sql-compare-fail.txt`~~ ✅ P3.2
3. ~~表名集合对比（忽略库名前缀和大小写），输出「仅 jkit 有 / 仅 druid 有」~~ ✅（`corpusFileTableSetDiff`，软断言）
4. 改完解析器后必须：`mvn -pl jkit-sql,jkit-core install -DskipTests` 再跑 tools-test，否则会用到旧的本地 2.0.1
5. 不要把 tools-test 的 POM 改回 jkit 2.0.0
6. Lexer 短 ident intern（需 profiling）

---

## 4. 仓库其它未做项（SQL 做稳之前不必开工）

来自「一季度计划」，SQL 占用后这些都还没做。**不要在完善解析器的同一 PR 里夹带。**

| 项 | 位置 | 要点 |
| --- | --- | --- |
| HTTP **可实例化客户端** | `jkit-core` `HttpUtils` / `HttpConfig` | 文档已写「后续提供」。`HttpClient.builder()`，全局 `setXxx` 只影响 `shared()`。测试：两个实例配置互不污染 |
| 流式 multipart | `HttpUtils.upload` | 现在整包进内存 |
| JWT + 加密默认值 | `EncryptUtils.RSA` 仍 1024+ECB；DES 仍在门面 | HS256/RS256；RSA 2048+OAEP；AES-GCM；DES/1024 `@Deprecated` |
| 空文档 | `docs/expression.md`、`docs/csv.md` | 现在是空文件，表达式/CSV 两套门面实际存在 |
| `jkit-llm` | 新模块 | 建立在 HttpClient 实例 + 已有 `sseMerge` 上，本季不要做 |
| 轻量 HTML | 新模块或 core | 替代已移除的 Jsoup，CSS 选择器子集 |
| 韧性抽包 | `http.lb` 的 Retry/熔断 | 给 notify / 任意 Callable 用 |
| zstd | HTTP `Content-Encoding` | 已有 gzip/deflate/br |
| Consul / K8s 发现 | `ServiceDiscovery` | 已有 Nacos + 静态 |
| JSON Patch / Schema `required` 别名 | `jkit-core` json | `must` → 兼容标准 `required` |
| ULID / UUIDv7 | `common.idgenerate` | 小 |
| 脱敏 | 与 `IdCardUtils` 同包 | 小 |

---

## 5. 明确不要做

- 在 `jkit-sql` 里执行 SQL、接 JDBC、做 ORM
- 引入 Calcite / JSqlParser / Druid 当实现
- 30 种方言（Hive/ODPS/ClickHouse/StarRocks…）除非用户拿真实 SQL 来
- Excel / PDF / 拼音 / 完整 IMAP
- 把 `expression` 包和 SQL AST 合并
- 为了对齐 Druid 而牺牲 JDK 8 或零依赖
- 重写已通过的黄金集测试来「降低失败率」

---

## 6. 建议开工顺序（给下一个 agent）

1. 跑绿：`mvn -pl jkit-sql test`；`cd ../tools-test && mvn -Dtest=SqlParserCompareTest test`（先 `install` jkit-sql）。
2. ~~P0.1 拆 Parser~~ ✅；~~P0.2 往返~~ ✅；~~P0.3 SchemaStat~~ ✅；~~P0.4 验收最小集 + CHANGE/CONSTRAINT/FK/GRANT/列定义回写~~ ✅。
3. ~~P1.1 WINDOW / P1.2 APPLY·LATERAL / P1–P3 主体~~ ✅；lexer 短 ident intern 仍延期。
4. 余量（可选）：其它聚合的 WITHIN GROUP/`aggOption`。~~OUTPUT INTO~~ ✅；~~SqlBuilder rightJoin/union/with/distinct~~ ✅。
5. 对比工程：语料成功率 ✅；~~表名集合差分~~ ✅；改解析器后记得 `install` 再跑 tools-test。

**发版叙事已定（sql-only）**：`jkit-sql` 随父 POM **2.0.1** 收口（`CHANGELOG` 顶栏 `## 2.0.1 - 2026-09-09`，javadoc `@since 2.0.1`）。每完成一块：补 `@since 2.0.1`（仅 sql 模块）、更新 `docs/sql.md` 覆盖表、在 `CHANGELOG.md` 的 `2.0.1 - 2026-09-09` 追加条目。父 POM 保持 2.0.1，不要擅自升版。

---

## 7. AST 深度收口评估（2026-09-10，agent）

本轮已落地：`COMMENT ON` / `SET` 多赋值 / `EXPLAIN|DESCRIBE` 结构化。

**余量多为低价值，建议问用户是否停止继续抠杂项：**

| 项 | 建议 |
| --- | --- |
| GRANT/REVOKE 角色 / WITH GRANT OPTION / 多 TO 细拆 | 可选；现有 privileges+对象名已够多数墙/抽表 |
| SHOW STATUS/VARIABLES/PROCESSLIST 细字段 | 跳过（raw 足够） |
| VACUUM/ANALYZE 括号选项 DSL | 跳过 |
| COMMENT ON FUNCTION 参数签名 | 跳过 |
| lexer 短 ident intern | 延期（需 profiling） |

主路径（DML/DDL 抽名、过程块、事务、维护、SHOW/SET/EXPLAIN/COMMENT）已较饱和。

---

## 8. 回写保真与表抽取精度收口（2026-09-12，agent）

第 7 节只覆盖语法/AST 深度；本轮按用户指示转向**回写保真**与**表抽取精度**，全部完成 ✅（`mvn -pl jkit-sql test` 796 全绿）。

**方法论（重要，后续 agent 直接复用）**：只断言「能否解析」测不出回写丢词——`NATURAL LEFT JOIN` 第一遍就把 `LEFT` 丢了，`format(format(x)) == format(x)` 依然成立。**必须做「原文归一化后 vs 回写」逐字比对**（归一化 = 去注释 / 去全部空白 / 去独立 `AS` / 统一大小写），已固化为 `SqlRoundTripFidelityTest`（66 条坑位语料）。

本轮修复清单：

- `SqlJoin.natural()`：`NATURAL` 与 joinType 正交（`NATURAL LEFT/RIGHT/FULL/INNER JOIN`）
- `RENAME TABLE a TO b[, c TO d]`：独立语句类型 `RENAME`；多组不再静默丢
- `ALTER … ADD UNIQUE KEY|INDEX` 保留 `UNIQUE`（根因：`isIdent("KEY")` 认不出关键字记号 → 改判 token 类型）
- 解析失败补齐：`DROP INDEX … ON`、SS `WITH (NOLOCK)`/`WITH (INDEX(ix))`、PG `ARRAY[1,2,3]`、`CREATE/DROP USER`
- `tables()`：补漏（TRIGGER 真实表 / LIKE 源表 / RENAME 目标表 / 维护语句全组）；清误（USE/CALL/DECLARE/对象名/GRANT/CTE 名）；VIEW 名计入 `tables()` 是 `SqlParserTest.p15` 锁定的既有语义，**保留勿动**
- 格式：类型参数紧凑逗号（`appendRawToken` 不给逗号前加空格）、`GRANT` 列表、`TRUNCATE TABLE` 统一

差分现状：only-druid 真缺口 3→1（剩 1 条是 druid 把 `TABLE(fn(...))` 函数当表，不是 jkit 的问题）；only-jkit 64→48（剩余抽查为 jkit 比 druid 抽得全：MERGE USING / HANDLER / REPAIR / FOR UPDATE 等）。

**已知遗留（低价值，勿自动展开）**：`FROM dual` 计表、`SHOW TABLES FROM db` 把库名计表、`WITH (INDEX(ix))` 内部空格按原文保留。

后续若继续：优先把 `SqlRoundTripFidelityTest` 扩到 `tools-test` 的 379 条语料批量跑（本地探针已验证可行），再考虑新语法。

---

## 9. 扩展性改造（2026-09-12，agent）

按 ROI 排序的五项扩展性改造，已全部完成 ✅（`mvn -pl jkit-sql test` 844 全绿）：

1. **方言能力可覆盖** ✅ `e3ca817` — `SqlDialectSpec` 接口 + `SqlDialectWrapper` 包装层；4 处散落 `dialect == SqlDialect.X`（Lexer 方括号标识符 / Lexer 反斜杠转义 / ExprParser `~` 正则 / Rewriter 逗号分页）改回能力方法；全部方言参数放宽为 `SqlDialectSpec`（枚举调用点源码兼容）。
2. **SqlWall 规则 SPI** ✅（见本轮提交）— `checkStatement` 的 if-else 拆成内置 5 条 `SqlWallRule` 规则链，`SqlWallConfig.rules(...)` 追加自定义规则；违规码收集走 `SqlWallViolations`（去重）。行为与违规码完全不变，`SqlWallTest` 全部原样通过。
3. **语句解析注册表** ✅ — `SqlStatementParsers`（`SqlParseOptions.statementParsers()`，默认关闭）按前导关键字注册 `SqlStatementParser`，只兜内建 switch 未覆盖的 default 分支；`SqlParseContext` 暴露游标子集（token / is / match / isIdent / matchIdent / name / consumeRest（原文切片）/ error / atStmtBreak）；返回 null 或留未消费记号 → 带位置错误；内建语句不受影响（注册 SELECT 也不会覆盖）。
4. **SqlFormatOptions 扩展** ✅ — 关键字大小写策略 `keywordCase(SqlKeywordCase.UPPER/LOWER/AS_IS)`；`SqlFormatter` 内 87 处关键字输出（含 84 处 `out.append("KEYWORD")` 直写、二元运算符 symbol、FLUSH 选项、事务 kind）统一收敛到 `kw()`，AS_IS 输出逐字节不变。pretty 缩进宽度**有意未做**：`SqlFormatter.indent` 字段是死代码（从未自增，pretty 输出本就无缩进），接 `indentSize` 前需先实现真实缩进（subquery/CTE/UNION 臂），等有真实需求再做，勿硬接死代码。
5. **改写规则链** ✅ — `SqlRewriteHook`（函数式接口：收当前语句、返回继续传递的语句）+ `SqlRewrites`（`create`/`none`/`add` 有序链 + 内建适配器 `addLimit`/`setLimit`/`setOffset`/`setPage`/`andWhere`/`replaceTable`/`replaceColumn`）；自定义规则在内建之前即前 hook、之后即后 hook；门面 `SQL.rewrite(stmt, chain)` 先深拷贝再改，规则返回 null 抛 `IllegalArgumentException`；`SqlRewriter` 既有静态方法行为零变化。

明确不做：`SqlKeywords`/`SqlTokenType` 动态注册化（零分配哈希是性能关键路径）；visitor 重设计（双轨制够用）。

---

## 10. 竞品语料覆盖率三轮提升 + 方言扩展交接（2026-09-12，agent）

### 10.1 现状快照（R7 覆盖后）

- 模块测试 **930+ 全绿**；保真回归 `SqlRoundTripFidelityTest`；379 语料 100%；保真批量 0 mismatch。
- 竞品语料（tools-test `CompetitorSuiteCorpusTest`，三方各用方言回退链、2s 超时护栏）：
  - druid-bvt-inline（6483）：**jkit 96.3%（已超 Druid 92.3%）** / druid 92.3% / jsql 68.1%
  - jsqlparser-inline（3078）：**jkit 92.3%（已超 90%）** / druid 71.1% / jsql 70.1%
  - jsqlparser-files（460）：**jkit 90.0%（已达目标）** / druid 81.5% / jsql 74.8%（jkitGaps=4）
  - jkitGaps 合计 **164**（128+32+4；R12 173+52+4 → R13）
  - jkit 全程 0 超时；druid 1.2.23 仍有 2 条 PG `ANALYZE` 死循环（jstack 实锤，勿追）
- 方言：一等枚举 6+1 → 11（新增 `DB2`/`SQLITE`/`HIVE`/`CLICKHOUSE`/`PRESTO`），行为全部走
  `SqlDialectSpec` 能力方法，无散落 `== SqlDialect.X`；`fromName` 国产/主流别名已全
  （含 common-model 数据源：`argo→HIVE`、`xcloud→POSTGRES`、`gbase8a→MYSQL`、`gbase8s→SQLITE`）。
- 竞品语料对比基线表：`tools-test/src/test/resources/sql-corpora/README.md`（每轮提升后记得同步）。

### 10.2 本轮已落地（不要重做）

- **R1**（`6d92cde`）：INTERVAL 复合单位（`HOUR_MINUTE`/`YEAR_MONTH`）与表达式值（`6/4`）；
  `count(UNIQUE…)`；`WEIGHT_STRING(AS CHAR(n)/LEVEL)`；`IS [NOT] UNKNOWN`；SQL/JSON 构造器
  （`json_object`/`json_array`/`json_objectagg`/`json_arrayagg`/`json_table` 专用文法**原文保留**为单参数）；
  `UNNEST…WITH ORDINALITY`；DDL 吞咽（`CREATE TYPE…AS OBJECT/VARRAY/ENUM`、`DROP…PURGE/TABLESPACE`、
  `TRUNCATE…PURGE SNAPSHOT LOG` 尾段）；CAST 后缀 `CHARACTER SET`/`ARRAY`；`TRANSLATE(…USING CHAR_CS)`。
- **R2**（`8bc016f`）：位置游离 hint 统一吸收（WHERE/AND/函数参数中的 `/*+TDDL*/`、Trino hint，
  表达式层暂存、语句层挂到 SELECT）；Hive `INSERT OVERWRITE [TABLE] t [PARTITION(…)]`；
  `CONNECT BY NOCYCLE`；`GROUP BY … WITH CUBE`；Teradata/Snowflake `QUALIFY`（已入别名停用词）；
  MySQL 8 函数索引 `ADD KEY idx ((expr))`；XML 系 7 函数原文；Spark `OVER (DISTRIBUTE BY … SORT BY …)`。
- **R8**：`*=` 不再当乘；`FOR KEY SHARE`（KEY 关键字）；Informix `, OUTER t`；`GLOBAL JOIN`；
  `INSERT … DEFAULT VALUES` / 非 OVERWRITE `PARTITION`；`IN SELECT` 无括号；一元 `&x`；
  Informix `db:schema.t`（仅当后接 `.`）；HAVING 可在 GROUP BY 前；`DATE + (1 DAY)` / `- 1 DAY`。
  gaps 479→455；**94.6 / 86.0 / 79.1**。
- R9：空白运算符/`=>`/`//`备注、SEED、NO KEY UPDATE、HASH/WITHIN/链式ON、EXCEPT/REPLACE、
  MEMBER OF/ISNULL/GLOBAL IN、PREWHERE/SETTINGS/EMIT/PREFERRING、GROUP BY ()、流 WINDOW、
  OPTION、数组 SET、ON CONFLICT WHERE、REPLACE VIEW、TABLE WITH()、READ ONLY。
  gaps 455→378；**95.0 / 88.2 / 79.8**。
- **R12**：Oracle MODEL（RULES ORDER / MEASURES 字面量别名 / WHERE 后）、MERGE DELETE|INSERT WHERE、
  括号 PIVOT/UNPIVOT、`PARTITION BY` 外连接、`INSERT WHEN`、多 GROUPING SETS、SEARCH/CYCLE、
  TABLE(SELECT)、interval qualifier、数值后缀、运算符夹注释、选择列表括号 UNION。
  files gaps 44→4；**95.6 / 91.5 / 90.0**（三语料目标均达成或维持）。
- **R13**：PG `DO $$…$$`；块内 `ELSIF`/`EXCEPTION WHEN`；标签 `BEGIN…END`；MySQL VIEW/PROCEDURE
  `ALGORITHM`/`DEFINER`/`SQL SECURITY`（`user@%`）；INSERT/REPLACE 优先级链；`AS` 关键字别名
  （修 match 误消费）；`SUBSTR FROM FOR`；类型字面量；`SIMILAR TO` 关键字；`type[]`；Informix `SKIP`
  关键字；`FOR JSON`；`VALUES UNION/ORDER/LIMIT`；`IN ((SELECT),…)`；`LATERAL VIEW OUTER`；
  `CONTAINS`；`@@` tsquery。gaps 229→164；**96.3 / 92.3 / 90.0**。
- R10：`$$` dollar-quote、GROUP BY (expr)、EMIT/LIMIT 序、数组切片、WITHIN GROUP PARTITION、
  CAST schema.type、ON CONFLICT≠链式ON、EXCLUDE、INSERT alias/OVERRIDING、OUTER JOIN、
  NOT ISNULL、SEPARATOR、FROM VALUES。gaps 378→339；**95.0 / 89.5 / 79.8**。
- **R7**：分号后多段垃圾跳过；`SIGNED INTEGER`；`((((t))))` 嵌套括号表；`RETURNING`/`OUTPUT` 别名与 `old.*`/`new.*`/`DELETED.*`；
  `DELETE … OUTPUT … FROM`；`FOR XML PATH/AUTO…`/`FOR BROWSE`；三元 `a?b:c` 与 jsonb `?`/`?:bind` 消歧；
  `ARRAY[[…]]`；`IGNORE/RESPECT NULLS`；`LIMIT n BY`；`SIMILAR TO`；无括号标量子查询；旧式 `*=` 外连接；
  `lookahead` peek 污染修复。gaps 595→479；**94.5 / 85.4 / 78.9**。
- **R6**：`|`+语句软分隔、`AT TIME ZONE`、`?1`、`FROM (WITH…)`、`catalog..t`、
  `FOR KEY SHARE`、三元运算、下标/成员链等。gaps 669→595；jsql-inline 84.2%。
- **R5**：别名停用词补语句关键字；`|`/`/` 软分隔；分号后尾垃圾软停；`SET (a,b)=`/`:=`；
  `FOR SHARE`/`FOR XML`；`WITH UR`；`PIVOT XML`；`SKIP/FIRST`；`IN` 无括号；`CURRENT TIMESTAMP`；
  `RETURNING INTO`；`INCLUDES/EXCLUDES`；MERGE hint。gaps 761→669。
- **R4**：ODPS `FORCE PARTITION`/`FORCE ALL PARTITIONS`；Hive UDTF `AS (c0,c1)`；
  Oracle `VERSIONS BETWEEN` / `CONNECT_BY_ROOT` / `TRY_CAST` / `INTERVAL … TO …`；
  相邻字符串拼接、`:0` 数字绑定、`_utf32 X'…'`；`LEFT|RIGHT ANTI|SEMI JOIN`；
  括号集合运算子查询；`INSERT … (WITH … SELECT …)`；`DELETE t1.*`；`SQL%FOUND`；
  函数 `USING charset`；别名后 `FORCE INDEX`；`SELECT INTO (c,d)`。jkitGaps 956→761；
  **druid-bvt 已超 Druid**。
- **R3**（`31286c8`）：MySQL SELECT 修饰符链（`STRAIGHT_JOIN`/`SQL_SMALL_RESULT`/`SQL_BIG_RESULT`/
  `SQL_BUFFER_RESULT`/`SQL_CACHE`/`SQL_NO_CACHE`/`DISTINCTROW`）；`replace(...)` 按函数调用解析；
  多表删除第二形式 `DELETE FROM a1, a2 USING …`（`SqlDelete.targets`，`parseJoinChain` 已抽出复用）；
  字符集前缀字面量 `_latin1'x'`（`SqlLiteral.name` 存前缀）；`NOT REGEXP`；模板占位 `#{}` 不再被
  `#` 行注释吞掉（lexer 占位匹配优先于注释跳过）；JDBC/ODBC 转义解包
  `{fn}`/`{d|t|ts}`/`{oj}`/`{call}`/`{escape}`；MODEL MEASURES 裸别名；`UNPIVOT INCLUDE|EXCLUDE NULLS`；
  `GROUP BY [DISTINCT] a GROUPING SETS(…)`；Oracle `fn@dblink`/`t@dblink`（`SqlIdentifier.dblink`）；
  CTAS 尾缀 `WITH [NO] DATA`（query+tail 回写）。
- **方言**（`2116546`/`6fba9db`）：5 个新枚举 + 国产/主流/common-model 别名。
- **竞品测试集**（tools-test `ec2393e`/`5a3d929`）：收割 druid bvt 2465 个 Java 文件内联 SQL 6483 条 +
  jsqlparser 内联 3078 + 资源文件 460，共 10001 条；占位符兜底（`#{}/${}/@x@/%s/<sheet>`）已计入通过率。

### 10.3 已知陷阱（本会话踩过三次，后续开发必读）

1. **`isIdent()` 只认 `SqlTokenType.IDENT`**。`DISTRIBUTE`/`INCLUDE`/`WITH`/`CUBE` 等若是关键字记号，
   `isIdent` 永远 false。按文本匹配时用：
   `(p.identLike() || p.token.type().keyword()) && p.token.textEqualsIgnoreCase("X")`。
2. **`match()` 会消费**：`if (match(A) || match(B))` 里做类型判断，A/B 已被吃掉（STRAIGHT_JOIN 旧 bug 的成因）。
   需要"看一眼不消费"时用 `p.token.type()` / `p.lexer.peek()`。
3. **解析分支互斥**：新增子句关键字（如 QUALIFY）要同步加进 `parseAlias` 的停用词，否则被当表别名吞掉。
4. **`OVER (...)` 的继承窗口名识别**有排除清单，新增 OVER 内子句（DISTRIBUTE/SORT）要加排除，否则被当窗口名。
5. **原文保留参数**（JSON 构造器 / WEIGHT_STRING 特殊尾段）内的 `?` 绑定**不进** `parameters()`（已知取舍，勿改）。
6. **`groupByExtension` 与 `groupBy` 列表**可共存（`GROUP BY DISTINCT a GROUPING SETS(…)`），回写两者都要输出。

### 10.4 剩余缺口构成与可选方向（956 条，收益递减，按真实需求驱动）

| 方向 | 规模 | 说明 |
| --- | --- | --- |
| PL/SQL 深度 | ~50+ | `SQL%FOUND`/`SQL%NOTFOUND`/`EXCEPTION WHEN`/`ELSIF` 结构化（现在靠 `SqlBlockStatement` 尽力 raw 兜底）；`CONNECT_BY_ROOT`/`SYS_CONNECT_BY_PATH`/`LEVEL` 伪列 |
| 长尾一次性变体 | ~700 | 各库专属 DDL/函数/怪异写法，单点收益极低，**不建议扫尾** |
| 多语句/批处理行 | ~50 | 测试集结构问题为主 |
| MERGE 变体 | ~20 | 罕见 WHEN 组合、UPSERT 方言形 |
| ODPS 专属 | ✅ R4 | `FORCE PARTITION` / UDTF `AS (c0,c1)` 已落地 |
| Oracle 闪回 | ✅ R4 | `VERSIONS BETWEEN` / `CONNECT_BY_ROOT` 已落地 |
| 不可修 | ~26 | GBK 乱码（编码问题，非语法） |

其它老遗留：`aggOption` 余量（其它聚合的 WITHIN GROUP 字符串）；lexer 短 ident intern（仍需 profiling，
别凭感觉做）；`SqlFormatter.indent` 是死代码（见第 9 节，勿硬接）。

### 10.5 验证流程（改解析器后必跑，顺序不要乱）

```bash
mvn -pl jkit-sql test                                   # 模块 889 必须全绿
mvn -q -pl jkit-sql,jkit-core install -DskipTests       # tools-test 才能用到新代码
cd ../tools-test && mvn -Dtest='SqlParserCompareTest,SqlRoundTripFidelityCorpusTest,CompetitorSuiteCorpusTest' test
# 379 语料必须仍 100%；保真批量必须 0 mismatch；竞品三语料看 jkitGaps 是否下降
# 报告：target/sql-corpus-reports/competitor-*-jkit-gaps.tsv / sql-fidelity-report.txt
```

- 竞品语料重收割：`/tmp/sql-suites/harvest.py`（sparse clone alibaba/druid 与 JSQLParser/JSqlParser
  到 /tmp/sql-suites；**/tmp 会被清理**，重跑前确认存在）。
- 探针：`/tmp/sqlprobe/Probe.java`（多方言 parseAll 探针）与 `ProbePh.java`（占位符探针），同样易失。

### 10.6 协作与工程约定（本会话实测有效的做法）

1. **共享工作区 + 共享 git index**：另一 agent 进程长期活跃（SQL 解析器主力）。**提交前必看
   `git status`/`git log`**：它的暂存可能被你的 commit 卷带（本会话发生两次，消息里已注明）。
   `jkit-core/.../JSONTest.java` 的 M 是历史遗留，**永远别带进提交**。
2. **发版叙事**：父 POM `2.0.1`；jkit-sql 新公开 API 一律 `@since 2.0.1`；用户明确要求
   **docs/sql.md 按"初版特性"口径写，不写修复叙事**（f44f30e 已按此同步，后续照此办理）。
3. **每完成一块**：`@since` + `docs/sql.md`（中英同步）+ CHANGELOG 顶部 `2.0.1 - 2026-09-09` 追加条目。
   文档站构建：`npx vitepress build docs`（预览 `npx vitepress preview docs --port 4173`）。
4. **邮件通知**：每轮完成后用 jkit-notify SMTP 发 `mpro@vip.qq.com`。
   脚本 `/tmp/jkit-mail-send/SendNotify.java`（/tmp 易失需重建）；凭证在
   `/opt/workspace/zml/z-notify-hub/z-notify.db` 表 `channel_config`（`config_id='email-aliyun'`，
   列名是 `config_json`），用 jkit 自家 JSON 解析；类路径 = jkit-2.0.1.jar + jkit-notify-2.0.1.jar。
5. **checkstyle**：`mvn checkstyle:check -Dcheckstyle.skip=false` 基线本身有 3632 条违规
   （含老代码），不要试图清零，只保证自己新增代码守 160 列/花括号/无 tab。
6. **文档站排除项**：`docs/next-plan.md`（内部计划）、`docs/csv.md`/`docs/expression.md`（空文件）
   在 `srcExclude` 里；csv/expression 两章补齐后记得从排除名单移除并进侧边栏。
   **公开文档禁止再链到 next-plan**（2026-09-12 已从 README / docs/sql.md / docs/en/sql.md / jkit-sql/README 去掉）。

### 10.7 建议的下一步（已过时，改看第 11 节）

竞品三语料硬目标（jsql-inline / jsql-files ≥90%、druid-bvt 超 Druid）**已达成**。继续扫 164 条 gaps 收益递减，**不要**当默认开工项。当前待办见第 11 节。

---

## 11. 当前待办（2026-09-12 复审）

对照现码：跨方言转换（类型表 / 函数 SPI / 自定义 `SqlDialectSpec` / 实体扫描）已经能用；`registerFunctions` 已进管线（`50746b3`）。下面是**还值得做**的，按对用户价值排。每条做完在本文件标 ✅ 并补测试。

### 11.1 已落地、不要重做

- 类型 Normal Form + SPI `registerTypes` / `registerAlias` / `LossyMapping`
- 函数 `SqlFunctionRegistry` + `registerFunctions`（SPI 覆盖、`null` 回落内置）
- 自定义方言 `SqlDialectSpec` / `SqlDialectWrapper`（派生方法跟原语）
- 表内 `KEY` → 附录 `CREATE INDEX`；`ALTER CHANGE/MODIFY` → PG `ALTER COLUMN TYPE`
- 实体扫描 `@SqlTable` / JPA `@Entity` 生成 DDL/DML
- Wall / 语句解析 / 改写链：代码注册，不是 ServiceLoader

### 11.2 P0 — 转换正确性 ✅（2026-09-12）

#### P0.1 函数 walker 漏节点 ✅

`FunctionAstRewriter` 只在 SELECT 列表 / WHERE / HAVING / ORDER / INSERT·UPDATE 上调用 `rewriteExpr`。`visitFunctionExpr` 没有覆写。结果：

- `JOIN ON IF(...)` / `MERGE ON IFNULL(...)` **不会**改写
- 窗口 `OVER (PARTITION BY IF(...))`、表函数参数、CTE 体内漏改的概率高
- 列 `DEFAULT NOW()` 走 `DefaultValueCoercer`（只处理布尔 0/1），不走函数表

验收：`SQL.convert("SELECT * FROM t JOIN u ON IF(a,1,0)=1", MYSQL, POSTGRES)` 含 `CASE` 不含 `IF(`；`CREATE TABLE t (ts DATETIME DEFAULT NOW())` 转到 PG 为 `CURRENT_TIMESTAMP`。补 `SqlSchemaConverterTest`。

实现建议：`visitJoin` / `visitMerge` / `visitOverExpr` / `visitWithItem` / `visitFunctionTable` 里对子表达式走同一套 `rewriteExpr`；列 DEFAULT 若是函数调用，解析成 `SqlFunctionExpr` 再进注册表。

#### P0.2 `ServiceLoader` 加载两次 ✅

`SqlDataTypeRegistryBuiltins` 与 `SqlFunctionRegistry` 各自 `ServiceLoader.load`，provider **不是同一实例**。`registerTypes` 里设的字段，`registerFunctions` 看不见。文档已警告，但接口把两个方法放在同一类型上，使用者会踩。

验收：抽 `SqlSchemaConverterProviders.loadSorted()` 一次，缓存不可变列表，两张表共用；单测用带字段的 provider 证明 `registerTypes` 之后 `registerFunctions` 能读到。注意 provider 构造器不得碰 `builtins()`（类初始化死锁）。

### 11.3 P1 — 转换能力 ✅（2026-09-12）

都走 `SqlFunctionRegistry.register` / 附录 SQL，**不要**改 `FunctionAstRewriter` 的分派结构（P0.1 的漏节点除外）。

| 项 | 说明 | 验收 |
|---|---|---|
| 日期算术 | `DATE_ADD`/`DATE_SUB`/`DATEDIFF`/`TIMESTAMPDIFF` → PG `+ interval` / `AGE` 或告警保留 | 黄金一句 MYSQL→PG + 目标方言 parse |
| 时间戳 | `FROM_UNIXTIME`/`UNIX_TIMESTAMP`/`STR_TO_DATE`/`TO_DATE` | 同上；格式符不对等要 `SEMANTIC_RISK` |
| Oracle 条件 | `DECODE`/`NVL2` → `CASE` | MYSQL/PG 目标不含 DECODE |
| 字符串 | `SUBSTRING_INDEX`/`FIND_IN_SET` 无干净等价则告警保留，不要装等价 | 有 warning |
| `ALTER` 附录 | PG 已改 `TYPE`；`SET/DROP NOT NULL`、`SET/DROP DEFAULT` 仍丢。源列约束应进 `extraSql` | `MODIFY c INT NOT NULL` → TYPE + `SET NOT NULL` |
| 独立 `CREATE INDEX` | 现只转表内 KEY。`CREATE INDEX … USING BTREE` / `CONCURRENTLY` / `INCLUDE` 原样 format | MYSQL→PG 去掉 `USING BTREE` 或改成 PG 写法 |
| FULLTEXT/SPATIAL | 表内已删除且不生成附录 | 保持；补一句明确 `MANUAL_ACTION_REQUIRED` |

`generateOracleSequence(true)` 已是 opt-in，设计文档第十二节已对齐。

### 11.4 P2 — 实体扫描 ✅（2026-09-12，含 MyBatis / MyBatis-Plus）

已落地（仍零 JPA/MyBatis 编译依赖，反射认 FQCN）：

| 项 | 状态 |
|---|---|
| JPA `@Table.indexes` / `@Index` | ✅ 进 `model.indexes()` → `CREATE INDEX` |
| `@Enumerated` | ✅ ORDINAL→INT，STRING/缺省名→VARCHAR |
| `List`/`Set`/`Map`、`@OneToMany` | ✅ 无 `@SqlColumn`/`@TableField`/`@Column` 时 skip |
| `@Embedded` / `@Embeddable` | ✅ 展开嵌套字段；父类字段本来就扫 |
| `createTables` FK 顺序 | ✅ 被引用表在前 |
| MyBatis-Plus `@TableName`/`@TableId`/`@TableField` | ✅ `exist=false` skip；`IdType.AUTO` 自增 |
| MyBatis `@Alias` | ✅ 仅表名回退（需同时有 `@TableId`/`@SqlId`/`@Id` 才当实体） |

不要引入 Hibernate / Spring / mybatis 包。继续零编译依赖。

### 11.5 P3 — 文档与工程

| 项 | 说明 |
|---|---|
| 中英 `sql.md` | 英文转换/SPI 章节短一截（还停留在 KEY 被 strip，没写附录 INDEX / `registerFunctions`）。对外只维护这两份 + 设计文档，**不要**把 next-plan 链回去 |
| 设计文档 §12 | 与 `generateOracleSequence` 对齐（opt-in 已实现） |
| `SqlDdlStatement.columnDefinitions()` | 仍是 `List<String>`。结构化列是平行通路。**不要**为了好看改公开签名 |
| pretty 缩进 | `SqlFormatter.indent` 是死代码（第 9 节）。有真实需求再做 subquery/CTE/UNION 缩进，勿硬接 |
| lexer 短 ident intern | 仍需 profiling，别凭感觉做 |
| 竞品 gaps ~164 | 长尾 DDL / 乱码 / 负向样例，**按真实用户 SQL 驱动**，不要当迭代 KPI |
| `docs/csv.md` / `docs/expression.md` | 空文件，补齐后从 `srcExclude` 拿掉并进侧边栏 |
| 仓库其它（第 4 节） | HTTP 可实例化客户端、流式 multipart、JWT/RSA 默认值……**SQL 转换 P0/P1 未做完前不要夹带** |

### 11.6 建议开工顺序

P0–P2 已完成。余量：

1. 中英 `sql.md` 转换/SPI 章节仍不完全对称（英文偏短）
2. 其余按用户拿来的真实 SQL 再开（P3 工程项、第 4 节其它模块）

验收命令不变：`mvn -pl jkit-sql test` 必须绿。改解析器时再 `install` 后跑 tools-test。

### 11.7 明确仍不做

- 存储过程 / 触发器 / 视图的跨方言转换（VIEW 内 SELECT 的函数可顺带改，不当目标）
- 跨库数据搬迁、字符集排序结果一致
- 把 MySQL `MODIFY` 自动变成 PG `ALTER COLUMN` **全套**语法（P1 只补 NOT NULL/DEFAULT 附录，不做 USING 表达式推断）
- pairwise 类型表、为新产品改 `SqlDialect` 枚举
- 在 `jkit-sql` 里执行 SQL / 引 JDBC / ORM（执行已放到独立模块 `jkit-sql-auto`，2026-09-12）
- 公开文档或站点再放 next-plan 入口

### 11.8 `jkit-sql-auto` ✅（2026-09-12）

独立模块 `com.alianga:jkit-sql-auto`：启动时扫描实体、对照 `DatabaseMetaData`、执行 CREATE/ALTER。`jkit-sql` 仍不执行 SQL。模式 `none/validate/update/create/create-drop`；默认 UPDATE 只加不删。文档 `docs/sql-auto.md` + 英文。

Spring Boot 2/3 starter：`jkit-sql-auto-spring-boot-2`（`spring.factories`）与 `jkit-sql-auto-spring-boot-3`（`AutoConfiguration.imports`）。`SqlAuto.drop` 显式删表。真库回归在 `tools-test` 的 `LocalDatasourceSqlAutoTest`。

表 / 列注释按方言生成（MySQL/Hive/ClickHouse 内联、H2 列内 + `COMMENT ON TABLE`、PG/Oracle/DB2/ANSI `COMMENT ON`、SQL Server `sp_addextendedproperty`、Presto 表级 `WITH`、SQLite 忽略）。Oracle ≤11g 自增用 SEQUENCE + TRIGGER；达梦列上写 IDENTITY。`CREATE TABLE`（`includeIndexes=false`）不含附录，由 `extraSql` 单独执行。

`SqlDialect.DAMENG` 一等方言：`fromName("dm"/"dameng")`、`jdbc:dm:`。函数改写：`NVL`/`INSTR`/`LISTAGG`/`TO_DATE`/`FROM_UNIXTIME→NUMTODSINTERVAL`。
