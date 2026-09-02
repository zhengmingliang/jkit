package com.alianga.jkit.json;

import com.alianga.jkit.Base64Utils;
import com.alianga.jkit.json.internal.beans.GeneralDate;
import com.alianga.jkit.json.internal.utils.EnvUtils;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.math.Scientific;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON 序列化输出的抽象写入器，屏蔽字符数组、字节数组以及包装 {@link Writer} 等不同输出目标的差异，
 * 并针对数字、日期时间、字符串转义等场景提供高性能写入方法。
 */
public abstract class JSONWriter extends Writer {
    static final int MAX_ALLOW_ALLOCATION_SIZE = (1 << 30) + (1 << 29); // max 1.5GB
    static final int CACHE_BUFFER_SIZE;
    static final int MAX_CACHE_BUFFER_SIZE;
    static final int AVAILABLE_PROCESSORS = Runtime.getRuntime().availableProcessors();
    // Safe skip over boundary check space
    static final int SECURITY_UNCHECK_SPACE = 160;
    static final int CACHE_COUNT;
    static final int INIT_CACHE_COUNT = Math.max(2, AVAILABLE_PROCESSORS >> 3);
    static final AtomicInteger AUTO_SEQ = new AtomicInteger();
    static final int EMPTY_ARRAY_INT;
    static final short EMPTY_ARRAY_SHORT;
    static final int Z_QUOT_INT;
    static final short Z_QUOT_SHORT;
    static final int ZERO_DOT_32 = EnvUtils.BIG_ENDIAN ? '0' << 16 | '.' : '.' << 16 | '0';
    static final short ZERO_DOT_16 = EnvUtils.BIG_ENDIAN ? (short) ('0' << 8 | '.') : (short) ('.' << 8 | '0');
    static final int DOT_ZERO_32 = EnvUtils.BIG_ENDIAN ? '.' << 16 | '0' : '0' << 16 | '.';
    static final short DOT_ZERO_16 = EnvUtils.BIG_ENDIAN ? (short) ('.' << 8 | '0') : (short) ('0' << 8 | '.');
    static final int ZERO_ZERO_32 = '0' << 16 | '0';
    static final short ZERO_ZERO_16 = '0' << 8 | '0';
    static final char[] HEX_DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
    static final long[] POW10_LONG_VALUES = new long[]{
            10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000, 10000000000L, 100000000000L,
            1000000000000L, 10000000000000L, 100000000000000L, 1000000000000000L, 10000000000000000L,
            100000000000000000L, 1000000000000000000L, 9223372036854775807L
    };
    static final char[][] POSITIVE_DECIMAL_POWER_CHARS = new char[325][];
    static final char[][] NEGATIVE_DECIMAL_POWER_CHARS = new char[325][];
    static final long[] FOUR_DIGITS_64_BITS = new long[10000];
    static final int[] FOUR_DIGITS_32_BITS = new int[10000];
    static final int[] TWO_DIGITS_32_BITS = new int[100];
    static final short[] TWO_DIGITS_16_BITS = new short[100];
    static final int[] HEX_DIGITS_INT32 = new int[256];
    static final long[] HEX_DIGITS_INT16 = new long[256];

    static final BigInteger BI_TO_DECIMAL_BASE = BigInteger.valueOf(1000000000L);
    static final BigInteger BI_MAX_VALUE_FOR_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    static {
        // init memory:  4KB * 2 * Runtime.getRuntime().availableProcessors() / 8 -> 核心个数KB（最小2kb）
        CACHE_BUFFER_SIZE = /*EnvUtils.JDK_VERSION >= 1.8f ? 1 << 14 :*/ 1 << 12;
        // max memory:   3MB * 2 * 16 -> 96MB
        MAX_CACHE_BUFFER_SIZE = (1 << 20) * 3;

        int availableProcessors = AVAILABLE_PROCESSORS << 1;
        int cacheCount = 16;
        while (availableProcessors > cacheCount) {
            cacheCount <<= 1;
        }
        CACHE_COUNT = cacheCount;

        if (EnvUtils.BIG_ENDIAN) {
            EMPTY_ARRAY_INT = '[' << 16 | ']';
            EMPTY_ARRAY_SHORT = '[' << 8 | ']';
            Z_QUOT_INT = 'Z' << 16 | '"';
            Z_QUOT_SHORT = 'Z' << 8 | '"';
        } else {
            EMPTY_ARRAY_INT = ']' << 16 | '[';
            EMPTY_ARRAY_SHORT = ']' << 8 | '[';
            Z_QUOT_INT = '"' << 16 | 'Z';
            Z_QUOT_SHORT = '"' << 8 | 'Z';
        }

        for (long d1 = 0; d1 < 10; ++d1) {
            for (long d2 = 0; d2 < 10; ++d2) {
                long intVal64;
                int intVal32;
                if (EnvUtils.BIG_ENDIAN) {
                    intVal64 = (d1 + 48) << 16 | (d2 + 48);
                    intVal32 = ((int) d1 + 48) << 8 | ((int) d2 + 48);
                } else {
                    intVal64 = (d2 + 48) << 16 | (d1 + 48);
                    intVal32 = ((int) d2 + 48) << 8 | ((int) d1 + 48);
                }
                int k = (int) (d1 * 10 + d2);
                TWO_DIGITS_32_BITS[k] = (int) intVal64;
                TWO_DIGITS_16_BITS[k] = (short) intVal32;
                for (long d3 = 0; d3 < 10; ++d3) {
                    for (long d4 = 0; d4 < 10; ++d4) {
                        long int64;
                        int int32;
                        if (EnvUtils.BIG_ENDIAN) {
                            int64 = (intVal64 << 32) | (d3 + 48) << 16 | (d4 + 48);
                            int32 = (intVal32 << 16) | ((int) d3 + 48) << 8 | ((int) d4 + 48);
                        } else {
                            int64 = ((d4 + 48) << 48) | ((d3 + 48) << 32) | intVal64;
                            int32 = (((int) d4 + 48) << 24) | (((int) d3 + 48) << 16) | intVal32;
                        }
                        int index = (int) (d1 * 1000 + d2 * 100 + d3 * 10 + d4);
                        FOUR_DIGITS_64_BITS[index] = int64;
                        FOUR_DIGITS_32_BITS[index] = int32;
                    }
                }
            }
        }

        // 0-255 for HEX
        for (int b = 0; b < 256; b++) {
            int b1 = b >> 4;
            int b2 = b & 0xF;
            HEX_DIGITS_INT32[b] = EnvUtils.BIG_ENDIAN ? (HEX_DIGITS[b1] << 16 | HEX_DIGITS[b2]) :
                    (HEX_DIGITS[b2] << 16 | HEX_DIGITS[b1]);
            HEX_DIGITS_INT16[b] = EnvUtils.BIG_ENDIAN ? (HEX_DIGITS[b1] << 8 | HEX_DIGITS[b2]) :
                    (HEX_DIGITS[b2] << 8 | HEX_DIGITS[b1]);
        }

        for (int i = 0, len = POSITIVE_DECIMAL_POWER_CHARS.length; i < len; ++i) {
            String positive = "1.0E" + i;
            String negative = "1.0E-" + i;
            POSITIVE_DECIMAL_POWER_CHARS[i] = positive.toCharArray();
            NEGATIVE_DECIMAL_POWER_CHARS[i] = negative.toCharArray();
        }
        NEGATIVE_DECIMAL_POWER_CHARS[NEGATIVE_DECIMAL_POWER_CHARS.length - 1] = "4.9E-324".toCharArray();
    }

    JSONWriter() {
    }

