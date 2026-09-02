package com.alianga.jkit.json.internal.beans;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 常用日期格式化
 *
 */
public class DateFormatter {
    private DateTemplate dateTemplate;

    private int estimateSize = -1;

    /**
     * {@code yyyyMMddHHmmssSSS} 格式（17 位，无分隔符、含毫秒）的格式化器
     */
    public static final DateFormatter YMDHMS_S_17 = new DateFormatterYMDHMS_S_17();
    /**
     * {@code yyyyMMddHHmmss} 格式（14 位，无分隔符）的格式化器
     */
    public static final DateFormatter YMDHMS_14 = new DateFormatterYMDHMS_14();
    /**
     * {@code yyyyMMdd} 格式（8 位纯日期）的格式化器
     */
    public static final DateFormatter YMD_8 = new DateFormatterYMD_8();
    /**
     * {@code HHmmss} 格式（6 位纯时间）的格式化器
     */
    public static final DateFormatter HMS_6 = new DateFormatterHMS_6();
    private static Map<String, DateFormatter> dateFormatterMap = new HashMap<String, DateFormatter>();

    static {
        DateFormatter temp;
        dateFormatterMap.put("yyyy-MM-dd HH:mm:ss", temp = DateFormatter.of('-', ':', ' '));
        dateFormatterMap.put("Y-M-d H:m:s", temp);
        dateFormatterMap.put("yyyy-MM-ddTHH:mm:ss", temp = DateFormatter.of('-', ':', 'T'));
        dateFormatterMap.put("yyyy-MM-dd'T'HH:mm:ss", temp);
        dateFormatterMap.put("yyyy/MM/dd HH:mm:ss", temp = DateFormatter.of('/', ':', ' '));
        dateFormatterMap.put("Y/M/d H:m:s", temp);
        dateFormatterMap.put("yyyy/MM/ddTHH:mm:ss", temp = DateFormatter.of('/', ':', 'T'));
        dateFormatterMap.put("yyyy/MM/dd'T'HH:mm:ss", temp);

        dateFormatterMap.put("yyyy-MM-dd HH:mm:ss.S", temp = DateFormatter.of('-', ':', ' ', true));
        dateFormatterMap.put("yyyy-MM-dd HH:mm:ss.SSS", temp);
        dateFormatterMap.put("yyyy-MM-ddTHH:mm:ss.S", temp = DateFormatter.of('-', ':', 'T', true));
        dateFormatterMap.put("yyyy-MM-ddTHH:mm:ss.SSS", temp);
        dateFormatterMap.put("yyyy-MM-dd'T'HH:mm:ss.S", temp);
        dateFormatterMap.put("yyyy-MM-dd'T'HH:mm:ss.SSS", temp);
        dateFormatterMap.put("yyyy/MM/dd HH:mm:ss.S", temp = DateFormatter.of('/', ':', ' ', true));
        dateFormatterMap.put("yyyy/MM/dd HH:mm:ss.SSS", temp);
        dateFormatterMap.put("yyyy/MM/ddTHH:mm:ss.S", temp = DateFormatter.of('/', ':', 'T', true));
        dateFormatterMap.put("yyyy/MM/ddTHH:mm:ss.SSS", temp);
        dateFormatterMap.put("yyyy/MM/dd'T'HH:mm:ss.S", temp);
        dateFormatterMap.put("yyyy/MM/dd'T'HH:mm:ss.SSS", temp);

        dateFormatterMap.put("yyyyMMddHHmmss", DateFormatter.YMDHMS_14);
        dateFormatterMap.put("YMdHms", DateFormatter.YMDHMS_14);
        dateFormatterMap.put("yyyyMMddHHmmssS", DateFormatter.YMDHMS_S_17);
        dateFormatterMap.put("yyyyMMddHHmmssSSS", DateFormatter.YMDHMS_S_17);
        dateFormatterMap.put("yyyyMMdd", DateFormatter.YMD_8);
        dateFormatterMap.put("YMd", DateFormatter.YMD_8);
        dateFormatterMap.put("HHmmss", DateFormatter.HMS_6);
        dateFormatterMap.put("Hms", DateFormatter.HMS_6);

        dateFormatterMap.put("yyyy-MM-dd", temp = DateFormatter.ofDate('-'));
        dateFormatterMap.put("Y-M-d", temp);
        dateFormatterMap.put("yyyy/MM/dd", temp = DateFormatter.ofDate('/'));
        dateFormatterMap.put("Y/M/d", temp);

        dateFormatterMap.put("HH:mm:ss", temp = DateFormatter.ofTime(':'));
        dateFormatterMap.put("H:m:s", temp);
        dateFormatterMap.put("HH/mm/ss", temp = DateFormatter.ofTime('/'));
        dateFormatterMap.put("H/m/s", temp);
    }

