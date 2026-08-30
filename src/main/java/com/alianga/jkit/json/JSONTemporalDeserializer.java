package com.alianga.jkit.json;

import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.json.internal.beans.DateTemplate;
import com.alianga.jkit.json.internal.utils.EnvUtils;
import com.alianga.jkit.json.temporal.*;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.ClassStrucWrap;
import com.alianga.jkit.reflect.GenericParameterizedType;

import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;

/**
 * java.time support
 * <p>
 * Deserialization using reflection
 * <p>
 * Localtime, localdate, localdatetime do not consider time zone
 *
 * @time 2022/8/13 15:08
 */
public abstract class JSONTemporalDeserializer extends JSONTypeDeserializer {
    /**
     * 日期格式分类：0 表示未指定格式（走默认解析），其他值表示具体的格式类型
     */
    protected final int patternType;
    final boolean isDefaultTemporal;
    /**
     * 指定了日期格式时使用的解析模板，未指定格式时为 {@code null}
     */
    protected DateTemplate dateTemplate;
    /**
     * 纳秒补位系数表，下标为缺失的位数，值为需要乘上的 10 的幂
     */
    protected static final int[] NANO_OF_SECOND_PADDING =
            {1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000};

    /**
     * 按时间类型配置构造反序列化器，未指定日期格式时使用默认模板，否则按格式创建 {@link DateTemplate}。
     *
     * @param temporalConfig 时间类型的解析配置，含目标类型与日期格式
     */
    protected JSONTemporalDeserializer(TemporalConfig temporalConfig) {
        checkClass(temporalConfig.getGenericParameterizedType());
        String pattern = temporalConfig.getDatePattern();
        patternType = getPatternType(pattern);
        isDefaultTemporal = patternType == 0;
        if (patternType == 0) {
            createDefaultTemplate();
        } else {
            dateTemplate = new DateTemplate(pattern);
        }
    }

    static JSONTypeDeserializer getTemporalDeserializerInstance(ClassStrucWrap.ClassWrapperType classWrapperType,
                                                                GenericParameterizedType genericParameterizedType,
                                                                JSONPropertyDefinition property) {
        TemporalConfig temporalConfig = TemporalConfig.of(genericParameterizedType, property);
        switch (classWrapperType) {
            case TemporalMonthDay: {
                return new TemporalMonthDayDeserializer(temporalConfig);
            }
            case TemporalYearMonth: {
                return new TemporalYearMonthDeserializer(temporalConfig);
            }
            case TemporalLocalDate: {
                return new TemporalLocalDateDeserializer(temporalConfig);
            }
            case TemporalLocalDateTime: {
                return new TemporalLocalDateTimeDeserializer(temporalConfig);
            }
            case TemporalLocalTime: {
                return new TemporalLocalTimeDeserializer(temporalConfig);
            }
            case TemporalInstant: {
                return new TemporalInstantDeserializer(temporalConfig);
            }
            case TemporalZonedDateTime: {
                return new TemporalZonedDateTimeDeserializer(temporalConfig);
            }
            case TemporalOffsetDateTime: {
                return new TemporalOffsetDateTimeDeserializer(temporalConfig);
            }
            default: {
                throw new UnsupportedOperationException();
            }
        }
    }

    /**
     * 未指定日期格式时创建默认解析模板，默认空实现，由子类按需覆写。
     */
    protected void createDefaultTemplate() {
    }

    // check
    /**
     * 校验目标类型是否为当前反序列化器支持的时间类型，不支持时由子类抛出异常。
     *
     * @param genericParameterizedType 目标类型信息
     */
    protected abstract void checkClass(GenericParameterizedType<?> genericParameterizedType);

