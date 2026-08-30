package com.alianga.jkit.json.temporal;

import com.alianga.jkit.reflect.GenericParameterizedType;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;

/**
 * 和ZonedDateTime实现相似
 */
public final class TemporalOffsetDateTimeDeserializer extends TemporalZonedDateTimeDeserializer {
    /**
     * 构造OffsetDateTime反序列化器
     *
     * @param temporalConfig 时间类型的配置（含日期模板与时区等信息）
     */
    public TemporalOffsetDateTimeDeserializer(TemporalConfig temporalConfig) {
        super(temporalConfig);
    }

    protected void checkClass(GenericParameterizedType<?> genericParameterizedType) {
    }

    @Override
    protected Temporal ofTemporalDateTime(int year, int month, int dayOfMonth, int hour, int minute, int second,
                                          int nanoOfSecond, Object zone) throws Exception {
        return OffsetDateTime.of(year, month, dayOfMonth, hour, minute, second, nanoOfSecond, (ZoneOffset) zone);
    }

    @Override
    protected Temporal ofTemporalDateTime(LocalDateTime localDateTime, Object zone) throws Exception {
        return OffsetDateTime.of(localDateTime, (ZoneOffset) zone);
    }

    protected Object getDefaultZoneId() throws Exception {
        return DEFAULT_ZONE_OFFSET;
    }

    protected boolean supportedZoneRegion() {
        return false;
    }

    @Override
    protected Object valueOf(String value, Class<?> actualType) throws Exception {
        return OffsetDateTime.parse(value);
    }
}
