# Mock Data Generation Module

`com.alianga:jkit-mock` is a dependency-free mock data generator with two coexisting capabilities that share one corpus and one output formatter:

- **Field mode** (`MockDataProducer`): pick field types and generate in bulk — 30 built-in field types, 5 quick templates, custom fields, and one-shot export to **JSON / CSV / SQL INSERT / XML**.
- **Template mode** (`MockJs`): a full implementation of the **Mock.js specification** — data template definition (DTD) and data placeholder definition (DPD), with 75 placeholders, regex reverse generation and JSON Schema reverse generation.

The corpus (500 common Chinese characters, 66 English first names / 32 surnames, 100 Chinese surnames / 23 given names, 263 top-level domains, 35 provinces / 366 cities / 2,595 counties, plus company / department / position / UA / MIME dictionaries) lives in a single shared `MockDict`, so both modes never drift apart.

<MavenBadge artifact="jkit-mock" />

## Install

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-mock</artifactId>
    <version>2.0.3</version>
</dependency>
```

Source and bytecode target **JDK 8**, **no third-party dependencies**. It does depend on the sibling core artifact `com.alianga:jkit`, which Maven pulls in transitively. JPMS module name: `com.alianga.jkit.mock`.

## 1. Quick Start

```java
import com.alianga.jkit.mock.MockDataProducer;
import com.alianga.jkit.mock.MockOutputFormat;
import com.alianga.jkit.mock.MockTemplate;

import java.util.List;
import java.util.Map;

MockDataProducer producer = new MockDataProducer()
        .addFields(MockTemplate.USER.getFields())   // name / email / phone / idCard / gender / age / birthday / address
        .setCount(2);

List<Map<String, Object>> rows = producer.generate();

String json = producer.format(MockOutputFormat.JSON);  // JSON array, two-space indent per level
String csv  = producer.format(MockOutputFormat.CSV);   // header row first
String sql  = producer.format(MockOutputFormat.SQL);   // CREATE TABLE + INSERT INTO
String xml  = producer.format(MockOutputFormat.XML);   // <data><item id="N">
```

`addFields` / `addField` / `addCustomField` / `setCount` / `setSqlOptions` all return `this`.

## 2. Field Mode

### 2.1 Built-in field types (30)

Every `MockFieldType` carries `getKey()` (the column name / JSON key), `getLabel()` (Chinese label) and `getCategory()`.

| Category | Fields (key) |
| --- | --- |
| Personal (8) | `name`, `email`, `phone`, `idCard`, `gender`, `age`, `birthday`, `address` |
| Business (8) | `company`, `department`, `position`, `salary`, `bankCard`, `creditCard`, `price`, `currency` |
| Technical (14) | `uuid`, `ip`, `mac`, `userAgent`, `url`, `domain`, `password`, `token`, `color`, `timestamp`, `filename`, `mimeType`, `boolean`, `date` |

`MockFieldType.fromKey(String)` resolves a key back to the enum and returns `null` when unknown.

### 2.2 Quick templates (5)

| Template | Fields |
| --- | --- |
| `USER` | name, email, phone, idCard, gender, age, birthday, address |
| `EMPLOYEE` | name, email, phone, company, department, position, salary |
| `PRODUCT` | name, price, currency, uuid, timestamp |
| `ORDER` | uuid, name, email, phone, address, price, timestamp |
| `API` | uuid, token, ip, userAgent, timestamp, boolean |

`getFields()` returns an unmodifiable list you can hand straight to `addFields`.

### 2.3 Custom fields

```java
MockCustomField field = new MockCustomField("nickname", MockCustomFieldType.STRING, null);

MockDataProducer p = new MockDataProducer()
        .addField(MockFieldType.NAME)
        .addCustomField(field)
        .addCustomField(new MockCustomField("score", MockCustomFieldType.NUMBER, null))
        .setCount(2);
