package com.alianga.jkit.mock;

/**
 * Mock 数据输出格式。
 *
 * @author 郑明亮
 */
public enum MockOutputFormat {
    /**
     * JSON 格式。
     */
    JSON("json", "JSON"),

    /**
     * CSV 格式。
     */
    CSV("csv", "CSV"),

    /**
     * SQL INSERT 格式。
     */
    SQL("sql", "SQL INSERT"),

    /**
     * XML 格式。
     */
    XML("xml", "XML");

    private final String key;
    private final String label;

    MockOutputFormat(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /**
     * 获取格式键名。
     *
     * @return 键名
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取格式显示名称。
     *
     * @return 显示名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 根据键名查找输出格式。
     *
     * @param key 键名
     * @return 输出格式，找不到时返回 {@code JSON}
     */
    public static MockOutputFormat fromKey(String key) {
        for (MockOutputFormat format : values()) {
            if (format.key.equals(key)) {
                return format;
            }
        }
        return JSON;
    }
}
