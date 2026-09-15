# SQL Parsing Module

`com.alianga:jkit-sql` is a zero-dependency SQL parser: a hand-written lexer (`char[]` + open-addressing keyword hashing) and recursive descent producing an AST, with support for formatting, table/column statistics, and rewriting.

Design references:

- **Druid SQL Parser**: hand-written parsing, thread-local Parser reuse, `SchemaStatVisitor`-style table/column extraction, production-grade throughput
- **JSqlParser**: AST + Visitor, `TablesNamesFinder`, pretty/compact write-back

It does not execute SQL and does not pull in any JDBC driver.

<MavenBadge artifact="jkit-sql" />

## Getting Started

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.2</version>
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

`tables()` only collects **tables that are actually accessed**: FROM/JOIN/INTO of DML/SELECT; for DDL it is object-type-aware — tables of `DROP/ALTER/TRUNCATE/RENAME TABLE`, the source of `CREATE TABLE t2 LIKE …`, `CREATE TRIGGER … ON t`, and every table of maintenance statements (`OPTIMIZE/ANALYZE/CHECK/REPAIR TABLE t, s`). VIEW names follow the existing semantics. **Not** collected: database names (`USE db`), routine/index/event object names (`CREATE INDEX idx`), CTE names (`WITH w AS (...) SELECT FROM w`), or the targets of `CALL sp` / `DECLARE` / `GRANT`.

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
SQL.parse(sql, SqlDialect.DAMENG);      // Dameng: double quotes, LIMIT/OFFSET, IDENTITY

SqlDialect.fromName("gbase");     // MYSQL
SqlDialect.fromName("gaussdb");   // POSTGRES
SqlDialect.fromName("dm");        // DAMENG
SqlDialect.fromName("oracle12");  // ORACLE12
SqlDialect.fromName("19c");       // ORACLE12
SqlDialect.fromName("tidb");      // MYSQL
SqlDialect.fromName("sqlite");    // SQLITE
SqlDialect.fromName("db2");       // DB2
SqlDialect.fromName("hive");      // HIVE
SqlDialect.fromName("clickhouse");// CLICKHOUSE
SqlDialect.fromName("trino");     // PRESTO
// Domestic & mainstream aliases: goldendb/selectdb/analyticdb/matrixone/stonedb/oceanbase/polardb/tdsql/starrocks/doris → MYSQL;
// highgo/uxdb/mogdb/vastbase/antdb/ivorysql/kingbase/opengauss/greenplum → POSTGRES; oscar → ORACLE; dm/dameng → DAMENG
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
SqlDialect.ORACLE.maxIdentifierLength();  // 30
SqlDialect.ORACLE.fitIdentifier("t_schedule_auth_resource_id_idx");
// over-long names keep a prefix plus a 4-hex hash, staying ≤30
```

When a built-in dialect is not enough, wrap it with `SqlDialectWrapper` and tweak individual capabilities (`SQL.parse*` / `format` / `setPage` / `wall` all take `SqlDialectSpec`):

```java
// MySQL + ANSI_QUOTES: double quotes are identifiers, not strings
SqlDialectSpec ansiQuotes = new SqlDialectWrapper(SqlDialect.MYSQL) {
    @Override
    public boolean doubleQuoteIsString() {
        return false;
    }
};
SQL.parse("SELECT \"id\" FROM t", ansiQuotes);  // "id" parsed as an identifier
```

Overridable capabilities cover the whole parse-to-rewrite path: quoting (`identQuoteOpen`; `identQuoteClose` / `quoteIdent` are derived — changing the opener to `[` makes the closer `]`), `||` semantics (`pipesAsOr`; `pipesAreConcat` is derived), backslash escapes, bracket identifiers, `~` regex, `#` comments, pagination (`supportsLimitOffset/Top/FetchFirst/Rownum/CommaLimitOffset`), and identifier length (`maxIdentifierLength`; `fitIdentifier` is derived). `preferredLimitStyle()` is a query-only derived value and does **not** drive `setPage`. A raw `SqlDialectSpec` implementation inherits ANSI defaults for methods you do not override.

Invalid SQL throws `SqlParseException` with line number, column number, and nearby source text; it never returns a half-built tree.

## Template Placeholders (Optional)

**Template SQL** such as common-model uses placeholder slots like `@age@`, `%s`, `<sheet>`, `<-sheet->`. Placeholder parsing is **off** by default (staying strict); enable it explicitly via `SqlParseOptions.placeholders()` when needed:

```java
SqlParseOptions opt = SqlParseOptions.defaults()
        .placeholders(SqlPlaceholders.create()
                .atWrapped()    // @name@
                .mybatis()      // #{id} / ${table}
                .printf()       // %s / %d / %f …
                .angle()        // <sheet>
                .arrowAngle()   // <-sheet->
                .add("{{*}}")); // custom: exactly one * marks the body

SqlStatement stmt = SQL.parse(
        "select * from <20241230.1> where age > @age@ and name in (%s)",
        SqlDialect.MYSQL, opt);
```

You can also use `SqlPlaceholders.create().commonModelTemplates()` to enable the four common-model presets at once;
`SqlPlaceholders.create().mybatis()` enables MyBatis `#{property}` / `${property}` (including `#{id,jdbcType=VARCHAR}`).

Rule summary:

| Pattern | Meaning | Lexer result |
|------|------|----------|
| `@*@` | identifier body wrapped in `@` on both sides | `IDENT` (usable as a column/value atom) |
| `#{*}` / `${*}` | MyBatis parameter / literal (`mybatis()`) | `IDENT`; bind uses `id` for `#{id,jdbcType=…}` |
| printf | `%` + one letter | `IDENT` |
| `<*>` / `<-*->` | table-name placeholder (allows `.` and `-`) | `IDENT` (usable as a table name) |
| custom <code v-pre>{{*}}</code> etc. | non-empty prefix/suffix + body | `IDENT` |

When not configured, `@age@` / `%s` / `<sheet>` still fail as before or get split into operators. Deliberately incomplete statements (e.g. `select * from`) fail even with placeholders enabled.

Common parse + bind (keys omit the wrappers):

```java
SqlParseOptions opt = SqlParseOptions.defaults()
        .placeholders(SqlPlaceholders.create().mybatis());   // MyBatis only; no need to stack common-model

Map<String, Object> vals = new LinkedHashMap<String, Object>();
vals.put("table", "t_user");
vals.put("id", 7);
String sql = SQL.bindNamed(
        "SELECT * FROM ${table} WHERE id = #{id, jdbcType=INTEGER}",
        SqlDialect.MYSQL, opt, vals);
// SELECT * FROM t_user WHERE id = 7

vals.put("table", 10086);                                  // not a bare identifier → dialect quotes
SQL.bindNamed("SELECT * FROM #{table}", SqlDialect.MYSQL, opt, vals);
// SELECT * FROM `10086`     (Oracle: SQL.toSqlString(stmt, ORACLE) → "10086")
```

## Custom statement parsers (SPI, optional)

