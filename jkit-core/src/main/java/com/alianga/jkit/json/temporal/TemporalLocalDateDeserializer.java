package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONParseContext;
import com.alianga.jkit.json.JSONTemporalDeserializer;
import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.json.internal.beans.DateTemplate;
import com.alianga.jkit.json.internal.beans.GeneralDate;
import com.alianga.jkit.json.internal.beans.GregorianDate;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.GenericParameterizedType;

import java.time.LocalDate;

/**
 * LocalDate反序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see DateTemplate
 */
public class TemporalLocalDateDeserializer extends JSONTemporalDeserializer {
    /**
     * 构造LocalDate反序列化器
     *
     * @param temporalConfig 时间类型的配置（含日期模板与时区等信息）
     */
    public TemporalLocalDateDeserializer(TemporalConfig temporalConfig) {
        super(temporalConfig);
    }

    protected void checkClass(GenericParameterizedType<?> genericParameterizedType) {
    }

    protected Object deserializeTemporal(char[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        // use dateTemplate && pattern
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return LocalDate.of(generalDate.getYear(), generalDate.getMonth(), generalDate.getDay());
    }

    protected Object deserializeTemporal(byte[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        // use dateTemplate && pattern
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return LocalDate.of(generalDate.getYear(), generalDate.getMonth(), generalDate.getDay());
    }

    // default ymd support yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd, yyyy~MM~dd
    @Override
    protected LocalDate deserializeDefault(char[] buf, int offset, char endToken, JSONParseContext jsonParseContext)
            throws Exception {
        int year = fourDigitsYear(buf, offset);
        int month;
        int day;
        int endIndex;
        // expected quick matching mode (yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd, yyyy~MM~dd)
        char symbol;
        if (year != -1 && ((symbol = buf[offset + 4]) == '-' || symbol == '/' || symbol == '.' || symbol == '~')
                && (month = digits2Chars(buf, offset + 5)) != -1 && buf[offset + 7] == symbol
                && (day = digits2Chars(buf, offset + 8)) != -1 && buf[endIndex = offset + 10] == endToken) {
            jsonParseContext.endIndex = endIndex;
            return LocalDate.of(year, month, day);
        }
        // compatible mode
        return compatibleDefault(year, buf, offset, endToken, jsonParseContext);
    }

    /**
     * 兼容模式解析日期字符（年月日位数不固定、支持负数年份，分隔符支持 '-'、'/'、'.'、'~'）
     *
     * @param year 已预读到的四位年份，为 -1 表示尚未解析年份
     * @param buf 字符缓冲区
     * @param offset 日期内容在缓冲区中的起始位置
     * @param endToken 日期内容的结束字符（通常为引号）
     * @param jsonParseContext 解析上下文，解析成功后写入结束位置
     * @return 解析得到的 {@link LocalDate}
     * @throws Exception 日期格式非法时抛出
     *         {@link JSONException}
     */
    protected static final LocalDate compatibleDefault(int year, char[] buf, int offset, char endToken,
                                                       JSONParseContext jsonParseContext) throws Exception {
        int i = offset;
        int month;
        int day;
        char c;
        char symbol;
        if (year != -1) {
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
        if (buf[++i] == endToken) {
            jsonParseContext.endIndex = i;
            return LocalDate.of(year, month, day);
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" +
                        buf[i] + "', expected '" + (char) endToken + "'");
    }

    // default yyyy-MM-dd compatible yyyy.MM?.dd?
    @Override
    protected LocalDate deserializeDefault(byte[] buf, int offset, byte endToken, JSONParseContext jsonParseContext)
            throws Exception {
        int year = fourDigitsYear(buf, offset);
        int month;
        int day;
        int endIndex;
        // expected quick matching mode (yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd, yyyy~MM~dd)
        byte symbol;
        if (year != -1 && ((symbol = buf[offset + 4]) == '-' || symbol == '/' || symbol == '.' || symbol == '~')
                && (month = digits2Bytes(buf, offset + 5)) != -1 && buf[offset + 7] == symbol
                && (day = digits2Bytes(buf, offset + 8)) != -1 && buf[endIndex = offset + 10] == endToken) {
            jsonParseContext.endIndex = endIndex;
            return LocalDate.of(year, month, day);
        }
        // compatible mode
        return compatibleDefault(year, buf, offset, endToken, jsonParseContext);
    }

    /**
     * 兼容模式解析日期字节（年月日位数不固定、支持负数年份，分隔符支持 '-'、'/'、'.'、'~'）
     *
     * @param year 已预读到的四位年份，为 -1 表示尚未解析年份
     * @param buf 字节缓冲区
     * @param offset 日期内容在缓冲区中的起始位置
     * @param endToken 日期内容的结束字节（通常为引号）
     * @param jsonParseContext 解析上下文，解析成功后写入结束位置
     * @return 解析得到的 {@link LocalDate}
     * @throws Exception 日期格式非法时抛出
     *         {@link JSONException}
     */
    protected static final LocalDate compatibleDefault(int year, byte[] buf, int offset, byte endToken,
                                                       JSONParseContext jsonParseContext) throws Exception {
        int i = offset;
        int month;
        int day;
        byte c;
        byte symbol;
        if (year != -1) {
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
        if (buf[++i] == endToken) {
            jsonParseContext.endIndex = i;
            return LocalDate.of(year, month, day);
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" +
                        (char) buf[i] + "', expected '" + (char) endToken + "'");
    }

    @Override
    protected LocalDate valueOf(String value, Class<?> actualType) throws Exception {
        return LocalDate.parse(value);
    }
}
