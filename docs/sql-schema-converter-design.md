# 表结构跨方言转换模块设计

## 一、问题陈述

现有 `jkit-sql` 模块已支持 N 种方言的解析、格式化与改写，但**表结构（DDL）和 SQL 语句在不同方言之间转换**的能力缺失。典型场景：

- 从 MySQL 迁移到 PostgreSQL，批量生成目标方言的 DDL 与 DML
- 同一份 SQL 模板需要在多个数据库上运行，按目标方言适配类型、函数、分页
- 多租户 SaaS 按租户所在数据库动态改写 SQL

### 当前方案的问题

最朴素的想法是维护一个**双向 pairwise 映射表**（MySQL↔PG、MySQL↔Oracle、PG↔Oracle …），但这是 O(N²) 的维护成本：

| 方言数 | 需要维护的映射条目（pairwise） |
|--------|-------------------------------|
| 3 | 6 |
| 5 | 20 |
| 10 | 90 |
| 新增 1 个方言 | 额外 2(N-1) 条 |

每次新增方言都要在两方各自维护映射，**双向等价性无法自动保证**，容易遗漏反向映射。

---

## 二、总体架构

### 2.1 核心设计：Normal Form 中转

```
源方言 SQL
    │
    │  parse(sql, sourceDialect)
    ▼
 AST（源方言结构）
    │
    │  Visitor 遍历时按各方言专属规则归一
    ▼
  Normal Form AST
    │
    │  Visitor 遍历时按目标方言规则重编码
    ▼
 AST（目标方言结构）
    │
    │  format(stmt, targetDialect)
    ▼
 目标方言 SQL
```

**关键思路**：每个方言只声明**"我是什么"**，转换经由公共中间态（Normal Form）中转。双向转换天然成立，无需显式定义反向映射。

```
MySQL ──→ NormalForm ──→ PostgreSQL
                  ▲
                  │
            Oracle ──→ NormalForm
```

新增第 N 个方言只需在 Normal Form 下声明自己的形态（K 条，K 为 canonical 类型数），现有所有映射零改动，双向能力自动成立。

### 2.2 为什么选 Normal Form 而非其他方案

| 方案 | 维护成本 | 双向自动成立 | 运行时复杂度 | 评价 |
|---|---|---|---|---|
| Pairwise 映射表 | O(N² × K) | ✗ 需手工保证 | O(1) 查表 | 不可扩展 |
| **Normal Form 中转** | **O(N × K)** | **✓ 自动** | **O(1) 两次查表** | **✅ 选择** |
| 全量 AST 重写引擎 | 高（每个方言一套完整规则） | ✓ | O(n) 树遍历 | 过度设计 |
| 字符串正则替换 | 极低但脆弱 | ✗ | O(n) | 不可维护 |

**选择 Normal Form 的理由**：
1. **开闭原则**：新增方言只需注册，无需修改任何现有类
2. **双向内建**：`A→B` 与 `B→A` 由同一张表对称保证，不会出现"A 能转 B 但 B 不能转 A"的不对称
3. **复用现有 Visitor**：`SqlAstVisitor` 已覆盖全部 AST 节点，改写器只需覆写感兴趣的 `visitXxx` 方法
4. **复用现有 Format**：格式化器已按目标方言处理引号、关键字大小写等，转换后直接复用
5. **零新增依赖**：全部用现有 `SqlDialect`、`SqlRewriter`、`SqlFormatter`，不引入第三方库

---

## 三、类型转换层设计

### 3.1 数据结构：以 Canonical 类型为轴

