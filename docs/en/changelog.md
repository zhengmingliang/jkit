> The changelog is maintained in Chinese. An English translation may be added in the future; version numbers, class names and code identifiers remain readable as-is.

# Release Notes

This document records user-visible changes in each jkit release. Each version number appears once, listed from newest to oldest.

Unreleased changes are appended to the **current version** section (currently 2.0.2); once released, that section is frozen and a new version section is added above it. Do not rewrite frozen historical versions, and do not open a new `unreleased` heading for the same version number.

## 2.0.2 - 2026-09-14

### Added

**jkit-sql**

- `JdbcUrlUtils`: parse JDBC URLs (host / cluster nodes / database / schema / parameters), infer `SqlDialect` via `fromUrl`, return a short type name via `getDbType`, and guess the driver class via `driverForUrl` / `getDriverClassName`. Covers MySQL replication and load-balance, PostgreSQL HA, Oracle SID/Service/RAC, SQL Server, H2, Gauss/openGauss, Dameng, and more. PostgreSQL-family URLs read schema from `currentSchema` (default `public`).
- `com.alianga.jkit.sql.entity.Comment`: first-party table/column comment annotation (`TYPE`+`FIELD`, `value()`). Sits alongside Hibernate `@Comment` and `@SqlTable(comment)` / `@SqlColumn(comment)`; the scanner still matches the simple name `Comment`, so `jkit-sql-model` and similar modules can prefer this package.
- Complex SQL dialect slice L2/L4 harness (Oracle): `ComplexSqlOracleSliceL2*` (jkit-sql) and `ComplexSqlOracleNativeExecute*` (tools-test); 001–300 native execute on oracle19c 300/300 (parseFail=0 / execFail=0); reports under `target/complex-sql-reports/oracle-l2|l4-*`.
- Complex SQL dialect slice L2/L4 harness (MySQL): `ComplexSqlMysqlSliceL2*` (jkit-sql) and `ComplexSqlMysqlNativeExecute*` (tools-test); 001–300 native execute on MySQL `3308/test_db` 300/300 (parseFail=0 / execFail=0); reports under `target/complex-sql-reports/mysql-l2|l4-*`.
- Complex SQL dialect slice L2/L4 harness (SQL Server): `ComplexSqlSqlServerSliceL2*` (jkit-sql) and `ComplexSqlSqlServerNativeExecute*` (tools-test); 001–300 native execute on `jkit_ss_test` (1433) 300/300 (parseFail=0 / execFail=0); reports under `target/complex-sql-reports/sqlserver-l2|l4-*`.
- Complex SQL dialect slice L2/L4 harness (PostgreSQL): `ComplexSqlPostgresSliceL2*` (jkit-sql) and `ComplexSqlPostgresNativeExecute*` (tools-test); 001–300 native execute on `jkit_complex` (5532) 294/300 (parseFail=0; 6 corpus cases need explicit cast for `round(float8,int)`); reports under `target/complex-sql-reports/postgres-l2|l4-*`.

### Changed

- `jkit-sql`: the row-level inject implementation is renamed from `SqlTenantRewriter` to `SqlInjectRewriter` (2.0.2 has not shipped, so the old name is not kept). Tenant is one scenario; class and method names no longer say tenant.
- `jkit-sql`: `SQL.bind` / `bindNamed` string entry points no longer clone the tree just produced by parse; visitor dispatch puts SELECT/expr types first; bind values allocate less (index over Iterator, fast-path strings without quotes, interned small-int text). The AST entry points still clone-then-mutate.
- `jkit-sql-auto`: `SqlAutoDialects.fromUrl` / `driverForUrl` now delegate to `JdbcUrlUtils` (more URL prefixes: Gauss, Kingbase, Hive, ClickHouse, Trino, …).

### Fixed

