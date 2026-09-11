# 表结构跨方言转换

`jkit-sql` 的 `com.alianga.jkit.sql.schema` 包：把 `CREATE TABLE` / 部分 `ALTER` 和查询语句从一种 `SqlDialect` 转到另一种。JDK 8，零新增运行时依赖。入口是 `SQL.convert`。

本文是**当前实现**的说明（由 v2 Normal Form 思路 + v3 结构化列/有损映射整改合并而来）。未实现的旧稿（`record`/`sealed`、字符串模板函数表、pairwise 映射）一律不收录。

## 一、要解决的问题

解析、格式化、分页改写已经按方言工作，缺的是**类型、自增、函数、列约束**在方言之间的转换。典型场景：MySQL DDL 迁到 PostgreSQL / Oracle；同一条 SQL 模板按租户库改写。

不维护 MySQL↔PG、MySQL↔Oracle 这种 O(N²) pairwise 表。每个方言只声明「我把每个 canonical 类型写成什么」，转换走公共中间态：

```
源 SQL  ──parse──►  AST
                      │  列定义：SqlLexer → ColumnDefinition
                      │  类型：方言写法 ⇄ CanonicalType
                      │  函数：在 clone 后的 AST 上直接换节点
                      ▼
                 format(目标方言) ──► 目标 SQL
```

新增方言成本是 O(canonical 类型数)，不是 O(方言对数)。多对一（如 DATETIME 与 TIMESTAMP 在 PG 都是 `TIMESTAMP`）必须**显式登记有损映射**，反向查找返回主类型，不能假装双向无损。

## 二、代码位置

```
jkit-sql/src/main/java/com/alianga/jkit/sql/schema/
├── model/          CanonicalType, SqlDataType, ColumnDefinition, ColumnConstraint
├── parse/          SqlColumnDefinitionParser（复用 SqlLexer）
├── registry/       SqlDataTypeRegistry + Builtins + DialectTypeForm
│                   LossyMapping + RegistryValidator
├── rewrite/        AutoIncrementStrategy, DefaultValueCoercer,
│                   FunctionAstRewriter（只遍历）, SqlFunctionRegistry,
│                   BuiltinFunctionRewriter, DateFormatRewriteRule, FunctionRewriteRule
├── convert/        SqlSchemaConverter, Options, Report, Result
└── spi/            SqlSchemaConverterProvider（registerTypes + registerFunctions）
```

`SqlDdlStatement.columnDefinitions()` 仍是 `List<String>`，不改公开签名。结构化列由 `SqlColumnDefinitionParser.fromDdl(ddl, dialect)` 另开通路。

## 三、公开 API

```java
String pg = SQL.convert(mysqlSql, SqlDialect.MYSQL, SqlDialect.POSTGRES);

ConversionResult r = SQL.convert(sql, SqlDialect.MYSQL, SqlDialect.ORACLE,
        SqlSchemaConvertOptions.defaults()
                .failOnSeverity(ConversionWarning.Severity.MANUAL_ACTION_REQUIRED));
r.sql();
r.report().warnings();

SQL.convertBatch(sqls, SqlDialect.MYSQL, SqlDialect.POSTGRES);
```

同源同目标时原样返回。多语句用 `; ` 拼接。`format` / `toSqlString` 仍负责分页形态（LIMIT ↔ ROWNUM ↔ FETCH）。

### 选项 `SqlSchemaConvertOptions`

| 项 | 默认 | 作用 |
|---|---|---|
| `unsignedHandling` | `UPSIZE` | `UNSIGNED`：升档 / 建议 CHECK / 丢掉并告警 |
| `stripDialectOptions` | `true` | 目标不支持时去掉 ENGINE / CHARSET / COLLATE |
| `postgresIdentityStyle` | `IDENTITY` | PG 自增：`GENERATED … AS IDENTITY` 或 `SERIAL` |
| `failOnSeverity` | `null` | 达到该警告级别则抛 `SqlSchemaConversionException` |
| `generateOracleSequence` | `false` | Oracle ≤11g 自增生成 SEQUENCE+TRIGGER 附录 |
| `parallelBatch` | `false` | `convertBatch` 并行 |
| `promoteLongVarchar` | `true` | VARCHAR 超长（MySQL/Oracle 4000、SQL Server 8000）提升为 TEXT/CLOB |

### 报告 `ConversionReport`

警告三级：`INFO`、`SEMANTIC_RISK`、`MANUAL_ACTION_REQUIRED`。另计已转换列数 / 原样保留数。

## 四、当前支持哪些数据库

