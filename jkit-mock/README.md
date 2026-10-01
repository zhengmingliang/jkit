# jkit-mock

零第三方依赖的 Mock 数据生成模块，两套能力并存：

1. **字段模式**（`MockDataProducer`）：内置 30 种字段类型、5 个快速模板、自定义字段，一次生成可导出 **JSON / CSV / SQL INSERT / XML** —— 1:1 对齐 FeHelper「数据 Mock」Chrome 插件。
2. **模板模式**（`MockJs`）：完整实现 **Mock.js 规范**，支持数据模板定义（DTD）与数据占位符定义（DPD），含 58 个占位符与正则反向生成。
3. **本地 Mock HTTP 服务**（`server.MockHttpServer`，2.0.4 起）：零依赖本地 Mock 服务，方法 + 路径模板匹配、延迟、动态 Schema、请求体回显、CORS、热更新与请求日志。

两套能力共用同一套输出格式化器，模板生成的结果同样能直接导出四种格式。

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-mock</artifactId>
    <version>2.0.4</version>
</dependency>
```

JDK 8 源码与目标，零第三方依赖（仅 `com.alianga:jkit`）。

## 30 秒上手

```java
MockDataProducer producer = new MockDataProducer()
        .addFields(MockTemplate.USER.getFields())   // 姓名 / 邮箱 / 手机号 / 性别 / 年龄 / 地址
        .setCount(10);

List<Map<String, Object>> rows = producer.generate();

String json = producer.format(MockOutputFormat.JSON);  // JSON 数组
String csv  = producer.format(MockOutputFormat.CSV);   // 首行表头
String sql  = producer.format(MockOutputFormat.SQL);   // CREATE TABLE + INSERT INTO
String xml  = producer.format(MockOutputFormat.XML);   // <data><item id="N">
```

## Mock.js 模板引擎

### 30 秒上手

```java
// 1. 静态方法，一行生成
Object data = MockJs.mock("{'list|3': [{'id|+1': 1, 'name': '@cname'}]}");
String json = MockJs.mockJson("{\"price|9-999.2\": 1}");

// 2. 实例方法，保留自增 / 顺序取值的上下文
MockJs js = new MockJs();
String out = MockDataFormatter.format(
        js.mock("{'code': 200, 'data|5-10': [{'id|+1': 1}]}"), MockOutputFormat.JSON);

// 3. 摊平成记录（自动下钻 {"list":[...]}），再导出 CSV / SQL / XML
List<Map<String, Object>> rows = js.mockRecords("{'list|3': [{'id|+1': 1}]}");
```

模板里的单引号会先被规范化成双引号（Mock.js 文档惯例用单引号），因此两种写法都可以。

### 数据模板定义（DTD）

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
  "list|5-10": [{
    "id|+1": 1,
    "name": "@cname",
    "phone": "/^1[3-9]\\d{9}$/",
    "gender|1": ["男", "女"],
    "score|0-100.1-2": 1,
    "vip|1-9": true
  }]
}
```

**扩展点**：Mock.js 原版不支持小数区间，`'price|9.9-999.2'` 会被解析成奇怪的 `"price.2"`。这里做了增强，`9.9-999.2` 会正常生成 9–999 之间、2 位小数的数值。

### 数据占位符定义（DPD）

`MockRandom` 内置 74 个占位符（含 `bool` / `int` / `number` / `char` / `str` / `img` / `inc` 等别名），覆盖 Mock.js 全部十类：

| 分类 | 占位符 |
| --- | --- |
| Basic | `boolean` `natural` `integer` `float` `character` `string` `range` |
| Date | `date` `time` `datetime` `now` |
| Image | `image` `dataImage` |
| Color | `color` `hex` `rgb` `rgba` `hsl` |
| Text | `paragraph` `sentence` `word` `title` |
| Name | `first` `last` `name` |
| Web | `url` `domain` `protocol` `tld` `email` `ip` |
| Address | `region` `province` `city` `county` `zip` |
| Helper | `capitalize` `upper` `lower` `pick` `shuffle` |
| Misc | `guid` `uuid` `id` `increment` |
| 中文 | `cparagraph` `csentence` `cword` `ctitle` `cfirst` `clast` `cname` |
| 业务字段 | `phone` `gender` `company` `department` `position` `salary` `bankCard` `creditCard` `currency` `mac` `userAgent` `password` `token` `timestamp` `fileName` `mime` |

最后一行是 Mock.js 规范之外的补充：字段模式原本能生成 MAC、银行卡、User-Agent 等，而模板模式写不出来，现在两边共用同一套语料与实现。

占位符可带参数，写在括号内：`@integer(10, 20)`、`@string('lower', 8)`、`@float(1, 10, 2, 4)`、`@pick(['a','b','c'])`、`@image('200x100')`、`@now('year')`。单独使用（整个字符串就是一个占位符）时返回原始类型，混在文本里则替换成字符串：

