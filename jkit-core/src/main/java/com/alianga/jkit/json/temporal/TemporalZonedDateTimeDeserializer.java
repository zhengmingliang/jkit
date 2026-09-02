package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONParseContext;
import com.alianga.jkit.json.JSONTemporalDeserializer;
import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.json.internal.beans.GeneralDate;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.GenericParameterizedType;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZonedDateTime反序列化
 *
 * @time 2022/8/13 15:06
 */
public class TemporalZonedDateTimeDeserializer extends JSONTemporalDeserializer {
    // 注：全局可变
    private static TimeZone defaultTimezone = UnsafeHelper.getDefaultTimeZone();
    private static ZoneId defaultZoneId = defaultTimezone.toZoneId();
    static final ZoneId ZERO = ZoneId.of("Z");
    static final ZoneOffset DEFAULT_ZONE_OFFSET = (ZoneOffset) ZERO;

    private static Map<String, ZoneId> zoneIdMap = new ConcurrentHashMap<String, ZoneId>();

    /**
     * 获取当前 JVM 默认时区对应的 {@code ZoneId}，默认时区发生变化时会自动刷新缓存。
     *
     * @return 默认时区对应的 {@link ZoneId} 实例
     * @throws Exception 获取默认时区失败时抛出
     */
    public static final Object defaultZoneId() throws Exception {
        TimeZone timeZone = UnsafeHelper.getDefaultTimeZone();
        if (timeZone == defaultTimezone) {
            return defaultZoneId;
        }
        defaultTimezone = timeZone;
        return defaultZoneId = defaultTimezone.toZoneId();
    }

    /**
     * 按时区标识获取 {@code ZoneId}，结果会缓存以避免重复解析。
     *
     * @param zoneId 时区标识，如 {@code +08:00}、{@code Z} 或 {@code Asia/Shanghai}
     * @return 与该标识对应的 {@link ZoneId} 实例
     * @throws Exception 时区标识非法无法解析时抛出
     */
    public static ZoneId ofZoneId(String zoneId) throws Exception {
        ZoneId value = zoneIdMap.get(zoneId);
        if (value == null) {
            value = ZoneId.of(zoneId);
            zoneIdMap.put(zoneId, value);
        }
        return value;
    }

    /**
     * 构造 ZonedDateTime 反序列化器。
     *
     * @param temporalConfig 时间格式配置，用于构建日期解析模板
     */
    public TemporalZonedDateTimeDeserializer(TemporalConfig temporalConfig) {
        super(temporalConfig);
    }

    protected void checkClass(GenericParameterizedType<?> genericParameterizedType) {
    }

