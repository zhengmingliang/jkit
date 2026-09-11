# jkit

**纯 JDK、零第三方依赖的 Java 通用工具库。**

jkit 由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来，在保留原有工具能力的同时，**移除了全部第三方依赖**（Gson、Fastjson、OkHttp、Guava、Apache Commons CSV、Jsoup、SLF4J、Logback、Lombok 等），仅使用 JDK 标准库（含 `java.util.logging`、`javax.crypto`、`java.awt`、`java.net` 等）。

> 坐标：`com.alianga:jkit`
> 版本：`2.0.1`（[版本更新说明](CHANGELOG.md)）
> 编译目标：JDK 8+
> License：Apache License 2.0

## 特性

- **零第三方依赖**：`<dependencies>` 为空，引入本库不会向宿主应用传递任何外部依赖，也没有版本冲突问题。
- **日志零依赖**：内置基于 `java.util.logging` 的极简门面 `com.alianga.jkit.log.Log`，用法与 SLF4J 一致（`{}` 占位符、尾参 Throwable 输出堆栈）。
- **纯 JDK 重写**：CSV 读写、JSON 以外的全部工具均不依赖任何外部库。

## 引入

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit</artifactId>
    <version>2.0.1</version>
</dependency>
```

把 curl 转成 OkHttp / fetch / requests 等其它语言源码时，另加：

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-curl-codegen</artifactId>
    <version>2.0.1</version>
</dependency>
```

jkit 只解析和执行 curl；代码生成见 [jkit-curl-codegen/README.md](jkit-curl-codegen/README.md)。

需要消息通知（钉钉 / 企微 / 飞书 / Server酱 / Bark / 通用 Webhook / SMTP 邮件）时，另加：

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify</artifactId>
    <version>2.0.1</version>
</dependency>
```

Slack / Telegram / ntfy / 短信（阿里云、腾讯云、云片、华为云）在可选模块 `jkit-notify-extra`：

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify-extra</artifactId>
    <version>2.0.1</version>
</dependency>
```

用法见 [docs/notify.md](docs/notify.md)。

