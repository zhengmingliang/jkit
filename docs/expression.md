# 表达式引擎使用指南

`com.alianga.jkit.expression` 是一个零依赖的表达式求值引擎，从 EL/Aviator 风格的语法演进而来，常用作规则判断、动态取值、模板表达式。核心入口是 `Expression`：

- `Expression.eval(expr)`：无变量常量表达式（`1 + 2 * 3`、`@max(1,2)`）。
- `Expression.eval(expr, context)`：带上下文（`Map` 或 JavaBean），按属性名取值（`price * count`、`user.age`）。
- `Expression.eval(expr, env)`：带 `EvaluateEnvironment`，可配置数值精度、空值策略、注册函数等。
- `Expression.renderTemplate(tpl, context)`：字符串模板渲染，`${...}` 占位符内写表达式。

## 1. 算术与逻辑

运算符覆盖算术、比较、逻辑与三元：

```java
Expression.eval("1 + 2 * 3");                 // 7（遵循优先级）
Expression.eval("price * count", map);         // 36（map 或 bean 取属性）
Expression.eval("age >= 18 ? 'adult' : 'minor'", map);  // "adult"
Expression.eval("a > 0 && b > 0", map);        // 逻辑与
Expression.eval("(a + b) % 3", map);           // 取模
```

数值计算基于 `BigDecimal`，默认 `MathContext` 为 `DECIMAL64`，可通过 `EvaluateEnvironment.mathContext(...)` 调整精度。

## 2. 变量与上下文

上下文支持 `Map` 与任意 JavaBean，Bean 走 getter，Map 走 key；支持多级访问（`user.address.city`）。

```java
Map<String, Object> ctx = new HashMap<String, Object>();
ctx.put("price", 12);
ctx.put("count", 3);
Expression.eval("price * count", ctx);          // 36

// 位置参数：p0、p1 … 按顺序对应传入的参数
Expression.evalParameters("p0 + p1", 3, 4);     // 7
Expression.evalParameters("p0 * p1", Integer.class, 3, 4);  // 12，带目标类型
```

`allowVariableNull(true)`（或 `env.allowVariableNull(true)`）允许变量为 `null` 继续运算；默认 null 变量会抛异常。

## 3. 内置函数（@ 前缀）

函数以 `@` 开头，来自 `BuiltInFunction`：

| 函数 | 说明 |
| --- | --- |
| `@max(...)` / `@min(...)` | 取最大/最小（支持多个或数组） |
| `@sum(...)` / `@avg(...)` | 求和 / 平均值 |
| `@abs(x)` / `@sqrt(x)` | 绝对值 / 平方根 |
| `@length(s)` / `@size(o)` | 字符串长度 / 集合或数组大小 |
| `@lower(s)` / `@upper(s)` | 大小写转换 |
| `@ifNull(a, b)` | a 为 null 返回 b |
| `@isNull(v)` / `@isNotNull(v)` | 空判断，返回布尔 |
| `@now()` | 当前时间 `Date` |
| `@date(offset)` / `@date_format(offset, tpl)` | 时间戳偏移与格式化 |
| `@toString(v)` | 转字符串（null 得 `"null"`） |
| `@BigDecimal(s)` / `@BigInteger(s)` | 字符串转大数 |

```java
Expression.eval("@max(3, 9, 5)");               // 9
Expression.eval("@ifNull(name, '匿名')", map);  // name 为 null 时用默认值
```

## 4. 自定义函数与静态方法

在 `EvaluateEnvironment` 上注册：`registerFunction(name, ExprFunction)` 注册一个函数实现，`registerStaticMethods(Class...)` 把类的静态方法按方法名暴露为函数（默认按方法名，可传 `global=true` 全局生效）。

```java
EvaluateEnvironment env = EvaluateEnvironment.create();
env.registerFunction("addOne", params -> (Number) params[0] + 1);
env.registerStaticMethods(Math.class);          // 暴露 Math 的静态方法，如 @abs、@max
Expression.eval("@addOne(10)", env);            // 11
Expression.eval("@abs(-5)", env);               // 5
```

`ExprFunction.call(Object... params)` 是函数唯一接口，返回值即函数结果。

## 5. 模板渲染

`renderTemplate` 把 `${expr}` 替换为计算结果，可自定义占位符前后缀（`${` / `}` 只是默认）：

```java
Map<String, Object> ctx = new HashMap<String, Object>();
ctx.put("name", "Tom");
ctx.put("age", 18);
Expression.renderTemplate("${name} is ${age}", ctx);        // "Tom is 18"
Expression.renderTemplate("#{name}#{age}", "#{", "}", ctx); // "Tom18"
```

## 6. 编译缓存（CacheableExpression）

`CacheableExpression` 提供带 LRU 缓存的等价入口（`parse` / `eval` / `evalParameters`），最多缓存 256 个表达式，且只缓存长度不超过上限（默认 `1<<16`）的表达式。源码注释明确说明：**生产环境没必要缓存**，这是为与某些带缓存的库做性能对比而保留的；超长表达式每次重新解析且不入缓存。

```java
CacheableExpression.eval("@max(1,2,3)");
CacheableExpression.setMaxExprLength(1 << 14);  // 调小缓存上限
```

## 7. 异常

解析或求值失败抛 `ExpressionException`（继承 `RuntimeException`），携带 message 与 cause。常见原因：语法不合法、引用了不存在的变量且未开启 `allowVariableNull`、函数名未注册、类型无法转换。
