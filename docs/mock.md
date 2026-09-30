# Mock 数据生成模块

`com.alianga:jkit-mock` 是零第三方依赖的 Mock 数据生成模块，两套能力并存、共用同一套语料与同一个输出格式化器：

- **字段模式**（`MockDataProducer`）：选定字段类型批量造数，内置 30 种字段、5 个快速模板、支持自定义字段，一次生成可导出 **JSON / CSV / SQL INSERT / XML**。
- **模板模式**（`MockJs`）：完整实现 **Mock.js 规范**的数据模板定义（DTD）与数据占位符定义（DPD），含 75 个占位符、正则反向生成与 JSON Schema 反向生成。

语料（500 个常用汉字、66 英文姓 / 32 英文名、100 中文姓 / 23 中文名、263 个顶级域名、35 省 / 366 市 / 2595 县，以及公司、部门、职位、UA、MIME 等业务字典）统一收在 `MockDict`，两种模式共用，不重复维护。

<MavenBadge artifact="jkit-mock" />

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-mock</artifactId>
    <version>2.0.3</version>
</dependency>
```

源码与目标均为 JDK 8，**零第三方依赖**，但依赖同仓库的核心库 `com.alianga:jkit`（Maven 会一并带入，无需显式声明）。JPMS 模块名 `com.alianga.jkit.mock`。

## 1. 30 秒上手

```java
import com.alianga.jkit.mock.MockDataProducer;
import com.alianga.jkit.mock.MockOutputFormat;
import com.alianga.jkit.mock.MockTemplate;

import java.util.List;
import java.util.Map;

MockDataProducer producer = new MockDataProducer()
        .addFields(MockTemplate.USER.getFields())   // 姓名 / 邮箱 / 手机号 / 身份证号 / 性别 / 年龄 / 生日 / 地址
        .setCount(2);

List<Map<String, Object>> rows = producer.generate();

String json = producer.format(MockOutputFormat.JSON);  // JSON 数组，逐层缩进两空格
String csv  = producer.format(MockOutputFormat.CSV);   // 首行表头
String sql  = producer.format(MockOutputFormat.SQL);   // CREATE TABLE + INSERT INTO
String xml  = producer.format(MockOutputFormat.XML);   // <data><item id="N">
```

`addFields` / `addField` / `addCustomField` / `setCount` / `setSqlOptions` 全部返回 `this`，可以一路链下去。

## 2. 字段模式

### 2.1 内置字段类型（30 种）

`MockFieldType` 每项带 `getKey()`（导出时的列名 / JSON key）、`getLabel()`（中文名）与 `getCategory()`（分类）。

| 分类 | 字段（key） |
| --- | --- |
| 个人信息（8） | `name` 姓名、`email` 邮箱、`phone` 手机号、`idCard` 身份证号、`gender` 性别、`age` 年龄、`birthday` 生日、`address` 地址 |
| 商业数据（8） | `company` 公司名称、`department` 部门、`position` 职位、`salary` 薪资、`bankCard` 银行卡号、`creditCard` 信用卡号、`price` 价格、`currency` 货币 |
| 技术数据（14） | `uuid` UUID、`ip` IP地址、`mac` MAC地址、`userAgent` User Agent、`url` URL、`domain` 域名、`password` 密码、`token` Token、`color` 颜色值、`timestamp` 时间戳、`filename` 文件名、`mimeType` MIME类型、`boolean` 布尔值、`date` 日期 |

`MockFieldType.fromKey(String)` 按 key 反查枚举，未命中返回 `null`。

### 2.2 快速模板（5 个）

| 模板 | 名称 | 字段 |
| --- | --- | --- |
| `USER` | 用户信息模板 | name, email, phone, idCard, gender, age, birthday, address |
| `EMPLOYEE` | 员工信息模板 | name, email, phone, company, department, position, salary |
| `PRODUCT` | 商品信息模板 | name, price, currency, uuid, timestamp |
| `ORDER` | 订单信息模板 | uuid, name, email, phone, address, price, timestamp |
| `API` | API测试数据模板 | uuid, token, ip, userAgent, timestamp, boolean |

`getFields()` 返回不可变列表，可直接喂给 `addFields`。

### 2.3 自定义字段

```java
MockCustomField field = new MockCustomField("nickname", MockCustomFieldType.STRING, null);

