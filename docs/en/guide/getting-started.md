# Getting Started

## What is jkit

jkit is a **pure-JDK, zero-dependency** general-purpose Java utility library, migrated from [ZmlTools](https://github.com/wuyongshi/ZmlTools). It keeps the original utility capabilities while removing every third-party dependency (Gson, Fastjson, OkHttp, Guava, Apache Commons CSV, Jsoup, SLF4J, Logback, Lombok, etc.) — only the JDK standard library is used.

- **Coordinates**: `com.alianga:jkit`
- **Compilation target**: JDK 8+
- **License**: Apache License 2.0

Highlights:

- **Zero third-party dependencies**: the `<dependencies>` section is empty, so adding jkit never leaks external libraries into your application — no version conflicts.
- **Dependency-free logging**: a minimal facade `com.alianga.jkit.log.Log` built on `java.util.logging`, with an SLF4J-style API (`{}` placeholders, trailing `Throwable` prints the stack trace).
- **Pure-JDK rewrites**: CSV, JSON and every other utility are implemented without any external library.

## Modules

This repository is a multi-module build rooted at `jkit-parent`. Pull in only what you need:

| Module | Coordinates | Description |
| --- | --- | --- |
| `jkit-core` | `com.alianga:jkit` | Runtime core (strings, dates, JSON, YAML, HTTP, configuration, …) |
| `jkit-sql` | `com.alianga:jkit-sql` | Hand-written zero-dependency SQL parser matching the common entry points of Druid / JSqlParser |
| `jkit-notify` | `com.alianga:jkit-notify` | Notifications: DingTalk / WeCom / Feishu / ServerChan / Bark / Webhook / SMTP |
| `jkit-notify-extra` | `com.alianga:jkit-notify-extra` | Optional channels: Slack / Telegram / ntfy / SMS (Alibaba Cloud, Tencent Cloud, Yunpian, Huawei Cloud) |
| `jkit-curl-codegen` | `com.alianga:jkit-curl-codegen` | Convert curl commands into OkHttp / fetch / requests and other source code |

## Add the dependency

Core library:

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit</artifactId>
    <version>2.0.1</version>
</dependency>
```

Optional modules:

::: code-group

```xml [SQL parsing]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.1</version>
</dependency>
```

```xml [Notifications]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify</artifactId>
    <version>2.0.1</version>
</dependency>
```

```xml [Extra channels]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify-extra</artifactId>
    <version>2.0.1</version>
</dependency>
```

```xml [curl codegen]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-curl-codegen</artifactId>
    <version>2.0.1</version>
</dependency>
```

:::

## Your first program

The basic utilities are static-method based — no setup objects required:

```java
import com.alianga.jkit.csv.CSVUtils;
import com.alianga.jkit.DateUtils;
import com.alianga.jkit.common.idgenerate.SnowFlakeIdWorker;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.log.Log;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class QuickStart {
    private static final Log log = Log.get(QuickStart.class);

    public static void main(String[] args) throws Exception {
        boolean blank = StringUtils.isBlank("  ");          // true
        String now = DateUtils.getNowDateTime();            // yyyy-MM-dd HH:mm:ss
        long id = SnowFlakeIdWorker.INSTANCE.nextId();      // snowflake ID

        List<List<String>> rows = Arrays.asList(
                Arrays.asList("Tom", "18"),
                Arrays.asList("Lucy", "20"));
        CSVUtils.write(new File("target/users.csv"), rows, "name", "age");
        List<List<String>> back = CSVUtils.read(new File("target/users.csv"));

        log.info("id={}, now={}", id, now);
    }
}
```

## What to read next

| Topic | Docs |
| --- | --- |
| Strings, collections, crypto, reflection, thread pools | [Misc Utilities](/en/toolkit) |
| Files, resources, properties, file monitoring | [IO & Resources](/en/io) |
| Serialization, POJO binding, JSONNode, JSONPath, Schema | [JSON](/en/json) |
| YAML parsing, node tree, anchors, multi-documents | [YAML](/en/yaml) |
| Spring Boot-style externalized configuration | [Configuration](/en/config) |
| GET/POST, upload & download, SSE, load balancing | [HTTP Client](/en/http) |
| Multi-dialect SQL parsing, formatting, rewriting | [SQL Parsing](/en/sql) |
| DingTalk / WeCom / Feishu / email notifications | [Notification](/en/notify) |

## Migrating from ZmlTools

The Maven coordinates moved from `top.wuyongshi:ZmlTools` to `com.alianga:jkit`, and the package prefix from `top.wys.utils.*` to `com.alianga.jkit.*`:

1. **Edit your POM** (recommended): switch the `groupId` / `artifactId` to `com.alianga:jkit:2.0.1` and rewrite `top.wys.utils.*` imports to `com.alianga.jkit.*`.
2. **Maven relocation**: after publishing `relocated/zmltools/pom.xml`, builds depending on the old coordinates are redirected automatically (imports still need manual rewriting).

## Limitations & compatibility

- Source and bytecode target **JDK 8**; JDK 9+ works, but the JSON Unsafe fast path is subject to runtime module permissions — call `JSONVmOptions.forceRequiredMemoryAlignment()` if needed.
- The published jar carries no runtime third-party dependencies; JUnit is test-scoped only.
- `sun.misc.Unsafe` is used purely for performance; application code should not rely on the internal `json.internal` packages.
- Crypto helpers, file writers and network helpers do not manage keys, path safety, timeouts or certificate policies for you.

## Build from source

```bash
# Use a JDK 8 provided via environment variables (JAVA8_HOME or JDK8_HOME)
export JAVA_HOME="${JAVA8_HOME:-${JDK8_HOME}}"
export PATH="$JAVA_HOME/bin:$PATH"

mvn package   # compile & package (dependency-free, fast)
mvn test      # run unit tests (JUnit 4, test scope)
mvn install   # install to the local repository
```