```java
MockRandom r = new MockRandom();
r.invoke("integer", 10, 20);        // Long
r.invoke("cname");                  // 中文姓名
r.invoke("image", "200x100");       // http://dummyimage.com/200x100
r.invoke("pick", "a", "b", "c");    // 随机取一个
```

语料（500 汉字、66 英文姓、32 英文名、100 中文姓、23 中文名、263 个 TLD、35 省 / 366 市 / 2595 县，以及公司 / 部门 / 职位 / UA / MIME 等业务字典）统一收在 `MockDict`，字段模式与模板模式共用，不重复维护。

### 自定义占位符

`MockRandom.extend()` 对应 Mock.js 的 `Random.extend()`，可注册自己的占位符：

```java
MockRandom r = new MockRandom();
r.extend("employeeNo", (random, args) -> "E" + random.invoke("integer", 1000, 9999));
MockJs js = new MockJs(r);
js.mock((Object) "{'no':'@employeeNo'}");   // => {no=E3821}
```

重名时自定义实现优先，可覆盖内置占位符。注意 `MockJs.mock(String)` 是**静态**便捷方法，内部新建实例，会丢掉已注册的扩展；需要 `extend` 时用实例方法 `mock((Object) template)`。`allPlaceholders()` 返回内置加已注册的全部占位符名。

### 正则反向生成

`MockRegex.generate()` 支持字面量、转义 `\d \D \w \W \s \S`、`.`、字符集与反向字符集、区间 `{m,n}`、量词 `* + ?`、分组（捕获 / 非捕获 / 前瞻）、选择 `|`、反向引用 `\1`；`^ $ \b` 生成时忽略。

```java
MockRegex.generate("^1[3-9]\\d{9}$");     // 15117833529
MockRegex.generate("[A-Z]{3}\\d{4}");     // XTL8829
MockRegex.generate("(\\d)\\1\\1");        // 777
```

### 校验与 Schema 推导

```java
List<String> errors = MockValid.valid(template, data);  // 空列表表示通过
Map<String, Object> schema = MockValid.toJsonSchema(template);
```

`valid` 会检查属性是否缺失、类型是否匹配、数值与数组是否落在规则区间内（数组按「重复次数」折算）。`toJsonSchema` 输出含 `type` / `properties` / `range` / `increment` 的 JSON Schema，其中 `'name|1'` 会降级为元素类型。

## 本地 Mock HTTP 服务（2.0.4 起）

`com.alianga.jkit.mock.server` 包提供一个零依赖的本地 Mock HTTP 服务：纯 JDK `com.sun.net.httpserver` 实现，不依赖 JavaFX 与任何第三方库，可嵌入桌面工具、单测或脚本。

```java
MockHttpServer server = new MockHttpServer();
server.start(18081, MockRoute.demoRoutes());   // 立即可用：/health、/api/hello、POST /api/echo

// 业务路由：路径参数、固定延迟、动态 Schema、请求体回显
List<MockRoute> routes = Arrays.asList(
        new MockRoute("GET", "/users/{id}", 200, 120,
                "application/json; charset=utf-8", "{\"id\":1}"),
        new MockRoute("GET", "/order", 200, 0,
                "application/json; charset=utf-8", "", orderSchema, true),  // dynamicSchema：每次请求重新生成
        new MockRoute("POST", "/api/echo", 200, 0,
                "application/json; charset=utf-8", "", "", false, true));   // echo：原样回写请求体
server.setRoutes(routes);                       // 运行中热更新，无需重启

server.addLogListener(log -> System.out.println(log));  // 引擎线程回调
server.stop();                                  // 释放端口，中断延迟等待
```

要点：

- **匹配**：方法（`*` 任意）+ 路径模板（`{name}` 匹配任意单段）；路径先 percent-decode 再匹配，含中文 / 空格的请求路径也能命中；`+` 按字面量处理（不当空格）。
- **响应**：状态码、固定延迟（毫秒）、任意 Content-Type、固定 body、Schema 动态生成（失败回落固定 body 或 `schema mock failed` 错误 JSON）。
- **CORS**：所有响应带 `Access-Control-Allow-Origin: *` 等三个头；未命中 OPTIONS 路由的预检请求自动 204。
- **健壮性**：空路由表即全 404（不回落演示路由）；状态码超出 100–599 的路由按 500 兜底返回错误 JSON；handler 顶层兜底异常，客户端提前断开 / 服务停止打断延迟的请求同样留下日志。
- **日志**：环形保留最近 200 条（`MockRequestLog`），query / 请求体 / 响应体按 2048 字符截断；`snapshotLogs()` / `clearLogs()` / `addLogListener()` 三种消费方式。
- 线程模型：每个请求一个 daemon 线程（缓存池），路由表为 volatile 不可变快照，热更新无锁。

## 核心 API