    static ThreadLocal<Integer> THREAD_CACHE_INDEX = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return AUTO_SEQ.incrementAndGet() & CACHE_COUNT - 1;
        }

        // disable update
        @Override
        public void set(Integer value) {
        }
    };

    static JSONWriter forStringWriter(JSONConfig jsonConfig) {
        if (jsonConfig.isIgnoreEscapeCheck()) {
            return new JSONCharArrayWriter.IgnoreEscapeWriter();
        } else {
            return new JSONCharArrayWriter();
        }
    }

    /**
     * only use for toJsonBytes
     *
     * @param charset
     * @param jsonConfig
     * @return
     */
    static JSONWriter forBytesWriter(Charset charset, JSONConfig jsonConfig) {
        return new JSONByteArrayWriter(charset);
    }

    static JSONWriter forStreamWriter(Charset charset, JSONConfig jsonConfig) {
        return new JSONByteArrayWriter(charset);
    }

    static JSONWriter wrap(Writer writer) {
        return JSONWrapWriter.wrap(writer);
    }

    public abstract String toString();

    abstract StringBuffer toStringBuffer();

    abstract StringBuilder toStringBuilder();

    /**
     * 按平台默认字符集将已写入的内容转换为字节数组
     *
     * @return 已写入内容按默认字符集编码后的字节数组
     */
    protected byte[] toBytes() {
        return toBytes(Charset.defaultCharset());
    }

    /**
     * 将已写入的内容输出到指定的输出流
     *
     * @param os 目标输出流
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    protected abstract void toOutputStream(OutputStream os) throws IOException;

    /**
     * 按指定字符集将已写入的内容转换为字节数组
     *
     * @param charset 目标字符集
     * @return 已写入内容按 {@code charset} 编码后的字节数组
     */
    protected byte[] toBytes(Charset charset) {
        return toString().getBytes(charset);
    }

    /**
     * 判断已写入内容的最后一个字符是否为指定字符
     *
     * @param c 待比较的字符
     * @return 末尾字符为 {@code c} 时返回 {@code true}，否则返回 {@code false}
     */
    protected abstract boolean endsWith(int c);

    void reset() {
    }

    /**
     * 清空写入器已缓存的内容，默认实现为空操作，由具体子类按需覆写
     */
    public void clear() {
    }

    static final int writeNano(int nano, char[] buf, int off) {
        // 4 + 2 + 3
        buf[off++] = '.';
        // seg1 4
        int div1 = (int) (nano * 274877907L >>
                38); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(nano, 0x4189374bc6a7f0L); // nano / 1000;
        // 4
        int seg1 = (int) (div1 * 1374389535L >>
                37); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(div1, 0x28f5c28f5c28f5dL); // div1 / 100;
        // 2
        int seg2 = div1 - 100 * seg1;
        // 3
        int seg3 = nano - div1 * 1000;
        off += JSONMemoryHandle.putLong(buf, off, FOUR_DIGITS_64_BITS[seg1]);
        if (seg3 > 0) {
            off += JSONMemoryHandle.putInt(buf, off, TWO_DIGITS_32_BITS[seg2]);
            int pos = --off;
            char last = buf[pos];
            off += JSONMemoryHandle.putLong(buf, pos, FOUR_DIGITS_64_BITS[seg3]); // writeFourDigits(seg3, buf, pos);
            buf[pos] = last;
        } else {
            if (seg2 == 0 && (seg1 & 1) == 0 && seg1 % 5 == 0) {
                // 4 - 1
                --off;
            } else {
                // 4 + 2
                off += JSONMemoryHandle.putInt(buf, off, TWO_DIGITS_32_BITS[seg2]);
            }
        }
        return off;
    }

    /**
     * write zoneId(zoneOffset)
     *
     * @param zoneId the zone id or zone offset, ignored if null or empty
     * @throws IOException if an I/O error occurs
     */
    public final void writeZoneId(String zoneId) throws IOException {
        if (zoneId == null) {
            return;
        }
        // zoneID
        if (zoneId.length() > 0) {
            char c = zoneId.charAt(0);
            switch (c) {
                case 'Z': {
                    writeJSONToken('Z');
                    break;
                }
                case '+':
                case '-': {
                    write(zoneId);
                    break;
                }
                default: {
                    write('[');
                    write(zoneId);
                    write(']');
                }
            }
        }
    }

    /**
     * Ensure the call is secure.
     *
     * @param c the token character to write
     * @throws IOException if an I/O error occurs
     * @throws IndexOutOfBoundsException if the underlying buffer has no remaining space
     */
    public void writeJSONToken(char c) throws IOException {
        write(c);
    }

    /**
     * 写入字符数组中的一段短字符串（长度较小，跳过部分边界检查）
     *
     * @param chars 源字符数组
     * @param offset 起始下标
     * @param len 写入的字符个数
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeShortChars(char[] chars, int offset, int len) throws IOException;

    /**
     * 以十进制数值形式写入 long 值
     *
     * @param numValue 待写入的数值
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeLong(long numValue) throws IOException;

    /**
     * supported write number as string
     *
     * @param numValue the number to write
     * @param jsonConfig the JSON config, decides whether the number is quoted as a string
     * @throws IOException if an I/O error occurs
     */
    public final void writeLong(long numValue, JSONConfig jsonConfig) throws IOException {
        if (jsonConfig.isWriteNumberAsString()) {
            writeJSONToken('"');
            writeLong(numValue);
            writeJSONToken('"');
        } else {
            writeLong(numValue);
        }
    }

    /**
     * 以十进制数值形式写入 int 值
     *
     * @param numValue 待写入的数值
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeInt(int numValue) throws IOException;

    /**
     * 将 UUID 以带引号的标准字符串形式写入
     *
     * @param uuid 待写入的 UUID
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeUUID(UUID uuid) throws IOException;

    /**
     * 写入 double 数值
     *
     * @param numValue 待写入的数值
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeDouble(double numValue) throws IOException;

    /**
     * 写入 float 数值
     *
     * @param numValue 待写入的数值
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeFloat(float numValue) throws IOException;

    /**
     * 写入 JSON 的 null 字面量
     *
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeNull() throws IOException {
        write("null");
    }

    // only use by JIT pojo compact
    /**
     * 将 long 数组写为 JSON 数组
     *
     * @param values 待写入的数组
     * @param jsonConfig JSON 配置，决定数值是否加引号输出
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeLongArray(long[] values, JSONConfig jsonConfig) throws IOException {
        int len = values.length;
        if (len > 0) {
            writeJSONToken('[');
            int i = 1;
            writeLong(values[0]);
            if ((len & 1) == 0) {
                writeJSONToken(',');
                writeLong(values[1], jsonConfig);
                ++i;
            }
            for (; i < len; i = i + 2) {
                writeCommaLongValues(values[i], values[i + 1], jsonConfig);
            }
            writeJSONToken(']');
        } else {
            writeEmptyArray();
        }
    }

    /**
     * 连续写入两个以逗号分隔的 long 数值（每个数值前都会先写入一个逗号）
     *
     * @param val1 第一个数值
     * @param val2 第二个数值
     * @param jsonConfig JSON 配置，决定数值是否加引号输出
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    protected void writeCommaLongValues(long val1, long val2, JSONConfig jsonConfig) throws IOException {
        writeJSONToken(',');
        writeLong(val1, jsonConfig);
        writeJSONToken(',');
        writeLong(val2, jsonConfig);
    }

    // only use by JIT compact
    /**
     * 将 double 数组写为 JSON 数组
     *
     * @param values 待写入的数组
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeDoubleArray(double[] values) throws IOException {
        int len = values.length;
        if (len > 0) {
            writeJSONToken('[');
            int i = 1;
            writeDouble(values[0]);
            if ((len & 1) == 0) {
                writeJSONToken(',');
                writeDouble(values[1]);
                ++i;
            }
            for (; i < len; i = i + 2) {
                writeJSONToken(',');
                writeDouble(values[i]);
                writeJSONToken(',');
                writeDouble(values[i + 1]);
            }
            writeJSONToken(']');
        } else {
            writeEmptyArray();
        }
    }

    final void writeStringCompatibleNull(String value) throws IOException {
        if (value == null) {
            writeNull();
        } else {
            writeJSONString(value);
        }
    }

    // only use by JIT compact
    /**
     * 将字符串数组写为 JSON 数组，元素为 {@code null} 时输出 null 字面量
     *
     * @param values 待写入的数组
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeStringArray(String[] values) throws IOException {
        int len = values.length;
        if (len > 0) {
            writeJSONToken('[');
            int i = 1;
            writeStringCompatibleNull(values[0]);
            if ((len & 1) == 0) {
                writeJSONToken(',');
                writeStringCompatibleNull(values[1]);
                ++i;
            }
            for (; i < len; i = i + 2) {
                writeJSONToken(',');
                writeStringCompatibleNull(values[i]);
                writeJSONToken(',');
                writeStringCompatibleNull(values[i + 1]);
            }
            writeJSONToken(']');
        } else {
            writeEmptyArray();
        }
    }

    // only use by JIT compact
    /**
     * 将字符串集合写为 JSON 数组，元素为 {@code null} 时输出 null 字面量
     *
     * @param values 待写入的集合，元素必须是字符串
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeStringCollection(Collection values) throws IOException {
        int size = values.size();
        if (size > 0) {
            writeJSONToken('[');
            boolean hasAddFlag = false;
            for (Object value : values) {
                if (hasAddFlag) {
                    writeJSONToken(',');
                } else {
                    hasAddFlag = true;
                }
                writeStringCompatibleNull((String) value);
            }
            writeJSONToken(']');
        } else {
            writeEmptyArray();
        }
    }

    /**
     * 按 UTC 时区将时间戳写为 ISO-8601 格式的时刻字符串，时区标记固定为 {@code Z}
     *
     * @param epochSeconds 距离 1970-01-01T00:00:00Z 的秒数
     * @param nanos 纳秒部分
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeJSONInstant(long epochSeconds, int nanos) throws IOException {
        GeneralDate generalDate = new GeneralDate(epochSeconds * 1000, JSONGeneral.ZERO_TIME_ZONE);
        int year = generalDate.getYear();
        int month = generalDate.getMonth();
        int day = generalDate.getDay();
        int hour = generalDate.getHourOfDay();
        int minute = generalDate.getMinute();
        int second = generalDate.getSecond();
        writeJSONLocalDateTime(year, month, day, hour, minute, second, nanos, "Z");
    }

    /**
     * format "yyyy-MM-ddTHH:mm:ss.SSSSSSSSS"
     * format "yyyy-MM-ddTHH:mm:ss.SSSSSSSSSZ"
     * format "yyyy-MM-ddTHH:mm:ss.SSSSSSSSS+08:00"
     *
     * @param year 年
     * @param month 月，取值 1-12
     * @param day 日
     * @param hour 小时，取值 0-23
     * @param minute 分钟，取值 0-59
     * @param second 秒，取值 0-59
     * @param nano 纳秒部分，为 0 时不输出小数部分
     * @param zoneId 时区标识或时区偏移，为 {@code null} 或空串时不输出时区部分
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeJSONLocalDateTime(int year, int month, int day, int hour, int minute, int second,
                                                int nano, String zoneId) throws IOException;

    /**
     * write default format "yyyy-MM-dd"
     *
     * @param year 年
     * @param month 月，取值 1-12
     * @param day 日
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeJSONLocalDate(int year, int month, int day) throws IOException;

    /**
     * HH:mm:ss
     *
     * @param hourOfDay 小时，取值 0-23
     * @param minute 分钟，取值 0-59
     * @param second 秒，取值 0-59
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeTime(int hourOfDay, int minute, int second) throws IOException;

    /**
     * "HH:mm:ss.SSSS"
     *
     * @param hourOfDay 小时，取值 0-23
     * @param minute 分钟，取值 0-59
     * @param second 秒，取值 0-59
     * @param nano 纳秒部分，为 0 时不输出小数部分
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeJSONTimeWithNano(int hourOfDay, int minute, int second, int nano) throws IOException;

    /**
     * 序列化java.time.Duration类
     *
     * @param seconds 时长的秒数部分
     * @param nano 时长的纳秒部分
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeJSONDuration(long seconds, int nano) throws IOException;

    /**
     * 序列化java.time.Period类
     *
     * @param years 年数，为 0 时不输出该字段
     * @param months 月数，为 0 时不输出该字段
     * @param days 天数，为 0 时不输出该字段
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeJSONPeriod(int years, int months, int days) throws IOException {
        if (years == 0 && months == 0 && days == 0) {
            write("\"P0D\"");
            return;
        }
        writeJSONToken('"');
        writeJSONToken('P');
        if (years != 0) {
            writeInt(years);
            writeJSONToken('Y');
        }
        if (months != 0) {
            writeInt(months);
            writeJSONToken('M');
        }
        if (days != 0) {
            writeInt(days);
            writeJSONToken('D');
        }
        writeJSONToken('"');
    }

    /**
     * -&gt; "YYYY-MM"
     *
     * @param year 年
     * @param monthValue 月，取值 1-12，不足两位时补前导 0
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeJSONYearMonth(int year, int monthValue) throws IOException {
        writeJSONToken('"');
        writeInt(year);
        writeJSONToken('-');
        if (monthValue < 10) {
            writeJSONToken('0');
        }
        writeInt(monthValue);
        writeJSONToken('"');
    }

    /**
     * -&gt; "--MM-dd"
     *
     * @param monthValue 月，取值 1-12，不足两位时补前导 0
     * @param dayOfMonth 日，不足两位时补前导 0
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeJSONDefaultMonthDay(int monthValue, int dayOfMonth) throws IOException {
        writeJSONToken('"');
        writeJSONToken('-');
        writeJSONToken('-');
        if (monthValue < 10) {
            writeJSONToken('0');
        }
        writeInt(monthValue);
        writeJSONToken('-');
        if (dayOfMonth < 10) {
            writeJSONToken('0');
        }
        writeInt(dayOfMonth);
        writeJSONToken('"');
    }

    /**
     * yyyy-MM-dd HH:mm:ss
     *
     * @param year 年
     * @param month 月，取值 1-12
     * @param day 日
     * @param hourOfDay 小时，取值 0-23
     * @param minute 分钟，取值 0-59
     * @param second 秒，取值 0-59
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeDate(int year, int month, int day, int hourOfDay, int minute, int second)
            throws IOException;

    /**
     * 写入yyyy-MM-dd HH:mm:ss格式
     *
     * @param date 待写入的日期
     * @param jsonConfig JSON 配置，从中读取输出使用的时区
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeDate(Date date, JSONConfig jsonConfig) throws IOException {
        writeJSONToken('"');
        TimeZone timeZone = jsonConfig.getTimezone();
        GeneralDate generalDate = new GeneralDate(date.getTime(), timeZone);
        int year = generalDate.getYear();
        int month = generalDate.getMonth();
        int day = generalDate.getDay();
        int hourOfDay = generalDate.getHourOfDay();
        int minute = generalDate.getMinute();
        int second = generalDate.getSecond();
        writeDate(year, month, day, hourOfDay, minute, second);
        writeJSONToken('"');
    }

    /**
     * 以十进制数值形式写入 BigInteger
     *
     * @param bigInteger 待写入的大整数
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public abstract void writeBigInteger(BigInteger bigInteger) throws IOException;

    /**
     * 支持数字转字符串
     *
     * @param bigInteger 待写入的大整数
     * @param jsonConfig JSON 配置，决定数值是否加引号输出
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeBigInteger(BigInteger bigInteger, JSONConfig jsonConfig) throws IOException {
        if (jsonConfig.isWriteNumberAsString()) {
            writeJSONToken('"');
            writeBigInteger(bigInteger);
            writeJSONToken('"');
        } else {
            writeBigInteger(bigInteger);
        }
    }

    /**
     * 直接写入仅含 Latin1 字符的字符串，不做转义处理
     *
     * @param str 待写入的字符串
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeLatinString(String str) throws IOException {
        write(str);
    }

    /**
     * 支持数字转字符串
     *
     * @param str 待写入的字符串，通常是数字的字面量
     * @param jsonConfig JSON 配置，决定内容是否加引号输出
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeLatinString(String str, JSONConfig jsonConfig) throws IOException {
        if (jsonConfig.isWriteNumberAsString()) {
            writeJSONToken('"');
            writeLatinString(str);
            writeJSONToken('"');
        } else {
            writeLatinString(str);
        }
    }

    // only for map key
    /**
     * 写入 JSON 对象的键以及其后的冒号，键会按 JSON 字符串规则加引号并转义
     *
     * @param value 键名
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeJSONKeyAndColon(String value) throws IOException {
        writeJSONString(value);
        writeJSONToken(':');
    }

    /**
     * 将字符数组按 JSON 字符串规则写入，两端补引号并转义需要转义的字符
     *
     * @param chars 待写入的字符数组
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeJSONChars(char[] chars) throws IOException {
        int beginIndex = 0;
        int len = chars.length;
        write('"');
        for (int i = 0; i < len; ++i) {
            char ch = chars[i];
            String escapeStr;
            if ((ch > '"' && ch != '\\') || (escapeStr = JSONGeneral.ESCAPE_VALUES[ch]) == null) {
                continue;
            }
            int length = i - beginIndex;
            // 很诡异的问题
            if (length > 0) {
                write(chars, beginIndex, length);
            }
            write(escapeStr);
            beginIndex = i + 1;
        }
        int size = len - beginIndex;
        write(chars, beginIndex, size);
        write('"');
    }

    /**
     * json string
     *
     * @param value the string to write as a quoted and escaped JSON string
     * @throws IOException if an I/O error occurs
     */
    public final void writeJSONString(String value) throws IOException {
        if (EnvUtils.JDK_9_PLUS) {
            byte[] bytes = (byte[]) JSONMemoryHandle.getStringValue(value.toString());
            writeJSONStringBytes(value, bytes);
        } else {
            writeJSONChars((char[]) JSONMemoryHandle.getStringValue(value.toString()));
        }
    }

    /**
     * When the bytes are determined, one reflection can be reduced
     *
     * @param value the string to write as a quoted and escaped JSON string
     * @param bytes the internal byte array of the string, its length decides
     *              whether the Latin1 or the UTF16 branch is used
     * @throws IOException if an I/O error occurs
     */
    public final void writeJSONStringBytes(String value, byte[] bytes) throws IOException {
        if (bytes.length == value.length()) {
            writeLatinJSONString(value, bytes);
        } else {
            writeUTF16JSONString(value, bytes);
        }
    }

    /**
     * 按 JSON 字符串规则写入 Latin1 编码的字符串，两端补引号并转义需要转义的字符
     *
     * @param value 待写入的字符串
     * @param bytes 该字符串的 Latin1 字节内容，用于快速定位需要转义的位置
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeLatinJSONString(String value, byte[] bytes) throws IOException {
        int len = bytes.length;
        write('"');
        int beginIndex = 0;
        for (int i = 0; i < len; ++i) {
            byte b = bytes[i];
//            if (b > '"' && b != '\\') continue;
            String escapeStr;
            if ((escapeStr = JSONGeneral.ESCAPE_VALUES[b & 0xFF]) != null) {
                write(value, beginIndex, i - beginIndex);
                write(escapeStr);
                beginIndex = i + 1;
            }
        }
        int size = len - beginIndex;
        write(value, beginIndex, size);
        write('"');
    }

    /**
     * 按 JSON 字符串规则写入含非 Latin1 字符的字符串，两端补引号并转义需要转义的字符
     *
     * @param value 待写入的字符串
     * @param bytes 该字符串的 UTF16 字节内容，当前实现按字符逐个扫描，未直接使用该参数
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeUTF16JSONString(String value, byte[] bytes) throws IOException {
        write('"');
        int beginIndex = 0;
        int strlen = value.length();
        for (int i = 0; i < strlen; ++i) {
            char b = value.charAt(i);
            if (b > '"' && b != '\\') {
                continue;
            }
            String escapeStr;
            if ((escapeStr = JSONGeneral.ESCAPE_VALUES[b]) != null) {
                write(value, beginIndex, i - beginIndex);
                write(escapeStr);
                beginIndex = i + 1;
            }
        }
        int size = strlen - beginIndex;
        write(value, beginIndex, size);
        write('"');
    }

    void writeMemory2(int int32, short int16, char[] source, int off) throws IOException {
        write(source, off, 2);
    }

    /**
     * 通过unsafe写入4 * n个字符（4 * n 字节）
     */
    void writeMemory(long fourChars, int fourBytes, int len) throws IOException {
        char[] chars = new char[4];
        JSONMemoryHandle.putLong(chars, 0, fourChars);
        write(chars, 0, len);
    }

    /**
     * 一次性写入6-8个字符(字节)
     */
    void writeMemory(long fourChars1, long fourChars2, long fourBytes, int len) throws IOException {
        char[] chars = new char[8];
        JSONMemoryHandle.putLong(chars, 0, fourChars1);
        JSONMemoryHandle.putLong(chars, 4, fourChars2);
        write(chars, 0, len);
    }

    /**
     * 通过unsafe写入totalCount个字节
     */
    void writeMemory(long[] fourChars, long[] fourBytes, int totalCount) throws IOException {
        int n = fourChars.length;
        char[] chars = new char[n << 2];
        int offset = 0;
        for (long fourChar : fourChars) {
            JSONMemoryHandle.putLong(chars, offset, fourChar);
            offset += 4;
        }
        write(chars, 0, totalCount);
    }

    /**
     * 写入空的 JSON 数组，即 {@code []}
     *
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeEmptyArray() throws IOException {
        write(JSONGeneral.EMPTY_ARRAY, 0, 2);
    }

    static final boolean isNoEscape(int b) {
        return JSONGeneral.NO_ESCAPE_FLAGS[b];
    }

    static final int writeUUID(UUID uuid, char[] buf, int offset) {
        long mostSigBits = uuid.getMostSignificantBits();
        long v1;
        long v2;
        long v3;
        long v4;
        {
            int b1 = (int) (mostSigBits >>> 56 & 0xff);
            int b2 = (int) (mostSigBits >>> 48 & 0xff);
            int b3 = (int) (mostSigBits >>> 40 & 0xff);
            int b4 = (int) (mostSigBits >>> 32 & 0xff);
            int b5 = (int) (mostSigBits >>> 24 & 0xff);
            int b6 = (int) (mostSigBits >>> 16 & 0xff);
            int b7 = (int) (mostSigBits >>> 8 & 0xff);
            int b8 = (int) (mostSigBits & 0xff);
            if (EnvUtils.BIG_ENDIAN) {
                v1 = (long) HEX_DIGITS_INT32[b1] << 32 | HEX_DIGITS_INT32[b2];
                v2 = (long) HEX_DIGITS_INT32[b3] << 32 | HEX_DIGITS_INT32[b4];
                v3 = (long) HEX_DIGITS_INT32[b5] << 32 | HEX_DIGITS_INT32[b6];
                v4 = (long) HEX_DIGITS_INT32[b7] << 32 | HEX_DIGITS_INT32[b8];
            } else {
                v1 = (long) HEX_DIGITS_INT32[b2] << 32 | HEX_DIGITS_INT32[b1];
                v2 = (long) HEX_DIGITS_INT32[b4] << 32 | HEX_DIGITS_INT32[b3];
                v3 = (long) HEX_DIGITS_INT32[b6] << 32 | HEX_DIGITS_INT32[b5];
                v4 = (long) HEX_DIGITS_INT32[b8] << 32 | HEX_DIGITS_INT32[b7];
            }
            offset += JSONMemoryHandle.putLong(buf, offset, v1);
            offset += JSONMemoryHandle.putLong(buf, offset, v2);
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putLong(buf, offset, v3);
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putLong(buf, offset, v4);
        }
        long leastSigBits = uuid.getLeastSignificantBits();
        {
            int b1 = (int) (leastSigBits >>> 56 & 0xff);
            int b2 = (int) (leastSigBits >>> 48 & 0xff);
            int b3 = (int) (leastSigBits >>> 40 & 0xff);
            int b4 = (int) (leastSigBits >>> 32 & 0xff);
            int b5 = (int) (leastSigBits >>> 24 & 0xff);
            int b6 = (int) (leastSigBits >>> 16 & 0xff);
            int b7 = (int) (leastSigBits >>> 8 & 0xff);
            int b8 = (int) (leastSigBits & 0xff);
            if (EnvUtils.BIG_ENDIAN) {
                v1 = (long) HEX_DIGITS_INT32[b1] << 32 | HEX_DIGITS_INT32[b2];
                v2 = (long) HEX_DIGITS_INT32[b3] << 32 | HEX_DIGITS_INT32[b4];
                v3 = (long) HEX_DIGITS_INT32[b5] << 32 | HEX_DIGITS_INT32[b6];
                v4 = (long) HEX_DIGITS_INT32[b7] << 32 | HEX_DIGITS_INT32[b8];
            } else {
                v1 = (long) HEX_DIGITS_INT32[b2] << 32 | HEX_DIGITS_INT32[b1];
                v2 = (long) HEX_DIGITS_INT32[b4] << 32 | HEX_DIGITS_INT32[b3];
                v3 = (long) HEX_DIGITS_INT32[b6] << 32 | HEX_DIGITS_INT32[b5];
                v4 = (long) HEX_DIGITS_INT32[b8] << 32 | HEX_DIGITS_INT32[b7];
            }
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putLong(buf, offset, v1);
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putLong(buf, offset, v2);
            offset += JSONMemoryHandle.putLong(buf, offset, v3);
            JSONMemoryHandle.putLong(buf, offset, v4);
        }
        return 36;
    }

    static final int writeUUID(UUID uuid, byte[] buf, int offset) {
        long mostSigBits = uuid.getMostSignificantBits();
        {
            long v1;
            long v2;
            long v3;
            int b1 = (int) (mostSigBits >>> 56 & 0xff);
            int b2 = (int) (mostSigBits >>> 48 & 0xff);
            int b3 = (int) (mostSigBits >>> 40 & 0xff);
            int b4 = (int) (mostSigBits >>> 32 & 0xff);
            int b5 = (int) (mostSigBits >>> 24 & 0xff);
            int b6 = (int) (mostSigBits >>> 16 & 0xff);
            int b7 = (int) (mostSigBits >>> 8 & 0xff);
            int b8 = (int) (mostSigBits & 0xff);
            if (EnvUtils.LITTLE_ENDIAN) {
                v1 = HEX_DIGITS_INT16[b4] << 48 | HEX_DIGITS_INT16[b3] << 32 | HEX_DIGITS_INT16[b2] << 16 |
                        HEX_DIGITS_INT16[b1];
                v2 = HEX_DIGITS_INT16[b6] << 16 | HEX_DIGITS_INT16[b5];
                v3 = HEX_DIGITS_INT16[b8] << 16 | HEX_DIGITS_INT16[b7];
            } else {
                v1 = HEX_DIGITS_INT16[b1] << 48 | HEX_DIGITS_INT16[b2] << 32 | HEX_DIGITS_INT16[b3] << 16 |
                        HEX_DIGITS_INT16[b4];
                v2 = HEX_DIGITS_INT16[b5] << 16 | HEX_DIGITS_INT16[b6];
                v3 = HEX_DIGITS_INT16[b7] << 16 | HEX_DIGITS_INT16[b8];
            }
            offset += JSONMemoryHandle.putLong(buf, offset, v1);
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putInt(buf, offset, (int) v2);
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putInt(buf, offset, (int) v3);
        }
        long leastSigBits = uuid.getLeastSignificantBits();
        {
            long v1;
            long v2;
            long v3;
            int b1 = (int) (leastSigBits >>> 56 & 0xff);
            int b2 = (int) (leastSigBits >>> 48 & 0xff);
            int b3 = (int) (leastSigBits >>> 40 & 0xff);
            int b4 = (int) (leastSigBits >>> 32 & 0xff);
            int b5 = (int) (leastSigBits >>> 24 & 0xff);
            int b6 = (int) (leastSigBits >>> 16 & 0xff);
            int b7 = (int) (leastSigBits >>> 8 & 0xff);
            int b8 = (int) (leastSigBits & 0xff);
            if (EnvUtils.LITTLE_ENDIAN) {
                v1 = HEX_DIGITS_INT16[b2] << 16 | HEX_DIGITS_INT16[b1];
                v2 = HEX_DIGITS_INT16[b4] << 16 | HEX_DIGITS_INT16[b3];
                v3 = HEX_DIGITS_INT16[b8] << 48 | HEX_DIGITS_INT16[b7] << 32 | HEX_DIGITS_INT16[b6] << 16 |
                        HEX_DIGITS_INT16[b5];
            } else {
                v1 = HEX_DIGITS_INT16[b1] << 16 | HEX_DIGITS_INT16[b2];
                v2 = HEX_DIGITS_INT16[b3] << 16 | HEX_DIGITS_INT16[b4];
                v3 = HEX_DIGITS_INT16[b5] << 48 | HEX_DIGITS_INT16[b6] << 32 | HEX_DIGITS_INT16[b7] << 16 |
                        HEX_DIGITS_INT16[b8];
            }
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putInt(buf, offset, (int) v1);
            buf[offset++] = '-';
            offset += JSONMemoryHandle.putInt(buf, offset, (int) v2);
            JSONMemoryHandle.putLong(buf, offset, v3);
        }
        return 36;
    }

    static final int writeDouble(double doubleValue, char[] buf, int off) {
        final int beginIndex = off;
        long bits;
        if (doubleValue == 0) {
            bits = Double.doubleToLongBits(doubleValue);
            if (bits == 0x8000000000000000L) {
                buf[off++] = '-';
            }
            buf[off++] = '0';
            off += JSONMemoryHandle.putInt(buf, off, DOT_ZERO_32); // .0
            return off - beginIndex;
        }
        boolean sign = doubleValue < 0;
        if (sign) {
            buf[off++] = '-';
            doubleValue = -doubleValue;
        }
        long output = (long) doubleValue;
        if (doubleValue == output && output < 10000000000000000L) {
            int numLength = NumberUtils.stringSize(output);
            return writeDecimal(output, numLength, numLength - 1, buf, beginIndex, off);
        }

        Scientific scientific = NumberUtils.doubleToScientific(doubleValue);
        int e10 = scientific.e10;
        if (!scientific.b) {
            return writeDecimal(scientific.output, scientific.count, e10, buf, beginIndex, off);
        }
        if (scientific == Scientific.SCIENTIFIC_NULL) {
            off += JSONMemoryHandle.putLong(buf, off, JSONGeneral.NULL_LONG);
            return off - beginIndex;
        }

        char[] chars = e10 >= 0 ? POSITIVE_DECIMAL_POWER_CHARS[e10] : NEGATIVE_DECIMAL_POWER_CHARS[-e10];
        System.arraycopy(chars, 0, buf, off, chars.length);
        off += chars.length;
        return off - beginIndex;
//        if (e10 >= 0) {
//            char[] chars = POSITIVE_DECIMAL_POWER_CHARS[e10];
//            System.arraycopy(chars, 0, buf, off, chars.length);
//            off += chars.length;
//            return off - beginIndex;
//        } else {
//            char[] chars = NEGATIVE_DECIMAL_POWER_CHARS[-e10];
//            System.arraycopy(chars, 0, buf, off, chars.length);
//            off += chars.length;
//            return off - beginIndex;
//        }
    }

    /**
     * float写入buf
     */
    static final int writeFloat(float floatValue, char[] buf, int off) {
        if (Float.isNaN(floatValue) || floatValue == Float.POSITIVE_INFINITY || floatValue == Float.NEGATIVE_INFINITY) {
            return JSONMemoryHandle.putLong(buf, off, JSONGeneral.NULL_LONG);
        }
        final int beginIndex = off;
        int bits;
        if (floatValue == 0) {
            bits = Float.floatToIntBits(floatValue);
            if (bits == 0x80000000) {
                buf[off++] = '-';
            }
            buf[off++] = '0';
            off += JSONMemoryHandle.putInt(buf, off, DOT_ZERO_32); // .0
            return off - beginIndex;
        }
        boolean sign = floatValue < 0;
        if (sign) {
            buf[off++] = '-';
            floatValue = -floatValue;
        }

        Scientific scientific = NumberUtils.floatToScientific(floatValue);
        return writeDecimal(scientific.output, scientific.count, scientific.e10, buf, beginIndex, off);
    }

    static final int writeDecimal(long value, int digitCnt, int e10, char[] buf, int beginIndex, int off) {
        if ((value & 1) == 0 && value % 5 == 0) {
            while (value % 100 == 0) {
                digitCnt -= 2;
                value /= 100;
                if (digitCnt == 1) {
                    break;
                }
            }
            if ((value & 1) == 0 && value % 5 == 0) {
                if (value > 0) {
                    --digitCnt;
                    value /= 10;
                }
            }
        }
        // 是否使用科学计数法
        boolean useScientific = e10 < -3 || e10 >= 7; // !((e10 >= -3) && (e10 < 7));
        if (useScientific) {
            if (digitCnt == 1) {
                buf[off++] = (char) (value + 48);
                off += JSONMemoryHandle.putInt(buf, off, DOT_ZERO_32); // .0
            } else {
                int pos = digitCnt - 2;
                // 获取首位数字
                long tl = POW10_LONG_VALUES[pos];
                int fd = (int) (value / tl);
                buf[off++] = (char) (fd + 48);
                buf[off++] = '.';

                long pointAfter = value - fd * tl;
                // 补0
                while (--pos > -1 && pointAfter < POW10_LONG_VALUES[pos]) {
                    buf[off++] = '0';
                }
                off += writeLong(pointAfter, buf, off);
            }
            buf[off++] = 'E';
            if (e10 < 0) {
                buf[off++] = '-';
                e10 = -e10;
            }
            if (e10 > 99) {
                int n = e10 / 100;
                buf[off++] = (char) (n + 48);
                e10 = e10 - n * 100;
                off += JSONMemoryHandle.putInt(buf, off, TWO_DIGITS_32_BITS[e10]);
            } else {
                if (e10 > 9) {
                    off += JSONMemoryHandle.putInt(buf, off, TWO_DIGITS_32_BITS[e10]);
                } else {
                    buf[off++] = (char) (e10 + 48);
                }
            }
        } else {
            // 非科学计数法例如12345, 在size = decimalExp时写入小数点.
            if (e10 < 0) {
                // -1/-2/-3
                off += JSONMemoryHandle.putInt(buf, off, ZERO_DOT_32); // 0.
                if (e10 == -2) {
                    buf[off++] = '0';
                } else if (e10 == -3) {
                    off += JSONMemoryHandle.putInt(buf, off, ZERO_ZERO_32); // 00
                }
                off += writeLong(value, buf, off);
            } else {
                // 0 - 6
                int decimalPointPos = (digitCnt - 1) - e10;
                if (decimalPointPos > 0) {
                    int pos = decimalPointPos - 1;
                    long tl = POW10_LONG_VALUES[pos];
                    int pointBefore = (int) (value / tl);
                    off += writeInteger(pointBefore, buf, off);
                    buf[off++] = '.';
                    long pointAfter = value - pointBefore * tl;
                    // 补0
                    while (--pos > -1 && pointAfter < POW10_LONG_VALUES[pos]) {
                        buf[off++] = '0';
                    }
                    off += writeLong(pointAfter, buf, off);
                } else {
                    off += writeLong(value, buf, off);
                    int zeroCnt = -decimalPointPos;
                    if (zeroCnt > 0) {
                        for (int i = 0; i < zeroCnt; ++i) {
                            buf[off++] = '0';
                        }
                    }
                    off += JSONMemoryHandle.putInt(buf, off, DOT_ZERO_32); // .0
                }
            }
        }

        return off - beginIndex;
    }

    /**
     * 将double值写入到字节数组(保留16位有效数字)
     */
    static final int writeDouble(double doubleValue, byte[] buf, int off) {
        final int beginIndex = off;
        long bits;
        if (doubleValue == 0) {
            bits = Double.doubleToLongBits(doubleValue);
            if (bits == 0x8000000000000000L) {
                buf[off++] = '-';
            }
            buf[off++] = '0';
            off += JSONMemoryHandle.putShort(buf, off, DOT_ZERO_16); // .0
            return off - beginIndex;
        }
        boolean sign = doubleValue < 0;
        if (sign) {
            buf[off++] = '-';
            doubleValue = -doubleValue;
        }
        long output = (long) doubleValue;
        if (doubleValue == output && output < 10000000000000000L) {
            int numLength = NumberUtils.stringSize(output);
            return writeDecimal(output, numLength, numLength - 1, buf, beginIndex, off);
        }
        Scientific scientific = NumberUtils.doubleToScientific(doubleValue);
        int e10 = scientific.e10;
        if (!scientific.b) {
            return writeDecimal(scientific.output, scientific.count, scientific.e10, buf, beginIndex, off);
        }
        if (scientific == Scientific.SCIENTIFIC_NULL) {
            off += JSONMemoryHandle.putInt(buf, off, JSONGeneral.NULL_INT);
            return off - beginIndex;
        }

        char[] chars = e10 >= 0 ? POSITIVE_DECIMAL_POWER_CHARS[e10] : NEGATIVE_DECIMAL_POWER_CHARS[-e10];
        for (char c : chars) {
            buf[off++] = (byte) c;
        }
        return off - beginIndex;
//        if (e10 >= 0) {
//            char[] chars = POSITIVE_DECIMAL_POWER_CHARS[e10];
//            for (char c : chars) {
//                buf[off++] = (byte) c;
//            }
//            return off - beginIndex;
//        } else {
//            char[] chars = NEGATIVE_DECIMAL_POWER_CHARS[-e10];
//            for (char c : chars) {
//                buf[off++] = (byte) c;
//            }
//            return off - beginIndex;
//        }
    }

    /**
     * float写入buf（保留7位有效数字）
     */
    static final int writeFloat(float floatValue, byte[] buf, int off) {
        if (Float.isNaN(floatValue) || floatValue == Float.POSITIVE_INFINITY || floatValue == Float.NEGATIVE_INFINITY) {
            return JSONMemoryHandle.putInt(buf, off, JSONGeneral.NULL_INT);
        }
        final int beginIndex = off;
        int bits;
        if (floatValue == 0) {
            bits = Float.floatToIntBits(floatValue);
            if (bits == 0x80000000) {
                buf[off++] = '-';
            }
            buf[off++] = '0';
            off += JSONMemoryHandle.putShort(buf, off, DOT_ZERO_16); // .0
            return off - beginIndex;
        }
        boolean sign = floatValue < 0;
        if (sign) {
            buf[off++] = '-';
            floatValue = -floatValue;
        }

        Scientific scientific = NumberUtils.floatToScientific(floatValue);
        return writeDecimal(scientific.output, scientific.count, scientific.e10, buf, beginIndex, off);
    }

    static final int writeDecimal(long value, int digitCnt, int e10, byte[] buf, int beginIndex, int off) {
        if ((value & 1) == 0 && value % 5 == 0) {
            while (value % 100 == 0) {
                digitCnt -= 2;
                value /= 100;
                if (digitCnt == 1) {
                    break;
                }
            }
            if ((value & 1) == 0 && value % 5 == 0) {
                if (value > 0) {
                    --digitCnt;
                    value /= 10;
                }
            }
        }
        // Whether to use Scientific notation
        boolean useScientific = e10 < -3 || e10 >= 7; // !((e10 >= -3) && (e10 < 7));
        if (useScientific) {
            if (digitCnt == 1) {
                buf[off++] = (byte) (value + 48);
                off += JSONMemoryHandle.putShort(buf, off, DOT_ZERO_16); // .0
            } else {
                int pos = digitCnt - 2;
                // 获取首位数字
                long tl = POW10_LONG_VALUES[pos];
                int fd = (int) (value / tl);
                buf[off++] = (byte) (fd + 48);
                buf[off++] = '.';

                long pointAfter = value - fd * tl;
                // 补0
                while (--pos > -1 && pointAfter < POW10_LONG_VALUES[pos]) {
                    buf[off++] = '0';
                }
                off += writeLong(pointAfter, buf, off);
            }
            buf[off++] = 'E';
            if (e10 < 0) {
                buf[off++] = '-';
                e10 = -e10;
            }
            if (e10 > 99) {
                int n = e10 / 100;
                buf[off++] = (byte) (n + 48);
                e10 = e10 - n * 100;
                off += JSONMemoryHandle.putShort(buf, off, TWO_DIGITS_16_BITS[e10]);
            } else {
                if (e10 > 9) {
                    off += JSONMemoryHandle.putShort(buf, off, TWO_DIGITS_16_BITS[e10]);
                } else {
                    buf[off++] = (byte) (e10 + 48);
                }
            }
        } else {
            // 非科学计数法例如12345, 在size = decimalExp时写入小数点.
            if (e10 < 0) {
                // -1/-2/-3
                off += JSONMemoryHandle.putShort(buf, off, ZERO_DOT_16); // 0.
                if (e10 == -2) {
                    buf[off++] = '0';
                } else if (e10 == -3) {
                    off += JSONMemoryHandle.putShort(buf, off, ZERO_ZERO_16); // 00
                }
                off += writeLong(value, buf, off);
            } else {
                // 0 - 6
                int decimalPointPos = (digitCnt - 1) - e10;
                if (decimalPointPos > 0) {
                    int pos = decimalPointPos - 1;
                    long tl = POW10_LONG_VALUES[pos];
                    int pointBefore = (int) (value / tl);
                    off += writeInteger(pointBefore, buf, off);
                    buf[off++] = '.';
                    long pointAfter = value - pointBefore * tl;
                    // 补0
                    while (--pos > -1 && pointAfter < POW10_LONG_VALUES[pos]) {
                        buf[off++] = '0';
                    }
                    off += writeLong(pointAfter, buf, off);
                } else {
                    off += writeLong(value, buf, off);
                    int zeroCnt = -decimalPointPos;
                    if (zeroCnt > 0) {
                        for (int i = 0; i < zeroCnt; ++i) {
                            buf[off++] = '0';
                        }
                    }
                    off += JSONMemoryHandle.putShort(buf, off, DOT_ZERO_16); // .0
                }
            }
        }

        return off - beginIndex;
    }

    static final int writeBigInteger(BigInteger val, char[] chars, int off) {
        final int beginIndex = off;
        if (val.signum() == -1) {
            chars[off++] = '-';
            val = val.negate();
        }
        if (val.compareTo(BI_MAX_VALUE_FOR_LONG) < 1) {
            long value = val.longValue();
            off += writeLong(value, chars, off);
            return off - beginIndex;
        }
        int bigLength = val.bitLength();
        int[] values = new int[bigLength / 31];
        int len = 0;
        do {
            BigInteger[] bigIntegers = val.divideAndRemainder(BI_TO_DECIMAL_BASE);
            int rem = bigIntegers[1].intValue();
            val = bigIntegers[0];
            if (val.compareTo(BI_MAX_VALUE_FOR_LONG) < 1) {
                long headNum = val.longValue();
                off += writeLong(headNum, chars, off);
                // rem < BI_TO_DECIMAL_BASE
                int pos = 8;
                while (--pos > -1 && rem < POW10_LONG_VALUES[pos]) {
                    chars[off++] = '0';
                }
                off += writeLong(rem, chars, off);
                for (int j = len - 1; j > -1; --j) {
                    int value = values[j];
                    pos = 8;
                    // value < BI_TO_DECIMAL_BASE
                    while (--pos > -1 && value < POW10_LONG_VALUES[pos]) {
                        chars[off++] = '0';
                    }
                    off += writeLong(value, chars, off);
                }
                return off - beginIndex;
            }
            values[len++] = rem;
        } while (true);
    }

    static final int writeBigInteger(BigInteger val, byte[] buf, int off) {
        final int beginIndex = off;
        if (val.signum() == -1) {
            buf[off++] = '-';
            val = val.negate();
        }
        if (val.compareTo(BI_MAX_VALUE_FOR_LONG) < 1) {
            long value = val.longValue();
            off += writeLong(value, buf, off);
            return off - beginIndex;
        }
        int bigLength = val.bitLength();
        int[] values = new int[bigLength / 31];
        int len = 0;
        do {
            BigInteger[] bigIntegers = val.divideAndRemainder(BI_TO_DECIMAL_BASE);
            int rem = bigIntegers[1].intValue();
            val = bigIntegers[0];
            if (val.compareTo(BI_MAX_VALUE_FOR_LONG) < 1) {
                long headNum = val.longValue();
                off += writeLong(headNum, buf, off);
                // rem < BI_TO_DECIMAL_BASE
                int pos = 8;
                while (--pos > -1 && rem < POW10_LONG_VALUES[pos]) {
                    buf[off++] = '0';
                }
                off += writeLong(rem, buf, off);
                for (int j = len - 1; j > -1; --j) {
                    int value = values[j];
                    pos = 8;
                    // value < BI_TO_DECIMAL_BASE
                    while (--pos > -1 && value < POW10_LONG_VALUES[pos]) {
                        buf[off++] = '0';
                    }
                    off += writeLong(value, buf, off);
                }
                return off - beginIndex;
            }
            values[len++] = rem;
        } while (true);
    }

    static final int writeThreeDigits(int val, char[] chars, int off) {
        if (val < 10) {
            chars[off] = (char) (val + 48);
            return 1;
        }
        if (val < 100) {
            JSONMemoryHandle.putInt(chars, off, TWO_DIGITS_32_BITS[val]);
            return 2;
        }
        int v = (int) (val * 1374389535L >>
                37); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(val, 0x28f5c28f5c28f5dL);  // val / 100;
        int v1 = val - v * 100;
        chars[off++] = (char) (v + 48);
        JSONMemoryHandle.putInt(chars, off, TWO_DIGITS_32_BITS[v1]);
        return 3;
    }

    static final int writeThreeDigits(int val, byte[] buf, int off) {
        if (val < 10) {
            buf[off] = (byte) (val + 48);
            return 1;
        }
        if (val < 100) {
            JSONMemoryHandle.putShort(buf, off, TWO_DIGITS_16_BITS[val]);
            return 2;
        }
        int v = (int) (val * 1374389535L >>
                37); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(val, 0x28f5c28f5c28f5dL);  // val / 100;
        int v1 = val - v * 100;
        buf[off++] = (byte) (v + 48);
        JSONMemoryHandle.putShort(buf, off, TWO_DIGITS_16_BITS[v1]);
        return 3;
    }

    static final long mergeInt64(int val, char pre, char suff) {
        return EnvUtils.BIG_ENDIAN ? ((long) pre) << 48 | ((long) TWO_DIGITS_32_BITS[val]) << 16 | suff :
                ((long) suff) << 48 | ((long) TWO_DIGITS_32_BITS[val]) << 16 | pre;
        // return JSONUnsafe.UNSAFE_ENDIAN.mergeInt64(TWO_DIGITS_32_BITS[val], pre, suff);
    }

    static final int mergeInt32(int val, char pre, char suff) {
        return EnvUtils.BIG_ENDIAN ? (pre << 24) | (TWO_DIGITS_16_BITS[val] << 8) | suff :
                (suff << 24) | (TWO_DIGITS_16_BITS[val] << 8) | pre;
        // return JSONUnsafe.UNSAFE_ENDIAN.mergeInt32(TWO_DIGITS_16_BITS[val], pre, suff);
    }

    static final long mergeInt64(int t, int r) {
        long top32 = FOUR_DIGITS_32_BITS[t];
        long rem32 = FOUR_DIGITS_32_BITS[r];
        return EnvUtils.BIG_ENDIAN ? top32 << 32 | rem32 : rem32 << 32 | top32;
        // return JSONUnsafe.UNSAFE_ENDIAN.mergeInt64(FOUR_DIGITS_32_BITS[t], FOUR_DIGITS_32_BITS[r]);
    }

