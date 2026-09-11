# jkit-sql-auto-spring-boot-3

Spring Boot **3.x**（JDK 17+）自动配置：应用就绪后按实体调用 `jkit-sql-auto` 建表 / 加列。

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-3</artifactId>
    <version>2.0.1</version>
</dependency>
```

```yaml
jkit:
  sql:
    auto:
      enabled: true
      mode: update
      packages: com.example.entity
```

Boot 2 应用请用 `jkit-sql-auto-spring-boot-2`。说明见 [docs/sql-auto.md](../docs/sql-auto.md)。
