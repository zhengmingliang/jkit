---
layout: home
title: jkit
titleTemplate: 纯 JDK、零依赖的 Java 工具库

hero:
  name: jkit
  text: 纯 JDK、零依赖的 Java 工具库
  tagline: 字符串、JSON、YAML、HTTP、SQL 解析、消息通知……一个坐标全部带走，不向宿主应用传递任何第三方依赖
  image:
    src: /logo.svg
    alt: jkit
  actions:
    - theme: brand
      text: 快速开始
      link: /guide/getting-started
    - theme: alt
      text: 更新日志
      link: /changelog
    - theme: alt
      text: GitHub
      link: https://github.com/zhengmingliang/jkit

features:
  - icon: 🧰
    title: 零第三方依赖
    details: dependencies 为空，引入本库不会向宿主应用传递任何外部依赖，天然免疫版本冲突与供应链风险。
  - icon: 📦
    title: JSON 全套能力
    details: 序列化、POJO 映射、JSONNode 节点树、JSONPath、JSON Schema、NDJSON，一个入口全覆盖。
    link: /json
    linkText: 查看 JSON 文档
  - icon: 🌐
    title: 纯 JDK HTTP 客户端
    details: GET/POST/上传下载/SSE/WebSocket，附负载均衡与服务发现；JDK 8 与 11+ 自动选择最优实现。
    link: /http
    linkText: 查看 HTTP 文档
  - icon: 🧬
    title: SQL 解析
    details: 零依赖手写 SQL 解析器，对标 Druid 与 JSqlParser 常用入口，支持多方言、格式化、统计与改写。
    link: /sql
    linkText: 查看 SQL 文档
  - icon: 📣
    title: 消息通知
    details: 钉钉、企微、飞书、Server酱、Bark、SMTP、Webhook 开箱即用，扩展渠道覆盖 Slack、Telegram、短信。
    link: /notify
    linkText: 查看通知文档
  - icon: 🧩
    title: YAML 与配置
    details: YAML 解析、节点树、锚点、多文档；按 Spring Boot 优先级的外部化配置读取，无 Spring 依赖。
    link: /yaml
    linkText: 查看 YAML 文档
---

## 三分钟上手

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

```java [第一个程序]
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