`SQL.convert(sql, source, target)` 的 `source` / `target` 是 `SqlDialect`。**一等方言 12 个**，类型表对这 12 个都登记了全部 canonical；解析、分页 format、DDL 列转换都可以互转。产品名通过 `SqlDialect.fromName` 归到某一等方言（无法识别时默认 MySQL）。

转换覆盖的语句：`CREATE TABLE`（列类型/约束/自增）、`ALTER TABLE ADD/MODIFY/CHANGE` 的列类型、SELECT/DML 里的函数与分页（分页复用 `format`）。不转换存储过程、触发器、视图。

### 4.1 一等方言（可作 source 或 target）

| `SqlDialect` | 代表产品 | 分页 | 自增转换 | 说明 |
|---|---|---|---|---|
| `MYSQL` | MySQL / MariaDB / TiDB / OceanBase MySQL 模式 / PolarDB-MySQL / GBase 8a / StarRocks / Doris 等 | `LIMIT`/`OFFSET` | `AUTO_INCREMENT` | 默认方言；`TINYINT(1)`↔布尔 |
| `POSTGRES` | PostgreSQL / GaussDB / openGauss / Greenplum / Kingbase / MogDB / Highgo / Cockroach / Redshift 等 | `LIMIT`/`OFFSET`，亦认 FETCH | `GENERATED … IDENTITY` 或 `SERIAL` | 真库验证过（本机 PG 13、容器 PG 16） |
| `ORACLE` | Oracle ≤11g / 达梦 / Oscar / OceanBase Oracle 模式 | 仅 `ROWNUM` | **不生成**，`MANUAL_ACTION_REQUIRED` | 真库验证过（本机 Oracle 11g） |
| `ORACLE12` | Oracle 12c / 18c / 19c / 21c | 裸 SELECT 可用 `OFFSET/FETCH` | `GENERATED … IDENTITY` | 类型写法与 `ORACLE` 相同，只是自增和分页不同 |
| `SQLSERVER` | SQL Server / Azure SQL / 别名 mssql、tsql、sybase | 第 1 页 `TOP`，其后 `OFFSET FETCH` | `IDENTITY(s,i)` | |
| `H2` | H2 | `LIMIT`/`OFFSET` | `AUTO_INCREMENT` | 内存库验证过 |
| `ANSI` | SQL-92 / 别名 sql92、standard；Snowflake 暂归此 | `LIMIT`/`OFFSET` 与 FETCH | `GENERATED … IDENTITY` | |
| `DB2` | DB2 LUW | 仅 `FETCH FIRST` | `GENERATED … IDENTITY` | |
| `SQLITE` | SQLite；GBase 8s 归此 | `LIMIT`/`OFFSET`，无 FETCH | `AUTOINCREMENT` | |
| `HIVE` | Hive / MaxCompute(ODPS) / ArgoDB | 仅 `LIMIT`（无 OFFSET） | 去掉并告警 | 类型多落到 `STRING`/`INT`，有损 |
| `CLICKHOUSE` | ClickHouse | `LIMIT`（含逗号风格） | 去掉并告警 | 类型如 `Int32`/`String`/`DateTime`，有损较多 |
| `PRESTO` | Presto / Trino | `LIMIT` | 去掉并告警 | |

任意两个一等方言都可以互为 source/target，例如 `MYSQL→POSTGRES`、`POSTGRES→ORACLE12`、`SQLSERVER→MYSQL`。有损方向（如 `MEDIUMINT`→PG `INTEGER` 再转回变成 `INT`）见第六节。

### 4.2 `fromName` 别名（同一套转换规则）

| 归入 | `fromName(...)` 可识别的名称（大小写不敏感） |
|---|---|
| `MYSQL` | mysql、mariadb、tidb、gbase、gbase8a、oceanbase、polardb、starrocks、doris、percona、singlestore、memsql、tdsql、greatsql、goldendb、adb、analyticdb、ads、selectdb、matrixone、stonedb |
| `POSTGRES` | postgres、postgresql、pgsql、gauss、gaussdb、opengauss、greenplum、kingbase、cockroach、redshift、highgo、uxdb、mogdb、vastbase、antdb、ivorysql、xcloud |
| `ORACLE` | oracle、oracle11、11g、dm、dameng、oscar、oceanbase_oracle |
| `ORACLE12` | oracle12、oracle12c、12c、oracle18、oracle19、oracle21、19c、21c |
| `SQLSERVER` | sqlserver、mssql、tsql、sybase、azure、azuresql、sqlserver2012 |
| `HIVE` | hive、hive2、hive3、maxcompute、odps、argo、argodb |
| `PRESTO` | presto、prestodb、trino |
| `SQLITE` | sqlite、sqlite3、gbase8s |
| `DB2` | db2、db2luw |
| `ANSI` | ansi、sql92、standard、snowflake |
| `H2` | h2 |
| `CLICKHOUSE` | clickhouse、ck、ch |