    /**
     * 获取格式化结果的预估字符长度，用于预分配缓冲区。
     *
     * @return 预估的字符长度；未按模板初始化时返回 {@code -1}
     */
    public int getEstimateSize() {
        return estimateSize;
    }

    /**
     * 通用表达式
     *
     * @param pattern 日期格式表达式，如 {@code yyyy-MM-dd HH:mm:ss}
     * @return 对应的格式化器，常用格式取自内置缓存，其他格式按模板新建；{@code pattern} 为 {@code null} 时返回 {@code null}
     */
    public static DateFormatter of(String pattern) {
        if (pattern == null) {
            return null;
        }
        // from cache
        if (dateFormatterMap.containsKey(pattern)) {
            return dateFormatterMap.get(pattern);
        }

        // not cache
        DateFormatter dateFormatter = new DateFormatter();
        dateFormatter.dateTemplate = new DateTemplate(pattern);
        dateFormatter.estimateSize = dateFormatter.dateTemplate.estimateSize();
        return dateFormatter;
    }

    /**
     * 支持yyyy?MM?dd?HH?mm?ss
     *
     * @param dateToken 年月日之间的分隔符
     * @param timeToken 时分秒之间的分隔符
     * @param concat    日期与时间之间的连接符
     * @return 输出 19 位日期时间（不含毫秒）的格式化器
     */
    public static DateFormatter of(char dateToken, char timeToken, char concat) {
        return new DateFormatterYMDHMS_19(dateToken, timeToken, concat);
    }

    /**
     * 构建日期时间格式化器，可选择是否输出毫秒
     *
     * @param dateToken 年月日之间的分隔符
     * @param timeToken 时分秒之间的分隔符
     * @param concat    日期与时间之间的连接符
     * @param millis    是否输出毫秒
     * @return millis 为 {@code true} 时返回输出 23 位日期时间（含 3 位毫秒）的格式化器，
     *         否则返回输出 19 位日期时间的格式化器
     */
    public static DateFormatter of(char dateToken, char timeToken, char concat, boolean millis) {
        DateFormatterYMDHMS_19 formatter = new DateFormatterYMDHMS_19(dateToken, timeToken, concat);
        return millis ? new DateFormatterYMDHMS_S_23(formatter) : formatter;
    }

    /**
     * yyyy?mm?dd
     *
     * @param dateToken 年月日之间的分隔符
     * @return 输出 10 位纯日期的格式化器
     */
    public static DateFormatter ofDate(char dateToken) {
        return new DateFormatterYMD_10(dateToken);
    }

    /**
     * HH?mm?ss
     *
     * @param timeToken 时分秒之间的分隔符
     * @return 输出 8 位纯时间的格式化器
     */
    public static DateFormatter ofTime(char timeToken) {
        return new DateFormatterHMS_8(timeToken);
    }

    /**
     * 格式化日期
     *
     * @param date 待格式化的日期
     * @return 按当前格式输出的日期字符串
     */
    public String format(GregorianDate date) {
        StringBuilder builder = new StringBuilder();
        dateTemplate.formatTo(date, builder);
        return builder.toString();
    }

    /**
     * 通用格式化(不带毫秒)
     *
     * @param year       年
     * @param month      月（1 ~ 12）
     * @param dayOfMonth 日
     * @param hour       小时（0 ~ 23）
     * @param minute     分钟
     * @param second     秒
     * @return 按当前格式输出的日期时间字符串，毫秒按 0 处理
     */
    public String format(int year,
                         int month,
                         int dayOfMonth,
                         int hour,
                         int minute,
                         int second) {
        StringBuilder builder = new StringBuilder();
        dateTemplate.formatTo(year, month, dayOfMonth, hour, minute, second, 0, builder);
        return builder.toString();
    }

