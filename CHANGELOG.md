# 版本更新说明

本文记录 jkit 各版本的用户可见变更。新版本一律追加到「版本记录」最上方，不要改写已发布小节。

## 如何记录后续版本

发布（或准备发布）新版本时按下面做：

1. 把根 `pom.xml`（`jkit-parent`）的 `<version>` 改成新版本号，子模块继承该版本；同步 `README.md` 页头与依赖示例、`Main.java` 打印的版本。
2. 在本文「版本记录」**最上方**插入新版本小节，日期用当天（`YYYY-MM-DD`）。
3. 按「新增 / 变更 / 修复 / 构建」分类写清调用方能感知的变化，不要只贴 git 标题。
4. 本次新增的公开 API 在 javadoc 里补 `@since x.y.z`。
5. 历史版本（如 2.0.0 的 ZmlTools 迁移说明）留在原处，不要把旧条目改写成新版本。

模板：

```markdown
## x.y.z - YYYY-MM-DD

### 新增
- ...

### 变更
- ...

### 修复
- ...

### 构建
- ...
```

没有某一类变化时省略对应小标题即可。

## 版本记录

## 2.0.1 - 2026-09-09

新模块 `jkit-sql` 随父 POM **2.0.1** 一并交付（同版本号下的新增 artifact；下方保留 2026-09-01 的历史 `2.0.1` 小节）。

### 新增

- `jkit-sql`：**方言扩展**——新增一等枚举 `DB2`（仅 FETCH FIRST 分页）、`SQLITE`（LIMIT 族无 FETCH）、`HIVE`（反引号/拼接/双引号字符串，别名 maxcompute/odps）、`CLICKHOUSE`（反引号、双引号也是标识符、逗号 LIMIT）、`PRESTO`（别名 trino）；`fromName` 增补国产与主流别名：GoldenDB/SelectDB/AnalyticDB(ads)/MatrixOne/StoneDB→MYSQL，HighGo/UXDB/MogDB/Vastbase/AntDB/IvorySQL→POSTGRES。能力全部经 `SqlDialectSpec` 方法驱动，无散落 `== SqlDialect.X` 判断。
- `jkit-sql`：方言别名对齐 icell `common-model` 数据源类型（`DbTypeEnum`）——`argo`/`argodb`→HIVE（ArgoDB 走 Transwarp Hive JDBC）、`xcloud`→POSTGRES（行云：双引号标识符 + LIMIT/OFFSET）、`gbase8a`→MYSQL（GBase 8a MPP 兼容）、`gbase8s`→SQLITE（GBase 8s：双引号、LIMIT、无 FETCH）；common-model 全部 SQL 型数据源（MYSQL/OceanBase/POSTGRESQL/GAUSSDB/SQLSERVER/DAMENG/HIVE/ARGO/GBASE8S/GBASE8A/ORACLE/XCLOUD/OSCAR/SQLITE）至此均有映射。

### 修复

