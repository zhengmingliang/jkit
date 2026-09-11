# 表结构跨方言转换模块 —— 企业级重构方案 v3.0

> 基于对 v2.0 方案（`sql-schema-converter-design.md`）的评审结论重构。
> 核心结论：**Normal Form 中转的总体思路保留**，但 DDL 列定义的处理方式、类型注册表的反向查找、函数改写机制三处需要重新设计；同时补齐 v2.0 完全缺失的可观测性、性能基准、插件化与差分测试。
>
> **落地评估（2026-09-11）**：总体可行，已按下文「整改」落地 Phase 0–1（结构化列模型 + canonical 类型注册表），`jkit-sql` 全量单测 1010 条通过。Phase 2 起继续。

---

## 前言：本版本解决了 v2.0 的哪些问题

| # | v2.0 的问题 | v3.0 的对策 | 对应章节 |
|---|---|---|---|
| 1 | DDL 列定义是 `List<String>`，"AST-based" 名不副实，本质是正则拼字符串 | 新增结构化 `ColumnDefinition` + 真正的 tokenizer 解析器，渐进式兼容旧 API | 三 |
| 2 | 类型注册表反向查找可能多对一碰撞（如 MySQL `DATETIME`/`TIMESTAMP` 在 PG 下同为 `TIMESTAMP`），"双向自动成立"不成立 | `CanonicalType` 强类型枚举 + `RegistryValidator` 编译期/CI 自检 + 显式声明有损映射 | 四 |
| 3 | 精度模板（`%d`）语义在文档和注册表里不一致，代码不知道何时该拼精度 | `DialectTypeForm` 统一模板规则，无占位符即完整字面量 | 4.2 |
| 4 | 函数改写：字符串模板拼接 vs AST 构造自相矛盾，有重解析/优先级风险 | 统一改为 AST 直接构造，字符串模板仅作为一次性预编译的语法糖 | 五 |
| 5 | `CONVERT(expr USING charset)` 与 `CAST` 语义被错误合并 | 拆成两个独立 Normal Form 函数 | 5.4 |
| 6 | 列级 `AUTO_INCREMENT` 在示例中被静默丢弃 | 显式的自增策略矩阵，按目标方言生成对应语法或明确报警 | 4.6 |
| 7 | `DEFAULT 0/1` 转 `BOOLEAN` 后未联动改写 | `DefaultValueCoercer` 按 canonical 类型对转换默认值 | 4.7 |
| 8 | `UNSIGNED`、字符集/排序规则等修饰符完全未处理 | 修饰符作为一等公民建模，可配置处理策略 | 4.5 |
| 9 | 无性能设计，无基准 | Immutable Registry、EnumMap、惰性缓存、批量并行、JMH 基准入 CI | 七 |
| 10 | 无可观测性，转换过程是黑盒 | `ConversionReport`：警告收集、统计、分级异常 | 6.3 |
| 11 | SPI 只是占位，无真实接口 | 完整 `SqlSchemaConverterProvider` + 优先级覆盖 + 新方言 TestKit | 八 |
| 12 | 测试断言薄弱（`contains` 弱断言、MEDIUMINT 用例与注册表矛盾） | Registry 自检 + 基于属性的 roundtrip 测试 + Testcontainers 差分测试 | 九 |

---

## 〇、落地评估与整改（相对本稿原文）

对照现有 `jkit-sql`（JDK 8、可变 AST、`SqlDdlStatement.columnDefinitions()` 为 `List<String>`、12 种一等方言）评审后，**Normal Form 中转可行**，但原文有几处不能按字面实现。已整改并写入代码：

| # | 原文问题 | 整改 | 状态 |
|---|---|---|---|
| A | 示例用 `record` / `sealed interface` / `Map.of` / `String.formatted`，模块是 **JDK 8** | 改为不可变 POJO + `ColumnConstraint.Kind` 穷尽枚举；`String.format`；显式泛型 | Phase 0 已落地 |
| B | `SqlDdlStatement.structuredColumns()` 写成 `default` 方法，该类是 class 不是 interface | 不改既有公开签名；新增 `SqlColumnDefinitionParser.fromDdl(ddl, dialect)` | Phase 0 已落地 |
| C | 「手写 tokenizer，不要正则」同时又写「Parser 里预编译 Pattern」自相矛盾 | **复用现有 `SqlLexer`**，不引入第二套词法、也不在转换路径用正则抠类型 | Phase 0 已落地 |
| D | 示例只覆盖 MYSQL/POSTGRES/ORACLE/SQLSERVER 四方言；代码里 Oracle 已拆成 `ORACLE`（≤11g）与 `ORACLE12` | 内置注册覆盖全部 12 个 `SqlDialect`；Oracle 两档类型写法相同，自增策略（Phase 2）才分档 | Phase 1 已落地 |
| E | `columnDefinitions()` 混有列定义和表级约束（`PRIMARY KEY (id)`、`FOREIGN KEY`…） | `ColumnDefinition.tableConstraint()` 标记表级片段，转换时原样保留 | Phase 0 已落地 |
| F | 反向查找若把 `NUMBER(10)` 的基名 `NUMBER` 登记成 TINYINT/INT，会吞掉 `NUMBER(10,2)`→DECIMAL | 完整字面量（无 `%d` 且含括号）**只做精确匹配**；有占位符的模板才登记基名别名。`TINYINT(1)`→BOOLEAN 走精确匹配，`TINYINT(4)` 走 TINYINT 别名 | Phase 1 已落地 |
| G | `jqwik` / Testcontainers / JMH 会引入新测试依赖 | Phase 0–1 只用现有 JUnit 4；属性测试、容器差分、JMH 门禁按原路线图放到 Phase 7–8 | 延后 |
| H | `IdentityHashMap` 按 AST 节点缓存列解析 | Phase 0 解析很快，暂不缓存；热路径优化放 Phase 7 | 延后 |