    /**
     * Temporal 支持字符串("/')/null/时间戳
     *
     * @param buf               字符缓冲区
     * @param fromIndex         当前值的起始下标
     * @param parameterizedType 目标类型信息
     * @param defaultValue      默认值（本实现未使用）
     * @param endToken          当前值的结束符（数组或对象的结束字符）
     * @param jsonParseContext  解析上下文
     * @return 解析出的时间对象；内容为 {@code null} 字面量时返回 {@code null}
     * @throws Exception 内容无法解析为目标时间类型时抛出
     */
    @Override
    protected Object deserialize(CharSource charSource, char[] buf, int fromIndex,
                                 GenericParameterizedType parameterizedType, Object defaultValue, int endToken,
                                 JSONParseContext jsonParseContext) throws Exception {
        char beginChar = buf[fromIndex];
        if (beginChar == '"' || beginChar == '\'') {
            if (isDefaultTemporal) {
                return deserializeDefault(buf, fromIndex + 1, beginChar, jsonParseContext);
            } else {
                CHAR_SEQUENCE_STRING.skip(charSource, buf, fromIndex, beginChar, jsonParseContext);
                int endIndex = jsonParseContext.endIndex;
                try {
                    return deserializeTemporal(buf, fromIndex, endIndex, jsonParseContext);
                } catch (Throwable throwable) {
                    if (throwable instanceof InvocationTargetException) {
                        throwable = ((InvocationTargetException) throwable).getTargetException();
                    }
                    String source = new String(buf, fromIndex + 1, endIndex - fromIndex - 1);
                    String errorContextTextAt = createErrorContextText(buf, fromIndex);
                    throw new JSONException(
                            "Syntax error, at pos " + fromIndex + ", context text by '" + errorContextTextAt +
                                    "', text '" + source + "' cannot convert to " + parameterizedType.getActualType() +
                                    ", exception: " + throwable.getMessage());
                }
            }
        }
        if (beginChar == 'n') {
            return parseNull(buf, fromIndex, jsonParseContext);
        }
        if (supportedTime()) {
            try {
                long timestamp =
                        (Long) NUMBER_LONG.deserialize(charSource, buf, fromIndex, GenericParameterizedType.LongType,
                                null, endToken, jsonParseContext);
                return fromTime(timestamp);
            } catch (Throwable throwable) {
            }
        }
        // not support or custom handle ?
        String errorContextTextAt = createErrorContextText(buf, fromIndex);
        throw new JSONException(
                "Syntax error, at pos " + fromIndex + ", context text by '" + errorContextTextAt + "', unexpected '" +
                        beginChar + "' for Temporal Type, expected '\"' ");
    }

    /**
     * Temporal 暂时只支持字符串(")和null
     *
     * @param buf               字节缓冲区
     * @param fromIndex         当前值的起始下标
     * @param parameterizedType 目标类型信息
     * @param defaultValue      默认值（本实现未使用）
     * @param endToken          当前值的结束符（数组或对象的结束字符）
     * @param jsonParseContext  解析上下文
     * @return 解析出的时间对象；内容为 {@code null} 字面量时返回 {@code null}
     * @throws Exception 内容无法解析为目标时间类型时抛出
     */
    @Override
    protected Object deserialize(CharSource charSource, byte[] buf, int fromIndex,
                                 GenericParameterizedType parameterizedType, Object defaultValue, int endToken,
                                 JSONParseContext jsonParseContext) throws Exception {
        byte beginByte = buf[fromIndex];
        if (beginByte == '"' || beginByte == '\'') {
            if (isDefaultTemporal) {
                return deserializeDefault(buf, fromIndex + 1, beginByte, jsonParseContext);
            } else {
                CHAR_SEQUENCE_STRING.skip(charSource, buf, fromIndex, beginByte, jsonParseContext);
                int endIndex = jsonParseContext.endIndex;
                try {
                    return deserializeTemporal(buf, fromIndex, endIndex, jsonParseContext);
                } catch (Throwable throwable) {
                    if (throwable instanceof InvocationTargetException) {
                        throwable = ((InvocationTargetException) throwable).getTargetException();
                    }
                    String source = new String(buf, fromIndex + 1, endIndex - fromIndex - 1);
                    String errorContextTextAt = createErrorContextText(buf, fromIndex);
                    throw new JSONException(
                            "Syntax error, at pos " + fromIndex + ", context text by '" + errorContextTextAt +
                                    "', text '" + source + "' cannot convert to " + parameterizedType.getActualType() +
                                    ", exception: " + throwable.getMessage());
                }
            }
        }
        if (beginByte == 'n') {
            return parseNull(buf, fromIndex, jsonParseContext);
        }
        if (supportedTime()) {
            try {
                // long
                long timestamp =
                        (Long) NUMBER_LONG.deserialize(charSource, buf, fromIndex, GenericParameterizedType.LongType,
                                null, endToken, jsonParseContext);
                return fromTime(timestamp);
            } catch (Throwable throwable) {
            }
        }
        // not support or custom handle ?
        String errorContextTextAt = createErrorContextText(buf, fromIndex);
        throw new JSONException(
                "Syntax error, at pos " + fromIndex + ", context text by '" + errorContextTextAt + "', unexpected '" +
                        (char) beginByte + "' for Temporal Type, expected '\"' ");
    }

