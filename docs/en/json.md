# JSON Module Guide

`com.alianga.jkit.json` provides complete JSON capabilities, with the public entry points concentrated in four classes: `JSON`, `JSONNode`, `JSONNodePath`, and `JSONSchema`. All APIs have zero third-party dependencies and are implemented on the JDK standard library.

## 1. Common Object Serialization and Deserialization

### 1.1 Serialization

```java
import com.alianga.jkit.json.JSON;

Map<String, Object> map = new HashMap<String, Object>();
map.put("msg", "hello, jkit json !");
map.put("name", "zhangsan");

// plain serialization
String result = JSON.toJsonString(map);
// {"msg":"hello, jkit json !","name":"zhangsan"}
```

Objects, collections, arrays, enums, `java.util.Date`, and common `java.time` types can all be serialized directly.

### 1.2 Deserialization

```java
String json = "{\"msg\":\"hello\",\"name\":\"zhangsan\"}";

// parse as a Map (LinkedHashMap by default)
Map<String, Object> map = (Map<String, Object>) JSON.parse(json);

// parse with a specified type
Map<String, Object> map2 = JSON.parseObject(json, Map.class);
User user = JSON.parseObject(json, User.class);
List<User> users = JSON.parseArray("[{\"name\":\"A\"}]", User.class);
```

`parseObject` / `parseArray` bind to entity classes; `parse` parses into Map/List/scalars; `parseAs` saves you the cast.

### 1.3 Reading and Writing Files and Streams

```java
JSON.writeJsonTo(obj, new File("/tmp/test.json"));        // write to a file
JSON.writeJsonTo(obj, new FileOutputStream(file));        // write to a stream
JSON.writeJsonTo(obj, writer);                            // write to a Writer

Map<String, Object> result = JSON.read(file, Map.class);  // read from a file
Map<String, Object> result2 = JSON.read(inputStream, Map.class); // read from a stream
Map<String, Object> result3 = JSON.read(url, Map.class);  // read from a remote URL
```

## 2. Serialization Formatting

Output format is controlled via `WriteOption` or `JSONConfig`.

```java
// formatted indentation (tab by default)
JSON.toJsonString(map, WriteOption.FormatOut);

// add a space after the colon
JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatOutColonSpace);

// indent with 4 spaces
JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatIndentUseSpace);

// indent with 8 spaces
JSON.toJsonString(map, WriteOption.FormatOut, WriteOption.FormatIndentUseSpace8);

// fine-grained control via JSONConfig (2-space indent)
JSONConfig config = JSONConfig.formatOf();
config.setFormatIndentUseSpace(true);
config.setFormatIndentSpaceNum(2);
JSON.toJsonString(map, config);
```

Common `WriteOption`s:

| Option | Description |
| --- | --- |
| `FormatOut` | formatted, indented output |
| `FormatOutColonSpace` | add a space after the colon |
| `FormatIndentUseSpace` / `FormatIndentUseSpace8` | space indentation |
| `CamelCaseToUnderline` | output camelCase properties as snake_case |
| `DateAsTime` | serialize dates as timestamps |
| `WriteEnumAsOrdinal` | output enums by ordinal |
| `FullProperty` | output null properties |
| `IgnoreNullProperty` | filter out null properties (default) |
| `BytesArrayToHex` / `BytesArrayToNative` | byte[] output style |
| `SkipCircularReference` | skip circular references |
| `NumberAsString` | output large numbers as strings |

## 3. Automatic CamelCase / snake_case Conversion

```java
class User {
    private String userName;
    private String firstName;
    // getter / setter omitted
}

User user = new User();
user.setUserName("tom");
user.setFirstName("Tom");

// camelCase by default: {"userName":"tom","firstName":"Tom"}
JSON.toJsonString(user);

// to snake_case: {"user_name":"tom","first_name":"Tom"}
JSON.toJsonString(user, WriteOption.CamelCaseToUnderline);
```

Deserialization accepts both camelCase and snake_case forms (`user_name` and `userName` both bind to the `userName` field):