MockDataProducer p = new MockDataProducer()
        .addField(MockFieldType.NAME)
        .addCustomField(field)
        .addCustomField(new MockCustomField("score", MockCustomFieldType.NUMBER, null))
        .setCount(2);
```

`MockCustomFieldType` 五种类型与取值规则：

| 类型 | key | 取值 |
| --- | --- | --- |
| `STRING` | string | 5–20 位字母数字串 |
| `NUMBER` | number | 1–1000 闭区间整数 |
| `BOOLEAN` | boolean | true / false |
| `DATE` | date | `yyyy-MM-dd`，2020-01-01 至今 |
| `ARRAY` | array | 1–5 个 5 位字母数字串 |

`MockCustomField` 只有无参构造与 `(name, type, rule)` 三参构造；第三参 `rule` 是给人看的说明文本，**不参与生成逻辑**。`fromKey` 未命中时返回 `STRING`。

### 2.4 记录内的一致性

`MockDataGenerator` 在 `beginRecord()` 之后生成的一条记录里，姓名 / 性别 / 生日 / 年龄 / 身份证号 / 地址互相推导，不会出现「身份证写着 1979 年、年龄却是 20 岁」：

```java
MockDataGenerator g = new MockDataGenerator();
g.beginRecord();
String name  = g.generateName();      // 萧帅
String idCard = g.generateIdCard();   // 440307197901119696
String birthday = g.generateBirthday(); // 1979-01-11
int age = g.generateAge();            // 47
String addr = g.generateAddress();    // 广东省深圳市龙岗区建设路53号
```

身份证号出生日期段与 `birthday` 一致、`age` 是按生日算的周岁、`address` 落在身份证区划码对应的省市。`MockDataProducer` 内部每条记录都会先 `beginRecord()`。

### 2.5 条数与随机源

- `setCount()` 钳制到 **[1, 10000]**（默认 10），超出不会报错，直接取边界。
- 随机源是 `SecureRandom`。`MockDataGenerator` 内部为静态 `SecureRandom`，外部无法替换；模板模式的 `MockRandom` 提供 `MockRandom(Random)` 与 `MockRandom(long seed)`，可用于复现同一批数据。

## 3. 模板模式（Mock.js 规范）

### 3.1 上手

```java
// 1. 静态便捷方法
Object data = MockJs.mock("{'list|3': [{'id|+1': 1, 'name': '@cname'}]}");
String json = MockJs.mockJson("{\"price|9-999.2\": 1}");   // {"price":148.06}

// 2. 实例方法（注意：参数是 Object，传 String 时要强转，否则会走静态重载）
MockJs js = new MockJs();
Object out = js.mock((Object) "{'code': 200, 'data|2': [{'id|+1': 1}]}");