Statements whose leading keyword is not on the built-in list (SELECT / INSERT / CREATE / …), such as `BACKUP …` / `SIGNAL …`, throw `unsupported statement` by default. To accept them, register a `SqlStatementParser` via `SqlParseOptions.statementParsers()` keyed by the leading keyword (case-insensitive). Registration only covers keywords the built-in switch does not already handle — registering `SELECT` does not replace the built-in parser:

```java
SqlStatementParsers registry = SqlStatementParsers.create()
        .add("BACKUP", ctx -> {
            ctx.next(); // consume the BACKUP keyword
            String rest = ctx.consumeRest().trim(); // remaining source (original spacing)
            SqlSimpleStatement stmt = new SqlSimpleStatement();
            stmt.setText(rest.isEmpty() ? "BACKUP" : "BACKUP " + rest);
            return stmt;
        });

SqlParseOptions opt = SqlParseOptions.defaults().statementParsers(registry);
SqlStatement stmt = SQL.parse("BACKUP DATABASE shop TO DISK='/tmp/shop.bak'",
        SqlDialect.MYSQL, opt);
```

`SqlParseContext` exposes a cursor subset: `token()` / `dialect()` / `is(type)` / `isIdent(word)` / `match(type)` / `matchIdent(word)` / `next()` / `name()` / `atStmtBreak()` / `consumeRest()` / `error(message)`. Conventions:

- On entry to `parse`, the current token is the registered keyword; the implementation must consume the statement up to `atStmtBreak()` (the terminator is left for the framework). Leftover tokens produce a positioned error.
- Returning `null` is a parse failure; a thrown `SqlParseException` becomes a failure placeholder under tolerant `parseAll(..., true)` and parsing continues.
- A later registration for the same keyword overrides an earlier one. The options overload of `SQL.parse` / `parseAll` / `parseExpr` all honour the registry.

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

Facade rewrites (`addLimit` / `setPage` / `andWhere` / `inject` / `replaceTable` / `replaceColumn` / `addSelectItem` / `removeSelectItem` / `replaceSelectItem` / `replaceSelectItems` / `expandStar` / `adaptPagination` / `bind`) always **clone first**: they return a new tree and leave the input AST untouched. `SQL.clone` is an AST deep copy (`SqlAstCloner` / `SqlNode.copy`); the dialect argument of `clone(stmt, dialect)` is kept only for API compatibility and is ignored.

```java
SqlSchemaStat stat = SQL.stat(sql);
stat.tableNames();
stat.getColumns();
stat.getConditions();       // compact fragments from WHERE / JOIN ON / HAVING
stat.getOrderByColumns();
stat.getGroupByColumns();
stat.getTables();           // Map<String, SqlTableAccess>; the same table can be INSERT+SELECT
SQL.isReadOnly(stmt);       // true for SELECT / SHOW / EXPLAIN — handy for read/write splitting

SqlStatement limited = SQL.addLimit(stmt, 100); // clone, then append LIMIT (default MySQL)
SQL.getLimit(stmt);                              // Long: LIMIT / TOP / ROWNUM / row_number page size
SQL.getOffset(stmt);
SqlStatement page = SQL.setPage(stmt, 2, 20, SqlDialect.MYSQL); // offset=20
SQL.setLimit(stmt, 50, SqlDialect.POSTGRES);
SQL.setOffset(stmt, 10, SqlDialect.POSTGRES);
SqlStatement w = SQL.andWhere(stmt, "tenant_id = ?"); // internally parseExpr
SqlStatement t2 = SQL.replaceTable(w, "users", "users_archive");
SqlStatement c2 = SQL.replaceColumn(t2, "name", "user_name");   // skips table names/aliases
SqlStatement c3 = SQL.addSelectItem(c2, "status");              // append a select item
SqlStatement c4 = SQL.removeSelectItem(c3, "name");             // remove by simple name or alias (cannot empty the list)
SqlStatement c5 = SQL.adaptPagination(c4, SqlDialect.ORACLE);   // reshape pagination for the target dialect
SqlStatement copy = SQL.clone(stmt);                            // AST deep copy

// Row-level inject: configure tables/columns once, interceptors only call SQL.inject(stmt)
SQL.injectConfig(SqlInjectConfig.create()
        .tables("t_order", "t_item", "t_user")
        .add("deleted", 0)
        .add("tenant_id", new SqlInjectValue() {
            public Object get() { return TenantHolder.get(); }
        }));
SqlStatement ten = SQL.inject(stmt);
// one-off: SQL.inject(stmt, "tenant_id", 100, "t_order")

// Column masking: expand *, then replace several columns in one clone/walk
Map<String, List<String>> cols = new LinkedHashMap<String, List<String>>();
cols.put("t_customer", Arrays.asList("id", "name", "phone", "id_card"));
Map<String, String> masks = new LinkedHashMap<String, String>();
masks.put("phone", "CONCAT(LEFT(phone, 3), '****')");
masks.put("id_card", "'****'");
SqlStatement masked = SQL.replaceSelectItems(
        SQL.expandStar(SQL.parse("SELECT * FROM t_customer"), cols), masks);
```

- `addLimit`: does not overwrite an existing pagination (LIMIT / TOP / ROWNUM / `row_number`). Writes `TOP` for SQL Server, a single-level ROWNUM wrap for classic Oracle, and `LIMIT` otherwise.
- `setLimit` / `setOffset` / `setPage`: **replace** pagination; in `setPage(pageNo, pageSize)`, pageNo starts at 1.
- `removeSelectItem`: case-insensitive match on the simple column name (last segment of `t.col`) or an explicit alias; removing the last remaining item throws `IllegalArgumentException`. Outer SELECT only.
- `inject` / `SqlInjectConfig`: AND `alias.col = value` onto matching physical tables (CTE names and `DUAL` skipped). Column names are yours — tenant, soft-delete, org id, etc. `SQL.injectConfig` sets the global table list and columns; `SqlInject.setCurrent` overrides the thread (aspect/request values). `SQL.inject(stmt)` throws `IllegalStateException` if nothing is configured.
- `expandStar`: expand `*` / `t.*` from a table→columns map; unresolved stars stay as-is. A subquery `*` uses the inner projection.
- `replaceSelectItem`: replace SELECT items across the tree (UNION / subqueries). Match alias or `t.col`. For `SELECT *`, call `expandStar` first.
- `replaceSelectItems`: one clone and one walk for several columns, so masking many fields does not rewrite repeatedly.
- Pagination shapes, Oracle wrapping, and on-demand `format` / `toSqlString` adaptation are in the next section.

### Rewrite chains (optional)

When several rewrites (custom + built-in) must run in order, compose them with `SqlRewrites` and execute with `SQL.rewrite`. A custom rule placed before the built-in adapters acts as a "pre hook"; after them, a "post hook". `SQL.rewrite` deep-copies first, so the original AST is untouched (`null` or an empty chain returns the original statement):

```java
SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
        .add(new TenantRule())                                   // pre hook: custom rule
        .add(SqlRewrites.replaceTable("users", "users_2026"))
        .add(SqlRewrites.andWhere(SQL.parseExpr("tenant_id = ?")))
        .add(SqlRewrites.addSelectItem("status"))
        .add(SqlRewrites.removeSelectItem("secret"))
        .add(SqlRewrites.adaptPagination(SqlDialect.ORACLE))
        .add(SqlRewrites.addLimit(100, SqlDialect.MYSQL)));
```

