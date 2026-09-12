# 自动建表模块

`com.alianga:jkit-sql-auto` 在应用启动时扫描实体、对照现有库表，执行 `CREATE TABLE` / `ALTER TABLE ADD`。DDL 文本由 [jkit-sql](./sql.md) 的 `SqlEntities` 按方言生成；本模块只负责 JDBC 元数据对比和执行。

`jkit-sql` **不执行 SQL、不引 JDBC 驱动**。需要连库改结构时加本模块。运行时仍零第三方依赖（测试用 H2）。JDK 8+。

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto</artifactId>
    <version>2.0.1</version>
</dependency>
```

会传递依赖 `jkit` 与 `jkit-sql`。JDBC 驱动由宿主提供（MySQL / PostgreSQL / Oracle 等）；本模块只调用 `java.sql`。

Spring Boot 应用加对应 starter，就绪后自动跑一次（仍用应用里的 `DataSource`）：

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

## 启动时执行

在 `main`、Servlet 监听器或 Spring `ApplicationRunner` 里调一次即可，**没有** Spring 编译依赖：

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

已有 `DataSource` 时：

```java
SqlAuto.run(SqlAutoOptions.defaults()
        .dataSource(dataSource)
        .packages("com.example.entity"));
```

已有 `Connection`：

```java
SqlAuto.run(connection, SqlAutoOptions.defaults().entities(User.class, Order.class));
```

只看将要执行的 SQL、不改库。`run(options)` 在 `dryRun(true)` 时**不打开 JDBC**（URL 只用来推断方言），按空库规划全量 `CREATE`，库没启动也能打印 SQL。若要对照现有表看 `ALTER`，把已打开的 `Connection` 传给 `run(connection, options)`：

```java
SqlAutoPlan plan = SqlAuto.run(SqlAutoOptions.defaults()
        .url(url).packages("com.example.entity").dryRun(true));
