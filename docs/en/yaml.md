# YAML Module Guide

`com.alianga.jkit.yaml` provides a lightweight YAML parser and node tree model, supporting common YAML syntax, type conversion, path lookup, and serialization back to YAML. The core entry points are `YamlDocument` and `YamlNode`, both with zero third-party dependencies.

## 1. Parsing

```java
import com.alianga.jkit.yaml.YamlDocument;
import com.alianga.jkit.yaml.YamlNode;

String yaml = "kind: ConfigMap\n"
        + "metadata:\n"
        + "  name: demo\n"
        + "  namespace: default\n";

// string / char array
YamlDocument doc = YamlDocument.parse(yaml);
YamlDocument doc2 = YamlDocument.parse(yaml.toCharArray());

// input stream / file / URL
YamlDocument fromStream = YamlDocument.read(inputStream);
YamlDocument fromFile = YamlDocument.read(new File("/tmp/config.yaml"));
YamlDocument fromUrl = YamlDocument.read(url);
```

## 2. Common Conversions

```java
YamlDocument doc = YamlDocument.parse(yaml);

// to Map
Map<String, Object> map = doc.toMap();

// to Properties (levels separated by .)
Properties properties = doc.toProperties();
properties.getProperty("metadata.name");

// to entity Bean
UserConfig user = YamlDocument.parse(yaml, UserConfig.class);
// or
UserConfig user2 = doc.toEntity(UserConfig.class);
```

Entity binding uses setter mapping and supports scalars, dates, collections, enums, **nested objects**, lists of objects, as well as lenient matching from `login_timeout` / `max-size` to `loginTimeout` / `maxSize`.

You can also hand an already-constructed nested Map to the YAML module for binding:

```java
Server server = YamlDocument.toEntity(nestedMap, Server.class);
```

## 3. Node Tree and Path Lookup

```java
YamlNode root = doc.getRoot();

// look up a node by path (separated by /)
YamlNode metadata = root.get("/metadata");
YamlNode nameNode = root.get("/metadata/name");

// get a value directly and convert its type
String kind = root.getPathValue("/kind", String.class);
String name = root.getPathValue("/metadata/name", String.class);

// relative path
String namespace = root.get("/metadata").getPathValue("namespace", String.class);

// array elements use [n]
String role = root.getPathValue("/roles/[1]", String.class);
```

## 4. Modification and Serialization Back to YAML

```java
// modify a leaf node
root.setPathValue("/metadata/name", "new-name");

// to a yaml string
String out = doc.toYamlString();

// write to a file / stream
doc.writeTo(new File("/tmp/test.yaml"));
doc.writeTo(outputStream);
```

## 5. Supported Features

- **Type conversion**: explicit type tags such as `!!int`, `!!float`, `!!bool`, `!!str`, `!!binary`, `!!timestamp`.
- **Arrays / inline JSON**: `- item` arrays plus `{a: 1}` / `[a, b]` inline JSON.
- **Multiple documents**: separated by `---`; use `doc.isMultiple()` to check and `doc.getYamlNodeList()` to get the root node of each document.
- **Anchors and references**: `&anchor` and `*ref`, plus `<<` merging.
- **Text blocks**: `|` / `|-` / `|+` and `>` / `>-` / `>+`.

```java
// multiple documents
String multi = "name: doc1\n---\nname: doc2\n";
YamlDocument doc = YamlDocument.parse(multi);
doc.isMultiple();            // true
doc.getYamlNodeList().size(); // 2

// anchors and references
String anchored = "base: &base\n  name: zhangsan\n"
        + "server:\n  <<: *base\n  port: 8080\n";
Map<String, Object> map = YamlDocument.parse(anchored).toMap();
Map<String, Object> server = (Map<String, Object>) map.get("server");
server.get("name");  // zhangsan
```

## 6. Node Information

```java
YamlNode node = root.get("/metadata/labels/addon");
node.leaf;             // whether it is a leaf node
node.getValue();       // leaf node value
node.getFullKey();     // full key, e.g. metadata.labels.addon
node.root();           // back to the root node
node.parent();         // parent node
node.isArray();        // whether it is an array
```

## Notes

These are the parser's boundaries and **do not affect reading and binding**; for complete examples see `YamlDocumentTest` and `YamlBindingTest`.

| Point | Behavior |
| --- | --- |
| Indentation | Spaces only. A tab at the start of a line throws a `YamlParseException`, preventing it from being silently mis-parsed as part of a key. |
| Comments | `#` full-line comments and trailing comments are discarded during parsing; **writing back does not restore comments**. |
| Anchors `&` / `*` / `<<` | **Parsing and `toMap` / `toEntity` work normally**. For documents containing references, `toYamlString()` does not guarantee the indentation matches the original; do not use write-back for round-tripping files with anchors. |
| Text blocks `\|` / `>` | Parsing preserves block content; write-back keeps the block structure, but comments are not written back either. |
| Class names | The public entry points are `YamlDocument` (document) and `YamlNode` (node), corresponding to `JSON` / `JSONNode` in the JSON module. `YamlJSON` only handles inline `{a: 1}` / `[a, b]` inside documents; it is not another YAML parser. `YamlParser` / `YamlLine` / `YamlGeneral` are package-internal implementations and do not need to be used directly. |

Recommendation: use `parse` / `toEntity` / `toMap` for reading configuration and binding Beans; use `writeTo` / `toYamlString` only for simple documents without anchors where losing comments is acceptable.