- `jkit-sql`：**竞品测试集驱动的覆盖率提升**（druid-bvt-inline 85.3→87.2%、jsqlparser-inline 73.1→76.7%、jsqlparser-files 66.7→73.7%，jkitGaps 1398→1178）——`INTERVAL` 复合单位（`HOUR_MINUTE`/`YEAR_MONTH` 等 11 种 + `QUARTER`）与表达式值（`INTERVAL 6/4 HOUR_MINUTE`）；聚合 `count(UNIQUE …)`（等同 DISTINCT）；`WEIGHT_STRING(… AS CHAR(n) LEVEL n [DESC])`（特殊尾段原文保留）；`IS [NOT] UNKNOWN`；SQL/JSON 构造器（`json_object`/`json_array`/`json_objectagg`/`json_arrayagg`/`json_table` 专用文法整体原文保留，内部绑定不进 `parameters()`）；`UNNEST … WITH ORDINALITY`（`SqlFunctionTable.withOrdinality`）；DDL 吞咽：`CREATE TYPE … AS OBJECT/VARRAY/ENUM`（AS 仅在 TABLE/VIEW 时当查询）、`DROP … PURGE` / `DROP TABLESPACE … ENGINE`、`TRUNCATE … PURGE SNAPSHOT LOG` 尾段原文；CAST 类型后缀 `CHARACTER SET` / `ARRAY`（MySQL 8 索引表达式）；`TRANSLATE(… USING CHAR_CS)`（复用 CONVERT 路径）。
- `jkit-sql`：**DML 修饰符回写保真**（tools-test 新增 `SqlRoundTripFidelityCorpusTest`：379 条全量语料批量归一化逐字比对，5 条有据白名单）——`STRAIGHT_JOIN` 在分支条件里被 `match` 提前消费、类型判断永远落空，回写成普通 `JOIN`（连接顺序约束静默丢失）；`INSERT/UPDATE/DELETE` 的 `IGNORE`、INSERT/UPDATE 的 `LOW_PRIORITY`、INSERT 的 `HIGH_PRIORITY`、SELECT 的 `HIGH_PRIORITY` / `SQL_CALC_FOUND_ROWS` 由「吞掉不存」改为入 AST 并回写（新 API：`SqlInsert.ignore/lowPriority/highPriority`、`SqlUpdate.ignore/lowPriority`、`SqlDelete.ignore/lowPriority/quick`、`SqlSelect.highPriority/calcFoundRows`）；非裸标识符别名（如 `'-- a'`）回写强制加引号，此前回写成 `AS -- a`，二次解析把别名当注释丢掉。
- `jkit-sql`：**回写保真收口**（新增 `SqlRoundTripFidelityTest`，66 条坑位语料，回写文本与原文**归一化后逐字比对**，只容忍纯排版差异）——修 `NATURAL LEFT/RIGHT/FULL/INNER JOIN` 回写丢修饰符（`LEFT` 静默丢失致外连接变内连接、`FULL` 被误当表名）；`RENAME TABLE a TO b[, c TO d]` 升为独立语句类型（此前回写成非法 `ALTER TABLE a TO b`，后续组静默丢失）；`ALTER TABLE … ADD UNIQUE KEY|INDEX` 回写丢 `UNIQUE`（唯一索引变普通索引）。
- `jkit-sql`：解析失败补齐 — MySQL `DROP INDEX idx ON t`；SQL Server 表提示 `WITH (NOLOCK)` / `WITH (INDEX(ix))`（别名前后均可，原文保留）；PG 数组构造 `ARRAY[1,2,3]`（含 `ANY(ARRAY[...])`）；`CREATE/DROP USER 'u'@'%'`（账号原文 `userSpec`，引号不再被改写）。
- `jkit-sql`：`tables()` 精度 — 补漏：`CREATE TRIGGER … ON t` 漏真实表、`CREATE TABLE t2 LIKE t1` 漏源表、`ALTER TABLE t RENAME TO t2` 漏目标表、`OPTIMIZE/ANALYZE/CHECK/REPAIR TABLE t, s` 只取首表；清误：库名（`USE db`）、例程/索引/事件对象名、`CALL sp` / `DECLARE` / `GRANT` 目标、CTE 名（`WITH w AS (…) SELECT FROM w`）不再计入。
- `jkit-sql`：回写格式 — 类型参数紧凑逗号（`DECIMAL(10,2)`，不再 `DECIMAL(10 , 2)`）；`GRANT SELECT, INSERT`；`TRUNCATE TABLE` 统一补 `TABLE`；`'u'@'%'` 紧凑无空格。
- `jkit-sql`：ClickHouse 参数化函数 `fn(params)(args)`（如 `windowFunnel(n)(...)`）；Oracle `MODEL` / `MATCH_RECOGNIZE` / `AS OF TIMESTAMP|SCN`；SQL Server `FOR SYSTEM_TIME AS OF`（表级时态子句）。
- `jkit-sql`：`MODEL` / `MATCH_RECOGNIZE` 升级为结构化 AST（`SqlModelClause` / `SqlMatchRecognize` / `SqlNamedExpr`），保留 `raw` 往返；Formatter / Visitor 同步；`Complex100GiantsTest` 断言结构化字段。
- `jkit-sql`：`MATCH_RECOGNIZE` 解析 `SUBSET name=(a,b,…)`（`SqlSubset`）；`MODEL RULES` 拆为 `SqlModelRule` 条目（`cell[…]=expr`，UPSERT 修饰；失败保留 raw）；`PATTERN`/`DEFINE` 保持既有字段。
- `jkit-sql`：`SqlWallConfig` 可配置增强（denyDdl / 危险函数 / INTO OUTFILE / 可选 denyUnion·information_schema / selectOnly）；`SQL.wall(sql, dialect, config)` 重载；默认保持安全关键检查。
- `jkit-sql`：CREATE PROCEDURE/FUNCTION 参数结构化（`SqlRoutineParam`）与 BEGIN 体 `bodyStatements`（尽力解析 SELECT/INSERT/SET 等；IF/WHILE 等进 OTHER）；保留 `bodyRaw`/`tail` 往返。
- `jkit-sql`：过程体 `IF`/`WHILE`/`LOOP`/`REPEAT` → `SqlControlStatement`（条件+bodyStatements；ELSEIF/ELSE）；`FUNCTION RETURNS` → `returnsType`；`TRIGGER` 抽 `triggerTiming`/`triggerEvent`/`triggerTable` 与 `bodyStatements`；`EVENT` DO 体进 bodyStatements。
- `jkit-sql`：过程 `CASE … END CASE` / `LEAVE` / `ITERATE` / `RETURN [expr]` / 循环标签 `lab: LOOP`；`TRIGGER` 补 `FOR EACH` / `FOLLOWS|PRECEDES`；`EVENT ON SCHEDULE AT|EVERY`；`MATCH_RECOGNIZE` 抽 `WITHIN`（`ROWS PER MATCH`/`AFTER MATCH` 保持字段）；`MODEL` 多维 cell 拆 `cellDims`。
- `jkit-sql`：过程体 `DECLARE`/`CURSOR FOR`/`CONTINUE|EXIT|UNDO HANDLER` → `SqlDeclareStatement`/`SqlHandlerStatement`；`TRIGGER UPDATE OF` → `triggerUpdateColumns`；`EVENT` 抽 `eventStarts`/`eventEnds`/`eventEnabled`/`eventComment`；`MODEL` 简单维 `cellDimExprs`；`MATCH_RECOGNIZE.PATTERN` 保持字符串。
- `jkit-sql`：`EXPLAIN` / `DESCRIBE` → `SqlExplainStatement`（analyze / format / options + 嵌套 statement / name）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`SET` 多赋值 / `SET NAMES` / `SET CHARACTER SET` / SESSION|GLOBAL → `SqlSetStatement`（scope / setKind / assignments + raw）；Formatter / Visitor 同步。
- `jkit-sql`：PG/Oracle `COMMENT ON TABLE|COLUMN|INDEX|… IS …` → `SqlCommentOnStatement`（objectKind / name / comment + raw）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`SHOW CREATE TABLE|VIEW|DATABASE|…` / `SHOW COLUMNS|INDEX|TABLES` → `SqlShowStatement`（showKind / objectType / name / fromOrIn + raw）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`ANALYZE` / `VACUUM` / `OPTIMIZE|REPAIR|CHECK TABLE` → `SqlMaintenanceStatement`（kind / tables / optionsRaw + raw）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`COMMIT` / `ROLLBACK [TO [SAVEPOINT] name]` / `SAVEPOINT` / `RELEASE SAVEPOINT` → `SqlTransactionControlStatement`（kind / savepoint / toSavepoint + raw）；Formatter / Visitor 同步。
- `jkit-sql`：PG `COPY table[(cols)] FROM|TO {STDIN|STDOUT|PROGRAM|filename}` → `SqlCopyStatement`（表/列/方向/源 + WITH 原文）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`FLUSH [LOCAL|NO_WRITE_TO_BINLOG] option[,…]` → `SqlFlushStatement`（PRIVILEGES/LOGS/STATUS/TABLES[tbl…] 等选项列表 + raw 残余）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`START TRANSACTION` / `BEGIN [WORK|TRANSACTION]` → `SqlStartTransactionStatement`（ISOLATION LEVEL / READ WRITE|ONLY / WITH CONSISTENT SNAPSHOT / [NOT] DEFERRABLE）；过程块 `BEGIN … END` 仍为 `SqlBlockStatement`；Formatter / Visitor 同步。
- `jkit-sql`：`LOAD DATA [LOCAL] INFILE` → `SqlLoadDataStatement`（文件/表/列、可选 FIELDS·LINES·IGNORE 原文段）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`LOCK TABLES`/`UNLOCK TABLES` → `SqlLockTablesStatement`（表清单 + READ/WRITE/LOCAL 等锁模式）；Formatter / Visitor / SchemaStat 同步。
- `jkit-sql`：`PREPARE`/`EXECUTE`/`DEALLOCATE PREPARE`/`EXECUTE IMMEDIATE` → `SqlPrepareStatement`（名、FROM/源表达式、USING 绑定）；Formatter / Visitor 同步。
- `jkit-sql`：顶层匿名 `DECLARE … BEGIN … END` / 裸 `BEGIN … END` → `SqlBlockStatement`（`declares`/`bodyStatements`）；会话式 `DECLARE x INT` 仍为 `SqlSimpleStatement`；MySQL 表 `HANDLER t OPEN|READ|CLOSE` → `SqlTableHandlerStatement`（表/操作/可选 WHERE·LIMIT）；`EVENT` 抽 `eventOnCompletion`（PRESERVE/NOT PRESERVE）与 `eventDisableOnSlave`；`MATCH_RECOGNIZE.PATTERN` 仍保持字符串（未建 DSL 树）。

