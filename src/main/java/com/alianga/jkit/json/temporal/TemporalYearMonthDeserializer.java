package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONParseContext;
import com.alianga.jkit.json.JSONTemporalDeserializer;
import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.json.internal.beans.DateTemplate;
import com.alianga.jkit.json.internal.beans.GeneralDate;
import com.alianga.jkit.json.internal.beans.GregorianDate;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.GenericParameterizedType;

import java.time.YearMonth;

/**
 * YearMonth反序列化
 *
 * @see GregorianDate
 * @see DateTemplate
 */
public class TemporalYearMonthDeserializer extends JSONTemporalDeserializer {
    /**
     * 使用指定的时间配置构建 YearMonth 反序列化器。
     *
     * @param temporalConfig 时间格式配置，提供解析所需的 pattern 与模板
     */
    public TemporalYearMonthDeserializer(TemporalConfig temporalConfig) {
        super(temporalConfig);
    }

    protected void checkClass(GenericParameterizedType<?> genericParameterizedType) {
    }

    protected Object deserializeTemporal(char[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        // use dateTemplate && pattern
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return YearMonth.of(generalDate.getYear(), generalDate.getMonth());
    }

    protected Object deserializeTemporal(byte[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        // use dateTemplate && pattern
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return YearMonth.of(generalDate.getYear(), generalDate.getMonth());
    }

    // default ymd support yyyy-MM, yyyy/MM, yyyy.MM, yyyy~MM
    @Override
    protected YearMonth deserializeDefault(char[] buf, int offset, char endToken, JSONParseContext jsonParseContext)
            throws Exception {
        int year = fourDigitsYear(buf, offset);
        int month;
        int endIndex;
        // expected quick matching mode (yyyy-MM, yyyy/MM, yyyy.MM, yyyy~MM)
        char symbol;
        if (year != -1 && ((symbol = buf[offset + 4]) == '-' || symbol == '/' || symbol == '.' || symbol == '~')
                && (month = digits2Chars(buf, offset + 5)) != -1 && buf[endIndex = offset + 7] == endToken) {
            jsonParseContext.endIndex = endIndex;
            return YearMonth.of(year, month);
        }
        // compatible mode
        return compatibleDefault(year, buf, offset, endToken, jsonParseContext);
    }

    /**
     * 兼容模式解析 yyyy-MM（分隔符可为 {@code -}、{@code /}、{@code .}、{@code ~}）形式的年月，
     * 支持负数年份与超过四位的年份，以及一位或两位的月份。
     *
     * @param year 快速匹配阶段已解析出的四位年份，未解析成功时为 -1
     * @param buf 待解析的字符缓冲区
     * @param offset 年份起始下标
     * @param endToken 值结束标记字符
     * @param jsonParseContext 解析上下文，解析成功后其 endIndex 被置为结束标记所在下标
     * @return 解析出的 YearMonth
     * @throws Exception 年份、月份或分隔符不合法，或结束位置不是预期的结束标记时抛出 JSONException
     */
    protected static final YearMonth compatibleDefault(int year, char[] buf, int offset, char endToken,
                                                       JSONParseContext jsonParseContext) throws Exception {
        int i = offset;
        int month;
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
        if (buf[++i] == endToken) {
            jsonParseContext.endIndex = i;
            return YearMonth.of(year, month);
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" +
                        buf[i] + "', expected '" + (char) endToken + "'");
    }

    // default yyyy-MM compatible yyyy.MM?
    @Override
    protected YearMonth deserializeDefault(byte[] buf, int offset, byte endToken, JSONParseContext jsonParseContext)
            throws Exception {
        int year = fourDigitsYear(buf, offset);
        int month;
        int endIndex;
        // expected quick matching mode (yyyy-MM, yyyy/MM, yyyy.MM, yyyy~MM)
        byte symbol;
        if (year != -1 && ((symbol = buf[offset + 4]) == '-' || symbol == '/' || symbol == '.' || symbol == '~')
                && (month = digits2Bytes(buf, offset + 5)) != -1 && buf[endIndex = offset + 7] == endToken) {
            jsonParseContext.endIndex = endIndex;
            return YearMonth.of(year, month);
        }
        // compatible mode
        return compatibleDefault(year, buf, offset, endToken, jsonParseContext);
    }

    /**
     * 兼容模式解析 yyyy-MM（分隔符可为 {@code -}、{@code /}、{@code .}、{@code ~}）形式的年月，
     * 支持负数年份与超过四位的年份，以及一位或两位的月份。
     *
     * @param year 快速匹配阶段已解析出的四位年份，未解析成功时为 -1
     * @param buf 待解析的字节缓冲区
     * @param offset 年份起始下标
     * @param endToken 值结束标记字节
     * @param jsonParseContext 解析上下文，解析成功后其 endIndex 被置为结束标记所在下标
     * @return 解析出的 YearMonth
     * @throws Exception 年份、月份或分隔符不合法，或结束位置不是预期的结束标记时抛出 JSONException
     */
    protected static final YearMonth compatibleDefault(int year, byte[] buf, int offset, byte endToken,
                                                       JSONParseContext jsonParseContext) throws Exception {
        int i = offset;
        int month;
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
        if (buf[++i] == endToken) {
            jsonParseContext.endIndex = i;
            return YearMonth.of(year, month);
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" +
                        (char) buf[i] + "', expected '" + (char) endToken + "'");
    }

    @Override
    protected YearMonth valueOf(String value, Class<?> actualType) throws Exception {
        return YearMonth.parse(value);
    }
}