**反向查找算法（已实现）**：

1. 归一化（大写、压缩空白、去掉括号旁多余空格）
2. 完整字面量精确表（`TINYINT(1)`、`NUMBER(10)`、`VARCHAR(MAX)`）
3. 去掉括号后的类型别名（`INTEGER`→`INT`、`VARCHAR2`→`VARCHAR`、`NUMBER`→`DECIMAL`）
4. 仍失败 → `CanonicalType.UNKNOWN`，`convert` 返回原文

有损映射由 `RegistryValidator` 在内置表构建时强制声明，CI 用 `RegistryValidationTest` 再跑一遍。

---

## 一、总体架构

Normal Form 中转的价值不变：新增方言 O(K) 而非 O(N²)，双向能力由同一张表对称保证。v3.0 的变化在于**每一层的内部表示都从"弱类型字符串"升级为"强类型结构"**，正则/字符串操作被压缩到唯一必要的地方——源文本的词法解析——并且只在那里出现一次。

```
源方言 SQL
    │  parse(sql, sourceDialect)                      【现有能力，不动】
    ▼
 语句级 AST（SqlDdlStatement / SqlSelect / ...）
    │  ColumnDefinitionParser.parse()  ← 新增，唯一允许做词法分析的地方
    ▼
 结构化列模型 ColumnDefinition[]（SqlDataType + ColumnConstraint[]）
    │  CanonicalTypeResolver：结构化类型 → CanonicalType（强类型，非字符串）
    ▼
 Canonical AST（方言无关的中间表示）
    │  CanonicalTypeResolver：CanonicalType → 目标方言 SqlDataType
    │  FunctionRewriteRule：目标方言函数 AST 直接构造
    ▼
 目标方言结构化列模型 / AST
    │  format(stmt, targetDialect)                     【现有能力，复用】
    ▼
 目标方言 SQL
```

**分层原则**：从"结构化列模型"往下的每一步都是对象到对象的映射，不再出现"从字符串里正则抠子串"这类操作。词法解析只发生一次，且发生在有独立单元测试覆盖的 `ColumnDefinitionParser` 里，而不是散落在 `convertColumnDefinition` 这种转换逻辑内部。

---

## 二、模块划分（对照现有 `jkit-sql` 包结构）

```
jkit-sql/src/main/java/com/alianga/jkit/sql/schema/
├── model/
│   ├── ColumnDefinition.java          # 不可变 POJO（JDK 8，非 record）
│   ├── SqlDataType.java               # 类型节点 + TypeAttribute
│   ├── ColumnConstraint.java          # 接口 + Kind 枚举 + 静态内部类（非 sealed）
│   └── CanonicalType.java             # 含 UNKNOWN
├── parse/
│   └── SqlColumnDefinitionParser.java # 复用 SqlLexer；fromDdl 不改 AST 签名
├── registry/
│   ├── SqlDataTypeRegistry.java       # EnumMap + 精确/别名两级反向查找
│   ├── SqlDataTypeRegistryBuiltins.java
│   ├── DialectTypeForm.java
│   ├── LossyMapping.java
│   ├── RegistryValidator.java
│   └── SqlFunctionRegistry.java       # Phase 4
├── rewrite/                           # Phase 2 / 4
├── convert/                           # Phase 3
└── spi/                               # Phase 6
```

测试：`jkit-sql/src/test/java/com/alianga/jkit/sql/schema/`
`SqlColumnDefinitionParserTest`、`SqlDataTypeRegistryTest`、`RegistryValidationTest`。

---

## 三、核心数据模型：结构化 DDL（解决"伪 AST"问题）

### 3.1 为什么不能直接大改现有 `SqlDdlStatement`

现有 `SqlDdlStatement.columnDefinitions()` 返回 `List<String>`，是既成事实，`jkit-sql` 里可能已有其他调用方依赖这个签名。**v3.0 不改变这个公开 API**，而是新增一条并行通路：在其之上惰性构建结构化视图，做到旧代码零改动、新代码不再碰字符串。