```java
// jkit-sql/src/main/java/com/alianga/jkit/sql/SqlDataTypeRegistry.java

/**
 * 类型映射注册表：以 Canonical 类型为轴，按方言声明各自的写法。
 *
 * <p>以 canonical 类型为键，value 是各方言的写法声明。
 * 新增方言只需在已有 canonical 类型下追加一行声明，现有所有映射零改动。</p>
 *
 * <pre>{@code
 * registry.register("INTEGER", Map.of(
 *     MYSQL,   "INT",
 *     POSTGRES, "INTEGER",
 *     ORACLE,  "NUMBER"
 * ));
 * }</pre>
 */
public final class SqlDataTypeRegistry {

    /** canonical 类型 → 各方言写法。key 为归一化的 canonical 名（大写字母+数字）。*/
    private final Map<String, DialectTypeDeclaration> canonicalMap = new LinkedHashMap<>();

    /**
     * 注册一个 canonical 类型在各方言的写法。
     *
     * @param canonical  canonical 名，如 "INTEGER"、"VARCHAR"、"DATETIME"
     * @param forms      各方言写法，key 为 {@link SqlDialect}，value 为写法模板或字面量
     *                   - 字面量：直接使用该字符串（如 "INT"）
     *                   - 模板：含 {@code %d} 表示精度，如 "VARCHAR(%d)"、"DECIMAL(%d,%d)"
     */
    public void register(String canonical, Map<SqlDialect, String> forms) { ... }

    /**
     * 查询：某 canonical 类型在指定方言的写法（无精度参数）。
     */
    public String toDialect(String canonical, SqlDialect dialect) { ... }

    /**
     * 带精度参数的查询（用于 VARCHAR(n)、DECIMAL(p,s) 等）。
     *
     * @param canonical   canonical 名
     * @param dialect     目标方言
     * @param precision   精度，null 表示无精度
     * @param scale       标度，null 表示无标度
     * @return 写法字符串，如 "VARCHAR(100)"、"DECIMAL(10,2)"
     */
    public String toDialect(String canonical, SqlDialect dialect,
                            Integer precision, Integer scale) { ... }

    /**
     * 反向：从方言写法解析回 canonical。
     * 基于正则匹配方言专属写法，失败时返回原始字符串（保守策略，不做危险猜测）。
     */
    public String fromDialect(String dialectForm, SqlDialect dialect) { ... }

    /**
     * 直接转换：方言 A 写法 → 方言 B 写法（两步：A→canonical→B）。
     *
     * @param sourceForm 源方言写法，如 MySQL 的 "INT"
     * @param from       源方言
     * @param to         目标方言
     * @return 目标方言写法，如 Postgres 的 "INTEGER"
     */
    public String convert(String sourceForm, SqlDialect from, SqlDialect to) { ... }
}
```

### 3.2 内置注册：当前 12 种方言的 Canonical 类型声明

```java
// 初始化时注册以下 canonical 类型（每种类型声明所有方言的写法）

// 整数族
register("TINYINT",  Map.of(MYSQL, "TINYINT",   POSTGRES, "SMALLINT",  ORACLE, "NUMBER(3)",  SQLSERVER, "TINYINT"));
register("SMALLINT", Map.of(MYSQL, "SMALLINT",  POSTGRES, "SMALLINT",  ORACLE, "NUMBER(5)",  SQLSERVER, "SMALLINT"));
register("MEDIUMINT",Map.of(MYSQL, "MEDIUMINT", POSTGRES, "INTEGER",   ORACLE, "NUMBER(8)",  SQLSERVER, "INTEGER")); // PG/Oracle 无 MEDIUMINT，归一到 INTEGER
register("INT",      Map.of(MYSQL, "INT",       POSTGRES, "INTEGER",   ORACLE, "NUMBER(10)", SQLSERVER, "INT"));
register("BIGINT",   Map.of(MYSQL, "BIGINT",    POSTGRES, "BIGINT",    ORACLE, "NUMBER(20)", SQLSERVER, "BIGINT"));

// 浮点族
register("FLOAT",    Map.of(MYSQL, "FLOAT",     POSTGRES, "FLOAT",     ORACLE, "BINARY_FLOAT", SQLSERVER, "REAL"));
register("DOUBLE",   Map.of(MYSQL, "DOUBLE",    POSTGRES, "DOUBLE PRECISION", ORACLE, "BINARY_DOUBLE", SQLSERVER, "FLOAT"));
register("DECIMAL",  Map.of(MYSQL, "DECIMAL",   POSTGRES, "NUMERIC",   ORACLE, "NUMBER",         SQLSERVER, "DECIMAL"));

// 字符族
register("CHAR",     Map.of(MYSQL, "CHAR",      POSTGRES, "CHAR",      ORACLE, "CHAR",             SQLSERVER, "CHAR"));
register("VARCHAR",  Map.of(MYSQL, "VARCHAR",   POSTGRES, "VARCHAR",   ORACLE, "VARCHAR2",         SQLSERVER, "VARCHAR"));
register("TEXT",     Map.of(MYSQL, "TEXT",      POSTGRES, "TEXT",      ORACLE, "CLOB",             SQLSERVER, "TEXT"));

// 日期时间族
register("DATE",     Map.of(MYSQL, "DATE",      POSTGRES, "DATE",      ORACLE, "DATE",             SQLSERVER, "DATE"));
register("DATETIME", Map.of(MYSQL, "DATETIME",  POSTGRES, "TIMESTAMP", ORACLE, "TIMESTAMP",      SQLSERVER, "DATETIME"));
register("TIMESTAMP",Map.of(MYSQL, "TIMESTAMP", POSTGRES, "TIMESTAMP", ORACLE, "TIMESTAMP",      SQLSERVER, "DATETIME"));
register("TIME",     Map.of(MYSQL, "TIME",      POSTGRES, "TIME",      ORACLE, "TIME",             SQLSERVER, "TIME"));

// 二进制族
register("BINARY",   Map.of(MYSQL, "BINARY",    POSTGRES, "BYTEA",     ORACLE, "BLOB",             SQLSERVER, "VARBINARY"));
register("BLOB",     Map.of(MYSQL, "BLOB",      POSTGRES, "BYTEA",     ORACLE, "BLOB",             SQLSERVER, "VARBINARY"));

// 特殊族
register("BOOLEAN",  Map.of(MYSQL, "TINYINT(1)", POSTGRES, "BOOLEAN",  ORACLE, "NUMBER(1)",      SQLSERVER, "BIT"));
register("JSON",     Map.of(MYSQL, "JSON",       POSTGRES, "JSONB",    ORACLE, "CLOB",             SQLSERVER, "NVARCHAR(MAX)"));
register("YEAR",     Map.of(MYSQL, "YEAR",       POSTGRES, "SMALLINT",  ORACLE, "NUMBER(4)",      SQLSERVER, "SMALLINT"));  // PG/Oracle 无 YEAR，归一到 SMALLINT
```