别名只表示「按该一等方言的引号/分页/类型表来转」，**不保证** StarRocks、Snowflake 等产品的专有类型（如 `BITMAP`、`VARIANT`）能识别——那些会变成 `UNKNOWN` 并保留原文。

### 4.3 各层覆盖程度

| 能力 | 覆盖 |
|---|---|
| 列类型（20 个 canonical × 12 方言） | 全登记，CI `RegistryValidationTest` |
| `CREATE TABLE` 列约束 / 自增 | 12 方言均有策略；Hive/CH/Presto/Oracle11g 自增只告警不瞎生成 |
| SELECT/DML 函数 | 见第八节；按目标方言分支，不是 12×12 张函数表 |
| 分页 | 随 `format(stmt, target)`，与 `SQL.convert` 同一条链路 |
| 真库建表回归 | MySQL 8、PostgreSQL 13/16、Oracle 11g、H2（`tools-test`） |

```java
SQL.convert(sql, SqlDialect.fromName("gbase8a"), SqlDialect.fromName("opengauss"));
// 等价 MYSQL → POSTGRES

SQL.convert(sql, SqlDialect.fromName("dm"), SqlDialect.MYSQL);
// 达梦按 ORACLE（11g 分页/自增）→ MYSQL
```

## 五、列模型与解析

`ColumnDefinition`：列名、`SqlDataType`、约束列表、原文、`tableConstraint` 标记。

`SqlDataType`：原始类型名、precision/scale、`TypeAttribute`（`UNSIGNED`、`ZEROFILL`、`BINARY_CHARSET`、`NATIONAL`、`WITH_TIME_ZONE`、`WITHOUT_TIME_ZONE`）。

`ColumnConstraint.Kind`：`NOT_NULL`、`NULLABLE`、`DEFAULT_VALUE`、`AUTO_INCREMENT`、`INLINE_PRIMARY_KEY`、`INLINE_UNIQUE`、`COMMENT`、`ON_UPDATE`、`CHARACTER_SET`、`COLLATION`。Java 8 无 sealed，用枚举做穷尽分支。

`SqlColumnDefinitionParser` 用现有 `SqlLexer` 切词，识别多词类型（`DOUBLE PRECISION`、`CHARACTER VARYING`、`TIMESTAMP WITH TIME ZONE`）、`SERIAL`→整数+自增、表级 `PRIMARY KEY` / `FOREIGN KEY` / `KEY` 等。解析失败不抛，返回 `UNKNOWN` + 原文。

## 六、类型注册表

`CanonicalType`（另加 `UNKNOWN`）：

`TINYINT` `SMALLINT` `MEDIUMINT` `INT` `BIGINT` `FLOAT` `DOUBLE` `DECIMAL` `CHAR` `VARCHAR` `TEXT` `DATE` `DATETIME` `TIMESTAMP` `TIME` `BINARY` `BLOB` `BOOLEAN` `JSON` `YEAR` `UUID` `INTERVAL`

内置覆盖全部 12 个 `SqlDialect`。`ORACLE` 与 `ORACLE12` **类型写法相同**，差别只在自增。

`DialectTypeForm`：模板无 `%d` 则是完整字面量（`NUMBER(10)`、`TINYINT(1)`、`VARCHAR(MAX)`），忽略传入精度；有 `%d` 才拼 precision/scale，缺省时去掉括号。

### 反向查找（`fromDialect`）

1. 归一化（大写、压缩空白）
2. 完整字面量精确表（`TINYINT(1)`→`BOOLEAN`，`NUMBER(10)`→`INT`）
3. 含括号则查「精度模板」基名（`VARCHAR(100)`→`VARCHAR`），避免命中 `TEXT` 的裸 `VARCHAR`
4. 别名（`INTEGER`→`INT`，`VARCHAR2`→`VARCHAR`，`NUMBER` 无精度→`DECIMAL`）
5. `FOR BIT DATA`→`BINARY`（DB2）
6. 仍失败 → `UNKNOWN`，`convert` 返回原文

完整字面量（无 `%d` 且带括号）**不**登记基名，所以 `NUMBER(10)` 不会把 `NUMBER(10,2)` 抢成整数。

