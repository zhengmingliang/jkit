---
layout: home
title: jkit
titleTemplate: Pure-JDK, zero-dependency Java utility library

hero:
  name: jkit
  text: Pure-JDK, zero-dependency Java toolkit
  tagline: Strings, JSON, YAML, HTTP, SQL parsing, notifications — one coordinate, zero transitive third-party dependencies
  image:
    src: /logo.svg
    alt: jkit
  actions:
    - theme: brand
      text: Getting Started
      link: /en/guide/getting-started
    - theme: alt
      text: Changelog
      link: /en/changelog
    - theme: alt
      text: GitHub
      link: https://github.com/zhengmingliang/jkit

features:
  - icon: 🧰
    title: Zero Dependencies
    details: An empty dependencies section — adding jkit never drags transitive libraries into your application, so no version conflicts or supply-chain surprises.
  - icon: 📦
    title: Full-Featured JSON
    details: Serialization, POJO binding, JSONNode tree, JSONPath, JSON Schema and NDJSON — all through one entry point.
    link: /en/json
    linkText: JSON docs
  - icon: 🌐
    title: Pure-JDK HTTP Client
    details: GET/POST, upload & download, SSE, WebSocket, plus load balancing and service discovery; picks the best implementation on JDK 8 and 11+ automatically.
    link: /en/http
    linkText: HTTP docs
  - icon: 🧬
    title: SQL Parsing
    details: A hand-written, zero-dependency SQL parser matching the common entry points of Druid and JSqlParser. jkit-sql-auto can create or update tables from entities at startup.
    link: /en/sql
    linkText: SQL docs
  - icon: 📣
    title: Notifications
    details: DingTalk, WeCom, Feishu, ServerChan, Bark, SMTP and Webhook out of the box; optional channels cover Slack, Telegram and SMS.
    link: /en/notify
    linkText: Notification docs
  - icon: 🧩
    title: YAML & Configuration
    details: YAML parsing, node tree, anchors and multi-documents; Spring Boot-style externalized configuration without Spring dependencies.
    link: /en/yaml
    linkText: YAML docs
---

## Up and running in 3 minutes

::: code-group

```xml [Maven]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit</artifactId>
    <version>2.0.1</version>
</dependency>
```

```groovy [Gradle]
implementation 'com.alianga:jkit:2.0.1'
```

```java [First program]
import com.alianga.jkit.DateUtils;
import com.alianga.jkit.json.JSON;
import com.alianga.jkit.log.Log;

public class Demo {
    private static final Log log = Log.get(Demo.class);

    public static void main(String[] args) {
        log.info("now={}", DateUtils.getNowDateTime());
        User user = JSON.parseObject("{\"name\":\"Tom\",\"age\":18}", User.class);
        log.info("user={}", JSON.toJsonString(user));
    }
}
```

:::