plan.sql(); // List<String>
```

实体注解与 `SqlEntities.scan` 相同：`@SqlTable`、JPA `@Entity`、MyBatis-Plus `@TableName`（反射认 FQCN，无编译依赖）。见 [实体扫描](./sql.md#实体扫描生成-ddl--dml)。

## 模式

对标 JPA `spring.jpa.hibernate.ddl-auto`：

| `SqlAutoMode` | 行为 |
| --- | --- |
| `NONE` | 什么都不做 |
| `VALIDATE` | 缺表 / 缺列 / 类型不兼容时抛 `SqlAutoException`，不改库 |
| `UPDATE`（默认） | 缺表则建、缺列则加、缺索引则建；**默认不改已有列类型、不删列/表** |
| `CREATE` | 先 `DROP` 托管表再按实体重建（开发用） |
| `CREATE_DROP` | 启动同 `CREATE`，JVM 退出时再删表 |

`UPDATE` 是生产默认：只追加，不收缩。已有列类型变窄或对不上时默认跳过；要改列需显式 `alterColumn(true)`。实体里没有的列默认保留；要删需 `dropExtraColumns(true)`。

## 配置项

`SqlAutoOptions.fromConfig()` 读 `jkit.sql.auto.*`，数据源回落 `spring.datasource.*`（方便已经在用 Spring 配置的应用）。也可用链式 API，不必走配置文件。

| key | 默认 | 说明 |
| --- | --- | --- |
| `jkit.sql.auto.enabled` | `true` | `false` 时 `run()` 直接返回 |
| `jkit.sql.auto.mode` | `update` | `none` / `validate` / `update` / `create` / `create-drop` |
| `jkit.sql.auto.packages` | （空） | 扫描包，逗号分隔；也可用 `package` / `base-package` |
| `jkit.sql.auto.entities` | （空） | 实体 FQCN 列表 |
| `jkit.sql.auto.dialect` | 从 URL / `DatabaseMetaData` 推断 | `mysql` / `postgres` / `h2` / `oracle` …（`SqlDialect.fromName`） |
| `jkit.sql.auto.url` | `spring.datasource.url` | JDBC URL |
| `jkit.sql.auto.username` | `spring.datasource.username` | 用户名 |
| `jkit.sql.auto.password` | `spring.datasource.password` | 密码 |
| `jkit.sql.auto.driver` | 按 URL 猜测 | 驱动类；也可 `driver-class-name` |
| `jkit.sql.auto.fail-fast` | `true` | 一条 DDL 失败是否立即抛错 |
| `jkit.sql.auto.alter-column` | `false` | 类型不一致时是否 `ALTER`/`MODIFY` |
| `jkit.sql.auto.drop-extra-columns` | `false` | 是否删除实体中没有的列 |
| `jkit.sql.auto.create-index` | `true` | 是否补 `CREATE INDEX` |
| `jkit.sql.auto.quote-identifiers` | `false` | 标识符加方言引号 |
| `jkit.sql.auto.show-sql` | `true` | 打日志 |
| `jkit.sql.auto.dry-run` | `false` | 只规划不执行；`run(options)` 时不打开 JDBC，URL 仅用于推断方言 |
| `jkit.sql.auto.catalog` / `schema` | JDBC 默认 | `DatabaseMetaData` 查找范围 |

`application.yml` 示例：

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

也可以当独立进程跑（classpath 含本模块、`jkit-sql`、JDBC 驱动和配置文件）：

```text
java com.alianga.jkit.sql.auto.SqlAuto
```

## 方言

未配置 `dialect` 时：

1. JDBC URL 前缀（`jdbc:mysql:` → MYSQL，`jdbc:postgresql:` → POSTGRES，`jdbc:h2:` → H2，`jdbc:oracle:` → ORACLE，达梦 `jdbc:dm:` → DAMENG …）
2. `DatabaseMetaData.getDatabaseProductName()`（Oracle 12+ 用 `ORACLE12`）
3. 再不行默认 MYSQL

`CREATE TABLE` / 列类型走 `SqlEntities` + canonical 类型表，与解析模块同一套 MySQL / PostgreSQL / Oracle / SQL Server / H2 / SQLite 等写法。

## 会做什么、不做什么

做：

- 扫描包或显式实体列表，按外键把被引用表排在前面
- 表不存在 → `CREATE TABLE`（可选随后 `CREATE INDEX`、注释附录、无 IDENTITY 方言的 SEQUENCE）
- 表在、列缺 → `ALTER TABLE … ADD [COLUMN]`（列注释同样按方言附录）
- 索引缺 → `CREATE INDEX`
- `VALIDATE` 把缺表/缺列/类型不兼容收成异常
- `CREATE` / `CREATE_DROP` 先删再建模
- `SqlAuto.drop(...)` 按外键逆序删托管表

默认**不做**：

- 改已有列类型（除非 `alterColumn=true`）
- 删实体里没有的列或表（除非 `dropExtraColumns` / `CREATE`）
- 数据迁移、改列名、改主键
- 存储过程 / 视图 / 触发器
- 把 JDBC 驱动打进本模块

显式删托管表（按外键逆序）：

```java
SqlAuto.drop(SqlAutoOptions.defaults().url(url).entities(User.class));
```

表 / 列注释（`@SqlTable(comment)` / `@SqlColumn(comment)`）按方言生成：MySQL / Hive / ClickHouse 内联 `COMMENT`；H2 列内 `COMMENT`、表级 `COMMENT ON TABLE`；PostgreSQL / Oracle / DB2 / ANSI 为 `COMMENT ON`；SQL Server 为 `sp_addextendedproperty`；Presto 表级 `WITH (comment=…)`；SQLite 忽略。自动建表把附录拆成独立语句执行（`CREATE TABLE` 本身不含这些附录）。

Oracle ≤11g（方言 `ORACLE`）没有 IDENTITY：自增主键改成 `CREATE SEQUENCE {table}_{column}_seq` + `BEFORE INSERT` 触发器；`CREATE_DROP` / `drop` 会先 `DROP SEQUENCE` 再删表。达梦（`DAMENG`）列上写 `IDENTITY`。Oracle 12c+ 仍用 `GENERATED … AS IDENTITY`。

未命名索引默认 `{table}_{col}_idx`。经典 Oracle 标识符上限 30 字符，超长时自动截断并追加 4 位散列（`ORACLE12` 为 128，一般不必截）。显式写了 `name:` 且仍超长的同样截断。序列名 / 触发器名走同一规则。

部分产品建表能力比一等方言窄，可用选项关掉对应 DDL：

- OpenGauss 老版本不认 `GENERATED … IDENTITY`：`postgresIdentityStyle(SERIAL)`
- GBase 8a 表内 `FOREIGN KEY` / 独立 `CREATE INDEX` 可能报 `unsupported key algorithm`：`foreignKeys(false).createIndex(false)`
- DuckDB 不认 `AUTOINCREMENT` / `IDENTITY` / 表内 FK：`autoIncrement(false).foreignKeys(false).createIndex(false)`
- Oracle ≤11g：自动生成 SEQUENCE + TRIGGER；达梦列上写 IDENTITY

H2 内存库做 `CREATE_DROP` 关机删表时，URL 需带 `DB_CLOSE_DELAY=-1`，否则连接一关库就没了。