多对一必须声明 `LossyMapping`（主类型 + 收敛集合）。`RegistryValidator` 在内置表 `freeze` 时检查：每个 canonical×方言都有写法，未声明碰撞直接失败。`RegistryValidationTest` 再跑一遍。

常用有损例子：PG 上 `DATETIME`/`TIMESTAMP` 都是 `TIMESTAMP`（主类型 `TIMESTAMP`）；`MEDIUMINT`/`INT` 都是 `INTEGER`（主类型 `INT`）。

## 七、DDL 转换语义

### 自增

| 目标 | 生成 |
|---|---|
| MYSQL、H2 | `AUTO_INCREMENT` |
| POSTGRES | `GENERATED … AS IDENTITY`（或选项 `SERIAL`/`BIGSERIAL`） |
| ORACLE12、ANSI、DB2 | `GENERATED … AS IDENTITY` |
| SQLSERVER | `IDENTITY(seed,increment)` |
| SQLITE | `AUTOINCREMENT` |
| **ORACLE（≤11g）** | **不生成**，`MANUAL_ACTION_REQUIRED`（需手工 SEQUENCE+TRIGGER） |
| HIVE / CLICKHOUSE / PRESTO | 去掉并 `MANUAL_ACTION_REQUIRED` |

### 其它列级规则

- `UNSIGNED` 默认升档：`TINYINT→SMALLINT→INT→BIGINT`（`BIGINT UNSIGNED` 无法再升，告警）
- `DEFAULT 0/1` 转到有真布尔的方言（PG/H2/ANSI 等）写成 `false`/`true`；Oracle / SQL Server / MySQL 保持 0/1
- 列 `COMMENT` 仅 MySQL 内联保留，其它方言去掉并 INFO
- `ON UPDATE` 非 MySQL 去掉并 `SEMANTIC_RISK`
- 表级 MySQL `KEY`/`INDEX`/`FULLTEXT` 从表定义**删除**并告警（否则目标库无法执行）；`UNIQUE KEY` 改成 `UNIQUE (...)`
- `ALTER TABLE ADD/MODIFY/CHANGE` 会转换列类型；`CHANGE`/`MODIFY` 转到非 MySQL 时告警：需手工改写成 `ALTER COLUMN`

## 八、函数改写

在 clone 后的 AST 上改节点，不把模板字符串再 parse 一遍。`FunctionAstRewriter` **只遍历**；规则来自 `SqlFunctionRegistry`：先登记内置（`BuiltinFunctionRewriter`、`DateFormatRewriteRule`），再 `ServiceLoader` 调 `SqlSchemaConverterProvider.registerFunctions`，后注册覆盖先注册。SPI 返回 `null` 则回落内置。

| 源 | 行为 |
|---|---|
| `IF(a,b,c)` | 非 MySQL → `CASE WHEN a THEN b ELSE c END` |
| `NOW` / `CURDATE` / `CURTIME` | `CURRENT_TIMESTAMP` / `DATE` / `TIME` |
| `IFNULL` / `NVL` / `ISNULL`（二元） | MySQL/H2/SQLite `IFNULL`；Oracle `NVL`；SQL Server `ISNULL`；其余 `COALESCE` |
| `GROUP_CONCAT` / `STRING_AGG` / `LISTAGG` | MySQL/H2 `GROUP_CONCAT`；PG `STRING_AGG`；Oracle `LISTAGG … WITHIN GROUP` |
| `CONCAT` 多于两参数 | Oracle 改为 `\|\|` |
| `CAST` / `CONVERT(expr, type)` | 类型走注册表 |
| `CONVERT(expr USING charset)` | **不**改写成 CAST，只 `SEMANTIC_RISK` 并保留原文 |
| `LOCATE` / `INSTR` / `CHARINDEX` | PG `POSITION`；Oracle `INSTR`（参数对调）；SQL Server `CHARINDEX` |
| `LENGTH` / `LEN` | SQL Server `LEN`，其余 `LENGTH` |
| `SUBSTRING` / `SUBSTR` | Oracle `SUBSTR` |
| `DATE_ADD` / `DATE_SUB` | 非 MySQL → 加减 `INTERVAL`（PG 写成 `INTERVAL '1 day'`） |
| `DATEDIFF` | PG/Oracle 改为日期相减 |
| `FROM_UNIXTIME` | PG `TO_TIMESTAMP` |
| `DECODE` / `NVL2` | 非 Oracle → `CASE` |
| `FIND_IN_SET` / `SUBSTRING_INDEX` | 无干净等价：告警并保留 |

