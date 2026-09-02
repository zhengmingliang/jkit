package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.YearMonth;

/**
 * YearMonth序列化
 *
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalYearMonthSerializer extends JSONTemporalSerializer {
    /**
     * 构造 YearMonth 序列化器。
     *
     * @param temporalClass 时间类型，即 {@link YearMonth}
     * @param property      属性定义，提供日期格式模板等配置
     */
    public TemporalYearMonthSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        YearMonth yearMonth = (YearMonth) value;
        writer.write('"');
        writeDate(yearMonth.getYear(), yearMonth.getMonthValue(), 0, 0, 0, 0, 0, dateFormatter, writer);
        writer.write('"');
    }

    // yyyy-MM
    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        YearMonth yearMonth = (YearMonth) value;
        writer.writeJSONYearMonth(yearMonth.getYear(), yearMonth.getMonthValue());
    }
}