    /**
     * 通用格式化（带毫秒）
     *
     * @param year        年
     * @param month       月（1 ~ 12）
     * @param dayOfMonth  日
     * @param hour        小时（0 ~ 23）
     * @param minute      分钟
     * @param second      秒
     * @param millisecond 毫秒
     * @return 按当前格式输出的日期时间字符串
     */
    public String format(int year,
                         int month,
                         int dayOfMonth,
                         int hour,
                         int minute,
                         int second,
                         int millisecond) {
        StringBuilder builder = new StringBuilder();
        dateTemplate.formatTo(year, month, dayOfMonth, hour, minute, second, millisecond, builder);
        return builder.toString();
    }

    /**
     * 格式化日期
     *
     * @param date 待格式化的日期
     * @param appendable 输出目标
     */
    public void formatTo(GregorianDate date, Appendable appendable) {
        dateTemplate.formatTo(date, appendable);
    }

    /**
     * 格式化到指定appendable
     *
     * @param year       年
     * @param month      月（1 ~ 12）
     * @param dayOfMonth 日
     * @param hour       小时（0 ~ 23）
     * @param minute     分钟
     * @param second     秒
     * @param appendable 输出目标
     */
    public void formatTo(int year,
                         int month,
                         int dayOfMonth,
                         int hour,
                         int minute,
                         int second,
                         Appendable appendable) {
        dateTemplate.formatTo(year, month, dayOfMonth, hour, minute, second, 0, appendable);
    }

    /**
     * 格式化到指定appendable
     *
     * @param year        年
     * @param month       月（1 ~ 12）
     * @param dayOfMonth  日
     * @param hour        小时（0 ~ 23）
     * @param minute      分钟
     * @param second      秒
     * @param millisecond 毫秒
     * @param appendable  输出目标
     */
    public void formatTo(int year,
                         int month,
                         int dayOfMonth,
                         int hour,
                         int minute,
                         int second,
                         int millisecond,
                         Appendable appendable) {
        dateTemplate.formatTo(year, month, dayOfMonth, hour, minute, second, millisecond, appendable);
    }

    abstract static class PatternedFormatter extends DateFormatter {
        @Override
        public String format(int year, int month, int dayOfMonth, int hour, int minute, int second) {
            StringBuilder appendable = new StringBuilder();
            formatTo(year, month, dayOfMonth, hour, minute, second, appendable);
            return appendable.toString();
        }

        @Override
        public void formatTo(GregorianDate date, Appendable appendable) {
            formatTo(date.year, date.month, date.dayOfMonth, date.hourOfDay, date.minute, date.second, appendable);
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                             Appendable appendable) {
            formatTo(year, month, dayOfMonth, hour, minute, second, appendable);
        }

        protected void appendMillisecond(Appendable appendable, int millisecond) throws IOException {
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            char s1 = (char) (millisecond / 100 + 48);
            int v = millisecond % 100;
            appendable.append(s1);
            appendable.append(DigitTens[v]);
            appendable.append(DigitOnes[v]);
        }
    }

    // yyyy?MM?dd?HH?mm?ss
    static class DateFormatterYMDHMS_19 extends PatternedFormatter {
        private final char dateToken;
        private final char timeToken;
        private final char concat;

        private DateFormatterYMDHMS_19(char dateToken, char timeToken, char concat) {
            this.dateToken = dateToken;
            this.timeToken = timeToken;
            this.concat = concat;
        }

        @Override
        public int getEstimateSize() {
            return 30;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            try {
                if (year < 0) {
                    appendable.append('-');
                    year = -year;
                }
                int y1 = year / 100;
                int y2 = year % 100;
                char[] DigitTens = DateTemplate.DigitTens;
                char[] DigitOnes = DateTemplate.DigitOnes;
                appendable.append(DigitTens[y1]);
                appendable.append(DigitOnes[y1]);
                appendable.append(DigitTens[y2]);
                appendable.append(DigitOnes[y2]);
                appendable.append(dateToken);
                appendable.append(DigitTens[month]);
                appendable.append(DigitOnes[month]);
                appendable.append(dateToken);
                appendable.append(DigitTens[dayOfMonth]);
                appendable.append(DigitOnes[dayOfMonth]);
                appendable.append(concat);
                appendable.append(DigitTens[hour]);
                appendable.append(DigitOnes[hour]);
                appendable.append(timeToken);
                appendable.append(DigitTens[minute]);
                appendable.append(DigitOnes[minute]);
                appendable.append(timeToken);
                appendable.append(DigitTens[second]);
                appendable.append(DigitOnes[second]);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            int size = 19;
            if (year < 0) {
                ++size;
                buf[off++] = '-';
                year = -year;
            }
            int y1 = year / 100;
            int y2 = year % 100;
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            buf[off++] = DigitTens[y1];
            buf[off++] = DigitOnes[y1];
            buf[off++] = DigitTens[y2];
            buf[off++] = DigitOnes[y2];
            buf[off++] = dateToken;
            buf[off++] = DigitTens[month];
            buf[off++] = DigitOnes[month];
            buf[off++] = dateToken;
            buf[off++] = DigitTens[dayOfMonth];
            buf[off++] = DigitOnes[dayOfMonth];
            buf[off++] = concat;
            buf[off++] = DigitTens[hour];
            buf[off++] = DigitOnes[hour];
            buf[off++] = timeToken;
            buf[off++] = DigitTens[minute];
            buf[off++] = DigitOnes[minute];
            buf[off++] = timeToken;
            buf[off++] = DigitTens[second];
            buf[off] = DigitOnes[second];
            return size;
        }
    }