## 九、如何扩展

先判断改哪一层，再动手。**不要**再加 pairwise 映射表。

| 你想做的事 | 改哪里 | 能否只靠 SPI |
|---|---|---|
| 某方言多一个类型别名（如 `INT4`→`INT`） | `registerAlias` | 能 |
| 覆盖某方言已有写法（如把 PG 的 `JSON` 改成 `JSON` 而不是 `JSONB`） | `register` + 必要时 `LossyMapping` | 能 |
| 两个 canonical 在目标方言写成同一个字面量 | 必须 `registerLossyMapping` | 能 |
| 新增一种**语义类型**（如 UUID、INTERVAL） | `CanonicalType` 枚举 + **全部 12 个方言**的写法 | 不能，要改本模块 |
| 新增一种**数据库** | 实现 {@code SqlDialectSpec}（或包 {@code SqlDialectWrapper}），不必改 {@code SqlDialect} 枚举 | 类型复用 {@code typeFamily()}；个别写法按 {@code dialectId()} SPI 覆盖 |
| 新增/改函数转换（`DATE_FORMAT`、`IF`、自有函数…） | `SqlSchemaConverterProvider.registerFunctions` | **能**（同名覆盖内置；返回 `null` 回落内置） |

插件不能发明新的 `CanonicalType`。`UNKNOWN` 表示「识别不了，保留原文」。

---

### 9.1 SPI：别名、覆盖写法、有损映射

实现 `com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider`，在 **自己的 jar** 里放：

```
META-INF/services/com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider
```

文件内容是实现类全名，一行一个。`ServiceLoader` 会被加载两次：类型表 `freeze()` **之前**调 `registerTypes`；函数表在内置规则 `snapshotBuiltins()` 之后、`freeze()` 之前调 `registerFunctions`。两次 load 是不同实例，不要靠字段在两个方法之间传状态。`priority()` 越大越晚，**后注册覆盖先注册**（内置为 0，默认插件 100）。

```java
public final class MyPgJsonProvider implements SqlSchemaConverterProvider {
    @Override
    public int priority() {
        return 200;
    }

    @Override
    public void registerTypes(SqlDataTypeRegistry registry) {
        // 1) 别名：只影响反向查找（源 SQL 写成 INT4 时当成 INT）
        registry.registerAlias(SqlDialect.POSTGRES, "INT4", CanonicalType.INT);
        registry.registerAlias(SqlDialect.POSTGRES, "INT8", CanonicalType.BIGINT);

        // 2) 覆盖正向写法：PG 的 JSON 输出 JSON 而不是内置的 JSONB
        registry.register(CanonicalType.JSON, SqlDialect.POSTGRES,
                DialectTypeForm.of("JSON"));

        // 3) 若覆盖后与另一 canonical 撞字面量，必须声明有损
        // registry.registerLossyMapping(new LossyMapping(
        //         SqlDialect.POSTGRES, "JSON", CanonicalType.JSON,
        //         EnumSet.of(CanonicalType.JSON, CanonicalType.TEXT)));
    }
}
```

`DialectTypeForm.of(pattern)` 规则：

- `"INTEGER"`、`"NUMBER(10)"`、`"TINYINT(1)"`：无 `%d`，**完整字面量**，转换时忽略列上的 precision。
- `"VARCHAR(%d)"`：一档精度；源列无长度则输出裸 `VARCHAR`。
- `"DECIMAL(%d,%d)"` / `"NUMBER(%d,%d)"`：精度+标度；都缺则输出裸类型名。
- 不要写 `"CHAR(%d) FOR BIT DATA"` 这种「括号后再跟关键字」还指望基名 `CHAR` 做反向查找——基名登记只接受「类型名 + 括号占位」；DB2 二进制靠 `FOR BIT DATA` 特例。

覆盖写法后务必跑：

```text
mvn -pl jkit-sql -Dtest=RegistryValidationTest,SqlDataTypeRegistryTest test
```

未声明的 reverse 碰撞会在 **类加载内置表时** 直接 `IllegalStateException`，插件把内置表搞坏时应用起不来。

**仓库内置扩展示例**（对照 icell `FieldConstruct` / `FieldTypeConverter` 缺的源类型名）：

`com.alianga.jkit.sql.schema.spi.CommonModelTypeAliasesProvider`

登记：`BPCHAR`→CHAR、`FLOAT4`/`FLOAT8`→FLOAT/DOUBLE、`LONG`→BIGINT、`BIT`→BOOLEAN、`INT8`→BIGINT（**不含 ClickHouse**，因其 `Int8` 是 TINYINT）。主 jar 的 `META-INF/services/` 已挂上，复制该类即可做第三方插件模板。

