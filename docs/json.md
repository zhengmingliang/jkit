# JSON 模块使用指南

`com.alianga.jkit.json` 提供完整的 JSON 能力，对外入口集中在 `JSON`、`JSONNode`、`JSONNodePath` 与 `JSONSchema` 四个类。所有 API 均为零第三方依赖，基于 JDK 标准库实现。

## 1. 常用对象序列化与反序列化

### 1.1 序列化

```java
import com.alianga.jkit.json.JSON;

Map<String, Object> map = new HashMap<String, Object>();
map.put("msg", "hello, jkit json !");
map.put("name", "zhangsan");

// 普通序列化
String result = JSON.toJsonString(map);
// {"msg":"hello, jkit json !","name":"zhangsan"}
```

对象、集合、数组、枚举、`java.util.Date` 以及 `java.time` 常见类型均可直接序列化。

### 1.2 反序列化

```java
String json = "{\"msg\":\"hello\",\"name\":\"zhangsan\"}";

// 解析为 Map（默认 LinkedHashMap）
Map<String, Object> map = (Map<String, Object>) JSON.parse(json);

// 指定类型解析
Map<String, Object> map2 = JSON.parseObject(json, Map.class);
User user = JSON.parseObject(json, User.class);
List<User> users = JSON.parseArray("[{\"name\":\"A\"}]", User.class);
```

`parseObject` / `parseArray` 用于实体类绑定；`parse` 用于解析为 Map/List/标量；`parseAs` 可省去强转。

### 1.3 读写文件与流

```java
JSON.writeJsonTo(obj, new File("/tmp/test.json"));        // 写文件
JSON.writeJsonTo(obj, new FileOutputStream(file));        // 写流
JSON.writeJsonTo(obj, writer);                            // 写 Writer

Map<String, Object> result = JSON.read(file, Map.class);  // 读文件
Map<String, Object> result2 = JSON.read(inputStream, Map.class); // 读流
Map<String, Object> result3 = JSON.read(url, Map.class);  // 读远程 URL
```

## 2. 序列化格式化

通过 `WriteOption` 或 `JSONConfig` 控制输出格式。

```java
// 格式化缩进（默认 tab）
JSON.toJsonString(map, WriteOption.FormatOut);

// 冒号后补空格
JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatOutColonSpace);

// 使用 4 个空格缩进
JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatIndentUseSpace);

// 使用 8 个空格缩进
JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatIndentUseSpace8);

// JSONConfig 精确控制（2 空格缩进）
JSONConfig config = JSONConfig.formatOf();
config.setFormatIndentUseSpace(true);
config.setFormatIndentSpaceNum(2);
JSON.toJsonString(map, config);
```

常用 `WriteOption`：

| 选项 | 说明 |
| --- | --- |
| `FormatOut` | 格式化缩进输出 |
| `FormatOutColonSpace` | 冒号后补一个空格 |
| `FormatIndentUseSpace` / `FormatIndentUseSpace8` | 空格缩进 |
| `CamelCaseToUnderline` | 驼峰属性转下划线输出 |
| `DateAsTime` | 日期序列化为时间戳 |
| `WriteEnumAsOrdinal` | 枚举按 ordinal 输出 |
| `FullProperty` | 输出 null 属性 |
| `IgnoreNullProperty` | 过滤 null 属性（默认） |
| `BytesArrayToHex` / `BytesArrayToNative` | byte[] 输出方式 |
| `SkipCircularReference` | 跳过循环引用 |
| `NumberAsString` | 大数按字符串输出 |

## 3. 驼峰 / 下划线自动转换

```java
class User {
    private String userName;
    private String firstName;
    // getter / setter 省略
}

User user = new User();
user.setUserName("tom");
user.setFirstName("Tom");

// 默认输出驼峰：{"userName":"tom","firstName":"Tom"}
JSON.toJsonString(user);

// 转下划线：{"user_name":"tom","first_name":"Tom"}
JSON.toJsonString(user, WriteOption.CamelCaseToUnderline);
```

反序列化时同时支持驼峰与下划线形式（`user_name` 与 `userName` 均可绑定到 `userName` 字段）：

```java
User u1 = JSON.parseObject("{\"userName\":\"tom\"}", User.class);
User u2 = JSON.parseObject("{\"user_name\":\"tom\"}", User.class); // 同样生效
```

## 4. 实体类解析绑定

