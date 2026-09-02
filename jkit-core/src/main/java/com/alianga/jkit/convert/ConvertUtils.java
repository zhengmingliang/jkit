package com.alianga.jkit.convert;

import com.alianga.jkit.DataUtils;
import com.alianga.jkit.DateUtils;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.collection.Booleans;
import com.alianga.jkit.math.Numbers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;

/**
 * Created by 郑明亮 on 2021/12/3 23:55.
 */

/**
 * 2021/12/3 23:55 <br>
 *
 * @author 郑明亮
 * @version 1.0
 */
public class ConvertUtils {
    private static final String NULL_STRING = "null";
    private static final String[] TRUE_FLAGS = {"ok", "y", "yes", "t", "true", "1"};

    private static final int UNSIGNED_MASK = 0xFF;

    /**
     * 转换为字符串类型的数据
     *
     * @param obj 要转换类型的对象
     * @return obj 的 toString 结果，obj 为 {@code null} 时返回 {@code null}
     */
    public static String toString(Object obj) {
        return toString(obj, null);
    }

    /**
     * 转换为字符串
     *
     * @param obj 要转换为字符串的对象
     * @param defaultValue 当obj为null时，默认返回的值
     * @return obj 的 toString 结果，obj 为 {@code null} 时返回 defaultValue
     */
    public static String toString(Object obj, String defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        return obj.toString();
    }

    /**
     * 转换为非null的字符串，当为null或“null”时，默认转为 "";
     *
     * @param obj 要转换类型的对象
     * @return obj 的 toString 结果；obj 为 {@code null} 或其字符串形式为 {@code "null"}（忽略大小写）时返回空串
     */
    public static String toNoneNullString(Object obj) {
        return toNoneNullString(obj, "");
    }

    /**
     * 转换为非null的字符串，当为null或“null”时，默认转为 "";
     *
     * @param str 要转换的字符串
     * @return 原字符串；str 为 {@code null} 或等于 {@code "null"}（忽略大小写）时返回空串
     */
    public static String toNoneNullString(String str) {
        return toNoneNullString(str, "");
    }

    /**
     * 转换为非null字符串
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 当被转换的字符串为null时，返回的默认值
     * @return obj 的 toString 结果；obj 为 {@code null} 或其字符串形式为 {@code "null"}（忽略大小写）时返回
     *         defaultValue
     */
    public static String toNoneNullString(Object obj, String defaultValue) {
        if (obj == null) {
            return defaultValue;
        }

        String strValue = obj.toString();
        if (NULL_STRING.equalsIgnoreCase(strValue)) {
            return defaultValue;
        }

        return strValue;
    }

    /**
     * 转换为非空字符串
     *
     * @param obj          要转换的字符串
     * @param defaultValue 当被转换的字符串为空字符串时，返回的默认值
     * @return 原字符串；obj 为 {@code null}、空串或等于 {@code "null"}（忽略大小写）时返回 defaultValue
     */
    public static String toNoneEmptyString(String obj, String defaultValue) {
        if (obj == null) {
            return defaultValue;
        }

        String strValue = obj;
        if (strValue.length() == 0 || NULL_STRING.equalsIgnoreCase(strValue)) {
            return defaultValue;
        }

        return strValue;
    }

    /**
     * 转换为非null对象实例
     *
     * @param <T>          默认值的类型
     * @param obj          要判断的对象
     * @param defaultValue 当被转换的字符串为null时，返回的默认值
     * @return obj 本身；obj 为 {@code null} 或其 {@code toString()} 为字符串 {@code "null"} 时返回 defaultValue
     */
    @SuppressWarnings("unchecked")
    public static <T> T toNoneNullObject(Object obj, T defaultValue) {
        if (obj == null) {
            return defaultValue;
        }

        String strValue = obj.toString();
        if (NULL_STRING.equalsIgnoreCase(strValue)) {
            return defaultValue;
        }

        return (T) obj;
    }

    /**
     * 转换为非null字符串
     *
     * @param str          要转换的字符串
     * @param defaultValue 当被转换的字符串为null时，返回的默认值
     * @return 原字符串；str 为 {@code null} 或等于 {@code "null"}（忽略大小写）时返回 defaultValue
     */
    public static String toNoneNullString(String str, String defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        if (str.equalsIgnoreCase(NULL_STRING)) {
            return defaultValue;
        }

        return str;
    }

