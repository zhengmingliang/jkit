# SQL Parsing Module

`com.alianga:jkit-sql` is a zero-dependency SQL parser: a hand-written lexer (`char[]` + open-addressing keyword hashing) and recursive descent producing an AST, with support for formatting, table/column statistics, and rewriting.

Design references:

- **Druid SQL Parser**: hand-written parsing, thread-local Parser reuse, `SchemaStatVisitor`-style table/column extraction, production-grade throughput
- **JSqlParser**: AST + Visitor, `TablesNamesFinder`, pretty/compact write-back

It does not execute SQL and does not pull in any JDBC driver.

## Getting Started

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.1</version>
</dependency>
```

Depends on `com.alianga:jkit` (logging, etc.); no other third-party libraries. JDK 8+.

## Parsing

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

// Fault-tolerant multi-statement parsing (audit): failed entries become SqlSimpleStatement with parseError() set; parsing continues with the next statement
List<SqlStatement> audit = SQL.parseAll(sql, SqlDialect.MYSQL, true);

// Bare expression (must consume the entire input; trailing garbage throws SqlParseException)
SqlExpr pred = SQL.parseExpr("tenant_id = ?");
SqlExpr fn = SQL.parseExpr("REPLACE(email, '@', '^-^')");
SqlExpr withOpt = SQL.parseExpr("age > @age@", SqlDialect.MYSQL,
        SqlParseOptions.defaults().placeholders(SqlPlaceholders.create().atWrapped()));
```

The default dialect is **MySQL** (GBase / MariaDB / TiDB share the same set). First-class dialect enums:

```java
SQL.parse(sql, SqlDialect.POSTGRES);
SQL.parse(sql, SqlDialect.ORACLE);    // pre-12c: pagination rewrite uses ROWNUM
SQL.parse(sql, SqlDialect.ORACLE12);  // 12c+: OFFSET/FETCH available
SQL.parse(sql, SqlDialect.SQLSERVER);
SQL.parse(sql, SqlDialect.ANSI);
SQL.parse(sql, SqlDialect.H2);
SQL.parse(sql, SqlDialect.DB2);         // FETCH FIRST only
SQL.parse(sql, SqlDialect.SQLITE);      // LIMIT family, no FETCH FIRST
SQL.parse(sql, SqlDialect.HIVE);        // backticks, || is concat; aliases maxcompute/odps
SQL.parse(sql, SqlDialect.CLICKHOUSE);  // backticks, double quotes are identifiers, comma-style LIMIT
SQL.parse(sql, SqlDialect.PRESTO);      // double quotes, || is concat; alias trino

SqlDialect.fromName("gbase");     // MYSQL
SqlDialect.fromName("gaussdb");   // POSTGRES
SqlDialect.fromName("dm");        // ORACLE (ROWNUM)
SqlDialect.fromName("oracle12");  // ORACLE12
SqlDialect.fromName("19c");       // ORACLE12
SqlDialect.fromName("tidb");      // MYSQL
SqlDialect.fromName("sqlite");    // SQLITE
SqlDialect.fromName("db2");       // DB2
SqlDialect.fromName("hive");      // HIVE
SqlDialect.fromName("clickhouse");// CLICKHOUSE
SqlDialect.fromName("trino");     // PRESTO
// Domestic & mainstream aliases: goldendb/selectdb/analyticdb/matrixone/stonedb/oceanbase/polardb/tdsql/starrocks/doris → MYSQL;
// highgo/uxdb/mogdb/vastbase/antdb/ivorysql/kingbase/opengauss/greenplum → POSTGRES; dm/oscar → ORACLE
// Aligned with icell common-model data sources: argo/argodb → HIVE (Transwarp Hive JDBC),
// xcloud → POSTGRES (XCloud), gbase8a → MYSQL, gbase8s → SQLITE (double quotes, LIMIT, no FETCH)

// Capability queries (single source of truth for rewriting/formatting)
SqlDialect.MYSQL.supportsLimitOffset();   // true
SqlDialect.SQLSERVER.supportsTop();       // true
SqlDialect.ORACLE.supportsFetchFirst();   // false (pre-12c)
SqlDialect.ORACLE12.supportsFetchFirst(); // true
SqlDialect.ORACLE.supportsRownum();       // true
SqlDialect.POSTGRES.pipesAreConcat();     // true
SqlDialect.MYSQL.quoteIdent("user");      // `user`
```

Invalid SQL throws `SqlParseException` with line number, column number, and nearby source text; it never returns a half-built tree.

## Template Placeholders (Optional)

**Template SQL** such as common-model uses placeholder slots like `@age@`, `%s`, `<sheet>`, `<-sheet->`. Placeholder parsing is **off** by default (staying strict); enable it explicitly via `SqlParseOptions.placeholders()` when needed:

```java
SqlParseOptions opt = SqlParseOptions.defaults()
        .placeholders(SqlPlaceholders.create()
                .atWrapped()    // @name@
                .printf()       // %s / %d / %f …
                .angle()        // <sheet>
                .arrowAngle()   // <-sheet->
                .add("{{*}}")); // custom: exactly one * marks the body

SqlStatement stmt = SQL.parse(
        "select * from <20241230.1> where age > @age@ and name in (%s)",
        SqlDialect.MYSQL, opt);
```

You can also use `SqlPlaceholders.create().commonModelTemplates()` to enable all four built-in presets above at once.

Rule summary:

| Pattern | Meaning | Lexer result |
|------|------|----------|
| `@*@` | identifier body wrapped in `@` on both sides | `IDENT` (usable as a column/value atom) |
| printf | `%` + one letter | `IDENT` |
| `<*>` / `<-*->` | table-name placeholder (allows `.` and `-`) | `IDENT` (usable as a table name) |
| custom <code v-pre>{{*}}</code> etc. | non-empty prefix/suffix + body | `IDENT` |

When not configured, `@age@` / `%s` / `<sheet>` still fail as before or get split into operators. Deliberately incomplete statements (e.g. `select * from`) fail even with placeholders enabled.

## DELIMITER (Batch Terminator)

MySQL client commands such as `DELIMITER ;;` / `DELIMITER $` / `DELIMITER //` parse as `SqlSimpleStatement.OTHER` and **switch** the batch terminator used by subsequent `parseAll` / procedure-body trailing splits (default `;`). When `//` coincides with division, it is not treated as a binary operator at statement boundaries. Statements inside a procedure body are still separated by `;`, independent of the client DELIMITER.

```java
List<SqlStatement> batch = SQL.parseAll(
        "DELIMITER ;;\n"
      + "CREATE PROCEDURE p() BEGIN SELECT 1; END;;\n"
      + "DELIMITER ;\n"
      + "CALL p()",
        SqlDialect.MYSQL);
```

## Statistics and Rewriting


```java
SqlSchemaStat stat = SQL.stat(sql);
stat.tableNames();
stat.getColumns();
stat.getConditions();       // compact fragments from WHERE / JOIN ON / HAVING
stat.getOrderByColumns();
stat.getGroupByColumns();
stat.getTables();           // Map<String, SqlTableAccess>; the same table can be INSERT+SELECT
SQL.isReadOnly(stmt);       // true for SELECT / SHOW / EXPLAIN — handy for read/write splitting

SqlStatement limited = SQL.addLimit(stmt, 100); // clones first, then appends LIMIT; the original tree is untouched
SQL.getLimit(stmt);                              // Long, from LIMIT/TOP
SQL.getOffset(stmt);
SqlStatement page = SQL.setPage(stmt, 2, 20, SqlDialect.MYSQL); // clone; offset=20
SQL.setLimit(stmt, 50, SqlDialect.POSTGRES);
SQL.setOffset(stmt, 10, SqlDialect.POSTGRES);
SqlStatement w = SQL.andWhere(stmt, "tenant_id = ?"); // internally parseExpr + clone, then AND WHERE
SqlStatement t2 = SQL.replaceTable(w, "users", "users_archive"); // clone
SqlStatement c2 = SQL.replaceColumn(t2, "name", "user_name");   // clone; skips table names/aliases
SqlStatement c3 = SQL.addSelectItem(c2, "status");              // clone; appends a select item
SqlStatement c4 = SQL.removeSelectItem(c3, "name");             // clone; removes by simple column name (cannot empty the list)
SqlStatement c5 = SQL.adaptPagination(c4, SqlDialect.ORACLE);   // clone; adapts pagination to the dialect
SqlStatement copy = SQL.clone(stmt); // AST deep copy (SqlAstCloner), no format→parse
```

`addLimit`: does not overwrite an existing LIMIT/TOP; writes `TOP` for SQL Server, `LIMIT` for everything else.
`andWhere` / `replaceTable` / `replaceColumn` / `addSelectItem` / `removeSelectItem` / `adaptPagination`: like `addLimit`/`setPage`, they now **clone before modifying** (breaking change: old code relying on in-place mutation must switch to using the return value).
`setLimit` / `setOffset` / `setPage`: **replace** pagination; in `setPage(pageNo, pageSize)`, pageNo starts at 1.
Dialects: MySQL/PG/H2/ANSI → `LIMIT`/`OFFSET`; SQL Server uses `TOP` on page 1 and `OFFSET FETCH` afterwards; **`SqlDialect.ORACLE` (pre-12c)** bare SELECT → **ROWNUM wrapping** (single-level `WHERE ROWNUM<=n`, double-level when offset > 0); **`ORACLE12` (12c+)** → `OFFSET … FETCH FIRST … ROWS ONLY`. For pre-existing Oracle `ROWNUM` double-nesting / `WHERE ROWNUM<=n` and SQL Server `row_number` wrappers: `getLimit` returns the page size, and `setPage`/`setLimit` only adjust the numeric bounds (without stacking OFFSET/FETCH). A UNION's LIMIT hangs at the end of the set-operation chain. `SqlBuilder.limit`/`offset`/`toSql(dialect)` share the same rewrite path (`toSql`'s dialect argument overrides the builder dialect).

### Rewrite chains (optional)

When several rewrites (custom + built-in) must run in order, compose them with `SqlRewrites` and execute with `SQL.rewrite`. A custom rule placed before the built-in adapters acts as a "pre hook"; after them, a "post hook". `SQL.rewrite` deep-copies first, so the original AST is untouched:

```java
SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
        .add(new TenantRule())                                   // pre hook: custom rule
        .add(SqlRewrites.replaceTable("users", "users_2026"))    // built-in adapter
        .add(SqlRewrites.andWhere(SQL.parseExpr("tenant_id = ?")))// built-in adapter
        .add(SqlRewrites.addLimit(100, SqlDialect.MYSQL)));      // post hook position is free
```