需要解析 / 格式化 / 抽表列 / 给 SELECT 补 LIMIT 时，另加：

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.1</version>
</dependency>
```

零依赖手写 SQL 解析器，对标 Druid SQL Parser 与 JSqlParser 的常用入口。用法见 [docs/sql.md](docs/sql.md)。

本仓库是多模块工程：根 POM 为 `jkit-parent`，运行时库在 `jkit-core`（发布坐标仍是 `com.alianga:jkit`），代码生成在 `jkit-curl-codegen`，消息通知在 `jkit-notify`，可选扩展渠道在 `jkit-notify-extra`，SQL 解析在 `jkit-sql`。根目录 `mvn test` 会构建全部模块。

本库接替 [ZmlTools](https://github.com/wuyongshi/ZmlTools)（`top.wuyongshi:ZmlTools`）。**新项目请只用上面的坐标。** 已经依赖 ZmlTools 的工程有两种迁法：

1. **直接改 POM**（推荐）把 `groupId` / `artifactId` 换成 `com.alianga:jkit:2.0.1`，并按本文档把 `top.wys.utils.*` 改为 `com.alianga.jkit.*`。
2. **Maven relocation**：发布 `relocated/zmltools/pom.xml`（`cd relocated/zmltools && mvn clean deploy -Ppublish`）。之后依赖 `top.wuyongshi:ZmlTools:2.0.0` 的构建会被 Maven 自动解析到 `com.alianga:jkit:2.0.0`，日志里会出现 relocation 提示。relocation POM 必须用**旧坐标的 Central 账号**发布；包名不会自动改写，源码仍要自己换 import。

## 快速开始

```java
import com.alianga.jkit.csv.CSVUtils;
import com.alianga.jkit.DateUtils;
import com.alianga.jkit.common.idgenerate.SnowFlakeIdWorker;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.log.Log;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class QuickStart {
    private static final Log log = Log.get(QuickStart.class);

    public static void main(String[] args) throws Exception {
        boolean blank = StringUtils.isBlank("  ");          // true
        String now = DateUtils.getNowDateTime();            // yyyy-MM-dd HH:mm:ss
        long id = SnowFlakeIdWorker.INSTANCE.nextId();      // 雪花 ID

        List<List<String>> rows = Arrays.asList(
                Arrays.asList("Tom", "18"),
                Arrays.asList("Lucy", "20"));
        CSVUtils.write(new File("target/users.csv"), rows, "name", "age");
        List<List<String>> back = CSVUtils.read(new File("target/users.csv"));

        log.info("id={}, now={}", id, now);
    }
}
```

## 功能总览

| 包 | 主要内容 |
| --- | --- |
| `com.alianga.jkit` | 字符串、日期、文件、随机数据、雪花 ID、校验、CSV、编码检测等基础工具 |
| `com.alianga.jkit.convert` | 对象、数字、布尔、日期和二进制转换 |
| `com.alianga.jkit.collection` | 数组、集合、Map、双向 Map、布尔值处理 |
| `com.alianga.jkit.math` | 数字运算和数字字符串处理 |
| `com.alianga.jkit.io` | 资源、文件类型、属性文件、文件监听（详见 [docs/io.md](docs/io.md)） |
| `com.alianga.jkit.crypto` | AES、DES、RSA、摘要、Base64、上下文混淆包装（`ContextObfuscator`） |
| `com.alianga.jkit.image` | 验证码、图片和 GIF 工具（java.awt） |
| `com.alianga.jkit.thread` | 线程池创建、执行和关闭 |
| `com.alianga.jkit.reflect` / `jdk` | 字段访问、Unsafe 和 JDK 辅助类 |
| `com.alianga.jkit.valid` | 参数和状态校验 |
| `com.alianga.jkit.log` | 零依赖日志门面（JUL 实现） |
| `com.alianga.jkit.http` | **HTTP 客户端**（纯 JDK）：GET/POST/JSON/上传下载、Cookie、代理；JDK 8 用 `HttpURLConnection`，JDK 11+ 用 `java.net.http.HttpClient`（详见 [docs/http.md](docs/http.md)） |
| `com.alianga.jkit.json` | **JSON 全套能力**（wast 迁移）：parse/toJsonString、POJO 映射、JSONNode、JSONPath、JSON Schema、校验、NDJSON、日期时间序列化（详见 [docs/json.md](docs/json.md)） |
| `com.alianga.jkit.yaml` | **YAML 解析与节点树**：parse/toMap/toEntity（含嵌套对象与对象列表）、路径查找、类型转换、锚点引用、多文档（详见 [docs/yaml.md](docs/yaml.md)） |
| `com.alianga.jkit.config` | **配置读取**：Spring Boot 优先级、profile、占位符、前缀绑定到对象、文件热加载（详见 [docs/config.md](docs/config.md)） |
| `com.alianga.jkit.expression` / `template` | **表达式求值与模板渲染**：运算符、内置函数、自定义函数、求值环境、表达式缓存、字符串模板（详见 [docs/expression.md](docs/expression.md)） |
| `com.alianga.jkit.csv` | **CSV 读写**：`CSVUtils`（字符串行）与 `CSV`/`CSVTable`（表格模型、按列名取值、列类型转换、POJO 映射），两个门面共用同一个解析器，都支持流式读写，详见 [docs/csv.md](docs/csv.md) |
| 其它 | convert / collection / log / valid / crypto / image / thread / reflect / 根包杂项工具，见 [docs/toolkit.md](docs/toolkit.md) |

## 使用指南

### 字符串、集合与类型转换

基础工具类均为静态方法，不需要初始化对象：

```java
boolean blank = StringUtils.isBlank("  ");
String value = blank ? "default" : "value";
int number = ConvertUtils.toInt("12", 0);
String joined = StringUtils.join(",", "a", "b");
```

`ConvertUtils` 支持数字、布尔值、日期、枚举和字符串之间的常用转换；无法转换时应使用带默认值的重载。`collection` 包提供数组、集合、Map、计数 Map 和栈等无状态工具。

### CSV

`CSVUtils` 使用 UTF-8 读写 CSV，支持表头、逗号、双引号、换行和 `""` 转义：

```java
List<List<String>> rows = Arrays.asList(
        Arrays.asList("Tom", "18"),
        Arrays.asList("Lucy", "20"));
