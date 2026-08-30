package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONConfig;
import com.alianga.jkit.json.JSONPropertyDefinition;
import com.alianga.jkit.json.JSONTemporalSerializer;
import com.alianga.jkit.json.JSONWriter;
import com.alianga.jkit.json.internal.beans.GregorianDate;

import java.time.LocalTime;

/**
 * LocalTime序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalLocalTimeSerializer extends JSONTemporalSerializer {
    /**
     * 创建 LocalTime 序列化器
     *
     * @param temporalClass 待序列化的时间类型，应为 {@link LocalTime}
     * @param property JSON 属性定义，用于读取时间格式化模板等配置
     */
    public TemporalLocalTimeSerializer(Class<?> temporalClass, JSONPropertyDefinition property) {
        super(temporalClass, property);
    }

    protected void checkClass(Class<?> temporalClass) {
    }

    @Override
    protected void writeTemporalWithTemplate(Object value, JSONWriter writer, JSONConfig jsonConfig) throws Exception {
        LocalTime localTime = (LocalTime) value;
        writer.write('"');
        writeDate(1970, 1, 1, localTime.getHour(), localTime.getMinute(), localTime.getSecond(),
                localTime.getNano() / 1000000, dateFormatter, writer);
        writer.write('"');
    }

    @Override
    protected void writeDefault(Object value, JSONWriter writer, JSONConfig jsonConfig, int indent) throws Exception {
        LocalTime localTime = (LocalTime) value;
        writer.writeJSONTimeWithNano(localTime.getHour(), localTime.getMinute(), localTime.getSecond(),
                localTime.getNano());
    }
}