A rule is the `SqlRewriteHook` functional interface: it receives the current statement and returns the statement to pass on (mutate in place and return it, or substitute another; returning `null` throws `IllegalArgumentException`). The built-in adapters mirror the static `SqlRewriter` methods (`addLimit`/`setLimit`/`setOffset`/`setPage`/`andWhere`/`replaceTable`/`replaceColumn`/`addSelectItem`/`removeSelectItem`/`adaptPagination`) and act in place on the statement travelling down the chain.

## Parameterization / Wall / Evaluation (P2)

```java
String finger = SQL.parameterize("SELECT * FROM t WHERE name = 'a' AND age = 1");
// SELECT * FROM t WHERE name = ? AND age = ?

List<Object> litValues = SQL.exportParameterValues(sql); // "a", 1 — not ?/:name
List<String> binds = SQL.parameters(sql);                 // "?", ":name"

SqlWallResult wall = SQL.wall(sql); // parsing is not intercepted by default; call explicitly
wall.passed();
wall.violations(); // multi-statement / comment-bypass / always-true-condition / sleep-function / delete-without-where / update-without-where

// Configurable rules (SqlWallConfig); defaults() enables the safety-critical checks;
// denyUnion / denyInformationSchema / selectOnly are off by default
SqlWallConfig cfg = SqlWallConfig.defaults()
        .denyDdl(true)
        .denyDangerousFunctions(true)  // SLEEP / BENCHMARK / LOAD_FILE …
        .denyIntoOutfile(true)
        .selectOnly(false);
SqlWallResult w2 = SQL.wall(sql, SqlDialect.MYSQL, cfg);

Object v = SQL.eval(expr); // literal arithmetic and comparison only; null when a column is read

stmt.accept(new SqlAstVisitor() {
    @Override protected boolean visitSelect(SqlSelect node) { return true; }
});
```

## Formatting

`format` / `toSqlString` write the AST back out (whitespace and comments are not preserved). **Semantic round-trip** (`parse → format → parse`) guarantees that `type()`, `tables()` (case-insensitive), and `isReadOnly()` match the original; the golden corpus `SqlGoldenCorpusTest` covers this fully. **Word-level fidelity** is additionally enforced by `SqlRoundTripFidelityTest`: the formatted text must equal the original after normalization (strip comments / strip all whitespace / drop standalone `AS` / unify case — only pure layout differences are tolerated), across 118 tricky statements covering JOIN modifiers, DDL keywords, multi-group `RENAME`, quoting styles, DML modifiers, and JDBC escapes — silently **dropped words** (e.g. `NATURAL LEFT JOIN` losing `LEFT`, `STRAIGHT_JOIN` losing `STRAIGHT`) fail the test outright. The same method is applied in batch to all 379 corpus lines by `SqlRoundTripFidelityCorpusTest` in `tools-test` (with extra semantic-equivalence normalizations and 5 justified whitelist entries, hard-asserted).

```java
SQL.format(stmt);                           // newlines and indentation
SQL.toSqlString(stmt);                      // compact single line
SQL.format(stmt, SqlDialect.MYSQL, true);

// Force dialect quotes on every identifier segment (default false; literals/keywords/*/function names untouched)
SqlFormatOptions opts = SqlFormatOptions.defaults().quoteIdentifiers(true);
SQL.format(stmt, SqlDialect.MYSQL, opts);
SQL.toSqlString(stmt, SqlDialect.POSTGRES, opts);
```

Identifiers that were already quoted in the input are written back per dialect: backticks for MySQL, double quotes for PostgreSQL/Oracle/ANSI/H2, `[]` for SQL Server.
With `quoteIdentifiers` on, **unquoted** table/column names are force-quoted the same way.
`||` is written back from the AST (`CONCAT`→`||`; `OR` parsed by MySQL by default →`OR`).

The write-back is pretty-printing; **comment and whitespace round-trip is not guaranteed**.

## Dialect Differences

| Aspect | MYSQL | POSTGRES / ANSI / ORACLE |
| --- | --- | --- |
| Identifier quoting | backticks `` ` `` | double quotes |
| Double quotes | treated as string by default | treated as identifier |
| `\|\|` | logical OR (`SqlParseOptions.pipesAsConcat(true)` switches to concatenation) | string concatenation |
| `#` line comment | yes | no |
| Pagination | `LIMIT` / `LIMIT off,n` (`supportsLimitOffset`) | PG/ANSI/H2: LIMIT+FETCH; **ORACLE**: ROWNUM; **ORACLE12**: OFFSET/FETCH; SQL Server: TOP + OFFSET FETCH |
| `\|\|` capability | `pipesAsOr()` | `pipesAreConcat()` |

## Quick Building (SqlBuilder)

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

// Pagination follows the effective dialect: ORACLE→ROWNUM, ORACLE12/SQLSERVER→OFFSET/FETCH, MySQL→LIMIT
SqlBuilder.select("*").from("t").limit(10).offset(20).toSql(SqlDialect.ORACLE);
SqlBuilder.select("*").from("t").limit(10).offset(20).toSql(SqlDialect.ORACLE12);

// Force identifier quotes (toggleable; off by default)
SqlBuilder.select("id", "name").from("users").quoteIdentifiers(true).toSql();

SqlBuilder.insertInto("t").columns("id", "name").values(1, "a").toSql();
SqlBuilder.update("t").set("name", "b").where("id = 1").toSql();
SqlBuilder.deleteFrom("t").where("id = 1").toSql();