- `jkit-sql`：点号后单引号名可作引用标识符（`T.'Group'`）；`INTERVAL` 仅吸收时间单位（不吞 `OR`/`AND`）；聚合内 `ORDER BY`（`ARRAY_AGG(x ORDER BY y)`）；`PIVOT`/`UNPIVOT`；Hive `LATERAL VIEW` / `DISTRIBUTE BY` / `SORT BY` / `CLUSTER BY`；`GROUP BY GROUPING SETS|CUBE|ROLLUP(...)`；Oracle `ORDER SIBLINGS BY`。

- `jkit-sql`：修复词法 `peek()` 污染当前记号（`next()` 消费预读后应拷贝到 `tokA`），使 `date()`/`datetime()`/`DATE()`/`MIN(Date)`/`IIF(timestamp=…)` 等可解析；允许词形式 `PERCENT` 作标识符（与 `%` 运算符共用 token）；支持 SQLite `trim(X, Y)` 逗号多参。

- `jkit-sql`：算术仅认符号 `-`/`%`（`isSymbolOp`），词形式 `MINUS` 可作集合运算；Oracle 聚合 `KEEP (DENSE_RANK …)` 可解析。- `jkit-sql`：限定名中点号后的数字开头标识符可解析（如 `t.1_id` / `test.52_user` / `a.32强国`）；`.5` / `.52e1` 等前导小数仍为 NUMBER，裸 `1_id` 行为不变。

- `jkit-sql`：SELECT 列表别名支持点号限定名（`AS a.b`，含无 AS 隐式形式）。- `jkit-sql`：`SqlBuilder.limit/offset` 按**有效方言**生成分页——`toSql(dialect)` 的参数覆盖 builder 方言；经典 `ORACLE` 走 ROWNUM 包装（同 `SQL.setPage`），`ORACLE12`/`SQLSERVER` 走 `OFFSET/FETCH`，MySQL 仍 `LIMIT`；修复原先一律输出 `LIMIT offset,count` 的问题。

- `jkit-sql`：PostgreSQL `::type` 紧绑定于主键表达式（`COUNT(*)::numeric / 2`）；`INTERVAL '30 minutes'` 字面量；`WITHIN GROUP` 可出现在 `OVER` 之前；SQL Server `OPTION (...)` 查询提示。- `jkit-sql`：`getLimit`/`getOffset`/`setPage` 识别并改写 Oracle ROWNUM 双层包装与 SQL Server `row_number` 边界（对齐 common-model `PagerUtils`）；UNION 分页作用于集合运算链末端；括号 UNION 后置 `ORDER BY` 可解析。
- `jkit-sql`：`CREATE TABLE` format 往返保留列类型/约束原文（`columnDefinitions`，不再只回写列名）；`GRANT` 收件人 `'u'@'%'` / `u@localhost` 不再被 raw 拼接拆成 `'u' @ '%'`。
- `jkit-sql`：仅注释/空白输入不再抛 `empty SQL`，归为 `SqlSimpleStatement.OTHER`（空 text）。
- `jkit-sql`：PostgreSQL `@>` / `<@` 不再被词法误判为 `VARIABLE`；`~` / `~*` / `!~` / `!~*` 按方言解析为正则比较（MySQL 仍保留一元 `~`）。
- `jkit-sql`：MySQL `FORCE/USE/IGNORE INDEX FOR JOIN|ORDER BY|GROUP BY (...)` 不再误吞进 `FOR UPDATE`。


### 变更