CSVUtils.write(new File("users.csv"), rows, "name", "age");
List<Map<String, String>> users = CSVUtils.readWithHeader(new File("users.csv"));
```

需要其他字符集时使用 `write(file, charset, rows, headers)` 或 `read(file, charset)`。不存在的输入文件返回空列表；文件输出的父目录会自动创建。

大文件用流式 API，内存占用与文件大小无关（实测 107MB / 100 万行在 `-Xmx50m` 下读 582ms、峰值堆 29.8MB，而全量 `read` 会 OOM）：

```java
// 读成字符串行
CSVUtils.readStream(new File("big.csv"), new CSVValuesHandler() {
    @Override
    public boolean handle(List<String> values, long rowIndex) {
        return true;                     // 返回 false 提前终止
    }
});

CSVWriter writer = CSVUtils.writer(new File("out.csv"), "name", "age");
try {
    writer.writeRow(Arrays.asList("Tom", "18"));
} finally {
    writer.close();
}

// 结构化：首行当表头，回调里按列名取值或直接转实体
CSV.readStream(new File("big.csv"), "UTF-8", new CSVRowHandler() {
    @Override
    public boolean handle(CSVRow row, long rowIndex) {
        String name = row.get("name");
        return true;
    }
});
```

同包的 `CSV`、`CSVTable`、`CSVRow`、`@CSVColumn` 提供按列名取值、列类型转换和 POJO 映射（含流式的 `CSV.readStream` / `CSV.writer(...).writeObject(bean)`）。两个门面共用同一个解析器与转义实现，但默认编码、表头语义、字段空格与引号严格程度不同，对照表见 [docs/csv.md](docs/csv.md)。

> 2.0.0 起 `CSVUtils` 从 `com.alianga.jkit` 迁到 `com.alianga.jkit.csv`，方法签名和行为不变，只需改 import。

### HTTP

`HttpUtils` 零依赖，JDK 8 走 `HttpURLConnection`，JDK 11+ 走 `java.net.http.HttpClient`（HTTP/2、连接复用）：

```java
String html = HttpUtils.get("https://example.com");
String json = HttpUtils.postJson(url, Collections.singletonMap("id", 1));
String path = HttpUtils.download(fileUrl, "data.zip", "/tmp");
```

拦截器、总时长上限、重试抖动、重定向逐跳跟随（不丢 cookie）：

```java
HttpUtils.config()
        .setRetryPolicy(RetryPolicy.defaults())   // 3 次尝试 + 指数退避 + 抖动 + Retry-After
        .setTotalTimeoutMs(3000)                  // 覆盖重试/重定向/故障转移的总耗时
        .addInterceptor(chain -> chain.proceed(chain.request().header("X-Trace-Id", id())));

HttpResponse response = HttpRequest.get(url).totalTimeoutMs(800).execute();
response.elapsedMs();   // 端到端耗时
response.attempts();    // 实际发出的请求次数
```

完整用法（表单、上传、断点续传、SSE 自动合并、curl 解析与执行、WebSocket、代理）见 [docs/http.md](docs/http.md)。
curl 转其它语言源码见 [jkit-curl-codegen](jkit-curl-codegen/README.md)。
消息通知（核心钉钉/企微/飞书/Server酱/Bark/Webhook/SMTP；可选 Slack/Telegram/ntfy/短信见 `jkit-notify-extra`）见 [jkit-notify](docs/notify.md)。

### 负载均衡与服务发现

多上游端点调度、故障转移、熔断与服务发现，接入不需要改调用代码——URL 主机名写服务名即可：

```java
import com.alianga.jkit.http.lb.*;

EndpointPool pool = EndpointPool.builder()
        .serviceName("orders")                            // 只作用于 http://orders/...
        .discovery(new NacosDiscovery.Builder("10.0.0.1:8848")
                .namespaceId("prod").groupName("ORDER_GROUP").auth("nacos", "nacos").build())
        .refreshIntervalMs(10_000)
        .strategy(LoadBalanceStrategies.p2cLeastLoaded())
        .build();

