# Auto schema module

`com.alianga:jkit-sql-auto` scans entity classes at startup, compares them with the live schema, and runs `CREATE TABLE` / `ALTER TABLE ADD`. DDL text comes from [jkit-sql](./sql.md) `SqlEntities`; this module only inspects `DatabaseMetaData` and executes.

`jkit-sql` **does not run SQL and does not ship a JDBC driver**. Add this module when you need to change a live schema. Runtime stays free of third-party libraries (tests use H2). JDK 8+.

## Dependency

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto</artifactId>
    <version>2.0.1</version>
</dependency>
```

It depends on `jkit` and `jkit-sql`. The host supplies the JDBC driver (MySQL / PostgreSQL / Oracle, …); this module only uses `java.sql`.

Spring Boot apps add a starter and run once when the application is ready (uses the app `DataSource`):

```xml
<!-- Boot 2.x / JDK 8+ -->
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-2</artifactId>
    <version>2.0.1</version>
</dependency>
```

```xml
<!-- Boot 3.x / JDK 17+ -->
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-3</artifactId>
    <version>2.0.1</version>
</dependency>
```

## Run at startup

Call it once from `main`, a servlet listener, or a Spring `ApplicationRunner`. There is **no** Spring compile dependency:

```java
import com.alianga.jkit.sql.auto.SqlAuto;
import com.alianga.jkit.sql.auto.SqlAutoMode;
import com.alianga.jkit.sql.auto.SqlAutoOptions;

public static void main(String[] args) {
    SqlAuto.run(SqlAutoOptions.defaults()
            .url("jdbc:mysql://localhost:3306/shop")
            .username("root")
            .password("secret")
            .packages("com.example.entity")
            .mode(SqlAutoMode.UPDATE));
    // then start the web / business layer
}
```

With a `DataSource`:

```java
SqlAuto.run(SqlAutoOptions.defaults()
        .dataSource(dataSource)
        .packages("com.example.entity"));
```

With an open `Connection`:

```java
SqlAuto.run(connection, SqlAutoOptions.defaults().entities(User.class, Order.class));
```

Dry-run (plan only, do not change the database). `run(options)` with `dryRun(true)` **does not open JDBC** (the URL is only used to infer the dialect) and plans a full `CREATE` as if the schema were empty, so the database does not need to be running. To preview `ALTER` against live tables, pass an open `Connection` to `run(connection, options)`:

```java
SqlAutoPlan plan = SqlAuto.run(SqlAutoOptions.defaults()
        .url(url).packages("com.example.entity").dryRun(true));
plan.sql(); // List<String>
```

Entity discovery is the same as `SqlEntities.scan`: `@SqlTable`, JPA `@Entity`, MyBatis-Plus `@TableName` (detected by FQCN, no compile dependency). See [entity scan](./sql.md#entity-scan-ddl--dml).

## Modes

Mirrors JPA `spring.jpa.hibernate.ddl-auto`:

| `SqlAutoMode` | Behaviour |
| --- | --- |
| `NONE` | No-op |
| `VALIDATE` | Throw `SqlAutoException` if a table/column is missing or types are incompatible; never mutate |
| `UPDATE` (default) | Create missing tables, add missing columns/indexes; **do not** change existing types or drop columns/tables |
| `CREATE` | Drop managed tables then recreate (dev) |
| `CREATE_DROP` | Same as `CREATE` at startup, then drop on JVM shutdown |

`UPDATE` is the production default: expand only, never shrink. Type mismatches are skipped unless `alterColumn(true)`. Extra columns stay unless `dropExtraColumns(true)`.

## Configuration

`SqlAutoOptions.fromConfig()` reads `jkit.sql.auto.*`, falling back to `spring.datasource.*` for the URL/user/password (handy if the app already has Spring config). You can also use the fluent API and skip files.

| key | default | meaning |
| --- | --- | --- |
| `jkit.sql.auto.enabled` | `true` | `false` makes `run()` a no-op |
| `jkit.sql.auto.mode` | `update` | `none` / `validate` / `update` / `create` / `create-drop` |
| `jkit.sql.auto.packages` | empty | comma-separated scan roots; also `package` / `base-package` |
| `jkit.sql.auto.entities` | empty | entity FQCNs |
| `jkit.sql.auto.dialect` | inferred from URL / `DatabaseMetaData` | `mysql` / `postgres` / `h2` / `oracle` … (`SqlDialect.fromName`) |
| `jkit.sql.auto.url` | `spring.datasource.url` | JDBC URL |
| `jkit.sql.auto.username` | `spring.datasource.username` | username |
| `jkit.sql.auto.password` | `spring.datasource.password` | password |
| `jkit.sql.auto.driver` | guessed from URL | driver class; also `driver-class-name` |
| `jkit.sql.auto.fail-fast` | `true` | abort on the first failed DDL |
| `jkit.sql.auto.alter-column` | `false` | `ALTER`/`MODIFY` on type mismatch |
| `jkit.sql.auto.drop-extra-columns` | `false` | drop columns not on the entity |
| `jkit.sql.auto.create-index` | `true` | emit `CREATE INDEX` |
| `jkit.sql.auto.quote-identifiers` | `false` | quote identifiers in the dialect |
| `jkit.sql.auto.show-sql` | `true` | log SQL |
| `jkit.sql.auto.dry-run` | `false` | plan only; `run(options)` does not open JDBC (URL is only used to infer dialect) |
| `jkit.sql.auto.catalog` / `schema` | JDBC default | `DatabaseMetaData` lookup scope |

`application.yml` example:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shop
    username: shop
    password: secret

jkit:
  sql:
    auto:
      enabled: true
      mode: update
      packages: com.example.entity
```