测试源码另有 `TestAliasProvider`（`MIDINT`→MEDIUMINT）。新方言插件可继承测试源码的 `SqlSchemaConverterProviderTestKit`（JUnit 在 test 依赖里，不进主 jar）。

---

### 9.2 新增一种 canonical 类型（改本模块）

例如要支持 `UUID`：

1. **`CanonicalType` 增加枚举常量**（并设好 `requiresPrecision` / `requiresScale`）。插件做不到这一步。
2. **`SqlDataTypeRegistryBuiltins.registerTypes`**：为 **每一个** `SqlDialect` 登记写法。缺任何一个，`RegistryValidator` 失败。
3. 若某方言没有独立类型、只能落到已有类型上（PG `UUID` vs MySQL `CHAR(36)`），登记完整字面量或模板，并加 `LossyMapping`（例如 MySQL `CHAR(36)` 不能无声明地同时当 `CHAR` 和 `UUID`——通常 UUID 用精确 `CHAR(36)`，普通 `CHAR(n)` 仍走模板）。
4. **别名**：`registerAlias` / `registerAliasAll`（`UNIQUEIDENTIFIER`→`UUID` 等）。
5. 解析：若类型名是两个词或带特殊括号，改 `SqlColumnDefinitionParser` 的 `readType()`（现有已处理 `DOUBLE PRECISION`、`TIMESTAMP WITH TIME ZONE`）。
6. 单测：
   - `fromDialect("UUID", POSTGRES) == UUID`
   - `convert("CHAR(36)", MYSQL, POSTGRES)` 若你把 MySQL 侧写成 `CHAR(36)` 精确映射
   - `RegistryValidationTest` 必须绿
   - 在 `SqlSchemaConvertCorpusTest` 加一条黄金语料

不要把新语义塞进 `UNKNOWN` 或滥用已有 `CHAR`/`TEXT` 而不登记——那样反向查找和 roundtrip 会对不上。

---

### 9.3 新增一种数据库（实现 `SqlDialectSpec`，不必改枚举）

`SqlDialectSpec` 就是外部扩展点。解析、分页、format、`SQL.convert` 全链路吃规约，**不要为新产品去改 `SqlDialect` 枚举**（除非它会成为全仓库一等公民，并愿意维护 12×canonical 全表）。

达梦 / openGauss / GBase 若只是「和某内置方言同一套类型」，用 `fromName` 别名或下面的 `typeFamily()` 即可。

```java
// 1) 接近 PostgreSQL：包装后只改能力，类型表自动复用 POSTGRES
SqlDialectSpec gaussLite = new SqlDialectWrapper(SqlDialect.POSTGRES) {
    @Override
    public String dialectId() { return "gauss-lite"; } // 若要单独登记类型才改 id
};

// 2) 全新实现：引号/分页自己定，类型族复用 MySQL
SqlDialectSpec myDb = new SqlDialectSpec() {
    @Override public String dialectId() { return "mydb"; }
    @Override public SqlDialect typeFamily() { return SqlDialect.MYSQL; }
    @Override public char identQuoteOpen() { return '`'; }
};

