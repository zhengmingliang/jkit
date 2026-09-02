package com.alianga.jkit.json.temporal;

import com.alianga.jkit.json.JSONParseContext;
import com.alianga.jkit.json.JSONTemporalDeserializer;
import com.alianga.jkit.json.exceptions.JSONException;
import com.alianga.jkit.json.internal.beans.GeneralDate;
import com.alianga.jkit.json.internal.beans.GregorianDate;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.GenericParameterizedType;

import java.time.Instant;
import java.time.temporal.Temporal;

/**
 * Instant反序列化
 *
 * @time 2022/8/13 15:06
 * @see GregorianDate
 * @see com.alianga.jkit.json.internal.beans.DateTemplate
 */
public class TemporalInstantDeserializer extends JSONTemporalDeserializer {
    /**
     * 使用指定的时间配置构造 Instant 反序列化器
     *
     * @param temporalConfig 时间日期配置，提供解析所用的日期模板
     */
    public TemporalInstantDeserializer(TemporalConfig temporalConfig) {
        super(temporalConfig);
    }

    protected void checkClass(GenericParameterizedType<?> genericParameterizedType) {
    }

    protected Object deserializeTemporal(char[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        long millis = dateTemplate.parseTime(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return Instant.ofEpochMilli(millis);
    }

    protected Object deserializeTemporal(byte[] buf, int fromIndex, int endIndex, JSONParseContext jsonParseContext)
            throws Exception {
        long millis = dateTemplate.parseTime(buf, fromIndex + 1, endIndex - fromIndex - 1, ZERO_TIME_ZONE);
        return Instant.ofEpochMilli(millis);
    }

    // use default pattern yyyy*MM*dd*HH*mm*ss.SZ?
    @Override
    protected Object deserializeDefault(char[] buf, int offset, char endToken, JSONParseContext parseContext)
            throws Exception {
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
        long epochSecond = GeneralDate.getSeconds(year, month, day, hour, minute, second);
        int nanoOfSecond = 0;
        if (c == '.') {
            nanoOfSecond = parseNanoOfSecond(buf, i + 1, parseContext);
            c = buf[i = parseContext.endIndex];
        }
        switch (c) {
            case 'z':
            case 'Z': {
                c = buf[++i];
            }
            default: {
                if (c == endToken) {
                    parseContext.endIndex = i;
                    return Instant.ofEpochSecond(epochSecond, nanoOfSecond);
                }
            }
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" + c +
                        "', expected '" + endToken + "'");
    }

    // use default supported pattern like yyyy?MM?dd?HH:mm:ss.SZ
    @Override
    protected Temporal deserializeDefault(byte[] buf, int offset, byte endToken, JSONParseContext parseContext)
            throws Exception {
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
        long epochSecond = GeneralDate.getSeconds(year, month, day, hour, minute, second);
        int nanoOfSecond = 0;
        if (c == '.') {
            nanoOfSecond = parseNanoOfSecond(buf, i + 1, parseContext);
            c = buf[i = parseContext.endIndex];
        }
        switch (c) {
            case 'z':
            case 'Z': {
                c = buf[++i];
            }
            default: {
                if (c == endToken) {
                    parseContext.endIndex = i;
                    return Instant.ofEpochSecond(epochSecond, nanoOfSecond);
                }
            }
        }
        String errorContextTextAt = createErrorContextText(buf, i);
        throw new JSONException(
                "Syntax error, at pos " + i + ", context text by '" + errorContextTextAt + "', unexpected token '" +
                        (char) c + "', expected '" + (char) endToken + "'");
    }

    @Override
    protected Object valueOf(String value, Class<?> actualType) throws Exception {
        return Instant.parse(value);
    }

    @Override
    protected Object fromTime(long timestamp) {
        long epochSecond = timestamp / 1000;
        long millis = timestamp - epochSecond;
        return Instant.ofEpochSecond(epochSecond, millis * 1000000);
    }

    @Override
    protected boolean supportedTime() {
        return true;
    }
}
