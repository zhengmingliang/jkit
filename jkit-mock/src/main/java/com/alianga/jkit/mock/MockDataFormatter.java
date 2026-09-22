package com.alianga.jkit.mock;

import com.alianga.jkit.json.JSON;

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
    private static final String TABLE_NAME = "fake_data";

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
        if (data == null) {
            return "";
        }
        if (format == null || format == MockOutputFormat.JSON) {
            return prettyJson(data);
        }
        return format(toRows(data), format);
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
        StringBuilder sb = new StringBuilder();
        writeJson(data, sb, 0);
        return sb.toString();
    }

    private static void writeJson(Object value, StringBuilder sb, int depth) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Map) {
            writeMap((Map<?, ?>) value, sb, depth);
            return;
        }
        if (value instanceof List) {
            writeList((List<?>) value, sb, depth);
            return;
        }
        if (value instanceof String) {
            writeString((String) value, sb);
            return;
        }
        if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
            return;
        }
        if (value instanceof Object[]) {
            Object[] arr = (Object[]) value;
            sb.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                writeJson(arr[i], sb, depth);
            }
            sb.append(']');
            return;
        }
        writeString(String.valueOf(value), sb);
    }

    private static void writeMap(Map<?, ?> map, StringBuilder sb, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            sb.append(i == 0 ? "\n" : ",\n");
            indent(sb, depth + 1);
            writeString(String.valueOf(entry.getKey()), sb);
            sb.append(": ");
            writeJson(entry.getValue(), sb, depth + 1);
            i++;
        }
        sb.append('\n');
        indent(sb, depth);
        sb.append('}');
    }

    private static void writeList(List<?> list, StringBuilder sb, int depth) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            indent(sb, depth + 1);
            writeJson(list.get(i), sb, depth + 1);
        }
        sb.append('\n');
        indent(sb, depth);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(String text, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
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
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
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
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        sb.append('\n');
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            sb.append("  {");
            boolean first = true;
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append('"');
                sb.append(escapeJson(entry.getKey()));
                sb.append('"');
                sb.append(':');
                sb.append(' ');
                sb.append(valueToJson(entry.getValue()));
            }
            sb.append('}');
            if (i < records.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * 将记录列表输出为 CSV。
     *
     * @param records 记录列表
     * @return CSV 字符串
     */
    public static String toCsv(List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> first = records.get(0);
        String[] keys = first.keySet().toArray(new String[0]);
        appendCsvRow(sb, keys, records.get(0), true);
        for (Map<String, Object> record : records) {
            appendCsvRow(sb, keys, record, false);
        }
        return sb.toString();
    }

    /**
     * 将记录列表输出为 SQL INSERT。
     *
     * @param records 记录列表
     * @return SQL 字符串
     */
    public static String toSql(List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> first = records.get(0);
        String[] keys = first.keySet().toArray(new String[0]);
        sb.append("-- 表结构");
        sb.append('\n');
        sb.append("CREATE TABLE ");
        sb.append(TABLE_NAME);
        sb.append(" (");
        sb.append('\n');
        for (int i = 0; i < keys.length; i++) {
            sb.append("  ");
            sb.append(keys[i]);
            sb.append(" VARCHAR(255)");
            if (i < keys.length - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(");");
        sb.append('\n');
        sb.append('\n');
        sb.append("-- 数据插入");
        sb.append('\n');
        for (Map<String, Object> record : records) {
            sb.append("INSERT INTO ");
            sb.append(TABLE_NAME);
            sb.append(" (");
            for (int i = 0; i < keys.length; i++) {
                sb.append(keys[i]);
                if (i < keys.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append(") VALUES (");
            for (int i = 0; i < keys.length; i++) {
                sb.append(valueToSql(record.get(keys[i])));
                if (i < keys.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append(");");
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 将记录列表输出为 XML。
     *
     * @param records 记录列表
     * @return XML 字符串
     */
    public static String toXml(List<Map<String, Object>> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append('\n');
        sb.append("<data>");
        sb.append('\n');
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            sb.append("  <item id=\"");
            sb.append(i + 1);
            sb.append("\">");
            sb.append('\n');
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                sb.append("    <");
                sb.append(key);
                sb.append(">");
                sb.append(escapeXml(value == null ? "" : String.valueOf(value)));
                sb.append("</");
                sb.append(key);
                sb.append(">");
                sb.append('\n');
            }
            sb.append("  </item>");
            sb.append('\n');
        }
        sb.append("</data>");
        return sb.toString();
    }

    private static void appendCsvRow(StringBuilder sb, String[] keys,
                                     Map<String, Object> record, boolean header) {
        for (int i = 0; i < keys.length; i++) {
            String raw = header ? keys[i] : String.valueOf(record.get(keys[i]));
            sb.append(escapeCsv(raw));
            if (i < keys.length - 1) {
                sb.append(',');
            }
        }
        sb.append('\n');
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

    private static String valueToSql(Object value) {
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

    private static String escapeXml(String value) {
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