### 3.3 反向解析策略

`fromDialect` 是转换的入口，负责将方言专属写法归一为 canonical：

```
MySQL "INT(11) UNSIGNED" → 去掉修饰词 → "INT" → canonical "INT"
MySQL "VARCHAR(100)"     → 提取前缀 → "VARCHAR" + precision=100
MySQL "DATETIME(6)"      → 去掉精度后缀 → "DATETIME"
Oracle "NUMBER(10,2)"    → 提取前缀 → "DECIMAL" + precision=10 + scale=2
Postgres "VARCHAR(100)"  → 提取前缀 → "VARCHAR" + precision=100
```

策略：
1. 对每个 dialect 维护一个**方言专属正则模式列表**（按长度降序，避免短前缀误匹配）
2. 匹配成功后提取精度/标度参数
3. 匹配失败时**保守返回原始字符串**（不做危险猜测），由上层决定是否报错

---

## 四、函数映射层设计

### 4.1 函数映射表：Normal Form 函数 + 各方言专属写法

```java
// jkit-sql/src/main/java/com/alianga/jkit/sql/SqlFunctionRegistry.java

/**
 * 函数映射注册表：以 Normal Form 函数名为轴，声明各方言的专属写法。
 *
 * <p>Normal Form 函数名采用 SQL-92 标准或广泛兼容的函数名：
 *   - IF → CASE WHEN（MySQL 特有语法 → 标准 SQL）
 *   - GROUP_CONCAT → STRING_AGG（MySQL 特有 → ANSI）
 *   - CONVERT(expr USING charset) → CAST(expr AS type)（MySQL charset 转换 → 标准 CAST）
 * </p>
 */
public final class SqlFunctionRegistry {

    /**
     * 注册一个 Normal Form 函数在各方言的写法。
     *
     * @param normalFormName  Normal Form 函数名，如 "IF"、"GROUP_CONCAT"、"CONVERT"
     * @param normalFormExpr  Normal Form 表达式模板，用 {arg} 占位，如 "CASE WHEN {cond} THEN {true} ELSE {false} END"
     * @param dialectForms    各方言专属写法（可部分方言缺失，缺失时回落到 Normal Form）
     *                        key: SqlDialect, value: 方言专属表达式模板或 null（表示与本方言相同）
     */
    public void register(String normalFormName, String normalFormExpr,
                         Map<SqlDialect, String> dialectForms) { ... }

    /**
     * 查询：Normal Form 函数在某方言的写法模板。
     * 方言专属写法缺失时回落到 Normal Form。
     */
    public String getDialectForm(String normalFormName, SqlDialect dialect) { ... }

    /**
     * 匹配：给定源方言函数调用，返回 Normal Form 名 + 参数列表。
     * 失败时返回 null（非标准函数，保持原样）。
     */
    public MatchResult match(SqlFunctionExpr fn, SqlDialect sourceDialect) { ... }
}
```

### 4.2 内置函数映射

