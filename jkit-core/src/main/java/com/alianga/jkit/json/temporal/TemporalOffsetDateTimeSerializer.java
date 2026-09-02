package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.OffsetDateTime;

/**
 * OffsetDateTime序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalOffsetDateTimeSerializer extends JSONTemporalSerializer {
    /**
     * 构造 {@link OffsetDateTime} 序列化器。
     *
     * @param temporalClass 待序列化的时间类型
     * @param property      属性定义，用于获取日期格式化模板等配置，可能为 {@code null}
     */
    public TemporalOffsetDateTimeSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        OffsetDateTime offsetDateTime = (OffsetDateTime) value;
        writer.write('"');
        // localDateTime
        writeDate(
                offsetDateTime.getYear(),
                offsetDateTime.getMonthValue(),
                offsetDateTime.getDayOfMonth(),
                offsetDateTime.getHour(),
                offsetDateTime.getMinute(),
                offsetDateTime.getSecond(),
                offsetDateTime.getNano() / 1000000,
                dateFormatter,
                writer);
        String zoneId = offsetDateTime.getOffset().toString();
        writer.writeZoneId(zoneId);
        writer.write('"');
    }

    // yyyy-MM-ddTHH:mm:ss.SSS+xx:yy
    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        OffsetDateTime offsetDateTime = (OffsetDateTime) value;
        writer.writeJSONLocalDateTime(
                offsetDateTime.getYear(),
                offsetDateTime.getMonthValue(),
                offsetDateTime.getDayOfMonth(),
                offsetDateTime.getHour(),
                offsetDateTime.getMinute(),
                offsetDateTime.getSecond(),
                offsetDateTime.getNano(),
                offsetDateTime.getOffset().toString());
    }
}
