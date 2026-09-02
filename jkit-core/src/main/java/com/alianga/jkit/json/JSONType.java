package com.alianga.jkit.json;

/**
 * 使用枚举定义JSON得6种数据类型
 *
 */
public enum JSONType {
    /**
     * JSON 对象类型
     */
    OBJECT(1, "object"),
    /**
     * JSON 数组类型
     */
    ARRAY(2, "array"),
    /**
     * JSON 字符串类型
     */
    STRING(3, "string"),
    /**
     * JSON 数字类型
     */
    NUMBER(4, "number"),
    /**
     * JSON 整数类型，与 {@link #NUMBER} 共用类型值
     */
    NUMBER_INTEGER(4, "integer"),
    /**
     * JSON 布尔类型
     */
    BOOLEAN(5, "boolean"),
    /**
     * JSON null 类型
     */
    NULL(6, "null");
    final int value;
    final String type;

    JSONType(int value, String type) {
        this.value = value;
        this.type = type;
    }

    /**
     * 获取类型的数值编码。
     *
     * @return 当前类型对应的数值编码
     */
    public int getValue() {
        return value;
    }

    /**
     * 获取类型的名称。
     *
     * @return 当前类型对应的名称，如 {@code object}、{@code array}
     */
    public String getType() {
        return type;
    }

    /**
     * 根据类型名称查找对应的枚举，比较时忽略大小写。
     *
     * @param type 类型名称，如 {@code object}、{@code string}
     * @return 匹配的枚举项；没有匹配项时返回 {@code null}
     */
    public static JSONType typeOf(String type) {
        for (JSONType value : values()) {
            if (value.type.equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }
}
