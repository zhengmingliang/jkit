# 版本更新说明

本文记录 jkit 各版本的用户可见变更。每个版本号只出现一次，按新到旧排列。

未发版的改动追加到**当前版本**小节（现在是 2.0.2）；发版后冻结该小节，在上方新建下一版本。不要改写已冻结的历史版本，也不要为同一版本再开 `unreleased` 标题。

## 2.0.2 - 2026-09-14

### 新增

**jkit-sql**

- `JdbcUrlUtils`：解析 JDBC URL（主机 / 集群节点 / 库名 / schema / 参数），`fromUrl` 推断 `SqlDialect`，`getDbType` 返回类型短名，`driverForUrl` / `getDriverClassName` 猜测驱动类。覆盖 MySQL 复制与负载、PostgreSQL HA、Oracle SID/Service/RAC、SQL Server、H2、Gauss/openGauss、达梦等。PostgreSQL 系从 `currentSchema` 取 schema（缺省 `public`）。
- `SQL.injectTenant` / `SqlRewrites.injectTenant`：按表白名单注入租户条件。下钻 UNION 臂、FROM 子查询、CTE 体、EXISTS / IN 标量子查询；JOIN 按别名限定列；INSERT 补列或改 SET；MERGE 补 ON。字符串值按 SQL 单引号转义，不当表达式解析。CTE 名与 `DUAL` 跳过。

### 变更

- `jkit-sql-auto`：`SqlAutoDialects.fromUrl` / `driverForUrl` 委托 `JdbcUrlUtils`（覆盖 Gauss / Kingbase / Hive / ClickHouse / Trino 等更多 URL）。

### 修复

- `jkit-sql-auto`：`SqlAutoInspector` 判断表是否存在时补上 schema。未配置时从 `Connection.getSchema()` 取；无连接（dry-run）或驱动不支持时从 JDBC URL 解析。PostgreSQL / Gauss 缺省 `public`，SQL Server 缺省 `dbo`，Oracle / 达梦回落用户名。避免把其它 schema 下的同名表误判为已存在，或对本库缺失表发出 ALTER。

## 2.0.1 - 2026-09-13

仓库拆成多模块。本版本随父 POM 一并交付 `jkit-sql`、`jkit-sql-auto`（及 Spring Boot 2 / 3 starter）、`jkit-notify`、`jkit-notify-extra`、`jkit-curl-codegen`。坐标 `com.alianga:jkit` 仍指向 `jkit-core`。

### 新增

**jkit-sql**

