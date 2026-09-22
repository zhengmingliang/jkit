package com.alianga.jkit.mock;

/**
 * 自定义字段的数据类型。
 *
 * @author 郑明亮
 */
public enum MockCustomFieldType {
    /**
     * 字符串。
     */
    STRING("string", "字符串"),

    /**
     * 数字。
     */
    NUMBER("number", "数字"),

    /**
     * 布尔值。
     */
    BOOLEAN("boolean", "布尔值"),

    /**
     * 日期。
     */
    DATE("date", "日期"),

    /**
     * 数组。
     */
    ARRAY("array", "数组");

    private final String key;
    private final String label;

    MockCustomFieldType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /**
     * 获取类型键名。
     *
     * @return 键名
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取类型中文名称。
     *
     * @return 中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 根据键名查找自定义字段类型。
     *
     * @param key 键名
     * @return 自定义字段类型，找不到时返回 {@code STRING}
     */
    public static MockCustomFieldType fromKey(String key) {
        for (MockCustomFieldType type : values()) {
            if (type.key.equals(key)) {
                return type;
            }
        }
        return STRING;
    }
}