```

| Type | key | Value |
| --- | --- | --- |
| `STRING` | string | 5–20 alphanumeric characters |
| `NUMBER` | number | integer in the closed range 1–1000 |
| `BOOLEAN` | boolean | true / false |
| `DATE` | date | `yyyy-MM-dd`, from 2020-01-01 to today |
| `ARRAY` | array | 1–5 strings of 5 alphanumeric characters |

`MockCustomField` only has a no-arg constructor and a `(name, type, rule)` constructor; `rule` is a human-readable note and **does not affect generation**. `fromKey` falls back to `STRING`.

### 2.4 Consistency inside a record

After `beginRecord()`, one record's name / gender / birthday / age / ID card number / address are derived from each other — you will never see an ID card saying 1979 next to `age = 20`:

```java
MockDataGenerator g = new MockDataGenerator();
g.beginRecord();
String name     = g.generateName();        // 萧帅
String idCard   = g.generateIdCard();      // 440307197901119696
String birthday = g.generateBirthday();    // 1979-01-11
int age         = g.generateAge();         // 47
String addr     = g.generateAddress();     // 广东省深圳市龙岗区建设路53号
```

The birth segment of the ID card matches `birthday`, `age` is the exact age computed from the birthday, and `address` falls inside the region encoded by the ID card's division code. `MockDataProducer` calls `beginRecord()` for every record.

### 2.5 Count and randomness

- `setCount()` is clamped to **[1, 10000]** (default 10). Out-of-range values are silently clamped instead of throwing.
- The default source is `SecureRandom`. `MockDataGenerator` holds a static `SecureRandom` that cannot be swapped; in template mode `MockRandom` offers `MockRandom(Random)` and `MockRandom(long seed)` for reproducible output.

## 3. Template Mode (Mock.js)

### 3.1 Quick Start

```java
// 1. static convenience methods
Object data = MockJs.mock("{'list|3': [{'id|+1': 1, 'name': '@cname'}]}");
String json = MockJs.mockJson("{\"price|9-999.2\": 1}");   // {"price":148.06}

// 2. instance method — the parameter is Object, so cast String literals
MockJs js = new MockJs();
Object out = js.mock((Object) "{'code': 200, 'data|2': [{'id|+1': 1}]}");

