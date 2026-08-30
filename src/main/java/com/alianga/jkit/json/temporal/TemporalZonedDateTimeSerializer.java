package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.ZonedDateTime;

/**
 * ZonedDateTime序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalZonedDateTimeSerializer extends JSONTemporalSerializer {
    /**
     * 构建 {@link ZonedDateTime} 序列化器。
     *
     * @param temporalClass 待序列化的时间类型
     * @param property      属性定义，提供日期格式（pattern）等配置
     */
    public TemporalZonedDateTimeSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        ZonedDateTime zonedDateTime = (ZonedDateTime) value;
        int year = zonedDateTime.getYear();
        int month = zonedDateTime.getMonthValue();
        int day = zonedDateTime.getDayOfMonth();
        int hour = zonedDateTime.getHour();
        int minute = zonedDateTime.getMinute();
        int second = zonedDateTime.getSecond();
        int nano = zonedDateTime.getNano();
        int millisecond = nano / 1000000;
        writer.write('"');
        // localDateTime
        writeDate(year, month, day, hour, minute, second, millisecond, dateFormatter, writer);
        String zoneId = zonedDateTime.getZone().toString();
        writer.writeZoneId(zoneId);
        writer.write('"');
    }

    // yyyy-MM-ddTHH:mm:ss.SSS+xx:yy or yyyy-MM-ddTHH:mm:ss.SSS[Asia/Shanghai]
    // note: toString有细微差别
    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        ZonedDateTime zonedDateTime = (ZonedDateTime) value;
        writer.writeJSONLocalDateTime(
                zonedDateTime.getYear(),
                zonedDateTime.getMonthValue(),
                zonedDateTime.getDayOfMonth(),
                zonedDateTime.getHour(),
                zonedDateTime.getMinute(),
                zonedDateTime.getSecond(),
                zonedDateTime.getNano(),
                zonedDateTime.getZone().toString());
    }
}