- `docs/sql.md` / `docs/next-plan.md`：对齐语料成功率（379/100%、黄金集约 216）、JMH 说明；§6 去掉已完成的 P0.1/列定义回写/表名差分等过时项。
- `jkit-sql`：P0.1 重构 — 拆分 `SqlParser` 为 `SqlSelectParser` / `SqlDmlParser` / `SqlDdlParser` / `SqlExprParser`（包内协作共享记号游标）；公开 API 与语法行为不变。
- `jkit-sql`：**破坏性** — `SQL.andWhere` / `replaceTable` / `replaceColumn` 改为与 `addLimit`/`setPage` 一致的 clone-then-mutate（返回新 AST，不污染原树）；调用方须使用返回值。
- `jkit-sql` P1.7 方言矩阵：`SqlParseOptions.pipesAsConcat`（MySQL `||` 改拼接）；PG `RETURNING` 多列列表（`SqlListExpr`，format 无外层括号）；Oracle `FETCH FIRST n ROWS ONLY` 保留 `SqlLimit.fetchStyle` 并按 FETCH 回写；验收已有 `ON CONFLICT ON CONSTRAINT`、`MINUS`、SQL Server `OUTPUT`/`APPLY`、达梦→ORACLE / GBase→MYSQL。未发明 Hive/ClickHouse/ODPS 方言。
- `jkit-sql` P1.6 注释与提示：MySQL 可执行注释 `/*!40101 … */` 展开为内部 SQL；优化器 hint `/*+ … */` 挂到 `SqlSelect.hints` / `SqlTable.optimizerHint` 且 format 可输出；`SqlParseOptions.keepComments`（默认 false）保留普通注释到 `SqlStatement.comments()`。
- `jkit-sql` P1.5 DDL / 过程：`CREATE VIEW` / `CREATE OR REPLACE VIEW`（`SqlDdlStatement.orReplace`）；`CREATE PROCEDURE` / `FUNCTION` / `TRIGGER` / `EVENT` 抽对象名，参数与过程体进 `tail`（BEGIN/END 内允许分号，识别 `END IF`/`END CASE` 等）；`BEGIN … END` / `DECLARE` 为 `SqlSimpleStatement.OTHER`；`CALL proc(a,b)` 实参进 AST（`arguments`/`withArguments`）；`ANALYZE` / `VACUUM` / `OPTIMIZE|REPAIR|CHECK TABLE`、`COMMENT ON TABLE/COLUMN`；SQL Server `GO` 批分隔（同分号）。
- `jkit-sql` P1.4 DML 边角：Oracle `INSERT ALL` / `INSERT FIRST`（`SqlInsertBranch`）；PG `INSERT … SELECT … ON CONFLICT`（含 `ON CONSTRAINT`）；`UPDATE … FROM`；`DELETE … USING`（PG，MySQL USING 多表删除保留）；MERGE 多个 `WHEN MATCHED AND <pred>` / `WHEN NOT MATCHED BY SOURCE|TARGET`（`SqlMergeWhen`）；SQL Server `OUTPUT INSERTED.*` / `DELETED.*`（INSERT/UPDATE/DELETE/MERGE）。format 往返；MERGE 无表 INSERT 不再误写 `INSERT INTO (`。
- `jkit-sql` P1.3：`GROUP_CONCAT` / `STRING_AGG`（`ORDER BY` / `SEPARATOR` / `WITHIN GROUP`）；`IF(a,b,c)`；`CONVERT(expr USING charset)` 与 SQL Server `CONVERT(type, expr)`；JSON `#>` `#>>`（与 `->` `->>` 同为 JSON_OP，format 保留原文）；`MATCH (...) AGAINST (...)`；`IS [NOT] DISTINCT FROM`；数组下标 `arr[1]`（非 SQL Server 方言下 `[` 不再当标识符引号）；`ANY`/`SOME`/`ALL` 子查询参数；`INTERVAL '1 day'` / `INTERVAL 1 DAY` 与 `X'FF'` 字面量往返。
- `jkit-sql` P1.2+：WINDOW 继承另一窗口名（`WINDOW w2 AS (w)` / `w2 AS (w ORDER BY …)`，`SqlOverExpr.existingWindowName`）；`UNNEST(...)` / 一般表函数 / `TABLE(fn(...))`（`SqlFunctionTable`，可 `LATERAL`）；`FROM (VALUES …) AS v(cols)`（`SqlValuesTable` + `SqlTableSource.columnAliases`）；顶层 `VALUES` 回写不再误加 `SELECT`。
- `jkit-sql` P1.1：SELECT 级 `WINDOW w AS (PARTITION BY … ORDER BY …)`（可多个）；保留内联 `OVER (…)` 与 `OVER w`；format 往返。
- `jkit-sql` P1.2：`LATERAL` 子查询（`SqlSubqueryTable.lateral`）；SQL Server `CROSS APPLY` / `OUTER APPLY`。
- `jkit-sql` P0.2：`parse → format → parse` 语义往返。修 `CREATE INDEX … ON t (cols)`、`SHOW COLUMNS/INDEX/CREATE TABLE`、`SET NAMES`（无等号）、`EXTRACT`/`TRIM`/`SUBSTRING`/`POSITION` 的 FROM/FOR/IN 回写；`SqlGoldenCorpusTest` 每条断言 type / tables（忽略大小写）/ isReadOnly。
- `jkit-sql` P0.3：`SqlSchemaStat.getConditions()` / `getOrderByColumns()` / `getGroupByColumns()` 从 WHERE、JOIN ON、HAVING、ORDER BY、GROUP BY 收集紧凑 SQL；`getTables()` 改为 `Map<String, SqlTableAccess>`，同表可合并多种访问类型（`INSERT INTO t SELECT * FROM t` → `INSERT+SELECT`；嵌套 SELECT 记读）。
- `jkit-sql` P0.4（验收最小集）：`FOR UPDATE OF … NOWAIT/SKIP LOCKED` 结构化（`forUpdateOf` + `forUpdateWait`）；CREATE TABLE `ENGINE` / `CHARSET` / `COLLATE` / `COMMENT` 进 AST（PARTITION 等仍 tail）；ALTER `ADD/DROP INDEX`、`RENAME TO` 结构化并可 format 回写。
- 解析增强：`SELECT` 字符串别名（MySQL 下 `"别名"` / `'x'`）、`INSERT INTO TABLE t`（Hive 风格）。


