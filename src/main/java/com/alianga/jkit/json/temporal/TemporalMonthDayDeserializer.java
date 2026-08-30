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
import java.time.MonthDay;

/**
 * MonthDay反序列化
 *
 * @see GregorianDate
 * @see DateTemplate
 */
public class TemporalMonthDayDeserializer extends JSONTemporalDeserializer {
    /**
     * 使用指定的时间配置构建 MonthDay 反序列化器。
     *
     * @param temporalConfig 时间格式配置，提供解析所需的 pattern 与模板
     */
    public TemporalMonthDayDeserializer(TemporalConfig temporalConfig) {
        super(temporalConfig);
    }

    protected void checkClass(GenericParameterizedType<?> genericParameterizedType) {
    }

    protected MonthDay deserializeTemporal(char[] buf, int fromIndex, int endIndex, JSONParseContext parseContext)
            throws Exception {
        // use dateTemplate && pattern
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return MonthDay.of(generalDate.getMonth(), generalDate.getDay());
    }

    protected MonthDay deserializeTemporal(byte[] buf, int fromIndex, int endIndex, JSONParseContext parseContext)
            throws Exception {
        // use dateTemplate && pattern
        GeneralDate generalDate =
                dateTemplate.parseGeneralDate(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return MonthDay.of(generalDate.getMonth(), generalDate.getDay());
    }

    // default md support MM-dd, MM/dd, MM.dd, MM~dd
    @Override
    protected MonthDay deserializeDefault(char[] buf, int offset, char endToken, JSONParseContext parseContext)
            throws Exception {
        int month;
        int day;
        int endIndex;
        // expected quick matching mode (MM-dd, --MM-dd)
        if (buf[offset] == '-' && buf[offset + 1] == '-') {
            offset += 2;
        }
        if ((month = digits2Chars(buf, offset)) != -1 && (buf[offset + 2] == '-')
                && (day = digits2Chars(buf, offset + 3)) != -1 && buf[endIndex = offset + 5] == endToken) {
            parseContext.endIndex = endIndex;
            return MonthDay.of(month, day);
        }
        // compatible mode
        return compatibleDefault(buf, offset, endToken, parseContext);
    }

    /**
     * 逐字符兼容解析 MonthDay，支持月、日为 1 位或 2 位数字，分隔符只支持 '-'。
     *
     * @param buf          待解析的字符缓冲区
     * @param offset       月份起始下标
     * @param endToken     日期结束标记字符
     * @param parseContext 解析上下文，解析成功后其 endIndex 指向结束标记位置
     * @return 解析出的 MonthDay
     * @throws Exception 月份、日或分隔符不合法，或未在预期位置遇到结束标记时抛出 JSONException
     */
    protected static final MonthDay compatibleDefault(char[] buf, int offset, char endToken,
                                                      JSONParseContext parseContext) throws Exception {
        int i = offset;
        int month;
        int day;
        char c;
        char symbol;
        if ((month = digits2Chars(buf, i)) == -1) {
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
        if ((symbol = buf[++i]) != '-') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                    "', default symbol error, only support '-', but " + symbol);
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
            parseContext.endIndex = i;
            return MonthDay.of(month, day);
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" +
                        buf[i] + "', expected '" + (char) endToken + "'");
    }

    // default yyyy-MM-dd compatible yyyy.MM?.dd?
    @Override
    protected MonthDay deserializeDefault(byte[] buf, int offset, byte endToken, JSONParseContext parseContext)
            throws Exception {
        int month;
        int day;
        int endIndex;
        // expected quick matching mode (MM-dd, --MM-dd)
        if (buf[offset] == '-' && buf[offset + 1] == '-') {
            offset += 2;
        }
        if ((month = digits2Bytes(buf, offset)) != -1 && (buf[offset + 2] == '-')
                && (day = digits2Bytes(buf, offset + 3)) != -1 && buf[endIndex = offset + 5] == endToken) {
            parseContext.endIndex = endIndex;
            return MonthDay.of(month, day);
        }
        // compatible mode
        return compatibleDefault(buf, offset, endToken, parseContext);
    }

    /**
     * 逐字节兼容解析 MonthDay，支持月、日为 1 位或 2 位数字，分隔符只支持 '-'。
     *
     * @param buf          待解析的字节缓冲区
     * @param offset       月份起始下标
     * @param endToken     日期结束标记字节
     * @param parseContext 解析上下文，解析成功后其 endIndex 指向结束标记位置
     * @return 解析出的 MonthDay
     * @throws Exception 月份、日或分隔符不合法，或未在预期位置遇到结束标记时抛出 JSONException
     */
    protected static final MonthDay compatibleDefault(byte[] buf, int offset, byte endToken,
                                                      JSONParseContext parseContext) throws Exception {
        int i = offset;
        int month;
        int day;
        byte c;
        byte symbol;
        if ((month = digits2Bytes(buf, i)) == -1) {
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
        if ((symbol = buf[++i]) != '-') {
            String errorContextTextAt = createErrorContextText(buf, i);
            throw new JSONException("Syntax error, at pos " + i + ", context text by '" + errorContextTextAt +
                    "', default symbol error, only support '-', but " + symbol);
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
            parseContext.endIndex = i;
            return MonthDay.of(month, day);
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
