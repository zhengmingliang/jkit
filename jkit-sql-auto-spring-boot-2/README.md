# jkit-sql-auto-spring-boot-2

Spring Boot **2.x** 自动配置：应用就绪后按实体调用 `jkit-sql-auto` 建表 / 加列。

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto-spring-boot-2</artifactId>
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

数据源用应用里的 `DataSource`（通常是 `spring.datasource.*`）。说明见 [docs/sql-auto.md](../docs/sql-auto.md)。
