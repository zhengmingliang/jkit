package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.MonthDay;

/**
 * MonthDay序列化
 *
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalMonthDaySerializer extends JSONTemporalSerializer {
    /**
     * 构造 MonthDay 序列化器。
     *
     * @param temporalClass 待序列化的时间类型，此处为 {@link MonthDay}
     * @param property      属性定义，用于获取日期格式化模板，可为 {@code null}
     */
    public TemporalMonthDaySerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        MonthDay monthDay = (MonthDay) value;
        writer.write('"');
        writeDate(1900, monthDay.getMonthValue(), monthDay.getDayOfMonth(), 0, 0, 0, 0, dateFormatter, writer);
        writer.write('"');
    }

    // --MM-dd
    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        MonthDay monthDay = (MonthDay) value;
        writer.writeJSONDefaultMonthDay(monthDay.getMonthValue(), monthDay.getDayOfMonth());
    }
}
