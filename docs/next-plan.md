# jkit 后续计划（给后续 AI agent）

本文是当前对话收口时的未完成清单。**先读约束和「已完成」，再按优先级改代码。** 不要重做已落地的模块，不要把第三方库引进 `jkit-sql` / `jkit-core`。

- 仓库：`/opt/workspace/zml/jkit`，父 POM `jkit-parent` **2.0.1**
- 对比测试工程：`/opt/workspace/zml/tools-test`（可以引 Druid / JSqlParser）
- 用户要求：每次回复用 `jkit-notify` SMTP 再发一封到 `mpro@vip.qq.com`（凭证在 `/opt/workspace/zml/z-notify-hub/z-notify.db` 的 `email-aliyun`，收件人历史测试为 `mpro@vip.qq.com`）。发信脚本曾放在 `/tmp/jkit-mail-send/`，不在 git 里。

---

## 0. 硬约束

1. **零第三方依赖**：`jkit-core`、`jkit-sql`、`jkit-notify` 的运行时 `<dependencies>` 不得引入 Druid、JSqlParser、POI、Hibernate Validator 等。JUnit 仅 test。
2. **JDK 8**：无 `var`、`List.of`、`String.isBlank`、switch 表达式。
3. Checkstyle：`checkstyle/check-style.xml`。行宽 160 error / 120 warning；ImportOrder 组 `*,javax,java`；禁止 tab；NeedBraces；字段不要显式赋默认值（`= null` / `= 0` / `= false`）。
4. 公开 API 中文 javadoc，`@author 郑明亮`，新 API `@since 2.0.2`（2.0.1 已发布，不要把未发布能力标成 2.0.1，jkit-sql 除外，这个是新增模块，继续从 2.0.1 开始）。
5. 对比测试、JMH、引入 Druid/JSqlParser **只允许**在 `/opt/workspace/zml/tools-test`，禁止写进 `jkit-sql` 的 POM。
6. 改 SQL 解析器后：`mvn -pl jkit-sql test` 必须绿；再 `mvn -pl jkit-sql,jkit-core install -DskipTests`，然后 `cd ../tools-test && mvn -Dtest=SqlParserCompareTest test`。

---

## 1. 已经完成（不要重做、不要推翻重写）

### 1.1 `jkit-sql`（新模块 `com.alianga:jkit-sql`）

入口 `com.alianga.jkit.sql.SQL`：

`parse` / `parseAll` / `format` / `toSqlString` / `tables` / `stat` / `addLimit` / `andWhere` / `replaceTable` / `parameters` / `isReadOnly`

已实现：

- 手写 lexer（`char[]` + `SqlKeywords` 开地址哈希）+ `ThreadLocal` 复用 `SqlParser`
- 方言：MYSQL（默认，GBase/MariaDB/TiDB 映射过来）、POSTGRES、ORACLE、SQLSERVER、ANSI、H2
- SELECT：JOIN（INNER/LEFT/RIGHT/FULL/CROSS/NATURAL/STRAIGHT/逗号）、UNION 族、WITH/RECURSIVE、DISTINCT ON、LIMIT/OFFSET/FETCH、TOP、FOR UPDATE、LOCK IN SHARE MODE、CONNECT BY / START WITH / PRIOR、`t.*`
- 窗口：`OVER (PARTITION BY … ORDER BY … ROWS/RANGE BETWEEN …)`、命名窗口、SELECT 级 WINDOW、窗口继承、`FILTER (WHERE …)`
- DML：INSERT/REPLACE（VALUES 多行、INSERT SELECT、INSERT SET、ON DUPLICATE KEY、ON CONFLICT DO UPDATE/NOTHING、RETURNING）、UPDATE/DELETE（JOIN、LIMIT、RETURNING）、MERGE 基本形态
- 表达式：CASE、CAST / `::`、IN/BETWEEN/LIKE/ILIKE/REGEXP、`?` / `:name` / `@var`、EXTRACT/TRIM/SUBSTRING/POSITION、行构造 `(a,b) IN ((?,?))`
- DDL：CREATE/DROP/ALTER/TRUNCATE 抽对象名；ALTER ADD/DROP/MODIFY/CHANGE 抽列名；其余进 `SqlDdlStatement.tail`
- SHOW CREATE TABLE / SHOW COLUMNS FROM / SHOW INDEX FROM 抽表名
- 类型字面量：`DATE '2020-01-01'`
- `SqlParseException` 带行号/列号/片段
- 模块内测试：`SqlParserTest` + `SqlGoldenCorpusTest`（约 90+ 条黄金 SQL）