A rule is the `SqlRewriteHook` functional interface: it receives the current statement and returns the statement to pass on (mutate in place and return it, or substitute another; returning `null` throws `IllegalArgumentException`). The built-in adapters mirror the static `SqlRewriter` methods (`addLimit` / `setLimit` / `setOffset` / `setPage` / `andWhere` / `replaceTable` / `replaceColumn` / `addSelectItem` / `removeSelectItem` / `adaptPagination`) and act in place on the statement travelling down the chain. For a single change, call the `SQL` facade; you do not need a chain.

## Cross-dialect pagination

Rewriting and write-back take `SqlDialect` capability methods as the single source of truth: `supportsLimitOffset` / `supportsTop` / `supportsFetchFirst` / `supportsRownum` / `supportsCommaLimitOffset`. `preferredLimitStyle()` only describes the preferred shape when adding a row count (`TOP` / `ROWNUM` / `LIMIT`) and does **not** drive `setPage` by itself.

| Dialect | Identifiers | `\|\|` | `#` line comment | Pagination shape |
| --- | --- | --- | --- | --- |
| MYSQL | backticks | OR | yes | `LIMIT n` / `LIMIT offset, n` |
| POSTGRES / H2 / ANSI | double quotes | concat | H2 yes | `LIMIT` / `OFFSET` (FETCH also recognised) |
| DAMENG | double quotes | concat | no | `LIMIT` / `OFFSET` |
| SQLITE / PRESTO | double quotes | concat | no | `LIMIT` / `OFFSET`, no FETCH FIRST |
| HIVE | backticks (double quotes are strings) | concat | no | `LIMIT` (`supportsLimitOffset=true`; an offset is still written on the AST; Hive engines usually accept only `LIMIT n`) |
| CLICKHOUSE | backticks (double quotes are identifiers too) | concat | no | `LIMIT n` / `LIMIT offset, n` |
| SQLSERVER | `[]` | concat | no | `TOP` when offset=0, then `OFFSET FETCH` |
| ORACLE (≤11g) | double quotes | concat | no | bare SELECT → ROWNUM subquery wrap |
| ORACLE12 | double quotes | concat | no | bare SELECT → `OFFSET … FETCH FIRST … ROWS ONLY`; existing ROWNUM wraps still recognised |
| DB2 | double quotes | concat | no | `FETCH FIRST n ROWS ONLY` only |

Pre-existing Oracle `ROWNUM` double-nesting / `WHERE ROWNUM <= n` and SQL Server `row_number` wrappers: `getLimit` returns the page size, and `setPage` / `setLimit` only adjust the numeric bounds (without stacking OFFSET/FETCH). A classic single-level ROWNUM wrap expands to a double wrap when offset>0. A UNION's pagination hangs at the end of the set-operation chain. `SqlBuilder.limit` / `offset` / `toSql(dialect)` share the same rewrite path (`toSql`'s dialect overrides the builder dialect).

### Classic Oracle ROWNUM wrapping

For a bare SELECT, `SqlDialect.ORACLE` (`supportsRownum && !supportsFetchFirst`) produces:

- **offset=0**: single-level subquery  
  `SELECT * FROM ( <original> ) XX WHERE ROWNUM <= n`
- **offset>0**: double wrap (middle alias `XX` projects `ROWNUM AS RN` and cuts `ROWNUM <= offset+n`; outer alias `XXX` filters `RN > offset`)

`WITH` stays on the outer node. `setPage` / `setLimit` / `adaptPagination` targeting classic Oracle first clear leftover LIMIT/TOP inside the select body so the wrap does not carry the source dialect's pagination.

`format` / `toSqlString(..., ORACLE)` use the same single-level wrap as a fast path when offset=0: they temporarily clear LIMIT/TOP, write the subquery wrap, then restore the input (the AST is not permanently mutated). A non-zero offset goes through full `adaptPagination`.

### format / toSqlString / adaptPagination

When writing back for a target dialect, if the AST already has pagination (LIMIT / TOP / ROWNUM / `row_number` / FETCH) that is incompatible with the target (including MySQL comma `LIMIT` → PG / ANSI), write-back `clone`s and runs `adaptPagination` only when needed. Compatible shapes pay no extra cost; the input AST is not mutated.

`SQL.adaptPagination(stmt, dialect)` is the explicit entry to the same adaptation (also clones first).

Typical directions:

- MySQL `LIMIT 10000` / `LIMIT 0,10000` → classic ORACLE single-level ROWNUM
- MySQL `LIMIT 10,20` → classic ORACLE double-level RN
- → ORACLE12 / DB2: `OFFSET … FETCH`
- → SQLSERVER: `TOP` when offset=0, `OFFSET FETCH` otherwise
- ROWNUM → POSTGRES / MYSQL: back to `LIMIT` (OFFSET omitted when 0; comma style is not kept)

## Parameterization / Wall / Evaluation

```java
String finger = SQL.parameterize("SELECT * FROM t WHERE name = 'a' AND age = 1");
// SELECT * FROM t WHERE name = ? AND age = ?

List<Object> litValues = SQL.exportParameterValues(sql); // "a", 1 — not ?/:name
List<String> binds = SQL.parameters(sql);                 // "?", ":name"

// Fill ? / :name with literals (strings double single quotes; no backslash)
String filled = SQL.bind("SELECT * FROM t WHERE name = ?", "'; DROP TABLE t; --");
// SELECT * FROM t WHERE name = '''; DROP TABLE t; --'   — still one statement
SQL.bindNamed("SELECT * FROM t WHERE id = :id", Collections.singletonMap("id", 1));
// Formulas must be SqlExpr; the string "NOW()" becomes 'NOW()'
SQL.bind("SELECT * FROM t WHERE ts > ?", SQL.parseExpr("NOW()"));
// SELECT * FROM t WHERE ts > NOW()

// Template placeholders (enable SqlPlaceholders at parse): #{table} as a table name, @name@ / :name as values
Map<String, Object> vals = new LinkedHashMap<String, Object>();
vals.put("table", "users");
vals.put("name", "bob");
vals.put("nameKey", SQL.parseExpr("LENGTH(name)"));
String out = SQL.bindNamed(
        "SELECT * FROM #{table} WHERE name = :name AND :nameKey > 2 OR nick = @name@",
        SqlDialect.MYSQL,
        SqlParseOptions.defaults().placeholders(
                SqlPlaceholders.create().commonModelTemplates().mybatis()),
        vals);

SqlWallResult wall = SQL.wall(sql); // parsing is not intercepted by default; call explicitly
wall.passed();
wall.violations(); // multi-statement / comment-bypass / always-true-condition / sleep-function / delete-without-where / update-without-where

// Configurable rules (SqlWallConfig); defaults() enables the safety-critical checks;
// denyUnion / denyInformationSchema / selectOnly are off by default
SqlWallConfig cfg = SqlWallConfig.defaults()
        .denyDdl(true)
        .denyDangerousFunctions(true)  // SLEEP / BENCHMARK / LOAD_FILE …
        .denyIntoOutfile(true)
        .selectOnly(false)
        .denyTables("mysql.user", "secret")   // deny-table
        .allowTables("t_order", "t_item")     // non-empty = only these tables; allow-table
        .requireWhereColumns("tenant_id")     // physical tables need this column in WHERE/JOIN ON
        .maxTables(8);                        // too-many-tables
SqlWallResult w2 = SQL.wall(sql, SqlDialect.MYSQL, cfg);

Object v = SQL.eval(expr); // literal arithmetic and comparison only; null when a column is read

stmt.accept(new SqlAstVisitor() {
    @Override protected boolean visitSelect(SqlSelect node) { return true; }
});
```