```java
// IF 函数：MySQL IF(cond, true, false) → 标准 CASE WHEN
register("IF",
    "CASE WHEN {cond} THEN {true} ELSE {false} END",
    Map.of(
        MYSQL,   null,                    // MySQL 原生 IF，无需转换
        POSTGRES, "CASE WHEN {cond} THEN {true} ELSE {false} END",
        ORACLE,  "CASE WHEN {cond} THEN {true} ELSE {false} END",
        SQLSERVER,"CASE WHEN {cond} THEN {true} ELSE {false} END"
    ));

// GROUP_CONCAT：MySQL GROUP_CONCAT(col ORDER BY ... SEPARATOR ...) → ANSI STRING_AGG
register("GROUP_CONCAT",
    "STRING_AGG({col}, {separator}) WITHIN GROUP (ORDER BY {order})",
    Map.of(
        MYSQL,   null,                    // MySQL 原生 GROUP_CONCAT
        POSTGRES,"STRING_AGG({col}, {separator})",
        ORACLE,  "LISTAGG({col}, {separator}) WITHIN GROUP (ORDER BY {order})",
        SQLSERVER,"STRING_AGG({col}, {separator})"
    ));

// CONVERT(expr USING charset)：MySQL charset 转换 → 标准 CAST
register("CONVERT",
    "CAST({expr} AS {type})",
    Map.of(
        MYSQL,   null,                    // MySQL 原生 CONVERT
        POSTGRES,"{expr}::{type}",        // PG 的 :: 类型转换
        ORACLE,  "TO_{type}({expr})",     // Oracle 的 TO_* 函数
        SQLSERVER,"CAST({expr} AS {type})"
    ));

// NOW() / CURDATE() / CURTIME()：MySQL 时间函数 → ANSI
register("NOW",      "CURRENT_TIMESTAMP", Map.of());
register("CURDATE",  "CURRENT_DATE",     Map.of());
register("CURTIME",  "CURRENT_TIME",     Map.of());
```

### 4.3 匹配与替换策略

```
源 AST: SqlFunctionExpr(name="IF", args=[cond, true, false])
    │
    ├─ match() 返回：normalFormName="IF", args=[cond, true, false]
    ├─ getDialectForm("IF", POSTGRES) 返回："CASE WHEN {cond} THEN {true} ELSE {false} END"
    ├─ 用 args 替换模板中的 {arg} 占位
    └─ 生成新 SqlCaseExpr(node)
```

替换失败（如函数名不匹配任何注册规则）时**保持原 AST 不变**，不报错——这是保守策略，避免误改写未知函数。

---

## 五、改写器层设计

### 5.1 改写器抽象

```java
// jkit-sql/src/main/java/com/alianga/jkit/sql/SqlSchemaConverter.java

/**
 * 表结构跨方言转换器门面。
 *
 * <p>核心流程：</p>
 * <pre>
 *   parse(sql, sourceDialect)
 *       → AST
 *       → Visitor 遍历：按 Normal Form 归一各方言专属结构
 *       → Visitor 遍历：按目标方言重编码结构
 *       → format(stmt, targetDialect)
 *       → 目标方言 SQL
 * </pre>
 *
 * <p>所有改写均先深拷贝原 AST（复用 {@link SQL#clone}），不污染调用方。</p>
 *
 * <pre>{@code
 * // 最简单用法
 * String pgSql = SQL.convert(
 *     "CREATE TABLE t (id INT NOT NULL, name VARCHAR(100), create_time DATETIME)",
 *     SqlDialect.MYSQL,
 *     SqlDialect.POSTGRES);
 *
 * // 带自定义选项
 * String oracleSql = SQL.convert(sql, MYSQL, ORACLE,
 *     SqlSchemaConvertOptions.defaults()
 *         .addTypeMapping("MEDIUMINT", "NUMBER(8)")   // 自定义类型映射
 *         .preserveComments(true)                     // 保留注释
 *         .convertPagination(true));                  // 同时转换分页形态
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class SqlSchemaConverter {
    private SqlSchemaConverter() {}

    /**
     * 转换单条 SQL（先 clone，不改原 AST）。
     */
    public static String convert(String sql, SqlDialectSpec source, SqlDialectSpec target) { ... }

    public static String convert(String sql, SqlDialectSpec source, SqlDialectSpec target,
                                  SqlSchemaConvertOptions options) { ... }

    /**
     * 转换已解析的 AST（不 clone；调用方如需保留原 AST 应先 clone）。
     */
    public static SqlStatement convert(SqlStatement stmt, SqlDialectSpec source,
                                        SqlDialectSpec target) { ... }

    public static SqlStatement convert(SqlStatement stmt, SqlDialectSpec source,
                                        SqlDialectSpec target,
                                        SqlSchemaConvertOptions options) { ... }
}
```

### 5.2 改写器链：按语句类型分发