- 新模块 `com.alianga:jkit-sql`：零依赖手写 SQL 解析器（词法 `char[]` + 关键字开地址哈希，递归下降 AST）。入口 `SQL.parse` / `parseAll` / `parseExpr` / `format` / `toSqlString` / `tables` / `stat` / `parameters` / `parameterize` / `exportParameterValues` / `wall` / `clone` / `eval` / `rewrite`。非法 SQL 抛 `SqlParseException`。用法见 [`docs/sql.md`](/sql)。
- **方言**：一等枚举 `MYSQL`（默认）、`POSTGRES`、`ORACLE`（12c 以下 ROWNUM 分页）、`ORACLE12`（`OFFSET/FETCH`）、`SQLSERVER`、`ANSI`、`H2`、`DB2`、`SQLITE`、`HIVE`（别名 `maxcompute`/`odps`/`argo`）、`CLICKHOUSE`、`PRESTO`（别名 `trino`）、`DAMENG`（`dm`/`dameng`/`jdbc:dm:`）。`fromName` 覆盖 GoldenDB / SelectDB / AnalyticDB / MatrixOne / StoneDB / HighGo / UXDB / MogDB / Vastbase / AntDB / IvorySQL / GBase 8a / GBase 8s / 行云 / Oscar 等别名，与 icell `common-model` 的 SQL 型数据源对齐。能力由 `SqlDialectSpec` 驱动，可用 `SqlDialectWrapper` 按需覆写单条能力（如 MySQL `ANSI_QUOTES`）。
- **分页**：`getLimit` / `getOffset` / `setLimit` / `setOffset` / `setPage` / `addLimit` / `SQL.adaptPagination`。`format` / `toSqlString` 按目标方言适配（MySQL `LIMIT` ↔ 经典 ORACLE ROWNUM ↔ ORACLE12 `OFFSET FETCH` ↔ SQL Server `TOP` | `OFFSET FETCH`）。`SqlBuilder.limit` / `offset` 按有效方言生成，不再一律输出 `LIMIT`。
- **改写链**：`SqlRewriteHook` + `SqlRewrites`（`addLimit` / `setPage` / `andWhere` / `replaceTable` / `replaceColumn` / `adaptPagination` / `addSelectItem` / `removeSelectItem` 可与自定义规则混排）+ `SQL.rewrite`（先深拷贝再改）。跨方言函数改写覆盖 JOIN ON / MERGE / OVER / 列 `DEFAULT`：`DATE_ADD` / `DATEDIFF` / `FROM_UNIXTIME` / `UNIX_TIMESTAMP` / `SUBSTRING` / `LEFT` / `RIGHT` / `DECODE` / `NVL2` / `UCASE`/`LCASE` / `CONCAT_WS` / `LPAD`/`RPAD` / `YEAR`/`MONTH`/`DAY`/`HOUR`/`MINUTE`/`SECOND` / `SYSDATE` / `LAST_DAY` / `CHAR`/`CHR` 等；FULLTEXT 升为 `MANUAL_ACTION_REQUIRED`。函数改写走 `SqlSchemaConverterProvider.registerFunctions`（SPI 后覆盖）。
- **实体 DDL**：`SqlEntities` 扫描 `@SqlTable` / JPA / MyBatis-Plus / MyBatis `@Alias`（无第三方编译依赖）。公开 `columnSql` / `columnTypeSql` / `createIndex` / `orderByForeignKeys` / `extraSql` / `sequenceSql` / `indexName` / `createTable(..., includeIndexes)`。认任意简单名为 `Comment` 的注解与 Hibernate `@ColumnDefault`；`@SqlTable(comment)` / `@SqlColumn(comment)` 按方言生成注释；Oracle ≤11g 无 IDENTITY 时附录 SEQUENCE + TRIGGER。
- **格式化与解析选项**：`SqlFormatOptions.keywordCase`（`AS_IS` / `UPPER` / `LOWER`）、`quoteIdentifiers`；`SqlParseOptions.pipesAsConcat` / `keepComments` / `placeholders()`（模板占位符）/ `statementParsers()`（按前导关键字挂自定义语句解析器）。
- **安全与扩展**：`SQL.wall` → `SqlWallResult`；`SqlWallRule` 规则链 + `SqlWallConfig.rules(...)`（内置 selectOnly / denyDdl / 无 WHERE 写等，行为与违规码不变）。`SqlAstVisitor` 类型分发（不破坏 `SqlVisitorAdapter`）。
- **语句 AST**：`EXPLAIN` / `SET` / `COMMENT ON` / `SHOW` / `ANALYZE|VACUUM|OPTIMIZE` / 事务控制 / `COPY` / `FLUSH` / `START TRANSACTION` / `LOAD DATA` / `LOCK TABLES` / `PREPARE` / `BEGIN…END` / 过程·触发器·事件（`SqlRoutineParam` / `SqlControlStatement` / `SqlDeclareStatement` / `SqlHandlerStatement`）升为独立类型；`MODEL` / `MATCH_RECOGNIZE` 结构化；`RENAME TABLE` 独立语句。
- **SqlBuilder**：流式 SELECT / INSERT / UPDATE / DELETE，`join` / `leftJoin` / `rightJoin` / `fullJoin` / `crossJoin` / `union` / `with` / `distinct` / `groupBy` / `having`。
- **解析覆盖**：相对 Druid BVT / JSqlParser 内联 / 文件语料约 **96.3% / 92.3% / 90.0%**。窗口、CTE、MERGE、PIVOT、闪回、过程块、Hive / ClickHouse / Informix / DB2 等边角语法见 [`docs/sql.md`](/sql)，不在此按迭代轮次罗列。

**jkit-sql-auto**