## Formatting

`format` / `toSqlString` write the AST back out (whitespace and comments are not preserved). When a target dialect is given and the pagination shape is incompatible, write-back adapts first (see "Cross-dialect pagination"). **Semantic round-trip** (`parse → format → parse`) guarantees that `type()`, `tables()` (case-insensitive), and `isReadOnly()` match the original; the golden corpus `SqlGoldenCorpusTest` covers this fully. **Word-level fidelity** is additionally enforced by `SqlRoundTripFidelityTest`: the formatted text must equal the original after normalization (strip comments / strip all whitespace / drop standalone `AS` / unify case — only pure layout differences are tolerated), across 118 tricky statements covering JOIN modifiers, DDL keywords, multi-group `RENAME`, quoting styles, DML modifiers, and JDBC escapes — silently **dropped words** (e.g. `NATURAL LEFT JOIN` losing `LEFT`, `STRAIGHT_JOIN` losing `STRAIGHT`) fail the test outright. The same method is applied in batch to all 379 corpus lines by `SqlRoundTripFidelityCorpusTest` in `tools-test` (with extra semantic-equivalence normalizations and 5 justified whitelist entries, hard-asserted).

```java
SQL.format(stmt);                           // newlines and indentation (SELECT clauses; CREATE TABLE columns)
SQL.toSqlString(stmt);                      // compact single line (MySQL by default)
stmt.toString();                            // same as SQL.toSqlString(stmt): MySQL backticks
SQL.format(stmt, SqlDialect.MYSQL, true);
SQL.toSqlString(stmt, SqlDialect.ORACLE);   // numeric table names use double quotes

stmt.addComment("a note");                  // body text; written back as /* a note */
stmt.addComment("-- already a comment");    // delimiters kept (line comments become block comments in compact mode)

// Force dialect quotes on every identifier segment (default false; literals/keywords/*/function names untouched)
SqlFormatOptions opts = SqlFormatOptions.defaults().quoteIdentifiers(true);
SQL.format(stmt, SqlDialect.MYSQL, opts);
SQL.toSqlString(stmt, SqlDialect.POSTGRES, opts);

// Keyword case (default AS_IS = the formatter's native output: clause/operator keywords upper-cased)
SQL.format(stmt, SqlDialect.MYSQL, false,
        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER)); // select ... from ... where ...
```

`keywordCase` covers clause, DDL, transaction-control and expression-operator keywords (AND / OR / NOT / LIKE / IN / BETWEEN…); identifiers, string literals and raw pass-through text (procedure bodies) are untouched. It can be combined with `quoteIdentifiers`.

Identifiers that were already quoted in the input are written back per dialect: backticks for MySQL, double quotes for PostgreSQL/Oracle/ANSI/H2, `[]` for SQL Server.
With `quoteIdentifiers` on, **unquoted** table/column names are force-quoted the same way.
`||` is written back from the AST (`CONCAT`→`||`; `OR` parsed by MySQL by default →`OR`).

The write-back is pretty-printing; **whitespace round-trip is not guaranteed**. Comments left by `keepComments` or `addComment` are emitted as valid SQL comments: bare text becomes a block comment; compact mode also turns `--` / `#` line comments into block comments so they cannot swallow the rest of the statement. For another dialect's quotes, use `SQL.toSqlString(stmt, dialect)` rather than `toString()`.

## Dialect Differences

Full pagination shapes are in "Cross-dialect pagination". This table only lists the parse/write-back pitfalls:

| Aspect | MYSQL | HIVE | SQLSERVER | Others (PG / Oracle / Dameng / ANSI / H2 / DB2 / SQLite / Presto) |
| --- | --- | --- | --- | --- |
| Identifiers | backticks `` ` `` | backticks | `[]` | double quotes |
| Double quotes | string by default | string | identifier | identifier |
| `\|\|` | logical OR (`SqlParseOptions.pipesAsConcat(true)` switches to concatenation) | concat | concat | concat |
| `#` line comment | yes | no | no | H2 only |
| Pagination | `supportsLimitOffset` + comma style | `supportsLimitOffset` | `supportsTop` + FETCH | see previous section |

ClickHouse uses backticks like MySQL, but double quotes are identifiers too, and pagination accepts comma `LIMIT`.

## JDBC URL

`JdbcUrlUtils` parses host / database / schema from a JDBC URL and infers the dialect and driver class, without opening a connection.

```java
import com.alianga.jkit.sql.jdbc.JdbcUrlUtils;
import com.alianga.jkit.sql.jdbc.JdbcUrlInfo;

JdbcUrlInfo info = JdbcUrlUtils.parse(
        "jdbc:postgresql://primary:5432,standby:5432/orders?currentSchema=sales");
info.getDbType();          // postgresql
info.getDatabaseName();    // orders
info.getSchema();          // sales
info.getNodes().size();    // 2

JdbcUrlUtils.fromUrl("jdbc:gaussdb://localhost:5433/postgres");  // POSTGRES
JdbcUrlUtils.getDbType("jdbc:kingbase8://h/db");                 // kingbase
JdbcUrlUtils.driverForUrl("jdbc:dm://localhost:5236");           // dm.jdbc.driver.DmDriver
JdbcUrlUtils.schema("jdbc:postgresql://h/db");                   // public
```

- `fromUrl` returns `null` when the URL is unknown (unlike `SqlDialect.fromName`, which falls back to MySQL).
- PostgreSQL / Gauss / openGauss / Kingbase read `currentSchema` (default `public`); SQL Server defaults to `dbo`; Dameng reads the `schema` parameter.
- `tryParse` returns null on failure instead of throwing. `jkit-sql-auto`'s `SqlAutoDialects.fromUrl` / `driverForUrl` delegate here.

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

Bind placeholders and literal extraction are covered under "Parameterization / Wall / Evaluation".

## Alias API Note

Read aliases with **`alias()`**:

- `SqlSelectItem.alias()` — column alias (including dotted forms such as `AS a.b`)
- `SqlTableSource.alias()` — table / subquery / table-function alias

Do not treat indexes of `SqlIdentifier.names()` as "the N-th alias"; `names()` is the list of qualified-name segments (`db.schema.table`) and is unrelated to aliases.

## Language coverage

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

## Cross-dialect type conversion