HttpUtils.config().setEndpointPool(pool);
String body = HttpUtils.get("http://orders/api/v1/detail?id=1");
```

- 五种策略：P2C 最小负载（默认）、P2C+EWMA 延迟、nginx 平滑加权轮询、加权随机、一致性哈希
- 熔断按「连续失败 **或** 滑动窗口失败率」触发，冷却指数递增，半开只放一个探测请求
- 失败分类：只有连接失败 / 5xx / 429 算节点故障，业务 4xx 不会把健康节点熔断
- 服务发现增量刷新，保留端点健康状态；发现失败保留上一次快照（fail-static）
- 熔断、故障转移、端点上下线均有事件回调，可直接接告警

详见 [docs/http.md - 负载均衡与服务发现](docs/http.md)。

### 配置读取

`ConfigPropertyResolver` 按 Spring Boot 外部化配置优先级读取 `application.yml` / `{name}-{profile}.yml`，YAML 使用本库解析，无 Spring 依赖：

```java
import com.alianga.jkit.config.ConfigPropertyResolver;

ConfigPropertyResolver resolver = ConfigPropertyResolver.load();
String port = resolver.getString("server.port");
List<String> paths = resolver.getList("whitelist.paths", String.class);

// 非 Spring 应用：自定义配置名，或直接读若干文件
ConfigPropertyResolver app = ConfigPropertyResolver.load("my-app");
ConfigPropertyResolver files = ConfigPropertyResolver.loadFile("jdbc.yml");
```

`PropertiesUtil` 已标记 `@Deprecated`，请迁移到 `ConfigPropertyResolver`（后者是前者能力的超集，且文件解析已统一由它实现）。注意两者数据源不同：`PropertiesUtil` 读「按文件名单独缓存」的内容，`ConfigPropertyResolver` 读按 Spring Boot 优先级合并后的视图，迁移时需留意这一语义差异。仅当确实需要 `PropertiesUtil.update()` 的「保留注释回写 properties 文件」能力时才继续使用它。完整说明见 [docs/config.md](docs/config.md)。

### 表达式与模板

表达式引擎位于 `com.alianga.jkit.expression`，当前只使用纯 Java 解析模式，不依赖 javassist：

```java
Map<String, Object> context = new HashMap<String, Object>();
context.put("price", 12);
context.put("count", 3);
Object total = Expression.eval("price * count", context);
Object maximum = Expression.eval("@max(3, 9, 5)");
String text = Expression.renderTemplate("${name} is ${age}", context);
```

支持算术、比较、逻辑、三目、属性访问、集合访问及 `max`、`min`、`sum`、`avg`、`abs`、`sqrt`、`length`、`lower`、`upper`、`size`、`ifNull`、`isNull`、`toString`、`now`、`date_format`、`BigDecimal` 等内置函数。动态场景可先调用 `Expression.parse(expression)`，再重复使用返回的表达式对象。

自定义函数（`ExprFunction`）、求值环境与计算变量（`EvaluateEnvironment`）、解析缓存（`CacheableExpression`）、以及 `com.alianga.jkit.template` 的代码生成模板（`StringTemplate`、`StringTemplateManager`）见 [docs/expression.md](docs/expression.md)。

### ID、摘要与加密

```java
long id = IdGenerator.id();
String hexId = IdGenerator.hex();
long digest = Hash64.hash("cache-key");
byte[] encrypted = AESCrypt.encrypt(
        "hello".getBytes(StandardCharsets.UTF_8),
        "1234567890123456".getBytes(StandardCharsets.UTF_8));