// 3. 摊平成记录（自动下钻 {"list":[...]}），再导出 CSV / SQL / XML
List<Map<String, Object>> rows = js.mockRecords("{'list|3': [{'id|+1': 1}]}");
```

模板里的单引号会先被规范化成双引号（Mock.js 文档惯例用单引号），两种写法都可以。

**两个容易踩的坑**：

1. `js.mock("...")` 里参数是 `String`，Java 会优先匹配**静态** `mock(String)`，内部新建一个 `MockJs`，已注册的自定义占位符、自增状态、流式开关全部失效。要用实例方法必须写成 `js.mock((Object) "...")`，或者先 `JSON.parse` 成 `Map` / `List` 再传入。
2. `mockJson` 走 `JSON.toJsonString`，输出的是**紧凑单行**；要缩进两空格用 `MockDataFormatter.prettyJson(MockJs.mock(tpl))`。

### 3.2 数据模板定义（DTD）

规则写在属性名里，格式 `'name|rule': value`：

| 规则 | 字符串 | 数字 | 布尔 | 数组 | 对象 |
| --- | --- | --- | --- | --- | --- |
| `'name\|min-max'` | 重复 min–max 次 | 取 min–max 间整数 | 按 `min/(min+max)` 概率 | 重复拼接 min–max 次 | 随机取 min–max 个属性 |
| `'name\|count'` | 重复 count 次 | 取 count | 按概率 | 重复拼接 count 次 | 随机取 count 个属性 |
| `'name\|1'` | 原值 | 1 | 按概率 | 随机取 1 个元素 | 随机取 1 个属性 |
| `'name\|+step'` | — | 每次 += step | — | 顺序取下一个元素 | — |
| `'name\|min-max.dmin-dmax'` | — | 整数部分取 min–max，小数位 dmin–dmax 位 | — | — | — |

属性值写成 `/^1[3-9]\d{9}$/` 会走正则反向生成，写成 `@cname` 这类字符串会调用占位符。

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

生成结果（同一实例、同一随机源可复现）：

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

相对 Mock.js 的增强：原版不支持小数区间，`'price|9.9-999.2'` 会被解析成 `"price.2"` 这样的怪 key，这里会正常生成 9–999 之间、两位小数的数值。整数部分取到上界时回退 1（`99.xx < 100`），保证不越界的同时留住小数位。

**自增与顺序取值的状态挂在模板对象上**，不是挂在 `MockJs` 实例上：

```java
Object tpl = JSON.parse(MockJs.normalizeQuotes("{'data|2': [{'id|+1': 1}]}"));
MockJs js = new MockJs();
js.mock(tpl);   // {data=[{id=1}, {id=2}]}
js.mock(tpl);   // {data=[{id=3}, {id=4}]}   —— 复用同一个已解析对象才连续
js.mock("{'data|2': [{'id|+1': 1}]}");  // {data=[{id=1}, {id=2}]} —— 每次传字符串会重新 parse，回到起点
```

`js.reset()` 只清空顺序取值游标与 `@increment` 计数器，不会把模板里已被改写的值还原回去。

### 3.3 数据占位符定义（DPD）

`MockRandom` 注册了 **75 个占位符名**（含 7 个简写别名，主名 68 个），覆盖 Mock.js 全部十类并补了一组业务字段：

| 分类 | 占位符 |
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
| 中文 | `cparagraph` `csentence` `cword` `ctitle` `cfirst` `clast` `cname` |
| 业务字段 | `phone` `gender` `company` `department` `position` `salary` `bankCard` `creditCard` `currency` `mac` `userAgent` `password` `token` `timestamp` `fileName` `mime` |

最后一行是 Mock.js 规范之外的补充：字段模式原本能生成 MAC、银行卡、User-Agent 等，模板模式写不出来，现在两边共用同一套语料。

```java
MockRandom r = new MockRandom();
r.invoke("integer", 10, 20);        // 19
r.invoke("cname");                  // 杨艳
r.invoke("image", "200x100");       // http://dummyimage.com/200x100
r.invoke("pick", "a", "b", "c");    // a
r.invoke("float", 1, 10, 2, 4);     // 2.18
r.invoke("email", "alianga.com");   // zkai@alianga.com —— 传入域名则不再随机
```

要点：

- 占位符名**大小写不敏感**（`@CNAME` 等价于 `@cname`），未命中抛 `IllegalArgumentException`。
- Java 关键字做了改名：模板里写 `@boolean` / `@float`，但直接调 Java 方法时是 `bool()` / `floatValue()`。
- `invoke` 的 `number` 走的是 `integer`（不是浮点），`float` 才是浮点。
- `MockRandom.placeholderGroups()` 返回上述 12 个分组（只含主名），`sample(name)` 返回带典型参数的推荐写法（如 `@integer(1,100)`、`@city(true)`），未收录的返回 `"@" + name`。

### 3.4 自定义占位符

```java
MockRandom r = new MockRandom();
r.extend("employeeNo", (random, args) -> "E" + random.invoke("integer", 1000, 9999));
MockJs js = new MockJs(r);
js.mock((Object) "{'no':'@employeeNo'}");   // => {no=E6704}
```

重名时自定义实现优先，可覆盖内置占位符。`allPlaceholders()` 返回内置加已注册的全部名字（上例为 76）。回调接口是 `MockRandom.MockPlaceholder#apply(MockRandom, Object...)`。