Schema / SQL conversion uses a **Normal Form** (canonical types) so adding a dialect is O(K), not O(N²). Design: [sql-schema-converter-design.md](../sql-schema-converter-design.md).

Phase 0–1 are in tree (JDK 8; `SqlDdlStatement.columnDefinitions()` stays `List<String>`):

```java
List<ColumnDefinition> cols = SqlColumnDefinitionParser.fromDdl(ddl, SqlDialect.MYSQL);
SqlDataTypeRegistry types = SqlDataTypeRegistry.builtins();
types.convert("VARCHAR(100)", SqlDialect.MYSQL, SqlDialect.ORACLE); // VARCHAR2(100)
types.fromDialect("TINYINT(1)", SqlDialect.MYSQL);                  // BOOLEAN
```

Undeclared reverse collisions fail `RegistryValidator` at builtin-table build time.

`SQL.convert` / `SQL.convertBatch` rewrite CREATE TABLE and `ALTER TABLE ADD/MODIFY/CHANGE` column types, plus query functions:

- `IF`→`CASE`, `GROUP_CONCAT`↔`STRING_AGG`/`LISTAGG`, `IFNULL`/`NVL`/`ISNULL`, `CAST`, `LOCATE`/`INSTR`
- `SUBSTRING`/`LEFT`/`RIGHT`: `FROM n FOR m` on PG/MySQL/H2; comma-form on SQL Server/SQLite; SQLite/Hive `LEFT`/`RIGHT` expand to `SUBSTR`
- `DATE_ADD`/`DATE_SUB`/`DATEDIFF`/`FROM_UNIXTIME`; `DECODE`/`NVL2`→`CASE`
- `DATE_FORMAT`: common specifiers are rewritten (`%Y-%m-%d %H:%i:%s` → PG/Oracle `TO_CHAR(..., 'YYYY-MM-DD HH24:MI:SS')`; SQLite `strftime` swaps arguments and maps `%i` to `%M`). Unmapped specifiers stay and raise `SEMANTIC_RISK`

MySQL table-level `KEY`/`INDEX` becomes appendix `CREATE INDEX` (FULLTEXT/SPATIAL dropped with `MANUAL_ACTION_REQUIRED`). Independent `CREATE INDEX … USING BTREE` drops `USING` on non-MySQL targets. `ALTER … MODIFY/CHANGE` to PG/H2 becomes `ALTER COLUMN … TYPE`, with NOT NULL/DEFAULT as extra statements.

Real execution lives in `tools-test`: `CrossDialectDdlExecutionTest` (MySQL→PG CREATE TABLE, Docker), `CrossDialectExprExecutionTest` (DDL + expression execution across PostgreSQL / MySQL containers and in-memory SQLite), `LocalDatasourceFunctionRewriteTest`, `LocalDatasourceBindTest` (`SQL.bind` on the local datasource file). JMH: `SqlSchemaConvertBenchmark`.

Supported first-class dialects (13, including Dameng and Oracle 12c: MySQL, PostgreSQL, Oracle 11g/12c, SQL Server, H2, ANSI, DB2, SQLite, Hive, ClickHouse, Presto, Dameng) and `fromName` product aliases: [design doc §4](../sql-schema-converter-design.md). How to extend types/functions: [§9](../sql-schema-converter-design.md).

## Entity scan → DDL / DML

Like data-set `EntityScanner`, without Spring: scan classes annotated with `@SqlTable`, JPA `@Entity`, or MyBatis-Plus `@TableName`/`@TableId` (resolved by FQCN via reflection — no compile dependency on Spring / JPA / MyBatis / Hibernate), then generate DDL and CRUD per dialect. Also honours `@TableField` (`exist=false` skips the column), JPA `@Index`/`@Enumerated`/`@Embedded`, any annotation whose simple name is `Comment`, and Hibernate `@ColumnDefault`; `List`/`Set`/`@OneToMany` fields are skipped unless annotated. `createTables` orders referenced tables first. Java types go through the canonical type registry.

Table / column comments (`@SqlTable(comment=…)`, `@SqlColumn(comment=…)`, and any `@Comment` by simple name on the type or field, reading `value` or `comment`; jkit annotations win): MySQL / Hive / ClickHouse inline `COMMENT '…'`; H2 inlines column comments and uses `COMMENT ON TABLE` for the table; PostgreSQL / Oracle / DB2 / ANSI emit `COMMENT ON TABLE|COLUMN`; SQL Server uses `sp_addextendedproperty`; Presto table-level `WITH (comment=…)`; SQLite has no comment syntax, so comments are skipped. Auto-DDL runs these as extra statements after `CREATE TABLE`.

Column defaults: Hibernate `@ColumnDefault` `value` is a **SQL fragment** (no `DEFAULT` keyword) written as-is, e.g. `@ColumnDefault("0")` → `DEFAULT 0`, `@ColumnDefault("'guest'")` → `DEFAULT 'guest'`. If `columnDefinition` already contains `DEFAULT`, it is not repeated.

Dialects without IDENTITY (Oracle ≤11g, enum `ORACLE`) get `CREATE SEQUENCE {table}_{column}_seq` plus a `BEFORE INSERT` trigger; Dameng (`DAMENG`) uses column `IDENTITY`; Oracle 12c+ still uses `GENERATED … AS IDENTITY`. `SqlEntities.extraSql` / `sequenceSql` expose the extras on their own.

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

`javax.persistence` / `jakarta.persistence` annotations (`@Entity` `@Table` `@Column` `@Id` `@GeneratedValue` `@Transient` `@Lob`) are recognised the same way. So is any `@Comment` (by simple name) and Hibernate `@ColumnDefault`. `@GeneratedValue` becomes database identity only for integer columns and non-UUID strategies; `generator="system-uuid"` / `GenerationType.UUID` / string PKs keep `PRIMARY KEY` only. Duplicate column names inherited from a superclass / `MappedSuperclass` keep the subclass declaration.

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
| `createIndex(String tableName, String spec)` / `createIndex(..., SqlDialect)` | A standalone `CREATE INDEX`; `spec` is `name:col1,col2` or `col1,col2` (unnamed → `{table}_{col}_idx`). With a dialect, names are fitted to the identifier length limit (30 for classic Oracle) |
| `indexName(String tableName, String spec)` / `indexName(..., SqlDialect)` | Resolve / generate the index name, same rules as `createIndex` |
| `extraSql(SqlEntityModel, SqlDialect, SqlSchemaConvertOptions)` | Post-create extras: `COMMENT ON` / SQL Server extended properties / SEQUENCE on dialects without IDENTITY |
| `sequenceSql(String table, SqlEntityColumn, SqlDialect)` | SEQUENCE (+ Oracle trigger) when the dialect has no IDENTITY; otherwise `null`. Sequence / trigger names are fitted to the dialect limit |
| `sequenceName(String table, String column)` / `sequenceName(..., SqlDialect)` | `{table}_{column}_seq`; with a dialect, fitted to the identifier limit |

