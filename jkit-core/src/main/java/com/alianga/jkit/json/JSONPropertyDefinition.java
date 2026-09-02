package com.alianga.jkit.json;

import com.alianga.jkit.json.annotations.JsonProperty;

/**
 * 字段定义，@JsonProperty注解的编码实现
 *
 * @time 2026/1/23 16:05
 * @see JsonProperty
 */
public class JSONPropertyDefinition {
    /**
     * @see JsonProperty#name()
     */
    String name;
    /**
     * @see JsonProperty#serialize()
     */
    boolean serialize;
    /**
     * @see JsonProperty#deserialize()
     */
    boolean deserialize;
    /**
     * @see JsonProperty#mapper()
     */
    Class<? extends JSONTypeFieldMapper> mapper;
    /**
     * @see JsonProperty#pattern()
     */
    String pattern;
    /**
     * @see JsonProperty#asTimestamp()
     */
    boolean asTimestamp;
    /**
     * @see JsonProperty#timezone()
     */
    String timezone;
    /**
     * @see JsonProperty#impl()
     */
    Class<?> impl;
    /**
     * @see JsonProperty#possibleTypes()
     */
    Class<?>[] possibleTypes;
    /**
     * @see JsonProperty#possibleExpression()
     */
    String possibleExpression;
    /**
     * @see JsonProperty#unfixedType()
     */
    boolean unfixedType;

    /**
     * 构造全部使用默认值的字段定义，等价于未标注 {@link JsonProperty} 注解的默认行为。
     */
    public JSONPropertyDefinition() {
        this("", true, true, JSONTypeFieldMapper.class, "", false, "", Object.class, new Class<?>[0], "", false);
    }

    /**
     * 构造仅指定别名、其余项使用默认值的字段定义。
     *
     * @param name 序列化/反序列化使用的别名，为空表示使用属性原名
     */
    public JSONPropertyDefinition(String name) {
        this(name, true, true, JSONTypeFieldMapper.class, "", false, "", Object.class, new Class<?>[0], "", false);
    }

    /**
     * 构造指定别名与序列化、反序列化开关的字段定义，其余项使用默认值。
     *
     * @param name        序列化/反序列化使用的别名，为空表示使用属性原名
     * @param serialize   是否参与序列化
     * @param deserialize 是否参与反序列化
     */
    public JSONPropertyDefinition(String name, boolean serialize, boolean deserialize) {
        this(name, serialize, deserialize, null, "", false, "", Object.class, new Class<?>[0], "", false);
    }

    /**
     * 根据 {@link JsonProperty} 注解创建对应的字段定义。
     *
     * @param jsonProperty 属性上的注解实例
     * @return 与注解各项配置一致的字段定义；注解为 {@code null} 时返回 {@code null}
     */
    public static JSONPropertyDefinition of(JsonProperty jsonProperty) {
        return jsonProperty == null ? null : new JSONPropertyDefinition(
                jsonProperty.name().trim(),
                jsonProperty.serialize(),
                jsonProperty.deserialize(),
                jsonProperty.mapper(),
                jsonProperty.pattern().trim(),
                jsonProperty.asTimestamp(),
                jsonProperty.timezone().trim(),
                jsonProperty.impl(),
                jsonProperty.possibleTypes(),
                jsonProperty.possibleExpression(),
                jsonProperty.unfixedType());
    }

    /**
     * 构造完整的字段定义，各参数为 {@code null} 时会回退到对应的默认值，字符串类型会做 trim 处理。
     *
     * @param name               序列化/反序列化使用的别名，为 {@code null} 时取空字符串
     * @param serialize          是否参与序列化
     * @param deserialize        是否参与反序列化
     * @param mapper             自定义字段映射器类型，为 {@code null} 时取 {@link JSONTypeFieldMapper}
     * @param pattern            日期格式表达式，为 {@code null} 时取空字符串
     * @param asTimestamp        是否序列化为时间戳，优先级高于 pattern
     * @param timezone           时区表达式，为空表示使用默认时区
     * @param impl               反序列化时的缺省实现类，为 {@code null} 时取 {@code Object.class}
     * @param possibleTypes      反序列化时可能的类型列表，为 {@code null} 时取空数组
     * @param possibleExpression 用于选择 possibleTypes 下标的表达式，为 {@code null} 时取空字符串
     * @param unfixedType        字段实际类型是否不固定，为 {@code true} 时会读写类型标识
     */
    public JSONPropertyDefinition(String name, boolean serialize, boolean deserialize,
                                  Class<? extends JSONTypeFieldMapper> mapper, String pattern, boolean asTimestamp,
                                  String timezone, Class<?> impl, Class<?>[] possibleTypes, String possibleExpression,
                                  boolean unfixedType) {
        this.name = name == null ? "" : name.trim();
        this.serialize = serialize;
        this.deserialize = deserialize;
        this.mapper = mapper == null ? JSONTypeFieldMapper.class : mapper;
        this.pattern = pattern == null ? "" : pattern.trim();
        this.asTimestamp = asTimestamp;
        this.timezone = timezone;
        this.impl = impl == null ? Object.class : impl;
        this.possibleTypes = possibleTypes == null ? new Class<?>[0] : possibleTypes;
        this.possibleExpression = possibleExpression == null ? "" : possibleExpression.trim();
        this.unfixedType = unfixedType;
    }

