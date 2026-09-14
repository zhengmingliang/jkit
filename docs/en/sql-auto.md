# Auto schema

At startup, scan entity classes, compare them with the live schema, and run `CREATE TABLE` / `ALTER TABLE ADD` / `CREATE INDEX`.

DDL text comes from [jkit-sql](./sql.md) `SqlEntities`. This module only inspects `DatabaseMetaData` and executes. `jkit-sql` **does not run SQL and does not ship a JDBC driver**. Runtime stays free of third-party libraries. JDK 8+ (the Boot 3 starter needs JDK 17+).

Pick one path:

| Project | Artifact | Extra startup code? |
| --- | --- | --- |
| Spring Boot **2.x** | `jkit-sql-auto-spring-boot-2` | No. Runs once when the app is ready, using the app `DataSource` |
| Spring Boot **3.x** | `jkit-sql-auto-spring-boot-3` | Same |
| Plain Java / servlet / your own `main` | `jkit-sql-auto` | Yes. Call `SqlAuto.run(...)` once at startup |

All three artifacts pull in `jkit` and `jkit-sql`. The host still supplies the JDBC driver.

<MavenBadge artifact="jkit-sql-auto" />
<MavenBadge artifact="jkit-sql-auto-spring-boot-2" />
<MavenBadge artifact="jkit-sql-auto-spring-boot-3" />

---

## 1. Write an entity

Discovery recognises three styles (by FQCN, **no** JPA / MyBatis-Plus compile dependency):

- jkit `@SqlTable` / `@SqlId` / `@SqlColumn` / `@SqlGenerated`
- JPA `@Entity` / `@Table` / `@Id` / `@Column` / `@GeneratedValue` (`javax` or `jakarta`)
- MyBatis-Plus `@TableName` / `@TableId`

```java
import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

@SqlTable(name = "demo_user", comment = "users", indexes = {"idx_email:email"})
public class User {
    @SqlId
    @SqlGenerated          // integer → AUTO_INCREMENT / IDENTITY; String/UUID stays a plain PK
    private Long id;

    @SqlColumn(name = "user_name", length = 32, nullable = false, comment = "name")
    private String name;

    @SqlColumn(length = 64, unique = true)
    private String email;
}
```

Existing JPA classes need no extra annotations:

```java
@Entity
@Table(name = "file_storage")
public class FileStorage {
    @Id
    @Column(name = "id", length = 32)
    @GeneratedValue(generator = "system-uuid")  // string UUID, not IDENTITY
    private String id;
    // ...
}
```

Indexes: `@SqlTable(indexes = {"col"})` or `"name:col1,col2"`; JPA `@Table(indexes = @Index(...))` is also read. Unnamed indexes become `{table}_{col}_idx`.