`columnSql` / `columnTypeSql` / `createIndex` / `extraSql` / `sequenceSql` exist for schema diffing, incremental `ALTER TABLE … ADD`, and comment / sequence extras — that is exactly what the auto-DDL module builds on:

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
| **jkit-sql** | **379/379 (100%)** | in-module golden corpus ~216 statements (including round-trip; `SqlGoldenCorpusTest` ~432 assertions) + 118 round-trip fidelity statements (`SqlRoundTripFidelityTest`) + 379 batch fidelity lines (`SqlRoundTripFidelityCorpusTest` in tools-test); `mvn -pl jkit-sql test` runs **1376** tests |
| Druid 1.2.23 | below jkit (gaps in `target/sql-compare-fail.txt`) | comparison is not part of this library's dependencies |
| JSqlParser 4.9 | below jkit | same as above |

## Corpus and Comparison

- In-module: `SqlGoldenCorpusTest` (about **216** statements, including round-trip), `SqlRoundTripFidelityTest` (**118** statements, formatted text compared verbatim against the original after normalization), `CommonModelSqlCorpusTest` (harvested from `icell/common-model`, 87 parseable statements), `Complex100GiantsTest` / `SqlModelMatchDeepenTest` (structured MODEL / MATCH_RECOGNIZE fields).
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

## Business Scenarios and Best Practices

The capabilities of `jkit-sql` map onto the 14 business scenarios below, each with at least two best-practice
examples. Every snippet is taken from
`jkit-sql/src/test/java/com/alianga/jkit/sql/SqlBusinessScenarioTest.java` and can be re-run directly:

```text
mvn -pl jkit-sql test -Dtest=SqlBusinessScenarioTest
```

| # | Scenario | Main API | Examples |
| --- | --- | --- | --- |
| 1 | SQL auditing and dependency analysis | `SQL.tables` / `SQL.stat` / `SqlStatement.isReadOnly` | dependency extraction, write-op flagging |
| 2 | Read/write splitting routing | `SqlStatement.isReadOnly` | read-only to replica, locking SELECT to primary |
| 3 | SQL injection protection | `SQL.parameterize` / `SQL.bind` / `SQL.bindNamed` / `SQL.exportParameterValues` | literal harvesting, safe fill-in, `IN` lists, formulas |
| 4 | SQL firewall | `SQL.wall` / `SqlWallConfig` | table allow/deny lists, required WHERE columns, max tables |
| 5 | Cross-dialect database migration | `SQL.convertBatch` / `SqlSchemaConverter.convert` | DDL translation loop, batch DML function rewriting |
| 6 | Multi-dialect pagination | `SQL.setPage` / `SQL.adaptPagination` / `SQL.toSqlString` | LIMIT/TOP/FETCH/ROWNUM, offset=0 single wrap, on-demand write-back |
| 7 | Multi-tenant rewriting | `SQL.inject` / `SqlInjectConfig` / `SqlRewrites.replaceTable` | global table/column config, aspect values, shard routing |
| 8 | Data masking and column-level access | `SQL.expandStar` / `SQL.replaceSelectItems` / `SQL.removeSelectItem` | expand `*`, batch mask, drop sensitive columns |
| 9 | Dynamic SQL building | `SqlBuilder` | conditional query assembly, INSERT/UPDATE |
| 10 | Entity-driven multi-dialect DDL | `SqlEntities.createTable` | MySQL inline COMMENT, PG COMMENT ON |
| 11 | SQL formatting and conventions | `SQL.format` / `toString` / `addComment` | keyword case, pretty, comments, dialect quotes |
| 12 | Legacy template placeholder migration | `SqlPlaceholders` / `SQL.bindNamed` | `@xx@`, `%s`, MyBatis `#{}/ ${}` |
| 13 | Expression pre-evaluation | `SQL.eval` | constant folding, column refs yield null |
| 14 | Safe rewriting without polluting the original | `SQL.clone` / clone-then-mutate | reuse cached statements, page without mutating |

### 1. SQL auditing and dependency analysis

Before a release you need to know which tables and columns a statement touches, to assess impact and route review.

**Example 1: extract table/column dependencies**

```java
SqlStatement stmt = SQL.parse(
        "SELECT u.id, u.name FROM users u JOIN orders o ON u.id = o.uid WHERE o.amount > 100");
List<String> tables = SQL.tables(stmt);                  // [users, orders]
boolean hit = SQL.stat(stmt).getColumns().contains("o.amount");
```

**Example 2: flag write operations across a batch script**

```java
List<SqlStatement> stmts = SQL.parseAll("SELECT 1; UPDATE t SET a = 1 WHERE id = 2");
long writes = 0;
for (SqlStatement s : stmts) {
    if (!s.isReadOnly()) {
        writes++;                                        // 1
    }
}
```

### 2. Read/write splitting routing

A proxy routes traffic to primary or replica based on statement type.

**Example 1: read-only statements go to the replica**

```java
SqlStatement q = SQL.parse("SELECT * FROM t_user WHERE id = 1");
boolean replica = q.isReadOnly();                        // true
```

**Example 2: locking SELECT and writes go to the primary**

```java
// SELECT ... FOR UPDATE holds a lock; routing it to a replica would break row locking
boolean primary = !SQL.parse("SELECT * FROM t_user WHERE id = 1 FOR UPDATE").isReadOnly(); // true
boolean write = !SQL.parse("INSERT INTO t_user(id, name) VALUES (1, 'a')").isReadOnly();   // true
```

> `FOR UPDATE` / `LOCK IN SHARE MODE` are deliberately treated as writes — otherwise a read/write split
> router would send locking queries to a replica.

### 3. SQL injection protection

Turn literals spliced into SQL from the outside into bind parameters for a prepared statement.

**Example 1: parameterize literals in one call**

```java
String out = SQL.parameterize(
        "SELECT id FROM t_user WHERE name = 'alice' AND age = 18 AND deleted = false");
// SELECT id FROM t_user WHERE name = ? AND age = ? AND deleted = ?
```

**Example 2: export values, then dispatch safely in two steps**

```java
SqlStatement stmt = SQL.parse(
        "SELECT * FROM t_user WHERE name = 'alice' AND age = 18 AND id = ?");
List<Object> values = SQL.exportParameterValues(stmt);   // [alice, 18]
String parameterized = SQL.parameterize(stmt);           // literals become ?; existing ? untouched
```

**Example 3: `SQL.bind` fills placeholders without SQL injection (including `IN` lists)**

```java
String payload = "'; DROP TABLE t_user; --";
String sql = SQL.bind("SELECT * FROM t_user WHERE name = ?", payload);
// SELECT * FROM t_user WHERE name = '''; DROP TABLE t_user; --'  — still one statement
SQL.parseAll(sql).size();                                // 1

String inList = SQL.bind("SELECT * FROM t WHERE id IN ?", Arrays.asList(1, 2, 3));
// SELECT * FROM t WHERE id IN (1, 2, 3)
```

**Example 4: `bindNamed` with a value and a formula**

```java
Map<String, Object> vals = new LinkedHashMap<String, Object>();
vals.put("id", 7);
vals.put("ts", SQL.parseExpr("NOW()"));                  // formulas must be SqlExpr
String sql = SQL.bindNamed(
        "SELECT * FROM t_user WHERE id = :id AND created_at > :ts", vals);
// SELECT * FROM t_user WHERE id = 7 AND created_at > NOW()
// the string "NOW()" would become 'NOW()' — don't pass it that way
```

