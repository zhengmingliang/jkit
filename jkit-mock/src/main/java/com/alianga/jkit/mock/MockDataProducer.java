package com.alianga.jkit.mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mock 数据生产入口：按字段配置批量生成记录。
 *
 * @author 郑明亮
 */
public class MockDataProducer {
    private final MockDataGenerator generator = new MockDataGenerator();
    private final List<MockFieldType> fieldTypes = new ArrayList<MockFieldType>();
    private final List<MockCustomField> customFields = new ArrayList<MockCustomField>();
    private MockSqlOptions sqlOptions = MockSqlOptions.defaults();
    private int count = 10;

    /**
     * 添加内置字段类型。
     *
     * @param fieldType 字段类型
     * @return 当前对象，便于链式调用
     */
    public MockDataProducer addField(MockFieldType fieldType) {
        if (fieldType != null) {
            fieldTypes.add(fieldType);
        }
        return this;
    }

    /**
     * 批量添加内置字段类型。
     *
     * @param fieldTypes 字段类型列表
     * @return 当前对象，便于链式调用
     */
    public MockDataProducer addFields(List<MockFieldType> fieldTypes) {
        if (fieldTypes != null) {
            for (MockFieldType fieldType : fieldTypes) {
                addField(fieldType);
            }
        }
        return this;
    }

    /**
     * 添加自定义字段。
     *
     * @param customField 自定义字段
     * @return 当前对象，便于链式调用
     */
    public MockDataProducer addCustomField(MockCustomField customField) {
        if (customField != null) {
            customFields.add(customField);
        }
        return this;
    }

    /**
     * 设置自定义字段列表。
     *
     * @param customFields 自定义字段列表
     * @return 当前对象，便于链式调用
     */
    public MockDataProducer setCustomFields(List<MockCustomField> customFields) {
        this.customFields.clear();
        if (customFields != null) {
            this.customFields.addAll(customFields);
        }
        return this;
    }

    /**
     * 设置生成数量。
     *
     * @param count 生成数量
     * @return 当前对象，便于链式调用
     */
    public MockDataProducer setCount(int count) {
        this.count = Math.max(1, Math.min(count, 10000));
        return this;
    }

    /**
     * 生成记录列表。
     *
     * @return 记录列表
     */
    public List<Map<String, Object>> generate() {
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>(count);
        for (int i = 0; i < count; i++) {
            records.add(generateOne());
        }
        return records;
    }

    private Map<String, Object> generateOne() {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        for (MockFieldType fieldType : fieldTypes) {
            record.put(fieldType.getKey(), generator.generate(fieldType));
        }
        for (MockCustomField customField : customFields) {
            record.put(customField.getName(), generator.generateCustom(customField));
        }
        return record;
    }

    /**
     * 生成并格式化为字符串。
     *
     * @param format 输出格式
     * @return 格式化后的字符串
     */
    public String format(MockOutputFormat format) {
        StringBuilder sb = new StringBuilder();
        try {
            formatTo(sb, format);
        } catch (IOException e) {
            throw new IllegalStateException("StringBuilder should not throw", e);
        }
        return sb.toString();
    }

    /**
     * 逐条生成并直接写入输出目标，避免在内存中保留完整记录列表。
     *
     * @param out    输出目标
     * @param format 输出格式
     * @return 实际生成条数
     * @throws IOException 写入异常
     */
    public int formatTo(Appendable out, MockOutputFormat format) throws IOException {
        if (format == MockOutputFormat.SQL) {
            return formatSqlTo(out);
        }
        if (format == MockOutputFormat.CSV) {
            return formatCsvTo(out);
        }
        if (format == MockOutputFormat.XML) {
            return formatXmlTo(out);
        }
        return formatJsonTo(out);
    }

