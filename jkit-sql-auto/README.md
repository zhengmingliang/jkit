# jkit-sql-auto

启动时按实体建表 / 加列。非 Spring 项目用本模块；Spring Boot 请改用 starter。

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto</artifactId>
    <version>2.0.2</version>
</dependency>
```

```java
SqlAuto.run(SqlAutoOptions.defaults()
        .url("jdbc:h2:mem:demo")
        .packages("com.example.entity")
        .mode(SqlAutoMode.UPDATE));
```

或 `SqlAuto.run()` 读 `jkit.sql.auto.*`（URL 可回落 `spring.datasource.*`）。

| 项目 | 坐标 |
| --- | --- |
| 普通 Java | `jkit-sql-auto`（本模块） |
| Spring Boot 2.x | `jkit-sql-auto-spring-boot-2` |
| Spring Boot 3.x | `jkit-sql-auto-spring-boot-3` |

完整说明：[docs/sql-auto.md](../docs/sql-auto.md)（[English](../docs/en/sql-auto.md)）。