```

`SnowFlakeIdWorker` 提供 64 位（默认）和 JavaScript 安全整数兼容的 53 位模式；生产环境建议显式配置 workerId 与 datacenterId，并通过 `expId` 反解。未显式配置时 workerId 由本机 IP 末段与**进程号**共同推导，同机多进程不会算出相同 workerId；进入新时间单位时序列号取随机起始值，避免低并发下 ID 尾数恒为 0、按 ID 取模分库分表出现倾斜。`Hash64` 是非密码学 FNV-1a 摘要，仅适合缓存、哈希表和路由，不得用于密码或签名。AES/DES 工具用于兼容性场景，密钥管理和随机 IV 应由业务负责。`ContextObfuscator` 是按调用方上下文派生密钥流的**混淆**包装（方法名即 `obfuscate` / `deobfuscate`），只用于提高逆向门槛，未经密码学评审、校验和也不是 MAC，**不得替代 AES**，详见 [docs/toolkit.md](docs/toolkit.md)。

### 文件、IO、反射与校验

- `FileUtils`、`IOUtils`、`com.alianga.jkit.io`：文件复制、资源读取、字节流、属性文件和文件类型识别（详见 [docs/io.md](docs/io.md)）。`FileUtils.getSize(long)` 按 1024 进制输出带单位文本，覆盖 B/KB/MB/GB/TB，负数按绝对值分档并保留负号，小数点固定为 `.` 而不随运行环境区域设置变化。
- `BeanUtils`、`ReflectionUtils`、`com.alianga.jkit.reflect`：JavaBean 属性拷贝、getter/setter 元数据和字段访问。
- `Preconditions`、`Verify`、`Assert`：参数、状态和断言校验，失败时抛出对应异常。
- `Log`：基于 JDK `java.util.logging` 的轻量日志门面，支持 `{}` 占位符和尾部 Throwable。
- `image`、`http`：验证码/图片编码；`HttpUtils` 为纯 JDK HTTP 客户端（详见 [docs/http.md](docs/http.md)）。
- 其它模块速览见 [docs/toolkit.md](docs/toolkit.md)。

#### 单一实现约定

同一能力在库内只保留一份实现，其余入口一律转调，避免出现「两份代码、两种行为」：

| 能力 | 唯一实现 | 转调入口 |
| --- | --- | --- |
| base64 编解码 | `Base64Utils`（整块转换按 JDK 版本自动选更快实现） | `EncryptUtils.base64*` |
| 16 进制转换 | `ByteUtils.toHexStringLower`（小写）/ `ByteUtils.toHexString`（大写） | `EncryptUtils.bytesToHexString`、`FileType.bytesToHexString` |
| 路径规范化 | `StringUtils.cleanPath` | `FileUtils.cleanPath` |
| `Content-Disposition` 文件名解析 | `HttpUtils.parseFileName` | `FileUtils.getFileNameFromHttp`、`HttpUtils.getFileName` |
| 配置文件解析 | `ConfigPropertyResolver.read` | `PropertiesUtil`（已废弃） |

**16 进制的大小写不能混用**：摘要（MD5/SHA/HMAC）和文件头识别依赖小写，请用 `toHexStringLower`；`toHexString` 保持历史的大写行为。

**base64 的整块转换是版本相关的**：JDK 给 `java.util.Base64` 陆续加了 JIT intrinsic，低版本上本库手写实现更快、高版本反之。实测在 JDK 8 上手写解码快 1.6~1.7 倍，而在 GraalVM 21 上 JDK 解码快 4 倍以上。因此 `Base64Utils.encodeToString` / `decode(String)` 会按运行时版本自动选择（编码只在 JDK 8 用手写，解码在 JDK 11 以下用手写），调用方无需关心；两条实现的等价性由 `Base64UtilsEquivalenceTest` 在 JDK 8/9/11/17/21 上验证。JSON 模块用的零拷贝 API（写入调用方缓冲区、从缓冲区范围解码）JDK 没有等价物，不参与切换。详见 [docs/toolkit.md](docs/toolkit.md)。

#### 两个易混淆的读值方法

`ReflectionUtils.getObjectFieldValue` 和 `ObjectUtils.getObjectFieldValue` 曾经同名同签名但查找规则不同，现已分别更名，旧名保留为 `@Deprecated` 转调：

- `ReflectionUtils.getDeclaredFieldValue(obj, fieldName)`：按**字段名精确匹配**，逐级遍历父类，不做缓存，读不到只有 getter 的计算属性。
- `ObjectUtils.getPropertyValue(target, propertyName)`：按**bean 属性名**查带缓存的 `ClassStrucWrap` 元数据，支持下划线别名，能读到无字段的计算属性。

注意后者在存在同名字段时会用 `Unsafe` 直读字段，**不会执行 getter 方法体**；只有没有对应字段的派生属性才会真正调用 getter。

### JSON、JSONNode 和 Schema

JSON 对外入口集中在 `JSON`：`parse` 用于解析为 Map/List/标量，`parseObject` 和 `parseArray` 用于类型绑定，`toJsonString` 用于序列化，`read`/`writeJsonTo` 用于文件和流。`JSONNode` 适合大文本的按需访问，路径使用 `/` 分隔：

```java
JSONNode root = JSONNode.parse("{\\"user\\":{\\"name\\":\\"Tom\\"}}");
String name = root.getPathValue("/user/name", String.class);
List<JSONNode> nodes = JSONNodePath.parse("//name").collect(root);
```

`JSONSchema.of(schemaJson)` 可创建 Schema 并调用 `validate`；`JSONReader` 支持流式 hook 解析，`JSONL` 支持逐行 JSON。日期时间模块覆盖 `java.time` 常见类型，非标准 JSON 行为（注释、单引号、无引号字段名、尾逗号）通过 `ReadOption` 显式开启。

## 使用限制与兼容性

- 源码和二进制目标为 **JDK 8**；JDK 9+ 可运行，但 JSON 的 Unsafe 快速路径受运行时模块权限影响，必要时调用 `JSONVmOptions.forceRequiredMemoryAlignment()`。
- 发布 jar 不携带运行时第三方依赖；JUnit 仅为测试作用域依赖。
- `sun.misc.Unsafe` 仅作为性能优化，业务代码不应直接依赖内部实现包 `json.internal`。
- 加密类、文件写入和网络辅助类不会替业务处理密钥、路径安全、超时及证书校验策略。

## 从 ZmlTools 迁移说明

Maven 坐标从 `top.wuyongshi:ZmlTools` 迁到 `com.alianga:jkit:2.0.0`。旧坐标的 relocation POM 在 `relocated/zmltools/`，发布后依赖 ZmlTools 2.0.0 会自动转到 jkit（包名仍需手工替换）。

### 已迁移（纯 JDK，包名 `top.wys.utils` → `com.alianga.jkit`）

字符串、日期、文件与 IO、集合、数字、加密、随机数据、雪花 ID、反射、校验、系统信息、线程池、文件监听、图片/验证码、编码检测等 80+ 个工具类全部迁移，并做了以下去依赖改造：

- **SLF4J → `com.alianga.jkit.log.Log`**：基于 `java.util.logging`，`{}` 占位符与 Throwable 尾参行为保持一致。
- **Guava → JDK 原生**：`Lists`/`Ints` 内联为 JDK 写法；`TypeUtils` 的 `BiMap` 改为双 HashMap 实现。
- **注解剥离**：移除 checkerframework / JetBrains / `javax.annotation` 的 `@Nullable`/`@NonNull`/`@NotNull`（纯编译期注解，不影响运行语义）。
- **Lombok 移除**：`ValidList` 手写 getter/setter 与 `equals/hashCode/toString`。
- **OkHttp 移除**：`FileUtils#getFileNameFromHttp` 改用 `HttpURLConnection`；`HttpUtils` 已用 `HttpURLConnection` / JDK 11+ `java.net.http.HttpClient` 重写，API 对齐原工具类。
- **CSV 重写**：`CSVUtils` 用纯 JDK IO 重写（支持引号内逗号/换行、`""` 转义、空行忽略、首行表头），API 简化为 `write/read/readWithHeader` 并新增流式 `readStream`/`writer`，不再返回 Commons CSV 的 parser 对象；2.0.0 起类迁到 `com.alianga.jkit.csv`，与 `CSV`/`CSVTable` 同包并共用解析器。
- **DataUtils 精简**：原类依赖 fastjson/Guava/OkHttp/Jsoup/Servlet，仅保留本库依赖的 `orEqualsIgnoreCase`、`getFirstNumber` 与新增的本机 IP 方法（`getLocalHostIP(s)`）。