- 新模块 `com.alianga:jkit-sql-auto`：启动时按实体自动建表 / 更新表结构。扫描 `@SqlTable` / JPA / MyBatis-Plus，对照 `DatabaseMetaData` 执行 `CREATE TABLE` / `ALTER TABLE ADD` / `CREATE INDEX`。模式：`none` / `validate` / `update`（默认，只追加）/ `create` / `create-drop`。配置前缀 `jkit.sql.auto.*`，数据源可回落 `spring.datasource.*`。运行时零第三方依赖。
- `SqlAuto.drop` 按外键逆序删托管表；Spring Boot 2 / 3 自动配置模块 `jkit-sql-auto-spring-boot-2`、`jkit-sql-auto-spring-boot-3`。
- 表 / 列注释按方言执行（`COMMENT` / `COMMENT ON` / `sp_addextendedproperty`）；Oracle ≤11g 自增主键用 SEQUENCE + TRIGGER，达梦列上写 IDENTITY。
- `postgresIdentityStyle` / `foreignKeys` / `autoIncrement`：给 OpenGauss / GBase 8a / DuckDB 等能力较窄的产品关掉对应 DDL。
- `table-prefix`（`jkit.sql.auto.table-prefix` / 链式 `tablePrefix(String)`）：所有自动建表名统一加前缀（如 `t_`），作用于建表 / 改表 / 删表 / 索引 / 序列 / 外键目标表。
- `index-prefix-enabled`（`jkit.sql.auto.index-prefix-enabled`，默认 `true`）：自动派生的索引名是否也带表前缀；实体里显式 `@Index(name=…)` 始终原样保留。

**jkit-notify**

- 新模块 `com.alianga:jkit-notify`：钉钉（含加签）/ 企微 / 飞书（含签名）/ Server酱 / Bark / 通用 Webhook / SMTP（纯 Socket：AUTH LOGIN + STARTTLS/SSL + MIME）。`NotificationChannel` SPI + `NotificationManager`；`MessageType`（TEXT / MARKDOWN / HTML）。零第三方依赖。用法见 [`docs/notify.md`](/notify)。
- 失败分类：`SendResult.failureType()` / `isRetryable()`，`FailureType` 为 RETRYABLE / THROTTLED / CONFIG_ERROR / PERMANENT / SUPPRESSED；未收录错误码回退 HTTP 状态。自定义渠道覆写 `AbstractHttpChannel.classify`。
- 消息按 UTF-8 **字节**截断（不劈开汉字与 emoji）；钉钉 @人自动把缺失的 `@手机号` 追加进正文；`Message.var` / `vars` 替换 `${key}` / `${a.b}`；附件流式读取、按 `10MB`/`512KB` 拆包、自动识别 MIME。
- SMTP：`sslProtocols` 钉扎 TLS 版本、`trustAllCerts` 支持自签网关；MARKDOWN 经 `NotifyUtils.markdownToHtml` 按 `text/html` 发送。飞书加签写入 JSON 请求体。`NotifyPolicy`：静默时段、5 分钟去重、本地限流；`sendFailover` 多账号顺序切换。
- 可选模块 `com.alianga:jkit-notify-extra`：Slack / Telegram / ntfy / 阿里云 / 腾讯云 / 云片 / 华为云短信。渠道 extras 在各自实现类上，不放进核心 `Message`。

**jkit-core**

- `DateUtils.parse(String)`：自动识别时间戳、紧凑数字、`-` `/` `.`、中文/韩文、ISO-8601（含 `T`/`Z`/`+0800`/`+08:00`）。
- `DateUtils.fromEpochNumber(long)`：10 位秒或 13 位毫秒时间戳转 `Date`。
- `DateUtils.fromTemporal(TemporalAccessor)`：`java.time` 时间对象转 `Date`。
- `ConvertUtils.toDate` 额外支持 `Instant`、`OffsetDateTime`、`ZonedDateTime`。
- `HttpUtils.debug` / `HttpUtils.printCurl`：发送前把请求摘要或等价 curl 打到标准输出（不要在生产打开）。
- `ConfigLoadOptions.addLocation`：在默认搜索路径上追加目录（`classpath:` / `file:` / 裸路径 / `File`，`~` 展开为 `user.home`）。
- `Print.enableLog`：为 `false` 时只打控制台，不再写入 JUL。
- `com.alianga.jkit.log.LocaleFormatter`：固定 Locale 的 JUL 格式化器。

**jkit-curl-codegen**

- 从 jkit 拆出独立 artifact `com.alianga:jkit-curl-codegen`，入口 `CurlCodegen.generate(id, curl)`。覆盖 34 种目标：Java（jkit / JDK 11+ / OkHttp / Apache 5 / HttpURLConnection / Unirest）、Kotlin、JavaScript（fetch / axios / request / jQuery / XHR 等）、Python（requests / httpx）、Go、C#、PHP、R、Rust、Swift、Ruby、Lua、PowerShell、curl（含 Windows cmd / PowerShell）、wget，以及 `http` / `har` / `httpie` 互操作格式。

### 变更