// AST-level composition (no string hacking)
SQL.and(SqlBuilder.parsePredicate("a=1"), SqlBuilder.parsePredicate("b=2"));
SQL.or(SqlBuilder.parsePredicate("a=1"), SqlBuilder.parsePredicate("b=2"));
SQL.concat(Arrays.asList(SQL.parse("SELECT 1"), SQL.parse("SELECT 2")));
SQL.builder().from("t").where("id = ?").limit(5).toSql();
```

The build result is an AST, which is then written back via `SQL.format` / `toSqlString`. The dialect argument to `toSql(dialect)` overrides the builder's own dialect and selects the pagination shape.

## Parameter Extraction

```java
List<String> params = SQL.parameters("SELECT * FROM t WHERE id = ? AND name = :name");
// ["?", ":name"]  — bind placeholders

List<Object> literals = SQL.exportParameterValues("SELECT * FROM t WHERE name = 'a' AND age = 1");
// ["a", 1]  — literal values (kept separate from parameters)
```


## Alias API Note

Read aliases with **`alias()`**:

- `SqlSelectItem.alias()` — column alias (including dotted forms such as `AS a.b`)
- `SqlTableSource.alias()` — table / subquery / table-function alias

Do not treat indexes of `SqlIdentifier.names()` as "the N-th alias"; `names()` is the list of qualified-name segments (`db.schema.table`) and is unrelated to aliases.

## v1 Coverage

- SELECT: columns, `*`, `t.*`, DISTINCT / DISTINCTROW / DISTINCT ON, HIGH_PRIORITY / STRAIGHT_JOIN modifier / SQL_SMALL_RESULT / SQL_BIG_RESULT / SQL_BUFFER_RESULT / SQL_CACHE / SQL_NO_CACHE / SQL_CALC_FOUND_ROWS, TOP, `INTO` table / `@var` / multi-var `INTO c,d` / `INTO (c,d)` / `OUTFILE` (target table extracted into `tables`/`INSERT`), ODPS `FORCE PARTITION / `SIGNED INTEGER` / `IGNORE NULLS` / `LIMIT BY` / jsonb `?` 'pt'` / `FORCE ALL PARTITIONS`, FROM (including MySQL `PARTITION (p0,p1)` table partition restriction; Oracle `PARTITION BY (expr)` partitioned outer join; `FORCE/USE/IGNORE INDEX` also after alias), JOIN (INNER/LEFT/RIGHT/FULL/CROSS/NATURAL/STRAIGHT/comma; `LEFT|RIGHT ANTI|SEMI JOIN`), `CROSS APPLY` / `OUTER APPLY`, `LATERAL` subquery/table function, `UNNEST(...) [WITH ORDINALITY]` / `TABLE(fn(...))` / `TABLE(SELECT…)` / `OPENJSON(...) WITH (...)` table functions, `PIVOT` / `UNPIVOT [INCLUDE|EXCLUDE NULLS]` (including `((SELECT…) PIVOT/UNPIVOT …)` parenthesized table sources), `(VALUES …) AS v(cols)`, parenthesized set-op subqueries `((SELECT…) UNION …)`, ON/USING, WHERE, GROUP BY [WITH ROLLUP|WITH CUBE|DISTINCT|GROUPING SETS (comma-chained multiples allowed)], HAVING, Teradata/Snowflake `QUALIFY` window filter, `WINDOW … AS (…)` (can inherit another window name), ORDER BY, LIMIT/OFFSET/`FETCH FIRST n ROWS ONLY`, FOR UPDATE [OF cols] [NOWAIT|SKIP LOCKED], LOCK IN SHARE MODE, UNION/UNION ALL/INTERSECT/EXCEPT/MINUS, CONNECT BY [NOCYCLE] / START WITH / PRIOR / `CONNECT_BY_ROOT`, WITH CTE (including Oracle `SEARCH DEPTH|BREADTH FIRST BY … SET` / `CYCLE …`); Hive UDTF multi-column aliases `fn(...) AS (c0,c1)`
- Oracle / temporal: `MODEL` → `SqlModelClause` (PARTITION/DIMENSION/MEASURES/RULES; `RULES UPSERT SEQUENTIAL ORDER`; literal/`AS` measure aliases; allowed after WHERE; `RULES` → `SqlModelRule`, `cellDims`/`cellDimExprs`, `raw` on failure); `MATCH_RECOGNIZE` → `SqlMatchRecognize` (`PARTITION BY`/`ORDER BY`/`MEASURES`/`PATTERN` string/`DEFINE`/`SUBSET`/`WITHIN`, `ROWS PER MATCH`/`AFTER MATCH` fields; no DSL tree for `PATTERN`); table-level `AS OF TIMESTAMP|SCN`, `VERSIONS BETWEEN TIMESTAMP|SCN … AND …`, SQL Server `FOR SYSTEM_TIME AS OF`; ClickHouse parameterized functions `fn(params)(args)`
- Window functions: `OVER (PARTITION BY ... ORDER BY ... ROWS/RANGE BETWEEN ...)`, named window reference `OVER w`, SELECT-level `WINDOW w AS (...)` (multiple allowed; `w2 AS (w)` / `w2 AS (w ORDER BY …)` inheritance), `FILTER (WHERE ...)`, Spark `OVER (DISTRIBUTE BY … SORT BY …)` (`SqlOverExpr.sparkStyle`)
- Special functions: `EXTRACT(field FROM expr)`, `TRIM(BOTH/LEADING/TRAILING ... FROM expr)`, `SUBSTRING(expr FROM n FOR m)`, `POSITION(a IN b)`, `IF(a,b,c)` (MySQL), `CONVERT(expr USING charset)` / `CONVERT(type, expr)` (SQL Server), `GROUP_CONCAT(... ORDER BY ... SEPARATOR ...)`, `STRING_AGG(... ORDER BY ...)` / `WITHIN GROUP (ORDER BY ...)`, `MATCH (cols) AGAINST (...)` (including `WITH QUERY EXPANSION`), `WEIGHT_STRING(… AS CHAR(n) LEVEL n [DESC])` (MySQL 8), SQL/JSON constructors (`json_object`/`json_array`/`json_objectagg`/`json_arrayagg`/`json_table` — key:value, KEY…VALUE, ON NULL, UNIQUE KEYS, FORMAT JSON, COLUMNS…PATH kept verbatim), XML functions (`XMLSERIALIZE`/`XMLPARSE`/`XMLROOT`/`XMLAGG`/`XMLELEMENT`/`XMLFOREST`/`EXTRACTVALUE`), `TRANSLATE(… USING CHAR_CS)`
- INSERT / REPLACE: column list, multi-row VALUES, INSERT SELECT, `INSERT … (WITH … SELECT …)`, INSERT SET, ON DUPLICATE KEY UPDATE, PG `ON CONFLICT` (`DO NOTHING` / `DO UPDATE` / `ON CONSTRAINT`), `RETURNING` (`*` or multi-column list), SQL Server `OUTPUT` / `OUTPUT … INTO`, Oracle `INSERT ALL` / `INSERT FIRST`; MySQL `LOW_PRIORITY` / `DELAYED` / `HIGH_PRIORITY` / `IGNORE` modifiers preserved and written back; Hive `INSERT OVERWRITE [TABLE] t [PARTITION (...)] SELECT …` (`SqlInsert.overwrite`/`tableKeyword`/`partitionRaw`); ODPS `UPDATE/DELETE FORCE PARTITION …`
- UPDATE / DELETE: JOIN, WHERE, ORDER BY, LIMIT, PG `UPDATE … FROM`, PG/MySQL `DELETE … USING`, `RETURNING` (multi-column), SQL Server `OUTPUT` / `OUTPUT … INTO` (table / `@var` / `#tmp`, included in `tables()`); MySQL `LOW_PRIORITY` / `QUICK` / `IGNORE` modifiers preserved and written back; MySQL multi-table delete, second form `DELETE FROM a1, a2 USING …` (`SqlDelete.targets`)
- MERGE: INTO / USING / ON, multiple `WHEN MATCHED [AND pred]`, `WHEN NOT MATCHED [BY TARGET|SOURCE]`, `UPDATE … DELETE WHERE`, `INSERT … VALUES … WHERE`, `OUTPUT` / `OUTPUT … INTO`
- DDL: CREATE/DROP/ALTER TABLE|VIEW|INDEX|DATABASE|PROCEDURE|FUNCTION|TRIGGER|EVENT (object name extracted; `CREATE OR REPLACE`; AS query for VIEW/CTAS; routine parameters → `SqlRoutineParam`, `FUNCTION RETURNS` → `returnsType`, BEGIN body → `bodyStatements` (`bodyRaw`/`tail` kept for round-trip); CREATE TABLE column definitions verbatim (`columnDefinitions`) + ENGINE/CHARSET/COLLATE/COMMENT + table-level FOREIGN KEY referenced tables; ALTER ADD/DROP INDEX, RENAME TO, CHANGE/MODIFY column definitions, ADD CONSTRAINT); `CREATE TYPE … AS OBJECT/VARRAY/ENUM` kept verbatim; `DROP … PURGE` / `DROP TABLESPACE … ENGINE`, `TRUNCATE … PURGE SNAPSHOT LOG` tail clauses; MySQL 8 functional index `ADD KEY idx ((expr))`; CTAS suffix `WITH [NO] DATA`
- `EXPLAIN`/`DESCRIBE` → `SqlExplainStatement` (options such as ANALYZE/FORMAT/BUFFERS + nested statement), `SET` → `SqlSetStatement` (multiple assignments / NAMES / CHARACTER SET / SESSION|GLOBAL), USE, SHOW, CALL (arguments into AST), TRUNCATE, GRANT / REVOKE (privileges + ON object name; recipient `user@host` written back compactly; REVOKE uses FROM)
- Procedural blocks / maintenance / transactions: `BEGIN … END` / top-level anonymous `DECLARE … BEGIN … END` → `SqlBlockStatement`; session-style `DECLARE x INT` (OTHER); in-procedure `DECLARE`/`CURSOR FOR` → `SqlDeclareStatement`, `CONTINUE|EXIT|UNDO HANDLER` → `SqlHandlerStatement`; `IF`/`WHILE`/`LOOP`/`REPEAT`/`CASE…END CASE`/`LEAVE`/`ITERATE`/`RETURN` → `SqlControlStatement` (optional loop labels); `TRIGGER` extracts `triggerTiming`/`triggerEvent`/`triggerTable`/`triggerUpdateColumns`/`FOR EACH`/`FOLLOWS|PRECEDES`; `EVENT` extracts `ON SCHEDULE AT|EVERY`, `eventStarts`/`eventEnds`/`eventEnabled`/`eventComment`/`eventOnCompletion`/`eventDisableOnSlave`;  bare `BEGIN` / `BEGIN WORK` / `START TRANSACTION` → `SqlStartTransactionStatement` (isolation level / READ WRITE|ONLY / WITH CONSISTENT SNAPSHOT); `COMMIT` / `ROLLBACK [TO SAVEPOINT]` / `SAVEPOINT` / `RELEASE SAVEPOINT` → `SqlTransactionControlStatement`; `FLUSH …` → `SqlFlushStatement` (option list / TABLES table names); `LOCK TABLES`/`UNLOCK TABLES` → `SqlLockTablesStatement`; `ANALYZE` / `VACUUM` / `OPTIMIZE|REPAIR|CHECK TABLE` → `SqlMaintenanceStatement` (tables + optionsRaw); `SHOW CREATE TABLE|VIEW|DATABASE` / `SHOW COLUMNS|INDEX|TABLES` → `SqlShowStatement`; `COMMENT ON TABLE|COLUMN|…` → `SqlCommentOnStatement` (objectKind/name/comment); SQL Server `GO` batch separator; PG `COPY … FROM|TO` → `SqlCopyStatement` (table/columns/STDIN·PROGRAM·file + WITH verbatim); MySQL `LOAD DATA [LOCAL] INFILE … INTO TABLE` → `SqlLoadDataStatement` (file/table/columns + FIELDS·LINES·IGNORE verbatim); MySQL table `HANDLER t OPEN|READ|CLOSE` → `SqlTableHandlerStatement`; `PREPARE` / `EXECUTE` / `DEALLOCATE PREPARE` / `EXECUTE IMMEDIATE` → `SqlPrepareStatement` (name / FROM·source / USING)
- Expressions: literals, binds `?` / `:name` / `:0` / `@var`, adjacent string literal concatenation, arithmetic and comparison, AND/OR/XOR/NOT, IN (including parenthesis-free bind lists `IN :name` / `IN ?`)/BETWEEN/LIKE/ILIKE/`NOT ILIKE`/REGEXP, IS NULL, `IS DISTINCT FROM` / `IS NOT DISTINCT FROM`, CASE, CAST / `TRY_CAST` / `::`, functions (including `USING charset`), EXISTS, subqueries, function result field access `f(x).y`, `INTERVAL '1 day'` / `INTERVAL 1 DAY` / `INTERVAL … YEAR(n) TO MONTH`, `CAST(… AS INTERVAL DAY TO SECOND)`, `X'FF'` / `0xFF`, row constructors `(a,b)`, JSON `->` `->>` `#>` `#>>`, array subscript `arr[1]`, `= ANY/SOME/ALL (...)`; `INTERVAL` compound units (`HOUR_MINUTE`/`YEAR_MONTH` etc.) and expression values (`INTERVAL 6/4 HOUR_MINUTE`); charset-prefixed literals (`_latin1'x'` / `_utf8mb4'…'` / `_binary'…'` / `_utf32 X'…'`); `NOT REGEXP`; PL/SQL cursor attributes `SQL%FOUND` / `c1%NOTFOUND`; `count(UNIQUE …)` (equivalent to DISTINCT); JDBC/ODBC escape unwrapping (`{fn …}` functions, `{d|t|ts '…'}` typed literals, `{oj …}` joins, `{call …}`, `{escape …}`); plus PG `@>`/`<@`/`~`/`~*`, MySQL `FORCE INDEX FOR …`/`<=>`/`INSERT DELAYED`/`BINARY`, SQL Server `TOP WITH TIES`, `TABLESAMPLE`/`SAMPLE`, Oracle `(+)` outer-join suffix / `CONNECT_BY_ROOT`
- Comments: `--`, `/* */`, MySQL `#`; input containing only comments/whitespace parses as an `OTHER` empty statement (no empty SQL error); MySQL executable comments `/*!40101 … */` are expanded into inner SQL (not discarded wholesale); optimizer hints `/*+ … */` attach to the SELECT / table and can be written back by format; floating hints in arbitrary positions (e.g. `/*+TDDL:MASTER*/` inside WHERE, Trino `/*+joinMethod=…*/`) are absorbed and written back on the SELECT
- Identifiers: bare MySQL identifiers may start with digits (e.g. `32强国` / `1019使用`), as long as the whole token is not purely numeric; `32` / `32.5` / `32e1` / `0xFF` remain literals; the backquoted form worked all along; digit-leading segments after a dot in qualified names parse (`t.1_id` / `a.32强国`; leading decimals like `.5` stay NUMBER); single-quoted names after a dot act as quoted identifiers (`T.'Group'`); SELECT-list aliases may be dotted (`AS a.b`); function and table names support the Oracle DB Link suffix (`fn@dblink` / `t@dblink`, `SqlIdentifier.dblink`)
- Client / batch: `DELIMITER xx` switches the `parseAll` terminator (see "DELIMITER"); SQL Server `GO` batch separator
- Parse options: with `SqlParseOptions.keepComments(true)` (default false), regular comments go into `SqlStatement.comments()`; the hot path still discards them by default; `SqlParseOptions.pipesAsConcat(true)` makes `||` parse as concatenation under the MySQL dialect (equivalent to `PIPES_AS_CONCAT`); `SqlParseOptions.placeholders()` configures template placeholders (off by default, see "Template Placeholders"); `SQL.parseAll(sql, dialect, true)` enables fault-tolerant multi-statement parsing (failure placeholder + `parseError`, for auditing)

