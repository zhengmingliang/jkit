package com.alianga.jkit.mock;

/**
 * Mock 数据字段类型，与 FeHelper 数据 Mock 工具的字段一一对应。
 *
 * @author 郑明亮
 */
public enum MockFieldType {
    /**
     * 姓名。
     */
    NAME("name", "姓名", MockFieldCategory.PERSONAL),

    /**
     * 邮箱。
     */
    EMAIL("email", "邮箱", MockFieldCategory.PERSONAL),

    /**
     * 手机号。
     */
    PHONE("phone", "手机号", MockFieldCategory.PERSONAL),

    /**
     * 身份证号。
     */
    ID_CARD("idCard", "身份证号", MockFieldCategory.PERSONAL),

    /**
     * 性别。
     */
    GENDER("gender", "性别", MockFieldCategory.PERSONAL),

    /**
     * 年龄。
     */
    AGE("age", "年龄", MockFieldCategory.PERSONAL),

    /**
     * 生日。
     */
    BIRTHDAY("birthday", "生日", MockFieldCategory.PERSONAL),

    /**
     * 地址。
     */
    ADDRESS("address", "地址", MockFieldCategory.PERSONAL),

    /**
     * 公司名称。
     */
    COMPANY("company", "公司名称", MockFieldCategory.BUSINESS),

    /**
     * 部门。
     */
    DEPARTMENT("department", "部门", MockFieldCategory.BUSINESS),

    /**
     * 职位。
     */
    POSITION("position", "职位", MockFieldCategory.BUSINESS),

    /**
     * 薪资。
     */
    SALARY("salary", "薪资", MockFieldCategory.BUSINESS),

    /**
     * 银行卡号。
     */
    BANK_CARD("bankCard", "银行卡号", MockFieldCategory.BUSINESS),

    /**
     * 信用卡号。
     */
    CREDIT_CARD("creditCard", "信用卡号", MockFieldCategory.BUSINESS),

    /**
     * 价格。
     */
    PRICE("price", "价格", MockFieldCategory.BUSINESS),

    /**
     * 货币。
     */
    CURRENCY("currency", "货币", MockFieldCategory.BUSINESS),

    /**
     * UUID。
     */
    UUID("uuid", "UUID", MockFieldCategory.TECHNICAL),

    /**
     * IP 地址。
     */
    IP("ip", "IP地址", MockFieldCategory.TECHNICAL),

    /**
     * MAC 地址。
     */
    MAC("mac", "MAC地址", MockFieldCategory.TECHNICAL),

    /**
     * User Agent。
     */
    USER_AGENT("userAgent", "User Agent", MockFieldCategory.TECHNICAL),

    /**
     * URL。
     */
    URL("url", "URL", MockFieldCategory.TECHNICAL),

    /**
     * 域名。
     */
    DOMAIN("domain", "域名", MockFieldCategory.TECHNICAL),

    /**
     * 密码。
     */
    PASSWORD("password", "密码", MockFieldCategory.TECHNICAL),

    /**
     * Token。
     */
    TOKEN("token", "Token", MockFieldCategory.TECHNICAL),

    /**
     * 颜色值。
     */
    COLOR("color", "颜色值", MockFieldCategory.TECHNICAL),

    /**
     * 时间戳。
     */
    TIMESTAMP("timestamp", "时间戳", MockFieldCategory.TECHNICAL),

    /**
     * 文件名。
     */
    FILENAME("filename", "文件名", MockFieldCategory.TECHNICAL),

    /**
     * MIME 类型。
     */
    MIME_TYPE("mimeType", "MIME类型", MockFieldCategory.TECHNICAL),

    /**
     * 布尔值。
     */
    BOOLEAN("boolean", "布尔值", MockFieldCategory.TECHNICAL),

    /**
     * 日期。
     */
    DATE("date", "日期", MockFieldCategory.TECHNICAL);

    private final String key;
    private final String label;
    private final MockFieldCategory category;

    MockFieldType(String key, String label, MockFieldCategory category) {
        this.key = key;
        this.label = label;
        this.category = category;
    }

    /**
     * 获取字段在输出中使用的键名。
     *
     * @return 键名
     */
    public String getKey() {
        return key;
    }

    /**
     * 获取界面显示的中文名称。
     *
     * @return 中文名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * 获取字段所属的分类。
     *
     * @return 分类
     */
    public MockFieldCategory getCategory() {
        return category;
    }

    /**
     * 根据键名查找字段类型。
     *
     * @param key 键名
     * @return 字段类型，找不到时返回 {@code null}
     */
    public static MockFieldType fromKey(String key) {
        for (MockFieldType type : values()) {
            if (type.key.equals(key)) {
                return type;
            }
        }
        return null;
    }
}