- `jkit-sql`：**破坏性** — `SQL.andWhere` / `replaceTable` / `replaceColumn` 改为与 `addLimit` / `setPage` 一致的 clone-then-mutate（返回新 AST，不污染原树）；调用方须使用返回值。
- `jkit-sql`：`SQL.clone` 改为真正的 AST 树拷贝（`SqlAstCloner` / `SqlNode.copy`），热路径不再 format→parse；跨方言 `format` / `adaptPagination` / `setPage` 显著加速。经典 ORACLE offset=0 单层 ROWNUM 包装免 clone。
- `jkit-sql-auto`：`dryRun(true)` 的 `SqlAuto.run(options)` / `plan(options)` 不再打开 JDBC / DataSource。URL 仅用于推断方言，按空库规划全量 `CREATE TABLE`。已传入 `Connection` 的重载仍对照活表，只是不执行。
- `jkit-core`：`DateUtils` 去掉 `SimpleDateFormat`。`yyyy-MM-dd HH:mm:ss` 走 `char[]` + 秒级缓存，其余常见 pattern 走手写拼接或 `DateTimeFormatter`。`ConvertUtils.toDate(Object)` 改为按数字字段抽取。常见字符串解析约快一个数量级，结果与 ZmlTools 对齐。
- `jkit-core`：JUL 默认格式改为英文级别名（`WARNING` / `SEVERE`），不再随 JVM 默认语言变化。
- `jkit-core`：`RandomUtils.randomBirth` 改为 `LocalDate`（`minAge == maxAge` 不再抛异常）；`getUUID` 去 `-` 不再走正则；`IdCardUtils` 随机生日按当月实际天数生成。
- `jkit-core`：curl 解析对齐 curl 语义（展开 `-kLs`/`-XPOST`，`--json` / `--data-urlencode`，`@file` 不再当字面正文）。公开 API：`parseCurl` / `curlToRequest` / `curl` / `curlString` / `requestToCurl`，模型 `ParsedCurlRequest`。`CurlParser.generate` 与 `http.codegen` 包从 jkit 移除，改依赖 `jkit-curl-codegen`。
- `jkit-notify`：核心 `ChannelConfig` 移除 SMS 专用 `template` / `appId` / `region`，改用 `ChannelConfig.extra` + `AbstractSmsChannel.CFG_*`。核心渠道仅由 `defaults()` 注册，SPI 只加载 extra 模块，避免双重注册。
- `jkit-notify-extra`：`SlackChannel.EXTRA_CHANNEL` 替代用 `Message.EXTRA_GROUP` 表示 Slack 频道（短暂回落兼容 `EXTRA_GROUP`）。

### 修复

- `jkit-core`：响应声明 `Content-Encoding: gzip` 但正文为空时，JDK 8 不再抛 `EOFException`，返回空流（关闭时仍回收底层连接）。
- `jkit-sql`：子类与 `MappedSuperclass` / 父类重复声明同名列时只保留子类字段，避免 PostgreSQL `column specified more than once`。
- `jkit-sql`：`@GeneratedValue(generator="system-uuid")` / `GenerationType.UUID` / 非整数 `@SqlGenerated` 不再生成 `AUTO_INCREMENT`/`IDENTITY`（PostgreSQL 对 `VARCHAR` 主键写 IDENTITY 会语法错误）。
- `jkit-sql`：自动生成的索引名 / 序列名 / 触发器名按方言标识符长度上限截断（经典 Oracle 30 字符，超长时保留前缀 + 4 位散列）。未命名索引改为 `{table}_{col}_idx`，同表多个不再撞名。公开 `SqlDialectSpec.maxIdentifierLength` / `fitIdentifier`。
- `jkit-sql`：`SQL.format` pretty 模式对 `CREATE TABLE` 按列换行缩进（`toSqlString` / compact 仍单行）。
- `jkit-sql`：`GROUP_CONCAT` 无显式 `SEPARATOR` 时转 `STRING_AGG`/`LISTAGG` 不再丢分隔符；MySQL `MODIFY`/`CHANGE` 转 PG/ANSI/H2/PRESTO 时不再把列名写重。
- `jkit-sql`：`SUBSTRING` / `LEFT` / `RIGHT` 按方言回写（PG/MySQL/H2 用 `FROM n FOR m`，SQL Server/SQLite/Hive/ClickHouse 用逗号形态；负起点按 `LENGTH/LEN` 改写）；达梦裸 SELECT 补 `FROM dual`。
- `jkit-sql`：`SqlDialectWrapper` 不再委托派生方法（`identQuoteClose` / `quoteIdent` / `pipesAreConcat` / `preferredLimitStyle`），子类只覆写原语时派生能力跟着变。
- `jkit-sql`：回写保真 — `NATURAL JOIN` 不再丢修饰符；`RENAME TABLE` 不再写成非法 `ALTER TABLE`；`ADD UNIQUE KEY` 不再丢 `UNIQUE`；`INSERT/UPDATE/DELETE` 的 `IGNORE` / `LOW_PRIORITY` / `HIGH_PRIORITY` 入 AST 并回写；非裸标识符别名强制加引号。
- `jkit-sql`：`tables()` 补漏 `CREATE TRIGGER` / `CREATE TABLE … LIKE` / `RENAME TO` / 多表 `OPTIMIZE|ANALYZE`；库名、例程名、CTE 名不再误计。仅注释 / 空白输入归为 `OTHER`，不再抛 `empty SQL`。
- `jkit-sql-auto`：经典 Oracle 下自动生成的 `CREATE INDEX` 名超过 30 字符时截断，避免 ORA-00972。
- `jkit-curl-codegen`：R（httr2）改用 `req_perform()`；PowerShell 5.1 受限头 / Cookie 逗号 / 中文乱码；Windows curl 拆成 cmd（`shell-curl-windows`）与 PowerShell（`shell-curl-powershell`）两个生成器。

