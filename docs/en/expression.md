# Expression Engine Guide

`com.alianga.jkit.expression` is a zero-dependency expression evaluation engine, evolved from an EL/Aviator-style syntax, commonly used for rule checks, dynamic value resolution, and template expressions. The main entry point is `Expression`:

- `Expression.eval(expr)`: no-variable constant expression (`1 + 2 * 3`, `@max(1,2)`).
- `Expression.eval(expr, context)`: with a context (`Map` or JavaBean), resolved by property name (`price * count`, `user.age`).
- `Expression.eval(expr, env)`: with an `EvaluateEnvironment`, to configure numeric precision, null strategy, registered functions, etc.
- `Expression.renderTemplate(tpl, context)`: string template rendering, where `${...}` placeholders hold expressions.

## 1. Arithmetic and logic

Operators cover arithmetic, comparison, logic, and ternary:

```java
Expression.eval("1 + 2 * 3");                 // 7 (operator precedence honored)
Expression.eval("price * count", map);         // 36 (map or bean property)
Expression.eval("age >= 18 ? 'adult' : 'minor'", map);  // "adult"
Expression.eval("a > 0 && b > 0", map);        // logical AND
Expression.eval("(a + b) % 3", map);           // modulo
```

Numeric arithmetic is `BigDecimal`-based, with default `MathContext` `DECIMAL64`; adjust precision via `EvaluateEnvironment.mathContext(...)`.

## 2. Variables and context

Context supports `Map` and any JavaBean—beans go through getters, maps through keys; nested access is supported (`user.address.city`).

```java
Map<String, Object> ctx = new HashMap<String, Object>();
ctx.put("price", 12);
ctx.put("count", 3);
Expression.eval("price * count", ctx);          // 36

// Positional parameters: p0, p1 … map in order to the passed arguments
Expression.evalParameters("p0 + p1", 3, 4);     // 7
Expression.evalParameters("p0 * p1", Integer.class, 3, 4);  // 12, with target type
```

`allowVariableNull(true)` (or `env.allowVariableNull(true)`) lets null variables keep computing; by default a null variable throws.

## 3. Built-in functions (@ prefix)

Functions start with `@` and come from `BuiltInFunction`:

| Function | Description |
| --- | --- |
| `@max(...)` / `@min(...)` | max / min (multiple args or an array) |
| `@sum(...)` / `@avg(...)` | sum / average |
| `@abs(x)` / `@sqrt(x)` | absolute value / square root |
| `@length(s)` / `@size(o)` | string length / collection or array size |
| `@lower(s)` / `@upper(s)` | case conversion |
| `@ifNull(a, b)` | a if not null, else b |
| `@isNull(v)` / `@isNotNull(v)` | null check, returns boolean |
| `@now()` | current time as `Date` |
| `@date(offset)` / `@date_format(offset, tpl)` | timestamp offset and formatting |
| `@toString(v)` | to string (null yields `"null"`) |
| `@BigDecimal(s)` / `@BigInteger(s)` | string to big number |

```java
Expression.eval("@max(3, 9, 5)");               // 9
Expression.eval("@ifNull(name, '匿名')", map);  // "匿名" when name is null
```

## 4. Custom functions and static methods

Register on `EvaluateEnvironment`: `registerFunction(name, ExprFunction)` registers a function implementation, `registerStaticMethods(Class...)` exposes a class's static methods as functions by method name (default by method name; pass `global=true` to register globally).

```java
EvaluateEnvironment env = EvaluateEnvironment.create();
env.registerFunction("addOne", params -> (Number) params[0] + 1);
env.registerStaticMethods(Math.class);          // expose Math statics, e.g. @abs, @max
Expression.eval("@addOne(10)", env);            // 11
Expression.eval("@abs(-5)", env);               // 5
```

`ExprFunction.call(Object... params)` is the single function interface; its return value is the function result.

## 5. Template rendering

`renderTemplate` replaces `${expr}` with the evaluated result; the placeholder prefix/suffix are configurable (`${` / `}` are defaults):

```java
Map<String, Object> ctx = new HashMap<String, Object>();
ctx.put("name", "Tom");
ctx.put("age", 18);
Expression.renderTemplate("${name} is ${age}", ctx);        // "Tom is 18"
Expression.renderTemplate("#{name}#{age}", "#{", "}", ctx); // "Tom18"
```

## 6. Compile cache (CacheableExpression)

`CacheableExpression` provides equivalent entries with an LRU cache (`parse` / `eval` / `evalParameters`), caching at most 256 expressions, and only those under the length limit (default `1<<16`). The source comment states explicitly: **caching is not needed in production**—it is kept for benchmarking against other cached libraries; over-long expressions are re-parsed each time and not cached.

```java
CacheableExpression.eval("@max(1,2,3)");
CacheableExpression.setMaxExprLength(1 << 14);  // lower the cache limit
```

## 7. Exceptions

Parsing or evaluation failures throw `ExpressionException` (extends `RuntimeException`) carrying message and cause. Common causes: invalid syntax, referencing a non-existent variable without `allowVariableNull`, an unregistered function name, or a type that cannot be converted.