> 落地说明：模块是 JDK 8，下列 `record` / `sealed` 示例在实现里对应不可变 POJO 与 `ColumnConstraint.Kind`。入口是 `SqlColumnDefinitionParser.parse` / `fromDdl`，不是 `SqlDdlStatement` 上的 default 方法。

```java
// jkit-sql/src/main/java/com/alianga/jkit/sql/schema/model/ColumnDefinition.java

/**
 * 结构化列定义。通过 {@link com.alianga.jkit.sql.schema.parse.SqlColumnDefinitionParser}
 * 从原始列定义文本解析得到，是本模块所有转换逻辑的唯一输入形式。
 */
public record ColumnDefinition(
        String columnName,
        SqlDataType dataType,
        List<ColumnConstraint> constraints,
        String rawText          // 保留原文，用于 unknown/无法解析时的兜底回退
) {
    public boolean has(Class<? extends ColumnConstraint> type) {
        return constraints.stream().anyMatch(type::isInstance);
    }
}
```

```java
// SqlDataType.java —— 类型节点，替代裸字符串类型名

public record SqlDataType(
        String rawTypeName,        // 源方言原始写法，如 "TINYINT"
        Integer precision,
        Integer scale,
        Set<TypeAttribute> attributes   // UNSIGNED / ZEROFILL / BINARY ...
) {
    public enum TypeAttribute { UNSIGNED, ZEROFILL, BINARY_CHARSET }
}
```

```java
// ColumnConstraint.java —— sealed interface，穷举约束类型，禁止遗漏分支处理

public sealed interface ColumnConstraint
        permits ColumnConstraint.NotNull, ColumnConstraint.Nullable,
                 ColumnConstraint.DefaultValue, ColumnConstraint.AutoIncrement,
                 ColumnConstraint.InlinePrimaryKey, ColumnConstraint.Comment,
                 ColumnConstraint.OnUpdate, ColumnConstraint.CharacterSet,
                 ColumnConstraint.Collation {

    record NotNull() implements ColumnConstraint {}
    record Nullable() implements ColumnConstraint {}
    record DefaultValue(SqlExpr expr) implements ColumnConstraint {}
    record AutoIncrement() implements ColumnConstraint {}
    record InlinePrimaryKey() implements ColumnConstraint {}
    record Comment(String text) implements ColumnConstraint {}
    record OnUpdate(SqlExpr expr) implements ColumnConstraint {}      // MySQL ON UPDATE CURRENT_TIMESTAMP
    record CharacterSet(String charset) implements ColumnConstraint {}
    record Collation(String collation) implements ColumnConstraint {}
}
```

用 `sealed interface` 而不是开放继承体系，是为了让 formatter/rewriter 里的 `switch` 表达式在编译期获得穷尽性检查——新增一种约束类型时，所有遗漏处理它的地方会编译失败，而不是运行时静默丢弃（这正是 v2.0 里 `AUTO_INCREMENT` 被静默丢掉的根因：没有任何机制强制"每种修饰符都必须被显式处理或显式声明忽略"）。

### 3.2 `SqlColumnDefinitionParser`：真正的词法解析，而不是正则拼接

v2.0 的问题不在于"用了字符串"，而在于**用临时正则去猜测列定义的结构**，且正则之间靠"按长度降序"这种脆弱的启发式来避免误匹配。v3.0 把这部分收敛成一个有限状态的 tokenizer：

```java
// SqlColumnDefinitionParser.java

/**
 * 将原始列定义文本解析为结构化 {@link ColumnDefinition}。
 *
 * <p>实现为简单的手写 tokenizer（非正则拼接、非 ANTLR 引入新依赖）：
 * 按空白/括号/引号切词，用一个方言相关的关键字表识别修饰符，
 * 顺序无关（不依赖"哪个正则先匹配"）。</p>
 *
 * <p>解析失败（罕见的方言专属语法）时不抛异常，而是返回一个
 * {@code dataType=UNKNOWN, rawText=原文} 的降级结果，交由上层按
 * {@link com.alianga.jkit.sql.schema.convert.SqlSchemaConvertOptions#unknownColumnHandling()}
 * 决定保留原文还是报警。</p>
 */
public final class SqlColumnDefinitionParser {

    public ColumnDefinition parse(String rawDefinition, SqlDialect dialect) { ... }

    // 每个方言注册自己的关键字集合（AUTO_INCREMENT / IDENTITY / UNSIGNED / ZEROFILL ...），
    // tokenizer 本身与方言无关，只有"关键字 → ColumnConstraint 的映射表"按方言区分。
    private ColumnConstraint matchKeyword(String token, SqlDialect dialect) { ... }
}
```

**性能考量**：解析结果按 AST 节点身份（`IdentityHashMap`）做惰性缓存——同一条 `SqlDdlStatement` 在一次转换流程里，列定义只解析一次，即使被多个 `visitXxx` 访问。

**兼容策略**：`SqlDdlStatement` 新增一个非破坏性的扩展方法：

```java
default List<ColumnDefinition> structuredColumns(SqlDialect dialect) {
    return SqlColumnDefinitionParserCache.getOrParse(this, dialect);
}
```