### 构建

- 仓库改为多模块：父 POM `com.alianga:jkit-parent`，运行时库在 `jkit-core`（发布坐标仍是 `com.alianga:jkit`），其余能力各一模块。根目录 `mvn test` 同时构建全部模块。
- `git-commit-id-plugin`、`buildnumber-maven-plugin`、`maven-source-plugin` 从 `publish` profile 挪到默认构建，`package` 即可产出带构建信息的 sources jar。
- 整条 reactor 可用 JDK 8 启动 Maven：其余模块按 JDK 8 编译；`jkit-core` 的 `META-INF/versions/9`、`/11` 经 toolchain 走 JDK 9 / 11；仅 `jkit-sql-auto-spring-boot-3` 经 toolchain 走 JDK 17（Spring Boot 3 要求）。依赖本机 `~/.m2/toolchains.xml` 已声明 jdk 8/9/11/17/21。启动示例：`JAVA_HOME=$(jdk8) mvn -o clean install`。
- `jkit-notify` 实发用例须 `-Djkit.notify.live=true` 且 yml 凭证齐全；默认 `mvn test` 即使有密钥也不实发。

### 文档

- `docs/sql.md` / `docs/en/sql.md` 按代码对齐跨方言分页、`SQL.clone`、`SqlRewrites`、`SqlEntities` 公开方法与方言表；两侧 API 覆盖已对齐。README 与模块 README 去掉内部开发计划入口。
- `docs/sql-auto.md`（及英文）按「选包 → 写实体 → Spring Boot / 非 Spring → 配置项」重排；模块 README、快速开始页同步。

## 2.0.0

jkit 首个对外版本，由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来：零第三方依赖、包名改为 `com.alianga.jkit`，坐标 `com.alianga:jkit:2.0.0`。

能力概要见 [README.md](https://github.com/zhengmingliang/jkit/blob/develop/README.md)：CSV / HTTP / JSON / YAML / 配置 / 表达式等均用纯 JDK 重写。从 ZmlTools 迁过来的项目可继续用 `relocated/zmltools` 把 `top.wuyongshi:ZmlTools:2.0.0` 重定向到本坐标（包名仍需手工替换）。

---

## 如何记录后续版本

1. 把根 `pom.xml`（`jkit-parent`）的 `<version>` 改成新版本号，子模块继承该版本；同步 `README.md` 页头与依赖示例、`Main.java` 打印的版本。
2. 在简介段落后、当前最新版本小节**上方**插入新版本，日期用当天（`YYYY-MM-DD`）。**同一版本号只有一个二级标题**；未发版前把新条目追加进该小节，不要再开 `## x.y.z / unreleased`。
3. 按「新增 / 变更 / 修复 / 构建」分类写清调用方能感知的变化（多模块时用加粗模块名分组）。不要只贴 git 标题，不要按内部里程碑或竞品覆盖率轮次记账（那些写 `docs/sql.md` / `docs/next-plan.md`）。
4. 本次新增的公开 API 在 javadoc 里补 `@since x.y.z`。
5. 历史版本留在原处，不要把旧条目改写成新版本。

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