```java
// jkit-sql/src/main/java/com/alianga/jkit/sql/SqlSchemaConverterVisitor.java

/**
 * 跨方言表结构转换 Visitor。
 * 继承 SqlAstVisitor，覆写感兴趣的节点类型，按 Normal Form 归一 + 重编码。
 */
public class SqlSchemaConverterVisitor extends SqlAstVisitor {

    private final SqlDialectSpec sourceDialect;
    private final SqlDialectSpec targetDialect;
    private final SqlSchemaConvertOptions options;
    private final SqlDataTypeRegistry typeRegistry;
    private final SqlFunctionRegistry functionRegistry;

    @Override
    protected boolean visitDdl(SqlDdlStatement node) {
        if (node.objectType() == null || !node.objectType().equalsIgnoreCase("TABLE")) {
            return true; // 非 TABLE DDL 暂不转换（视图、触发器等保持原样）
        }
        convertDdlColumns(node);
        convertDdlTableOptions(node);
        return true;
    }

    @Override
    protected boolean visitFunctionExpr(SqlFunctionExpr node) {
        // 函数名映射：IF → CASE WHEN，GROUP_CONCAT → STRING_AGG ...
        return convertFunction(node);
    }

    @Override
    protected boolean visitSelect(SqlSelect node) {
        // 分页形态转换（复用现有 SqlRewriter）
        if (options.convertPagination()) {
            SqlRewriter.adaptPagination(node, targetDialect);
        }
        return true;
    }

    // ... 其他 visitXxx 方法
}
```

### 5.3 DDL 列类型改写

```java
/**
 * 改写 CREATE TABLE 的列定义。
 *
 * <p>策略：</p>
 * <ol>
 *   <li>解析 columnDefinitions[] 原文，提取类型名与精度参数</li>
 *   <li>通过 typeRegistry.fromDialect() 归一为 canonical</li>
 *   <li>通过 typeRegistry.toDialect() 按目标方言重新编码</li>
 *   <li>重建 columnDefinitions[]，替换原文中的类型部分</li>
 * </ol>
 */
private void convertDdlColumns(SqlDdlStatement ddl) {
    List<String> newDefs = new ArrayList<>(ddl.columnDefinitions().size());
    for (String def : ddl.columnDefinitions()) {
        String converted = convertColumnDefinition(def);
        newDefs.add(converted);
    }
    ddl.columnDefinitions().clear();
    ddl.columnDefinitions().addAll(newDefs);
}

private String convertColumnDefinition(String definition) {
    // 正则匹配：列名 + 类型 + [精度] + [修饰符]
    // MySQL:  `id` INT NOT NULL AUTO_INCREMENT
    // 解析后：name="id", type="INT", precision=null, modifiers=["NOT NULL", "AUTO_INCREMENT"]
    // 转换后：type="INTEGER" (Postgres)
    // 重建：  `id` INTEGER NOT NULL AUTO_INCREMENT
    ...
}
```

### 5.4 DDL 表选项改写

```java
/**
 * 改写 CREATE TABLE 的表级选项。
 *
 * <p>按目标方言过滤或改写：</p>
 * <ul>
 *   <li>MySQL 特有：{@code ENGINE=InnoDB}、{@code DEFAULT CHARSET=utf8mb4}
 *       → Postgres 无对应选项，删除</li>
 *   <li>MySQL 特有：{@code AUTO_INCREMENT=100} → Postgres 用 {@code GENERATED ALWAYS AS IDENTITY}</li>
 *   <li>{@code COMMENT='...'} → 保持，但引号形态按目标方言调整</li>
 * </ul>
 */
private void convertDdlTableOptions(SqlDdlStatement ddl) {
    // ENGINE / CHARSET / COLLATE 按目标方言过滤
    if (options.stripDialectOptions()) {
        ddl.setEngine(null);
        ddl.setCharset(null);
        ddl.setCollate(null);
    }
    // 表注释：按目标方言引号形态调整
    if (ddl.comment() != null && !options.stripComments()) {
        // 双引号方言保持，反引号方言转换为双引号
    }
}
```

---

## 六、转换上下文与配置

### 6.1 `SqlSchemaConvertContext`

```java
// jkit-sql/src/main/java/com/alianga/jkit/sql/SqlSchemaConvertContext.java

/**
 * 转换上下文：持有源/目标方言、类型映射表、函数映射表、改写选项。
 * 所有改写器共享此上下文，保证转换一致性。
 */
public final class SqlSchemaConvertContext {
    private final SqlDialectSpec sourceDialect;
    private final SqlDialectSpec targetDialect;
    private final SqlSchemaConvertOptions options;
    private final SqlDataTypeRegistry typeRegistry;
    private final SqlFunctionRegistry functionRegistry;

    // 构造器私有，通过 Builder 构建
    private SqlSchemaConvertContext(Builder builder) { ... }

    public static Builder builder() { ... }

    public static final class Builder {
        public Builder source(SqlDialectSpec source) { ... }
        public Builder target(SqlDialectSpec target) { ... }
        public Builder options(SqlSchemaConvertOptions options) { ... }
        // 自定义类型映射（运行时注入，优先级高于内置）
        public Builder addTypeMapping(String canonical, String sourceForm, String targetForm) { ... }
        public SqlSchemaConvertContext build() { ... }
    }
}
```