//    // yyyy-MM-
//    final static long mergeYearAndMonth(int year, int month) {
//        long month16 = TWO_DIGITS_16_BITS[month];
//        return EnvUtils.LITTLE_ENDIAN
//                ? 0x2d00002d00000000L | month16 << 40 | FOUR_DIGITS_32_BITS[year]
//                : (long) FOUR_DIGITS_32_BITS[year] << 32 | month16 << 8 | 0x2d00002d;
//    }
//
//    // HH:mm:ss
//    final static long mergeHHMMSS(int hour, int minute, int second) {
////        return EnvUtils.LITTLE_ENDIAN
////                ? 0x00003a00003a0000L | (long) TWO_DIGITS_16_BITS[second] << 48 | (long) TWO_DIGITS_16_BITS
//    [minute] << 24 | TWO_DIGITS_16_BITS[hour]
////                : 0x00003a00003a0000L | (long) TWO_DIGITS_16_BITS[hour] << 48 | (long) TWO_DIGITS_16_BITS[minute]
//            << 24 | TWO_DIGITS_16_BITS[second];
//         return JSONUnsafe.UNSAFE_ENDIAN.mergeHHMMSS(hour, minute, second);
//    }

    /**
     * ensure val >= 0 && chars.length > off + 19
     */
    static final int writeLong(long val, char[] chars, int off) {
        if (val < 0x80000000L) {
            return writeInteger((int) val, chars, off);
        }
        final int beginIndex = off;
        long high = EnvUtils.JDK_AGENT_INSTANCE.multiplyHigh(val, 0x55e63b88c230e77fL) >> 25;  // value / 100000000L
        long low = val - high * 100000000L;
        int l1 = (int) (low * 1759218605L >> 44);
        int l2 = (int) (low - l1 * 10000);
        if (high < 0x80000000L) {
            off += writeInteger((int) high, chars, off);
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[l1]);
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[l2]);
            return off - beginIndex;
        } else {
            // high(10-11) -> h0(2-3) h1(4) h2(4)
            int v = (int) EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(high, 0x68db8bac710ccL);
            int h2 = (int) (high - v * 10000L);
            int h0 = (int) (v * 1759218605L >> 44);
            int h1 = (v - h0 * 10000);
            off += writeThreeDigits(h0, chars, off);
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[h1]);
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[h2]);
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[l1]);
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[l2]);
            return off - beginIndex;
        }