老代码继续用 `columnDefinitions()`（`List<String>`）不受影响；新的 schema 转换逻辑一律走 `structuredColumns()`。

---

## 四、Canonical 类型系统

### 4.1 `CanonicalType`：从裸字符串升级为枚举

```java
public enum CanonicalType {
    TINYINT, SMALLINT, MEDIUMINT, INT, BIGINT,
    FLOAT, DOUBLE, DECIMAL,
    CHAR, VARCHAR, TEXT,
    DATE, DATETIME, TIMESTAMP, TIME,
    BINARY, BLOB,
    BOOLEAN, JSON, YEAR;

    /** 该 canonical 类型是否需要精度参数（VARCHAR(n)、DECIMAL(p,s) 等）。*/
    public boolean requiresPrecision() { ... }
}
```

字符串键（`"INTEGER"`、`"VARCHAR"`）在 v2.0 里散落在多处字面量中，容易拼错且无法被 IDE/编译器检查。枚举的代价是"外部插件不能凭空发明新的 canonical 类型"——这是**有意为之的约束**：canonical 类型集合必须是一个受控的、被全体已知方言共同理解的公共词汇表，任由插件随意扩充反而会破坏"归一化"这个核心假设。插件真正需要的扩展点是"某个方言下某个 canonical 类型的写法"，而不是发明新的 canonical 类型本身（见第八节 SPI 设计）。

### 4.2 `DialectTypeForm`：统一的精度模板规则

v2.0 里"`INT` 在 Oracle 下是完整字面量 `NUMBER(10)`，`DECIMAL` 在 Oracle 下是需要拼精度的裸类型 `NUMBER`"——同一个 `Map<SqlDialect,String>` 结构却承载两种不同语义，代码无从区分。v3.0 用统一规则消除歧义：

```java
public record DialectTypeForm(String pattern) {

    /** pattern 含 %d 才会尝试拼接精度/标度；不含则视为完整字面量，忽略传入的 precision/scale。 */
    public String render(Integer precision, Integer scale) {
        if (!pattern.contains("%d")) {
            return pattern;                         // 完整字面量：NUMBER(10)、BOOLEAN、TEXT ...
        }
        if (scale != null) {
            return pattern.formatted(precision, scale);   // DECIMAL(%d,%d)
        }
        return pattern.formatted(precision);              // VARCHAR(%d)
    }
}
```

注册示例（对照 v2.0 会发现二义性被消除）：

```java
register(CanonicalType.INT, Map.of(
    MYSQL,     new DialectTypeForm("INT"),
    POSTGRES,  new DialectTypeForm("INTEGER"),
    ORACLE,    new DialectTypeForm("NUMBER(10)"),   // 无 %d → 完整字面量，用户精度被忽略
    SQLSERVER, new DialectTypeForm("INT")
));

register(CanonicalType.DECIMAL, Map.of(
    MYSQL,     new DialectTypeForm("DECIMAL(%d,%d)"),
    POSTGRES,  new DialectTypeForm("NUMERIC(%d,%d)"),
    ORACLE,    new DialectTypeForm("NUMBER(%d,%d)"), // 含 %d → 拼接源精度
    SQLSERVER, new DialectTypeForm("DECIMAL(%d,%d)")
));
```

### 4.3 反向查找与歧义消解：`RegistryValidator`

**问题回顾**：MySQL `DATETIME` 和 `TIMESTAMP` 两个 canonical 类型，在 Postgres 下都写作 `TIMESTAMP`。如果只有正向注册表，反向查找（`fromDialect("TIMESTAMP", POSTGRES)`）在存在碰撞时是未定义行为，"双向自动成立"这个承诺就是假的。

v3.0 把这个问题从"运行时可能踩坑"变成"注册阶段强制显式处理"：

```java
public final class RegistryValidator {

    /**
     * 在 registry 构建完成后（应用启动期或单测里）执行一次全量自检：
     * 对每个方言，检查是否存在两个不同的 canonical 类型渲染出完全相同的字面量
     * 且没有通过 precision 区分开——这种情况必须被显式声明为 {@link LossyMapping}，
     * 否则校验失败并抛出异常（阻断启动/构建，而不是留到线上被用户发现）。
     */
    public ValidationResult validate(SqlDataTypeRegistry registry) { ... }
}
```

```java
/**
 * 显式声明一组 canonical 类型在某方言下不可逆地收敛到同一写法。
 * 反向转换（fromDialect）遇到该写法时，返回 {@link #primary()}；
 * 其余 canonical 类型只能"转入"、不能"转出"到该方言的这个写法。
 * 这不是缺陷，是如实记录信息丢失的事实，供调用方决策（如日志告警）。
 */
public record LossyMapping(SqlDialect dialect, String literalForm,
                            CanonicalType primary, Set<CanonicalType> collapsedFrom) {}

// 注册示例：
registerLossyMapping(new LossyMapping(
    POSTGRES, "TIMESTAMP",
    /* primary = */ CanonicalType.TIMESTAMP,
    /* collapsedFrom = */ Set.of(CanonicalType.DATETIME, CanonicalType.TIMESTAMP)
));
```