    private int formatJsonTo(Appendable out) throws IOException {
        out.append('[');
        out.append('\n');
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(',');
                out.append('\n');
            }
            MockDataFormatter.writeJson(generateOne(), out, 1);
        }
        out.append('\n');
        out.append(']');
        return count;
    }

    private int formatCsvTo(Appendable out) throws IOException {
        Map<String, Object> first = generateOne();
        String[] keys = first.keySet().toArray(new String[0]);
        MockDataFormatter.appendCsvRow(out, keys, first, true);
        MockDataFormatter.appendCsvRow(out, keys, first, false);
        for (int i = 1; i < count; i++) {
            MockDataFormatter.appendCsvRow(out, keys, generateOne(), false);
        }
        return count;
    }

    private int formatXmlTo(Appendable out) throws IOException {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        out.append('\n');
        out.append("<data>");
        out.append('\n');
        for (int i = 0; i < count; i++) {
            Map<String, Object> record = generateOne();
            out.append("  <item id=\"");
            out.append(String.valueOf(i + 1));
            out.append("\">");
            out.append('\n');
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                out.append("    <");
                out.append(entry.getKey());
                out.append(">");
                out.append(MockDataFormatter.escapeXml(entry.getValue() == null
                        ? "" : String.valueOf(entry.getValue())));
                out.append("</");
                out.append(entry.getKey());
                out.append(">");
                out.append('\n');
            }
            out.append("  </item>");
            out.append('\n');
        }
        out.append("</data>");
        return count;
    }

    private int formatSqlTo(Appendable out) throws IOException {
        Map<String, Object> first = generateOne();
        String[] keys = first.keySet().toArray(new String[0]);
        String table = MockDataFormatter.quoteIdentifier(sqlOptions.getTableName(), sqlOptions.getQuote());
        if (sqlOptions.isCreateTable()) {
            out.append("-- 表结构");
            out.append('\n');
            out.append("CREATE TABLE ");
            out.append(table);
            out.append(" (");
            out.append('\n');
            for (int i = 0; i < keys.length; i++) {
                out.append("  ");
                out.append(MockDataFormatter.quoteIdentifier(keys[i], sqlOptions.getQuote()));
                out.append(' ');
                out.append(MockDataFormatter.columnType(first.get(keys[i]), sqlOptions.getTypeMode()));
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
        if (sqlOptions.isBatch()) {
            out.append("INSERT INTO ");
            out.append(table);
            out.append(" (");
            for (int i = 0; i < keys.length; i++) {
                out.append(MockDataFormatter.quoteIdentifier(keys[i], sqlOptions.getQuote()));
                if (i < keys.length - 1) {
                    out.append(", ");
                }
            }
            out.append(") VALUES");
            out.append('\n');
            appendSqlValues(out, first, keys);
            for (int i = 1; i < count; i++) {
                out.append(',');
                out.append('\n');
                appendSqlValues(out, generateOne(), keys);
            }
            out.append(";");
            out.append('\n');
        } else {
            appendSqlInsert(out, first, table, keys);
            for (int i = 1; i < count; i++) {
                appendSqlInsert(out, generateOne(), table, keys);
            }
        }
        return count;
    }

    private void appendSqlInsert(Appendable out, Map<String, Object> record,
                                 String table, String[] keys) throws IOException {
        out.append("INSERT INTO ");
        out.append(table);
        out.append(" (");
        for (int i = 0; i < keys.length; i++) {
            out.append(MockDataFormatter.quoteIdentifier(keys[i], sqlOptions.getQuote()));
            if (i < keys.length - 1) {
                out.append(", ");
            }
        }
        out.append(") VALUES (");
        for (int i = 0; i < keys.length; i++) {
            out.append(MockDataFormatter.valueToSql(record.get(keys[i])));
            if (i < keys.length - 1) {
                out.append(", ");
            }
        }
        out.append(");");
        out.append('\n');
    }

    private void appendSqlValues(Appendable out, Map<String, Object> record,
                                 String[] keys) throws IOException {
        out.append('(');
        for (int i = 0; i < keys.length; i++) {
            out.append(MockDataFormatter.valueToSql(record.get(keys[i])));
            if (i < keys.length - 1) {
                out.append(", ");
            }
        }
        out.append(')');
    }

    /**
     * 设置 SQL 输出选项（仅 SQL 格式生效）。
     *
     * @param sqlOptions SQL 选项
     * @return 当前对象，便于链式调用
     */
    public MockDataProducer setSqlOptions(MockSqlOptions sqlOptions) {
        if (sqlOptions != null) {
            this.sqlOptions = sqlOptions;
        }
        return this;
    }
}