```java
public static void main(String[] args) {
    SqlAuto.run(); // fromConfig()
}
```

Or as a standalone process (`jkit-sql-auto`, `jkit-sql`, a JDBC driver and config on the classpath):

```text
java com.alianga.jkit.sql.auto.SqlAuto
```

## Dialects

When `dialect` is unset:

1. JDBC URL prefix (`jdbc:mysql:` → MYSQL, `jdbc:postgresql:` → POSTGRES, `jdbc:h2:` → H2, `jdbc:oracle:` → ORACLE, Dameng `jdbc:dm:` → DAMENG, …)
2. `DatabaseMetaData.getDatabaseProductName()` (Oracle 12+ uses `ORACLE12`)
3. otherwise MYSQL

`CREATE TABLE` / column types go through `SqlEntities` and the canonical type table — the same MySQL / PostgreSQL / Oracle / SQL Server / H2 / SQLite forms as the parser module.

## What it does / does not

Does:

- Scan packages or an explicit entity list; referenced tables come first by foreign key
- Missing table → `CREATE TABLE` (optional `CREATE INDEX`, comment extras, SEQUENCE on dialects without IDENTITY)
- Table exists, column missing → `ALTER TABLE … ADD [COLUMN]` (column comments as extras)
- Missing index → `CREATE INDEX`
- `VALIDATE` turns missing tables/columns / type mismatches into an exception
- `CREATE` / `CREATE_DROP` drop then rebuild
- `SqlAuto.drop(...)` drops managed tables in reverse FK order

Default **does not**:

- Change existing column types (unless `alterColumn=true`)
- Drop columns or tables not on the entity (unless `dropExtraColumns` / `CREATE`)
- Migrate data, rename columns, or change primary keys
- Convert procedures / views / triggers
- Bundle a JDBC driver

Drop managed tables explicitly (reverse FK order):

```java
SqlAuto.drop(SqlAutoOptions.defaults().url(url).entities(User.class));
```

Table / column comments (`@SqlTable(comment)` / `@SqlColumn(comment)`) follow the dialect: MySQL / Hive / ClickHouse inline `COMMENT`; H2 inlines column comments and uses `COMMENT ON TABLE`; PostgreSQL / Oracle / DB2 / ANSI emit `COMMENT ON`; SQL Server uses `sp_addextendedproperty`; Presto table-level `WITH (comment=…)`; SQLite skips comments. Auto-DDL runs extras as separate statements (`CREATE TABLE` itself does not include them).

Oracle ≤11g (`ORACLE`) has no IDENTITY: auto-increment PKs become `CREATE SEQUENCE {table}_{column}_seq` plus a `BEFORE INSERT` trigger. `CREATE_DROP` / `drop` drop the sequence before the table. Dameng (`DAMENG`) uses column `IDENTITY`. Oracle 12c+ still uses `GENERATED … AS IDENTITY`.

Some products are narrower than the first-class dialect. Turn the matching DDL off:

- Old OpenGauss rejects `GENERATED … IDENTITY`: `postgresIdentityStyle(SERIAL)`
- GBase 8a may reject in-table `FOREIGN KEY` / standalone `CREATE INDEX` (`unsupported key algorithm`): `foreignKeys(false).createIndex(false)`
- DuckDB rejects `AUTOINCREMENT` / `IDENTITY` / in-table FK: `autoIncrement(false).foreignKeys(false).createIndex(false)`
- Oracle ≤11g: SEQUENCE + TRIGGER is generated; Dameng uses IDENTITY

For H2 in-memory `CREATE_DROP`, put `DB_CLOSE_DELAY=-1` on the URL so the database survives the startup connection closing.
