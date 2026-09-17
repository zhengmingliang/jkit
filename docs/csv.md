# CSV 模块使用指南

`com.alianga.jkit.csv` 提供零依赖的 CSV 读写，覆盖三类场景：字符串/字段行的轻量读写、带表头与 POJO 映射的表格模型、流式（大文件不落内存）读写。包内两套实现共用同一个手写解析器——`CSVUtils` 偏底层（读写 `List<List<String>>` 与 `List<Map<String,String>>`，需要显式处理表头），`CSV` / `CSVTable` / `CSVObjectWriter` 偏高层（首行自动当表头、按列名取值、JavaBean 与 `Map` 直接映射）。

解析器遵循 RFC 4180 的常见约定：引号成对、引号内的逗号与换行原样保留、引号内的引号用 `""` 转义。

## 1. CSVUtils：轻量读写

适合不需要表头语义、只想拿到"每一行一个字符串列表"的场景。默认 UTF-8、逗号分隔符。

```java
import com.alianga.jkit.csv.CSVUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

// 读成 List<List<String>>，不处理表头（第一行也是普通数据行）
List<List<String>> rows = CSVUtils.read(new File("/tmp/data.csv"));

// 读成 List<Map<String,String>>，首行作为表头映射到 key
List<Map<String, String>> withHeader = CSVUtils.readWithHeader(new File("/tmp/data.csv"));

// 写出：表头 + 多行数据
CSVUtils.write(new File("/tmp/out.csv"), StandardCharsets.UTF_8,
        rows, "name", "age", "city");

// 拿到一个流式写出器，逐行追加
CSVWriter writer = CSVUtils.writer(new File("/tmp/out.csv"), "name", "age");
writer.writeRow("Tom", "18");
writer.writeRow("Jerry", "20");
writer.close();
```

`CSVUtils.writer(...)` 返回 `CSVWriter`，提供 `writeRow(List)` / `writeRow(String...)` / `getRowCount()` / `flush()` / `close()`。

## 2. CSV / CSVTable：带表头与映射

`CSV.read(...)` 把首行当作表头，后续行解析成 `CSVRow`，整张表是 `CSVTable`。取值可以按列名，也可以按序号。

```java
import com.alianga.jkit.csv.CSV;
import com.alianga.jkit.csv.CSVTable;

// 从字符串、文件、输入流、字节数组读入（均支持指定字符集）
CSVTable table = CSV.read("name,age\nTom,18\nJerry,20");

table.size();                       // 2（数据行数，不含表头）
table.getColumns().getValues();     // [name, age]
table.getColumnIndex("age");        // 1

CSVRow row = table.getRow(0);
row.get("name");                    // "Tom"
row.get(1);                         // "18"
row.getValues();                    // [Tom, 18]
row.toMap();                        // {name=Tom, age=18}
```

`CSVTable` 也能从零构建并写回：

```java
CSVTable t = CSVTable.create(new String[] {"name", "age"});
t.addRow(java.util.Arrays.asList("Tom", "18"));
t.setValue(0, 1, "19");             // 改单元格
t.writeTo(new File("/tmp/out.csv"));
String csv = t.toCSVString();
```

### POJO 映射

`CSVTable.asEntityList(Class)` 把每一行映射成 JavaBean；字段顺序按 getter，可用 `@CSVColumn("表头名")` 把 getter 重命名到指定列。反过来 `CSV.writeObjectTo(List, File)` 把 JavaBean 列表写回 CSV（首元素的属性名作为表头）：

```java
import com.alianga.jkit.csv.CSVColumn;

public static class User {
    @CSVColumn("姓名")
    private String name;
    @CSVColumn("年龄")
    private int age;
    // getter/setter
}

// 写出
CSV.writeObjectTo(users, new File("/tmp/users.csv"));   // 表头: 姓名,年龄

// 读回
CSVTable t = CSV.read(new File("/tmp/users.csv"));
List<User> list = t.asEntityList(User.class);
```

`CSVRow.toBean(Class)` / `toBean(Class, Map<String,String> columnMapping)` 做单行映射，后者用 `Map` 显式声明"CSV 列名 → 属性名"。

## 3. 流式读写（大文件）

`CSV.readStream(...)` 不把整张表读进内存，而是逐行回调，适合 GB 级文件。`CSVRowHandler` 接收每一行（`CSVRow`），`CSVValuesHandler` 接收裸的 `List<String>`。

```java
import com.alianga.jkit.csv.CSVRowHandler;

long count = CSV.readStream(new File("/tmp/big.csv"), new CSVRowHandler() {
    @Override
    public void handle(CSVRow row) {
        System.out.println(row.get("name"));
    }
});

// 流式写对象：边查库边写，不必攒成一个完整 List
CSVObjectWriter writer = CSV.writer(new File("/tmp/out.csv"), "name", "age");
for (User u : userCursor) {
    writer.writeObject(u);
}
writer.close();
```

`CSVObjectWriter` 是 `CSVWriter` 的子类：首次 `writeObject` 时按对象解析列（JavaBean 走 getter + `@CSVColumn`，`Map` 走 key）；创建时没给列名就自动补写表头，给了列名则按表头顺序对齐（对象里没有的列写成空串）。`null` 属性统一写成字符串 `"null"`，与 `writeObjectTo` 一致。

## 4. 字符集与解析细节

- 默认字符集 UTF-8、默认分隔符逗号；所有 `read` / `write` 入口都有 `Charset` 或 `charsetName` 重载。
- 引号内逗号、换行、换行符 `\r\n` 均原样保留；字段含逗号/引号/换行时自动加引号，引号用 `""` 转义。
- 空字段（`1,` / `,2` / `1,,3`）会解析成空串而非丢字段；内容不以换行结尾也能正确读到最后一行。
- 写出时 `null` 值（对象映射场景）写为字符串 `"null"`；`CSVUtils.read` 系列对 `null` 行/字段按空串处理。

## 5. 异常

解析与写出失败抛 `CSVException`（继承 `RuntimeException`），携带原始 message 与 cause。非法输入（如分隔符为空白、列数不齐）会在读写时尽早暴露，不会静默错位。