Explicitly not done: a procedure-body **execution engine** (structured AST already covers DECLARE/HANDLER/control flow/TRIGGER/EVENT etc., but nothing is interpreted), a complete Wall rule set (`SqlWallConfig` is a configurable subset, not the full Druid WallFilter), or a DSL tree for `MATCH_RECOGNIZE.PATTERN` (still a string). CREATE TABLE column types/constraints are captured in `columnDefinitions` and can round-trip through format. Unknown functions parse as ordinary function calls and do not fail.

## Cross-dialect type conversion (in progress)

Schema / SQL conversion uses a **Normal Form** (canonical types) so adding a dialect is O(K), not O(N²). Design: [sql-schema-converter-design.md](../sql-schema-converter-design.md).

Phase 0–1 are in tree (JDK 8; `SqlDdlStatement.columnDefinitions()` stays `List<String>`):

```java
List<ColumnDefinition> cols = SqlColumnDefinitionParser.fromDdl(ddl, SqlDialect.MYSQL);
SqlDataTypeRegistry types = SqlDataTypeRegistry.builtins();
types.convert("VARCHAR(100)", SqlDialect.MYSQL, SqlDialect.ORACLE); // VARCHAR2(100)
types.fromDialect("TINYINT(1)", SqlDialect.MYSQL);                  // BOOLEAN
```