### 6.2 `SqlSchemaConvertOptions`

```java
// jkit-sql/src/main/java/com/alianga/jkit/sql/SqlSchemaConvertOptions.java

/**
 * 表结构转换配置项。
 *
 * <p>所有选项默认为安全保守策略（不做破坏性转换），可按需开启增强行为。</p>
 */
public final class SqlSchemaConvertOptions {

    /**
     * 是否转换分页形态。
     * true：目标方言的分页形态按 {@link SqlRewriter#adaptPagination} 适配（如 MySQL LIMIT → Oracle ROWNUM）。
     * false：分页形态保持原样，只转换类型与函数。
     * 默认 false（DDL/DML 场景通常不涉及分页）。
     */
    private boolean convertPagination = false;

    /**
     * 是否移除目标方言不支持的表级选项（如 MySQL 的 ENGINE、CHARSET）。
     * 默认 true。
     */
    private boolean stripDialectOptions = true;

    /**
     * 是否保留表/列注释。
     * 默认 true。
     */
    private boolean preserveComments = true;

    /**
     * 未知类型（未注册到 typeRegistry 的类型）的处理策略：
     * <ul>
     *   <li>KEEP：保持原样，不做转换（默认）</li>
     *   <li>WARN：保持原样，但记录警告日志</li>
     *   <li>ERROR：抛出 SqlSchemaConvertException</li>
     * </ul>
     */
    private UnknownTypeHandling unknownTypeHandling = UnknownTypeHandling.KEEP;

    // ... Builder 模式构建，private 构造器
}
```

---

## 七、与现有体系的集成关系

```
现有体系                            新增体系
─────────────────────────────────────────────────────
SQL.parse(sql, dialect)    ──────→  解析源方言 SQL 到 AST
SQL.clone(stmt)            ──────→  深拷贝（SqlAstCloner 树拷贝）
SQL.format(stmt, dialect)  ──────→  按目标方言格式化输出
SqlRewriter.adaptPagination() ───→  分页形态适配（可选开启）
SqlAstVisitor.visitXxx()   ──────→  节点级改写（Visitor 模式）
SqlFormatter.writeDdl()    ──────→  DDL 格式化复用（convertDdlColumns 后直接调用）
```

**集成点**：
1. 复用 `SQL.clone()` 保证原 AST 不被污染
2. 复用 `SqlAstVisitor` 的节点分发，改写器只需覆写 `visitDdl`、`visitFunctionExpr` 等方法
3. 复用 `SqlFormatter.writeDdl()` 的 DDL 格式化逻辑（改写列定义后直接 format 即可）
4. 复用 `SqlRewriter.adaptPagination()` 处理分页形态转换（可选）
5. 复用 `SqlDialectSpec` 的能力判断（是否需要引号、是否支持 LIMIT 等）

**不破坏现有行为**：所有方法均为新增，不修改 `SqlRewriter`、`SqlFormatter`、`SqlDialect` 等既有类。

---

## 八、完整调用链路示例

### 示例 1：MySQL CREATE TABLE → PostgreSQL

```java
String mysqlSql = "CREATE TABLE t ("
    + "  id INT NOT NULL AUTO_INCREMENT,"
    + "  name VARCHAR(100) NOT NULL,"
    + "  status TINYINT(1) DEFAULT 0,"
    + "  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,"
    + "  PRIMARY KEY (id)"
    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

String pgSql = SQL.convert(mysqlSql, SqlDialect.MYSQL, SqlDialect.POSTGRES);
// 期望输出：
// CREATE TABLE t (
//   id INTEGER NOT NULL,
//   name VARCHAR(100) NOT NULL,
//   status SMALLINT DEFAULT 0,
//   create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
//   PRIMARY KEY (id)
// )
```