`RegistryValidator.validate()` 作为一个独立的 JUnit 测试（`RegistryValidationTest`）在 CI 里对当前全部注册内容跑一遍，新增方言或新增 canonical 类型时，一旦引入未声明的碰撞，**CI 直接红**，而不是像 v2.0 那样只能靠人工评审发现（本次评审就是这么发现 `DATETIME`/`TIMESTAMP` 碰撞的——不应该依赖人工评审兜底）。

### 4.4 修饰符：`UNSIGNED` / `ZEROFILL` / 字符集

v2.0 完全没提这些，但它们是 MySQL DDL 里极常见的修饰符。v3.0 把处理策略做成可配置项：

```java
public enum UnsignedHandling {
    UPSIZE,        // 默认：升级到下一档有符号类型以覆盖同样的正数范围
                    // TINYINT UNSIGNED(0..255) → SMALLINT；INT UNSIGNED(0..2^32-1) → BIGINT
    ADD_CHECK,      // 保持原类型宽度，附加 CHECK (col >= 0) 约束
    DROP_AND_WARN   // 直接丢弃 UNSIGNED，写入 ConversionReport 警告
}
```

`SqlSchemaConvertOptions` 新增 `unsignedHandling`，默认 `UPSIZE`——这是唯一能同时保证"不丢数据范围""不产生非法目标方言语法"的默认策略。

字符集/排序规则（`CHARACTER SET` / `COLLATE`）：目标方言不支持列级字符集时（PG/Oracle），策略同表级选项处理——按 `options.stripDialectOptions()` 决定删除还是保留在注释里，与 v2.0 表级选项的处理保持一致的语义，不再是"表级选项处理了、列级修饰符没处理"的割裂状态。

### 4.5 `AUTO_INCREMENT` / `IDENTITY`：统一策略矩阵

这是 v2.0 实际示例里被静默丢弃、且最容易被用户第一时间发现的 bug。v3.0 给出显式的策略矩阵，不允许"默认丢弃"这条路径存在：

```java
public interface AutoIncrementStrategy {
    /** 将源列的自增约束，转换为目标方言下等价的列定义改写。 */
    ColumnRewriteResult apply(ColumnDefinition column, SqlDialect target);
}
```

| 目标方言 | 处理方式 |
|---|---|
| MySQL | 保留 `AUTO_INCREMENT` 修饰符；表级 `AUTO_INCREMENT=n` 按需保留 |
| PostgreSQL | 列类型改写为 `GENERATED ALWAYS AS IDENTITY`（可选项切换为 legacy `SERIAL`/`BIGSERIAL`） |
| Oracle 12c+ | `GENERATED ALWAYS AS IDENTITY` |
| Oracle ≤11g | **不做自动建 DDL**——转换器生成该列的普通类型定义 + 在 `ConversionReport` 里给出 `MANUAL_ACTION_REQUIRED` 级别警告："需手工创建 SEQUENCE + TRIGGER"。明确声明能力边界，而不是假装生成一段可能不完整的 DDL 骗过用户 |
| SQL Server | `IDENTITY(1,1)` |

**关键设计原则**：任何"目标方言无法完整表达源语义"的场景，宁可产出警告也不要静默吞掉——这条原则同样适用于下面的默认值联动。

### 4.6 `DEFAULT` 值随类型联动转换

```java
public interface DefaultValueCoercer {
    /** 当列的 canonical 类型发生变化时，判断默认值字面量是否需要跟着改写。 */
    Optional<SqlExpr> coerce(SqlExpr originalDefault, CanonicalType from, CanonicalType to);
}
```

内置规则：`CanonicalType.BOOLEAN` 转换时，数值字面量 `0`/`1` 改写为目标方言的布尔字面量（PG `false`/`true`；Oracle 因为落到 `NUMBER(1)` 数值类型，反而不需要改写——**注意这正是 v2.0 示例里被算错的那个场景**，v3.0 把它做成显式的、可测试的规则而不是隐含在正则替换里。

---

## 五、函数改写层：AST 直接构造替代字符串模板

### 5.1 为什么放弃纯字符串模板

v2.0 的 `"CASE WHEN {cond} THEN {true} ELSE {false} END"` 如果是**运行时字符串拼接再重新解析**，就会引入两个风险：拼接时参数本身若是复合表达式，需要额外处理括号/优先级（`a OR b` 直接替换进 `{cond}` 而外层没有括号包裹时，语义可能改变）；且需要"重新解析生成的字符串"这一步，等于多了一趟不必要的 parse，性能也差。

v3.0 统一为：**改写规则直接返回 AST 节点**，函数参数以 `SqlExpr` 对象形式传入、组装，不经过任何字符串拼接：

