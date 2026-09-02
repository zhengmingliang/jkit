package com.alianga.jkit.convert;

/**
 * 对象包装器，将任意对象包装后按需转换为常用类型，并在对象为 {@code null} 时返回零值或指定的默认值
 */
public class ObjectWrapper {
    private final Object object;

    /**
     * 构造对象包装器
     *
     * @param object 被包装的对象，允许为 {@code null}
     */
    public ObjectWrapper(Object object) {
        this.object = object;
    }

    /**
     * 获取被包装的原始对象
     *
     * @return 被包装的对象，包装的是 {@code null} 时返回 {@code null}
     */
    public Object getObject() {
        return object;
    }

    /**
     * 获取被包装的原始对象，为 {@code null} 时返回默认值
     *
     * @param defaultObject 对象为 {@code null} 时返回的默认对象
     * @return 被包装的对象，为 {@code null} 时返回 {@code defaultObject}
     */
    public Object getObjectOrDefault(Object defaultObject) {
        return object == null ? defaultObject : object;
    }

    /**
     * 将被包装的对象转为字符串
     *
     * @return 对象的 {@code toString()} 结果，对象为 {@code null} 时返回空字符串
     */
    public String getString() {
        if (object == null) {
            return "";
        }
        return object.toString();
    }

    /**
     * 将被包装的对象转为字符串，为 {@code null} 时返回默认值
     *
     * @param defaultString 对象为 {@code null} 时返回的默认字符串
     * @return 对象的 {@code toString()} 结果，对象为 {@code null} 时返回 {@code defaultString}
     */
    public String getStringOrDefault(String defaultString) {
        if (object == null) {
            return defaultString;
        }
        return object.toString();
    }

    /**
     * 将被包装的对象转为整型
     *
     * @return 解析对象字符串形式得到的整数，对象为 {@code null} 时返回 {@code 0}；
     *         字符串不是合法整数时抛出 {@link NumberFormatException}
     */
    public Integer getInteger() {
        if (object == null) {
            return 0;
        }
        return Integer.parseInt(object.toString());
    }

    /**
     * 将被包装的对象转为整型，为 {@code null} 时返回默认值
     *
     * @param defaultInteger 对象为 {@code null} 时返回的默认整数
     * @return 解析对象字符串形式得到的整数，对象为 {@code null} 时返回 {@code defaultInteger}；
     *         字符串不是合法整数时抛出 {@link NumberFormatException}
     */
    public Integer getIntegerOrDefault(Integer defaultInteger) {
        if (object == null) {
            return defaultInteger;
        }
        return Integer.parseInt(object.toString());
    }

    /**
     * 将被包装的对象转为长整型
     *
     * @return 解析对象字符串形式得到的长整数，对象为 {@code null} 时返回 {@code 0L}；
     *         字符串不是合法长整数时抛出 {@link NumberFormatException}
     */
    public Long getLong() {
        if (object == null) {
            return 0L;
        }
        return Long.parseLong(object.toString());
    }

    /**
     * 将被包装的对象转为长整型，为 {@code null} 时返回默认值
     *
     * @param defaultLong 对象为 {@code null} 时返回的默认长整数
     * @return 解析对象字符串形式得到的长整数，对象为 {@code null} 时返回 {@code defaultLong}；
     *         字符串不是合法长整数时抛出 {@link NumberFormatException}
     */
    public Long getLongOrDefault(Long defaultLong) {
        if (object == null) {
            return defaultLong;
        }
        return Long.parseLong(object.toString());
    }

    /**
     * 将被包装的对象转为双精度浮点型
     *
     * @return 解析对象字符串形式得到的浮点数，对象为 {@code null} 时返回 {@code 0.0}；
     *         字符串不是合法浮点数时抛出 {@link NumberFormatException}
     */
    public Double getDouble() {
        if (object == null) {
            return 0.0;
        }
        return Double.parseDouble(object.toString());
    }

    /**
     * 将被包装的对象转为双精度浮点型，为 {@code null} 时返回默认值
     *
     * @param defaultDouble 对象为 {@code null} 时返回的默认浮点数
     * @return 解析对象字符串形式得到的浮点数，对象为 {@code null} 时返回 {@code defaultDouble}；
     *         字符串不是合法浮点数时抛出 {@link NumberFormatException}
     */
    public Double getDoubleOrDefault(Double defaultDouble) {
        if (object == null) {
            return defaultDouble;
        }
        return Double.parseDouble(object.toString());
    }

    /**
     * 将被包装的对象转为布尔值
     *
     * @return 对象字符串形式忽略大小写等于 {@code "true"} 时返回 {@code true}，
     *         对象为 {@code null} 或其他内容时返回 {@code false}
     */
    public boolean getBoolean() {
        if (object == null) {
            return false;
        }
        return Boolean.parseBoolean(object.toString());
    }

    /**
     * 将被包装的对象转为布尔值，为 {@code null} 时返回默认值
     *
     * @param defaultBoolean 对象为 {@code null} 时返回的默认布尔值，不能为 {@code null}，否则拆箱会抛出空指针异常
     * @return 对象字符串形式忽略大小写等于 {@code "true"} 时返回 {@code true}，其他内容返回 {@code false}；
     *         对象为 {@code null} 时返回 {@code defaultBoolean}
     */
    public boolean getBooleanOrDefault(Boolean defaultBoolean) {
        if (object == null) {
            return defaultBoolean;
        }
        return Boolean.parseBoolean(object.toString());
    }
}