    /**
     * 转为布尔类型
     *
     * @param obj 要转换类型的对象
     * @return obj 为 {@link Boolean#TRUE}，或其字符串形式为 {@code ok}、{@code y}、{@code yes}、{@code t}、
     *         {@code true}、{@code 1} 之一（忽略大小写）时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean toBoolean(Object obj) {
        return toBoolean(obj, (Object[]) TRUE_FLAGS);
    }

    /**
     * 转为布尔类型
     *
     * @param obj 要转换类型的对象
     * @param trueValues 该对象和obj一样时，则返回true
     * @return obj 为 {@link Boolean#TRUE}，或其字符串形式与 trueValues 中任意一项相等（忽略大小写）时返回
     *         {@code true}，否则返回 {@code false}
     */
    public static boolean toBoolean(Object obj, Object... trueValues) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof Boolean) {
            return Boolean.TRUE.equals(obj);
        }
        if (obj instanceof CharSequence) {
            String value = obj.toString();
            return Boolean.parseBoolean(value)
                    || DataUtils.orEqualsIgnoreCase(value, trueValues);
        }

        if (trueValues != null && trueValues.length > 0) {
            return DataUtils.orEqualsIgnoreCase(obj.toString(), trueValues);
        }
        return false;
    }

    /**
     * 转为Integer，转换失败时返回 {@code null}。
     *
     * @param obj 要转换类型的对象
     * @return 转换后的整数，obj 为 {@code null} 或转换失败时返回 {@code null}
     */
    public static Integer toInteger(Object obj) {
        return toInteger(obj, null);
    }

    /**
     * 将 byte 按无符号方式转为 int。
     *
     * @param value 要转换的字节
     * @return 该字节的无符号取值，范围 0 ~ 255
     */
    public static int toInt(byte value) {
        return value & UNSIGNED_MASK;
    }

    /**
     * 转为 int，obj 为 {@code null} 时返回 0。
     *
     * @param obj 要转换类型的对象
     * @return 转换后的整数，obj 为 {@code null} 时返回 0
     * @throws NumberFormatException 字符串形式为空白或无法解析为数字时抛出
     */
    public static int toInt(Object obj) {
        return toInt(obj, 0);
    }

    /**
     * 按大端顺序将字节数组的前 4 个字节转为 int。
     *
     * @param bytes 字节数组，长度需不小于 4
     * @return 由前 4 个字节按大端顺序拼接得到的整数
     */
    public static int toInt(byte[] bytes) {
        int result = 0;
        for (int i = 0; i < 4; i++) {
            result = (result << 8) | (bytes[i] & UNSIGNED_MASK);
        }
        return result;
    }

    /**
     * 转为 int，支持 {@link Number}、{@code byte[]}、字符串以及布尔字符串。
     *
     * <p>字符串无法直接解析时，会先尝试截取其中的第一段数字，再尝试按布尔字符串取 1 或 0。
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 当 obj 为 {@code null} 时返回的默认值
     * @return 转换后的整数，obj 为 {@code null} 时返回 defaultValue
     * @throws NumberFormatException 字符串形式为空白或无法解析为数字时抛出
     */
    public static int toInt(Object obj, int defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        if (obj instanceof Number) {
            return ((Number) obj).intValue();
        }

        if (obj instanceof byte[]) {
            return toInt((byte[]) obj);
        }
        String strValue = obj.toString();
        if (strValue.trim().length() == 0) {
            throw new NumberFormatException("当前字符不是数字");
        }
        if (obj instanceof CharSequence) {
            try {
                return new BigDecimal(strValue).intValue();
            } catch (NumberFormatException e) {
                String number = DataUtils.getFirstNumber(strValue);
                if (number.length() > 0) {
                    return new BigDecimal(number).intValue();
                } else {
                    if (Booleans.isBoolean(strValue)) {
                        return getNumberFromBoolean(strValue).intValue();
                    }

                }

            }
        }

        return Integer.parseInt(strValue);
    }

    /**
     * 根据布尔类型获取数字， true 返回1，false返回0
     *
     * @param strValue 要判断的对象，通常为布尔值或布尔字符串
     * @return 根据布尔类型获取数字， true 返回1，false返回0
     */
    public static Number getNumberFromBoolean(Object strValue) {
        boolean bool = toBoolean(strValue, (Object[]) TRUE_FLAGS);
        if (bool) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * 转为Integer
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 默认值
     * @return 转换后的整数，obj 为 {@code null} 或转换过程抛出异常时返回 defaultValue
     */
    public static Integer toInteger(Object obj, Integer defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        try {
            return toInt(obj);
        } catch (Exception e) {
//            e.printStackTrace();
        }
        return defaultValue;
    }

    /**
     * 转为Double，转换失败时返回 {@code null}。
     *
     * @param obj 要转换类型的对象
     * @return 转换后的浮点数，obj 为 {@code null} 或转换失败时返回 {@code null}
     */
    public static Double toDouble(Object obj) {
        return toDouble(obj, null);
    }

    /**
     * 转为 double 基本类型，支持 {@link Number}、字符串以及布尔字符串。
     *
     * <p>字符串无法直接解析时，会先尝试截取其中的第一段数字，再尝试按布尔字符串取 1 或 0。
     *
     * @param obj 要转换类型的对象
     * @return 转换后的浮点数，obj 为 {@code null} 时返回 0
     * @throws NumberFormatException 字符串形式无法解析为数字时抛出
     */
    public static double toDoubleValue(Object obj) {
        if (obj == null) {
            return 0D;
        }
        if (obj instanceof Number) {
            return ((Number) obj).doubleValue();
        }
        String strValue = obj.toString();
        if (obj instanceof CharSequence) {
            try {
                return new BigDecimal(strValue).doubleValue();
            } catch (NumberFormatException e) {
                String numberString = DataUtils.getFirstNumber(strValue);
                if (numberString.length() > 0) {
                    return new BigDecimal(numberString).doubleValue();
                } else {
                    if (Booleans.isBoolean(strValue)) {
                        return getNumberFromBoolean(strValue).doubleValue();
                    }
                }

            }
        }

        return Double.parseDouble(strValue);
    }

    /**
     * 转为Double
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 默认值
     * @return 转换后的浮点数，obj 为 {@code null} 或转换过程抛出异常时返回 defaultValue
     */
    public static Double toDouble(Object obj, Double defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        try {
            return toDoubleValue(obj);
        } catch (Exception e) {
            // 转换失败时返回调用方提供的默认值。
        }
        return defaultValue;
    }

    /**
     * 转为Long，转换失败时返回 {@code null}。
     *
     * @param obj 要转换类型的对象
     * @return 转换后的长整数，obj 为 {@code null} 或转换失败时返回 {@code null}
     */
    public static Long toLong(Object obj) {
        return toLong(obj, null);
    }

    /**
     * 转为 long 基本类型，obj 为 {@code null} 时返回 0。
     *
     * @param obj 要转换类型的对象
     * @return 转换后的长整数，obj 为 {@code null} 时返回 0
     * @throws NumberFormatException 字符串形式无法解析为数字时抛出
     */
    public static long toLongValue(Object obj) {
        return toLongValue(obj, 0L);
    }

    /**
     * 转为long类型
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 当 obj 为 {@code null} 时返回的默认值
     * @return 转换后的长整数，obj 为 {@code null} 时返回 defaultValue
     * @throws NumberFormatException 字符串形式无法解析为数字时抛出
     */
    public static long toLongValue(Object obj, long defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }

        String strValue = obj.toString();
        if (obj instanceof CharSequence) {
            try {
                return new BigDecimal(strValue).longValue();
            } catch (NumberFormatException e) {
                strValue = DataUtils.getFirstNumber(strValue);
                if (strValue.length() > 0) {
                    return new BigDecimal(strValue).longValue();
                } else {
                    strValue = obj.toString();
                    if (Booleans.isBoolean(strValue)) {
                        return getNumberFromBoolean(strValue).longValue();
                    }
                }
            }
        }

        long number = Long.parseLong(strValue);
        return number;
    }

    /**
     * 转为Long
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 默认值
     * @return 转换后的长整数，obj 为 {@code null} 或转换过程抛出异常时返回 defaultValue
     */
    public static Long toLong(Object obj, Long defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        try {
            return toLongValue(obj);
        } catch (Exception e) {
            // 转换失败时返回调用方提供的默认值。
        }
        return defaultValue;
    }

    /**
     * 转为BigDecimal
     *
     * @param obj 要转换类型的对象
     * @return 转换后的 BigDecimal，obj 为 {@code null} 或无法解析出数字时返回 {@code null}
     */
    public static BigDecimal toBigDecimal(Object obj) {
        return toBigDecimal(obj, null);
    }

    /**
     * 转为BigDecimal
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 默认值
     * @return 转换后的 BigDecimal；字符串为空串时返回 {@link BigDecimal#ZERO}；obj 为 {@code null} 或无法解析出
     *         数字时返回 defaultValue
     */
    public static BigDecimal toBigDecimal(Object obj, BigDecimal defaultValue) {
        if (obj == null) {
            return defaultValue;
        }
        String strValue = obj.toString();
        try {
            if (strValue.length() == 0) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(strValue);
        } catch (Exception e) {
            String numberString = Numbers.getFirstNumber(strValue);
            if (numberString.length() > 0) {
                return new BigDecimal(numberString);
            } else {
                if (Booleans.isBoolean(strValue)) {
                    return new BigDecimal(getNumberFromBoolean(strValue).intValue());
                }

            }

        }
        return defaultValue;
    }

    /**
     * 转换为Date类型
     *
     * @param obj 要转换类型的对象
     * @return 转换后的日期，obj 为 {@code null} 时返回 {@code null}
     */
    public static Date toDate(Object obj) {
        return toDate(obj, null);
    }

    /**
     * 转换为Date类型，支持 {@link Date}、{@link Calendar}、{@link LocalDate}、
     * {@link LocalDateTime}、{@link Instant}、{@link OffsetDateTime}、{@link ZonedDateTime}、
     * 10 位或 13 位时间戳，以及常见的日期时间字符串（自动推断格式，不经过 SimpleDateFormat）。
     * 2.0.1 起额外支持 {@link Instant}、{@link OffsetDateTime}、{@link ZonedDateTime}，
     * 字符串路径改为数字字段抽取。
     *
     * @param obj          要转换类型的对象
     * @param defaultValue 当 obj 为 {@code null} 时返回的默认值
     * @return 转换后的日期，obj 为 {@code null} 时返回 defaultValue
     */
    public static Date toDate(Object obj, Date defaultValue) {
        if (obj == null) {
            return defaultValue;
        }

        if (obj instanceof Date) {
            return (Date) obj;
        }

        if (obj instanceof Calendar) {
            return ((Calendar) obj).getTime();
        }

        if (obj instanceof Number) {
            return DateUtils.fromEpochNumber(((Number) obj).longValue());
        }

        if (obj instanceof Instant) {
            return Date.from((Instant) obj);
        }
        if (obj instanceof LocalDateTime) {
            return Date.from(((LocalDateTime) obj).atZone(ZoneId.systemDefault()).toInstant());
        }
        if (obj instanceof LocalDate) {
            return Date.from(((LocalDate) obj).atStartOfDay(ZoneId.systemDefault()).toInstant());
        }
        if (obj instanceof OffsetDateTime) {
            return Date.from(((OffsetDateTime) obj).toInstant());
        }
        if (obj instanceof ZonedDateTime) {
            return Date.from(((ZonedDateTime) obj).toInstant());
        }
        if (obj instanceof TemporalAccessor) {
            Date temporalDate = DateUtils.fromTemporal((TemporalAccessor) obj);
            if (temporalDate != null) {
                return temporalDate;
            }
        }

        return DateUtils.parse(obj.toString());
    }

    /**
     * 将 byte 转为 二进制字符串，默认返回 8 位，高位补0
     *
     * @param value 要转换的字节
     * @return 长度为 8 的二进制字符串，高位补 0
     */

    public static String toBinaryString(byte value) {
        String formatted = Integer.toBinaryString(value);
        if (formatted.length() > 8) {
            formatted = formatted.substring(formatted.length() - 8);
        }
        StringBuilder buf = new StringBuilder("00000000");
        buf.replace(8 - formatted.length(), 8, formatted);
        return buf.toString();
    }

    /**
     * 将 int 整数转为二进制字符串，默认返回 32 位，高位补0
     *
     * @param value 要转换的整数
     * @return 长度为 32 的二进制字符串，高位补 0
     */
    public static String toBinaryString(int value) {
        String formatted = Long.toBinaryString(value);
        StringBuilder buf = new StringBuilder(StringUtils.repeat('0', 32));
        buf.replace(32 - formatted.length(), 32, formatted);
        return buf.toString();
    }

    /**
     * 将 long 整数转为二进制字符串，默认返回 64 位，高位补0
     *
     * @param value 要转换的长整数
     * @return 长度为 64 的二进制字符串，高位补 0
     */
    public static String toBinaryString(long value) {
        String formatted = Long.toBinaryString(value);
        StringBuilder buf = new StringBuilder(StringUtils.repeat('0', 64));
        buf.replace(64 - formatted.length(), 64, formatted);
        return buf.toString();
    }

}