SQL.parse(sql, myDb);
SQL.convert(mysqlDdl, SqlDialect.MYSQL, myDb);
```

| 方法 | 作用 |
|---|---|
| `dialectId()` | SPI / 类型表主键。默认等于 `typeFamily().name()` |
| `typeFamily()` | 自增、函数改写、未单独登记的类型，复用哪个内置方言。默认 `ANSI` |

类型写法和内置不一致时，SPI 按 **id** 覆盖（不必填满全部 canonical，缺的回落 `typeFamily()`）：

```java
public void registerTypes(SqlDataTypeRegistry registry) {
    registry.register(CanonicalType.INT, "mydb", DialectTypeForm.of("INT32"));
    registry.register(CanonicalType.VARCHAR, "mydb", DialectTypeForm.of("TEXT(%d)"));
}
```

只有这些情况才考虑改枚举：要进 `SqlDialect.values()` 的 CI 全表校验、或成为 `fromName` 的默认一等方言。

---

### 9.4 新增 / 覆盖函数转换（`registerFunctions`，不要改 `FunctionAstRewriter`）

函数改写的扩展点就是 `SqlSchemaConverterProvider.registerFunctions`。`FunctionAstRewriter` 只遍历 AST 并 `SqlFunctionRegistry.find(name)`；内置规则已经挂在注册表里（`BuiltinFunctionRewriter`、`DateFormatRewriteRule`），SPI 后注册同名即可覆盖。

原则：

- **构造 AST**（`SqlCaseExpr`、`SqlFunctionExpr.setName`、`SqlBinaryExpr`），不要 `String` 拼 SQL 再 `parse`。
- 改名即可的，只 `fn.setName(SqlIdentifier.of("..."))`。
- 参数顺序不同（`LOCATE(sub, str)` vs `INSTR(str, sub)`）要交换 `arguments()`。
- 语义对不上的（`CONVERT … USING`）**保留原节点 + `SEMANTIC_RISK`**，不要假装等价。
- 目标方言就是该函数的原生写法时直接 `return fn`。
- 只想拦一部分方言、其余仍走内置：`return null`，walker 会 `findBuiltin`。
- 想包装内置：在 `register` 之前 `final FunctionRewriteRule current = registry.find("IF")`，再包一层。
- `CAST` 是 `SqlCastExpr` 节点，由 walker 直接改类型，不走函数表；`CONVERT(expr, type)` 的类型名走 `SqlDataTypeRegistry.convert`。

本仓库测试 SPI `TestAliasProvider` 已示范：`JKIT_SPI_FN` → `SPI_OK`；`DATE_FORMAT` 返回 `null` 仍落到内置 `TO_CHAR`。

```java
public final class MyFnProvider implements SqlSchemaConverterProvider {
    @Override
    public int priority() {
        return 200;
    }

    @Override
    public void registerFunctions(SqlFunctionRegistry registry) {
        // 1) 新函数
        registry.register("JKIT_SPI_FN", new FunctionRewriteRule() {
            @Override
            public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source,
                                   SqlDialectSpec target, ConversionReport.Builder report) {
                fn.setName(SqlIdentifier.of("SPI_OK"));
                return fn;
            }
        });

        // 2) 覆盖内置 DATE_FORMAT：只对自有方言动手，其余 return null 回落
        registry.register("DATE_FORMAT", new FunctionRewriteRule() {
            @Override
            public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source,
                                   SqlDialectSpec target, ConversionReport.Builder report) {
                if ("mydb".equals(target.dialectId())) {
                    fn.setName(SqlIdentifier.of("FORMAT_DATE"));
                    return fn;
                }
                return null;
            }
        });
    }
}
```

单测：源 SQL → `SQL.convert` → 断言目标文本含/不含关键字，并 `SQL.parse(out, targetDialect)`。模块内已有 `SqlSchemaConverterProviderTest.registerFunctionsSpiRewritesUnknownFunction`。

---

### 9.5 新增列约束或自增形态

- 新的列修饰符（如 `INVISIBLE`）：给 `ColumnConstraint.Kind` 加枚举值，在 `SqlColumnDefinitionParser.readConstraint` 里消费，在 `ColumnDefinitionConverter.render` 的 `switch (c.kind())` 里**显式处理或显式丢弃+告警**。漏掉 `Kind` 会在 converter 里落到 `default`，等于静默丢失（这是早期 `AUTO_INCREMENT` 被丢掉的原因）。
- 新方言的自增：只改 `AutoIncrementStrategy.apply` 的 `switch`，不要在 converter 里散落字符串。

---

### 9.6 扩展时的测试清单

| 改动 | 最少要绿的测试 |
|---|---|
| 别名 / 覆盖类型 | `RegistryValidationTest` + 一条 `fromDialect`/`convert` 断言 |
| 新 canonical | 上一项 + `roundtripEveryCanonicalOnEveryDialect` |
| 新函数 | `SqlSchemaConverterProviderTest` + `SqlSchemaConverterTest` 含/不含关键字 + 目标方言 `parse` |
| 新方言 | 类型 roundtrip + 一条 CREATE TABLE 语料 + 自增策略用例 |
| 真库 | `tools-test` 的 `LocalDatasourceConvertTest` 或容器测试 |

```text
mvn -pl jkit-sql test
```

---

### 9.7 扩展点一览（声明的方法都要能用）

`jkit-sql` 里带「SPI」语义的接口，注册方式不一样，不要混：

| 接口 / 方法 | 怎么挂上 | 是否真正进管线 |
|---|---|---|
| `SqlSchemaConverterProvider.registerTypes` | `META-INF/services/…SqlSchemaConverterProvider` | 是，类型表 freeze 前 |
| `SqlSchemaConverterProvider.registerFunctions` | 同上（另一次 `ServiceLoader.load`） | 是，函数表 snapshot 之后、freeze 前 |
| `SqlSchemaConverterProvider.priority` | 同上 | 是，升序，后覆盖先 |
| `FunctionRewriteRule.rewrite` | `registry.register(name, rule)` | 是；返回 `null` 回落内置 |
| `SqlDialectSpec` 原语能力（引号 / `pipesAsOr` / 反斜杠 / `[]` / `~` / `#` / 分页开关 / `dialectId` / `typeFamily`） | 实现接口或 `SqlDialectWrapper` 覆写，传给 `SQL.parse` / `convert` / `setPage` | 是 |
| `SqlDialectSpec.quoteIdent` / `pipesAreConcat` / `preferredLimitStyle` / `identQuoteClose` | **派生查询**：不要只覆写它们指望改写跟着变。`SqlDialectWrapper` 不再委托这四个，子类只改原语时派生会跟上 | `preferredLimitStyle` **不**驱动 `setPage`（分页读 `supports*`） |
| `SqlWallRule.check` | `SqlWallConfig.rules(...)`（**不是** ServiceLoader） | 是，全部内置检查之后按注册顺序 |
| `SqlStatementParser.parse` | `SqlParseOptions.statementParsers()` 按前导关键字（**不是** ServiceLoader） | 是，仅兜内建未覆盖的关键字 |
| `SqlParseContext` 全部方法（`token` / `dialect` / `is` / `isIdent` / `match` / `matchIdent` / `next` / `name` / `atStmtBreak` / `consumeRest` / `error`） | 自定义 `SqlStatementParser` 入参 | 是，透传到 `SqlParser` |
| `SqlRewriteHook.apply` | `SqlRewrites.add` + `SQL.rewrite`（**不是** ServiceLoader） | 是；返回 `null` 抛错 |