    /**
     * 以 source 的配置补全当前定义中仍为默认值的项（别名、mapper、pattern、时区、缺省实现类、
     * 可能类型与选择表达式），已显式设置的项保持不变。
     *
     * @param source 提供补全值的字段定义，为 {@code null} 时不做任何处理
     */
    public void merge(JSONPropertyDefinition source) {
        if (source == null) {
            return;
        }
        if (name().isEmpty()) {
            this.name = source.name();
        }
        if (mapper() == JSONTypeFieldMapper.class) {
            this.mapper = source.mapper();
        }
        if (pattern().isEmpty()) {
            this.pattern = source.pattern();
        }
        if (timezone().isEmpty()) {
            this.timezone = source.timezone();
        }
        if (impl() == Object.class) {
            this.impl = source.impl();
        }
        if (possibleTypes().length == 0) {
            this.possibleTypes = source.possibleTypes();
        }
        if (possibleExpression().isEmpty()) {
            this.possibleExpression = source.possibleExpression();
        }
    }

    /**
     * 获取属性别名。
     *
     * @return 去除首尾空白后的别名，未设置或为 {@code null} 时返回空字符串
     */
    public String name() {
        return name == null ? "" : name.trim();
    }

    /**
     * 设置属性别名。
     *
     * @param name 序列化/反序列化使用的别名
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * 判断该属性是否参与序列化。
     *
     * @return 参与序列化时返回 {@code true}，否则返回 {@code false}
     */
    public boolean serialize() {
        return serialize;
    }

    /**
     * 设置该属性是否参与序列化。
     *
     * @param serialize 是否参与序列化
     */
    public void setSerialize(boolean serialize) {
        this.serialize = serialize;
    }

    /**
     * 判断该属性是否参与反序列化。
     *
     * @return 参与反序列化时返回 {@code true}，否则返回 {@code false}
     */
    public boolean deserialize() {
        return deserialize;
    }

    /**
     * 设置该属性是否参与反序列化。
     *
     * @param deserialize 是否参与反序列化
     */
    public void setDeserialize(boolean deserialize) {
        this.deserialize = deserialize;
    }

    /**
     * 获取自定义字段映射器类型。
     *
     * @return 自定义映射器类型，未指定时返回 {@link JSONTypeFieldMapper} 本身表示不使用自定义映射
     */
    public Class<? extends JSONTypeFieldMapper> mapper() {
        return mapper == null ? JSONTypeFieldMapper.class : mapper;
    }

    /**
     * 设置自定义字段映射器类型。
     *
     * @param mapper 自定义字段映射器类型
     */
    public void setMapper(Class<? extends JSONTypeFieldMapper> mapper) {
        this.mapper = mapper;
    }

    /**
     * 获取日期格式表达式。
     *
     * @return 去除首尾空白后的日期格式表达式，未设置或为 {@code null} 时返回空字符串
     */
    public String pattern() {
        return pattern == null ? "" : pattern.trim();
    }

    /**
     * 设置日期格式表达式。
     *
     * @param pattern 日期格式表达式
     */
    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    /**
     * 判断日期是否序列化为时间戳。
     *
     * @return 序列化为时间戳时返回 {@code true}，否则返回 {@code false}
     */
    public boolean asTimestamp() {
        return asTimestamp;
    }

    /**
     * 设置日期是否序列化为时间戳，为 {@code true} 时优先级高于日期格式表达式。
     *
     * @param asTimestamp 是否序列化为时间戳
     */
    public void setAsTimestamp(boolean asTimestamp) {
        this.asTimestamp = asTimestamp;
    }

    /**
     * 获取时区表达式。
     *
     * @return 去除首尾空白后的时区表达式，未设置或为 {@code null} 时返回空字符串（表示使用默认时区）
     */
    public String timezone() {
        return timezone == null ? "" : timezone.trim();
    }

    /**
     * 设置时区表达式。
     *
     * @param timezone 时区表达式，支持 {@code +8}、{@code +08:30} 等形式
     */
    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    /**
     * 获取反序列化时使用的缺省实现类。
     *
     * @return 缺省实现类，未指定时返回 {@code Object.class} 表示不指定
     */
    public Class<?> impl() {
        return impl == null ? Object.class : impl;
    }

    /**
     * 设置反序列化时使用的缺省实现类。
     *
     * @param impl 缺省实现类，仅当属性声明类型为接口或抽象类时生效
     */
    public void setImpl(Class<?> impl) {
        this.impl = impl;
    }

    /**
     * 获取反序列化时可能的类型列表。
     *
     * @return 可能的类型数组，未指定时返回长度为 0 的空数组
     */
    public Class<?>[] possibleTypes() {
        return possibleTypes == null ? new Class<?>[0] : possibleTypes;
    }

    /**
     * 设置反序列化时可能的类型列表。
     *
     * @param possibleTypes 可能的类型数组，排在靠前的类型优先适配
     */
    public void setPossibleTypes(Class<?>[] possibleTypes) {
        this.possibleTypes = possibleTypes;
    }

    /**
     * 获取用于选择 possibleTypes 下标的表达式。
     *
     * @return 去除首尾空白后的表达式，未设置或为 {@code null} 时返回空字符串（表示忽略处理）
     */
    public String possibleExpression() {
        return possibleExpression == null ? "" : possibleExpression.trim();
    }

    /**
     * 设置用于选择 possibleTypes 下标的表达式。
     *
     * @param possibleExpression 返回 int 类型结果的表达式，结果作为 possibleTypes 的下标
     */
    public void setPossibleExpression(String possibleExpression) {
        this.possibleExpression = possibleExpression;
    }

    /**
     * 判断字段的实际类型是否不固定。
     *
     * @return 类型不固定时返回 {@code true}，否则返回 {@code false}
     */
    public boolean unfixedType() {
        return unfixedType;
    }

    /**
     * 设置字段的实际类型是否不固定，为 {@code true} 时序列化会写入类型标识、反序列化会优先读取类型标识。
     *
     * @param unfixedType 类型是否不固定
     */
    public void setUnfixedType(boolean unfixedType) {
        this.unfixedType = unfixedType;
    }
}