- `jkit-sql`: classic Oracle ROWNUM pagination of a UNION / INTERSECT / EXCEPT / MINUS that has an `ORDER BY` now wraps as `SELECT * FROM (set-op) ORDER BY …` before the ROWNUM layers, so Oracle does not raise `ORA-00904` when ordering a set-op by column aliases inside a subquery. The outer select of a double wrap projects the original columns only and no longer returns the helper `RN` column (a source `SELECT *` still includes `RN`).
- `jkit-sql`: `SqlNode.toString()` now uses MySQL identifier quotes (backticks) by default, matching `SQL.toSqlString`. `addComment("text")` and compact `--` line comments are emitted as block comments so they cannot swallow the following statement. Bare `addHint` text is wrapped as a slash-star-plus hint. Use `SQL.toSqlString(stmt, dialect)` for another dialect.
- `jkit-sql-auto`: `SqlAutoInspector` now resolves schema when checking whether a table exists. If unset, it uses `Connection.getSchema()`; with no connection (dry-run) or an unsupported driver, it parses the JDBC URL. PostgreSQL / Gauss default to `public`, SQL Server to `dbo`, Oracle / Dameng fall back to the username. This avoids treating a same-named table in another schema as present, or emitting ALTER for a table missing from the current schema.
- `jkit-curl-codegen`: curl parse warnings (e.g. "unsupported option --digest, skipped") were dropped on the generation path; they are now merged at the front of `GeneratedCode.notes()`, ahead of the generator's own notes.
- `jkit-curl-codegen`: generators no longer silently drop content. `py-requests` now emits a real `files=` dict for multipart and passes it to the request (file uploads previously vanished); `py-httpx` gained multipart / file bodies; `js-fetch` / `js-axios` emit a readable fs example with the file path for `-T` bodies instead of a bare `undefined`; `go-nethttp` / `csharp-httpclient` generate real multipart code (`mime/multipart` / `MultipartFormDataContent`) and file bodies (`os.Open` / `File.ReadAllBytes`); `php-curl` emits `CURLFile` for multipart. Proxy handling aligned: `py-requests` no longer hardcodes `http://` (keeps scheme and credentials); `java-okhttp` / `kotlin-okhttp` emit `proxyAuthenticator`; `go-nethttp` uses `http.Transport`; `csharp-httpclient` uses `WebProxy`; `java-apache` emits `setProxy`; `js-axios` emits a `proxy` block; `php-curl` emits `CURLOPT_PROXY`; `js-fetch` / `js-native` explain in notes when a per-request proxy cannot be expressed.
- `jkit-curl-codegen`: the `har` generator's `decode` no longer turns a literal `+` in URLs/forms into a space (`+` is preserved; `%20` / `%2B` still decode correctly); the creator version is no longer hardcoded `2.0.1` — it reads `Implementation-Version` from the jar and falls back to the current version in development. `CodeQuote` now escapes U+2028/U+2029 and remaining control characters for JS/Python/Go/C#/Ruby/R (previously able to emit illegal JS); Rust/Swift use `\u{XXXX}`, Lua uses three-digit decimal `\ddd`.
- `jkit-core`: curl parsing now joins multiple `-b` / `--cookie` flags with `; ` like curl does (previously each overwrote the last); `-E` is skipped consistently with `--cert` (reported as ignored rather than unsupported).
- `jkit-core`: URLs in `HttpUtils` debug logs and "total timeout exceeded" exceptions keep their full structure but mask sensitive fields — query values of `access_token` / `token` / `sign` / `signature` / `secret` / `key` and path tokens such as Telegram `/bot<token>/` and ServerChan `/<SendKey>.send`; new public helper `HttpIo.maskUrl`. DingTalk/Telegram/ServerChan/Aliyun-SMS credentials no longer end up in logs or exception messages.
- `jkit-notify`: four SMTP fixes — recipients/sender/Reply-To no longer accept CR/LF (stripped at config level, rejected at channel level), closing MIME-header/SMTP-command injection; the DATA phase now actually performs RFC 5321 dot-stuffing (the `dotStuff()` helper existed but was never wired in); `AUTH PLAIN` is preferred when advertised by EHLO (previously only `AUTH LOGIN`); a single rejected RCPT no longer aborts the whole email — only all-rejected fails, and partial rejections are listed in the result response.
- `jkit-notify`: new `NotificationChannel.validate(ChannelConfig)` (default no-op). `NotificationManager` invokes it before any network send, restoring the "programming errors never surface after a partial send" contract of `sendAll` / `sendFailover` — previously a missing webhook/token surfaced only after an earlier channel had already been sent. SMS and ntfy channels, whose URL building depends on the message, validate via a probe message instead.
- `jkit-notify`: `Message` gains `copy()`; SMS channels now stamp the current receiver on a copy per number instead of mutating the caller's message, so no `smsReceiver` leaks back and one message can fan out to several SMS channels concurrently.
- `jkit-notify`: `SendResult` aggregation now treats local suppression (SUPPRESSED) as the least severe category, so it no longer outranks throttling/config errors as the aggregate failure type.
- `jkit-notify`: `Attachment.contentBytes()` reads file-backed attachments in chunks instead of allocating the declared length upfront (large files OOMed); attachments over 2GB fail fast with a hint to use `openStream()`.
- `jkit-notify`: the channel registry (`register/unregister/get/list`) is now synchronized, so concurrent access can neither lose channels nor throw `ConcurrentModificationException`; `NotifyPolicy` dedup reservation and rate-limit counting moved into `beforeSend` as atomic operations (`afterAttempt` is kept for compatibility and no longer double-counts), so concurrent sends of the same message cannot both pass dedup and never exceed the rate limit; the default async pool is now 8 threads with a bounded queue of 1000 and CallerRunsPolicy backpressure instead of an unbounded queue.
- `jkit-notify`: SPI loading tolerates broken providers one by one — a damaged extension channel (missing dependency / constructor failure) is skipped with a warning instead of crashing the whole module with `ExceptionInInitializerError`.
- `jkit-notify-extra`: Aliyun SMS `RegionId` now follows `CFG_REGION` (configurable region) instead of being hardcoded to `cn-hangzhou`.