```java
String json = "{\"name\":\"Tom\",\"age\":18,\"tags\":[\"a\",\"b\"]}";
User user = JSON.parseObject(json, User.class);

// 解析到已有实例（避免反射创建开销）
User target = new User();
JSON.parseToObject(json, target);

// 解析到指定集合
List<User> list = new ArrayList<User>();
JSON.parseToList("[{\"name\":\"A\"}]", list, User.class);
```

`@JsonProperty` 注解支持：

```java
import com.alianga.jkit.json.annotations.JsonProperty;

class Bean {
    @JsonProperty(name = "alias_name")   // 指定别名
    private String aliasField;

    @JsonProperty(serialize = false)     // 不序列化
    private String skipOnWrite;

    @JsonProperty(deserialize = false)   // 不反序列化
    private String skipOnRead;
}
```

## 5. 自定义序列化和反序列化

### 5.1 类型映射器（推荐）

`JSONTypeMapper` 同时负责序列化与反序列化，注册后全局生效：

```java
JSON.register(Point.class, new JSONTypeMapper<Point>() {
    @Override
    public Point readOf(Object value) throws Exception {
        String[] parts = String.valueOf(value).split(",");
        return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    @Override
    public JSONValue<?> writeAs(Point value, JSONConfig jsonConfig) throws Exception {
        return value == null ? null : JSONValue.of(value.x + "," + value.y);
    }
});

String out = JSON.toJsonString(new Point(3, 4));     // "3,4"
Point p = JSON.parseObject("\"10,20\"", Point.class);
```

### 5.2 字段级映射器

`JSONTypeFieldMapper` 配合 `@JsonProperty(mapper = ...)` 只作用于指定字段：

```java
class SecretBean {
    @JsonProperty(mapper = ReverseMapper.class)
    private String secret;
}

class ReverseMapper extends JSONTypeFieldMapper<String> {
    @Override
    public String readOf(Object value) throws Exception {
        return new StringBuilder(String.valueOf(value)).reverse().toString();
    }

    @Override
    public JSONValue<?> writeAs(String value, JSONConfig jsonConfig) throws Exception {
        return JSONValue.of(new StringBuilder(value).reverse().toString());
    }
}
```

### 5.3 底层序列化器 / 反序列化器

```java
JSON.register(MyType.class, new JSONTypeSerializer() { ... });   // 仅序列化
JSON.register(MyType.class, new JSONTypeDeserializer() { ... }); // 仅反序列化
JSON.register(MyType.class, mapper);                             // 二者合一
```

## 6. JSONNode：节点树按需解析

`JSONNode` 适合大文本 JSON 的按需访问：扫描一次、仅解析访问到的路径，无需解析完整 JSON。

```java
String json = "{\"name\":\"Tom\",\"dept\":{\"id\":7,\"name\":\"dev\"}}";
JSONNode root = JSONNode.parse(json);

// 通过路径获取值（/ 分隔，数组用下标）
String name = root.getPathValue("/name", String.class);
int id = root.getPathValue("/dept/id", int.class);

// 定位节点
JSONNode dept = root.get("/dept");
JSONNode idNode = root.get("dept/id");

// 直接取值 / 转类型
String s = root.getChildValue("name", String.class);
Map<String, Object> map = root.asMap();
List<Object> list = root.get("/tags").asList();

// 转实体 / 集合
User user = root.toBean(User.class);
List<User> users = root.get("/list").toList(User.class);
```

### 6.1 局部提取（from）

`from` 只扫描到目标路径即返回，比 `parse` 更高效，支持懒加载：

```java
JSONNode bookRoot = JSONNode.from(json, "/store/book");
JSONNode title = JSONNode.from(json, "/store/book/0/title");
// 提取并直接转化类型
Double price = JSONNode.from(json, "/store/book/0/price", Double.class);
```

### 6.2 节点修改

```java
root.setPathValue("/dept/name", "newdev");        // 按路径设值
root.setChildValue("name", "Jerry");              // 设子值
root.setChildValue("extra", "v", true);           // 不存在则创建
root.removeField("dept");                          // 删除字段
root.get("/arr").removeElementAt(0);              // 删除数组元素
```

### 6.3 聚合与 diff

```java
JSONNode arr = JSONNode.parse("[{\"price\":8.95},{\"price\":12.99}]");
arr.max("price");  // 12.99
arr.min("price");  // 8.95
arr.avg("price");  // 平均值

JSONNode[] diff = JSONNode.diff("{\"a\":1}", "{\"a\":2}");
```