### 4. SQL firewall (Wall)

Pre-flight blocking for untrusted entry points: user-editable queries, open APIs, low-code platforms.

**Example 1: block the classic dangerous patterns**

```java
SQL.wall("SELECT 1; DELETE FROM t WHERE id = 1").violations(); // [multi-statement]
SQL.wall("DELETE FROM t").violations();                        // [delete-without-where]
SQL.wall("UPDATE t SET a = 1").violations();                   // [update-without-where]
SQL.wall("SELECT SLEEP(5) FROM t").violations();               // [dangerous-function]
SQL.wall("SELECT * FROM t WHERE name = 'x' --").violations();  // [comment-bypass]
SQL.wall("SELECT * FROM t WHERE id = 1").passed();             // true, legitimate query allowed
```

**Example 2: allow DDL only for the ops channel**

```java
SQL.wall("DROP TABLE t").violations();                          // [deny-ddl], blocked by default
SqlWallConfig allowDdl = SqlWallConfig.defaults().denyDdl(false);
SQL.wall("DROP TABLE t", SqlDialect.MYSQL, allowDdl).passed();   // true
```

### 5. Cross-dialect database migration

Translate existing MySQL statements to the target database, then re-parse them with the target dialect to close the loop.

**Example 1: single DDL translation + re-parse check**

```java
ConversionResult r = SqlSchemaConverter.convert(
        "ALTER TABLE t MODIFY c INT NOT NULL", SqlDialect.MYSQL, SqlDialect.POSTGRES);
// ALTER TABLE t ALTER COLUMN c TYPE INTEGER
SQL.parse(r.sql(), SqlDialect.POSTGRES);                 // translation only counts if it re-parses
```

**Example 2: batch DML translation with function rewriting**

```java
List<ConversionResult> rs = SQL.convertBatch(
        Arrays.asList("SELECT GROUP_CONCAT(name) FROM t", "SELECT IFNULL(a, b) FROM t"),
        SqlDialect.MYSQL, SqlDialect.POSTGRES);
// rs.get(0).sql() → SELECT STRING_AGG(name, ',') FROM t
```

### 6. Multi-dialect pagination

The same business query, paginated for the target dialect. `setPage` / `adaptPagination` / `format` / `toSqlString` share the same shape check.

**Example 1: MySQL / PostgreSQL LIMIT OFFSET**

```java
SqlStatement page = SQL.setPage(
        SQL.parse("SELECT id FROM orders WHERE status = 1"), 2, 10, SqlDialect.MYSQL);
SQL.getLimit(page);                                      // 10
SQL.getOffset(page);                                     // 10
```

**Example 2: classic Oracle offset=0 single wrap, and a double wrap on later pages**

```java
SqlStatement first = SQL.setPage(
        SQL.parse("SELECT * FROM emp"), 1, 8, SqlDialect.ORACLE);
SQL.toSqlString(first, SqlDialect.ORACLE);
// SELECT * FROM (SELECT * FROM emp) XX WHERE ROWNUM <= 8

SqlStatement page = SQL.setPage(
        SQL.parse("SELECT * FROM emp"), 2, 8, SqlDialect.ORACLE);
// double-level RN, no OFFSET/FETCH
SQL.parse(SQL.toSqlString(page, SqlDialect.ORACLE), SqlDialect.ORACLE);

// An AST that already has MySQL LIMIT, written back or adapted for another dialect
SqlStatement mysql = SQL.parse("SELECT * FROM emp LIMIT 10");
SQL.toSqlString(mysql, SqlDialect.ORACLE);               // single-level ROWNUM
SQL.toSqlString(mysql, SqlDialect.SQLSERVER);            // SELECT TOP 10 ...
SQL.adaptPagination(mysql, SqlDialect.ORACLE12);         // OFFSET 0 FETCH FIRST 10 ROWS ONLY
```

### 7. Multi-tenant rewriting

Shard routing plus centrally injected tenant predicates, so no query ever misses its tenant filter.

**Example 1: route to a tenant shard**

```java
SqlStatement out = SQL.rewrite(SQL.parse("SELECT id, name FROM t_user WHERE status = 1"),
        SqlRewrites.create().add(SqlRewrites.replaceTable("t_user", "t_user_2026")));
// SELECT id, name FROM t_user_2026 WHERE status = 1
```

**Example 2: inject the tenant_id predicate at the gateway**

```java
SqlStatement stmt = SQL.parse("SELECT id FROM t_order WHERE status = 1");
SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
        .add(SqlRewrites.andWhere(SQL.parseExpr("tenant_id = 100"))));
// SELECT id FROM t_order WHERE status = 1 AND tenant_id = 100
SQL.toSqlString(stmt);                                   // the original statement is untouched
```

**Example 3: configure tables/columns at startup, then `SQL.inject` in the interceptor**

```java
SQL.injectConfig(SqlInjectConfig.create()
        .tables("t_order", "t_item")
        .add("deleted", 0)
        .add("tenant_id", new SqlInjectValue() {
            public Object get() { return TenantHolder.get(); }
        }));
SqlStatement out = SQL.inject(SQL.parse("SELECT id FROM t_order WHERE status = 1"));
// SELECT id FROM t_order WHERE status = 1 AND tenant_id = … AND deleted = 0
// after the request: SqlInject.clear(); one-off: SQL.inject(stmt, "tenant_id", 100, "t_order")
```

### 8. Data masking and column-level access

Trim sensitive columns for external APIs; adapt old SQL to a physical rename without touching application code.

**Example 1: trim sensitive columns**

```java
SqlStatement stmt = SQL.parse("SELECT id, name, phone, id_card FROM t_customer WHERE id = 1");
SqlStatement out = SQL.removeSelectItem(stmt, "phone");   // already clones; no extra SQL.clone
out = SQL.removeSelectItem(out, "id_card");
// SELECT id, name FROM t_customer WHERE id = 1
```

**Example 2: map a physical column rename**

```java
SqlStatement out = SQL.rewrite(SQL.parse("SELECT name FROM t_user WHERE name = 'a'"),
        SqlRewrites.create().add(SqlRewrites.replaceColumn("name", "user_name")));
// SELECT user_name FROM t_user WHERE user_name = 'a'
```

**Example 3: expand `*` then mask several columns in one pass**

```java
Map<String, List<String>> cols = new LinkedHashMap<String, List<String>>();
cols.put("t_customer", Arrays.asList("id", "name", "phone", "id_card"));
Map<String, String> masks = new LinkedHashMap<String, String>();
masks.put("phone", "CONCAT(LEFT(phone, 3), '****')");
masks.put("id_card", "'****'");
SqlStatement masked = SQL.replaceSelectItems(
        SQL.expandStar(SQL.parse("SELECT * FROM t_customer"), cols), masks);
// SELECT id, name, CONCAT(LEFT(phone, 3), '****') AS phone, '****' AS id_card FROM t_customer
```