### Documentation

- `docs/sql.md` / `docs/en/sql.md`: entity table/column comments now name this module's `com.alianga.jkit.sql.entity.Comment`.
- `docs/sql.md` / `docs/en/sql.md`: classic Oracle ROWNUM wrapping now documents lifting `ORDER BY` off a set-op before pagination, and that `toSqlString` must name the target dialect.
- `docs/sql.md` / `docs/en/sql.md` business-scenario section: extra copy-paste samples for `bind` / `inject` / `expandStar`+`replaceSelectItems` / `addComment`+dialect quotes / MyBatis `#{}/ ${}`. The template-placeholder section now shows the common parse+bind pattern. Samples match `SqlBusinessScenarioTest`.
- Business scenarios expanded to 18 (2.0.2): dialect-from-URL (`JdbcUrlUtils`), report `DATE_FORMAT` rewrite, safe dynamic table-name bind, low-code query sandbox (Wall table allow/deny, required WHERE columns, max tables). Scenario 4 also covers tautology `LIKE '%'` / `XOR`.

## 2.0.1 - 2026-09-13

The repository has been split into multiple modules. This release delivers `jkit-sql`, `jkit-sql-auto` (with Spring Boot 2 / 3 starters), `jkit-notify`, `jkit-notify-extra`, and `jkit-curl-codegen` alongside the parent POM. The coordinate `com.alianga:jkit` still refers to `jkit-core`.

### Added

**jkit-sql**