关键文件：

| 路径 | 职责 |
| --- | --- |
| `jkit-sql/src/main/java/com/alianga/jkit/sql/SQL.java` | 门面 |
| `SqlParser.java` | 递归下降，已经很大，优先拆分而不是继续无限膨胀 |
| `SqlLexer.java` / `SqlKeywords.java` / `SqlTokenType.java` | 词法 |
| `ast/*` | AST |
| `SqlFormatter.java` / `SqlRewriter.java` / `SqlSchemaStat.java` | 回写、改写、抽表列 |
| `docs/sql.md` | 用户文档 |

已知对比结果（`tools-test` 的 `SqlParserCompareTest`，49 条，含 WINDOW/继承/LATERAL/APPLY/UNNEST/TABLE/VALUES）：

| | 成功率 | simple ns/op | join | window |
| --- | --- | --- | --- | --- |
| jkit-sql | 49/49 | ~1.7µs | ~2.3µs | ~1.2µs |
| Druid 1.2.23 | 45/49 | ~6µs | ~6.7µs | ~5.8µs |
| JSqlParser 4.9 | 45/49 | ~240µs | ~276µs | ~325µs |

Druid 挂 `DISTINCT ON`、WINDOW 继承、UNNEST；JSqlParser 挂 `LOCK IN SHARE MODE`、`[dbo].[user]`、WINDOW 继承。吞吐是 warmup 后 2 万次墙钟，**不是 JMH**。

### 1.2 本仓库其它已有能力（不要当成新需求）

HTTP（含 SSE merge、curl 执行、负载均衡、Nacos）、JSON、YAML、配置、CSV、表达式、notify、curl-codegen。详见 README。

---

## 2. `jkit-sql` 待完善（主战场）

按对用户价值排序。每条都写了验收标准，做完在本文件对应条目标「完成」并补测试。

### P0 — 正确性与可维护性（先做这些）

#### P0.1 拆 `SqlParser.java`

现状单文件过大（1500+ 行）。建议拆：

- `SqlParser`：语句分发 + WITH
- `SqlSelectParser` / `SqlDmlParser` / `SqlDdlParser`
- 表达式继续留在 `SqlParser` 或抽 `SqlExprParser`（共享 lexer/token 状态，用包内可见字段或把 lexer 传入）

验收：`mvn -pl jkit-sql test` 全绿；公开 API 不变。

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
| ALTER CHANGE 全列定义 / ADD CONSTRAINT | 未做（仍 tail / 列名） |
| GRANT / SHOW CREATE VIEW/DATABASE | 未做（本 pass 未扩） |
| WITHIN GROUP order-by | 可选，本 pass 未做（仍 `aggOption` 字符串） |
| CREATE TABLE 表级 FOREIGN KEY 引用表 | 未做 |

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
- Oracle `(+)` 外连接（本 pass 跳过）
- MySQL `PARTITION (p0, p1)` 表分区限定（本 pass 跳过）

#### P1.3 函数与表达式

