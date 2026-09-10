# 快速开始

## 什么是 jkit

jkit 是**纯 JDK、零第三方依赖**的 Java 通用工具库，由 [ZmlTools](https://github.com/wuyongshi/ZmlTools) 迁移而来。在保留原有工具能力的同时，移除了全部第三方依赖（Gson、Fastjson、OkHttp、Guava、Apache Commons CSV、Jsoup、SLF4J、Logback、Lombok 等），仅使用 JDK 标准库。

- **坐标**：`com.alianga:jkit`
- **编译目标**：JDK 8+
- **License**：Apache License 2.0

特性：

- **零第三方依赖**：`<dependencies>` 为空，引入本库不会向宿主应用传递任何外部依赖，也没有版本冲突问题。
- **日志零依赖**：内置基于 `java.util.logging` 的极简门面 `com.alianga.jkit.log.Log`，用法与 SLF4J 一致（`{}` 占位符、尾参 Throwable 输出堆栈）。
- **纯 JDK 重写**：CSV 读写、JSON 等全部工具均不依赖任何外部库。

## 模块构成

本仓库是多模块工程，根 POM 为 `jkit-parent`，按需引入：

| 模块 | 发布坐标 | 说明 |
| --- | --- | --- |
| `jkit-core` | `com.alianga:jkit` | 运行时核心库（字符串、日期、JSON、YAML、HTTP、配置等） |
| `jkit-sql` | `com.alianga:jkit-sql` | 零依赖手写 SQL 解析器，对标 Druid / JSqlParser 常用入口 |
| `jkit-notify` | `com.alianga:jkit-notify` | 消息通知：钉钉 / 企微 / 飞书 / Server酱 / Bark / Webhook / SMTP |
| `jkit-notify-extra` | `com.alianga:jkit-notify-extra` | 可选渠道：Slack / Telegram / ntfy / 短信（阿里云、腾讯云、云片、华为云） |
| `jkit-curl-codegen` | `com.alianga:jkit-curl-codegen` | 把 curl 转成 OkHttp / fetch / requests 等其它语言源码 |

## 引入依赖

核心库：

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit</artifactId>
    <version>2.0.1</version>
</dependency>
```

按需追加其它模块：

::: code-group

```xml [SQL 解析]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.1</version>
</dependency>
```

```xml [消息通知]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify</artifactId>
    <version>2.0.1</version>
</dependency>
```

```xml [通知扩展渠道]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-notify-extra</artifactId>
    <version>2.0.1</version>
</dependency>
```

```xml [curl 代码生成]
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-curl-codegen</artifactId>
    <version>2.0.1</version>
</dependency>
```

:::

## 第一个程序

基础工具类均为静态方法，不需要初始化对象：

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

## 接下来读什么

| 主题 | 文档 |
| --- | --- |
| 字符串、集合、加解密、反射、线程池等基础工具 | [其它工具模块](/toolkit) |
| 文件、资源、属性文件、文件监听 | [IO 与资源](/io) |
| 序列化、POJO 映射、JSONNode、JSONPath、Schema | [JSON 模块](/json) |
| YAML 解析、节点树、锚点、多文档 | [YAML 模块](/yaml) |
| Spring Boot 风格的外部化配置读取 | [配置读取](/config) |
| GET/POST/上传下载/SSE/负载均衡 | [HTTP 客户端](/http) |
| 多方言 SQL 解析、格式化、统计改写 | [SQL 解析](/sql) |
| 钉钉 / 企微 / 飞书 / 邮件等消息通知 | [消息通知](/notify) |

## 从 ZmlTools 迁移

Maven 坐标从 `top.wuyongshi:ZmlTools` 迁到 `com.alianga:jkit`，包名从 `top.wys.utils.*` 改为 `com.alianga.jkit.*`：

1. **直接改 POM**（推荐）：把 `groupId` / `artifactId` 换成 `com.alianga:jkit:2.0.1`，并把源码中的 `top.wys.utils.*` import 改为 `com.alianga.jkit.*`。
2. **Maven relocation**：发布 `relocated/zmltools/pom.xml` 后，依赖旧坐标的构建会被 Maven 自动解析到新坐标（包名仍需手工替换）。

## 使用限制与兼容性

- 源码和二进制目标为 **JDK 8**；JDK 9+ 可运行，但 JSON 的 Unsafe 快速路径受运行时模块权限影响，必要时调用 `JSONVmOptions.forceRequiredMemoryAlignment()`。
- 发布 jar 不携带运行时第三方依赖；JUnit 仅为测试作用域依赖。
- `sun.misc.Unsafe` 仅作为性能优化，业务代码不应直接依赖内部实现包 `json.internal`。
- 加密类、文件写入和网络辅助类不会替业务处理密钥、路径安全、超时及证书校验策略。

## 自行构建

```bash
# 使用环境变量提供的 JDK 8（JAVA8_HOME 或 JDK8_HOME）
export JAVA_HOME="${JAVA8_HOME:-${JDK8_HOME}}"
export PATH="$JAVA_HOME/bin:$PATH"

mvn package   # 编译打包（零依赖，构建很快）
mvn test      # 运行单元测试（JUnit 4，test 作用域）
mvn install   # 安装到本地仓库
```
