# 表结构跨方言转换

`jkit-sql` 的 `com.alianga.jkit.sql.schema` 包：把 `CREATE TABLE` / 部分 `ALTER` 和查询语句从一种 `SqlDialect` 转到另一种。JDK 8，零新增运行时依赖。入口是 `SQL.convert`。

本文是**当前实现**的说明（由 v2 Normal Form 思路 + v3 结构化列/有损映射整改合并而来）。未实现的旧稿（`record`/`sealed`、字符串模板函数表、`SqlFunctionRegistry`、pairwise 映射）一律不收录。

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
├── rewrite/        AutoIncrementStrategy, DefaultValueCoercer, FunctionAstRewriter
├── convert/        SqlSchemaConverter, Options, Report, Result
└── spi/            SqlSchemaConverterProvider
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

### 报告 `ConversionReport`

警告三级：`INFO`、`SEMANTIC_RISK`、`MANUAL_ACTION_REQUIRED`。另计已转换列数 / 原样保留数。

## 四、列模型与解析

`ColumnDefinition`：列名、`SqlDataType`、约束列表、原文、`tableConstraint` 标记。

`SqlDataType`：原始类型名、precision/scale、`TypeAttribute`（`UNSIGNED`、`ZEROFILL`、`BINARY_CHARSET`、`NATIONAL`、`WITH_TIME_ZONE`、`WITHOUT_TIME_ZONE`）。

`ColumnConstraint.Kind`：`NOT_NULL`、`NULLABLE`、`DEFAULT_VALUE`、`AUTO_INCREMENT`、`INLINE_PRIMARY_KEY`、`INLINE_UNIQUE`、`COMMENT`、`ON_UPDATE`、`CHARACTER_SET`、`COLLATION`。Java 8 无 sealed，用枚举做穷尽分支。

`SqlColumnDefinitionParser` 用现有 `SqlLexer` 切词，识别多词类型（`DOUBLE PRECISION`、`CHARACTER VARYING`、`TIMESTAMP WITH TIME ZONE`）、`SERIAL`→整数+自增、表级 `PRIMARY KEY` / `FOREIGN KEY` / `KEY` 等。解析失败不抛，返回 `UNKNOWN` + 原文。

## 五、类型注册表

`CanonicalType`（另加 `UNKNOWN`）：

`TINYINT` `SMALLINT` `MEDIUMINT` `INT` `BIGINT` `FLOAT` `DOUBLE` `DECIMAL` `CHAR` `VARCHAR` `TEXT` `DATE` `DATETIME` `TIMESTAMP` `TIME` `BINARY` `BLOB` `BOOLEAN` `JSON` `YEAR`

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

## 六、DDL 转换语义

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

## 七、函数改写

在 clone 后的 AST 上改节点，不把模板字符串再 parse 一遍。由 `FunctionAstRewriter` 实现：

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

## 八、SPI

`META-INF/services/com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider`。`registerTypes` 在内置表 freeze 之前调用；`priority()` 越大越晚、可覆盖。不能发明新的 `CanonicalType`。

TestKit 在**测试源码**（JUnit 仅为 test 依赖）：`SqlSchemaConverterProviderTestKit`，SQL Server 做 dogfooding。

## 九、测试

模块内（`jkit-sql`，JUnit 4）：

- `SqlColumnDefinitionParserTest`
- `SqlDataTypeRegistryTest`（含全部 canonical × 方言 roundtrip）
- `RegistryValidationTest`
- `SqlSchemaConverterTest`、`SqlSchemaConvertCorpusTest`
- `SqlSchemaConverterProviderTest`

`tools-test`（不进 `jkit-sql` 依赖）：

- `CrossDialectDdlExecutionTest`：转换后的 DDL 在本地 `postgres:16-alpine` 真建表（无 Docker/无镜像则 skip）
- `LocalDatasourceConvertTest`：读 `src/test/resources/datasource`，在本机 MySQL / PostgreSQL / Oracle 11g 建表（文件不入库；连不上 skip）
- JMH：`com.alianga.test.sql.jmh.SqlSchemaConvertBenchmark`

## 十、明确不做

- 存储过程 / 触发器 / 视图的跨方言转换
- 自动生成 Oracle ≤11g 的 SEQUENCE+TRIGGER
- 字符集/排序规则转换后排序结果一致（只做保留/删除/警告）
- 跨库数据搬迁
- 把 MySQL `MODIFY`/`CHANGE` 自动改写成 PostgreSQL `ALTER COLUMN` 全套语法（只转类型并告警）