- New module `com.alianga:jkit-sql`: zero-dependency hand-written SQL parser (lexical analysis via `char[]` + open-address keyword hashing, recursive-descent AST). Entry points: `SQL.parse` / `parseAll` / `parseExpr` / `format` / `toSqlString` / `tables` / `stat` / `parameters` / `parameterize` / `exportParameterValues` / `wall` / `clone` / `eval` / `rewrite`. Invalid SQL throws `SqlParseException`. Usage: see [`docs/sql.md`](/sql).
- **Dialects**: first-class enum `MYSQL` (default), `POSTGRES`, `ORACLE` (pre-12c ROWNUM pagination), `ORACLE12` (`OFFSET/FETCH`), `SQLSERVER`, `ANSI`, `H2`, `DB2`, `SQLITE`, `HIVE` (aliases `maxcompute`/`odps`/`argo`), `CLICKHOUSE`, `PRESTO` (alias `trino`), `DAMENG` (`dm`/`dameng`/`jdbc:dm:`). `fromName` covers GoldenDB / SelectDB / AnalyticDB / MatrixOne / StoneDB / HighGo / UXDB / MogDB / Vastbase / AntDB / IvorySQL / GBase 8a / GBase 8s / Xingyun / Oscar and more, aligning with icell `common-model` SQL data sources. Capabilities are driven by `SqlDialectSpec` and can be individually overridden via `SqlDialectWrapper` (e.g. MySQL `ANSI_QUOTES`).
- **Pagination**: `getLimit` / `getOffset` / `setLimit` / `setOffset` / `setPage` / `addLimit` / `SQL.adaptPagination`. `format` / `toSqlString` adapt to the target dialect (MySQL `LIMIT` ↔ classic ORACLE ROWNUM ↔ ORACLE12 `OFFSET FETCH` ↔ SQL Server `TOP` | `OFFSET FETCH`). `SqlBuilder.limit` / `offset` generate output based on the active dialect instead of always emitting `LIMIT`.
- **Rewrite chain**: `SqlRewriteHook` + `SqlRewrites` (`addLimit` / `setPage` / `andWhere` / `replaceTable` / `replaceColumn` / `adaptPagination` / `addSelectItem` / `removeSelectItem`, combinable with custom rules) + `SQL.rewrite` (deep-clone then mutate). Cross-dialect function rewriting covers JOIN ON / MERGE / OVER / column `DEFAULT`: `DATE_ADD` / `DATEDIFF` / `FROM_UNIXTIME` / `UNIX_TIMESTAMP` / `SUBSTRING` / `LEFT` / `RIGHT` / `DECODE` / `NVL2` / `UCASE`/`LCASE` / `CONCAT_WS` / `LPAD`/`RPAD` / `YEAR`/`MONTH`/`DAY`/`HOUR`/`MINUTE`/`SECOND` / `SYSDATE` / `LAST_DAY` / `CHAR`/`CHR` etc.; FULLTEXT upgraded to `MANUAL_ACTION_REQUIRED`. Function rewriting is extensible via `SqlSchemaConverterProvider.registerFunctions` (SPI override).
- **Entity DDL**: `SqlEntities` scans `@SqlTable` / JPA / MyBatis-Plus / MyBatis `@Alias` (no third-party compile dependencies). Public APIs: `columnSql` / `columnTypeSql` / `createIndex` / `orderByForeignKeys` / `extraSql` / `sequenceSql` / `indexName` / `createTable(..., includeIndexes)`. Recognizes any simple-name `Comment` annotation and Hibernate `@ColumnDefault`; `@SqlTable(comment)` / `@SqlColumn(comment)` generate dialect-aware comments; Oracle ≤11g appends SEQUENCE + TRIGGER when IDENTITY is unavailable.
- **Formatting & parse options**: `SqlFormatOptions.keywordCase` (`AS_IS` / `UPPER` / `LOWER`), `quoteIdentifiers`; `SqlParseOptions.pipesAsConcat` / `keepComments` / `placeholders()` (template placeholders) / `statementParsers()` (custom statement parsers keyed by leading keyword).
- **Security & extensibility**: `SQL.wall` → `SqlWallResult`; `SqlWallRule` chain + `SqlWallConfig.rules(...)` (built-in selectOnly / denyDdl / no-WHERE writes etc., behavior and violation codes unchanged). `SqlAstVisitor` type dispatch (non-breaking to `SqlVisitorAdapter`).
- **Statement AST**: `EXPLAIN` / `SET` / `COMMENT ON` / `SHOW` / `ANALYZE|VACUUM|OPTIMIZE` / transaction control / `COPY` / `FLUSH` / `START TRANSACTION` / `LOAD DATA` / `LOCK TABLES` / `PREPARE` / `BEGIN…END` / routines·triggers·events (`SqlRoutineParam` / `SqlControlStatement` / `SqlDeclareStatement` / `SqlHandlerStatement`) promoted to standalone types; `MODEL` / `MATCH_RECOGNIZE` structured; `RENAME TABLE` independent statement.
- **SqlBuilder**: fluent SELECT / INSERT / UPDATE / DELETE, `join` / `leftJoin` / `rightJoin` / `fullJoin` / `crossJoin` / `union` / `with` / `distinct` / `groupBy` / `having`.
- **Parsing coverage**: relative to Druid BVT / JSqlParser inline / file corpora approximately **96.3% / 92.3% / 90.0%**. Window functions, CTE, MERGE, PIVOT, flashback, procedure blocks, Hive / ClickHouse / Informix / DB2 edge-case syntax and more are documented in [`docs/sql.md`](/sql) rather than listed per iteration.