    // yyyy?MM?dd?HH?mm?ss.S+
    static class DateFormatterYMDHMS_S_23 extends PatternedFormatter {
        private final DateFormatterYMDHMS_19 dateFormatterYMDHMS_19;

        private DateFormatterYMDHMS_S_23(DateFormatterYMDHMS_19 dateFormatterYMDHMS_19) {
            dateFormatterYMDHMS_19.getClass();
            this.dateFormatterYMDHMS_19 = dateFormatterYMDHMS_19;
        }

        @Override
        public int getEstimateSize() {
            return 28;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            formatTo(year, month, dayOfMonth, hour, minute, second, 0, appendable);
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                             Appendable appendable) {
            try {
                dateFormatterYMDHMS_19.formatTo(year, month, dayOfMonth, hour, minute, second, appendable);
                appendable.append('.');
                appendMillisecond(appendable, millisecond);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            final int begin = off;
            off += dateFormatterYMDHMS_19.write(year, month, dayOfMonth, hour, minute, second, millisecond, buf, off);
            char s1 = (char) (millisecond / 100 + 48);
            int v = millisecond % 100;
            buf[off++] = '.';
            buf[off++] = s1;
            buf[off++] = DateTemplate.DigitTens[v];
            buf[off++] = DateTemplate.DigitOnes[v];
            return off - begin;
        }
    }

    /**
     * yyyyMMddHHmmss
     */
    static class DateFormatterYMDHMS_14 extends PatternedFormatter {
        private DateFormatterYMDHMS_14() {
        }

        @Override
        public int getEstimateSize() {
            return 15;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            try {
                if (year < 0) {
                    appendable.append('-');
                    year = -year;
                }
                int y1 = year / 100;
                int y2 = year % 100;
                char[] DigitTens = DateTemplate.DigitTens;
                char[] DigitOnes = DateTemplate.DigitOnes;
                appendable.append(DigitTens[y1]);
                appendable.append(DigitOnes[y1]);
                appendable.append(DigitTens[y2]);
                appendable.append(DigitOnes[y2]);
                appendable.append(DigitTens[month]);
                appendable.append(DigitOnes[month]);
                appendable.append(DigitTens[dayOfMonth]);
                appendable.append(DigitOnes[dayOfMonth]);
                appendable.append(DigitTens[hour]);
                appendable.append(DigitOnes[hour]);
                appendable.append(DigitTens[minute]);
                appendable.append(DigitOnes[minute]);
                appendable.append(DigitTens[second]);
                appendable.append(DigitOnes[second]);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            int size = 14;
            if (year < 0) {
                ++size;
                buf[off++] = '-';
                year = -year;
            }
            int y1 = year / 100;
            int y2 = year % 100;
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            buf[off++] = DigitTens[y1];
            buf[off++] = DigitOnes[y1];
            buf[off++] = DigitTens[y2];
            buf[off++] = DigitOnes[y2];
            buf[off++] = DigitTens[month];
            buf[off++] = DigitOnes[month];
            buf[off++] = DigitTens[dayOfMonth];
            buf[off++] = DigitOnes[dayOfMonth];

            buf[off++] = DigitTens[hour];
            buf[off++] = DigitOnes[hour];
            buf[off++] = DigitTens[minute];
            buf[off++] = DigitOnes[minute];
            buf[off++] = DigitTens[second];
            buf[off] = DigitOnes[second];
            return size;
        }
    }

