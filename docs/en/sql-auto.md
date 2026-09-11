# Auto schema module

`com.alianga:jkit-sql-auto` scans entity classes at startup, compares them with `DatabaseMetaData`, and runs `CREATE TABLE` / `ALTER TABLE ADD`. DDL text comes from [jkit-sql](./sql.md) `SqlEntities`; this module only inspects and executes.

`jkit-sql` **does not run SQL and does not ship a JDBC driver**. Add this module when you need to change a live schema. Runtime stays free of third-party libraries (tests use H2). JDK 8+.

## Dependency

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto</artifactId>
    <version>2.0.1</version>
</dependency>
```

It depends on `jkit` and `jkit-sql`. The host supplies the JDBC driver.

## Run at startup

Call it once from `main`, a servlet listener, or a Spring `ApplicationRunner`. There is **no** Spring compile dependency:

```java
SqlAuto.run(SqlAutoOptions.defaults()
        .url("jdbc:mysql://localhost:3306/shop")
        .username("root")
        .password("secret")
        .packages("com.example.entity")
        .mode(SqlAutoMode.UPDATE));
```

With a `DataSource` or an open `Connection`:

```java
SqlAuto.run(SqlAutoOptions.defaults().dataSource(ds).packages("com.example.entity"));
SqlAuto.run(connection, SqlAutoOptions.defaults().entities(User.class));
```

Dry-run (plan only):

```java
SqlAutoPlan plan = SqlAuto.run(options.dryRun(true));
plan.sql();
```

Entity discovery is the same as `SqlEntities.scan`: `@SqlTable`, JPA `@Entity`, MyBatis-Plus `@TableName` (detected by FQCN, no compile dependency).

## Modes

Mirrors JPA `spring.jpa.hibernate.ddl-auto`:

| `SqlAutoMode` | Behaviour |
| --- | --- |
| `NONE` | No-op |
| `VALIDATE` | Throw if a table/column is missing or types are incompatible; never mutate |
| `UPDATE` (default) | Create missing tables, add missing columns/indexes; **do not** change existing types or drop columns/tables |
| `CREATE` | Drop managed tables then recreate (dev) |
| `CREATE_DROP` | Same as `CREATE`, then drop on JVM shutdown |

`UPDATE` only expands the schema. Type mismatches are skipped unless `alterColumn(true)`. Extra columns stay unless `dropExtraColumns(true)`.

## Configuration

`SqlAutoOptions.fromConfig()` reads `jkit.sql.auto.*`, falling back to `spring.datasource.*` for the URL/user/password.

| key | default | meaning |
| --- | --- | --- |
| `jkit.sql.auto.enabled` | `true` | `false` makes `run()` a no-op |
| `jkit.sql.auto.mode` | `update` | `none` / `validate` / `update` / `create` / `create-drop` |
| `jkit.sql.auto.packages` | empty | comma-separated scan roots |
| `jkit.sql.auto.entities` | empty | entity FQCNs |
| `jkit.sql.auto.dialect` | inferred from URL / metadata | `mysql` / `postgres` / `h2` / … |
| `jkit.sql.auto.url` | `spring.datasource.url` | JDBC URL |
| `jkit.sql.auto.fail-fast` | `true` | abort on the first failed DDL |
| `jkit.sql.auto.alter-column` | `false` | `ALTER`/`MODIFY` on type mismatch |
| `jkit.sql.auto.drop-extra-columns` | `false` | drop columns not on the entity |
| `jkit.sql.auto.create-index` | `true` | emit `CREATE INDEX` |
| `jkit.sql.auto.dry-run` | `false` | plan only |
| `jkit.sql.auto.show-sql` | `true` | log SQL |

```yaml
jkit:
  sql:
    auto:
      mode: update
      packages: com.example.entity
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shop
```

```java
SqlAuto.run(); // fromConfig()
```

Or as a standalone process (`jkit-sql-auto`, `jkit-sql`, a JDBC driver and config on the classpath):

```text
java com.alianga.jkit.sql.auto.SqlAuto
```

The dialect is taken from the option, else the JDBC URL prefix, else `DatabaseMetaData.getDatabaseProductName()`, else MySQL. Column types use the same canonical registry as the parser module.

Default **does not**: migrate data, rename columns, change primary keys, drop unused tables (except `CREATE`), or bundle a JDBC driver. For H2 in-memory `CREATE_DROP`, put `DB_CLOSE_DELAY=-1` on the URL so the database survives the startup connection closing.