### 3.5 正则反向生成

```java
MockRegex.generate("^1[3-9]\\d{9}$");     // 17684584134
MockRegex.generate("[A-Z]{3}\\d{4}");     // WYY2828
MockRegex.generate("(\\d)\\1\\1");        // 444
```

支持字面量、转义 `\d \D \w \W \s \S`、`.`、字符集与反向字符集、区间 `{m,n}`、量词 `* + ?`、分组（捕获 / 非捕获 / 前瞻）、选择 `|`、反向引用 `\1`。不参与生成的部分：`^ $ \b` 忽略；负向预查 `(?!...)` 整组产出空串；`{m,}` 会兜一个 `min + 3~7` 的上界而不是无限展开；反向字符集的候选池只覆盖可打印 ASCII。模板里写 `/.../` 时，正则体必须含元字符（否则当普通字符串），例如 `"zip": "100000"` 不会被当成正则。

## 4. JSON Schema 反向生成

`MockSchema` 由 JSON Schema 反推出一份符合约束的样例数据：

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

结果示例：

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

入口与语义：

| 入口 | 说明 |
| --- | --- |
| `MockSchema.mock(schemaJson)` / `mock(schemaJson, options)` | 静态便捷，每次新建实例，随机源不可控 |
| `MockSchema.mockJson(schemaJson)` | 静态，输出缩进两空格的 JSON（无带 options 的重载） |
| `new MockSchema(MockRandom).generate(...)` | 实例方法；**只有构造器能注入随机源**，传种子即可复现 |
| `mockMany(schemaJson, count[, options])` | 实例方法，逐条独立生成 |
| `MockSchema.samples()` | 4 个内置示例 Schema（用户 / 订单 / 商品 / 接口响应） |

字符串取值优先级：**`pattern`**（交 `MockRegex` 反向生成，失败才降级）→ **`format`**（`email` / `date-time` / `uri` / `uuid` / `ipv4` / `hostname` 等）→ **属性名语义推断**（`name` 得中文姓名、`email` 得邮箱、`phone` 得手机号、`createdAt` 得时间……）→ 随机串，最后统一收敛到 `minLength` / `maxLength`。数值尊重 `minimum` / `maximum` / `exclusiveMinimum` / `exclusiveMaximum` / `multipleOf`；只给单边界时按 `DEFAULT_NUM_SPAN = 1000` 推导另一侧，两侧都缺省才是 `[0, 100]`。

`MockSchemaOptions`（builder 风格）：

| 选项 | 默认 | 说明 |
| --- | --- | --- |
| `includeOptional` | `true` | 是否生成 `required` 之外的非必填属性 |
| `arrayItems(min, max)` | `1, 3` | 未声明 `minItems` / `maxItems` 时数组的条数区间 |
| `useDefaults` | `false` | 直接取 `default` 而不随机 |
| `maxDepth` | `8` | 嵌套与 `$ref` 递归深度上限 |

支持 `type`（含数组形式随机取一个）/ `properties` / `required` / `items`（含元组）/ `minItems` `maxItems` / `additionalProperties` / `enum` / `const` / `default` / `allOf` / `oneOf` `anyOf` / `$ref`。`$ref` 只解析文档内部引用（`#/definitions/*`、`#/$defs/*`、`#/components/schemas/*` 都走同一套路径下钻），**外部引用返回 null**；递归深度超限同样静默返回 null，不会抛异常。`if/then/else`、`not`、`patternProperties`、`uniqueItems`、`$schema` / `$id` 等未实现，出现在 Schema 里会被忽略。