**jkit-sql-auto**

- New module `com.alianga:jkit-sql-auto`: auto-creates / updates tables at startup based on entities. Scans `@SqlTable` / JPA / MyBatis-Plus, compares against `DatabaseMetaData`, and executes `CREATE TABLE` / `ALTER TABLE ADD` / `CREATE INDEX`. Modes: `none` / `validate` / `update` (default, append only) / `create` / `create-drop`. Configuration prefix `jkit.sql.auto.*`, data source falls back to `spring.datasource.*`. Zero third-party runtime dependencies.
- `SqlAuto.drop` deletes managed tables in reverse foreign-key order; Spring Boot 2 / 3 auto-configuration modules `jkit-sql-auto-spring-boot-2` and `jkit-sql-auto-spring-boot-3`.
- Table / column comments executed per dialect (`COMMENT` / `COMMENT ON` / `sp_addextendedproperty`); Oracle ≤11g auto-increment primary keys use SEQUENCE + TRIGGER; Dameng uses IDENTITY on columns.
- `postgresIdentityStyle` / `foreignKeys` / `autoIncrement`: disable the corresponding DDL for products with limited capabilities such as OpenGauss / GBase 8a / DuckDB.
- `table-prefix` (`jkit.sql.auto.table-prefix` / chain `tablePrefix(String)`): prepends a prefix (e.g. `t_`) to all auto-generated table names; applies to create / alter / drop / indexes / sequences / foreign-key target tables.
- `index-prefix-enabled` (`jkit.sql.auto.index-prefix-enabled`, default `true`): whether auto-derived index names also carry the table prefix; explicit `@Index(name=…)` on entities is always preserved as-is.

**jkit-notify**