### 新增
- `jkit-sql`：改写规则链 — `SqlRewriteHook`（函数式接口：收当前语句、返回继续传递的语句，可就地改或整体替换）+ `SqlRewrites`（`create()`/`none()`/`add()` 有序链，内建改写的规则适配器 `addLimit`/`setLimit`/`setOffset`/`setPage`/`andWhere`/`replaceTable`/`replaceColumn` 可与自定义规则任意混排，排在内建之前即"前 hook"、之后即"后 hook"）+ 门面 `SQL.rewrite(stmt, chain)`（先深拷贝再改，原 AST 不受影响；规则返回 null 抛 `IllegalArgumentException`）。既有 `SqlRewriter` 静态方法行为零变化。
- `jkit-sql`：格式化关键字大小写策略 — `SqlFormatOptions.keywordCase(SqlKeywordCase)`（`AS_IS` 默认 = 原生输出 / `UPPER` / `LOWER`），`SQL.format` / `SQL.toSqlString` 的 options 重载直接生效；`SqlFormatter` 内 87 处关键字输出统一收敛到 `kw()`（含 84 处散落的 `out.append("KEYWORD")` 直写与二元运算符 `symbol()`、FLUSH 选项、事务 kind 变量路径），AS_IS 下输出逐字节不变；不影响标识符、字符串字面量与 raw 直通原文。
- `jkit-sql`：语句解析注册表 — `SqlStatementParsers`（`SqlParseOptions.statementParsers()`，默认关闭）按前导关键字注册自定义 `SqlStatementParser`，兜住内建分派未覆盖的语句（如 `BACKUP …` / `SIGNAL …`）；配套 `SqlParseContext` 解析上下文（token / next / is / match / isIdent / matchIdent / name / dialect / atStmtBreak / consumeRest / error），`consumeRest()` 返回**原文切片**（保留原始间距）；自定义解析器返回 null 或留下未消费记号会得到带位置的明确错误；内建语句（SELECT / INSERT / CREATE 等）不受影响。
- `jkit-sql`：SqlWall 规则 SPI — 语句级检查重构为 `List<SqlWallRule>` 规则链（内置 5 条：selectOnly / denyDdl / 无 WHERE 写 / SELECT 特性 / AST 扫描，行为与违规码不变）；新增 `SqlWallRule` 接口、`SqlWallViolations` 违规码收集器（去重）与 `SqlWallConfig.rules(SqlWallRule...)` 注册口——自定义规则在全部内置检查之后按注册顺序执行，新增检查项不再需要改 `SqlWall` 本体。
- `jkit-sql`：方言能力可覆写 — 新增 `SqlDialectSpec` 接口（全部方言能力方法的单一规约，未覆写处默认 ANSI 基线）与 `SqlDialectWrapper` 包装层（委托基方言、按需覆写单个能力：如 MySQL+ANSI_QUOTES、NO_BACKSLASH_ESCAPES、关闭逗号分页）；`SQL.parse*/parseExpr/format/toSqlString/setPage/setLimit/setOffset/addLimit/wall/concat/clone/parameterize`、`SqlParser.reset`、`SqlLexer.reset`、`SqlFormatter`、`SqlBuilder`、`SqlRewriter`、`SqlParameterizer` 的方言参数统一放宽为 `SqlDialectSpec`（传枚举的调用点源码兼容）；新增能力方法 `backslashEscapes()` / `bracketIdentifiers()` / `supportsTildeRegex()` / `supportsCommaLimitOffset()`，替换词法（方括号标识符、反斜杠转义）、表达式（`~` 正则）、改写（逗号分页）中 4 处散落的 `dialect == SqlDialect.X` 判断。
- `jkit-sql`：回写保真新增公开 API（均 `@since 2.0.1`）— `SqlJoin.natural()`（`NATURAL` 与连接类型正交的独立标志）；`SqlTable.withHint()`（SQL Server `WITH (…)` 表提示原文）；`SqlFunctionExpr.arrayConstructor()`（PG 数组构造，方括号回写）；`SqlDdlStatement.likeTable()`（`CREATE TABLE … LIKE` 源表）/ `userSpec()`（`CREATE/DROP USER` 账号原文）；语句类型 `SqlStatementType.RENAME`。
- `jkit-sql`：拼接/回写可开关标识符引号 — `SqlFormatOptions.quoteIdentifiers`（默认 false）；`SqlFormatter`/`SQL.toSqlString`/`SQL.format` 重载；`SqlBuilder.quoteIdentifiers(boolean)` 可开可关；开启后按方言强制引用（MySQL `` ` ``、PG/Oracle `"`、SQL Server `[]`），不影响字面量/关键字/`*`。
- `jkit-sql`：新增 `SQL.parseExpr` / `SQL.parseExpr(expr, dialect)` / `SQL.parseExpr(expr, dialect, options)`，经 `SqlParser.parseExpression()` 解析裸表达式为 `SqlExpr`（须 EOF；支持方言与占位符选项）；`andWhere` / `SqlBuilder.parsePredicate` 改为走 `parseExpr`。
- `jkit-sql`：补 Joplin 实语法缺口 — `LOCK TABLES` / `UNLOCK TABLES`（OTHER + 抽首表）、`SELECT … FROM … INTO @var|OUTFILE|DUMPFILE`（FROM 后置 INTO，镜像既有 SELECT INTO）、MySQL 客户端 `DELIMITER`（OTHER 占位）、`SET PASSWORD [FOR user] = …`（SET + text）；`CREATE DEFINER=user PROCEDURE|FUNCTION|…` 跳过 DEFINER 子句以便 DELIMITER 批可解析。
- `jkit-sql`：`DELIMITER xx` 切换 `parseAll` / 过程体尾部的批处理终止符（`;`/`;;`/`$`/`//` 等）；`//` 与除法同形时在终止处不再当二元运算符；Joplin《SQL语法合集》约 65/67。
- `jkit-sql`：可配置模板占位符（`SqlParseOptions.placeholders()` / `SqlPlaceholders`：`atWrapped`/`printf`/`angle`/`arrowAngle`/`add("@*@")`）；默认关闭，启用后按 IDENT 解析以便抽表列；见 `docs/sql.md`「模板占位符」。

- `jkit-sql`：补 common-model 审计 A 类缺口 — 数字开头裸标识符（`32强国`/`1019使用`，不破坏数值字面量）；`IN :types` / `IN ?` 无括号绑定列表；`LOAD DATA [LOCAL] INFILE … INTO TABLE`（OTHER + 抽表名）；顶层匿名 `DECLARE … BEGIN … END;`（OTHER，BEGIN/END 内允许分号）。
- `jkit-sql`：补 `REVOKE`（镜像 GRANT：权限 + ON 对象 + FROM 用户）、`FLUSH PRIVILEGES|TABLES|LOGS`（OTHER）、`START TRANSACTION` / `BEGIN WORK` / 裸 `BEGIN;` / `COMMIT` / `ROLLBACK` / `SAVEPOINT`（OTHER，不破坏 `BEGIN…END` 过程块）、`SELECT … INTO` 表 / `@var` / `OUTFILE`（目标表计入 `tables` 为 INSERT）；`parseAll` 可批 `UPDATE …; FLUSH PRIVILEGES`。

- `jkit-sql`：`SqlDialect.ORACLE` 表示 12c 以下分页（裸 SELECT 改写为 ROWNUM 包装，不写 OFFSET/FETCH）；新增 `ORACLE12`（12c+）可用 `OFFSET … FETCH`；`fromName` 支持 `oracle12`/`19c` 等。
- `jkit-sql`：`SqlBuilder` 补 `rightJoin` / `fullJoin` / `crossJoin`、`union` / `unionAll`、`with` CTE、`distinct()`。

- `jkit-sql`：SQL Server `OUTPUT … INTO` 目标表（`dbo.archive` / `@out` / `#tmp`）进 AST（`outputInto`）并计入 `tables()`；SQL Server 下 `#tmp`/`##g` 不再被词法当成 JSON 运算符。

