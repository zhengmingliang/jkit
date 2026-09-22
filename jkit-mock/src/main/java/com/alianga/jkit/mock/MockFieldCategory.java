package com.alianga.jkit.mock;

/**
 * Mock 字段分类。
 *
 * @author 郑明亮
 */
public enum MockFieldCategory {
    /**
     * 个人信息。
     */
    PERSONAL("personal", "个人信息"),

    /**
     * 商业数据。
     */
    BUSINESS("business", "商业数据"),

    /**
     * 技术数据。
     */
    TECHNICAL("technical", "技术数据");

    private final String key;
    private final String label;

    MockFieldCategory(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /**
     * 获取分类键名。
     *
     * @return 键名
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取分类中文名称。
     *
     * @return 中文名称
     */
    public String getLabel() {
        return label;
    }
}