- New module `com.alianga:jkit-notify`: DingTalk (with signing), WeCom, Feishu (with signature), ServerChan, Bark, generic Webhook, SMTP (raw socket: AUTH LOGIN + STARTTLS/SSL + MIME). `NotificationChannel` SPI + `NotificationManager`; `MessageType` (TEXT / MARKDOWN / HTML). Zero third-party dependencies. Usage: see [`docs/notify.md`](/notify).
- Failure classification: `SendResult.failureType()` / `isRetryable()`, `FailureType` is RETRYABLE / THROTTLED / CONFIG_ERROR / PERMANENT / SUPPRESSED; unlisted error codes fall back to the HTTP status. Custom channels can override `AbstractHttpChannel.classify`.
- Messages truncated by UTF-8 **bytes** (no splitting of CJK characters or emoji); DingTalk @-mentions automatically append missing `@phone` into the body; `Message.var` / `vars` replace `${key}` / `${a.b}`; attachments streamed, split at `10MB` / `512KB`, MIME auto-detected.
- SMTP: `sslProtocols` pins the TLS version, `trustAllCerts` supports self-signed gateways; MARKDOWN sent as `text/html` via `NotifyUtils.markdownToHtml`. Feishu signature written into the JSON request body. `NotifyPolicy`: quiet hours, 5-minute deduplication, local rate limiting; `sendFailover` sequential account failover.
- Optional module `com.alianga:jkit-notify-extra`: Slack / Telegram / ntfy / Alibaba Cloud / Tencent Cloud / Yunpian / Huawei Cloud SMS. Channel extras live on their implementation classes, not in the core `Message`.

**jkit-core**

- `DateUtils.parse(String)`: auto-detects timestamps, compact numerics, `-` / `/` `.`, Chinese / Korean formats, ISO-8601 (including `T`/`Z`/`+0800`/`+08:00`).
- `DateUtils.fromEpochNumber(long)`: converts 10-digit seconds or 13-digit milliseconds to `Date`.
- `DateUtils.fromTemporal(TemporalAccessor)`: converts `java.time` objects to `Date`.
- `ConvertUtils.toDate` additionally supports `Instant`, `OffsetDateTime`, `ZonedDateTime`.
- `HttpUtils.debug` / `HttpUtils.printCurl`: logs the request summary or equivalent curl to stdout before sending (do not enable in production).
- `ConfigLoadOptions.addLocation`: appends directories to the default search path (`classpath:` / `file:` / bare path / `File`, `~` expanded to `user.home`).
- `Print.enableLog`: when `false`, prints to console only without writing to JUL.
- `com.alianga.jkit.log.LocaleFormatter`: JUL formatter with a fixed Locale.

**jkit-curl-codegen**

- Extracted from jkit as a standalone artifact `com.alianga:jkit-curl-codegen`, entry point `CurlCodegen.generate(id, curl)`. Covers 34 targets: Java (jkit / JDK 11+ / OkHttp / Apache 5 / HttpURLConnection / Unirest), Kotlin, JavaScript (fetch / axios / request / jQuery / XHR etc.), Python (requests / httpx), Go, C#, PHP, R, Rust, Swift, Ruby, Lua, PowerShell, curl (Windows cmd / PowerShell), wget, plus `http` / `har` / `httpie` interop formats.

### Changed

- `jkit-sql`: **Breaking** — `SQL.andWhere` / `replaceTable` / `replaceColumn` now use the same clone-then-mutate pattern as `addLimit` / `setPage` (return a new AST, leave the original tree untouched); callers must use the return value.
- `jkit-sql`: `SQL.clone` now performs a true AST tree copy (`SqlAstCloner` / `SqlNode.copy`); hot paths no longer format→parse; cross-dialect `format` / `adaptPagination` / `setPage` significantly faster. Classic ORACLE offset=0 single-layer ROWNUM wrapping skips cloning.
- `jkit-sql-auto`: `dryRun(true)` on `SqlAuto.run(options)` / `plan(options)` no longer opens a JDBC connection / DataSource. The URL is only used to infer the dialect; a full `CREATE TABLE` plan is produced against a blank database. Overloads that receive a `Connection` still compare against live tables but do not execute.
- `jkit-core`: `DateUtils` drops `SimpleDateFormat`. `yyyy-MM-dd HH:mm:ss` uses `char[]` + second-level cache; other common patterns use hand-written assembly or `DateTimeFormatter`. `ConvertUtils.toDate(Object)` refactored to extract by numeric field. Common string parsing roughly an order of magnitude faster; results aligned with ZmlTools.
- `jkit-core`: JUL default format changed to English level names (`WARNING` / `SEVERE`), no longer following the JVM default locale.
- `jkit-core`: `RandomUtils.randomBirth` switched to `LocalDate` (no longer throws when `minAge == maxAge`); `getUUID` dash removal no longer uses regex; `IdCardUtils` random birthday generates by actual days in the month.
- `jkit-core`: curl parsing aligned with curl semantics (expands `-kLs`/`-XPOST`, `--json` / `--data-urlencode`, `@file` no longer treated as literal body). Public APIs: `parseCurl` / `curlToRequest` / `curl` / `curlString` / `requestToCurl`, model `ParsedCurlRequest`. `CurlParser.generate` and the `http.codegen` package removed from jkit core; depend on `jkit-curl-codegen` instead.
- `jkit-notify`: core `ChannelConfig` removes SMS-specific `template` / `appId` / `region`; replaced by `ChannelConfig.extra` + `AbstractSmsChannel.CFG_*`. Core channels registered only via `defaults()`; SPI loads only extra modules to avoid double registration.
- `jkit-notify-extra`: `SlackChannel.EXTRA_CHANNEL` replaces using `Message.EXTRA_GROUP` for Slack channels (brief backward-compatible fallback to `EXTRA_GROUP`).

