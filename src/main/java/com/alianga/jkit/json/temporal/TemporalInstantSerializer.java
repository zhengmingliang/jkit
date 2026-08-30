package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GeneralDate;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.Instant;
import java.util.TimeZone;

/**
 * Instant序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalInstantSerializer extends JSONTemporalSerializer {
    final TimeZone timeZone;
    final boolean asTimestamp;

    /**
     * 构造 Instant 序列化器
     *
     * @param temporalClass 待序列化的时间类型，此处为 {@link Instant}
     * @param property JSON 属性定义，用于读取时间格式、时区以及是否按时间戳输出等配置；
     *                 未指定格式时使用 0 时区
     */
    public TemporalInstantSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
        TimeZone timeZone = ZERO_TIME_ZONE;
        if (dateFormatter != null && !property.timezone().isEmpty()) {
            timeZone = getTimeZone(property.timezone());
        }
        this.timeZone = timeZone;
        this.asTimestamp = property != null && property.asTimestamp();
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        Instant instant = (Instant) value;
        if (asTimestamp) {
            writer.writeLong(instant.toEpochMilli());
        } else {
            long epochMilli = instant.toEpochMilli();
            GeneralDate date = new GeneralDate(epochMilli, timeZone);
            writer.write('"');
            writeGeneralDate(date, dateFormatter, writer);
            writer.write('"');
        }
    }

    // YYYY-MM-ddTHH:mm:ss.SSSZ(时区为0)
    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        Instant instant = (Instant) value;
        if (asTimestamp) {
            writer.writeLong(instant.toEpochMilli());
        } else {
            long epochSeconds = instant.getEpochSecond();
            int nano = instant.getNano();
            writer.writeJSONInstant(epochSeconds, nano);
        }
    }
}