### 9. Dynamic SQL building

Replace string concatenation with a fluent builder to eliminate injection and comma/paren syntax errors.

**Example 1: assemble a conditional query**

```java
String sql = SqlBuilder.select("id", "name").from("users")
        .where("status = 1").and("age > 18").orderBy("id").limit(10).toSql();
// SELECT id, name FROM users WHERE status = 1 AND age > 18 ORDER BY id LIMIT 10
SQL.parse(sql);                                          // the output is always valid SQL
```

**Example 2: INSERT / UPDATE with zero concatenation**

```java
String insert = SqlBuilder.insertInto("t_user").columns("id", "name").values(1L, "alice").toSql();
// INSERT INTO t_user(id, name) VALUES (1, 'alice')
String update = SqlBuilder.update("t_user").set("name", "bob").where("id = 1").toSql();
// UPDATE t_user SET name = 'bob' WHERE id = 1
```

### 10. Entity-driven multi-dialect DDL

One set of entity annotations, one DDL per target database — no more maintaining several create-table scripts.

**Example 1: MySQL inline COMMENT**

```java
@SqlTable(name = "t_member", comment = "Member table")
class Member {
    @SqlId
    Long id;
    @SqlColumn(comment = "Nickname")
    String nick;
}
String ddl = SqlEntities.createTable(Member.class, SqlDialect.MYSQL);
// CREATE TABLE t_member (...) COMMENT 'Member table', columns carry COMMENT 'Nickname'
```

**Example 2: PostgreSQL uses separate COMMENT ON statements**

```java
String ddl = SqlEntities.createTable(Member.class, SqlDialect.POSTGRES);
// COMMENT ON TABLE t_member IS 'Member table';
// COMMENT ON COLUMN t_member.nick IS 'Nickname';
```

### 11. SQL formatting and conventions

De-noise logs, shrink review diffs, normalize keyword casing.

**Example 1: normalize keyword case**

```java
SqlStatement stmt = SQL.parse("select Id, Name from MyTable where Age > 18", SqlDialect.MYSQL);
SQL.format(stmt, SqlDialect.MYSQL, false, SqlFormatOptions.defaults());
// SELECT Id, Name FROM MyTable WHERE Age > 18 (identifier case preserved)
SQL.format(stmt, SqlDialect.MYSQL, false,
        SqlFormatOptions.defaults().keywordCase(SqlKeywordCase.LOWER));
// select Id, Name from MyTable where Age > 18
```

**Example 2: pretty multi-line output that round-trips**

```java
String pretty = SQL.format("select id,name,phone from t_user where status=1 and age>18 "
        + "order by id desc limit 10");
pretty.contains("\n");                                   // true
// re-parsing the pretty form keeps the semantics intact
assertEquals(SQL.tables(SQL.parse(ugly)), SQL.tables(SQL.parse(pretty)));
```

**Example 3: `addComment` + `toString()` defaults to MySQL backticks**

```java
SqlParseOptions opt = SqlParseOptions.defaults()
        .placeholders(SqlPlaceholders.create().mybatis());
SqlStatement bound = SQL.bindNamed(
        SQL.parse("SELECT * FROM #{table}", SqlDialect.MYSQL, opt),
        SqlDialect.MYSQL, Collections.<String, Object>singletonMap("table", 10086));
bound.addComment("a note");                              // body text is enough
String sql = bound.toString();
// /* a note */ SELECT * FROM `10086`
SQL.toSqlString(bound, SqlDialect.ORACLE);               // "10086"
SQL.toSqlString(bound, SqlDialect.SQLSERVER);            // [10086]
```

### 12. Legacy template placeholder migration

Old `@xx@` / `%s` style SQL parses as-is, without rewriting the text.

**Example 1: `@xx@` style**

```java
SqlStatement stmt = SQL.parse(
        "select * from t_user where id = 1 and age > @minAge@ limit 10",
        SqlDialect.MYSQL,
        SqlParseOptions.defaults().placeholders(SqlPlaceholders.create().atWrapped()));
SQL.tables(stmt);                                        // [t_user]
SQL.getLimit(stmt);                                      // 10
```

**Example 2: `%s` (printf) style**

```java
SqlStatement stmt = SQL.parse("SELECT %s FROM (SELECT '20221111' AS %s) AS a",
        SqlDialect.MYSQL,
        SqlParseOptions.defaults().placeholders(SqlPlaceholders.create().printf()));
SQL.tables(stmt);                                        // [], FROM is a derived table
```

**Example 3: MyBatis `#{id}` / `${table}`**

```java
SqlParseOptions opt = SqlParseOptions.defaults()
        .placeholders(SqlPlaceholders.create().mybatis());
SQL.parse("SELECT * FROM ${table} WHERE id = #{id, jdbcType=INTEGER}",
        SqlDialect.MYSQL, opt);
```

**Example 4: bind fills MyBatis placeholders by property name**

```java
Map<String, Object> vals = new LinkedHashMap<String, Object>();
vals.put("table", "t_user");
vals.put("id", 7);
vals.put("user.name", "bob");
String sql = SQL.bindNamed(
        "SELECT * FROM ${table} WHERE id = #{id, jdbcType=INTEGER} AND name = #{user.name}",
        SqlDialect.MYSQL,
        SqlParseOptions.defaults().placeholders(SqlPlaceholders.create().mybatis()),
        vals);
// SELECT * FROM t_user WHERE id = 7 AND name = 'bob'
```

### 13. Expression pre-evaluation

Rule engines, UI previews and report pre-checks fold constants first; anything unresolvable is left to the caller.

**Example 1: constant folding**

```java
SqlStatement stmt = SQL.parse("SELECT 1 + 2 * 3");
Object v = SQL.eval(((SqlSelect) stmt).selectItems().get(0).expr());  // "7"
```

**Example 2: column references yield null instead of throwing**

```java
SqlStatement stmt = SQL.parse("SELECT price * 0.8 FROM t");
Object v = SQL.eval(((SqlSelect) stmt).selectItems().get(0).expr());  // null
```

### 14. Safe rewriting without polluting the original statement

Gateways and proxies cache parse results, so rewriting must be clone-then-mutate and never touch the shared original.

**Example 1: the original stays reusable after trimming columns**

```java
SqlStatement original = SQL.parse("SELECT id, name FROM t_user WHERE status = 1");
SqlStatement masked = SQL.removeSelectItem(original, "name");   // returns a new statement
SQL.toSqlString(original).contains("name");                     // true
SQL.toSqlString(masked).contains("name");                       // false
```

**Example 2: pagination rewriting leaves the original alone**

```java
SqlStatement original = SQL.parse("SELECT id FROM users WHERE status = 1");
SQL.setPage(original, 2, 10, SqlDialect.MYSQL);
((SqlSelect) original).limit();                          // null, the original has no LIMIT
```

### Convention for adding scenarios

Land a new scenario in `SqlBusinessScenarioTest` first (at least two examples per scenario, and the output must
re-parse with `SQL.parse` under the target dialect); only after it is green, add it to this section so the docs
stay one-to-one with executable tests.