### 未迁移（依赖第三方库，需重写或引入外部依赖）

| 类 | 依赖 | 说明 |
| --- | --- | --- |
| `http/Cookies`、`CookieStore`、`InMemoryCookieStore` | Servlet | 服务端 Cookie 读写，未迁移 |
| `JsoupUtils` | Jsoup | HTML 解析，可基于 `javax.xml` DOM + XPath 重写 |
| `log/LevelRangeFilter`、`log/RegexFilter` | Logback | logback 专用过滤器，随日志框架一并移除 |

> 说明：`HttpUtils`、`CookieJarImpl`、`HttpCallBack` 已用纯 JDK 重写；`DownloadParam`、`ProcessNotifyEvent`、`SSLSocketClient` 仍在 `com.alianga.jkit.http`。

### JSON 模块（迁移自 wast）

jkit 的 JSON 能力来自 [wast](https://github.com/wycst/wast)（Apache License 2.0，版权归 wangyunchao 所有，源码保留原许可头，详见 [NOTICE](NOTICE)）：

- 对外 API 位于 `com.alianga.jkit.json`：`JSON`、`JSONNode`、`JSONNodePath`、`JSONSchema`、`JSONValidator`、`JSONL`、`JSONReader`/`JSONWriter`、`JSONUtil` 等。
- 内部支撑类位于 `com.alianga.jkit.json.internal.*`（原 wast common 包，属实现细节，不建议直接使用）。
- **已剔除 javassist**：wast 的表达式 `compile` 模式依赖 javassist，jkit 版统一走纯 Java 的 `parse` 解释模式（`Expression.compile(...)` 已降级为 `parse(...)`），JSONPath 过滤、JSON Schema 规则等依赖表达式的功能不受影响。
- 零第三方依赖：除 JDK 外无任何依赖（含 `javax.tools` 内存编译、`sun.misc.Unsafe` 加速均来自 JDK 自身）。

```java
import com.alianga.jkit.json.JSON;
import com.alianga.jkit.json.JSONNode;

String json = "{\"name\":\"Tom\",\"age\":18}";
Map<String, Object> map = (Map<String, Object>) JSON.parse(json);
User user = JSON.parseObject(json, User.class);          // POJO 映射
String out = JSON.toJsonString(user);                    // 序列化
JSONNode node = JSONNode.parse(json);
JSONNode id = node.get("dept/id");                      // 路径查询
```

> 原 ZmlTools 的 `GsonTools`/`FastJsonTools`（Gson/Fastjson）不再迁移，由 `com.alianga.jkit.json` 替代。

## 构建与测试

```bash
# 使用环境变量提供的 JDK 8（JAVA8_HOME 或 JDK8_HOME）
export JAVA_HOME="${JAVA8_HOME:-${JDK8_HOME}}"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

# 编译并打包（零依赖，构建很快；不含 javadoc/sources jar，也不会签名）
mvn package

# 运行单元测试（JUnit 4，test 作用域，不影响发布产物）
mvn test

# 安装到本地仓库
mvn install

# checkstyle（默认 skip，按需开启）
mvn checkstyle:check -Dcheckstyle.skip=false -Dcheckstyle.violationSeverity=error

# 试打发布产物：额外产出 javadoc jar、sources jar，不签名不上传
mvn clean package -Ppublish -Dgpg.skip=true

# 发布到 Maven Central（需 settings.xml 中的 Portal Token 与 GPG）
# mvn clean deploy -Ppublish
#
# GPG 口令走 gpg-agent；若口令放在 settings.xml 的 release profile 属性里，
# 由于该 profile 已不再默认激活，需要一起激活：mvn clean deploy -Ppublish,release
```

发布相关的插件（javadoc jar、sources jar、git 信息、GPG 签名、central-publishing 上传）
全部收在 `publish` profile 里，平时的 `package` / `install` 不会触发。profile 特意**不叫
`release`**：很多机器的 `~/.m2/settings.xml` 里有全局 `<activeProfile>release</activeProfile>`，
那样会每次构建都去签名，而且它一激活就会让 `activeByDefault` 的 profile 整体失效。

单元测试位于 `jkit-core/src/test/java`，覆盖：字符串/转换/类型映射（`StringUtilsTest`、`ConvertUtilsTest`、`TypeUtilsTest`）、CSV 读写往返与流式读写（`csv/CSVUtilsTest`、`csv/CSVReadTest`、`csv/CSVStreamTest`、`csv/CSVTableStreamTest`，含引号、多行字段、空字段、空行、块边界与百万行流式）、Base64 与 FNV-1a（`Base64CompareTest`、`Hash64Test`）、表达式（`ExpressionTest`）、雪花 ID（`SnowFlakeIdWorkerTest`）、日志门面（`LogTest`）、`DataUtils`/`ValidList`，JSON/YAML 模块核心能力：`json/JSONTest`、`json/JSONNodeTest`、`json/JSONSchemaTest`、`yaml/YamlDocumentTest`、`yaml/YamlBindingTest`（嵌套对象/对象列表绑定、tab 缩进拒绝、注释丢弃），以及配置读取：`config/ConfigPropertyResolverTest`（Spring Boot 优先级、profile 覆盖、前缀绑定到对象、自定义配置名、占位符）。

> JUnit 仅以 `test` 作用域引入，不会进入发布的 jar/pom，零依赖承诺不受影响。

## License

[Apache License 2.0](LICENSE)