```java
public interface FunctionRewriteRule {
    /** 直接构造目标 AST 节点；返回 null 表示不改写（保持原样）。 */
    SqlExpr rewrite(List<SqlExpr> args, SqlDialect target);
}

// 示例：IF → CASE WHEN，纯 AST 构造，天然保证括号/优先级正确
FunctionRewriteRule ifToCase = (args, target) -> new SqlCaseExpr(
    List.of(new SqlCaseExpr.WhenClause(args.get(0), args.get(1))),
    /* elseExpr = */ args.get(2)
);
register(CanonicalFunction.IF, Map.of(
    MYSQL, null,           // 原生支持，不改写
    POSTGRES, ifToCase,
    ORACLE, ifToCase,
    SQLSERVER, ifToCase
));
```

### 5.2 `TemplateFunctionRewriteRule`：给简单场景的语法糖，但仍然基于 AST

不是所有规则都值得手写 AST 构造代码。对纯参数替换、不涉及优先级重组的简单场景（如 Oracle 的 `TO_DATE({expr})`），提供一个便捷实现——但内部机制是"**启动时把模板解析成带占位符叶子节点的 AST 骨架，运行时克隆骨架再替换占位符叶子**"，而不是每次调用都做字符串拼接+重新解析：

```java
public final class TemplateFunctionRewriteRule implements FunctionRewriteRule {
    private final SqlExpr compiledSkeleton;   // 启动期编译一次

    public TemplateFunctionRewriteRule(String template) {
        this.compiledSkeleton = SqlExprTemplateCompiler.compile(template); // 仅执行一次
    }

    @Override
    public SqlExpr rewrite(List<SqlExpr> args, SqlDialect target) {
        return SqlAstCloner.cloneAndSubstitute(compiledSkeleton, args);   // 克隆骨架，O(节点数)
    }
}
```

这样既保留了"用模板字符串快速声明规则"的开发体验，又消除了"运行时重新解析字符串"的性能与正确性风险。

### 5.3 `CONVERT` 语义拆分

v2.0 把 MySQL 的字符集转换 `CONVERT(expr USING charset)` 和类型转换 `CONVERT(expr, type)` / `CAST(expr AS type)` 合并成同一条 Normal Form 规则，这是两个不同的操作。v3.0 拆成两个独立的 `CanonicalFunction`：

```java
CanonicalFunction.CAST            // CONVERT(expr, type) / CAST(expr AS type)
CanonicalFunction.CHARSET_CONVERT // CONVERT(expr USING charset) —— 字符集转换，无通用等价物时保留原样+警告
```

`CHARSET_CONVERT` 在多数目标方言下没有直接等价语法（PG 的 `convert_to`/`convert_from` 语义也不完全对等），默认策略是**保持原样并产生 `SEMANTIC_RISK` 级别警告**，而不是像 v2.0 那样错误地映射成 `CAST`。

---

## 六、转换器门面与上下文（API 基本兼容 v2.0，新增可观测性）

### 6.1 对外 API

```java
public final class SqlSchemaConverter {
    public static ConversionResult convert(String sql, SqlDialect source, SqlDialect target) { ... }
    public static ConversionResult convert(String sql, SqlDialect source, SqlDialect target,
                                            SqlSchemaConvertOptions options) { ... }

    /** 批量转换：复用同一个 Context/Registry，可选并行。 */
    public static List<ConversionResult> convertBatch(List<String> sqls, SqlDialect source,
                                                        SqlDialect target, SqlSchemaConvertOptions options) { ... }
}
```

与 v2.0 的关键差异：返回值从裸 `String` 升级为 `ConversionResult`（携带 SQL 文本 + `ConversionReport`）。为了不破坏"只要文本"的简单用法，`ConversionResult` 实现 `CharSequence`/提供 `toString()` 直接返回 SQL 文本，旧的 `String pgSql = SQL.convert(...)` 式调用可以不改代码（如果 `SQL.convert` 仍需要返回裸 `String`，保留一个 `@Deprecated` 的重载调用新 API 并丢弃报告，引导迁移而非强制break）。

### 6.2 `ConversionReport`：可观测性

```java
public record ConversionReport(
        List<ConversionWarning> warnings,
        ConversionStats stats
) {
    public boolean hasBlockingIssues() {
        return warnings.stream().anyMatch(w -> w.severity() == Severity.MANUAL_ACTION_REQUIRED);
    }
}

public record ConversionWarning(Severity severity, String columnOrLocation, String message) {
    public enum Severity { INFO, SEMANTIC_RISK, MANUAL_ACTION_REQUIRED }
}
```

`options.failOnSeverity(Severity.MANUAL_ACTION_REQUIRED)` 可以让调用方选择"遇到无法完整表达的语义就直接抛异常阻断"，用于 CI/流水线场景；默认不阻断，只收集警告，适合交互式/探索性使用。

---

## 七、性能设计

企业级批量迁移场景（"多租户 SaaS 按租户所在数据库动态改写 SQL"）意味着这个模块可能在请求路径上被高频调用，性能不是可选项。