## 5. 输出：JSON / CSV / SQL / XML

### 5.1 四种格式

```java
List<Map<String, Object>> rows = new MockDataProducer()
        .addFields(MockTemplate.USER.getFields()).setCount(2).generate();

MockDataFormatter.prettyJson(rows);                    // 逐层缩进两空格
MockDataFormatter.toJson(rows);                        // 每条记录压一行
MockDataFormatter.toCsv(rows);                         // 首行表头
MockDataFormatter.toSql(rows);                         // 默认选项的 SQL
MockDataFormatter.toXml(rows);                         // <data><item id="N">
MockDataFormatter.format(rows, MockOutputFormat.JSON); // 按枚举分发
MockDataFormatter.toRows(data);                        // 把 {"list":[{...}]} 下钻成记录列表
```

实际输出：

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

转义规则：SQL 里字符串用单引号包裹、内部 `'` 转义为 `''`，`null` 写 `NULL`；CSV 按 RFC 4180 给含逗号 / 引号 / 换行的字段加双引号、内部 `"` 转义为 `""`；XML 转义 `& < > " '`。

### 5.2 SQL 输出选项

```java
MockSqlOptions options = MockSqlOptions.builder()
        .batch(true)                              // 多行 VALUES 合并成一条 INSERT
        .quote(MockSqlOptions.Quote.BACKTICK)     // 标识符包裹方式
        .typeMode(MockSqlOptions.TypeMode.VARCHAR)
        .tableName("product")
        .createTable(false)                       // 不输出 CREATE TABLE
        .build();

producer.setSqlOptions(options);                  // 字段模式
MockDataFormatter.toSql(rows, options);           // 已有记录
```

| 选项 | 取值 | 默认 |
| --- | --- | --- |
| `typeMode` | `AUTO` 按首条记录的值推断 / `VARCHAR` 全部 `VARCHAR(255)` / `TEXT` 全部 `TEXT` | `AUTO` |
| `batch` | `true` 时单条 `INSERT ... VALUES` 后跟多组 `(...)` | `false` |
| `quote` | `NONE` / `BACKTICK` / `DOUBLE_QUOTE` | `NONE` |
| `tableName` | 表名 | `fake_data` |
| `createTable` | 是否输出建表语句 | `true` |

`AUTO` 的推断规则：整数 → `BIGINT`，浮点 → `DOUBLE`，布尔 → `BOOLEAN`，其余（含日期、UUID 等字符串）→ `VARCHAR(255)`。

### 5.3 流式与大批量

一次性生成 10 万条时把结果全攒在 `List` 里会撑爆内存，流式路径逐条生成、直接写出：

```java
MockJs js = new MockJs();
js.setStreaming(true);                       // 开启后 count >= 1000 的数组返回惰性容器 MockRepeat
StringBuilder out = new StringBuilder();
MockDataFormatter.formatTo(out,
        js.mock((Object) "{'list|50000': [{'id|+1': 1, 'name': '@cname'}]}"),
        MockOutputFormat.CSV, null);         // 5 万条约 44 万字符、200 ms 量级，不落完整列表

int n = new MockDataProducer().addFields(MockTemplate.USER.getFields())
        .setCount(5).formatTo(out, MockOutputFormat.CSV);   // 返回实际条数
```

`MockDataFormatter.formatTo` 与 `MockDataProducer.formatTo` 都声明 `throws IOException`；`producer.format(...)` 内部把它包成 `IllegalStateException`。`MockRepeat` 的构造是包级私有，只能通过开启流式拿到；它 `implements Iterable<Object>`，`size()` 不展开即可知总数，`MockDataFormatter` 的 `rowCount` / `forEachRow` / 四个写出方法都能直接消费它。