    // use dateTemplate
    protected Object deserializeTemporal(char[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        String zoneId = null;
        int j = endIndex;
        int ch;
        while (j > fromIndex + 20) {
            if ((ch = buf[--j]) == '.') {
                break;
            }
            if (ch == '+' || ch == '-' || ch == 'Z') {
                zoneId = new String(buf, j, endIndex - j);
                endIndex = j;
                break;
            }
            if (ch == '[' && buf[endIndex - 1] == ']') {
                // eg: [Asia/Shanghai]
                zoneId = new String(buf, j + 1, endIndex - j - 2);
                endIndex = j;
                break;
            }
        }
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        Object zoneObject;
        if (zoneId == null) {
            // default
            zoneObject = getDefaultZoneId();
        } else {
            zoneObject = ofZoneId(zoneId);
        }
        return ofTemporalDateTime(generalDate.getYear(), generalDate.getMonth(), generalDate.getDay(),
                generalDate.getHourOfDay(), generalDate.getMinute(), generalDate.getSecond(),
                generalDate.getMillisecond() * 1000000, zoneObject);
    }

    protected Object deserializeTemporal(byte[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        String zoneId = null;
        int j = endIndex;
        int ch;
        while (j > fromIndex + 20) {
            if ((ch = buf[--j]) == '.') {
                break;
            }
            if (ch == '+' || ch == '-' || ch == 'Z') {
                zoneId = new String(buf, j, endIndex - j);
                endIndex = j;
                break;
            }
            if (ch == '[' && buf[endIndex - 1] == ']') {
                // eg: [Asia/Shanghai]
                zoneId = new String(buf, j + 1, endIndex - j - 2);
                endIndex = j;
                break;
            }
        }
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        Object zoneObject;
        if (zoneId == null) {
            // default
            zoneObject = getDefaultZoneId();
        } else {
            zoneObject = ofZoneId(zoneId);
        }
        return ofTemporalDateTime(generalDate.getYear(), generalDate.getMonth(), generalDate.getDay(),
                generalDate.getHourOfDay(), generalDate.getMinute(), generalDate.getSecond(),
                generalDate.getMillisecond() * 1000000, zoneObject);
    }

    // default format yyyy-MM-ddTHH:mm:ss.SSS+08:00[Asia/Shanghai] not supported 'T'
    // ymd support yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd, yyyy~MM~dd
    // hms support HH:mm:ss.SSS
    @Override
    protected Object deserializeDefault(char[] buf, int offset, char endToken, JSONParseContext parseContext)
            throws Exception {
        LocalDateTime localDateTime = parseLocalDateTime(buf, offset, parseContext);
        int i = parseContext.endIndex;
        char c = buf[i];
        Object zoneObject;
        if (c == 'Z' || c == 'z') {
            zoneObject = ZERO;
            c = buf[++i];
        } else if (c == '+' || c == '-') {
            int zoneBeginOff = i;
            // parse +08:00
            while (NumberUtils.isDigit(c = buf[++i]) || c == ':') {
                // 数字或冒号
            }
            zoneObject = ofZoneId(new String(buf, zoneBeginOff, i - zoneBeginOff));
        } else {
            zoneObject = getDefaultZoneId();
        }
        if (c == '[') {
            if (supportedZoneRegion()) {
                int zoneRegionOff = i;
                while (buf[++i] != ']') {
                    // skip
                }
                zoneObject = ofZoneId(new String(buf, zoneRegionOff + 1, i - zoneRegionOff - 1));
                c = buf[++i];
            } else {
                while (buf[++i] != ']') {
                    // skip
                }
                c = buf[++i];
            }
        }
        if (c == endToken) {
            parseContext.endIndex = i;
            return ofTemporalDateTime(localDateTime, zoneObject);
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" + c +
                        "', expected '" + endToken + "'");
    }

    // default format yyyy*MM*dd*HH*mm*ss.SSS+08:00[Asia/Shanghai] not supported 'T'
    @Override
    protected final Temporal deserializeDefault(byte[] buf, int offset, byte endToken, JSONParseContext parseContext)
            throws Exception {
        LocalDateTime localDateTime = parseLocalDateTime(buf, offset, parseContext);
        int i = parseContext.endIndex;
        byte c = buf[i];
        Object zoneObject;
        if (c == 'Z' || c == 'z') {
            zoneObject = ZERO;
            c = buf[++i];
        } else if (c == '+' || c == '-') {
            int zoneBeginOff = i;
            // parse +08:00
            while (NumberUtils.isDigit(c = buf[++i]) || c == ':') {
                // skip
            }
            zoneObject = ofZoneId(new String(buf, zoneBeginOff, i - zoneBeginOff));
        } else {
            zoneObject = getDefaultZoneId();
        }
        if (c == '[') {
            if (supportedZoneRegion()) {
                int zoneRegionOff = i;
                while (buf[++i] != ']') {
                    // skip
                }
                zoneObject = ofZoneId(new String(buf, zoneRegionOff + 1, i - zoneRegionOff - 1));
                c = buf[++i];
            } else {
                while (buf[++i] != ']') {
                    // skip
                }
                c = buf[++i];
            }
        }
        if (c == endToken) {
            parseContext.endIndex = i;
            return ofTemporalDateTime(localDateTime, zoneObject);
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" +
                        (char) c + "', expected '" + (char) endToken + "'");
    }

    /**
     * 判断是否支持解析 {@code [Asia/Shanghai]} 这类时区区域标记，子类可覆盖以忽略该部分。
     *
     * @return 支持时区区域标记时返回 {@code true}，否则返回 {@code false}，默认 {@code true}
     */
    protected boolean supportedZoneRegion() {
        return true;
    }

    /**
     * 获取未显式指定时区时使用的默认时区对象。
     *
     * @return 默认时区对象，默认实现返回 {@link #defaultZoneId()} 的结果
     * @throws Exception 获取默认时区失败时抛出
     */
    protected Object getDefaultZoneId() throws Exception {
        return defaultZoneId();
    }

    /**
     * 由日期时间各字段与时区构造带时区的时间对象，子类可覆盖以返回其他 {@link Temporal} 实现。
     *
     * @param year         年
     * @param month        月，取值 1-12
     * @param dayOfMonth   日
     * @param hour         小时，取值 0-23
     * @param minute       分钟
     * @param second       秒
     * @param nanoOfSecond 纳秒
     * @param zone         时区对象，默认实现要求为 {@link ZoneId}
     * @return 构造出的时间对象，默认实现返回 {@link ZonedDateTime}
     * @throws Exception 字段值非法或时区类型不匹配时抛出
     */
    protected Temporal ofTemporalDateTime(int year, int month, int dayOfMonth, int hour, int minute, int second,
                                          int nanoOfSecond, Object zone) throws Exception {
        return ZonedDateTime.of(year, month, dayOfMonth, hour, minute, second, nanoOfSecond, (ZoneId) zone);
    }

    /**
     * 由本地日期时间与时区构造带时区的时间对象，子类可覆盖以返回其他 {@link Temporal} 实现。
     *
     * @param localDateTime 已解析出的本地日期时间
     * @param zone          时区对象，默认实现要求为 {@link ZoneId}
     * @return 构造出的时间对象，默认实现返回 {@link ZonedDateTime}
     * @throws Exception 时区类型不匹配时抛出
     */
    protected Temporal ofTemporalDateTime(LocalDateTime localDateTime, Object zone) throws Exception {
        return ZonedDateTime.of(localDateTime, (ZoneId) zone);
    }

    @Override
    protected Object valueOf(String value, Class<?> actualType) throws Exception {
        return ZonedDateTime.parse(value);
    }
}
