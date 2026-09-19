# 自动建表

启动时扫描实体，对照现有库表，执行 `CREATE TABLE` / `ALTER TABLE ADD` / `CREATE INDEX`。

DDL 文本由 [jkit-sql](./sql.md) 的 `SqlEntities` 按方言生成；本模块只做 JDBC 元数据对比和执行。`jkit-sql` **不执行 SQL、不带 JDBC 驱动**。运行时零第三方依赖。JDK 8+（Boot 3 starter 要 JDK 17+）。

按你的项目选一条路：

| 项目类型 | 加哪个包 | 还要写启动代码吗 |
| --- | --- | --- |
| Spring Boot **2.x** | `jkit-sql-auto-spring-boot-2` | 不用。就绪后自动跑，用应用里的 `DataSource` |
| Spring Boot **3.x** | `jkit-sql-auto-spring-boot-3` | 同上 |
| 普通 Java / Servlet / 自己管 `main` | `jkit-sql-auto` | 要。在启动入口调一次 `SqlAuto.run(...)` |

三个 starter / 核心包都会带上 `jkit` 与 `jkit-sql`。JDBC 驱动仍由宿主提供。

<MavenBadge artifact="jkit-sql-auto" />
<MavenBadge artifact="jkit-sql-auto-spring-boot-2" />
<MavenBadge artifact="jkit-sql-auto-spring-boot-3" />

---

## 1. 写实体

扫描认三种标记（反射按类名，**没有** JPA / MyBatis-Plus 编译依赖）：

- jkit `@SqlTable` / `@SqlId` / `@SqlColumn` / `@SqlGenerated`
- JPA `@Entity` / `@Table` / `@Id` / `@Column` / `@GeneratedValue`（`javax` 或 `jakarta`）
- MyBatis-Plus `@TableName` / `@TableId`

```java
import com.alianga.jkit.sql.entity.SqlColumn;
import com.alianga.jkit.sql.entity.SqlGenerated;
import com.alianga.jkit.sql.entity.SqlId;
import com.alianga.jkit.sql.entity.SqlTable;

@SqlTable(name = "demo_user", comment = "用户", indexes = {"idx_email:email"})
public class User {
    @SqlId
    @SqlGenerated          // 整数列 → AUTO_INCREMENT / IDENTITY；字符串/UUID 只当主键
    private Long id;

    @SqlColumn(name = "user_name", length = 32, nullable = false, comment = "用户名")
    private String name;

    @SqlColumn(length = 64, unique = true)
    private String email;
}
```

已经在用 JPA 的类不用改注解，例如：

```java
@Entity
@Table(name = "file_storage")
public class FileStorage {
    @Id
    @Column(name = "id", length = 32)
    @GeneratedValue(generator = "system-uuid")  // 字符串 UUID，不会写成 IDENTITY
    private String id;
    // ...
}
```

索引：`@SqlTable(indexes = {"col"})` 或 `"name:col1,col2"`；JPA `@Table(indexes = @Index(...))` 同样认。未写名字时生成 `{table}_{col}_idx`。