**转换过程**：
```
1. SQL.parse(mysqlSql, MYSQL)
   → SqlDdlStatement AST，columnDefinitions = [
       "id INT NOT NULL AUTO_INCREMENT",
       "name VARCHAR(100) NOT NULL",
       "status TINYINT(1) DEFAULT 0",
       "create_time DATETIME DEFAULT CURRENT_TIMESTAMP",
       "PRIMARY KEY (id)"
     ]

2. SqlSchemaConverterVisitor.visitDdl(stmt)
   → 遍历 columnDefinitions[]
   → "INT"           → fromDialect(MYSQL) → "INT"       → toDialect(POSTGRES) → "INTEGER"
   → "VARCHAR(100)"  → fromDialect(MYSQL) → "VARCHAR"   → toDialect(POSTGRES) → "VARCHAR(100)"
   → "TINYINT(1)"    → fromDialect(MYSQL) → "BOOLEAN"   → toDialect(POSTGRES) → "SMALLINT"
   → "DATETIME"      → fromDialect(MYSQL) → "DATETIME"  → toDialect(POSTGRES) → "TIMESTAMP"
   → 重建 columnDefinitions[]

3. convertDdlTableOptions(stmt)
   → ENGINE=InnoDB  → 删除（Postgres 不支持）
   → CHARSET=utf8mb4 → 删除（Postgres 不支持）
   → 保留 PRIMARY KEY

4. SQL.format(stmt, POSTGRES)
   → 输出 PostgreSQL 格式 SQL
```

### 示例 2：MySQL SELECT → Oracle（含函数与分页转换）

```java
String mysqlSql = "SELECT IF(status=1,'active','inactive') AS st,"
    + " GROUP_CONCAT(tag SEPARATOR ',') AS tags"
    + " FROM t WHERE create_time > '2024-01-01'"
    + " LIMIT 20 OFFSET 40";

String oracleSql = SQL.convert(mysqlSql, SqlDialect.MYSQL, SqlDialect.ORACLE,
    SqlSchemaConvertOptions.defaults().convertPagination(true));
// 期望输出：
// SELECT
//   CASE WHEN status=1 THEN 'active' ELSE 'inactive' END AS st,
//   LISTAGG(tag, ',') WITHIN GROUP (ORDER BY tag) AS tags
// FROM t
// WHERE create_time > '2024-01-01'
// AND ROWNUM <= 60
// MINUS
// SELECT * FROM (SELECT t.*, ROWNUM AS RN FROM (...) t WHERE ROWNUM <= 60) WHERE RN > 40
// （具体形态由 SqlRewriter.adaptPagination 决定）
```

---

## 九、扩展点说明

### 9.1 新增方言

新增第 N 个方言时，只需：

1. 在 `SqlDataTypeRegistry` 的每个 canonical 类型下追加一行声明：
   ```java
   register("INT", Map.of(
       MYSQL,   "INT",
       POSTGRES,"INTEGER",
       ORACLE,  "NUMBER(10)",
       SQLSERVER,"INT",
       CLICKHOUSE, "Int32"  // 新增一行
   ));
   ```
2. 在 `SqlFunctionRegistry` 的每个函数下追加方言专属写法（如有）：
   ```java
   register("IF", "...", Map.of(
       ...
       CLICKHOUSE, "if({cond}, {true}, {false})"  // ClickHouse 有原生 IF
   ));
   ```
3. 现有所有映射零改动，双向能力自动成立。

### 9.2 运行时自定义映射

```java
// 通过 Builder 注入自定义映射（优先级高于内置）
SqlSchemaConvertContext ctx = SqlSchemaConvertContext.builder()
    .source(SqlDialect.MYSQL)
    .target(SqlDialect.POSTGRES)
    .addTypeMapping("MEDIUMINT", "MEDIUMINT", "INTEGER")  // 自定义 MEDIUMINT→INTEGER
    .build();
```

### 9.3 SPI 扩展点

在 `SqlSchemaConverters` 中预留 SPI 注册点，支持外部模块注入自定义类型/函数映射：

```java
// jkit-sql/src/main/resources/META-INF/services/com.alianga.jkit.sql.SqlSchemaConverterProvider
// 外部实现提供自定义映射，由 SPI 机制自动加载
```

---

## 十、测试策略

### 10.1 单元测试