- `jkit-sql`：MySQL 表分区限定 `FROM t PARTITION (p0, p1)`（`SqlTable.partitions`，format 往返；不再误当别名）。
- `jkit-sql` P0.4 余量：`ALTER … CHANGE/MODIFY` 抽旧/新列名 + `columnDefinition`；`ALTER ADD CONSTRAINT`（FOREIGN/PRIMARY/UNIQUE/CHECK）与引用表；`CREATE TABLE` 表级 `FOREIGN KEY … REFERENCES` 抽引用表（`referencedTables`）；`GRANT` 抽 `privileges` + 对象名（`*.*`/`db.*`/`db.t`）。
- `jkit-sql`：`SqlBuilder.leftJoin`/`join`/`groupBy`/`having`。
- `jkit-sql`：补语料缺口 — Oracle `(+)` 外连接（`SqlUnaryExpr.Op.ORACLE_OUTER_JOIN`）；SQL Server `OPENJSON(...) WITH (...)`（`SqlFunctionTable.withDefinition`）；PG `COPY … FROM STDIN`；MySQL `HANDLER` / `PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE`（OTHER + 抽名）。
- `jkit-sql`：MySQL `<=>`、`INSERT DELAYED`、`BINARY expr`；SQL Server `TOP (n) WITH TIES`；PG `TABLESAMPLE` / Oracle `SAMPLE(n)`。
- `jkit-sql`：`SqlBuilder` 流式构建 SELECT/INSERT/UPDATE/DELETE + `SQL.and`/`or`/`concat`/`builder`；分页 API `getLimit`/`getOffset`/`setLimit`/`setOffset`/`setPage`；`SqlDialect` 能力矩阵与别名；AST `toString()` 输出紧凑 SQL。
- `tools-test` P3.2：JMH `SqlParseBenchmark`（simple/join/window × jkit/druid/jsql，fork≥2）；`sql-corpus.txt`（~379 条）+ `SqlParserCompareTest#corpusFileSuccessRates`（缺口写 `target/sql-compare-fail.txt`）。Lexer 短 ident intern 仍延期。
- `jkit-sql` P3.1：`SqlFormatter` 按方言回写标识符引号（MySQL 反引号 / PG·Oracle·ANSI·H2 双引号 / SQL Server `[]`）；`||` 按 AST 运算符回写（`CONCAT`→`||`，`OR`→`OR`，配合 `pipesAsConcat`）；`SQL.parseAll(sql, dialect, true)` 容错多语句（失败记 `SqlSimpleStatement` + `parseError` 继续）；清理 `parseAlias` 未使用的 `inFrom` 参数。Lexer 短 ident intern 仍延期（需 profiling）；tools-test JMH/corpus 已在 P3.2 完成。
- `jkit-sql` P2 能力对标（零依赖，入口在 `SQL`）：`parameterize` / `exportParameterValues`（字面量指纹与导出，区别于绑定 `parameters`）；`wall` → `SqlWallResult`（多语句、注释绕过、永远真条件、`SLEEP`、无 WHERE 的 DELETE/UPDATE）；`clone`（format→parse 深拷贝）；`eval`（字面量算术/比较子集）；`SqlAstVisitor` 类型分发（并存不破坏 `SqlVisitorAdapter`）；`replaceColumn` 对称 `replaceTable`。`addLimit` 改为 clone-then-mutate。
- `jkit-sql`：从 `icell/common-model` 测试收获语料 `common-model-sql-corpus.txt`（87 条可解析）+ `CommonModelSqlCorpusTest`；已知缺口见 `CommonModelSqlKnownGapsTest`（`<sheet>` 占位表名等）；数字开头裸标识符已支持。
- 新模块 `com.alianga:jkit-sql`：零依赖手写 SQL 解析器（词法 `char[]` + 关键字开地址哈希，递归下降 AST）。入口 `SQL.parse` / `parseAll` / `format` / `toSqlString` / `tables` / `stat` / `addLimit` / `andWhere` / `replaceTable` / `parameters`。方言 MYSQL（默认，含 GBase/MariaDB）、POSTGRES、ORACLE、SQLSERVER、ANSI、H2。覆盖 DML（含 JOIN/UNION/CTE/ON DUPLICATE/ON CONFLICT）、窗口函数 `OVER`/`FILTER`、EXTRACT/TRIM/SUBSTRING、SHOW CREATE/COLUMNS 抽表名、常见 DDL。非法 SQL 抛 `SqlParseException`。模块内黄金集；与 Druid / JSqlParser 的对比在上级 `tools-test` 的 `SqlParserCompareTest`（不进本库依赖）。用法见 `docs/sql.md`。


## 2.0.1 - 2026-09-01

日期格式化与 `ConvertUtils.toDate` 性能版本。常用日期路径不再每次 `new SimpleDateFormat`。

### 变更

- `jkit-notify` 测试与渠道小优化：`AbstractHttpChannel` 抽出 `jsonOk` / `jsonIntEquals` / `jsonStringField` / `jsonCodeMessage`；`NotifyUtils.firstNonEmpty` / `escapeHtml` 去重；实发统一走 `~/jkit/application.yml`（`LiveNotifyTest` 去掉 sqlite/硬编码路径，Server酱/Telegram 去掉 `@Ignore`）。**所有实发用例**（`LiveNotifyTest`、`ServerChanBarkChannelTest#serverChanSend`、`SlackTelegramChannelTest#telegramMarkdownParseMode2`）统一经 `NotifyTestConfig.assumeLiveEnabled()` 门控：须 `-Djkit.notify.live=true` **且** yml 凭证齐全；默认 `mvn test` 即使有密钥也不实发。
- `jkit-notify`：核心 `ChannelConfig` 移除 SMS 专用 `template`/`appId`/`region`；改用 `ChannelConfig.extra` + `AbstractSmsChannel.CFG_*`（`jkit-notify-extra`）。`name` 保留（Slack 显示名 / 短信签名）。
- `jkit-notify-extra`：`SlackChannel.EXTRA_CHANNEL` 替代用 `Message.EXTRA_GROUP` 表示 Slack 频道（仍短暂回落兼容 `EXTRA_GROUP`）。
- `NotificationManager`：核心渠道仅由 `defaults()` 注册；核心 `META-INF/services` 不再列出内置渠道，SPI 只加载可选/extra 模块，避免双重注册。

### 新增