```java
User u1 = JSON.parseObject("{\"userName\":\"tom\"}", User.class);
User u2 = JSON.parseObject("{\"user_name\":\"tom\"}", User.class); // works the same
```

## 4. Entity Class Binding

```java
String json = "{\"name\":\"Tom\",\"age\":18,\"tags\":[\"a\",\"b\"]}";
User user = JSON.parseObject(json, User.class);

// parse into an existing instance (avoids reflective creation overhead)
User target = new User();
JSON.parseToObject(json, target);

// parse into a specified collection
List<User> list = new ArrayList<User>();
JSON.parseToList("[{\"name\":\"A\"}]", list, User.class);
```

`@JsonProperty` annotation support:

```java
import com.alianga.jkit.json.annotations.JsonProperty;

class Bean {
    @JsonProperty(name = "alias_name")   // specify an alias
    private String aliasField;

    @JsonProperty(serialize = false)     // do not serialize
    private String skipOnWrite;

    @JsonProperty(deserialize = false)   // do not deserialize
    private String skipOnRead;
}
```

## 5. Custom Serialization and Deserialization

### 5.1 Type Mappers (Recommended)

`JSONTypeMapper` handles both serialization and deserialization, and takes effect globally once registered:

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

### 5.2 Field-Level Mappers

`JSONTypeFieldMapper` works with `@JsonProperty(mapper = ...)` and applies only to the annotated field:

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

### 5.3 Low-Level Serializers / Deserializers

```java
JSON.register(MyType.class, new JSONTypeSerializer() { ... });   // serialization only
JSON.register(MyType.class, new JSONTypeDeserializer() { ... }); // deserialization only
JSON.register(MyType.class, mapper);                             // both in one
```

## 6. JSONNode: On-Demand Parsing of a Node Tree

`JSONNode` suits on-demand access to large JSON documents: it scans once and only parses the paths you actually visit, without parsing the entire JSON.

```java
String json = "{\"name\":\"Tom\",\"dept\":{\"id\":7,\"name\":\"dev\"}}";
JSONNode root = JSONNode.parse(json);

// get a value by path (/ separated, array indexes as numbers)
String name = root.getPathValue("/name", String.class);
int id = root.getPathValue("/dept/id", int.class);

// locate a node
JSONNode dept = root.get("/dept");
JSONNode idNode = root.get("dept/id");

// read a value directly / convert its type
String s = root.getChildValue("name", String.class);
Map<String, Object> map = root.asMap();
List<Object> list = root.get("/tags").asList();

// convert to an entity / a collection
User user = root.toBean(User.class);
List<User> users = root.get("/list").toList(User.class);
```

### 6.1 Partial Extraction (from)

`from` returns as soon as the target path is scanned, making it more efficient than `parse`, and it supports lazy loading:

```java
JSONNode bookRoot = JSONNode.from(json, "/store/book");
JSONNode title = JSONNode.from(json, "/store/book/0/title");
// extract and convert the type directly
Double price = JSONNode.from(json, "/store/book/0/price", Double.class);
```

### 6.2 Node Modification

```java
root.setPathValue("/dept/name", "newdev");        // set a value by path
root.setChildValue("name", "Jerry");              // set a child value
root.setChildValue("extra", "v", true);           // create if absent
root.removeField("dept");                          // remove a field
root.get("/arr").removeElementAt(0);              // remove an array element
```

### 6.3 Aggregation and diff

```java
JSONNode arr = JSONNode.parse("[{\"price\":8.95},{\"price\":12.99}]");
arr.max("price");  // 12.99
arr.min("price");  // 8.95
arr.avg("price");  // average value

JSONNode[] diff = JSONNode.diff("{\"a\":1}", "{\"a\":2}");
```

## 7. JSON xpath Extraction

### 7.1 Wildcards and Indexes