// 3. flatten into records (drills into {"list":[...]}) and export CSV / SQL / XML
List<Map<String, Object>> rows = js.mockRecords("{'list|3': [{'id|+1': 1}]}");
```

Single quotes in a template are normalised to double quotes first (Mock.js docs use single quotes), so both styles work.

**Two easy traps**:

1. `js.mock("...")` passes a `String`, and Java prefers the **static** `mock(String)` — it builds a fresh `MockJs`, discarding custom placeholders, increment state and the streaming flag. Use `js.mock((Object) "...")`, or parse into a `Map` / `List` first.
2. `mockJson` goes through `JSON.toJsonString`, so it emits **compact single-line JSON**. For two-space indentation use `MockDataFormatter.prettyJson(MockJs.mock(tpl))`.

### 3.2 Data template definition (DTD)

Rules live in the property name as `'name|rule': value`:

| Rule | String | Number | Boolean | Array | Object |
| --- | --- | --- | --- | --- | --- |
| `'name\|min-max'` | repeat min–max times | integer between min and max | probability `min/(min+max)` | concatenate min–max times | pick min–max properties |
| `'name\|count'` | repeat count times | that number | by probability | concatenate count times | pick count properties |
| `'name\|1'` | unchanged | 1 | by probability | pick 1 element | pick 1 property |
| `'name\|+step'` | — | increment by step | — | take the next element in order | — |
| `'name\|min-max.dmin-dmax'` | — | integer part in min–max, dmin–dmax decimals | — | — | — |

A value written as `/^1[3-9]\d{9}$/` is reverse-generated from the regex; a value like `@cname` invokes a placeholder.

```json
{
  "total|100-500": 1,
  "price|9-999.2": 1,
  "list|2": [{
    "id|+1": 1,
    "name": "@cname",
    "phone": "/^1[3-9]\\d{9}$/",
    "gender|1": ["男", "女"],
    "score|0-100.1-2": 1,
    "vip|1-9": true
  }]
}
```

Output:

```json
{
  "total": 481,
  "price": 144.98,
  "list": [
    {
      "id": 1,
      "name": "罗娟",
      "phone": "17890645162",
      "gender": "男",
      "score": 92.5,
      "vip": false
    },
    {
      "id": 2,
      "name": "姚涛",
      "phone": "15636634366",
      "gender": "女",
      "score": 26.75,
      "vip": false
    }
  ]
}
```

Enhancement over Mock.js: the original cannot parse decimal ranges and turns `'price|9.9-999.2'` into a weird `"price.2"` key; here it produces a value between 9 and 999 with 2 decimals. When the integer part hits the upper bound it steps back by one (`99.xx < 100`), keeping both the range and the decimal digits intact.

**Increment and ordered-selection state lives on the parsed template object**, not on the `MockJs` instance:

```java
Object tpl = JSON.parse(MockJs.normalizeQuotes("{'data|2': [{'id|+1': 1}]}"));
MockJs js = new MockJs();
js.mock(tpl);   // {data=[{id=1}, {id=2}]}
js.mock(tpl);   // {data=[{id=3}, {id=4}]}   — continuous only when reusing the same parsed object
js.mock("{'data|2': [{'id|+1': 1}]}");  // {data=[{id=1}, {id=2}]} — re-parsing starts over
```

`js.reset()` clears the ordered-selection cursor and the `@increment` counter, but does not roll back values already written into the template.

### 3.3 Data placeholder definition (DPD)

`MockRandom` registers **75 placeholder names** (7 short aliases included; 68 primary names), covering all ten Mock.js categories plus a business set:

| Group | Placeholders |
| --- | --- |
| Basic | `boolean` `bool` `natural` `integer` `int` `number` `float` `character` `char` `string` `str` `range` |
| Date | `date` `time` `datetime` `now` |
| Image | `image` `img` `dataImage` |
| Color | `color` `hex` `rgb` `rgba` `hsl` |
| Text | `paragraph` `sentence` `word` `title` |
| Name | `first` `last` `name` |
| Web | `url` `domain` `protocol` `tld` `email` `ip` |
| Address | `region` `province` `city` `county` `zip` |
| Helper | `capitalize` `upper` `lower` `pick` `shuffle` |
| Misc | `guid` `uuid` `id` `increment` `inc` |
| Chinese | `cparagraph` `csentence` `cword` `ctitle` `cfirst` `clast` `cname` |
| Business | `phone` `gender` `company` `department` `position` `salary` `bankCard` `creditCard` `currency` `mac` `userAgent` `password` `token` `timestamp` `fileName` `mime` |

The last row is an extension beyond Mock.js: field mode could already produce MAC addresses, bank cards, user agents and so on, and template mode can now produce them too, from the same corpus.

```java
MockRandom r = new MockRandom();
r.invoke("integer", 10, 20);        // 19
r.invoke("cname");                  // 杨艳
r.invoke("image", "200x100");       // http://dummyimage.com/200x100
r.invoke("pick", "a", "b", "c");    // a
r.invoke("float", 1, 10, 2, 4);     // 2.18
r.invoke("email", "alianga.com");   // zkai@alianga.com — an explicit domain skips the random one
```

Notes:

- Placeholder names are **case-insensitive** (`@CNAME` equals `@cname`); unknown names throw `IllegalArgumentException`.
- Java keywords are renamed: the template says `@boolean` / `@float`, while the Java methods are `bool()` / `floatValue()`.
- `invoke("number", ...)` maps to `integer` (not a float); `float` is the floating-point one.
- `MockRandom.placeholderGroups()` returns those 12 groups (primary names only) and `sample(name)` returns a recommended call such as `@integer(1,100)` or `@city(true)`, falling back to `"@" + name`.

### 3.4 Custom placeholders

```java
MockRandom r = new MockRandom();
r.extend("employeeNo", (random, args) -> "E" + random.invoke("integer", 1000, 9999));
MockJs js = new MockJs(r);
js.mock((Object) "{'no':'@employeeNo'}");   // => {no=E6704}
```

Custom implementations win on name collisions, so built-ins can be overridden. `allPlaceholders()` returns built-ins plus everything registered on that instance (76 in the example). The callback type is `MockRandom.MockPlaceholder#apply(MockRandom, Object...)`.

### 3.5 Regex reverse generation

