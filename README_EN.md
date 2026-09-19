<div align="center">

# jkit

**Pure-JDK, zero-dependency Java toolkit — one coordinate for strings, JSON, YAML, HTTP, SQL parsing, HTML extraction and notifications.**

[![Maven Central](https://img.shields.io/maven-central/v/com.alianga/jkit?style=flat-square)](https://central.sonatype.com/artifact/com.alianga/jkit)
[![JDK](https://img.shields.io/badge/JDK-8%2B-orange?style=flat-square)](https://jkit.alianga.com/en/guide/getting-started)
[![Tests](https://img.shields.io/badge/tests-2906%20passing-brightgreen?style=flat-square)](#-quality)
[![Dependencies](https://img.shields.io/badge/runtime%20deps-0-success?style=flat-square)](#-why-jkit)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-jkit.alianga.com-ea580c?style=flat-square)](https://jkit.alianga.com/en/)

[简体中文](README.md) · **English**

</div>

---

## 🌟 Why jkit

jkit started as a migration from [ZmlTools](https://github.com/wuyongshi/ZmlTools) and kept the 80+ utility classes while **rewriting everything on the JDK standard library alone** — Gson, Fastjson, OkHttp, Guava, Commons CSV, Jsoup, SLF4J, Lombok and javassist are all gone.

| | Typical utility libraries | jkit |
|---|---|---|
| Third-party jars pulled in | 3 – 15 | **0** |
| Version conflicts / dependency arbitration | Needs care | **Not a thing** |
| Supply-chain audit surface | Every transitive dep | **Just the JDK** |
| Legacy JDK 8 projects | Eroding support | **First-class** |

**Verify it yourself**: the released POM has an empty `<dependencies>` — `mvn dependency:tree` shows only your own code.

> 💡 For teams under strict dependency auditing (finance, government, enterprise), "a toolkit that ships zero transitive dependencies" is usually the deciding factor.

## 🚀 Performance

The HTML parser is differentially validated against Jsoup 1.18.1 and benchmarked side by side (data from the archived reports in [tools-test](https://github.com/zhengmingliang/tools-test), JDK 17, pairwise order-balanced timing):

| Scenario | jkit | Jsoup 1.18.1 | Result |
|---|---|---|---|
| Large page (300 KB) parse | 1.294 ms | 2.275 ms | **1.76x faster** |
| Large page `.post` selector (indexed) | 0.015 ms | 0.185 ms | **12.7x faster** |
| Large page `#main` | <0.001 ms | 0.164 ms | **1344x faster** |
| Retained heap, 150 large DOMs | **153.3 MB** | 180.2 MB | **15% lower** |
| Real site 70 KB parse | 0.306 ms | 0.456 ms | **1.49x faster** |
| Real site extraction, 10 posts × 5 fields | 0.360 ms | 0.465 ms | **1.29x faster, results identical** |

Also measured: streaming CSV reads 107 MB / 1M rows in 582 ms with a 29.8 MB peak heap under `-Xmx50m`; 1,200 complex business SQL statements round-trip with 100% structural fidelity across four dialects.

## ⚡ Quick Start

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

// HTML extraction: faster and lighter than Jsoup, with zero dependencies
Document doc = Html.parse(html);
String title = doc.selectFirst("a.post-title h3").text();

// JSON: serialization / POJO binding / JSONPath / Schema
User user = JSON.parseObject(json, User.class);

// HTTP: HttpURLConnection on JDK 8, java.net.http from JDK 11 automatically
String body = HttpUtils.get("https://example.com");
```

## 🧭 Every Module in One Minute

```java
// 🗄 SQL parsing & rewriting: multi-dialect parse / format / pagination rewrite
SqlStatement oracleSql = SQL.adaptPagination(mysqlStmt, SqlDialect.ORACLE);

// 📣 Notifications: DingTalk / WeCom / Feishu / ServerChan / Bark / SMTP / Webhook
SendResult r = NotificationManager.send("dingtalk", Message.text("CI", "deploy done"));

// 🧮 Expressions & templates: rule engines / report formulas
Expression.eval("price * count > 1000 && level == 'VIP'", ctx);

// 🔑 IDs & crypto: snowflake (53-bit JS-safe mode), ULID, UUIDv7, AES-GCM, RSA-OAEP
String id = IdGenerator.ulid();

// 📋 YAML / config: anchors, multi-document; Spring-Boot-priority config without Spring
YamlDocument doc = YamlDocument.parse(yamlText);
```

## 📚 Feature Overview

| Package / module | Highlights |
| --- | --- |
| `com.alianga.jkit` | 80+ foundational utilities: strings, dates, files, random data, snowflake IDs, validation, CSV, charset detection |
| `com.alianga.jkit.json` | **Full JSON** (from [wast](https://github.com/wycst/wast)): parse/POJO binding, JSONNode, JSONPath, JSON Schema, NDJSON — [docs](https://jkit.alianga.com/en/json) |
| `com.alianga.jkit.http` | **HTTP client**: GET/POST/upload/download/SSE/WebSocket, interceptors, retries, cookies, load balancing & service discovery (Nacos…) — [docs](https://jkit.alianga.com/en/http) |
| `com.alianga.jkit.html` | **HTML parsing + CSS selectors**: built for web extraction, zero dependencies, faster and lighter than Jsoup — [docs](https://jkit.alianga.com/en/html) |
| `com.alianga.jkit.sql` (module) | **SQL parsing & rewriting**: multi-dialect parse/format, statistics, pagination rewrite, injection rewrite, column masking — [docs](https://jkit.alianga.com/en/sql) |
| `com.alianga.jkit.expression` / `template` | **Expressions & templates**: operators, built-in/custom functions, evaluation cache, string templates — [docs](https://jkit.alianga.com/en/expression) |
| `com.alianga.jkit.yaml` / `config` | **YAML & config**: anchors, multi-document; Spring-Boot-priority externalized config with hot reload — [yaml](https://jkit.alianga.com/en/yaml) / [config](https://jkit.alianga.com/en/config) |
| `com.alianga.jkit.csv` | **CSV**: table model, POJO mapping, streaming read/write — [docs](https://jkit.alianga.com/en/csv) |
| `jkit-notify` (module) | **Notifications**: DingTalk/WeCom/Feishu/ServerChan/Bark/SMTP/Webhook, optional Slack/Telegram/SMS — [docs](https://jkit.alianga.com/en/notify) |
| `jkit-curl-codegen` (module) | **curl → source code**: generate OkHttp / requests / fetch / Go and 10+ more targets — [README](jkit-curl-codegen/README.md) |
| `com.alianga.jkit.crypto` etc. | AES-GCM/DES/RSA-OAEP/digests, captcha images, thread pools, reflection, validation — [docs](https://jkit.alianga.com/en/toolkit) |

Full API documentation (12,000+ lines, Chinese and English): **[jkit.alianga.com](https://jkit.alianga.com)**

## ✅ Quality

- **2,906 unit tests, all passing** (`mvn test`, 8 modules: jkit-core 977 / jkit-sql 1609 / notify 170 / curl-codegen 44 / …)
- **HTML differential validation**: 119 "HTML + selector" cases compared result-by-result against Jsoup — 112 identical, 7 documented deliberate differences
- **SQL corpus regression**: 1,200 complex business SQL statements, parse→format→parse structural fidelity 100% on MySQL / Oracle / PostgreSQL / SQL Server, with parts executed natively on real databases
- **Concurrency & edges**: circuit breaker / retry concurrency tests, 16,000 concurrent ID uniqueness, ULID/UUIDv7 round-trips, verified on JDK 8/11/17/21/25

## 📦 Modules

| Module | Coordinate | Purpose |
|---|---|---|
| Core (required) | `com.alianga:jkit:2.0.2` | Everything under `com.alianga.jkit.*` |
| curl → source | `com.alianga:jkit-curl-codegen:2.0.2` | Turn curl commands into idiomatic HTTP source code |
| Notifications | `com.alianga:jkit-notify:2.0.2` | DingTalk/WeCom/Feishu/ServerChan/Bark/SMTP/Webhook |
| Extra channels | `com.alianga:jkit-notify-extra:2.0.2` | Slack/Telegram/ntfy/Aliyun & more SMS |
| SQL parsing | `com.alianga:jkit-sql:2.0.2` | Multi-dialect parsing, pagination rewrite, injection & masking |
| Auto schema | `com.alianga:jkit-sql-auto:2.0.2` | CREATE/ALTER tables from entities on startup |
| Auto schema Starter | `com.alianga:jkit-sql-auto-spring-boot-2` / `-3` | Spring Boot 2 / 3 auto configuration |

## 🛠 Build & Test

```bash
mvn test        # 2,906 tests across 8 modules (JUnit 4, test scope only)
mvn package     # zero dependencies, fast build
mvn install     # install to local repository
```

Source and bytecode target JDK 8; JDK 9+ runs fine (if the JSON Unsafe fast path is blocked by module access, call `JSONVmOptions.forceRequiredMemoryAlignment()`).

## 🔄 Migrating from ZmlTools

Change the coordinate from `top.wuyongshi:ZmlTools` to `com.alianga:jkit`, and rewrite imports `top.wys.utils.*` → `com.alianga.jkit.*`. A Maven relocation POM is provided under `relocated/zmltools/`. See [docs/changelog.md](docs/changelog.md) for the full list of removed dependencies and their replacements.

## License

[Apache License 2.0](LICENSE)

The JSON module originates from [wast](https://github.com/wycst/wast) (Apache License 2.0); original license headers are retained — see [NOTICE](NOTICE).

---

<div align="center">

If jkit helps you, please consider dropping a ⭐ — it keeps zero-dependency projects like this one maintained.

</div>