### Fixed

- `jkit-core`: when the response declares `Content-Encoding: gzip` but the body is empty, JDK 8 no longer throws `EOFException`; returns an empty stream (underlying connection is still reclaimed on close).
- `jkit-sql`: when a subclass and a `MappedSuperclass` / parent class both declare a same-named column, only the subclass field is kept, preventing PostgreSQL `column specified more than once`.
- `jkit-sql`: `@GeneratedValue(generator="system-uuid")` / `GenerationType.UUID` / non-integer `@SqlGenerated` no longer produce `AUTO_INCREMENT` / `IDENTITY` (PostgreSQL would syntax-error on `IDENTITY` for a `VARCHAR` primary key).
- `jkit-sql`: auto-generated index / sequence / trigger names are truncated to the dialect identifier length limit (classic Oracle 30 characters; when exceeded, the prefix is kept plus a 4-character hash). Unnamed indexes changed to `{table}_{col}_idx`; multiple unnamed indexes on the same table no longer collide. Public APIs: `SqlDialectSpec.maxIdentifierLength` / `fitIdentifier`.
- `jkit-sql`: `SQL.format` pretty mode now wraps and indents `CREATE TABLE` columns (`toSqlString` / compact still single-line).
- `jkit-sql`: `GROUP_CONCAT` without an explicit `SEPARATOR` no longer loses the separator when rewriting to `STRING_AGG` / `LISTAGG`; MySQL `MODIFY` / `CHANGE` rewritten to PG/ANSI/H2/PRESTO no longer duplicate the column name.
- `jkit-sql`: `SUBSTRING` / `LEFT` / `RIGHT` rewritten per dialect (PG/MySQL/H2 use `FROM n FOR m`; SQL Server/SQLite/Hive/ClickHouse use comma form; negative start rewritten with `LENGTH/LEN`); Dameng bare SELECT appends `FROM dual`.
- `jkit-sql`: `SqlDialectWrapper` no longer delegates derived methods (`identQuoteClose` / `quoteIdent` / `pipesAreConcat` / `preferredLimitStyle`); subclass overrides of primitives now automatically cascade to derived capabilities.
- `jkit-sql`: rewrite fidelity — `NATURAL JOIN` no longer loses its modifier; `RENAME TABLE` no longer writes illegal `ALTER TABLE`; `ADD UNIQUE KEY` no longer drops `UNIQUE`; `IGNORE` / `LOW_PRIORITY` / `HIGH_PRIORITY` on `INSERT/UPDATE/DELETE` are preserved in the AST and rewritten; non-bare-identifier aliases are force-quoted.
- `jkit-sql`: `tables()` now captures `CREATE TRIGGER` / `CREATE TABLE … LIKE` / `RENAME TO` / multi-table `OPTIMIZE|ANALYZE`; database names, routine names, and CTE names are no longer miscounted. Comment-only / blank-only input is classified as `OTHER` instead of throwing `empty SQL`.
- `jkit-sql-auto`: on classic Oracle, auto-generated `CREATE INDEX` names exceeding 30 characters are now truncated to avoid ORA-00972.
- `jkit-curl-codegen`: R (httr2) now uses `req_perform()`; PowerShell 5.1 restricted headers / cookie commas / Chinese encoding fixed; Windows curl split into cmd (`shell-curl-windows`) and PowerShell (`shell-curl-powershell`) generators.

