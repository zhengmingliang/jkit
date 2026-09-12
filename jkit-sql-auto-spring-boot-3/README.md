# jkit-sql-auto-spring-boot-3

Spring Boot **3.x**（JDK 17+）适配包：应用就绪后自动按实体建表 / 加列，使用容器里的 `DataSource`。**不必**在 `main` 里调 `SqlAuto.run`。

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-3</artifactId>
    <version>2.0.1</version>
</dependency>
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/shop
    username: shop
    password: secret

jkit:
  sql:
    auto:
      enabled: true
      mode: update
      packages: com.example.entity
```

`packages` 或 `entities` 至少配一项。Boot 2 请用 `jkit-sql-auto-spring-boot-2`。完整说明：[docs/sql-auto.md](../docs/sql-auto.md)。