| 类 | 作用 |
| --- | --- |
| `server.MockHttpServer` | 本地 Mock HTTP 服务：启停、热更新路由、CORS、请求日志（2.0.4 起） |
| `server.MockRoute` | 一条路由定义：方法 / 路径模板 / 状态码 / 延迟 / Content-Type / body / Schema / 回显 |
| `server.MockRequestLog` | 一次入站请求的日志条目（内容已按长度截断） |
| `MockDataProducer` | 字段模式门面：装配字段 → `generate()` 得 `List<Map>`，`format()` 得字符串 |
| `MockDataGenerator` | 单字段值生成器，`generate(MockFieldType)` / `generateCustom(MockCustomField)` |
| `MockDataFormatter` | 静态格式化：`toJson` / `toCsv` / `toSql` / `toXml` / `format(Object, MockOutputFormat)` / `toRows(Object)` |
| `MockFieldType` | 30 种内置字段枚举，带 `key` / `label` / `category` |
| `MockFieldCategory` | 字段分类：个人信息 / 商业数据 / 技术数据 |
| `MockTemplate` | 快速模板：`USER` / `EMPLOYEE` / `PRODUCT` / `ORDER` / `API` |
| `MockCustomField` + `MockCustomFieldType` | 自定义字段（字符串 / 数字 / 布尔 / 日期 / 数组） |
| `MockOutputFormat` | 输出格式：`JSON` / `CSV` / `SQL` / `XML` |
| `MockJs` | Mock.js 模板引擎：`mock` / `mockJson` / `mockRecords` / `samples` |
| `MockRandom` | 58 个占位符生成器，`invoke(name, args...)` 调用 |
| `MockRule` | `'name\|rule'` 规则解析 |
| `MockRegex` | 正则反向生成 |
| `MockValid` | 模板校验与 JSON Schema 推导 |

### 字段类型

- **个人信息**：姓名、邮箱、手机号、身份证号、性别、年龄、生日、地址
- **商业数据**：公司名称、部门、职位、薪资、银行卡号、信用卡号、价格、货币
- **技术数据**：UUID、IP 地址、MAC 地址、User Agent、URL、域名、密码、Token、颜色值、时间戳、文件名、MIME 类型、布尔值、日期

### 快速模板

| 模板 | 字段 |
| --- | --- |
| `USER` 用户信息 | name, email, phone, gender, age, address |
| `EMPLOYEE` 员工信息 | name, email, phone, company, department, position, salary |
| `PRODUCT` 商品信息 | name, price, currency, uuid, timestamp |
| `ORDER` 订单信息 | uuid, name, email, phone, address, price, timestamp |
| `API` API 测试数据 | uuid, token, ip, userAgent, timestamp, boolean |

### 自定义字段

```java
MockDataProducer p = new MockDataProducer()
        .addField(MockFieldType.NAME)
        .addCustomField(new MockCustomField("nickname", MockCustomFieldType.STRING))
        .addCustomField(new MockCustomField("score", MockCustomFieldType.NUMBER))
        .setCount(5);
```

各类型取值规则：字符串 5–20 位随机串、数字 1–1000、布尔 true/false、日期 2020-01-01 至今、数组 1–5 个 5 位随机串。

## 输出示例

`SQL` 会先建表再插入，表名固定 `fake_data`，列统一 `VARCHAR(255)`：

```sql
-- 表结构
CREATE TABLE fake_data (
  name VARCHAR(255),
  email VARCHAR(255)
);

-- 数据插入
INSERT INTO fake_data (name, email) VALUES ('郑娜', 'VlZrQhGq@qq.com');
```

字符串里的 `'` 转义为 `''`；CSV 中逗号与引号按 RFC 4180 用双引号包裹、`"` 转义为 `""`；XML 转义 `& < > " '`。

## 数量限制

`setCount()` 内部钳制到 `[1, 10000]`（`MockDataGenerator` 本身不设限）。随机源为 `SecureRandom`。

## 测试

```bash
mvn -pl jkit-mock test -DskipTests=false
# Tests run: 83, Failures: 0, Errors: 0, Skipped: 0
```

覆盖 `MockJsTest`（25 个，DTD 规则 / 占位符 / 正则 / 单引号兼容）、`MockRandomTest`（26 个，占位符全量取值、格式断言与 extend 扩展点）、`MockDataFormatterTest`（8 个，两种模式 JSON 风格一致、摊平下钻）、`MockRegexTest`（12 个，正则反向生成）、`MockValidTest`（6 个，校验与 Schema）、`MockDataProducerTest`（6 个，字段模式）。

## 输出风格

两种模式的 JSON 都走 `MockDataFormatter.prettyJson()`：对象与数组逐层缩进两空格，字符串按 JSON 规范转义。`toRows()` 会把 `{"list":[{...}]}` 下钻成记录列表，供 CSV / SQL / XML 使用。