Undeclared reverse collisions fail `RegistryValidator` at builtin-table build time.

Phase 2–8 add `SQL.convert` / `SQL.convertBatch` for CREATE TABLE and `ALTER TABLE ADD/MODIFY/CHANGE` column types, plus query functions (`IF`→`CASE`, `GROUP_CONCAT`↔`STRING_AGG`/`LISTAGG`, `IFNULL`/`NVL`, `CAST`, `LOCATE`/`INSTR`). MySQL table-level `KEY`/`INDEX` is stripped (with a warning) so the DDL can parse on the target. Real MySQL→PG CREATE TABLE execution lives in `tools-test` (`CrossDialectDdlExecutionTest`, Docker). JMH: `SqlSchemaConvertBenchmark`.

Supported first-class dialects (MySQL, PostgreSQL, Oracle 11g/12c, SQL Server, H2, ANSI, DB2, SQLite, Hive, ClickHouse, Presto) and `fromName` product aliases: [design doc §4](../sql-schema-converter-design.md). How to extend types/functions: [§9](../sql-schema-converter-design.md).

## Entity scan → DDL / DML

Like data-set `EntityScanner`, without Spring: scan classes annotated with `@SqlTable`, JPA `@Entity`, or MyBatis-Plus `@TableName`/`@TableId` (resolved by FQCN via reflection — no compile dependency on Spring / JPA / MyBatis), then generate DDL and CRUD per dialect. Also honours `@TableField` (`exist=false` skips the column), JPA `@Index`/`@Enumerated`/`@Embedded`; `List`/`Set`/`@OneToMany` fields are skipped unless annotated. `createTables` orders referenced tables first. Java types go through the canonical type registry.

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