## 7. JSON xpath 提取

### 7.1 通配与下标

```java
// 提取所有 author
List<JSONNode> authors = JSONNode.extract(json, "/store/book/*/author");

// 指定下标
JSONNode author = JSONNode.extract(json, "/store/book/1/author").get(0);

// 前 2 个（下标 1- 包含 1）
List<JSONNode> top2 = JSONNode.extract(json, "/store/book/1-/author");

// 从下标 1 开始（1+ 包含 1）
List<JSONNode> from1 = JSONNode.extract(json, "/store/book/1+/author");

// 离散下标（编程式构建）
JSONNodePath path = JSONNodePath.create()
        .exact("store").exact("book").indexs(0, 2).exact("author");
List<JSONNode> selected = path.collect(JSONNode.parse(json));
```

### 7.2 递归查找（collect）

```java
// // 递归查找所有 name
List<JSONNode> names = JSONNode.collect(json, "//name");

// 递归 + 下标
List<JSONNode> authors = JSONNode.collect(json, "//book/1+/author");
```

### 7.3 属性过滤

过滤表达式以 `*[表达式]` 形式紧跟路径片段，字符串使用单引号：

```java
// author == 'Nigel Rees'
List<JSONNode> matched = JSONNode.collect(json, "/store/book/*[author == 'Nigel Rees']/title");
```

### 7.4 首尾与类型转化

```java
String first = JSONNode.first(json, "/store/book/*/author", String.class);
String last = JSONNode.last(json, "/store/book/*/author", String.class);
String fallback = JSONNode.firstIfEmpty(json, "/none/*/x", String.class, "default");
```

## 8. JSON Schema 校验

```java
String schemaJson = "{\n"
        + "  \"type\": \"object\",\n"
        + "  \"properties\": {\n"
        + "    \"name\": {\n"
        + "      \"must\": true,\n"
        + "      \"type\": \"string\",\n"
        + "      \"rules\": [\n"
        + "        {\"expression\": \"value.indexOf('test') > -1\", \"message\": \"必须包含test\"}\n"
        + "      ]\n"
        + "    },\n"
        + "    \"age\": {\"type\": \"number\", \"minimum\": 20, \"maximum\": 100},\n"
        + "    \"key\": {\"type\": [\"number\", \"boolean\"]}\n"
        + "  }\n"
        + "}";

JSONSchema schema = JSONSchema.of(schemaJson);
JSONSchemaResult result = schema.validate("{\"name\":\"test user\",\"age\":33,\"key\":false}");
result.isSuccess();   // true
result.getMessage();  // 失败信息
result.getPath();     // 失败路径
```

支持的约束关键字：

| 关键字 | 说明 |
| --- | --- |
| `type` | 类型：`object` / `array` / `string` / `number` / `integer` / `boolean` / `null`，支持数组多类型 |
| `must` | 必填（本实现使用 `must`，等价于标准 `required`） |
| `minimum` / `maximum` | 数值边界 |
| `exclusiveMinimum` / `exclusiveMaximum` | 开区间 |
| `minLength` / `maxLength` | 字符串长度 |
| `pattern` | 字符串正则 |
| `minItems` / `maxItems` | 数组长度 |
| `items` | 数组元素 schema |
| `enum` | 类型枚举 |
| `format` | `url` / `email` / `date` |
| `rules` | 自定义规则，支持 `regular` 正则与 `expression` 表达式 |
| `anyOf` / `allOf` / `oneOf` | 组合校验 |
| `disableExtra` | 禁止出现未定义字段 |

## 9. 其它能力

```java
// NDJSON（JSON Lines）
String nd = JSON.toNdJsonString(Arrays.asList(obj1, obj2));
List parsed = JSON.parseNdJson(nd);

// 排序
String sorted = JSON.sortJsonString("{\"b\":2,\"a\":1}");

// 校验 JSON 合法性
boolean valid = JSON.validate(json);

// 深拷贝
User copy = JSON.cloneObject(user);

// 非标准 JSON 兼容（注释、单引号、无引号 key、尾逗号）
JSON.parse(json, ReadOption.AllowComment,
        ReadOption.AllowSingleQuotes,
        ReadOption.AllowUnquotedFieldNames,
        ReadOption.AllowLastEndComma);
```

完整测试用例见 `jkit-core/src/test/java/com/alianga/jkit/json/JSONTest.java`、`JSONNodeTest.java` 与 `JSONSchemaTest.java`。
