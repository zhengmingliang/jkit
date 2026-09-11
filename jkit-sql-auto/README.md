# jkit-sql-auto

启动时按实体自动建表 / 更新表结构。扫描 `jkit-sql` 的 `@SqlTable`、JPA `@Entity`、MyBatis-Plus `@TableName`，对照 `DatabaseMetaData` 执行 `CREATE TABLE` / `ALTER TABLE ADD`。

`jkit-sql` **不执行 SQL**；本模块才走 JDBC。运行时仍零第三方依赖（测试用 H2）。

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql-auto</artifactId>
    <version>2.0.1</version>
</dependency>
```

```java
SqlAuto.run(SqlAutoOptions.defaults()
        .url("jdbc:h2:mem:demo")
        .packages("com.example.entity")
        .mode(SqlAutoMode.UPDATE));
```

完整说明见 [docs/sql-auto.md](../docs/sql-auto.md)。
