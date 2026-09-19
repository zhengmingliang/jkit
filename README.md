<div align="center">

# jkit

**纯 JDK、零第三方依赖的 Java 工具库 —— 一个坐标，带走字符串、JSON、YAML、HTTP、SQL 解析、HTML 抽取、消息通知。**

[![Maven Central](https://img.shields.io/maven-central/v/com.alianga/jkit?style=flat-square)](https://central.sonatype.com/artifact/com.alianga/jkit)
[![JDK](https://img.shields.io/badge/JDK-8%2B-orange?style=flat-square)](https://jkit.alianga.com/guide/getting-started)
[![Tests](https://img.shields.io/badge/tests-2906%20passing-brightgreen?style=flat-square)](#-质量保障)
[![Dependencies](https://img.shields.io/badge/runtime%20deps-0-success?style=flat-square)](#-为什么选-jkit)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-jkit.alianga.com-ea580c?style=flat-square)](https://jkit.alianga.com)

**简体中文** · [English](README_EN.md)

</div>

---

## 🧭 目录

- [为什么选 jkit](#-为什么选-jkit)
- [性能亮点](#-性能亮点)
- [快速开始](#-快速开始)
- [一分钟看懂各模块](#-一分钟看懂各模块)
- [功能总览](#-功能总览)
- [质量保障](#-质量保障)
- [模块与引入](#-模块与引入)
- [深入使用](#-深入使用)
- [构建与测试](#-构建与测试)
- [从 ZmlTools 迁移](#-从-zmltools-迁移)
- [License](#license)

## 🌟 为什么选 jkit

jkit 由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来，在保留 80+ 工具类能力的同时**重写并剔除了全部第三方依赖**（Gson、Fastjson、OkHttp、Guava、Commons CSV、Jsoup、SLF4J、Lombok、javassist…），仅使用 JDK 标准库。

| | 通常的工具库 | jkit |
|---|---|---|
| 引入后新增的第三方 jar | 3 ~ 15 个 | **0 个** |
| 版本冲突 / 依赖仲裁 | 需要小心处理 | **不存在** |
| 供应链审计面 | 每个传递依赖 | **只有 JDK** |
| 老项目（JDK 8）兼容 | 逐渐放弃 | **一等公民** |

**一行代码验证**：本库发布 POM 的 `<dependencies>` 为空，`mvn dependency:tree` 里只有你自己。

> 💡 这不只是洁癖：对金融、政企等依赖审计严格的团队，「引入一个工具包不带来任何传递依赖」往往就是选型的决定性理由。

## 🚀 性能亮点

HTML 解析模块以 Jsoup 1.18.1 为对照做了差分验证与性能对比（数据来自 [tools-test](https://github.com/zhengmingliang/tools-test) 归档报告，JDK 17，成对交替计时取中位数）：

| 场景 | jkit | Jsoup 1.18.1 | 结果 |
|---|---|---|---|
| 大页面（300 KB）解析 | 1.294 ms | 2.275 ms | **快 1.76x** |
| 大页面 `.post` 选择器（索引加持） | 0.015 ms | 0.185 ms | **快 12.7x** |
| 大页面 `#main` | <0.001 ms | 0.164 ms | **快 1344x** |
| 150 份大页面 DOM 常驻堆 | **153.3 MB** | 180.2 MB | **低 15%** |
| 真实站点 70 KB 解析 | 0.306 ms | 0.456 ms | **快 1.49x** |
| 真实站点抽取 10 篇 × 5 字段 | 0.360 ms | 0.465 ms | **快 1.29x，结果逐条一致** |

其它实测：CSV 流式读 107 MB / 100 万行在 `-Xmx50m` 下 582 ms、峰值堆 29.8 MB；1200 条复杂业务 SQL 四方言解析回写结构保真 1200/1200。

## ⚡ 快速开始

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit</artifactId>
    <version>2.0.2</version>
</dependency>
```

```java
import com.alianga.jkit.html.Html;
import com.alianga.jkit.json.JSON;
import com.alianga.jkit.HttpUtils;

// HTML 抽取：比 Jsoup 更快、更省内存，且零依赖
Document doc = Html.parse(html);
String title = doc.selectFirst("a.post-title h3").text();

// JSON：序列化 / POJO 映射 / JSONPath / Schema
User user = JSON.parseObject(json, User.class);

// HTTP：JDK 8 走 HttpURLConnection，JDK 11+ 自动切 java.net.http
String body = HttpUtils.get("https://example.com");
```

## 🧭 一分钟看懂各模块

```java
// 🗄 SQL 解析与改写：多方言 parse / format / 分页改写（LIMIT/TOP/FETCH/ROWNUM）
SqlStatement oracleSql = SQL.adaptPagination(mysqlStmt, SqlDialect.ORACLE);

// 📣 消息通知：钉钉 / 企微 / 飞书 / Server酱 / Bark / SMTP / Webhook
SendResult r = NotificationManager.send("dingtalk", Message.text("CI", "部署完成"));

// 🧮 表达式与模板：规则引擎 / 报表公式
Expression.eval("price * count > 1000 && level == 'VIP'", ctx);

// 🔑 ID 与加解密：雪花（53 位 JS 安全模式可选）、ULID、UUIDv7、AES-GCM、RSA-OAEP
String id = IdGenerator.ulid();

// 📋 YAML / 配置：锚点、多文档；按 Spring Boot 优先级读配置，不依赖 Spring
YamlDocument doc = YamlDocument.parse(yamlText);
```

## 📚 功能总览

| 包 / 模块 | 主要内容 |
| --- | --- |
| `com.alianga.jkit` | 字符串、日期、文件、随机数据、雪花 ID、校验、CSV、编码检测等 80+ 基础工具类 |
| `com.alianga.jkit.json` | **JSON 全套**（迁移自 [wast](https://github.com/wycst/wast)）：parse/POJO 映射、JSONNode、JSONPath、JSON Schema、NDJSON，[文档](docs/json.md) |
| `com.alianga.jkit.http` | **HTTP 客户端**：GET/POST/上传下载/SSE/WebSocket、拦截器、重试、Cookie、负载均衡与服务发现（Nacos 等），[文档](docs/http.md) |
| `com.alianga.jkit.html` | **HTML 解析与 CSS 选择器**：网页抽取场景对标 Jsoup，零依赖且更快更省内存，[文档](docs/html.md) |
| `com.alianga.jkit.sql`（独立模块） | **SQL 解析与改写**：多方言 parse/format、统计、跨方言分页、注入改写、列级脱敏，[文档](docs/sql.md) |
| `com.alianga.jkit.expression` / `template` | **表达式与模板**：运算符、内置/自定义函数、求值缓存、字符串模板，[文档](docs/expression.md) |
| `com.alianga.jkit.yaml` / `config` | **YAML 与配置读取**：锚点引用、多文档；Spring Boot 优先级配置、热加载，[文档](docs/yaml.md) / [config](docs/config.md) |
| `com.alianga.jkit.csv` | **CSV 读写**：表格模型、POJO 映射、流式读写，[文档](docs/csv.md) |
| `jkit-notify`（独立模块） | **消息通知**：钉钉/企微/飞书/Server酱/Bark/SMTP/Webhook，可选 Slack/Telegram/短信，[文档](docs/notify.md) |
| `jkit-curl-codegen`（独立模块） | **curl 转源码**：一键生成 OkHttp / requests / fetch / Go 等 10+ 语言调用代码，[README](jkit-curl-codegen/README.md) |
| `com.alianga.jkit.crypto` 等 | AES-GCM/DES/RSA-OAEP/摘要、图片验证码、线程池、反射、校验等，[文档](docs/toolkit.md) |

完整 API 文档（中文 12,000+ 行，另有英文版）：**[jkit.alianga.com](https://jkit.alianga.com)** · [English docs](https://jkit.alianga.com/en/)

## ✅ 质量保障

- **2,906 个单元测试全部通过**（`mvn test`，8 个模块：jkit-core 977 / jkit-sql 1609 / notify 170 / curl-codegen 44 / …）
- **HTML 差分验证**：119 组「HTML + 选择器」用例与 Jsoup 逐结果比对，112 组完全一致，7 组差异全部为文档化的刻意取舍
- **SQL 语料回归**：1200 条复杂业务 SQL 在 MySQL / Oracle / PostgreSQL / SQL Server 四方言下 parse→format→parse 结构保真 100%，部分语料在真库原生执行验证
- **并发与边界**：熔断/重试并发测试、16000 并发 ID 唯一性、ULID/UUIDv7 往返校验、JDK 8/11/17/21/25 多版本运行
- Checkstyle 门禁可按需开启：`mvn checkstyle:check -Dcheckstyle.skip=false`

## 📦 模块与引入

| 模块 | 坐标 | 说明 |
|---|---|---|
| 核心工具（必选） | `com.alianga:jkit:2.0.2` | 上表 `com.alianga.jkit.*` 全部内容 |
| curl 转源码 | `com.alianga:jkit-curl-codegen:2.0.2` | 解析 curl 命令生成其它语言 HTTP 源码 |
| 消息通知 | `com.alianga:jkit-notify:2.0.2` | 钉钉/企微/飞书/Server酱/Bark/SMTP/Webhook |
| 通知扩展渠道 | `com.alianga:jkit-notify-extra:2.0.2` | Slack/Telegram/ntfy/阿里云等短信 |
| SQL 解析与改写 | `com.alianga:jkit-sql:2.0.2` | 多方言解析、分页改写、注入与脱敏 |
| 自动建表 | `com.alianga:jkit-sql-auto:2.0.2` | 按实体对照库表执行 CREATE/ALTER |
| 自动建表 Starter | `com.alianga:jkit-sql-auto-spring-boot-2` / `-3` | Spring Boot 2 / 3 自动配置 |

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.2</version>
</dependency>
```

所有模块同样零第三方依赖（Spring Boot Starter 仅在提供自动配置的模块内依赖 spring-boot）。

## 📖 深入使用

<details>
<summary><b>CSV：大文件流式读写</b></summary>

```java
// 内存占用与文件大小无关（实测 107MB / 100 万行，-Xmx50m 下 582ms、峰值堆 29.8MB）
CSV.readStream(new File("big.csv"), "UTF-8", new CSVRowHandler() {
    @Override
    public boolean handle(CSVRow row, long rowIndex) {
        String name = row.get("name");       // 按列名取值
        return true;                          // 返回 false 提前终止
    }
});
```

`CSV` / `CSVTable` / `@CSVColumn` 支持按列名取值、列类型转换与 POJO 映射。完整对照见 [docs/csv.md](docs/csv.md)。
</details>

<details>
<summary><b>HTTP：拦截器、重试与负载均衡</b></summary>

```java
HttpUtils.config()
        .setRetryPolicy(RetryPolicy.defaults())   // 3 次尝试 + 指数退避 + 抖动 + Retry-After
        .setTotalTimeoutMs(3000)                  // 覆盖重试/重定向/故障转移的总耗时
        .addInterceptor(chain -> chain.proceed(chain.request().header("X-Trace-Id", id())));

HttpResponse response = HttpRequest.get(url).totalTimeoutMs(800).execute();
response.elapsedMs();   // 端到端耗时
response.attempts();    // 实际发出的请求次数

// 负载均衡与服务发现：URL 主机名写服务名即可，不改调用代码
EndpointPool pool = EndpointPool.builder()
        .serviceName("orders")
        .discovery(new NacosDiscovery.Builder("10.0.0.1:8848").build())
        .strategy(LoadBalanceStrategies.p2cLeastLoaded())
        .build();
```

五种负载均衡策略、熔断按「连续失败或滑动窗口失败率」触发、发现失败保留上次快照。完整说明见 [docs/http.md](docs/http.md)。
</details>

<details>
<summary><b>配置读取：无 Spring 依赖的 Spring Boot 优先级</b></summary>

```java
ConfigPropertyResolver resolver = ConfigPropertyResolver.load();
String port = resolver.getString("server.port");
List<String> paths = resolver.getList("whitelist.paths", String.class);
```

按 Spring Boot 外部化配置优先级合并 `application.yml` / `{name}-{profile}.yml`，支持占位符与前缀绑定到对象，文件热加载。见 [docs/config.md](docs/config.md)。
</details>

<details>
<summary><b>加解密与 ID：现代默认值</b></summary>

```java
// AES-GCM 认证加密：随机 IV 自动拼进密文，篡改/错钥直接抛异常
byte[] ct = AESCrypt.encryptGcm(plain, key);

// RSA 默认 2048 位 + OAEP(SHA-256, MGF1-SHA256)
KeyPair pair = EncryptUtils.RSA.buildKeyPair();

// ULID / UUIDv7：时间有序，按字符串排序即按时间排序
String ulid = IdGenerator.ulid();
UUID v7 = IdGenerator.uuidV7();
```

雪花 ID 未显式配置时由 IP + 进程号推导 workerId，同机多进程不冲突；进入新时间单位时序列号取随机起始值，避免按 ID 取模分表倾斜。`Hash64` 是非密码学摘要，仅适合缓存与路由。详见 [docs/toolkit.md](docs/toolkit.md)。
</details>

<details>
<summary><b>单一实现约定</b></summary>

同一能力在库内只保留一份实现，其余入口一律转调：base64 编解码唯一实现 `Base64Utils`（整块转换按 JDK 版本自动选手写或 JDK intrinsic，JDK 8 手写快 1.6~1.7 倍、GraalVM 21 JDK 快 4 倍以上）、16 进制唯一实现 `ByteUtils.toHexStringLower`（摘要与文件头识别依赖小写）、配置解析唯一实现 `ConfigPropertyResolver`。详见 [docs/toolkit.md](docs/toolkit.md)。
</details>

## 🛠 构建与测试

```bash
mvn test        # 2,906 个测试（8 个模块，JUnit 4 仅 test 作用域）
mvn package     # 零依赖，构建很快
mvn install     # 安装到本地仓库

# 试打发布产物（javadoc/sources jar，不签名不上传）
mvn clean package -Ppublish -Dgpg.skip=true
```

源码与二进制目标为 JDK 8，JDK 9+ 可运行（JSON 的 Unsafe 快速路径受模块权限影响时调用 `JSONVmOptions.forceRequiredMemoryAlignment()`）。发布相关插件全部收在 `publish` profile，平时构建不触发。

## 🔄 从 ZmlTools 迁移

坐标从 `top.wuyongshi:ZmlTools` 迁到 `com.alianga:jkit`，包名 `top.wys.utils.*` → `com.alianga.jkit.*`：

1. **直接改 POM**（推荐）：替换坐标并批量替换 import，80+ 工具类 API 保持兼容。
2. **Maven relocation**：发布 `relocated/zmltools/pom.xml`（`cd relocated/zmltools && mvn clean deploy -Ppublish`，需旧坐标的 Central 账号），旧依赖自动解析到 jkit，包名仍需手工替换。

已剔除的依赖与替代方案：SLF4J→`Log`、OkHttp→纯 JDK HTTP、Gson/Fastjson→`JSON`、Commons CSV→`csv` 包、Jsoup→`html` 包、Guava/Lombok/javassist→JDK 原生。完整迁移清单见 [docs/changelog.md](docs/changelog.md)。

## License

[Apache License 2.0](LICENSE)

JSON 模块源自 [wast](https://github.com/wycst/wast)（Apache License 2.0），源码保留原许可头，详见 [NOTICE](NOTICE)。

---

<div align="center">

如果 jkit 对你有帮助，欢迎点一个 ⭐ Star —— 这是零依赖项目持续维护的最大动力。

</div>
