package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GeneralDate;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.LocalDateTime;

/**
 * LocalDateTime序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalLocalDateTimeSerializer extends JSONTemporalSerializer {
    final boolean asTimestamp;

    /**
     * 构建 LocalDateTime 序列化器。
     *
     * @param temporalClass 待序列化的时间类型，即 {@link LocalDateTime}
     * @param property      属性定义信息，可为 {@code null}，用于判断是否按时间戳输出
     */
    public TemporalLocalDateTimeSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
        this.asTimestamp = property != null && property.asTimestamp();
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        LocalDateTime localDateTime = (LocalDateTime) value;
        if (asTimestamp) {
            long time = GeneralDate.getTime(localDateTime.getYear(),
                    localDateTime.getMonthValue(),
                    localDateTime.getDayOfMonth(),
                    localDateTime.getHour(),
                    localDateTime.getMinute(),
                    localDateTime.getSecond(),
                    localDateTime.getNano() / 1000000,
                    null);
            writer.writeLong(time);
        } else {
            writer.write('"');
            writeDate(
                    localDateTime.getYear(),
                    localDateTime.getMonthValue(),
                    localDateTime.getDayOfMonth(),
                    localDateTime.getHour(),
                    localDateTime.getMinute(),
                    localDateTime.getSecond(),
                    localDateTime.getNano() / 1000000, dateFormatter, writer);
            writer.write('"');
        }
    }

    // yyyy-MM-ddTHH:mm:ss.SSS
    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        LocalDateTime localDateTime = (LocalDateTime) value;
        if (asTimestamp) {
            long time = GeneralDate.getTime(localDateTime.getYear(),
                    localDateTime.getMonthValue(),
                    localDateTime.getDayOfMonth(),
                    localDateTime.getHour(),
                    localDateTime.getMinute(),
                    localDateTime.getSecond(),
                    localDateTime.getNano() / 1000000,
                    null);
            writer.writeLong(time);
        } else {
            writer.writeJSONLocalDateTime(
                    localDateTime.getYear(),
                    localDateTime.getMonthValue(),
                    localDateTime.getDayOfMonth(),
                    localDateTime.getHour(),
                    localDateTime.getMinute(),
                    localDateTime.getSecond(),
                    localDateTime.getNano(),
                    "");
        }
    }
}
