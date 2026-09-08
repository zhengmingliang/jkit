# jkit-sql

零依赖 SQL 解析器。手写词法 / 语法，生成 AST，支持多方言格式化、表列统计与改写。

对标 Druid SQL Parser（手写、可进生产的吞吐）和 JSqlParser（AST + Visitor + 抽表名）的常用能力。**不执行 SQL。**

```xml
<dependency>
    <groupId>com.alianga</groupId>
    <artifactId>jkit-sql</artifactId>
    <version>2.0.1</version>
</dependency>
```

```java
SqlStatement stmt = SQL.parse("SELECT id, name FROM users u WHERE u.age > 18");
SQL.tables(stmt);          // [users]
SQL.addLimit(stmt, 100);
SQL.format(stmt);
```

完整说明见 [docs/sql.md](../docs/sql.md)。后续未完成清单见 [docs/next-plan.md](../docs/next-plan.md)。

和 Druid / JSqlParser 的成功率与吞吐对比在上级目录 `tools-test`：

```text
cd ../tools-test
mvn -Dtest=SqlParserCompareTest test
```