    /**
     * yyyyMMddHHmmssSSS
     */
    static class DateFormatterYMDHMS_S_17 extends PatternedFormatter {
        private DateFormatterYMDHMS_S_17() {
        }

        @Override
        public int getEstimateSize() {
            return 18;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                             Appendable appendable) {
            try {
                if (year < 0) {
                    appendable.append('-');
                    year = -year;
                }
                int y1 = year / 100;
                int y2 = year % 100;
                char[] DigitTens = DateTemplate.DigitTens;
                char[] DigitOnes = DateTemplate.DigitOnes;
                appendable.append(DigitTens[y1]);
                appendable.append(DigitOnes[y1]);
                appendable.append(DigitTens[y2]);
                appendable.append(DigitOnes[y2]);
                appendable.append(DigitTens[month]);
                appendable.append(DigitOnes[month]);
                appendable.append(DigitTens[dayOfMonth]);
                appendable.append(DigitOnes[dayOfMonth]);
                appendable.append(DigitTens[hour]);
                appendable.append(DigitOnes[hour]);
                appendable.append(DigitTens[minute]);
                appendable.append(DigitOnes[minute]);
                appendable.append(DigitTens[second]);
                appendable.append(DigitOnes[second]);
                appendMillisecond(appendable, millisecond);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            formatTo(year, month, dayOfMonth, hour, minute, second, 0, appendable);
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            int size = 17;
            if (year < 0) {
                ++size;
                buf[off++] = '-';
                year = -year;
            }
            int y1 = year / 100;
            int y2 = year % 100;
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            buf[off++] = DigitTens[y1];
            buf[off++] = DigitOnes[y1];
            buf[off++] = DigitTens[y2];
            buf[off++] = DigitOnes[y2];
            buf[off++] = DigitTens[month];
            buf[off++] = DigitOnes[month];
            buf[off++] = DigitTens[dayOfMonth];
            buf[off++] = DigitOnes[dayOfMonth];

            buf[off++] = DigitTens[hour];
            buf[off++] = DigitOnes[hour];
            buf[off++] = DigitTens[minute];
            buf[off++] = DigitOnes[minute];
            buf[off++] = DigitTens[second];
            buf[off++] = DigitOnes[second];

            char s1 = (char) (millisecond / 100 + 48);
            int v = millisecond % 100;
            buf[off++] = s1;
            buf[off++] = DigitTens[v];
            buf[off] = DigitOnes[v];
            return size;
        }
    }

    // yyyy?MM?dd
    static class DateFormatterYMD_10 extends PatternedFormatter {
        private final char dateToken;

        private DateFormatterYMD_10(char dateToken) {
            this.dateToken = dateToken;
        }

        @Override
        public int getEstimateSize() {
            return 11;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            try {
                if (year < 0) {
                    appendable.append('-');
                    year = -year;
                }
                int y1 = year / 100;
                int y2 = year % 100;
                char[] DigitTens = DateTemplate.DigitTens;
                char[] DigitOnes = DateTemplate.DigitOnes;
                appendable.append(DigitTens[y1]);
                appendable.append(DigitOnes[y1]);
                appendable.append(DigitTens[y2]);
                appendable.append(DigitOnes[y2]);
                appendable.append(dateToken);
                appendable.append(DigitTens[month]);
                appendable.append(DigitOnes[month]);
                appendable.append(dateToken);
                appendable.append(DigitTens[dayOfMonth]);
                appendable.append(DigitOnes[dayOfMonth]);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            int size = 10;
            if (year < 0) {
                ++size;
                buf[off++] = '-';
                year = -year;
            }
            int y1 = year / 100;
            int y2 = year % 100;
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            buf[off++] = DigitTens[y1];
            buf[off++] = DigitOnes[y1];
            buf[off++] = DigitTens[y2];
            buf[off++] = DigitOnes[y2];
            buf[off++] = dateToken;
            buf[off++] = DigitTens[month];
            buf[off++] = DigitOnes[month];
            buf[off++] = dateToken;
            buf[off++] = DigitTens[dayOfMonth];
            buf[off] = DigitOnes[dayOfMonth];
            return size;
        }
    }

    // yyyyMMdd
    static class DateFormatterYMD_8 extends PatternedFormatter {
        private DateFormatterYMD_8() {
        }