`javax.persistence` / `jakarta.persistence` annotations (`@Entity` `@Table` `@Column` `@Id` `@GeneratedValue` `@Transient` `@Lob`) are recognised the same way.

### API

| Method | Purpose |
| --- | --- |
| `scan(String)` / `scan(List<String>)` | Scan a package for entity classes |
| `inspect(Class<?>)` | Parse into a `SqlEntityModel` (table, columns, id, indexes) |
| `createTable(Class<?>, SqlDialect)` | DDL for one table, with `CREATE INDEX` appended in the same batch |
| `createTable(SqlEntityModel, SqlDialect, boolean includeIndexes)` | `includeIndexes=false` emits `CREATE TABLE` only |
| `createTables(String basePackage, SqlDialect)` / `createTables(List<Class<?>>, SqlDialect)` | Many tables; referenced tables first |
| `orderByForeignKeys(List<Class<?>>)` | Ordering only; cycles and external refs keep their relative order |
| `dropTable(Class<?>, SqlDialect)` | `DROP TABLE` |
| `insert(Object, SqlDialect)` / `insertBatch(List<?>, SqlDialect)` | Single-row / multi-row `INSERT` |
| `insertPlaceholders(Class<?>, SqlDialect)` | `INSERT` with `?` placeholders, for `PreparedStatement` |
| `updateById(Object, SqlDialect)` / `deleteById(Class<?>, Object, SqlDialect)` | Update / delete by id |
| `selectById(Class<?>, Object, SqlDialect)` / `selectAll(Class<?>, SqlDialect)` | Select by id / select all |
| `columnSql(SqlEntityColumn, SqlDialect, boolean inlinePk)` | One column definition; pass `inlinePk=false` for `ALTER TABLE … ADD` |
| `columnTypeSql(SqlEntityColumn, SqlDialect)` | Type text only, for schema comparison |
| `createIndex(String tableName, String spec)` | A standalone `CREATE INDEX`; `spec` is `name:col1,col2` or `col1,col2` |

The last four exist for schema diffing and incremental `ALTER TABLE … ADD` — that is exactly what the auto-DDL module builds on:

```java
SqlEntityModel model = SqlEntities.inspect(DemoUser.class);

// Emit DDL and indexes separately
String ddlOnly = SqlEntities.createTable(model, SqlDialect.POSTGRES, false);
// CREATE TABLE demo_user (id BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY PRIMARY KEY, ...)
String idx = SqlEntities.createIndex("demo_user", "idx_user_name:user_name");
// CREATE INDEX idx_user_name ON demo_user (user_name)

// Compare types, build an ADD COLUMN
SqlEntities.columnTypeSql(model.idColumn(), SqlDialect.POSTGRES);            // BIGINT
String col = SqlEntities.columnSql(model.columns().get(1), SqlDialect.MYSQL, false);
// user_name VARCHAR(32) NOT NULL
String add = "ALTER TABLE demo_user ADD " + col;

// Batch insert and placeholders
SqlEntities.insertBatch(users, SqlDialect.MYSQL);
SqlEntities.insertPlaceholders(DemoUser.class, SqlDialect.MYSQL);
// INSERT INTO demo_user(user_name, email, age, amount) VALUES (?, ?, ?, ?)
SqlEntities.selectAll(DemoUser.class, SqlDialect.MYSQL);
// SELECT id, user_name, email, age, amount FROM demo_user

// Many tables: order by foreign keys, then create in one go
List<Class<?>> ordered = SqlEntities.orderByForeignKeys(entities);
String all = SqlEntities.createTables(ordered, SqlDialect.POSTGRES);
SqlEntities.dropTable(DemoUser.class, SqlDialect.MYSQL);   // DROP TABLE demo_user
```

`jkit-sql` only generates SQL. To execute that DDL against a live database at startup, use [jkit-sql-auto](./sql-auto.md).

```java
String pg = SQL.convert(
        "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, flag TINYINT(1) DEFAULT 0)",
        SqlDialect.MYSQL, SqlDialect.POSTGRES);
```

## Performance

Hand-written lexer + `ThreadLocal` Parser reuse. Comparison tests against Druid / JSqlParser live in **`tools-test`** in the parent directory (kept out of this module to avoid pulling in third-party dependencies):

```text
cd ../tools-test
mvn -Dtest=SqlParserCompareTest test
```

On the `tools-test` file corpus `sql-corpus.txt` (about **379** statements), **jkit scores 379/379 (100%)**; the embedded CORPUS (about 64 statements) is also fully green. Competitor gaps vary with the samples (Druid commonly fails on `DISTINCT ON` / WINDOW inheritance / UNNEST; JSqlParser commonly fails on `LOCK IN SHARE MODE` / `[dbo].[user]` / WINDOW inheritance).

Throughput is measured with **JMH** (`SqlParseBenchmark` in `tools-test`). Formal run (fork=2, warmup=5, iterations=5, Cnt=10, avgt, ns/op — lower is better; measured 2026-09-10 on i9-13900HX / OpenJDK 17.0.11):

| Engine | SIMPLE (single table) | JOIN (two tables) | WINDOW (window function) |
| --- | ---: | ---: | ---: |
| **jkit-sql** | **508** | **1,073** | **657** |
| Druid 1.2.23 | 1,274 (2.5×) | 3,667 (3.4×) | 3,023 (4.6×) |
| JSqlParser 4.9 | 220,445 (434×) | 252,464 (235×) | 301,220 (459×) |

jkit and Druid are both hand-written parsers, and jkit is consistently **2.5–4.6× faster**; the JavaCC-generated JSqlParser is over two orders of magnitude slower. Reproduce (about 30 minutes):

```text
cd ../tools-test
mvn -DskipTests package
java -jar target/benchmarks.jar com.alianga.test.sql.jmh.SqlParseBenchmark -f 2 -wi 5 -i 5
```

| | Parse success rate (file corpus) | Notes |
| --- | --- | --- |
| **jkit-sql** | **379/379 (100%)** | in-module golden corpus ~216 statements (including round-trip; `SqlGoldenCorpusTest` ~432 assertions) + 118 round-trip fidelity statements (`SqlRoundTripFidelityTest`) + 379 batch fidelity lines (`SqlRoundTripFidelityCorpusTest` in tools-test); `mvn -pl jkit-sql test` runs **889** tests |
| Druid 1.2.23 | below jkit (gaps in `target/sql-compare-fail.txt`) | comparison is not part of this library's dependencies |
| JSqlParser 4.9 | below jkit | same as above |

## Corpus and Comparison

- In-module: `SqlGoldenCorpusTest` (about **216** statements, including round-trip), `SqlRoundTripFidelityTest` (**73** statements, formatted text compared verbatim against the original after normalization), `CommonModelSqlCorpusTest` (harvested from `icell/common-model`, 87 parseable statements), `Complex100GiantsTest` / `SqlModelMatchDeepenTest` (structured MODEL / MATCH_RECOGNIZE fields).
- Comparison against Druid / JSqlParser lives only in the parent project's `tools-test` module as `SqlParserCompareTest` (success rate + table-name set diff + JMH; not part of this library's dependencies).
- Batch round-trip fidelity check: `SqlRoundTripFidelityCorpusTest` in `tools-test` (all **379** corpus lines compared verbatim after normalization, hard-asserted with 5 justified whitelist entries; report at `target/sql-fidelity-report.txt`).
- External corpora batch checks (also in `tools-test`, not a dependency of this library):
  - `ExternalSqlCorpusTest` — jkit parse success rates on bird / Spider / complex100 (soft-assert)
  - `ExternalSqlCorpusCompareTest` — jkit vs Druid vs JSqlParser accuracy + speed; reports under `target/sql-corpus-reports/`
  - Corpus notes: `tools-test/src/test/resources/sql-corpora/README.md` (recent compare: bird/spider_ddl/dev/train* / complex100 all 100%; spider_test ≈ 99.63%)

```text
cd ../tools-test
mvn -Dtest=ExternalSqlCorpusTest test
mvn -Dtest=ExternalSqlCorpusCompareTest test
```