更细的类型映射、注释、外键见 [实体扫描](./sql.md#实体扫描生成-ddl--dml)。

---

## 2. Spring Boot 怎么用

加对应 starter，配 `jkit.sql.auto.packages`（或 `entities`），**不必**在 `main` 里调 `SqlAuto.run`。`ApplicationReadyEvent` 时自动执行一次，数据源用容器里的 `DataSource`（通常就是 `spring.datasource.*`）。

默认 `phase=eager`：同步在上下文刷新期完成（DataSource 就绪后、Web 端口开放前），
`fail-fast` 失败时应用在接收流量前就退出；要恢复「应用就绪后再执行」的行为，
配 `jkit.sql.auto.phase: ready`。没有 DataSource bean 且未配置 `jkit.sql.auto.url`
时 starter 直接跳过（warn 日志），不会让应用启动失败。MyBatis / JPA 等 Bean 若
依赖表已存在，可加 `@DependsOn("sqlAutoStartupListener")` 保证初始化顺序。

### Boot 2.x（JDK 8+）

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-2</artifactId>
    <version>2.0.2</version>
</dependency>
```

### Boot 3.x（JDK 17+）

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
      enabled: true          # 默认 true；false 则整段跳过
      mode: update           # none / validate / update / create / create-drop
      packages: com.example.entity
      # entities:            # 不想扫包时，列出全限定名
      #   - com.example.entity.User
      show-sql: true
```

`packages` 与 `entities` 至少配一项，否则启动日志会是 `no entities, skip`。

生产建议保持 `mode: update`（只加表/列/索引，不改已有列、不删列）。开发可以 `create` / `create-drop`。

关掉：

```yaml
jkit.sql.auto.enabled: false
```

或 `mode: none`。

Boot 2 注册走 `spring.factories`，Boot 3 走 `AutoConfiguration.imports`，引入坐标即可，不用 `@Import`。

---

## 3. 非 Spring 怎么用

只加核心包：

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto</artifactId>
    <version>2.0.2</version>
</dependency>
```

再自己提供 JDBC 驱动。在 `main`、Servlet 监听器等启动入口调 **一次**：

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
    // 再启动 Web / 业务
}
```

已有 `DataSource`：

```java
SqlAuto.run(SqlAutoOptions.defaults()
        .dataSource(dataSource)
        .packages("com.example.entity"));
```

已有 `Connection`（调用方负责开关连接）：

```java
SqlAuto.run(connection, SqlAutoOptions.defaults().entities(User.class, Order.class));
```

### 用配置文件、不写链式 API

`SqlAuto.run()` / `SqlAutoOptions.fromConfig()` 读 `jkit.sql.auto.*`。没有 `jkit.sql.auto.url` 时回落 `spring.datasource.*`（普通 Java 也可以把 Spring 风格的 yml 当配置用）。

```yaml
# application.yml，放在工作目录或 classpath
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

独立进程（classpath 含本模块、`jkit-sql`、JDBC 驱动和配置文件）：

```text
java com.alianga.jkit.sql.auto.SqlAuto
```

---

## 4. 只看 SQL、不改库

`dryRun(true)` 的 `SqlAuto.run(options)` **不打开 JDBC**。URL 只用来推断方言，按空库规划全量 `CREATE TABLE`，库没启动也能打印：

```java
SqlAutoPlan plan = SqlAuto.run(SqlAutoOptions.defaults()
        .dialect(SqlDialect.POSTGRES)   // 或 .url("jdbc:postgresql://...")
        .packages("com.example.entity")
        .mode(SqlAutoMode.CREATE_DROP)
        .dryRun(true));
List<String> sqls = plan.sql();
```

要对照**现有表**看 `ALTER`，把已打开的 `Connection` 传给 `run(connection, options)`，同样可以 `dryRun(true)`（只规划不执行）。

配置项：`jkit.sql.auto.dry-run: true`。

---

## 5. 配置项

前缀一律 `jkit.sql.auto.`。Spring Boot starter 绑同一套；非 Spring 的 `fromConfig()` 也读这一套。

| key | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | `false` 时 `run()` 直接返回 |
| `mode` | `update` | `none` / `validate` / `update` / `create` / `create-drop`；也认 `ddl-auto` |
| `packages` | （空） | 扫描包，列表或逗号分隔；别名 `package` / `base-package` / `base-packages` |
| `entities` | （空） | 实体 FQCN 列表 |
| `dialect` | 从 URL / `DatabaseMetaData` 推断 | `mysql` / `postgres` / `oracle` / `oracle12` / `h2` / `dm` …（`SqlDialect.fromName`） |
| `url` | `spring.datasource.url` | JDBC URL |
| `username` | `spring.datasource.username` | 用户名 |
| `password` | `spring.datasource.password` | 密码 |
| `driver` | 按 URL 猜 | 驱动类；也认 `driver-class-name` |
| `fail-fast` | `true` | 一条 DDL 失败是否立即抛错 |
| `alter-column` | `false` | 类型不一致时是否 `ALTER`/`MODIFY` |
| `drop-extra-columns` | `false` | 是否删除实体里没有的列 |
| `create-index` | `true` | 是否补 `CREATE INDEX` |
| `table-prefix` | （空） | 表名统一前缀，如 `t_`；作用于建表 / 改表 / 删表 / 索引 / 序列 / 外键目标表 |
| `index-prefix-enabled` | `true` | 自动派生的索引名是否也带 `table-prefix`（如 `t_user` 的索引是 `t_user_idx` 还是 `user_idx`）；实体里显式写的 `@Index(name=…)` 始终原样保留，不受此开关影响 |
| `quote-identifiers` | `false` | 标识符加方言引号 |
| `foreign-keys` | `true` | 是否在 CREATE TABLE 里写 FOREIGN KEY；GBase 8a 等不支持时设 `false` |
| `auto-increment` | `true` | 是否生成自增子句；DuckDB 等不认 IDENTITY 时设 `false` |
| `postgres-identity-style` | `identity` | PG / OpenGauss 自增写法：`identity` 或 `serial`（老版 OpenGauss 不认 GENERATED…IDENTITY） |
| `lock` | `true` | 执行前在当前连接上取元数据锁（MySQL `GET_LOCK` / PG `pg_advisory_lock`），多实例并发冷启动串行化；不支持的方言自动跳过 |
| `history` | `false` | 把已应用的变更写入历史表（审计用），行含时间 / 主机 / 模式 / 语句 |
| `history-table` | `jkit_schema_history` | 历史表名，不存在自动创建 |
| `export` | （空） | 每次规划后把将执行的 DDL 写入该文件（UTF-8，每条一行分号结尾），配合 `dry-run` 可当 schema 生成器用 |
| `phase` | `eager` | Spring Starter 专用：`eager` 在上下文刷新期执行（端口开放前完成）；`ready` 恢复 2.0.1 的应用就绪事件后执行 |
| `show-sql` | `true` | 打日志 |
| `dry-run` | `false` | 只规划不执行 |
| `catalog` / `schema` | JDBC 默认 | `DatabaseMetaData` 查找范围。未配 `catalog` 时用连接 `getCatalog()`；未配 `schema` 时用 `getSchema()`，再不行从 JDBC URL 解析（PG 系 `currentSchema` 缺省 `public`，SQL Server `dbo`）。dry-run 无连接时同样从 URL 取 |

链式 API 与配置一一对应，例如 `.mode(SqlAutoMode.UPDATE)`、`.packages("a","b")`、`.alterColumn(true)`、`.lock(false)`、`.history(true)`、`.export("target/schema.sql")`。

---

## 6. 模式

对标 JPA `spring.jpa.hibernate.ddl-auto`：

| `SqlAutoMode` | 行为 |
| --- | --- |
| `NONE` | 什么都不做 |
| `VALIDATE` | 缺表 / 缺列 / 类型不兼容时抛 `SqlAutoException`，不改库 |
| `UPDATE`（默认） | 缺表则建、缺列则加、缺索引则建；**默认不改已有列类型、不删列/表** |
| `CREATE` | 先 `DROP` 托管表再按实体重建（开发用） |
| `CREATE_DROP` | 启动同 `CREATE`，JVM 退出时再删表 |

`UPDATE` 是生产默认：只追加，不收缩。已有列类型对不上时默认跳过，要改列需 `alter-column: true`。实体里没有的列默认保留，要删需 `drop-extra-columns: true`。

显式删托管表（按外键逆序）：

```java
SqlAuto.drop(SqlAutoOptions.defaults().url(url).entities(User.class));
```

---

## 7. 方言

未配置 `dialect` 时：

1. JDBC URL 前缀（`jdbc:mysql:` → MYSQL，`jdbc:postgresql:` → POSTGRES，`jdbc:oracle:` → ORACLE，达梦 `jdbc:dm:` → DAMENG …）
2. `DatabaseMetaData.getDatabaseProductName()`（Oracle 12c+ 产品名会落到 `ORACLE12`）
3. 再不行默认 MYSQL

`jdbc:oracle:` 推断的是经典 `ORACLE`（标识符 30 字符，自增走 SEQUENCE + TRIGGER）。要用 12c 的 `IDENTITY` / `OFFSET FETCH`，显式 `dialect: oracle12`。

---

## 8. 行为细节与常见坑

**会做**

- 扫描包或显式实体列表，按外键把被引用表排在前面
- 表不存在 → `CREATE TABLE`（随后可跟 `CREATE INDEX`、注释附录、Oracle 11g SEQUENCE）
- 表在、列缺 → `ALTER TABLE … ADD`
- 索引缺 → `CREATE INDEX`
- 表/列注释按方言拆成独立语句（MySQL 内联 `COMMENT`，PG/Oracle `COMMENT ON`，SQL Server `sp_addextendedproperty`）
- 表已存在时：实体注释非空且与 `DatabaseMetaData.REMARKS` 不一致 → `COMMENT ON` / `ALTER TABLE … COMMENT` / MySQL `MODIFY … COMMENT`。实体没写注释时不覆盖库里已有注释（2.0.2：注释同步是对照活表，不是每次无条件覆盖）

**默认不做**

- 改已有列类型、删多余列/表、改列名、改主键、迁数据
- 存储过程 / 视图 / 触发器（Oracle 11g 自增触发器除外）
- 把 JDBC 驱动打进本模块

**主键 / 自增**

- 整数 + `@SqlGenerated` / `@GeneratedValue(IDENTITY|AUTO)` → `AUTO_INCREMENT` / `GENERATED … AS IDENTITY` / `SERIAL`
- 字符串、UUID、`@GeneratedValue(generator="system-uuid")`、`GenerationType.UUID` → **只写 `PRIMARY KEY`**，不会给 PostgreSQL 生成 `VARCHAR … IDENTITY`（会语法错误）
- 经典 Oracle（`ORACLE`）整数自增：`CREATE SEQUENCE {table}_{column}_seq` + `BEFORE INSERT` 触发器；`CREATE_DROP` / `drop` 会先 `DROP SEQUENCE`

**索引名 / 标识符长度**

未命名索引 `{table}_{col}_idx`。经典 Oracle 上限 30 字符，超长截断并追加 4 位散列；`ORACLE12` 为 128。序列名、触发器名同一规则。

**继承列**

子类与 `MappedSuperclass` / 父类同时声明 `create_time` 时只生成一次，避免 PostgreSQL `column specified more than once`。

**窄产品**

- OpenGauss 老版本：`.postgresIdentityStyle(SERIAL)` 或 `jkit.sql.auto.postgres-identity-style: serial`
- GBase 8a：`.foreignKeys(false).createIndex(false)`
- DuckDB：`.autoIncrement(false).foreignKeys(false).createIndex(false)`
- H2 内存库 `CREATE_DROP`：URL 加 `DB_CLOSE_DELAY=-1`，否则连接一关库就没了

**多实例与审计**

- 并发冷启动：`lock: true`（默认）让多实例串行执行 DDL，避免「都读到表不存在、都去 CREATE」的竞态；MySQL / PostgreSQL 系有效，其它方言自动跳过
- 审计：`history: true` 把每次实际执行的 DDL 写入 `jkit_schema_history`（时间 / 主机 / 模式 / 语句），历史表不存在自动创建，不在实体清单里因此永远不会被同步或删除
- 评审：`export: target/schema.sql` 把本次计划落盘，走 DBA 变更评审流程；配合 `dry-run` 就是纯 schema 生成器

**MySQL 注释同步提示**

Connector/J 默认 `useInformationSchema=false` 时 `getColumns` 的 REMARKS 恒为空，
注释同步会认为库里没有注释、每次启动都重发 `MODIFY … COMMENT`（幂等但有噪音）。
URL 加 `useInformationSchema=true` 即可让注释比对基于真实值。