        @Override
        public int getEstimateSize() {
            return 9;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            try {
                if (year < 0) {
                    appendable.append('-');
                    year = -year;
                }
                int y1 = year / 100;
                int y2 = year % 100;
                char[] DigitTens = DateTemplate.DigitTens;
                char[] DigitOnes = DateTemplate.DigitOnes;
                appendable.append(DigitTens[y1]);
                appendable.append(DigitOnes[y1]);
                appendable.append(DigitTens[y2]);
                appendable.append(DigitOnes[y2]);
                appendable.append(DigitTens[month]);
                appendable.append(DigitOnes[month]);
                appendable.append(DigitTens[dayOfMonth]);
                appendable.append(DigitOnes[dayOfMonth]);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            int size = 8;
            if (year < 0) {
                ++size;
                buf[off++] = '-';
                year = -year;
            }
            int y1 = year / 100;
            int y2 = year % 100;
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            buf[off++] = DigitTens[y1];
            buf[off++] = DigitOnes[y1];
            buf[off++] = DigitTens[y2];
            buf[off++] = DigitOnes[y2];
            buf[off++] = DigitTens[month];
            buf[off++] = DigitOnes[month];
            buf[off++] = DigitTens[dayOfMonth];
            buf[off] = DigitOnes[dayOfMonth];
            return size;
        }
    }

    // HHmmss
    static class DateFormatterHMS_6 extends PatternedFormatter {
        @Override
        public int getEstimateSize() {
            return 6;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            try {
                char[] DigitTens = DateTemplate.DigitTens;
                char[] DigitOnes = DateTemplate.DigitOnes;
                appendable.append(DigitTens[hour]);
                appendable.append(DigitOnes[hour]);
                appendable.append(DigitTens[minute]);
                appendable.append(DigitOnes[minute]);
                appendable.append(DigitTens[second]);
                appendable.append(DigitOnes[second]);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            buf[off++] = DigitTens[hour];
            buf[off++] = DigitOnes[hour];
            buf[off++] = DigitTens[minute];
            buf[off++] = DigitOnes[minute];
            buf[off++] = DigitTens[second];
            buf[off] = DigitOnes[second];
            return 6;
        }
    }

    // HH?mm?ss
    static class DateFormatterHMS_8 extends PatternedFormatter {
        private char timeToken;

        private DateFormatterHMS_8(char timeToken) {
            this.timeToken = timeToken;
        }

        @Override
        public int getEstimateSize() {
            return 8;
        }

        @Override
        public void formatTo(int year, int month, int dayOfMonth, int hour, int minute, int second,
                             Appendable appendable) {
            try {
                char[] DigitTens = DateTemplate.DigitTens;
                char[] DigitOnes = DateTemplate.DigitOnes;
                appendable.append(DigitTens[hour]);
                appendable.append(DigitOnes[hour]);
                appendable.append(timeToken);
                appendable.append(DigitTens[minute]);
                appendable.append(DigitOnes[minute]);
                appendable.append(timeToken);
                appendable.append(DigitTens[second]);
                appendable.append(DigitOnes[second]);
            } catch (IOException e) {
                throw new UnsupportedOperationException(e);
            }
        }

        public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond,
                         char[] buf, int off) {
            char[] DigitTens = DateTemplate.DigitTens;
            char[] DigitOnes = DateTemplate.DigitOnes;
            buf[off++] = DigitTens[hour];
            buf[off++] = DigitOnes[hour];
            buf[off++] = timeToken;
            buf[off++] = DigitTens[minute];
            buf[off++] = DigitOnes[minute];
            buf[off++] = timeToken;
            buf[off++] = DigitTens[second];
            buf[off] = DigitOnes[second];
            return 8;
        }
    }

    /**
     * 将日期时间按当前格式直接写入字符数组。
     *
     * @param year        年
     * @param month       月（1 ~ 12）
     * @param dayOfMonth  日
     * @param hour        小时（0 ~ 23）
     * @param minute      分钟
     * @param second      秒
     * @param millisecond 毫秒
     * @param buf         目标字符数组
     * @param off         写入的起始下标
     * @return 实际写入的字符数
     */
    public int write(int year, int month, int dayOfMonth, int hour, int minute, int second, int millisecond, char[] buf,
                     int off) {
        return dateTemplate.write(year, month, dayOfMonth, hour, minute, second, millisecond, buf, off);
    }
}