```java
// SqlSchemaConverterTest.java
public class SqlSchemaConverterTest {

    // DDL：类型转换
    @Test
    public void convertColumnType_MySQLToPostgres() {
        assertEquals(
            "CREATE TABLE t (id INTEGER NOT NULL, name VARCHAR(100) NOT NULL)",
            SQL.convert("CREATE TABLE t (id INT NOT NULL, name VARCHAR(100) NOT NULL)",
                        MYSQL, POSTGRES));
    }

    @Test
    public void convertColumnType_MySQLToOracle() {
        assertEquals(
            "CREATE TABLE t (id NUMBER(10) NOT NULL, name VARCHAR2(100) NOT NULL)",
            SQL.convert("CREATE TABLE t (id INT NOT NULL, name VARCHAR(100) NOT NULL)",
                        MYSQL, ORACLE));
    }

    // DDL：表选项过滤
    @Test
    public void stripTableOptions() {
        String result = SQL.convert(
            "CREATE TABLE t (id INT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            MYSQL, POSTGRES);
        assertFalse(result, result.contains("ENGINE"));
        assertFalse(result, result.contains("CHARSET"));
    }

    // 函数转换
    @Test
    public void convertIfFunction() {
        assertEquals(
            "SELECT CASE WHEN status=1 THEN 'active' ELSE 'inactive' END AS st FROM t",
            SQL.convert("SELECT IF(status=1,'active','inactive') AS st FROM t",
                        MYSQL, POSTGRES));
    }

    // 双向验证：A→B→A 应等价于原 SQL（类型层面）
    @Test
    public void bidirectionalRoundTrip() {
        String original = "CREATE TABLE t (id INT, name VARCHAR(100))";
        String toPg = SQL.convert(original, MYSQL, POSTGRES);
        String backToMySql = SQL.convert(toPg, POSTGRES, MYSQL);
        // 类型应等价（INT↔INTEGER 互为 canonical）
        assertTrue(backToMySql.contains("INT"));
    }

    // 分页转换（可选开启）
    @Test
    public void convertPagination_mysqlToOracle() {
        String result = SQL.convert(
            "SELECT * FROM t LIMIT 20 OFFSET 40",
            MYSQL, ORACLE,
            SqlSchemaConvertOptions.defaults().convertPagination(true));
        assertTrue(result, result.contains("ROWNUM"));
    }

    // 未知类型保持原样（保守策略）
    @Test
    public void unknownTypeKept() {
        String result = SQL.convert(
            "CREATE TABLE t (id MEDIUMINT)",
            MYSQL, POSTGRES);
        assertTrue(result, result.contains("MEDIUMINT"));
    }
}
```

### 10.2 集成测试

参考 `SqlGoldenCorpusTest` 的模式，准备跨方言的黄金语料集：

```
src/test/resources/sql-schema-convert-corpus/
├── mysql-to-postgres/
│   ├── create_table_basic.sql          → 基础 CREATE TABLE
│   ├── create_table_full.sql           → 完整 CREATE TABLE（含约束、注释）
│   ├── select_if_function.sql          → IF 函数转换
│   ├── select_group_concat.sql         → GROUP_CONCAT 转换
│   └── select_pagination.sql           → 分页转换
├── mysql-to-oracle/
│   └── ...
└── postgres-to-mysql/
    └── ...
```

---

## 十一、实现阶段规划

| 阶段 | 内容 | 产出 | 依赖 |
|---|---|---|---|
| Phase 1 | `SqlDataTypeRegistry` + 内置注册 | 类型映射核心 | 无 |
| Phase 2 | `SqlSchemaConvertContext` + `SqlSchemaConvertOptions` | 上下文与配置 | Phase 1 |
| Phase 3 | `SqlSchemaConverterVisitor.visitDdl()` | DDL 列类型转换 | Phase 1, 2 |
| Phase 4 | `SqlSchemaConverter` 门面 + `SQL.convert()` | 集成入口 | Phase 1-3 |
| Phase 5 | `visitFunctionExpr()` + `SqlFunctionRegistry` | 函数映射转换 | Phase 1 |
| Phase 6 | 分页转换（复用 `SqlRewriter.adaptPagination`）+ 完整测试 | 端到端验证 | Phase 4 |
| Phase 7 | SPI 扩展点 + 性能基准 | 生产就绪 | Phase 6 |

---

## 十二、设计决策总结

| 决策 | 选择 | 原因 |
|---|---|---|
| 映射模型 | Normal Form 中转（非 pairwise） | 新增方言 O(K) 而非 O(N²)，双向自动成立 |
| 改写方式 | Visitor 模式（非字符串替换） | 基于 AST，语义准确，复用 `SqlAstVisitor` 分发 |
| 破坏性 | 先 clone 再改（复用 `SQL.clone`） | 与原 `SQL.addLimit` 等 API 保持一致 |
| 未知类型 | 保守保持原样（非报错） | 避免误改写用户自定义类型 |
| 函数映射 | 匹配失败时保持原样 | 不破坏未知函数的语义 |
| 分页转换 | 可选开启（默认关闭） | DDL 场景不涉及分页，避免不必要的转换 |
| 表选项处理 | 目标方言不支持则删除 | 避免无效语法（如 Postgres 不接受 ENGINE=InnoDB） |