//        if (val < 0x80000000L) {
//            return writeInteger((int) val, chars, off);
//        }
//        int v, v1, v2, v3, v4;
//        final int beginIndex = off;
//        long numValue = val;
//        val = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710cb296L) >> 12; // numValue /
//        10000;
//        v1 = (int) (numValue - val * 10000);
//
//        numValue = val;
//        val = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710ccL); // numValue / 10000;
//        v2 = (int) (numValue - val * 10000);
//        if (val < 10000) {
//            v = (int) val;
//            if (v < 1000) {
//                off += writeThreeDigits(v, chars, off);
//            } else {
//                off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v]);
//            }
//            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v2]);
//            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v1]);
//            return off - beginIndex;
//        }
//
//        numValue = val;
//        val = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710ccL); // numValue / 10000;
//        v3 = (int) (numValue - val * 10000);
//        if (val < 10000) {
//            v = (int) val;
//            if (v < 1000) {
//                off += writeThreeDigits(v, chars, off);
//            } else {
//                off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v]);
//            }
//            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v3]);
//            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v2]);
//            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v1]);
//            return off - beginIndex;
//        }
//
//        numValue = val;
//        val = numValue * 1759218605L >> 44; // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue,
//        0x68db8bac710ccL); // numValue / 10000;
//        v4 = (int) (numValue - val * 10000);
//
//        off += writeThreeDigits((int) val, chars, off);
//        off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v4]);
//        off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v3]);
//        off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v2]);
//        off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v1]);
//        return off - beginIndex;
    }

    /**
     * ensure val >= 0 && chars.length > off + 10
     *
     * @param val max 10 digits
     */
    static final int writeInteger(int val, char[] chars, int off) {
        int v;
        int v2;
        if (val < 10000) {
            v = val;
            if (v < 1000) {
                return writeThreeDigits(v, chars, off);
            } else {
                return JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v]);
            }
        }
        final int beginIndex = off;
        long numValue = val;
        val = (int) (numValue * 1759218605L >>
                44); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710cb296L) >> 12; //
        // numValue / 10000;
        int v1 = (int) (numValue - val * 10000);
        if (val < 10000) {
            v = val;
            if (v < 1000) {
                off += writeThreeDigits(v, chars, off);
            } else {
                off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v]);
            }
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v1]);
            return off - beginIndex;
        } else {
            numValue = val;
            val = (int) (numValue * 1759218605L >>
                    44); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710ccL); //
            // numValue / 10000;
            v2 = (int) (numValue - val * 10000);
            // max val 21
            if (val < 10) {
                chars[off++] = (char) (val + 48);
            } else {
                off += JSONMemoryHandle.putInt(chars, off, TWO_DIGITS_32_BITS[val]);
            }
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v2]);
            off += JSONMemoryHandle.putLong(chars, off, FOUR_DIGITS_64_BITS[v1]);
            return off - beginIndex;
        }
    }

    /**
     * ensure val >= 0 && buf.length > off + 19
     */
    static final int writeLong(long val, byte[] buf, int off) {
        if (val < 0x80000000L) {
            return writeInteger((int) val, buf, off);
        }
        final int beginIndex = off;
        long high = EnvUtils.JDK_AGENT_INSTANCE.multiplyHigh(val, 0x55e63b88c230e77fL) >> 25;  // value / 100000000L
        long low = val - high * 100000000L;
        int l1 = (int) (low * 1759218605L >> 44);
        int l2 = (int) (low - l1 * 10000);
        if (high < 0x80000000L) {
            off += writeInteger((int) high, buf, off);
            off += JSONMemoryHandle.putLong(buf, off, mergeInt64(l1, l2));
            return off - beginIndex;
        } else {
            // high(10-11) -> h0(2-3) h1(4) h2(4)
            int v = (int) EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(high, 0x68db8bac710ccL);
            int h2 = (int) (high - v * 10000L);
            int h0 = (int) (v * 1759218605L >> 44);
            int h1 = (v - h0 * 10000);
            off += writeThreeDigits(h0, buf, off);
            off += JSONMemoryHandle.putLong(buf, off, mergeInt64(h1, h2));
            off += JSONMemoryHandle.putLong(buf, off, mergeInt64(l1, l2));
            return off - beginIndex;
        }
//        int v, v1, v2, v3, v4;
//        final int beginIndex = off;
//        long numValue = val;
//        val = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710cb296L) >> 12; // numValue /
//        10000;
//        v1 = (int) (numValue - val * 10000);
//
//        numValue = val;
//        val = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710ccL); // numValue / 10000;
//        v2 = (int) (numValue - val * 10000);
//        if (val < 10000) {
//            v = (int) val;
//            if (v < 1000) {
//                off += writeThreeDigits(v, buf, off);
//            } else {
//                off += JSONMemoryHandle.putInt(buf, off, FOUR_DIGITS_32_BITS[v]);
//            }
//            off += JSONMemoryHandle.putLong(buf, off, mergeInt64(v2, v1));
//            return off - beginIndex;
//        }
//
//        numValue = val;
//        val = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710ccL); // numValue / 10000;
//        v3 = (int) (numValue - val * 10000);
//        if (val < 10000) {
//            v = (int) val;
//            if (v < 1000) {
//                off += writeThreeDigits(v, buf, off);
//                off += JSONMemoryHandle.putInt(buf, off, FOUR_DIGITS_32_BITS[v3]);
//            } else {
//                off += JSONMemoryHandle.putLong(buf, off, mergeInt64(v, v3));
//            }
//            off += JSONMemoryHandle.putLong(buf, off, mergeInt64(v2, v1));
//            return off - beginIndex;
//        }
//
//        numValue = val;
//        val = numValue * 1759218605L >> 44; // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue,
//        0x68db8bac710ccL); // numValue / 10000;
//        v4 = (int) (numValue - val * 10000);
//        off += writeThreeDigits((int) val, buf, off);
//        off += JSONMemoryHandle.putLong(buf, off, mergeInt64(v4, v3));
//        off += JSONMemoryHandle.putLong(buf, off, mergeInt64(v2, v1));
//        return off - beginIndex;
    }

    /**
     * ensure val >= 0 && buf.length > off + 10
     *
     * @param val max 10 digits
     */
    static final int writeInteger(int val, byte[] buf, int off) {
        int v;
        int v2;
        if (val < 10000) {
            v = val;
            if (v < 1000) {
                return writeThreeDigits(v, buf, off);
            } else {
                return JSONMemoryHandle.putInt(buf, off, FOUR_DIGITS_32_BITS[v]);
            }
        }
        final int beginIndex = off;
        long numValue = val;
        val = (int) (numValue * 1759218605L >>
                44); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710ccL); // numValue /
        // 10000;
        int v1 = (int) (numValue - val * 10000);
        if (val < 10000) {
            v = val;
            if (v < 1000) {
                off += writeThreeDigits(v, buf, off);
                off += JSONMemoryHandle.putInt(buf, off, FOUR_DIGITS_32_BITS[v1]);
            } else {
                off += JSONMemoryHandle.putLong(buf, off, mergeInt64(v, v1));
            }
            return off - beginIndex;
        } else {
            numValue = val;
            val = (int) (numValue * 1759218605L >>
                    44); // EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(numValue, 0x68db8bac710ccL); //
            // numValue / 10000;
            v2 = (int) (numValue - val * 10000);
            // max val 21
            if (val < 10) {
                buf[off++] = (byte) (val + 48);
            } else {
                off += JSONMemoryHandle.putShort(buf, off, TWO_DIGITS_16_BITS[val]);
            }
            off += JSONMemoryHandle.putLong(buf, off, mergeInt64(v2, v1));
            return off - beginIndex;
        }
    }

    /**
     * 将单个字符按 JSON 字符串规则写入，两端补引号，必要时输出转义序列
     *
     * @param ch 待写入的字符
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public final void writeJSONChar(char ch) throws IOException {
        writeJSONToken('"');
        String escapeStr;
        if ((ch > '"' && ch != '\\') || (escapeStr = JSONGeneral.ESCAPE_VALUES[ch]) == null) {
            writeJSONToken(ch);
        } else {
            write(escapeStr, 0, escapeStr.length());
        }
        writeJSONToken('"');
    }

    /**
     * 将字节数组以 Base64 编码后的带引号字符串形式写入
     *
     * @param src 待编码的字节数组
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeAsBase64String(byte[] src) throws IOException {
        writeJSONToken('"');
        char[] chars = new char[(src.length / 3 + 1) << 2];
        int len = Base64Utils.encode(src, chars, 0);
        write(chars, 0, len);
        writeJSONToken('"');
    }

    /**
     * 将字节数组以小写十六进制字符串形式写入，两端补引号
     *
     * @param src 待编码的字节数组
     * @throws IOException 写出过程中发生 IO 异常时抛出
     */
    public void writeAsHexString(byte[] src) throws IOException {
        writeJSONToken('"');
        char[] buf = new char[src.length << 1];
        int count = 0;
        for (byte b : src) {
            count += JSONMemoryHandle.putInt(buf, count, HEX_DIGITS_INT32[b & 0xff]);
        }
        write(buf, 0, count);
        writeJSONToken('"');
    }

    /**
     * 将已写入的内容输出到指定的 {@link Writer}
     *
     * @param writer 目标字符输出流
     * @throws IOException 写出过程中发生 IO 异常时抛出
     * @throws UnsupportedOperationException 默认实现不支持该操作，需由具体子类覆写
     */
    public void writeTo(Writer writer) throws IOException {
        throw new UnsupportedOperationException();
    }
}
