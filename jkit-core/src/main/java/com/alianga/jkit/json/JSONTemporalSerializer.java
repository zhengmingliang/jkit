package com.alianga.jkit.json;

import com.alianga.jkit.json.internal.beans.DateFormatter;
import com.alianga.jkit.json.temporal.*;
import com.alianga.jkit.reflect.ClassStrucWrap;

import java.util.TimeZone;

/**
 * java.time support
 * <p>
 * Serialization using reflection
 * <p>
 * Localtime, localdate, localdatetime do not consider time zone
 *
 * @time 2022/8/13 15:08
 */
public abstract class JSONTemporalSerializer extends JSONTypeSerializer {
    /**
     * 待序列化的时间类型
     */
    protected final Class<?> temporalClass;
    /**
     * 根据属性上的 pattern 构建的日期格式化器，未指定 pattern 时为 {@code null}
     */
    protected DateFormatter dateFormatter;
    /**
     * 是否使用自定义格式化器输出，等价于 {@link #dateFormatter} 不为 {@code null}
     */
    protected final boolean useFormatter;

    /**
     * 构建时间类型序列化器，并校验时间类型、初始化格式化器。
     *
     * @param temporalClass 待序列化的时间类型
     * @param property      属性定义信息，可为 {@code null}，其 pattern 用于构建格式化器
     */
    protected JSONTemporalSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        checkClass(temporalClass);
        this.temporalClass = temporalClass;
        if (property != null) {
            String pattern = property.pattern();
            if (pattern.length() > 0) {
                dateFormatter = DateFormatter.of(pattern);
            }
        }
        useFormatter = dateFormatter != null;
    }

    static JSONTypeSerializer getTemporalSerializerInstance(ClassStrucWrap classStrucWrap,
                                                            JSONPropertyDefinition property) {
        ClassStrucWrap.ClassWrapperType classWrapperType = classStrucWrap.getClassWrapperType();
        Class<?> temporalClass = classStrucWrap.getSourceClass();
        switch (classWrapperType) {
            case TemporalMonthDay: {
                return new TemporalMonthDaySerializer(temporalClass, property);
            }
            case TemporalYearMonth: {
                return new TemporalYearMonthSerializer(temporalClass, property);
            }
            case TemporalLocalDate: {
                return new TemporalLocalDateSerializer(temporalClass, property);
            }
            case TemporalLocalDateTime: {
                return new TemporalLocalDateTimeSerializer(temporalClass, property);
            }
            case TemporalLocalTime: {
                return new TemporalLocalTimeSerializer(temporalClass, property);
            }
            case TemporalInstant: {
                return new TemporalInstantSerializer(temporalClass, property);
            }
            case TemporalZonedDateTime: {
                return new TemporalZonedDateTimeSerializer(temporalClass, property);
            }
            case TemporalOffsetDateTime: {
                return new TemporalOffsetDateTimeSerializer(temporalClass, property);
            }
            default: {
                throw new UnsupportedOperationException();
            }
        }
    }

    // check
    /**
     * 校验时间类型是否为当前序列化器支持的类型，不支持时由子类抛出异常。
     *
     * @param temporalClass 待校验的时间类型
     */
    protected abstract void checkClass(Class<?> temporalClass);

    protected void serialize(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        if (useFormatter) {
            writeTemporalWithTemplate(value, writer, jsonConfig);
        } else {
            writeDefault(value, writer, jsonConfig, indent);
        }
    }

    /**
     * 使用自定义模板格式化并写出时间值，由各时间类型的子类实现。
     *
     * @param value      待序列化的时间对象
     * @param writer     JSON 输出流
     * @param jsonConfig 序列化配置
     * @throws Exception 写出过程中发生的异常
     */
    protected abstract void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig)
            throws Exception;

    /**
     * <p> 默认toString方式序列化
     * <p> 可重写优化，减少一次字符串的构建
     *
     * @param value 待序列化的时间对象
     * @param writer JSON 输出流
     * @param jsonConfig 序列化配置
     * @param indent 当前缩进层级
     * @throws Exception 写出过程中发生的异常
     */
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        String temporal = value.toString();
        CHAR_SEQUENCE_STRING.serialize(temporal, writer, jsonConfig, indent);
    }

    /**
     * 根据时区 ID 获取时区对象。
     *
     * @param zoneId 时区标识，如 {@code +08:00}、{@code Asia/Shanghai}
     * @return 对应的时区对象
     */
    protected static final TimeZone getTimeZone(String zoneId) {
        return JSONGeneral.getTimeZone(zoneId);
    }
}