```java
MockRegex.generate("^1[3-9]\\d{9}$");     // 17684584134
MockRegex.generate("[A-Z]{3}\\d{4}");     // WYY2828
MockRegex.generate("(\\d)\\1\\1");        // 444
```

Supported: literals, escapes `\d \D \w \W \s \S`, `.`, character classes and negated classes, `{m,n}`, quantifiers `* + ?`, groups (capturing / non-capturing / look-ahead), alternation `|`, back-references `\1`. Not generated: `^ $ \b` are ignored; negative look-ahead `(?!...)` yields an empty string; an open-ended `{m,}` picks an upper bound of `min + 3~7` instead of expanding forever; negated classes draw from printable ASCII only. Inside a template the `/.../` body must contain a regex metacharacter, otherwise it is treated as plain text (`"zip": "100000"` is not a regex).

## 4. JSON Schema Reverse Generation

`MockSchema` turns a JSON Schema into a sample document that satisfies it:

```java
String schema = "{"
        + "  \"type\": \"object\","
        + "  \"required\": [\"id\", \"name\", \"email\"],"
        + "  \"properties\": {"
        + "    \"id\": {\"type\": \"integer\", \"minimum\": 1, \"maximum\": 9999},"
        + "    \"name\": {\"type\": \"string\"},"
        + "    \"email\": {\"type\": \"string\", \"format\": \"email\"},"
        + "    \"phone\": {\"type\": \"string\", \"pattern\": \"^1[3-9]\\\\d{9}$\"},"
        + "    \"status\": {\"enum\": [\"ACTIVE\", \"DISABLED\"]},"
        + "    \"score\": {\"type\": \"number\", \"minimum\": 0, \"maximum\": 100},"
        + "    \"profile\": {\"$ref\": \"#/definitions/Profile\"},"
        + "    \"tags\": {\"type\": \"array\", \"items\": {\"type\": \"string\"}}"
        + "  },"
        + "  \"definitions\": {\"Profile\": {\"type\": \"object\","
        + "      \"properties\": {\"city\": {\"type\": \"string\"}, \"vip\": {\"type\": \"boolean\"}}}}"
        + "}";

Object one = MockSchema.mock(schema);
String json = MockSchema.mockJson(schema);
List<Object> many = new MockSchema(new MockRandom(42L)).mockMany(schema, 2);
```

Sample output:

```json
{
  "id": 7279,
  "name": "许勇",
  "email": "hanp1984@yahoo.com",
  "phone": "16670688886",
  "status": "DISABLED",
  "score": 8.28,
  "profile": {"city": "北京市", "vip": false},
  "tags": ["sdmxusgf", "qeoh"]
}
```

| Entry | Meaning |
| --- | --- |
| `MockSchema.mock(schemaJson)` / `mock(schemaJson, options)` | static convenience; a new instance each call, randomness not controllable |
| `MockSchema.mockJson(schemaJson)` | static; returns two-space indented JSON (no options overload) |
| `new MockSchema(MockRandom).generate(...)` | instance method; **the constructor is the only way to inject a random source**, so seeds make results reproducible |
| `mockMany(schemaJson, count[, options])` | instance method; each item generated independently |
| `MockSchema.samples()` | 4 built-in sample schemas (user / order / product / API response) |

String resolution order: **`pattern`** (reverse-generated by `MockRegex`, falls through on failure) → **`format`** (`email`, `date-time`, `uri`, `uuid`, `ipv4`, `hostname`, …) → **property-name inference** (`name` → Chinese name, `email` → email, `phone` → mobile number, `createdAt` → timestamp, …) → random string, finally trimmed to `minLength` / `maxLength`. Numbers honour `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum` and `multipleOf`; with only one bound the other side is derived using `DEFAULT_NUM_SPAN = 1000`, and only when both are missing does it fall back to `[0, 100]`.

`MockSchemaOptions` (builder style):

| Option | Default | Meaning |
| --- | --- | --- |
| `includeOptional` | `true` | generate properties outside `required` |
| `arrayItems(min, max)` | `1, 3` | array length range when `minItems` / `maxItems` are absent |
| `useDefaults` | `false` | take `default` instead of a random value |
| `maxDepth` | `8` | nesting and `$ref` recursion limit |