    /**
     * 由时间戳构造目标时间对象，默认不支持，由支持时间戳的子类覆写。
     *
     * @param timestamp 毫秒时间戳
     * @return 对应的时间对象
     */
    protected Object fromTime(long timestamp) {
        throw new UnsupportedOperationException();
    }

    /**
     * 判断该时间类型是否支持由时间戳解析。
     *
     * @return 支持时间戳时返回 {@code true}，默认返回 {@code false}
     */
    protected boolean supportedTime() {
        return false;
    }

    /**
     * 按指定的日期格式模板从字符缓冲区解析时间对象。
     *
     * @param buf              字符缓冲区
     * @param fromIndex        字符串值起始下标（指向起始引号）
     * @param toIndex          字符串值结束下标（指向结束引号）
     * @param jsonParseContext 解析上下文
     * @return 解析出的时间对象
     * @throws Exception 内容与格式不匹配或无法转换时抛出
     */
    protected abstract Object deserializeTemporal(char[] buf, int fromIndex, int toIndex,
                                                  JSONParseContext jsonParseContext) throws Exception;

    /**
     * 按指定的日期格式模板从字节缓冲区解析时间对象。
     *
     * @param buf              字节缓冲区
     * @param fromIndex        字符串值起始下标（指向起始引号）
     * @param toIndex          字符串值结束下标（指向结束引号）
     * @param jsonParseContext 解析上下文
     * @return 解析出的时间对象
     * @throws Exception 内容与格式不匹配或无法转换时抛出
     */
    protected abstract Object deserializeTemporal(byte[] buf, int fromIndex, int toIndex,
                                                  JSONParseContext jsonParseContext) throws Exception;

    /**
     * 未指定日期格式时，从字符缓冲区按标准格式解析时间对象。
     *
     * @param buf              字符缓冲区
     * @param offset           字符串内容起始下标（已跳过起始引号）
     * @param endChar          字符串的结束引号字符
     * @param jsonParseContext 解析上下文
     * @return 解析出的时间对象
     * @throws Exception 内容不符合标准格式时抛出
     */
    protected abstract Object deserializeDefault(char[] buf, int offset, char endChar,
                                                 JSONParseContext jsonParseContext) throws Exception;

    /**
     * 未指定日期格式时，从字节缓冲区按标准格式解析时间对象。
     *
     * @param buf              字节缓冲区
     * @param offset           字符串内容起始下标（已跳过起始引号）
     * @param endToken         字符串的结束引号字节
     * @param jsonParseContext 解析上下文
     * @return 解析出的时间对象
     * @throws Exception 内容不符合标准格式时抛出
     */
    protected abstract Object deserializeDefault(byte[] buf, int offset, byte endToken,
                                                 JSONParseContext jsonParseContext) throws Exception;

