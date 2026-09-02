package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.LocalDate;

/**
 * LocalDate序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalLocalDateSerializer extends JSONTemporalSerializer {
    /**
     * 构建 LocalDate 序列化器。
     *
     * @param temporalClass 待序列化的时间类型，即 {@link LocalDate}
     * @param property      属性定义信息，可为 {@code null}，用于确定日期格式模板
     */
    public TemporalLocalDateSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        LocalDate localDate = (LocalDate) value;
        int year = localDate.getYear();
        int month = localDate.getMonthValue();
        int day = localDate.getDayOfMonth();
        writer.write('"');
        writeDate(year, month, day, 0, 0, 0, 0, dateFormatter, writer);
        writer.write('"');
    }

    // yyyy-MM-dd
    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        LocalDate localDate = (LocalDate) value;
        writer.writeJSONLocalDate(localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth());
    }
}
