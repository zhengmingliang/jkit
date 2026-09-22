package com.alianga.jkit.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 快速模板定义。
 *
 * @author 郑明亮
 */
public enum MockTemplate {
    /**
     * 用户信息模板。
     */
    USER("user", "用户信息模板", MockFieldType.NAME, MockFieldType.EMAIL, MockFieldType.PHONE,
            MockFieldType.GENDER, MockFieldType.AGE, MockFieldType.ADDRESS),

    /**
     * 员工信息模板。
     */
    EMPLOYEE("employee", "员工信息模板", MockFieldType.NAME, MockFieldType.EMAIL, MockFieldType.PHONE,
            MockFieldType.COMPANY, MockFieldType.DEPARTMENT, MockFieldType.POSITION, MockFieldType.SALARY),

    /**
     * 商品信息模板。
     */
    PRODUCT("product", "商品信息模板", MockFieldType.NAME, MockFieldType.PRICE, MockFieldType.CURRENCY,
            MockFieldType.UUID, MockFieldType.TIMESTAMP),

    /**
     * 订单信息模板。
     */
    ORDER("order", "订单信息模板", MockFieldType.UUID, MockFieldType.NAME, MockFieldType.EMAIL,
            MockFieldType.PHONE, MockFieldType.ADDRESS, MockFieldType.PRICE, MockFieldType.TIMESTAMP),

    /**
     * API 测试数据模板。
     */
    API("api", "API测试数据模板", MockFieldType.UUID, MockFieldType.TOKEN, MockFieldType.IP,
            MockFieldType.USER_AGENT, MockFieldType.TIMESTAMP, MockFieldType.BOOLEAN);

    private final String key;
    private final String name;
    private final List<MockFieldType> fields;

    MockTemplate(String key, String name, MockFieldType... fields) {
        this.key = key;
        this.name = name;
        this.fields = Collections.unmodifiableList(Arrays.asList(fields));
    }

    /**
     * 获取模板键名。
     *
     * @return 键名
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取模板中文名称。
     *
     * @return 中文名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取模板包含的字段列表。
     *
     * @return 字段列表
     */
    public List<MockFieldType> getFields() {
        return fields;
    }

    /**
     * 根据键名查找模板。
     *
     * @param key 键名
     * @return 模板，找不到时返回 {@code null}
     */
    public static MockTemplate fromKey(String key) {
        for (MockTemplate template : values()) {
            if (template.key.equals(key)) {
                return template;
            }
        }
        return null;
    }
}