Supported: `type` (including array form — one is picked at random), `properties`, `required`, `items` (including tuple form), `minItems` / `maxItems`, `additionalProperties`, `enum`, `const`, `default`, `allOf`, `oneOf` / `anyOf`, `$ref`. `$ref` resolves **internal references only** (`#/definitions/*`, `#/$defs/*`, `#/components/schemas/*` all go through the same path walk) — **external references resolve to null**. Exceeding `maxDepth` also returns null silently rather than throwing. `if/then/else`, `not`, `patternProperties`, `uniqueItems`, `$schema` and `$id` are not implemented and are ignored.

## 5. Output: JSON / CSV / SQL / XML

### 5.1 Four formats

```java
List<Map<String, Object>> rows = new MockDataProducer()
        .addFields(MockTemplate.USER.getFields()).setCount(2).generate();

MockDataFormatter.prettyJson(rows);                    // two-space indent per level
MockDataFormatter.toJson(rows);                        // one compact line per record
MockDataFormatter.toCsv(rows);                         // header row first
MockDataFormatter.toSql(rows);                         // SQL with default options
MockDataFormatter.toXml(rows);                         // <data><item id="N">
MockDataFormatter.format(rows, MockOutputFormat.JSON); // dispatch by enum
MockDataFormatter.toRows(data);                        // drill {"list":[{...}]} into records
```

Real output:

```sql
-- 表结构
CREATE TABLE fake_data (
  name VARCHAR(255),
  email VARCHAR(255),
  phone VARCHAR(255),
  idCard VARCHAR(255),
  gender VARCHAR(255),
  age BIGINT,
  birthday VARCHAR(255),
  address VARCHAR(255)
);

-- 数据插入
INSERT INTO fake_data (name, email, phone, idCard, gender, age, birthday, address) VALUES ('李静', 'songm@outlook.com', '18645922343', '610725199801305358', '男', 28, '1998-01-30', '陕西省汉中市勉县解放路55号');
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<data>
  <item id="1">
    <name>杨娜阳</name>
    <email>linyang1987@163.com</email>
  </item>
</data>
```

Escaping: SQL wraps strings in single quotes and escapes `'` as `''`, writing `NULL` for nulls; CSV follows RFC 4180 (quote fields containing commas, quotes or newlines, escape `"` as `""`); XML escapes `& < > " '`.

### 5.2 SQL output options

```java
MockSqlOptions options = MockSqlOptions.builder()
        .batch(true)                              // merge many VALUES into one INSERT
        .quote(MockSqlOptions.Quote.BACKTICK)
        .typeMode(MockSqlOptions.TypeMode.VARCHAR)
        .tableName("product")
        .createTable(false)                       // skip CREATE TABLE
        .build();

producer.setSqlOptions(options);                  // field mode
MockDataFormatter.toSql(rows, options);           // existing records
```

| Option | Values | Default |
| --- | --- | --- |
| `typeMode` | `AUTO` (infer from the first record) / `VARCHAR` (all `VARCHAR(255)`) / `TEXT` (all `TEXT`) | `AUTO` |
| `batch` | `true` emits one `INSERT ... VALUES` followed by several `(...)` groups | `false` |
| `quote` | `NONE` / `BACKTICK` / `DOUBLE_QUOTE` | `NONE` |
| `tableName` | table name | `fake_data` |
| `createTable` | emit CREATE TABLE | `true` |

`AUTO` inference: integers → `BIGINT`, floating point → `DOUBLE`, booleans → `BOOLEAN`, everything else (dates, UUIDs, other strings) → `VARCHAR(255)`.

### 5.3 Streaming and large batches

Holding 100k generated records in a `List` blows up memory, so the streaming path generates and writes one record at a time:

```java
MockJs js = new MockJs();
js.setStreaming(true);                       // arrays with count >= 1000 become a lazy MockRepeat
StringBuilder out = new StringBuilder();
MockDataFormatter.formatTo(out,
        js.mock((Object) "{'list|50000': [{'id|+1': 1, 'name': '@cname'}]}"),
        MockOutputFormat.CSV, null);         // ~440k chars / ~200 ms for 50k rows, no full list

int n = new MockDataProducer().addFields(MockTemplate.USER.getFields())
        .setCount(5).formatTo(out, MockOutputFormat.CSV);   // returns the number of rows
```