插件不能发明新的 `CanonicalType`。函数名大小写不敏感。内置函数清单见第八节。

## 十、测试

模块内（`jkit-sql`，JUnit 4）：

- `SqlColumnDefinitionParserTest`
- `SqlDataTypeRegistryTest`（含全部 canonical × 方言 roundtrip）
- `RegistryValidationTest`
- `SqlSchemaConverterTest`、`SqlSchemaConvertCorpusTest`
- `SqlSchemaConverterProviderTest`、`SqlFunctionRegistryTest`

`tools-test`（不进 `jkit-sql` 依赖）：

- `CrossDialectDdlExecutionTest`：转换后的 DDL 在本地 `postgres:16-alpine` 真建表（无 Docker/无镜像则 skip）
- `LocalDatasourceConvertTest`：读 `src/test/resources/datasource`，在本机 MySQL / PostgreSQL / Oracle 11g 建表（文件不入库；连不上 skip）
- JMH：`com.alianga.test.sql.jmh.SqlSchemaConvertBenchmark`

## 十一、实体扫描生成 DDL / DML

对标 data-set `com.dtsz.subject.uitls.scan.EntityScanner`，但不引入 Spring。`SqlEntityScanner` 扫 classpath（`file:` / `jar:`）下带 `@SqlTable` 或 JPA `@Entity` 的具体类。

`SqlEntities` 结合类型表与 `SqlBuilder`：

| 方法 | 产出 |
|---|---|
| `scan(package)` | 实体 `Class` 列表 |
| `inspect(Class)` | `SqlEntityModel`（表名、列、主键、自增） |
| `createTable` / `createTables` | 目标方言 `CREATE TABLE` |
| `dropTable` | `DROP TABLE` |
| `insert` / `insertPlaceholders` | `INSERT` |
| `updateById` / `deleteById` / `selectById` / `selectAll` | 按主键 DML |

Java → canonical：`String`→VARCHAR、`int/Integer`→INT、`long`→BIGINT、`boolean`→BOOLEAN、`BigDecimal`→DECIMAL、时间类型→DATE/TIME/DATETIME、`byte[]`→BLOB。列名默认驼峰转下划线；`XxxEntity` 去后缀当表名。

## 十二、明确不做

- 存储过程 / 触发器 / 视图的跨方言转换
- 默认自动生成 Oracle ≤11g 的 SEQUENCE+TRIGGER（`generateOracleSequence(true)` 为 **opt-in**，会附录 SEQUENCE+TRIGGER）
- 字符集/排序规则转换后排序结果一致（只做保留/删除/警告）
- 跨库数据搬迁
- 把 MySQL `MODIFY`/`CHANGE` 自动改写成 PostgreSQL `ALTER COLUMN` 全套语法（类型 + SET/DROP NOT NULL/DEFAULT 附录；不做 USING 表达式推断）
