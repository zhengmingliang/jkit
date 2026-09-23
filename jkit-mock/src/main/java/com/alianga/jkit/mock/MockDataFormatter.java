package com.alianga.jkit.mock;

import com.alianga.jkit.json.JSON;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock 数据格式化器，支持 JSON / CSV / SQL / XML 输出。
 *
 * @author 郑明亮
 */
public class MockDataFormatter {
    private MockDataFormatter() {
        throw new UnsupportedOperationException("you can not instantiate me !");
    }

    /**
     * 将记录列表按指定格式输出为字符串。
     *
     * @param records 记录列表
     * @param format  输出格式
     * @return 格式化后的字符串
     */
    public static String format(List<Map<String, Object>> records, MockOutputFormat format) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        switch (format) {
            case CSV:
                return toCsv(records);
            case SQL:
                return toSql(records);
            case XML:
                return toXml(records);
            case JSON:
            default:
                return prettyJson(records);
        }
    }

    /**
     * 将任意结构的数据（模板模式的产出可能是对象、标量或数组）按指定格式输出。
     *
     * <p>JSON 走 {@link #prettyJson(Object)} 缩进输出；CSV / SQL / XML 需要二维表，会尽量把数据
     * 摊平成记录列表——对象取一条，对象数组逐条，标量包成 {@code value} 列。</p>
     *
     * @param data   任意数据
     * @param format 输出格式
     * @return 格式化后的字符串
     */
    public static String format(Object data, MockOutputFormat format) {
        return format(data, format, null);
    }

    /**
     * 将任意结构的数据按指定格式输出为字符串（可带 SQL 选项）。
     *
     * @param data    任意数据
     * @param format  输出格式
     * @param options SQL 选项（仅 SQL 格式生效）
     * @return 格式化后的字符串
     */
    public static String format(Object data, MockOutputFormat format, MockSqlOptions options) {
        if (data == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        try {
            formatTo(sw, data, format, options);
        } catch (IOException e) {
            throw new IllegalStateException("StringWriter should not throw", e);
        }
        return sw.toString();
    }

    /**
     * 流式格式化入口：数据直接写入 {@link Appendable}，避免在内存中同时保留记录列表与完整输出字符串。
     *
     * @param out     输出目标
     * @param data    任意数据
     * @param format  输出格式
     * @param options SQL 选项（仅 SQL 格式生效，null 走默认）
     * @throws IOException 写入异常
     */
    public static void formatTo(Appendable out, Object data, MockOutputFormat format,
                                MockSqlOptions options) throws IOException {
        if (data == null) {
            return;
        }
        if (format == null || format == MockOutputFormat.JSON) {
            writeJson(data, out, 0);
            return;
        }
        List<Map<String, Object>> rows = toRows(data);
        if (rows.isEmpty()) {
            return;
        }
        switch (format) {
            case CSV:
                toCsv(out, rows);
                break;
            case SQL:
                toSql(out, rows, options);
                break;
            case XML:
                toXml(out, rows);
                break;
            default:
                writeJson(data, out, 0);
                break;
        }
    }

    /**
     * 把任意结构（对象 / 数组 / 标量）序列化为缩进两空格的 JSON。
     *
     * <p>字段模式与模板模式共用，保证同一工具两种模式的 JSON 输出风格一致：
     * 对象与数组都逐层换行，字符串按 JSON 规范转义。</p>
     *
     * @param data 任意数据
     * @return 缩进后的 JSON 字符串
     */
    public static String prettyJson(Object data) {
        StringWriter sw = new StringWriter();
        try {
            writeJson(data, sw, 0);
        } catch (IOException e) {
            throw new IllegalStateException("StringWriter should not throw", e);
        }
        return sw.toString();
    }

    /**
     * 流式写入 JSON（缩进两空格）。
     *
     * @param value 任意数据
     * @param out   输出目标
     * @param depth 当前缩进层级
     * @throws IOException 写入异常
     */
    public static void writeJson(Object value, Appendable out, int depth) throws IOException {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Map) {
            writeMap((Map<?, ?>) value, out, depth);
            return;
        }
        if (value instanceof List) {
            writeList((List<?>) value, out, depth);
            return;
        }
        if (value instanceof String) {
            writeString((String) value, out);
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            out.append(value.toString());
            return;
        }
        if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            out.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                writeJson(arr[i], out, depth);
            }
            out.append(']');
            return;
        }
        writeString(String.valueOf(value), out);
    }

    private static void writeMap(Map<?, ?> map, Appendable out, int depth) throws IOException {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.append(i == 0 ? "\n" : ",\n");
            indent(out, depth + 1);
            writeString(String.valueOf(entry.getKey()), out);
            out.append(": ");
            writeJson(entry.getValue(), out, depth + 1);
            i++;
        }
        out.append('\n');
        indent(out, depth);
        out.append('}');
    }

    private static void writeList(List<?> list, Appendable out, int depth) throws IOException {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            out.append(i == 0 ? "\n" : ",\n");
            indent(out, depth + 1);
            writeJson(list.get(i), out, depth + 1);
        }
        out.append('\n');
        indent(out, depth);
        out.append(']');
    }

    private static void indent(Appendable out, int depth) throws IOException {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private static void writeString(String text, Appendable out) throws IOException {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /**
     * 把任意数据摊平成记录列表，供 CSV / SQL / XML 使用。
     *
     * @param data 任意数据
     * @return 记录列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> toRows(Object data) {
        if (data instanceof List) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object item : (List<Object>) data) {
                if (item instanceof Map) {
                    rows.add((Map<String, Object>) item);
                } else {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("value", item);
                    rows.add(row);
                }
            }
            return rows;
        }
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            List<Map<String, Object>> nested = longestList(map);
            if (nested != null && !nested.isEmpty()) {
                return nested;
            }
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            rows.add(map);
            return rows;
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("value", data);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row);
        return rows;
    }

    /**
     * 取对象里最长的「对象数组」字段，用于把 {@code {"list":[{...}]}} 摊平成记录。
     *
     * @param map 数据对象
     * @return 记录列表，无则返回 null
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> longestList(Map<String, Object> map) {
        List<Map<String, Object>> best = null;
        for (Object value : map.values()) {
            if (!(value instanceof List) || ((List<Object>) value).isEmpty()) {
                continue;
            }
            if (!(((List<Object>) value).get(0) instanceof Map)) {
                continue;
            }
            List<Map<String, Object>> rows = toRows(value);
            if (best == null || rows.size() > best.size()) {
                best = rows;
            }
        }
        return best;
    }

    /**
     * 将记录列表输出为 JSON。
     *
     * @param records 记录列表
     * @return JSON 字符串
     */
    public static String toJson(List<Map<String, Object>> records) {
        StringWriter sw = new StringWriter();
        try {
            toJson(sw, records);
        } catch (IOException e) {
            throw new IllegalStateException("StringWriter should not throw", e);
        }
        return sw.toString();
    }

    /**
     * 将记录列表流式输出为 JSON。
     *
     * @param out     输出目标
     * @param records 记录列表
     * @throws IOException 写入异常
     */
    public static void toJson(Appendable out, List<Map<String, Object>> records) throws IOException {
        if (records == null || records.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        out.append('\n');
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            out.append("  {");
            boolean first = true;
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append('"');
                out.append(escapeJson(entry.getKey()));
                out.append('"');
                out.append(':');
                out.append(' ');
                out.append(valueToJson(entry.getValue()));
            }
            out.append('}');
            if (i < records.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        out.append(']');
    }

    /**
     * 将记录列表输出为 CSV。
     *
     * @param records 记录列表
     * @return CSV 字符串
     */
    public static String toCsv(List<Map<String, Object>> records) {
        StringWriter sw = new StringWriter();
        try {
            toCsv(sw, records);
        } catch (IOException e) {
            throw new IllegalStateException("StringWriter should not throw", e);
        }
        return sw.toString();
    }

    /**
     * 将记录列表流式输出为 CSV。
     *
     * @param out     输出目标
     * @param records 记录列表
     * @throws IOException 写入异常
     */
    public static void toCsv(Appendable out, List<Map<String, Object>> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<String, Object> first = records.get(0);
        String[] keys = first.keySet().toArray(new String[0]);
        appendCsvRow(out, keys, first, true);
        for (Map<String, Object> record : records) {
            appendCsvRow(out, keys, record, false);
        }
    }

    /**
     * 将记录列表输出为 SQL INSERT（默认选项：自动类型、逐条、不加引号、含建表）。
     *
     * @param records 记录列表
     * @return SQL 字符串
     */
    public static String toSql(List<Map<String, Object>> records) {
        return toSql(records, MockSqlOptions.defaults());
    }

    /**
     * 按选项将记录列表输出为 SQL INSERT。
     *
     * @param records 记录列表
     * @param options SQL 选项（null 走默认）
     * @return SQL 字符串
     */
    public static String toSql(List<Map<String, Object>> records, MockSqlOptions options) {
        StringWriter sw = new StringWriter();
        try {
            toSql(sw, records, options);
        } catch (IOException e) {
            throw new IllegalStateException("StringWriter should not throw", e);
        }
        return sw.toString();
    }

    /**
     * 按选项将记录列表流式输出为 SQL INSERT。
     *
     * @param out     输出目标
     * @param records 记录列表
     * @param options SQL 选项（null 走默认）
     * @throws IOException 写入异常
     */
    public static void toSql(Appendable out, List<Map<String, Object>> records,
                             MockSqlOptions options) throws IOException {
        MockSqlOptions opts = options == null ? MockSqlOptions.defaults() : options;
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<String, Object> first = records.get(0);
        String[] keys = first.keySet().toArray(new String[0]);
        String table = quoteIdentifier(opts.getTableName(), opts.getQuote());
        if (opts.isCreateTable()) {
            out.append("-- 表结构");
            out.append('\n');
            out.append("CREATE TABLE ");
            out.append(table);
            out.append(" (");
            out.append('\n');
            for (int i = 0; i < keys.length; i++) {
                out.append("  ");
                out.append(quoteIdentifier(keys[i], opts.getQuote()));
                out.append(' ');
                out.append(columnType(first.get(keys[i]), opts.getTypeMode()));
                if (i < keys.length - 1) {
                    out.append(',');
                }
                out.append('\n');
            }
            out.append(");");
            out.append('\n');
            out.append('\n');
        }
        out.append("-- 数据插入");
        out.append('\n');
        if (opts.isBatch()) {
            out.append("INSERT INTO ");
            out.append(table);
            out.append(" (");
            for (int i = 0; i < keys.length; i++) {
                out.append(quoteIdentifier(keys[i], opts.getQuote()));
                if (i < keys.length - 1) {
                    out.append(", ");
                }
            }
            out.append(") VALUES");
            out.append('\n');
            for (int r = 0; r < records.size(); r++) {
                out.append('(');
                Map<String, Object> record = records.get(r);
                for (int i = 0; i < keys.length; i++) {
                    out.append(valueToSql(record.get(keys[i])));
                    if (i < keys.length - 1) {
                        out.append(", ");
                    }
                }
                out.append(r < records.size() - 1 ? ")," : ");");
                out.append('\n');
            }
        } else {
            for (Map<String, Object> record : records) {
                out.append("INSERT INTO ");
                out.append(table);
                out.append(" (");
                for (int i = 0; i < keys.length; i++) {
                    out.append(quoteIdentifier(keys[i], opts.getQuote()));
                    if (i < keys.length - 1) {
                        out.append(", ");
                    }
                }
                out.append(") VALUES (");
                for (int i = 0; i < keys.length; i++) {
                    out.append(valueToSql(record.get(keys[i])));
                    if (i < keys.length - 1) {
                        out.append(", ");
                    }
                }
                out.append(");");
                out.append('\n');
            }
        }
    }

    /**
     * 按引号方式包裹标识符。
     *
     * @param identifier 标识符
     * @param quote      引号方式
     * @return 包裹后的标识符
     */
    static String quoteIdentifier(String identifier, MockSqlOptions.Quote quote) {
        if (quote == null) {
            return identifier;
        }
        switch (quote) {
            case BACKTICK:
                return "`" + identifier + "`";
            case DOUBLE_QUOTE:
                return "\"" + identifier + "\"";
            default:
                return identifier;
        }
    }

    /**
     * 按类型模式推断列类型。
     *
     * @param value 首条记录中该列的值
     * @param mode  类型模式
     * @return SQL 列类型
     */
    static String columnType(Object value, MockSqlOptions.TypeMode mode) {
        if (mode == MockSqlOptions.TypeMode.VARCHAR) {
            return "VARCHAR(255)";
        }
        if (mode == MockSqlOptions.TypeMode.TEXT) {
            return "TEXT";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return "BIGINT";
        }
        if (value instanceof Float || value instanceof Double
                || value instanceof java.math.BigDecimal) {
            return "DOUBLE";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        return "VARCHAR(255)";
    }

    /**
     * 将记录列表输出为 XML。
     *
     * @param records 记录列表
     * @return XML 字符串
     */
    public static String toXml(List<Map<String, Object>> records) {
        StringWriter sw = new StringWriter();
        try {
            toXml(sw, records);
        } catch (IOException e) {
            throw new IllegalStateException("StringWriter should not throw", e);
        }
        return sw.toString();
    }

    /**
     * 将记录列表流式输出为 XML。
     *
     * @param out     输出目标
     * @param records 记录列表
     * @throws IOException 写入异常
     */
    public static void toXml(Appendable out, List<Map<String, Object>> records) throws IOException {
        if (records == null || records.isEmpty()) {
            return;
        }
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.append('\n');
        out.append("<data>");
        out.append('\n');
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            out.append("  <item id=\"");
            out.append(String.valueOf(i + 1));
            out.append("\">");
            out.append('\n');
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                out.append("    <");
                out.append(key);
                out.append(">");
                out.append(escapeXml(value == null ? "" : String.valueOf(value)));
                out.append("</");
                out.append(key);
                out.append(">");
                out.append('\n');
            }
            out.append("  </item>");
            out.append('\n');
        }
        out.append("</data>");
    }

    /**
     * 追加一条 CSV 行。
     *
     * @param out     输出目标
     * @param keys    列顺序
     * @param record  记录
     * @param header  是否为表头
     * @throws IOException 写入异常
     */
    static void appendCsvRow(Appendable out, String[] keys,
                             Map<String, Object> record, boolean header) throws IOException {
        for (int i = 0; i < keys.length; i++) {
            String raw = header ? keys[i] : String.valueOf(record.get(keys[i]));
            out.append(escapeCsv(raw));
            if (i < keys.length - 1) {
                out.append(',');
            }
        }
        out.append('\n');
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.contains(",") || value.contains("\"") || value.contains("\n");
        if (!needQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String valueToSql(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof String[]) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            String[] arr = (String[]) value;
            for (int i = 0; i < arr.length; i++) {
                sb.append('"');
                sb.append(escapeJson(arr[i]));
                sb.append('"');
                if (i < arr.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    static String escapeXml(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