Types, comments and foreign keys: [entity scan](./sql.md#entity-scan-ddl--dml).

---

## 2. Spring Boot

Add the matching starter, set `jkit.sql.auto.packages` (or `entities`), and **do not** call `SqlAuto.run` from `main`. It runs once on `ApplicationReadyEvent` and uses the container `DataSource` (usually `spring.datasource.*`).

### Boot 2.x (JDK 8+)

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-2</artifactId>
    <version>2.0.2</version>
</dependency>
```

### Boot 3.x (JDK 17+)

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-3</artifactId>
    <version>2.0.2</version>
</dependency>
```

### `application.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shop
    username: shop
    password: secret

jkit:
  sql:
    auto:
      enabled: true          # default true; false skips the whole step
      mode: update           # none / validate / update / create / create-drop
      packages: com.example.entity
      # entities:
      #   - com.example.entity.User
      show-sql: true
```

Set at least `packages` or `entities`, or the log will say `no entities, skip`.

Keep `mode: update` in production (add tables/columns/indexes only). Use `create` / `create-drop` in development.

Turn it off with `jkit.sql.auto.enabled: false` or `mode: none`.

Boot 2 registers via `spring.factories`, Boot 3 via `AutoConfiguration.imports`. Adding the artifact is enough; no `@Import`.

---

## 3. Without Spring

Core artifact only:

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto</artifactId>
    <version>2.0.2</version>
</dependency>
```

Supply a JDBC driver yourself. Call **once** from `main`, a servlet listener, or any other startup hook:

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

With an open `Connection` (the caller opens and closes it):

```java
SqlAuto.run(connection, SqlAutoOptions.defaults().entities(User.class, Order.class));
```

### Config file, no fluent API

`SqlAuto.run()` / `SqlAutoOptions.fromConfig()` read `jkit.sql.auto.*`. If `jkit.sql.auto.url` is missing they fall back to `spring.datasource.*` (a plain Java app can still use a Spring-style yaml).

```yaml
jkit:
  sql:
    auto:
      url: jdbc:mysql://localhost:3306/shop
      username: root
      password: secret
      packages: com.example.entity
      mode: update
```

```java
public static void main(String[] args) {
    SqlAuto.run(); // = SqlAuto.run(SqlAutoOptions.fromConfig())
}
```

Standalone process (`jkit-sql-auto`, `jkit-sql`, a JDBC driver and config on the classpath):

```text
java com.alianga.jkit.sql.auto.SqlAuto
```

---

## 4. Plan only, do not change the database

`SqlAuto.run(options)` with `dryRun(true)` **does not open JDBC**. The URL is only used to infer the dialect. It plans a full `CREATE TABLE` as if the schema were empty, so the database does not need to be running:

```java
SqlAutoPlan plan = SqlAuto.run(SqlAutoOptions.defaults()
        .dialect(SqlDialect.POSTGRES)   // or .url("jdbc:postgresql://...")
        .packages("com.example.entity")
        .mode(SqlAutoMode.CREATE_DROP)
        .dryRun(true));
List<String> sqls = plan.sql();
```

To preview `ALTER` against **live** tables, pass an open `Connection` to `run(connection, options)` (still with `dryRun(true)` if you only want the plan).

Config key: `jkit.sql.auto.dry-run: true`.

---

## 5. Configuration keys

Prefix is always `jkit.sql.auto.`. The Spring Boot starters bind the same set; non-Spring `fromConfig()` reads the same set.

| key | default | meaning |
| --- | --- | --- |
| `enabled` | `true` | `false` makes `run()` a no-op |
| `mode` | `update` | `none` / `validate` / `update` / `create` / `create-drop`; also `ddl-auto` |
| `packages` | empty | scan roots (list or comma-separated); aliases `package` / `base-package` / `base-packages` |
| `entities` | empty | entity FQCNs |
| `dialect` | inferred from URL / `DatabaseMetaData` | `mysql` / `postgres` / `oracle` / `oracle12` / `h2` / `dm` … (`SqlDialect.fromName`) |
| `url` | `spring.datasource.url` | JDBC URL |
| `username` | `spring.datasource.username` | username |
| `password` | `spring.datasource.password` | password |
| `driver` | guessed from URL | driver class; also `driver-class-name` |
| `fail-fast` | `true` | abort on the first failed DDL |
| `alter-column` | `false` | `ALTER`/`MODIFY` on type mismatch |
| `drop-extra-columns` | `false` | drop columns not on the entity |
| `create-index` | `true` | emit `CREATE INDEX` |
| `table-prefix` | empty | uniform table-name prefix, e.g. `t_`; applies to create / alter / drop / index / sequence / FK target table |
| `index-prefix-enabled` | `true` | whether auto-derived index names also carry `table-prefix` (so `t_user`'s index is `t_user_idx` vs `user_idx`); an explicit `@Index(name=…)` is always kept verbatim and ignores this switch |
| `quote-identifiers` | `false` | quote identifiers in the dialect |
| `show-sql` | `true` | log SQL |
| `dry-run` | `false` | plan only |
| `catalog` / `schema` | JDBC default | `DatabaseMetaData` lookup scope. If `catalog` is unset, uses `Connection.getCatalog()`; if `schema` is unset, uses `getSchema()`, then the JDBC URL (`currentSchema` on PostgreSQL-family URLs, default `public`; SQL Server `dbo`). Dry-run (no connection) also reads the URL |

The fluent API matches these keys (`.mode(SqlAutoMode.UPDATE)`, `.packages("a","b")`, `.alterColumn(true)`). Code-only switches: `postgresIdentityStyle(SERIAL)`, `foreignKeys(false)`, `autoIncrement(false)`.

---

## 6. Modes

Mirrors JPA `spring.jpa.hibernate.ddl-auto`:

| `SqlAutoMode` | Behaviour |
| --- | --- |
| `NONE` | No-op |
| `VALIDATE` | Throw `SqlAutoException` if a table/column is missing or types are incompatible; never mutate |
| `UPDATE` (default) | Create missing tables, add missing columns/indexes; **do not** change existing types or drop columns/tables |
| `CREATE` | Drop managed tables then recreate (dev) |
| `CREATE_DROP` | Same as `CREATE` at startup, then drop on JVM shutdown |

`UPDATE` is the production default: expand only. Type mismatches are skipped unless `alter-column: true`. Extra columns stay unless `drop-extra-columns: true`.

Drop managed tables explicitly (reverse FK order):

```java
SqlAuto.drop(SqlAutoOptions.defaults().url(url).entities(User.class));
```

---

## 7. Dialects

When `dialect` is unset:

1. JDBC URL prefix (`jdbc:mysql:` → MYSQL, `jdbc:postgresql:` → POSTGRES, `jdbc:oracle:` → ORACLE, Dameng `jdbc:dm:` → DAMENG, …)
2. `DatabaseMetaData.getDatabaseProductName()` (Oracle 12c+ product names map to `ORACLE12`)
3. otherwise MYSQL

`jdbc:oracle:` infers classic `ORACLE` (30-character identifiers, SEQUENCE + TRIGGER for autoincrement). For 12c `IDENTITY` / `OFFSET FETCH`, set `dialect: oracle12`.

---

## 8. Behaviour and pitfalls

**Does**

- Scan packages or an explicit entity list; referenced tables come first by foreign key
- Missing table → `CREATE TABLE` (then optional `CREATE INDEX`, comment extras, Oracle 11g SEQUENCE)
- Table exists, column missing → `ALTER TABLE … ADD`
- Missing index → `CREATE INDEX`
- Table/column comments as extra statements (MySQL inline `COMMENT`, PG/Oracle `COMMENT ON`, SQL Server `sp_addextendedproperty`)
- When the table already exists: if the entity comment is non-empty and differs from `DatabaseMetaData.REMARKS`, emit `COMMENT ON` / `ALTER TABLE … COMMENT` / MySQL `MODIFY … COMMENT`. An entity with no comment does not overwrite comments already in the database

**Default does not**

- Change existing column types, drop extra columns/tables, rename columns, change primary keys, migrate data
- Touch procedures / views / triggers (except the Oracle 11g autoincrement trigger)
- Bundle a JDBC driver

**Primary keys / identity**

- Integer + `@SqlGenerated` / `@GeneratedValue(IDENTITY|AUTO)` → `AUTO_INCREMENT` / `GENERATED … AS IDENTITY` / `SERIAL`
- String, UUID, `@GeneratedValue(generator="system-uuid")`, `GenerationType.UUID` → **`PRIMARY KEY` only** (PostgreSQL rejects `VARCHAR … IDENTITY`)
- Classic Oracle (`ORACLE`) integer autoincrement: `CREATE SEQUENCE {table}_{column}_seq` plus a `BEFORE INSERT` trigger; `CREATE_DROP` / `drop` drop the sequence first

**Index names / identifier length**

Unnamed indexes are `{table}_{col}_idx`. Classic Oracle caps identifiers at 30 characters and truncates with a 4-hex hash; `ORACLE12` allows 128. Sequence and trigger names use the same rule.

**Inherited columns**

If a subclass restates a superclass / `MappedSuperclass` column such as `create_time`, the name is emitted once (avoids PostgreSQL `column specified more than once`).

**Narrow products**

- Old OpenGauss: `.postgresIdentityStyle(SERIAL)`
- GBase 8a: `.foreignKeys(false).createIndex(false)`
- DuckDB: `.autoIncrement(false).foreignKeys(false).createIndex(false)`
- H2 in-memory `CREATE_DROP`: put `DB_CLOSE_DELAY=-1` on the URL so the database survives the startup connection closing
