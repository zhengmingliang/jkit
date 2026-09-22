package com.alianga.jkit.mock;

/**
 * 自定义 Mock 字段定义。
 *
 * @author 郑明亮
 */
public class MockCustomField {
    private String name;
    private MockCustomFieldType type;
    private String rule;

    /**
     * 默认构造方法。
     */
    public MockCustomField() {
        this.type = MockCustomFieldType.STRING;
    }

    /**
     * 构造方法。
     *
     * @param name 字段名称
     * @param type 字段类型
     * @param rule 生成规则说明
     */
    public MockCustomField(String name, MockCustomFieldType type, String rule) {
        this.name = name;
        this.type = type;
        this.rule = rule;
    }

    /**
     * 获取字段名称。
     *
     * @return 字段名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置字段名称。
     *
     * @param name 字段名称
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 获取字段类型。
     *
     * @return 字段类型
     */
    public MockCustomFieldType getType() {
        return type;
    }

    /**
     * 设置字段类型。
     *
     * @param type 字段类型
     */
    public void setType(MockCustomFieldType type) {
        this.type = type;
    }

    /**
     * 获取生成规则说明。
     *
     * @return 生成规则说明
     */
    public String getRule() {
        return rule;
    }

    /**
     * 设置生成规则说明。
     *
     * @param rule 生成规则说明
     */
    public void setRule(String rule) {
        this.rule = rule;
    }
}