Both `MockDataFormatter.formatTo` and `MockDataProducer.formatTo` declare `throws IOException`; `producer.format(...)` wraps it in `IllegalStateException`. `MockRepeat`'s constructor is package-private — you only get one by enabling streaming. It `implements Iterable<Object>`, `size()` is known without expanding, and `MockDataFormatter`'s `rowCount` / `forEachRow` / writers consume it directly.

## 6. Validation and Schema Inference

```java
String tpl = "{'list|2': [{'id|+1': 1,'name': '@cname'}]}";

MockValid.valid(tpl, "{\"list\":[{\"id\":1,\"name\":\"张三\"},{\"id\":2,\"name\":\"李四\"}]}");
// => []  empty means "passed"

MockValid.valid(tpl, "{\"list\":[{\"id\":1},{\"id\":2,\"name\":\"李四\"}]}");
// => [ROOT.list[0].name 缺少属性]

MockValid.toJsonSchema(tpl);
// => {type=object, properties={list={type=array, range={min=2, max=null}, items=[{type=object}]}}}
```

`valid` checks missing properties, type mismatches, and whether numbers and arrays fall inside the declared range (arrays are compared as "repeat count = element count / template length"). Fields whose template value is a placeholder (contains `@` or starts with `/`) are skipped. The `valid(tpl, data, Progress)` overload reports progress and supports cancellation: return `false` from `tick(total, current)` to stop immediately; `total` is `-1` when unknown.

`toJsonSchema` emits a structure with `type` / `properties` / `range` / `increment`, where `range` and `increment` are **non-standard keywords** — do not feed them to a standard validator. `'name|1'` is unwrapped to the element type first.

## 7. Class Cheat Sheet

| Class | Role |
| --- | --- |
| `MockDataProducer` | field-mode facade: assemble fields → `generate()` / `format()` / `formatTo()` |
| `MockDataGenerator` | single-value generator: `generate(MockFieldType)` / `generateCustom(MockCustomField)` / `beginRecord()` |
| `MockFieldType` / `MockFieldCategory` | 30 field types / 3 categories |
| `MockTemplate` | 5 quick templates |
| `MockCustomField` / `MockCustomFieldType` | custom fields and their 5 types |
| `MockOutputFormat` | `JSON` / `CSV` / `SQL` / `XML` |
| `MockSqlOptions` | SQL options (type mode / batch / quoting / table name / create table) |
| `MockDataFormatter` | static formatting and streaming writes, shared by both modes |
| `MockJs` | Mock.js engine: `mock` / `mockJson` / `mockRecords` / `gen` / `samples` |
| `MockRandom` | placeholder generator: `invoke(name, args...)` / `extend` / `placeholderGroups` |
| `MockRule` | parses `'name\|rule'` |
| `MockRegex` | regex reverse generation |
| `MockDict` | shared corpus (including `regionTree()`, the three-level region tree) |
| `MockSchema` / `MockSchemaOptions` | JSON Schema reverse generation and options |
| `MockValid` | template validation and JSON Schema inference |
| `MockRepeat` | lazy array container for streaming (package-private constructor) |

## 8. Caveats

- Cast the template to `Object` when calling the instance method (`js.mock((Object) tpl)`); otherwise Java picks the static `mock(String)` and streaming, increments and custom placeholders all silently stop working.
- `MockJs.mockJson` returns compact single-line JSON, not indented JSON.
- Increment and ordered-selection state sits on the **parsed template object**: reuse the same `Map` to stay continuous; passing a `String` re-parses and restarts. `reset()` does not roll back values already written into the template.
- `setCount` is clamped to `[1, 10000]`; `MockDataGenerator` itself has no cap.
- `MockDataGenerator` uses a static `SecureRandom`; performance-sensitive code should switch to template mode with `new MockRandom(new Random())`.
- The `formatTo` family throws `IOException`; the `format` family wraps it in `IllegalStateException`.
- `MockCustomField` has no two-arg constructor, and its `rule` field does not affect generation.
- `MockJs.samples()` / `MockSchema.samples()` return template text, not generated data.
