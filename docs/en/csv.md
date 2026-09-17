# CSV Module Guide

`com.alianga.jkit.csv` is a zero-dependency CSV reader/writer covering three scenarios: lightweight string/field-row I/O, a header-aware table model with POJO mapping, and streaming (large-file, memory-friendly) I/O. The two implementations share one hand-written parser—`CSVUtils` is lower-level (`List<List<String>>` and `List<Map<String,String>>`, headers handled explicitly), while `CSV` / `CSVTable` / `CSVObjectWriter` are higher-level (first row treated as header, access by column name, JavaBean and `Map` mapping directly).

The parser follows the common RFC 4180 conventions: paired quotes, commas and newlines inside quotes preserved verbatim, and quotes inside quotes escaped with `""`.

## 1. CSVUtils: lightweight I/O

For when you don't need header semantics and just want "one string list per row". Default UTF-8, comma delimiter.

```java
import com.alianga.jkit.csv.CSVUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

// Read as List<List<String>>; the first row is also a plain data row (no header handling)
List<List<String>> rows = CSVUtils.read(new File("/tmp/data.csv"));

// Read as List<Map<String,String>>; the first row is used as header keys
List<Map<String, String>> withHeader = CSVUtils.readWithHeader(new File("/tmp/data.csv"));

// Write: header + multiple data rows
CSVUtils.write(new File("/tmp/out.csv"), StandardCharsets.UTF_8,
        rows, "name", "age", "city");

// Grab a streaming writer and append rows
CSVWriter writer = CSVUtils.writer(new File("/tmp/out.csv"), "name", "age");
writer.writeRow("Tom", "18");
writer.writeRow("Jerry", "20");
writer.close();
```

`CSVUtils.writer(...)` returns `CSVWriter`, offering `writeRow(List)` / `writeRow(String...)` / `getRowCount()` / `flush()` / `close()`.

## 2. CSV / CSVTable: headers and mapping

`CSV.read(...)` treats the first row as header; subsequent rows become `CSVRow`, and the whole table is `CSVTable`. Values can be fetched by column name or by index.

```java
import com.alianga.jkit.csv.CSV;
import com.alianga.jkit.csv.CSVTable;

CSVTable table = CSV.read("name,age\nTom,18\nJerry,20");

table.size();                       // 2 (data rows, excludes header)
table.getColumns().getValues();     // [name, age]
table.getColumnIndex("age");        // 1

CSVRow row = table.getRow(0);
row.get("name");                    // "Tom"
row.get(1);                         // "18"
row.getValues();                    // [Tom, 18]
row.toMap();                        // {name=Tom, age=18}
```

`CSVTable` can also be built from scratch and written back:

```java
CSVTable t = CSVTable.create(new String[] {"name", "age"});
t.addRow(java.util.Arrays.asList("Tom", "18"));
t.setValue(0, 1, "19");             // mutate a cell
t.writeTo(new File("/tmp/out.csv"));
String csv = t.toCSVString();
```

### POJO mapping

`CSVTable.asEntityList(Class)` maps each row to a JavaBean; field order follows getters, and `@CSVColumn("header name")` renames a getter to a specific column. Conversely `CSV.writeObjectTo(List, File)` writes a JavaBean list back to CSV (the first element's property names become the header):

```java
import com.alianga.jkit.csv.CSVColumn;

public static class User {
    @CSVColumn("姓名")
    private String name;
    @CSVColumn("年龄")
    private int age;
    // getters/setters
}

CSV.writeObjectTo(users, new File("/tmp/users.csv"));   // header: 姓名,年龄

CSVTable t = CSV.read(new File("/tmp/users.csv"));
List<User> list = t.asEntityList(User.class);
```

`CSVRow.toBean(Class)` / `toBean(Class, Map<String,String> columnMapping)` map a single row; the latter declares "CSV column name → property name" explicitly via the `Map`.

## 3. Streaming I/O (large files)

`CSV.readStream(...)` does not load the whole table into memory—it calls back per row, suitable for GB-scale files. `CSVRowHandler` receives each `CSVRow`, `CSVValuesHandler` receives the raw `List<String>`.

```java
import com.alianga.jkit.csv.CSVRowHandler;

long count = CSV.readStream(new File("/tmp/big.csv"), new CSVRowHandler() {
    @Override
    public void handle(CSVRow row) {
        System.out.println(row.get("name"));
    }
});

// Streaming object write: write while paging from the DB, no need to accumulate a full List
CSVObjectWriter writer = CSV.writer(new File("/tmp/out.csv"), "name", "age");
for (User u : userCursor) {
    writer.writeObject(u);
}
writer.close();
```

`CSVObjectWriter` extends `CSVWriter`: on the first `writeObject` it resolves columns from the object (JavaBean via getter + `@CSVColumn`, `Map` via keys); if no column names were given at creation it auto-writes the header, otherwise it aligns to the header order (missing columns become empty strings). `null` properties are written as the string `"null"`, consistent with `writeObjectTo`.

## 4. Charset and parsing details

- Default charset UTF-8, default delimiter comma; every `read` / `write` entry point has a `Charset` or `charsetName` overload.
- Commas, newlines, and `\r\n` inside quotes are preserved verbatim; fields containing comma/quote/newline are auto-quoted, and quotes are escaped with `""`.
- Empty fields (`1,` / `,2` / `1,,3`) parse to empty strings rather than dropping fields; content not ending with a newline is still read correctly to the last row.
- On write, `null` values (object mapping) are written as the string `"null"`; `CSVUtils.read` treats null rows/fields as empty strings.

## 5. Exceptions

Parsing and writing failures throw `CSVException` (extends `RuntimeException`) carrying the original message and cause. Illegal input (e.g. blank delimiter, ragged columns) is surfaced early instead of silently misaligning.