## 6. 校验与 Schema 推导

```java
String tpl = "{'list|2': [{'id|+1': 1,'name': '@cname'}]}";

MockValid.valid(tpl, "{\"list\":[{\"id\":1,\"name\":\"张三\"},{\"id\":2,\"name\":\"李四\"}]}");
// => []  空列表表示通过

MockValid.valid(tpl, "{\"list\":[{\"id\":1},{\"id\":2,\"name\":\"李四\"}]}");
// => [ROOT.list[0].name 缺少属性]

MockValid.toJsonSchema(tpl);
// => {type=object, properties={list={type=array, range={min=2, max=null}, items=[{type=object}]}}}
```

`valid` 检查属性是否缺失、类型是否匹配、数值与数组是否落在规则区间内（数组按「重复次数 = 元素数 / 模板长度」折算）。模板值是占位符字符串（含 `@` 或以 `/` 开头）时该字段跳过检查。重载 `valid(tpl, data, Progress)` 支持逐条回报进度并中途取消：`tick(total, current)` 返回 `false` 立即中止，`total` 未知时为 `-1`。

`toJsonSchema` 输出含 `type` / `properties` / `range` / `increment` 的结构，其中 `range` 与 `increment` 是**非标准关键字**，不能直接喂给标准校验器；`'name|1'` 会先降级成元素类型。

## 7. 类速查

| 类 | 作用 |
| --- | --- |
| `MockDataProducer` | 字段模式门面：装配字段 → `generate()` / `format()` / `formatTo()` |
| `MockDataGenerator` | 单字段值生成器，`generate(MockFieldType)` / `generateCustom(MockCustomField)` / `beginRecord()` |
| `MockFieldType` / `MockFieldCategory` | 30 种内置字段枚举 / 三个分类 |
| `MockTemplate` | 5 个快速模板 |
| `MockCustomField` / `MockCustomFieldType` | 自定义字段与 5 种类型 |
| `MockOutputFormat` | 输出格式：`JSON` / `CSV` / `SQL` / `XML` |
| `MockSqlOptions` | SQL 输出选项（类型模式 / 批量 / 引号 / 表名 / 建表） |
| `MockDataFormatter` | 静态格式化与流式写出，两种模式共用 |
| `MockJs` | Mock.js 模板引擎：`mock` / `mockJson` / `mockRecords` / `gen` / `samples` |
| `MockRandom` | 占位符生成器：`invoke(name, args...)` / `extend` / `placeholderGroups` |
| `MockRule` | `'name\|rule'` 规则解析 |
| `MockRegex` | 正则反向生成 |
| `MockDict` | 共享语料字典（含 `regionTree()` 三级行政区划） |
| `MockSchema` / `MockSchemaOptions` | JSON Schema 反向生成与选项 |
| `MockValid` | 模板校验与 JSON Schema 推导 |
| `MockRepeat` | 流式场景的惰性数组容器（包级构造） |

## 8. 注意事项

- 用实例方法时把模板强转成 `Object`（`js.mock((Object) tpl)`），否则 Java 会选中静态 `mock(String)`，流式、自增、自定义占位符全部失效。
- `MockJs.mockJson` 输出紧凑单行，不是缩进 JSON。
- 自增 / 顺序取值的状态在**已解析的模板对象**上：复用同一个 `Map` 才连续，每次传字符串会重新 parse 回起点。`reset()` 不还原已被改写的模板值。
- `setCount` 会被钳制到 `[1, 10000]`；`MockDataGenerator` 本身不设上限。
- `MockDataGenerator` 的随机源是静态 `SecureRandom`，性能敏感场景只能改用模板模式 + `new MockRandom(new Random())`。
- `formatTo` 系列抛 `IOException`；`format` 系列把它包成 `IllegalStateException`。
- `MockCustomField` 没有两参构造；`rule` 字段不参与生成。
- `MockJs.samples()` / `MockSchema.samples()` 返回的是模板文本，不是生成结果。