- `GROUP_CONCAT` / `STRING_AGG` 的 `ORDER BY` / `SEPARATOR`
- `IF(a,b,c)`（MySQL，`IF` 是关键字，确认函数调用路径）
- `CONVERT(expr USING charset)` vs SQL Server `CONVERT(type, expr)`
- `JSON_EXTRACT` / `->` `->>` 已有 JSON_OP，补 `#>` `#>>` 与路径字面量
- `MATCH (cols) AGAINST (...)` 全文
- `IS DISTINCT FROM`（PG）
- 数组 `col[1]`、PG `ANY(array)`
- 类型字面量补 `INTERVAL '1 day'` 与 `X'FF'`（lexer 已有 HEX）

#### P1.4 DML 边角

- Oracle `INSERT ALL` / `INSERT FIRST`
- PG `INSERT … SELECT … ON CONFLICT`
- `UPDATE … FROM`（PG）
- `DELETE … USING`（PG，MySQL USING 已有一部分）
- MERGE：多个 `WHEN MATCHED AND <pred>`、`WHEN NOT MATCHED BY SOURCE`
- `OUTPUT INSERTED.*`（SQL Server）

#### P1.5 DDL / 过程（解析能过 + 抽对象名即可，不必执行）

- `CREATE VIEW` / `CREATE OR REPLACE VIEW`（CREATE 已有 AS query，核对 VIEW）
- `CREATE PROCEDURE` / `FUNCTION` / `TRIGGER` / `EVENT`：解析到名字后可 tail，但 **不要抛 unsupported**
- `BEGIN … END`、`DECLARE`：可作为 `SqlSimpleStatement.OTHER` 吃到匹配 END，避免批处理第一句就挂
- `CALL proc(a,b)` 参数进 AST（现在 CALL 只到名字）
- `ANALYZE` / `VACUUM` / `OPTIMIZE TABLE` / `REPAIR` / `CHECK TABLE`（词法已有部分关键字）
- `COMMENT ON TABLE/COLUMN`（COMMENT 是关键字）
- `USE` 已有；补 `GO`（SQL Server 批分隔）可当分号

#### P1.6 注释与提示

- MySQL 可执行注释 `/*!40101 SET … */`：应展开为内部 SQL，而不是整段丢掉
- 优化器 hint `/*+ INDEX(t idx) */`：挂到 SELECT/表上，format 可输出
- 解析器可选 `keepComments`（默认 false，避免热路径变慢）

#### P1.7 方言矩阵（先 MYSQL/PG，再 Oracle/SQLServer）

不要一上来做 Hive/ClickHouse/ODPS（Druid 有 30 种方言，那是深坑）。

缺的方言行为：

- MYSQL：`||` 已当 OR；补 `PIPES_AS_CONCAT` 作为 parse 选项
- PG：`RETURNING *`、`ILIKE`、`::` 已有；补 `RETURNING` 多列列表、`ON CONFLICT ON CONSTRAINT name`
- Oracle：`DUAL`、`ROWNUM`、`CONNECT BY` 已有；补 `FETCH FIRST n ROWS ONLY` 与 `MINUS`
- SQL Server：`TOP`、`[]` 已有；补 `OUTPUT`、`APPLY`
- 达梦/GBase：继续映射到 ORACLE/MYSQL，特殊函数随业务 SQL 加，不要预先发明方言枚举

### P2 — 能力对标（Druid 常用 Visitor）

均保持零依赖，API 放 `SQL` 门面。

| 能力 | 对标 | 说明 |
| --- | --- | --- |
| 参数化归一 | Druid `ParameterizedOutputVisitor` | `SQL.parameterize(sql)` → 字面量变 `?`，便于 SQL 统计去重 |
| 导出参数值 | `ExportParameterVisitor` | 与 `parameters()` 不同：要抽出 `'a'` / `1` 这些字面量 |
| 注入/危险操作 | `WallFilter` 子集 | 检测多语句、注释绕过、永远真条件、`SLEEP`、无 WHERE 的 DELETE/UPDATE。默认关闭，显式 `SQL.wall(sql)` |
| 表达式求值常量折 | `EvalVisitor` 子集 | 仅字面量算术/比较，不要反射 |
| AST clone | JSqlParser `DeParser` 配套 | `statement` 深拷贝，改写不要污染原树（`addLimit` 现在是就地改） |
| 按类型 Visitor | Druid `visit(SQLSelect)` | 现在只有 `visit(SqlNode)` + instanceof。可保留现接口，另加 `SqlAstVisitor` 带具体类型，避免破坏已有 Adapter |
| 列改写 | — | `SQL.replaceColumn(stmt, from, to)` 对称 `replaceTable` |

