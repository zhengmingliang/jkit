# jkit-sql-auto-spring-boot-2

[![Maven Central](https://img.shields.io/maven-central/v/com.alianga/jkit-sql-auto-spring-boot-2?style=flat-square)](https://central.sonatype.com/artifact/com.alianga/jkit-sql-auto-spring-boot-2)

Spring Boot **2.x**（JDK 8+）适配包：应用就绪后自动按实体建表 / 加列，使用容器里的 `DataSource`。**不必**在 `main` 里调 `SqlAuto.run`。

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-2</artifactId>
    <version>2.0.2</version>
</dependency>
```

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/shop
    username: root
    password: secret

jkit:
  sql:
    auto:
      enabled: true
      mode: update
      packages: com.example.entity
```

`packages` 或 `entities` 至少配一项。Boot 3 请用 `jkit-sql-auto-spring-boot-3`。完整说明：[docs/sql-auto.md](../docs/sql-auto.md)。
