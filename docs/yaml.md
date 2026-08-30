# YAML 模块使用指南

`com.alianga.jkit.yaml` 提供轻量的 YAML 解析与节点树模型，支持常用 YAML 语法、类型转换、路径查找、反向写出。核心入口为 `YamlDocument` 与 `YamlNode`，均零第三方依赖。

## 1. 解析

```java
import com.alianga.jkit.yaml.YamlDocument;
import com.alianga.jkit.yaml.YamlNode;

String yaml = "kind: ConfigMap\n"
        + "metadata:\n"
        + "  name: demo\n"
        + "  namespace: default\n";

// 字符串 / 字符数组
YamlDocument doc = YamlDocument.parse(yaml);
YamlDocument doc2 = YamlDocument.parse(yaml.toCharArray());

// 输入流 / 文件 / URL
YamlDocument fromStream = YamlDocument.read(inputStream);
YamlDocument fromFile = YamlDocument.read(new File("/tmp/config.yaml"));
YamlDocument fromUrl = YamlDocument.read(url);
```

## 2. 常用转换

```java
YamlDocument doc = YamlDocument.parse(yaml);

// 转 Map
Map<String, Object> map = doc.toMap();

// 转 Properties（. 分隔层级）
Properties properties = doc.toProperties();
properties.getProperty("metadata.name");

// 转实体 Bean
UserConfig user = YamlDocument.parse(yaml, UserConfig.class);
// 或者
UserConfig user2 = doc.toEntity(UserConfig.class);
```

实体绑定使用 setter 映射，支持标量、日期、集合、枚举、**嵌套对象**、对象列表，以及 `login_timeout` / `max-size` 到 `loginTimeout` / `maxSize` 的宽松匹配。

也可把已经构造好的嵌套 Map 交给 YAML 模块绑定：

```java
Server server = YamlDocument.toEntity(nestedMap, Server.class);
```

## 3. 节点树与路径查找

```java
YamlNode root = doc.getRoot();

// 按路径查找节点（/ 分隔）
YamlNode metadata = root.get("/metadata");
YamlNode nameNode = root.get("/metadata/name");

// 直接取值并转化类型
String kind = root.getPathValue("/kind", String.class);
String name = root.getPathValue("/metadata/name", String.class);

// 相对路径
String namespace = root.get("/metadata").getPathValue("namespace", String.class);

// 数组元素使用 [n]
String role = root.getPathValue("/roles/[1]", String.class);
```

## 4. 修改与反向写出

```java
// 修改叶子节点
root.setPathValue("/metadata/name", "new-name");

// 转 yaml 字符串
String out = doc.toYamlString();

// 写文件 / 流
doc.writeTo(new File("/tmp/test.yaml"));
doc.writeTo(outputStream);
```

## 5. 支持的特性

- **类型转换**：`!!int`、`!!float`、`!!bool`、`!!str`、`!!binary`、`!!timestamp` 等显式类型标记。
- **数组 / 内联 JSON**：`- item` 数组以及 `{a: 1}` / `[a, b]` 内联 JSON。
- **多文档**：以 `---` 分隔，`doc.isMultiple()` 判断，`doc.getYamlNodeList()` 获取各文档根节点。
- **锚点与引用**：`&anchor` 与 `*ref`、`<<` 合并。
- **文本块**：`|` / `|-` / `|+` 与 `>` / `>-` / `>+`。

```java
// 多文档
String multi = "name: doc1\n---\nname: doc2\n";
YamlDocument doc = YamlDocument.parse(multi);
doc.isMultiple();            // true
doc.getYamlNodeList().size(); // 2

// 锚点与引用
String anchored = "base: &base\n  name: zhangsan\n"
        + "server:\n  <<: *base\n  port: 8080\n";
Map<String, Object> map = YamlDocument.parse(anchored).toMap();
Map<String, Object> server = (Map<String, Object>) map.get("server");
server.get("name");  // zhangsan
```

## 6. 节点信息

```java
YamlNode node = root.get("/metadata/labels/addon");
node.leaf;             // 是否叶子节点
node.getValue();       // 叶子节点值
node.getFullKey();     // 完整 key，如 metadata.labels.addon
node.root();           // 回到根节点
node.parent();         // 父节点
node.isArray();        // 是否数组
```

## 注意事项

这些是解析器的边界，**不影响读取和绑定**；完整用例见 `YamlDocumentTest`、`YamlBindingTest`。

| 点 | 行为 |
| --- | --- |
| 缩进 | 只用空格。行首出现 tab 会抛 `YamlParseException`，避免被当成 key 的一部分 silently 解析错。 |
| 注释 | `#` 行注释和行尾注释在解析时丢弃，**回写不会还原注释**。 |
| 锚点 `&` / `*` / `<<` | **解析与 `toMap` / `toEntity` 正常**。带引用的文档 `toYamlString()` 不保证缩进与原文一致，不要用回写做锚点文件的 round-trip。 |
| 文本块 `\|` / `>` | 解析保留块内容；回写保持块结构，注释同样不会写回。 |
| 类名 | 对外入口就是 `YamlDocument`（文档）和 `YamlNode`（节点），与 JSON 模块的 `JSON` / `JSONNode` 对应。`YamlJSON` 只处理文档里的内联 `{a: 1}` / `[a, b]`，不是另一套 YAML 解析器。`YamlParser` / `YamlLine` / `YamlGeneral` 为包内实现，不必直接使用。 |

建议：读配置、绑定 Bean 用 `parse` / `toEntity` / `toMap`；只有无锚点、可接受丢失注释的简单文档才 `writeTo` / `toYamlString`。