`addLimit` 就地修改应改成 **clone 后再改**，或文档+测试写明；推荐 clone，避免 `parse` 结果被调用方改掉后无法复用。

### P3 — 工程与性能

- **JMH 对比**：在 `tools-test` 加 JMH（该工程已有 jmh 也可放到 `jmh-test`）。测 simple / join / window 三条，fork≥2，不要用墙钟 for 循环当正式数字。
- **扩 corpus**：从业务日志抽 200～500 条真实 SQL 放到 `tools-test/src/test/resources/sql-corpus.txt`（一行一条，`#` 注释）。统计三家成功率。**不要**把 Druid 测试 jar 拷进 jkit。
- Lexer：关键字哈希已无字符串分配；profile 一下 `new String` 在 ident 上的占比，必要时对短 ident intern。
- `SqlFormatter` 的 `dialect` 字段已保存但几乎没用：按方言输出反引号 / 双引号 / `[]`，`||` 语义。
- `parseAlias(boolean inFrom)` 的 `inFrom` 已不再使用，删参数或真正用起来。
- 多语句：一条失败时现在整批抛错。可选 `SQL.parseAll(sql, dialect, true)` 容错，失败的记 `SqlSimpleStatement` + 错误，继续下一条（审计场景有用）。
- 发布：父版本仍是 2.0.1。`jkit-sql` 若要发 Central，应随 **2.1.0** 一起发，不要用已发布的 2.0.1 坐标抢发（artifactId 虽新，但和 BOM/文档版本会乱）。发布前补 `@since`、README 版本号。

---

## 3. 对比测试工程（`/opt/workspace/zml/tools-test`）

已加依赖：`jkit-sql:2.0.1`、`druid:1.2.23`、`jsqlparser:4.9`。  
测试类：`com.alianga.test.sql.SqlParserCompareTest`。

后续可做：

1. JMH 正式吞吐（见 P3）
2. corpus 文件化，失败 SQL 写入 `target/sql-compare-fail.txt` 方便 diff
3. 表名集合对比（忽略库名前缀和大小写），输出「仅 jkit 有 / 仅 druid 有」
4. 改完解析器后必须：`mvn -pl jkit-sql,jkit-core install -DskipTests` 再跑 tools-test，否则会用到旧的本地 2.0.1
5. 不要把 tools-test 的 POM 改回 jkit 2.0.0

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
2. P0.2 往返测试 ✅。
3. P0.3 SchemaStat 条件/多访问类型 ✅。
4. P0.4 ALTER/CREATE/FOR UPDATE 验收最小集 ✅；余量见 P0.4 表（CONSTRAINT/CHANGE/WITHIN GROUP/FOREIGN KEY）。
5. P1.1 WINDOW 子句 + P1.2 APPLY/LATERAL（缺了就会在业务 SQL 上直接 parse 失败）。
6. 把失败 SQL 追加进 `SqlGoldenCorpusTest` 和 `SqlParserCompareTest` 的 CORPUS。
7. P0.1 拆 Parser（行为稳定后再拆，避免和语法扩展搅在一起）。
8. 有余力再 P2 parameterize / wall，以及 tools-test JMH。

每完成一块：补 `@since 2.1.0`、更新 `docs/sql.md` 覆盖表、在 `CHANGELOG.md` 的 `2.1.0 - unreleased` 追加条目。不要把父 POM 版本改成 2.1.0，除非用户明确说要发版。