1. **Registry 不可变、启动期一次性构建**：`CanonicalType` 用 `EnumMap` 而非 `HashMap<String,...>`，查找是数组下标级别的开销；整张注册表构建完成后用 `Map.copyOf`/不可变包装，多线程并发读取零锁。
2. **列定义解析惰性 + 按 AST 节点身份缓存**：同一条 DDL 语句在一次转换流程中（类型转换、函数改写、格式化多个阶段都可能访问列信息）只解析一次。
3. **模板函数规则启动期编译**：见 5.2，避免每次函数调用都重新解析模板字符串。
4. **批量转换支持并行**：`convertBatch` 内部对无状态的 Registry/Context 只读共享，可选 `parallelStream()`，避免为每条 SQL 重复走 SPI 加载/规则编译开销（SPI 加载本身只在应用启动时执行一次，不在转换热路径里）。
5. **正则预编译**：`SqlColumnDefinitionParser` 里所有 `Pattern` 在类初始化期编译为 `static final`，不在方法调用时 `Pattern.compile`。
6. **JMH 基准纳入 CI 门禁**：提供 `SqlSchemaConverterBenchmark`，以"每千行 DDL 转换耗时"为核心指标，性能回归超过阈值（如 +20%）时 CI 失败，防止后续迭代悄悄引入性能劣化（比如误用了字符串模板重解析路径）。

---

## 八、可扩展性：SPI 插件化

### 8.1 真实的 SPI 接口（替代 v2.0 的占位）

```java
public interface SqlSchemaConverterProvider {
    /** 为已有 canonical 类型追加新方言写法，或（罕见）追加新的 LossyMapping 声明。 */
    default void registerTypes(SqlDataTypeRegistry registry) {}

    /** 为已有 canonical 函数追加新方言的改写规则。 */
    default void registerFunctions(SqlFunctionRegistry registry) {}

    /** 优先级，数值越大越晚注册、越能覆盖前面的声明。内置注册固定为 0。 */
    default int priority() { return 100; }
}
```

```
jkit-sql/src/main/resources/META-INF/services/
    com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider
```

### 8.2 新增方言的开发者体验：`SqlSchemaConverterProviderTestKit`

新增一个方言最容易出的错，是"某个 canonical 类型忘了声明这个方言的写法"或"引入了未声明的 reverse 碰撞"——这些错误应该在插件作者自己的单测里就被抓到，而不是等到集成时才发现：

```java
public abstract class SqlSchemaConverterProviderTestKit {

    protected abstract SqlSchemaConverterProvider providerUnderTest();
    protected abstract SqlDialect dialectUnderTest();

    @Test
    void everyCanonicalTypeIsDeclared() { ... }   // 遍历 CanonicalType 枚举全量校验覆盖率

    @Test
    void noUndeclaredReverseCollision() { ... }   // 复用 RegistryValidator

    @Test
    void coreFunctionsHaveExplicitDecision() { ... } // 核心函数要么声明改写规则，要么显式声明"无需改写"
}
```

插件作者只需继承这个基类、实现两个抽象方法，就能获得一整套"新增方言正确性"的回归测试，把原本依赖人工评审的正确性保证前移到了开发阶段。

---

## 九、测试策略

在 v2.0 单测/黄金语料的基础上，新增三类测试，分别对应本次评审发现的三类问题（内部矛盾、注册表设计漏洞、弱断言）：

### 9.1 Registry 自检测试（对应 4.3）

`RegistryValidationTest` 在 CI 中对当前全部注册内容跑 `RegistryValidator.validate()`，任何未声明的 reverse 碰撞或精度模板歧义直接使构建失败。

### 9.2 基于属性的 Roundtrip 测试（对应弱断言问题）

用 jqwik 对每个"未被声明为 `LossyMapping`"的 canonical 类型做 `A → B → A` 的**强等值**断言（而不是 v2.0 里 `contains("INT")` 这种连 `"INTEGER"` 都能通过的弱断言）；对声明为有损的类型，断言至少落在同一个 `LossyMapping.collapsedFrom` 等价类里。

### 9.3 差分测试：真实数据库建表验证（新增，企业级刚需）

用 Testcontainers 启动 MySQL / PostgreSQL / Oracle-XE 容器，把转换生成的目标方言 DDL 真的丢进去执行：

```java
@Testcontainers
class CrossDialectDdlExecutionTest {
    @Container static final MySQLContainer<?> mysql = ...;
    @Container static final PostgreSQLContainer<?> postgres = ...;

    @Test
    void convertedDdlActuallyCreatesTable() {
        String pgDdl = SqlSchemaConverter.convert(goldenMysqlDdl, MYSQL, POSTGRES).sql();
        assertDoesNotThrow(() -> postgresJdbc.execute(pgDdl));  // 真的能建表，不只是字符串比对
    }
}
```

这是"字符串比对通过"和"生成的 DDL 真的能在目标数据库跑起来"之间唯一可靠的验证方式，纳入 CI（可标记为较慢的 `@Tag("integration")`，与快速单测分开执行）。