- 可选模块 `com.alianga:jkit-notify-extra`：将 Slack / Telegram / ntfy / 短信（阿里云/腾讯云/云片/华为云）从 `jkit-notify` 拆出，按需依赖；核心模块保留钉钉/企微/飞书/Server酱/Bark/Webhook/SMTP。extra 渠道经 SPI 自动注册（核心内置走 `defaults()`）。
- `HttpUtils.debug` / `HttpUtils.printCurl`：发送前把请求摘要或等价 curl 打到标准输出，便于本地复现。摘要只打方法/URL/头/正文预览（二进制按字节数、长正文截断），打印失败不影响实际发送。不要在生产打开。
- `ConfigLoadOptions.addLocation`：在默认搜索路径上追加目录（后者覆盖前者）。支持 `classpath:` / `file:` / 裸文件系统路径，`~` 展开为 `user.home`；也可传入 `File`（文件则取其父目录）。
- 新模块 `com.alianga:jkit-notify`：轻量消息通知（版本随 `jkit-parent` 2.0.1，不单独升版）。一套 API 发钉钉机器人（含加签）/ 企微机器人 / 飞书机器人（含签名校验）/ Server酱 / Bark / 通用 Webhook（payload 模板）/ SMTP 邮件（纯 Socket 实现，AUTH LOGIN + STARTTLS/SSL + MIME）；`NotificationChannel` SPI + `NotificationManager` 注册表支持代码 / `META-INF/services` 两种方式扩展渠道，`MessageType`（TEXT/MARKDOWN/HTML）声明式能力；零第三方依赖，HTTP/JSON/日志复用 jkit。用法见 `docs/notify.md`。
- `jkit-notify` 失败分类：`SendResult.failureType()` / `isRetryable()` 配合 `FailureType`（RETRYABLE / THROTTLED / CONFIG_ERROR / PERMANENT），调用方据此决定重试策略；已映射钉钉 130101/300001/310000、企微 45009/-1/93000、飞书 9499/19021/19001/19003、Server酱 40001、Bark 400 及 SMTP 4xx/5xx 语义，未收录的错误码回退到 HTTP 状态码分类。自定义渠道覆写 `AbstractHttpChannel.classify` 接入。
- `jkit-notify` 消息长度上限：按 UTF-8 **字节**做字符边界安全截断（不劈开汉字与 emoji 代理对），超限追加可见标记。钉钉 20000 字节、企微 text 2048 / markdown 4096 字节、Server酱标题 32 字符 / 正文 32KB；工具方法 `NotifyUtils.truncateUtf8` / `utf8Length`，自定义渠道覆写 `contentMaxBytes` 接入。
- `jkit-notify` 钉钉 @人自动内联：钉钉光有 `at.atMobiles` 不触发提醒，被 @ 的手机号必须字面出现在正文；渠道会自动追加缺失的 `@手机号`，已出现的（含 markdown 装饰形式）不重复追加。
- `jkit-notify` SMTP 新增 `ChannelConfig.sslProtocols(String)` 钉扎 TLS 协议版本（规避 JDK 大版本调整默认协议集导致的握手失败）与 `trustAllCerts(boolean)` 支持企业自建网关自签证书；配置校验前置到建立连接之前。
- `jkit-notify` SMTP 的 MARKDOWN 不再按纯文本降级：经 `NotifyUtils.markdownToHtml` 转成 HTML 后按 `text/html` 发送。列表项可嵌套代码块和表格；识别缩进围栏（``` / ~~~）、GFM 表和 `----+----` CLI 宽表。钉钉/企微/飞书/Server酱仍走各平台原生 markdown。
- `jkit-notify` 飞书加签改为写入 JSON 请求体（官方要求，不再拼 URL query）；`sendAll` / `unregister`；SMTP 补 `Date`/`Message-ID`/dot-stuffing/`cc`/`bcc`/附件自动拆包；钉钉 actionCard、企微 markdown_v2 与 `EXTRA_AT_USERIDS`；异步发送改用本模块独立线程池。
- `jkit-notify` 附件自动识别 MIME（`FileType` + 常见后缀兜底）；拆包大小支持 `10MB`/`512KB`；SMTP Markdown 默认响应式 HTML；钉钉 feedCard / 企微 news 与 image。
- `jkit-notify` 模板变量：`Message.var` / `vars`，发送前替换 `${key}` / `${a.b}`。文件附件流式读取与拆包，不再把整文件载入内存。
- `jkit-notify` `NotifyPolicy`：静默时段、5 分钟去重、本地限流；`sendFailover` 同一渠道多账号顺序切换。抑制为 `FailureType.SUPPRESSED`。
- `jkit-notify` `AbstractHttpChannel` 新增 `contentType` 扩展点与 payload 感知的 `applyHeaders`；`ChannelConfig` 补 `name`（Slack 显示名 / 短信签名）与通用 `extra(key,value)`（短信 `template`/`appId`/`region` 等经 `AbstractSmsChannel.CFG_*` 写入，不在核心暴露 SMS 字段）。
- `jkit-notify-extra` 渠道：Slack（Incoming Webhook / chat.postMessage）、Telegram Bot（chat_id 支持 `@channel`，话题群与静默；MARKDOWN 转 Telegram HTML 子集发送）、ntfy（tags / 优先级 / markdown 与 Bearer / Basic 鉴权），以及阿里云 / 腾讯云 / 云片 / 华为云短信。渠道 extras 在各自实现类上（`SlackChannel.EXTRA_COLOR`、`TelegramChannel.EXTRA_CHAT_ID`、`NtfyChannel.EXTRA_TAGS`、`AbstractSmsChannel.EXTRA_SMS_PARAMS`），不放进核心 `Message`。
- `DateUtils.parse(String)`：自动识别常见日期字符串（时间戳、紧凑数字、`-` `/` `.`、中文/韩文、ISO-8601 含 `T`/`Z`/`+0800`/`+08:00`）。
- `DateUtils.fromEpochNumber(long)`：10 位秒或 13 位毫秒时间戳转 `Date`。
- `DateUtils.fromTemporal(TemporalAccessor)`：`java.time` 时间对象转 `Date`。
- `ConvertUtils.toDate` 额外支持 `Instant`、`OffsetDateTime`、`ZonedDateTime`。
- `Print.enableLog`：为 `false` 时只打控制台，不再写入 JUL。
- `com.alianga.jkit.log.LocaleFormatter`：固定 Locale 的 JUL 格式化器，时间戳用手写 `yyyy-MM-dd HH:mm:ss.SSS`。

### 变更

- `jkit-notify` `NotifyUtils` 编解码复用 jkit-core（Base64、URL 编码、SHA-256、hex）；新增 `strMap` / `parseJson` / `formEncode` / `parseReceivers` / `uuid`。
- `DateUtils` 去掉 `SimpleDateFormat`。`yyyy-MM-dd HH:mm:ss` 走 `char[]` + 秒级缓存，`yyyy-MM-dd` / `yyyyMMdd` / `yyyy-MM-dd HH:mm:ss.SSS` 走手写拼接，其余 pattern 复用 `DateTimeFormatter`。
- `ConvertUtils.toDate(Object)` 改为按数字字段抽取，不再推断 SimpleDateFormat pattern。常见字符串解析约快一个数量级，结果与 ZmlTools 对齐。
- JUL 默认格式改为英文级别名（`WARNING`/`SEVERE`），不再随 JVM 默认语言变化。
- `RandomUtils.randomBirth` 改为 `LocalDate` + 手写 `yyyyMMdd`，去掉每次分配的 `SimpleDateFormat`/`Calendar`；`minAge == maxAge` 不再抛异常。
- `getIdCardCheckNum` 复用 `IdCardUtils.calcTrailingNumber`，避免 17 次 `Integer.parseInt`。
- `getUUID` 去 `-` 不再走正则；`decoding` 改为整数幂避免 `Math.pow` 精度问题；`randomOne` 可取到数组最后一个元素。
- `IdCardUtils` 随机生日按当月实际天数生成，避免 Calendar 宽松模式下的日期滚动。
- curl 解析：展开 `-kLs`/`-XPOST`，`--json` / `--data-urlencode` 按 curl 语义处理，`@file` 不再当字面正文，`-F` 保持 multipart；`toCurl` 代理输出 `host:port`；`-k` 只作用于单次请求。公开 API 为 `parseCurl` / `curlToRequest` / `curl` / `curlString` / `requestToCurl`，以及不可变模型 `ParsedCurlRequest`（`CurlParser.parseModel`）。
- 多语言 curl 代码生成从 jkit 拆到独立 artifact `com.alianga:jkit-curl-codegen`。jkit 只解析和执行，不再带 `CurlParser.generate` 与 `http.codegen` 包。

### 构建

- `git-commit-id-plugin`、`buildnumber-maven-plugin`、`maven-source-plugin` 从 `publish` profile 挪到默认构建，`package` 即可产出带构建信息的 sources jar。
- 仓库改为多模块：父 POM `com.alianga:jkit-parent`（packaging pom），运行时库在模块 `jkit-core`（发布坐标仍是 `com.alianga:jkit`，沿用原根 POM 的编译 / Checkstyle / MRJAR / 发布配置），代码生成在 `jkit-curl-codegen`，消息通知在 `jkit-notify`，可选扩展渠道在 `jkit-notify-extra`。根目录 `mvn test` 同时构建全部模块。

### jkit-curl-codegen

- 扩充到 34 种：新增互操作格式 `http`（原始 HTTP/1.1 报文）、`har`（HAR 1.2 JSON）、`httpie`（HTTPie CLI），以及 `ruby-httparty`、`php-guzzle`、`lua`（luasocket `socket.http`，与 curlconverter lua 一致）。
- 新增 curl 代码生成：OkHttp / Apache 5 / JDK 11+ / jkit / Kotlin / fetch / axios / requests / httpx / Go / C# / PHP。
- 扩充到 28 种：新增 Java `HttpURLConnection` / Unirest、JavaScript request / unirest / http（follow-redirects）/ jQuery / XMLHttpRequest、PHP pecl_http、R httr2、Rust reqwest、Swift URLSession、Ruby Net::HTTP、PowerShell Invoke-RestMethod、curl（Windows cmd）、curl（Windows PowerShell）、wget。入口为 `CurlCodegen.generate(id, curl)`。
- 修复 R（httr2）与 PowerShell 生成器的运行时错误：R 改用 `req_perform()`（默认跟随重定向；关闭时 `req_options(followlocation = 0)`），multipart 字段名加反引号、正文不重复设置 Content-Type；PowerShell 把受限头 User-Agent / Cookie 分别转成 `-UserAgent` 参数与 `WebRequestSession`（Windows PowerShell 5.1 的 `-Headers` 不接受这两个头），字符串正文的 Content-Type 自动补 `charset=utf-8`。
- 修复 PowerShell 生成器两处 5.1 运行时错误：`-Headers` 里的 Connection / Content-Length / Host / Range 等受限头直接丢弃（`Connection: close` 转成 `-DisableKeepAlive`，keep-alive 为默认行为）；Cookie 值含逗号 / 分号时按 .NET 要求整体加双引号，避免 `CookieContainer.Add` 抛 `CookieException`。
- 修复 Windows curl 生成器在 PowerShell 中不可用：拆成两个生成器——`shell-curl-windows` 面向 cmd.exe，恢复 `^` 续行的多行写法（cmd.exe 不认识 `--%`，会当成 curl 的未知选项报错）；新增 `shell-curl-powershell` 面向 PowerShell，输出单行 `curl.exe --% ...`（`curl.exe` 绕过 Invoke-WebRequest 别名，`--%` 停止解析让 sec-ch-ua 等含双引号的头原样透传；`^` 续行是 cmd.exe 专用语法，粘贴到 PowerShell 会被逐行执行）；顺带移除非法选项 `--no-location`（curl 默认即不跟随重定向）。
- 修复 PowerShell 版 curl 响应中文乱码：命令前追加 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8;`（Windows PowerShell 5.1 默认按系统 ANSI 代码页 / 中文系统 GBK 解码原生命令输出，UTF-8 响应会乱码）；cmd.exe 版本在说明里提示先执行 `chcp 65001`。
- 修复 PowerShell（Invoke-RestMethod）生成器响应中文乱码：PS 5.1 在响应 Content-Type 不带 charset 时按 ISO-8859-1 解码响应体且无法覆盖，改为 `Invoke-WebRequest -UseBasicParsing` + `[System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())` 从原始字节强制 UTF-8 解码，再 `ConvertFrom-Json`（非 JSON 响应回退为原始文本）；生成器 id `powershell-restmethod` 保持不变。

## 2.0.0

jkit 首个对外版本，由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来：零第三方依赖、包名改为 `com.alianga.jkit`，坐标 `com.alianga:jkit:2.0.0`。

能力概要见 [README.md](https://github.com/zhengmingliang/jkit/blob/develop/README.md)：CSV / HTTP / JSON / YAML / 配置 / 表达式等均用纯 JDK 重写。从 ZmlTools 迁过来的项目可继续用 `relocated/zmltools` 把 `top.wuyongshi:ZmlTools:2.0.0` 重定向到本坐标（包名仍需手工替换）。
