package com.alianga.jkit.mock;

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
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            for (MockFieldType fieldType : fieldTypes) {
                record.put(fieldType.getKey(), generator.generate(fieldType));
            }
            for (MockCustomField customField : customFields) {
                record.put(customField.getName(), generator.generateCustom(customField));
            }
            records.add(record);
        }
        return records;
    }

    /**
     * 生成并格式化为字符串。
     *
     * @param format 输出格式
     * @return 格式化后的字符串
     */
    public String format(MockOutputFormat format) {
        return MockDataFormatter.format(generate(), format);
    }
}