### 9.4 黄金语料扩展

沿用 v2.0 的目录结构，但每条语料除了 `.sql` 输入输出对，额外要求标注该转换预期产生的 `ConversionWarning`（如果有），防止"该报警的场景没有报警"这类回归被忽略。

---

## 十、实施路线图

| 阶段 | 内容 | 产出 | 依赖 | 备注 |
|---|---|---|---|---|
| Phase 0 | `ColumnDefinition` / `SqlDataType` / `ColumnConstraint` 模型 + `SqlColumnDefinitionParser` | 结构化列模型可用，不接入转换逻辑 | 无 | **已完成**（JDK 8 POJO + 复用 `SqlLexer`） |
| Phase 1 | `CanonicalType` 枚举 + `DialectTypeForm` + `SqlDataTypeRegistry` + `RegistryValidator` | 类型映射核心，自带 CI 自检 | Phase 0 | **已完成**（12 方言 + 有损映射自检） |
| Phase 2 | `AutoIncrementStrategy` + `DefaultValueCoercer` + UNSIGNED/字符集策略 | DDL 列转换语义完整 | Phase 1 | **已完成** |
| Phase 3 | `SqlSchemaConverter` 门面 + `SqlSchemaConvertOptions` + `ConversionReport` | 集成入口，含可观测性 | Phase 0-2 | **已完成**（`SQL.convert`） |
| Phase 4 | `FunctionAstRewriter`：IF→CASE、NOW/CURDATE/CURTIME、GROUP_CONCAT↔STRING_AGG/LISTAGG、IFNULL/NVL/ISNULL、CONCAT、CAST 类型、`CONVERT USING` 告警 | 函数改写 AST 化 | Phase 1 | **已完成**（模板骨架预编译仍可后续加） |
| Phase 5 | 分页转换接入（复用现有 `SqlRewriter.adaptPagination` / `format`） | 端到端 DML 转换 | Phase 3 | **已完成**（format 路径） |
| Phase 6 | SPI（`SqlSchemaConverterProvider` + TestKit） | 第三方/内部团队可插拔新方言 | Phase 1, 4 | **已完成**（TestKit 在 test 源码，因 JUnit 仅为 test 依赖） |
| Phase 7 | 性能优化 + JMH 基准入 CI | 性能回归门禁 | Phase 3-4 | **基准类已放 `tools-test`**：`SqlSchemaConvertBenchmark`（本模块零运行时依赖） |
| Phase 8 | Testcontainers 差分测试 + 属性测试 + 黄金语料扩展 | 生产就绪的正确性保证 | Phase 3-6 | **属性 roundtrip + 回解析 + ALTER 列转换**；MySQL→PG 容器建表在 `tools-test` `CrossDialectDdlExecutionTest`（无 Docker 则 skip） |
| Phase 9 | 生产灰度：先接入多租户 SaaS 场景里风险最低的只读分页改写，再逐步开放 DDL 迁移场景 | 灰度发布 | Phase 8 |

---

## 十一、验收标准（Definition of Done）

- [x] `RegistryValidationTest` 在 CI 中稳定通过，且覆盖全部已注册方言 × canonical 类型组合
- [x] 已声明的 `LossyMapping` 有 roundtrip 等价类断言（`SqlDataTypeRegistryTest` / 语料）
- [x] `AUTO_INCREMENT` / `IDENTITY` 在四个内置方言下均有黄金语料覆盖，且 Oracle ≤11g 场景产出 `MANUAL_ACTION_REQUIRED` 警告而非静默生成不完整 DDL
- [x] `CHARSET_CONVERT` 与 `CAST` 有独立的黄金语料，不再共用同一条规则
- [x] Testcontainers 差分测试覆盖 MySQL→PG 建表验证（`tools-test`；无 Docker skip）。Oracle 容器镜像过大，仍用目标方言回解析兜底
- [x] JMH 基准类 `SqlSchemaConvertBenchmark` 已建立（`tools-test`）；CI 阈值门禁仍需积累基线后另开
- [x] `SqlSchemaConverterProviderTestKit` 用 SQLServer 做 dogfooding；测试 classpath 的 `TestAliasProvider` 证明 SPI 别名可加载

---

## 十二、明确不做的事（划清能力边界，避免过度承诺）

- **不做存储过程 / 触发器 / 视图的跨方言转换**——这些语法差异远大于类型和标量函数，属于完全不同量级的问题，v3.0 与 v2.0 一致，只处理 `CREATE TABLE` 与查询语句。
- **不自动生成 Oracle ≤11g 的 SEQUENCE+TRIGGER DDL**——只报警，不代做，避免生成方案在用户环境里"看起来能跑但语义不对"。
- **不保证字符集/排序规则转换后排序结果完全一致**——这依赖具体 collation 的语言学规则，不在本模块职责范围内，只做"保留/删除/警告"三选一的機械处理。
- **不做跨库的数据迁移（DML data migration）**——本模块只转换 DDL 结构和 SQL 语句形态，不涉及实际数据搬迁。
