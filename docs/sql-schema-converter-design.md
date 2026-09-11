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

## 八、如何扩展

先判断改哪一层，再动手。**不要**再加 pairwise 映射表。

| 你想做的事 | 改哪里 | 能否只靠 SPI |
|---|---|---|
| 某方言多一个类型别名（如 `INT4`→`INT`） | `registerAlias` | 能 |
| 覆盖某方言已有写法（如把 PG 的 `JSON` 改成 `JSON` 而不是 `JSONB`） | `register` + 必要时 `LossyMapping` | 能 |
| 两个 canonical 在目标方言写成同一个字面量 | 必须 `registerLossyMapping` | 能 |
| 新增一种**语义类型**（如 UUID、INTERVAL） | `CanonicalType` 枚举 + **全部 12 个方言**的写法 | 不能，要改本模块 |
| 新增一种**数据库**（如在枚举里加一条） | `SqlDialect` + 类型表 + 自增 + 解析/分页能力 | 不能，要改本模块 |
| 新增/改函数转换（`DATE_FORMAT`、`IF`…） | `FunctionAstRewriter` | **不能**（SPI 目前只接类型表） |

插件不能发明新的 `CanonicalType`。`UNKNOWN` 表示「识别不了，保留原文」。

---

### 8.1 SPI：别名、覆盖写法、有损映射

实现 `com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider`，在 **自己的 jar** 里放：

```
META-INF/services/com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider
```

文件内容是实现类全名，一行一个。`ServiceLoader` 在内置表 `freeze()` **之前**调用 `registerTypes`。`priority()` 越大越晚，**后注册覆盖先注册**（内置为 0，默认插件 100）。

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

测试源码里的例子：`TestAliasProvider` 给 MySQL 登记 `MIDINT`→`MEDIUMINT`。新方言插件可继承测试源码的 `SqlSchemaConverterProviderTestKit`（JUnit 在 test 依赖里，不进主 jar）。

---

### 8.2 新增一种 canonical 类型（改本模块）

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

### 8.3 新增一种数据库（改本模块）

「GBase 当 MySQL」这种**别名**已经在 `SqlDialect.fromName` 里（`gbase`→`MYSQL`，`dm`→`ORACLE`，`gaussdb`→`POSTGRES`）。只有分页/引号/类型三者都对不齐时才值得加新枚举。

清单（漏一项就会在解析、转换或 format 上 silently 错）：

1. **`SqlDialect` 新常量**，并实现/沿用 `SqlDialectSpec`（引号、`||` 语义、LIMIT/TOP/FETCH/ROWNUM）。
2. **`SqlDialect.fromName`** 加上常见别名。
3. **`SqlDataTypeRegistryBuiltins`**：每个 `CanonicalType` 一行该方言写法（改 `put(...)` 的参数列表或单独 `register`）。
4. 多对一写法 → `registerLossy`。
5. **`AutoIncrementStrategy`**：`switch (target)` 增加分支；没有自增就告警 `MANUAL_ACTION_REQUIRED`，禁止静默丢掉。
6. **`DefaultValueCoercer.nativeBoolean`**：该方言布尔是 `true/false` 还是 `0/1`。
7. **列解析**：自增关键字（`IDENTITY` / `AUTO_INCREMENT` / `SERIAL`）是否要在 `SqlColumnDefinitionParser` 里认。
8. **函数**：`FunctionAstRewriter` 里目标方言分支（见 8.4）。
9. **表选项**：`supportsMysqlTableOptions` / 列 charset 是否保留。
10. 测试：该方言 × 全部 canonical 的 roundtrip；至少一条 `CREATE TABLE` 黄金语料；能连真库的话接到 `LocalDatasourceConvertTest` 一类测试。

达梦走 `ORACLE`、openGauss 走 `POSTGRES`、GBase8a 走 `MYSQL`：优先加 `fromName` 别名，不要复制一整套类型表。

---

### 8.4 新增函数转换（改 `FunctionAstRewriter`）

函数 **没有** SPI。`FunctionRewriteRule` 接口还在，但运行路径是 `FunctionAstRewriter.rewriteFunction` 里的硬编码。新增规则就改这个方法：参数已经递归改写过，这里只负责换节点。

原则：

- **构造 AST**（`SqlCaseExpr`、`SqlFunctionExpr.setName`、`SqlBinaryExpr`），不要 `String` 拼 SQL 再 `parse`。
- 改名即可的，只 `fn.setName(SqlIdentifier.of("..."))`。
- 参数顺序不同（`LOCATE(sub, str)` vs `INSTR(str, sub)`）要交换 `arguments()`。
- 语义对不上的（`CONVERT … USING`）**保留原节点 + `SEMANTIC_RISK`**，不要假装等价。
- 目标方言就是该函数的原生写法时直接 `return fn`。

示例：把 MySQL `DATE_FORMAT(ts, '%Y-%m-%d')` 转到 PG `TO_CHAR`（格式串仍可能有损，要告警）：

```java
if ("DATE_FORMAT".equals(name) && args.size() >= 2) {
    if (target == SqlDialect.MYSQL) {
        return fn;
    }
    if (target == SqlDialect.POSTGRES || target == SqlDialect.ORACLE
            || target == SqlDialect.ORACLE12) {
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                "DATE_FORMAT 格式符与 TO_CHAR 不完全等价");
        fn.setName(SqlIdentifier.of("TO_CHAR"));
        return fn;
    }
    report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
            target + " 无 DATE_FORMAT/TO_CHAR 映射，已保留原文");
    return fn;
}
```

`CAST` / `CONVERT(expr, type)` 的**类型名**不要手写对照表，走 `SqlDataTypeRegistry.convert(typeText, source, target)`。

单测写在 `SqlSchemaConverterTest`：源 SQL → `SQL.convert` → 断言目标文本含/不含关键字，并 `SQL.parse(out, targetDialect)` 保证能解析。

---

### 8.5 新增列约束或自增形态

- 新的列修饰符（如 `INVISIBLE`）：给 `ColumnConstraint.Kind` 加枚举值，在 `SqlColumnDefinitionParser.readConstraint` 里消费，在 `ColumnDefinitionConverter.render` 的 `switch (c.kind())` 里**显式处理或显式丢弃+告警**。漏掉 `Kind` 会在 converter 里落到 `default`，等于静默丢失（这是早期 `AUTO_INCREMENT` 被丢掉的原因）。
- 新方言的自增：只改 `AutoIncrementStrategy.apply` 的 `switch`，不要在 converter 里散落字符串。

---

### 8.6 扩展时的测试清单

| 改动 | 最少要绿的测试 |
|---|---|
| 别名 / 覆盖类型 | `RegistryValidationTest` + 一条 `fromDialect`/`convert` 断言 |
| 新 canonical | 上一项 + `roundtripEveryCanonicalOnEveryDialect` |
| 新函数 | `SqlSchemaConverterTest` 含/不含关键字 + 目标方言 `parse` |
| 新方言 | 类型 roundtrip + 一条 CREATE TABLE 语料 + 自增策略用例 |
| 真库 | `tools-test` 的 `LocalDatasourceConvertTest` 或容器测试 |

```text
mvn -pl jkit-sql test
```

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