```java
// extract all authors
List<JSONNode> authors = JSONNode.extract(json, "/store/book/*/author");

// a specific index
JSONNode author = JSONNode.extract(json, "/store/book/1/author").get(0);

// first 2 (index 1- is inclusive of 1)
List<JSONNode> top2 = JSONNode.extract(json, "/store/book/1-/author");

// starting from index 1 (1+ is inclusive of 1)
List<JSONNode> from1 = JSONNode.extract(json, "/store/book/1+/author");

// discrete indexes (built programmatically)
JSONNodePath path = JSONNodePath.create()
        .exact("store").exact("book").indexs(0, 2).exact("author");
List<JSONNode> selected = path.collect(JSONNode.parse(json));
```

### 7.2 Recursive Search (collect)

```java
// // recursively find all names
List<JSONNode> names = JSONNode.collect(json, "//name");

// recursion + index
List<JSONNode> authors = JSONNode.collect(json, "//book/1+/author");
```

### 7.3 Attribute Filtering

A filter expression follows a path segment in the form `*[expression]`; strings use single quotes:

```java
// author == 'Nigel Rees'
List<JSONNode> matched = JSONNode.collect(json, "/store/book/*[author == 'Nigel Rees']/title");
```

### 7.4 First/Last and Type Conversion

```java
String first = JSONNode.first(json, "/store/book/*/author", String.class);
String last = JSONNode.last(json, "/store/book/*/author", String.class);
String fallback = JSONNode.firstIfEmpty(json, "/none/*/x", String.class, "default");
```

## 8. JSON Schema Validation

```java
String schemaJson = "{\n"
        + "  \"type\": \"object\",\n"
        + "  \"properties\": {\n"
        + "    \"name\": {\n"
        + "      \"must\": true,\n"
        + "      \"type\": \"string\",\n"
        + "      \"rules\": [\n"
        + "        {\"expression\": \"value.indexOf('test') > -1\", \"message\": \"must contain test\"}\n"
        + "      ]\n"
        + "    },\n"
        + "    \"age\": {\"type\": \"number\", \"minimum\": 20, \"maximum\": 100},\n"
        + "    \"key\": {\"type\": [\"number\", \"boolean\"]}\n"
        + "  }\n"
        + "}";

JSONSchema schema = JSONSchema.of(schemaJson);
JSONSchemaResult result = schema.validate("{\"name\":\"test user\",\"age\":33,\"key\":false}");
result.isSuccess();   // true
result.getMessage();  // failure message
result.getPath();     // failure path
```

Supported constraint keywords:

| Keyword | Description |
| --- | --- |
| `type` | type: `object` / `array` / `string` / `number` / `integer` / `boolean` / `null`; an array of multiple types is supported |
| `must` | required (this implementation uses `must`, equivalent to the standard `required`) |
| `minimum` / `maximum` | numeric bounds |
| `exclusiveMinimum` / `exclusiveMaximum` | open intervals |
| `minLength` / `maxLength` | string length |
| `pattern` | string regex |
| `minItems` / `maxItems` | array length |
| `items` | array element schema |
| `enum` | value enumeration |
| `format` | `url` / `email` / `date` |
| `rules` | custom rules, supporting `regular` regex and `expression` expressions |
| `anyOf` / `allOf` / `oneOf` | combined validation |
| `disableExtra` | forbid undefined fields |

## 9. Other Capabilities

```java
// NDJSON (JSON Lines)
String nd = JSON.toNdJsonString(Arrays.asList(obj1, obj2));
List parsed = JSON.parseNdJson(nd);

// sorting
String sorted = JSON.sortJsonString("{\"b\":2,\"a\":1}");

// validate JSON well-formedness
boolean valid = JSON.validate(json);

// deep copy
User copy = JSON.cloneObject(user);

// non-standard JSON tolerance (comments, single quotes, unquoted keys, trailing commas)
JSON.parse(json, ReadOption.AllowComment,
        ReadOption.AllowSingleQuotes,
        ReadOption.AllowUnquotedFieldNames,
        ReadOption.AllowLastEndComma);
```

For complete test cases see `jkit-core/src/test/java/com/alianga/jkit/json/JSONTest.java`, `JSONNodeTest.java`, and `JSONSchemaTest.java`.