### Build

- Repository converted to multi-module: parent POM `com.alianga:jkit-parent`, runtime library in `jkit-core` (release coordinate remains `com.alianga:jkit`), other capabilities in separate modules. `mvn test` at the root builds all modules.
- `git-commit-id-plugin`, `buildnumber-maven-plugin`, `maven-source-plugin` moved from the `publish` profile to the default build; `package` now produces sources jars with build info.
- The full reactor can be built with JDK 8 launching Maven: other modules compile against JDK 8; `jkit-core`'s `META-INF/versions/9`, `/11` use toolchains targeting JDK 9 / 11; only `jkit-sql-auto-spring-boot-3` uses toolchain for JDK 17 (required by Spring Boot 3). Requires `~/.m2/toolchains.xml` declaring jdk 8/9/11/17/21. Launch example: `JAVA_HOME=$(jdk8) mvn -o clean install`.
- `jkit-notify` live-use tests require `-Djkit.notify.live=true` and complete YML credentials; default `mvn test` never sends live messages even when keys are present.

### Documentation

- `docs/sql.md` / `docs/en/sql.md` updated to align with code: cross-dialect pagination, `SQL.clone`, `SqlRewrites`, `SqlEntities` public methods, and dialect tables; API coverage is now aligned on both sides. READMEs and module READMEs removed internal development-plan links.
- `docs/sql-auto.md` (and its English version) reordered to follow "pick package → write entity → Spring Boot / non-Spring → configuration" flow; module READMEs and quick-start pages synchronized.

## 2.0.0

jkit's first public release, migrated from [ZmlTools](https://github.com/wuyongshi/ZmlTools): zero third-party dependencies, package renamed to `com.alianga.jkit`, coordinate `com.alianga:jkit:2.0.0`.

Capability summary in [README.md](https://github.com/zhengmingliang/jkit/blob/develop/README.md): CSV / HTTP / JSON / YAML / configuration / expressions etc. all rewritten with pure JDK. Projects migrating from ZmlTools can continue using `relocated/zmltools` to redirect `top.wuyongshi:ZmlTools:2.0.0` to this coordinate (package names still require manual replacement).

---

## How to Record Future Versions

1. Change the `<version>` in the root `pom.xml` (`jkit-parent`) to the new version number; child modules inherit it. Update the README header and dependency examples, and the version printed by `Main.java`.
2. Insert the new version **above** the current latest-version section, with today's date (`YYYY-MM-DD`). **Each version number gets exactly one H2 heading**; before release, append new items to that section — do not open a separate `## x.y.z / unreleased`.
3. Document caller-visible changes under **Added / Changed / Fixed / Build** (use bold module names for grouping in multi-module projects). Do not simply paste git titles, and do not record internal milestones or competitor-coverage rounds (those go in `docs/sql.md` / `docs/next-plan.md`).
4. Add `@since x.y.z` to javadoc for newly added public APIs.
5. Leave historical versions in place; do not rewrite old entries as new versions.

Template:

```markdown
## x.y.z - YYYY-MM-DD

### Added
- ...

### Changed
- ...

### Fixed
- ...

### Build
- ...
```

Omit any section heading when there are no changes in that category.