    /**
     * 从字节缓冲区读取 4 位数字年份。
     *
     * @param buf    字节缓冲区
     * @param offset 起始下标
     * @return 解析出的年份；4 个字节不全是数字字符时返回 {@code -1}
     */
    protected static final int fourDigitsYear(byte[] buf, int offset) {
        final int value = JSONMemoryHandle.JSON_ENDIAN.getInt(buf, offset);
        if ((value & 0xF0F0F0F0) == 0x30303030) {
            return THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 8 | (buf[offset + 1] & 0xf) << 4 |
                    (buf[offset + 2] & 0xf)] + buf[offset + 3];
        }
        return -1;
    }

    /**
     * 从字符缓冲区读取 4 位数字年份。
     *
     * @param buf    字符缓冲区
     * @param offset 起始下标
     * @return 解析出的年份；4 个字符不全是数字时返回 {@code -1}
     */
    protected static final int fourDigitsYear(char[] buf, int offset) {
        final long value = JSONMemoryHandle.JSON_ENDIAN.getLong(buf, offset);
        if ((value & 0xFFF0FFF0FFF0FFF0L) == 0x0030003000300030L) {
            return THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 8 | (buf[offset + 1] & 0xf) << 4 |
                    (buf[offset + 2] & 0xf)] + buf[offset + 3];
        }
        return -1;
    }

    /**
     * 从字符缓冲区解析秒的小数部分并换算为纳秒，不足 9 位时按 {@link #NANO_OF_SECOND_PADDING} 补位，
     * 解析结束位置写入 {@code parseContext.endIndex}。
     *
     * @param buf          字符缓冲区
     * @param offset       小数部分（小数点之后）的起始下标
     * @param parseContext 解析上下文
     * @return 换算后的纳秒值（0 ~ 999999999）
     */
    protected static final int parseNanoOfSecond(char[] buf, int offset, JSONParseContext parseContext) {
        boolean isDigitFlag;
        int i = offset;
        int cnt = 9;
        int c;
        int c1;
        int nanoOfSecond = 0;
        while ((isDigitFlag = NumberUtils.isDigit(c = buf[i])) && NumberUtils.isDigit(c1 = buf[++i])) {
            cnt -= 2;
            nanoOfSecond = nanoOfSecond * 100 + twoDigitsValue(c, c1);
            ++i;
        }
        if (isDigitFlag) {
            nanoOfSecond = (nanoOfSecond << 3) + (nanoOfSecond << 1) + (c & 0xf);
            --cnt;
        }
        if (cnt > 0) {
            nanoOfSecond *= NANO_OF_SECOND_PADDING[cnt];
        }
        parseContext.endIndex = i;
        return nanoOfSecond;
    }

    /**
     * 从字节缓冲区解析秒的小数部分并换算为纳秒，解析结束位置写入 {@code parseContext.endIndex}。
     *
     * @param buf          字节缓冲区
     * @param offset       小数部分（小数点之后）的起始下标
     * @param parseContext 解析上下文
     * @return 换算后的纳秒值（0 ~ 999999999）；小数位数非法时抛出
     *         {@link JSONException}
     */
    protected static final int parseNanoOfSecond(byte[] buf, int offset, JSONParseContext parseContext) {
        int nanoOfSecond;
        if ((nanoOfSecond = digits2Bytes(buf, offset)) != -1) {
            long mask;
            if ((mask = getDigits8Mask(buf, offset += 2)) != 0) {
                int cnt = (EnvUtils.LITTLE_ENDIAN ? Long.numberOfTrailingZeros(mask) >> 3 :
                        Long.numberOfLeadingZeros(mask) >> 3);
                parseContext.endIndex = offset + cnt;
                if (cnt == 7) {
                    return nanoOfSecond * 10000000
                            +
                            (THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 4 | (buf[offset + 1] & 0xf)] + buf[offset + 2]) *
                                    10000
                            + THREE_DIGITS_MUL10[(buf[offset + 3] & 0xf) << 8 | (buf[offset + 4] & 0xf) << 4 |
                            (buf[offset + 5] & 0xf)] + buf[offset + 6];
                } else {
                    return calculateNextDigits(nanoOfSecond, buf, offset, cnt);
                }
            } else {
                String errorContextTextAt = createErrorContextText(buf, offset - 2);
                throw new JSONException("Syntax error, at pos " + offset + ", context text by '" + errorContextTextAt +
                        "', illegal nanoOfSecond out of range(0-999999999)");
            }
        } else {
            int c;
            if (NumberUtils.isDigit(c = buf[offset])) {
                parseContext.endIndex = offset + 1;
                return (c & 0xf) * 100000000;
            }
        }
        String errorContextTextAt = createErrorContextText(buf, offset);
        throw new JSONException("Syntax error, at pos " + offset + ", context text by '" + errorContextTextAt +
                "', illegal nanoOfSecond");
    }

    /**
     * 在已解析出前两位小数的基础上继续解析剩余数字，并把结果补齐为纳秒。
     *
     * @param nanoOfSecond 已解析出的前两位小数值
     * @param buf          字节缓冲区
     * @param offset       剩余数字的起始下标
     * @param cnt          剩余数字的个数（0 ~ 6）
     * @return 补齐到 9 位后的纳秒值；{@code cnt} 超出 0~6 时原样返回 {@code nanoOfSecond}
     */
    protected static final int calculateNextDigits(int nanoOfSecond, byte[] buf, int offset, int cnt) {
        switch (cnt) {
            case 0:
                return nanoOfSecond * 10000000;
            case 1:
                return (nanoOfSecond * 10 + (buf[offset] & 0xF)) * 1000000;
            case 2:
                return (nanoOfSecond * 100 + TWO_DIGITS_VALUES[buf[offset] ^ ((buf[offset + 1] & 0xf) << 4)]) * 100000;
            case 3:
                return (nanoOfSecond * 1000 + THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 4 | (buf[offset + 1] & 0xf)] +
                        buf[offset + 2]) * 10000;
            case 4:
                return (nanoOfSecond * 10000 +
                        THREE_DIGITS_MUL10[(buf[offset] & 0xf) << 8 | (buf[offset + 1] & 0xf) << 4 |
                                (buf[offset + 2] & 0xf)] + buf[offset + 3]) * 1000;
            case 5:
                return (nanoOfSecond * 100000 + (buf[offset] & 0xf) * 10000 +
                        THREE_DIGITS_MUL10[(buf[offset + 1] & 0xf) << 8 | (buf[offset + 2] & 0xf) << 4 |
                                (buf[offset + 3] & 0xf)] + buf[offset + 4]) * 100;
            case 6:
                return (nanoOfSecond * 1000000 +
                        (TWO_DIGITS_VALUES[buf[offset] ^ ((buf[offset + 1] & 0xf) << 4)]) * 10000 +
                        THREE_DIGITS_MUL10[(buf[offset + 2] & 0xf) << 8 | (buf[offset + 3] & 0xf) << 4 |
                                (buf[offset + 4] & 0xf)] + buf[offset + 5]) * 10;
            default:
                break;
        }
        return nanoOfSecond;
    }

    // 标准模式解析
    /**
     * 从字节缓冲区解析 {@code yyyy-MM-dd HH:mm:ss[.SSS]} 形式的日期时间，
     * 日期分隔符支持 {@code - / . ~}，日期与时间之间支持 {@code T} 或空格，
     * 解析结束位置写入 {@code parseContext.endIndex}。
     *
     * @param buf          字节缓冲区
     * @param offset       日期时间内容的起始下标
     * @param parseContext 解析上下文
     * @return 解析出的 {@link LocalDateTime}；不满足标准格式时回退到兼容模式解析
     */
    protected static final LocalDateTime parseLocalDateTime(byte[] buf, final int offset,
                                                            JSONParseContext parseContext) {
        int year;
        int month;
        int day;
        int hour;
        int minute;
        int second;
        byte symbol;
        byte split;
        if ((year = fourDigitsYear(buf, offset)) != -1 &&
                ((symbol = buf[offset + 4]) == '-' || symbol == '/' || symbol == '.' || symbol == '~')
                && (month = JSONMemoryHandle.JSON_ENDIAN.digits2Bytes(buf, offset + 5)) != -1 &&
                buf[offset + 7] == symbol
                && (day = JSONMemoryHandle.JSON_ENDIAN.digits2Bytes(buf, offset + 8)) != -1 &&
                ((split = buf[offset + 10]) == 'T' || split == ' ')
                && (hour = JSONMemoryHandle.JSON_ENDIAN.digits2Bytes(buf, offset + 11)) != -1 && buf[offset + 13] == ':'
                && (minute = JSONMemoryHandle.JSON_ENDIAN.digits2Bytes(buf, offset + 14)) != -1 &&
                buf[offset + 16] == ':'
                && (second = JSONMemoryHandle.JSON_ENDIAN.digits2Bytes(buf, offset + 17)) != -1) {
            int i = offset + 19;
            if (buf[i] == '.') {
                return LocalDateTime.of(year, month, day, hour, minute, second,
                        parseNanoOfSecond(buf, i + 1, parseContext));
            }
            parseContext.endIndex = i;
            return LocalDateTime.of(year, month, day, hour, minute, second);
        }
        return compatibleParseLocalDateTime(buf, offset, parseContext);
    }

    /**
     * 从字符缓冲区解析 {@code yyyy-MM-dd HH:mm:ss[.SSS]} 形式的日期时间，
     * 日期分隔符支持 {@code - / . ~}，日期与时间之间支持 {@code T} 或空格，
     * 解析结束位置写入 {@code parseContext.endIndex}。
     *
     * @param buf          字符缓冲区
     * @param offset       日期时间内容的起始下标
     * @param parseContext 解析上下文
     * @return 解析出的 {@link LocalDateTime}；不满足标准格式时回退到兼容模式解析
     */
    protected static final LocalDateTime parseLocalDateTime(char[] buf, final int offset,
                                                            JSONParseContext parseContext) {
        int year;
        int month;
        int day;
        int hour;
        int minute;
        int second;
        char symbol;
        char split;
        if ((year = fourDigitsYear(buf, offset)) != -1 &&
                ((symbol = buf[offset + 4]) == '-' || symbol == '/' || symbol == '.' || symbol == '~')
                && (month = JSONMemoryHandle.JSON_ENDIAN.digits2Chars(buf, offset + 5)) != -1 &&
                buf[offset + 7] == symbol
                && (day = JSONMemoryHandle.JSON_ENDIAN.digits2Chars(buf, offset + 8)) != -1 &&
                ((split = buf[offset + 10]) == 'T' || split == ' ')
                && (hour = JSONMemoryHandle.JSON_ENDIAN.digits2Chars(buf, offset + 11)) != -1 && buf[offset + 13] == ':'
                && (minute = JSONMemoryHandle.JSON_ENDIAN.digits2Chars(buf, offset + 14)) != -1 &&
                buf[offset + 16] == ':'
                && (second = JSONMemoryHandle.JSON_ENDIAN.digits2Chars(buf, offset + 17)) != -1) {
            int i = offset + 19;
            if (buf[i] == '.') {
                return LocalDateTime.of(year, month, day, hour, minute, second,
                        parseNanoOfSecond(buf, i + 1, parseContext));
            }
            parseContext.endIndex = i;
            return LocalDateTime.of(year, month, day, hour, minute, second);
        }
        return compatibleParseLocalDateTime(buf, offset, parseContext);
    }

    // 兼容模式解析LocalDateTime
    private static LocalDateTime compatibleParseLocalDateTime(byte[] buf, int offset, JSONParseContext parseContext) {
        int i = offset;
        int year;
        int month;
        int day;
        int hour;
        int minute;
        int second;
        byte symbol;
        byte split;
        byte c;
        if ((year = fourDigitsYear(buf, offset)) != -1) {
            i += 3;
        } else {
            if (buf[i] == '-' && (year = fourDigitsYear(buf, i + 1)) != -1) {
                year = -year;
                i += 4;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', year field error ");
            }
        }
        while (NumberUtils.isDigit(c = buf[++i])) {
            year = year * 10 + (c & 0xf);
        }
        if ((symbol = c) != '-' && symbol != '/' && symbol != '.' && symbol != '~') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                    "', default symbol error, only support '-' or '/' or '.' or '~', but " + symbol);
        }
        if ((month = digits2Bytes(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                month = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', month field error ");
            }
        } else {
            ++i;
        }
        if (buf[++i] != symbol) {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException(
                    "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', expect '" +
                            (char) symbol + "', but '" + (char) buf[i] + "'");
        }
        if ((day = digits2Bytes(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                day = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', day field error ");
            }
        } else {
            ++i;
        }
        if ((split = buf[++i]) != 'T' && split != ' ') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                    "', expect 'T' or ' ', but '" + (char) buf[i] + "'");
        }
        // HH:mm:ss
        if ((hour = digits2Bytes(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                hour = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', hour field error ");
            }
        } else {
            ++i;
        }
        if (buf[++i] != ':') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException(
                    "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', expect ':', but '" +
                            (char) buf[i] + "'");
        }
        if ((minute = digits2Bytes(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                minute = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', minute field error ");
            }
        } else {
            ++i;
        }
        if (buf[++i] != ':') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException(
                    "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', expect ':', but '" +
                            (char) buf[i] + "'");
        }
        if ((second = digits2Bytes(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                second = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', second field error ");
            }
        } else {
            ++i;
        }
        // next .
        c = buf[++i];
        if (c == '.') {
            return LocalDateTime.of(year, month, day, hour, minute, second,
                    parseNanoOfSecond(buf, i + 1, parseContext));
        }
        parseContext.endIndex = i;
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    // 兼容模式解析LocalDateTime
    private static LocalDateTime compatibleParseLocalDateTime(char[] buf, int offset, JSONParseContext parseContext) {
        int i = offset;
        int year;
        int month;
        int day;
        int hour;
        int minute;
        int second;
        char symbol;
        char split;
        char c;
        if ((year = fourDigitsYear(buf, offset)) != -1) {
            i += 3;
        } else {
            if (buf[i] == '-' && (year = fourDigitsYear(buf, i + 1)) != -1) {
                year = -year;
                i += 4;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', year field error ");
            }
        }
        while (NumberUtils.isDigit(c = buf[++i])) {
            year = year * 10 + (c & 0xf);
        }
        if ((symbol = c) != '-' && symbol != '/' && symbol != '.' && symbol != '~') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                    "', default symbol error, only support '-' or '/' or '.' or '~', but " + symbol);
        }
        if ((month = digits2Chars(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                month = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', month field error ");
            }
        } else {
            ++i;
        }
        if (buf[++i] != symbol) {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException(
                    "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', expect '" + symbol +
                            "', but '" + buf[i] + "'");
        }
        if ((day = digits2Chars(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                day = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', day field error ");
            }
        } else {
            ++i;
        }
        if ((split = buf[++i]) != 'T' && split != ' ') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                    "', expect 'T' or ' ', but '" + buf[i] + "'");
        }
        // HH:mm:ss
        if ((hour = digits2Chars(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                hour = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', hour field error ");
            }
        } else {
            ++i;
        }
        if (buf[++i] != ':') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException(
                    "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', expect ':', but '" +
                            buf[i] + "'");
        }
        if ((minute = digits2Chars(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                minute = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', minute field error ");
            }
        } else {
            ++i;
        }
        if (buf[++i] != ':') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException(
                    "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', expect ':', but '" +
                            buf[i] + "'");
        }
        if ((second = digits2Chars(buf, ++i)) == -1) {
            if (NumberUtils.isDigit(c = buf[i])) {
                second = c & 0xf;
            } else {
                String errorContextTextAt = createErrorContextText(buf, i);
                throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                        "', second field error ");
            }
        } else {
            ++i;
        }
        // next .
        c = buf[++i];
        if (c == '.') {
            return LocalDateTime.of(year, month, day, hour, minute, second,
                    parseNanoOfSecond(buf, i + 1, parseContext));
        }
        parseContext.endIndex = i;
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }
}
