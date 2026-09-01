package com.alianga.jkit;

import java.text.ParseException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalQueries;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link DateUtils} 的高性能格式化/解析实现。
 *
 * <p>常用固定格式走 {@code char[]} 手写拼接，其中 {@code yyyy-MM-dd HH:mm:ss}
 * 使用秒级缓存；其余格式复用不可变的 {@link DateTimeFormatter}。
 * 无模板解析按数字字段抽取，避免 {@code SimpleDateFormat} 的同步与每次分配。</p>
 */
final class DateTimes {
    static final String DATETIME_MS = "yyyy-MM-dd HH:mm:ss.SSS";
    static final String COMPACT_DATE = "yyyyMMdd";

    private static final ConcurrentMap<String, DateTimeFormatter> FORMATTERS =
            new ConcurrentHashMap<String, DateTimeFormatter>();

    private static volatile long cachedDateTimeSecond = Long.MIN_VALUE;
    private static volatile String cachedDateTime;

    private DateTimes() {
    }

    static DateTimeFormatter formatter(String pattern) {
        DateTimeFormatter cached = FORMATTERS.get(pattern);
        if (cached != null) {
            return cached;
        }
        DateTimeFormatter created = DateTimeFormatter.ofPattern(pattern);
        DateTimeFormatter existing = FORMATTERS.putIfAbsent(pattern, created);
        return existing != null ? existing : created;
    }

    static LocalDateTime localDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    static String format(long epochMilli, String pattern) {
        if (DateUtils.DATE_TIME_PATTERN.equals(pattern)) {
            return formatDateTime(epochMilli);
        }
        if (DateUtils.DATE_PATTERN.equals(pattern)) {
            return formatDate(epochMilli);
        }
        if (DATETIME_MS.equals(pattern)) {
            return formatDateTimeMillis(epochMilli);
        }
        if (COMPACT_DATE.equals(pattern)) {
            return formatCompactDate(epochMilli);
        }
        LocalDateTime ldt = localDateTime(epochMilli);
        if (hasZonePattern(pattern)) {
            return formatter(pattern).format(ldt.atZone(ZoneId.systemDefault()));
        }
        return formatter(pattern).format(ldt);
    }

    static String formatDateTime(long epochMilli) {
        long second = Math.floorDiv(epochMilli, 1000L);
        if (second == cachedDateTimeSecond) {
            String cached = cachedDateTime;
            if (cached != null && second == cachedDateTimeSecond) {
                return cached;
            }
        }
        char[] buf = new char[19];
        writeDateTime(buf, localDateTime(epochMilli));
        String formatted = new String(buf);
        cachedDateTime = formatted;
        cachedDateTimeSecond = second;
        return formatted;
    }

    static String formatDate(long epochMilli) {
        LocalDateTime ldt = localDateTime(epochMilli);
        char[] buf = new char[10];
        writeDate(buf, 0, ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth());
        return new String(buf);
    }

    static String formatDateTimeMillis(long epochMilli) {
        LocalDateTime ldt = localDateTime(epochMilli);
        char[] buf = new char[23];
        writeDateTime(buf, ldt);
        buf[19] = '.';
        int ms = ldt.getNano() / 1_000_000;
        buf[20] = (char) ('0' + ms / 100);
        buf[21] = (char) ('0' + (ms / 10) % 10);
        buf[22] = (char) ('0' + ms % 10);
        return new String(buf);
    }

    static String formatCompactDate(long epochMilli) {
        LocalDateTime ldt = localDateTime(epochMilli);
        return formatYmd(ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth());
    }

    static String formatYmd(int year, int month, int day) {
        char[] buf = new char[8];
        write4(buf, 0, year);
        write2(buf, 4, month);
        write2(buf, 6, day);
        return new String(buf);
    }

    static Date parse(String text, String pattern) {
        try {
            if (DateUtils.DATE_TIME_PATTERN.equals(pattern) && text.length() == 19) {
                Date fast = parseDateTimeFixed(text);
                if (fast != null) {
                    return fast;
                }
            } else if (DateUtils.DATE_PATTERN.equals(pattern) && text.length() == 10) {
                Date fast = parseDateFixed(text);
                if (fast != null) {
                    return fast;
                }
            } else if (COMPACT_DATE.equals(pattern) && text.length() == 8 && isAllDigits(text, 0, 8)) {
                return ofLocal(parse4(text, 0), parse2(text, 4), parse2(text, 6), 0, 0, 0, 0);
            }
            return toDate(formatter(pattern).parse(text));
        } catch (DateTimeException e) {
            // 与旧 SimpleDateFormat 解析失败时返回 null 的行为保持一致
            return null;
        }
    }

    static Date parseUtc(String text, String pattern) {
        try {
            LocalDateTime ldt = localDateTimeFrom(formatter(pattern).parse(text));
            if (ldt == null) {
                return null;
            }
            return Date.from(ldt.toInstant(ZoneOffset.UTC));
        } catch (DateTimeException e) {
            return null;
        }
    }

    static String utcToLocal(String utcTime, String utcPattern, String localPattern) throws ParseException {
        Date utcDate = parseUtc(utcTime, utcPattern);
        if (utcDate == null) {
            throw new ParseException("Unparseable date: \"" + utcTime + "\"", 0);
        }
        return format(utcDate.getTime(), localPattern);
    }

    static Date truncate(Date date, String pattern) {
        if (DateUtils.DATE_TIME_PATTERN.equals(pattern)) {
            long t = date.getTime();
            return new Date(t - Math.floorMod(t, 1000L));
        }
        if (DateUtils.DATE_PATTERN.equals(pattern)) {
            LocalDateTime ldt = localDateTime(date.getTime())
                    .withHour(0)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0);
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        }
        String formatted = format(date.getTime(), pattern);
        return parse(formatted, pattern);
    }

    static Date parseFlexible(String str) {
        int len = str.length();
        if (len == 0) {
            return null;
        }
        if (len >= 8 && isAllDigits(str, 0, len)) {
            return parseAllDigits(str, len);
        }
        return parseSeparated(str, len);
    }

    static Date fromEpochNumber(long value) {
        if (value >= 1_000_000_000L && value <= 9_999_999_999L) {
            return new Date(value * 1000L);
        }
        return new Date(value);
    }

    static Date fromTemporal(TemporalAccessor temporal) {
        if (temporal instanceof Instant) {
            return Date.from((Instant) temporal);
        }
        if (temporal instanceof LocalDateTime) {
            return Date.from(((LocalDateTime) temporal).atZone(ZoneId.systemDefault()).toInstant());
        }
        if (temporal instanceof LocalDate) {
            return Date.from(((LocalDate) temporal).atStartOfDay(ZoneId.systemDefault()).toInstant());
        }
        if (temporal.isSupported(ChronoField.INSTANT_SECONDS)) {
            return Date.from(Instant.from(temporal));
        }
        LocalDateTime ldt = localDateTimeFrom(temporal);
        if (ldt == null) {
            return null;
        }
        ZoneId zone = temporal.query(TemporalQueries.zone());
        if (zone == null) {
            zone = ZoneId.systemDefault();
        }
        return Date.from(ldt.atZone(zone).toInstant());
    }

    private static Date parseAllDigits(String str, int len) {
        if (len == 10) {
            return new Date(parseLongDigits(str, 0, 10) * 1000L);
        }
        if (len == 13) {
            return new Date(parseLongDigits(str, 0, 13));
        }
        if (len == 8) {
            return ofLocal(parse4(str, 0), parse2(str, 4), parse2(str, 6), 0, 0, 0, 0);
        }
        if (len == 12) {
            long v = parseLongDigits(str, 0, 12);
            return ofLocal((int) (v / 100_000_000L), (int) ((v / 1_000_000L) % 100),
                    (int) ((v / 10_000L) % 100), (int) ((v / 100L) % 100), (int) (v % 100), 0, 0);
        }
        if (len == 14) {
            long v = parseLongDigits(str, 0, 14);
            return ofLocal((int) (v / 10_000_000_000L), (int) ((v / 100_000_000L) % 100),
                    (int) ((v / 1_000_000L) % 100), (int) ((v / 10_000L) % 100),
                    (int) ((v / 100L) % 100), (int) (v % 100), 0);
        }
        if (len == 17) {
            long v = parseLongDigits(str, 0, 17);
            return ofLocal((int) (v / 10_000_000_000_000L), (int) ((v / 100_000_000_000L) % 100),
                    (int) ((v / 1_000_000_000L) % 100), (int) ((v / 10_000_000L) % 100),
                    (int) ((v / 100_000L) % 100), (int) ((v / 1000L) % 100), (int) (v % 1000));
        }
        return null;
    }

    private static Date parseSeparated(String str, int len) {
        long[] fields = new long[8];
        int[] widths = new int[8];
        int n = 0;
        int offsetSeconds = Integer.MIN_VALUE;
        boolean sawT = false;
        int i = 0;
        while (i < len) {
            char ch = str.charAt(i);
            if (ch >= '0' && ch <= '9') {
                int start = i;
                long v = 0L;
                while (i < len) {
                    char d = str.charAt(i);
                    if (d < '0' || d > '9') {
                        break;
                    }
                    v = v * 10 + (d - '0');
                    i++;
                }
                if (n < fields.length) {
                    fields[n] = v;
                    widths[n] = i - start;
                    n++;
                }
                continue;
            }
            if (ch == 'T' || ch == 't') {
                sawT = true;
                i++;
                continue;
            }
            if ((ch == 'Z' || ch == 'z') && n >= 3) {
                offsetSeconds = 0;
                i++;
                continue;
            }
            if ((ch == '+' || ch == '-') && n >= 3 && (sawT || n >= 6)) {
                int sign = ch == '+' ? 1 : -1;
                i++;
                int parsed = parseOffset(str, i, len);
                if (parsed < 0) {
                    return null;
                }
                offsetSeconds = sign * parsed;
                break;
            }
            i++;
        }
        if (n < 3 && !(n == 1 && isCompactWidth(widths[0]))) {
            return null;
        }
        int year;
        int month;
        int day;
        int hour = 0;
        int minute = 0;
        int second = 0;
        int milli = 0;
        int timeIndex;
        if (isCompactWidth(widths[0])) {
            int[] ymdhms = splitCompact(fields[0], widths[0]);
            if (ymdhms == null) {
                return null;
            }
            year = ymdhms[0];
            month = ymdhms[1];
            day = ymdhms[2];
            hour = ymdhms[3];
            minute = ymdhms[4];
            second = ymdhms[5];
            milli = ymdhms[6];
            timeIndex = 1;
        } else if (fields[0] >= 1000) {
            year = (int) fields[0];
            month = (int) fields[1];
            day = (int) fields[2];
            timeIndex = 3;
        } else {
            if (n < 3) {
                return null;
            }
            day = (int) fields[0];
            month = (int) fields[1];
            year = (int) fields[2];
            timeIndex = 3;
        }
        if (timeIndex < n) {
            hour = (int) fields[timeIndex];
        }
        if (timeIndex + 1 < n) {
            minute = (int) fields[timeIndex + 1];
        }
        if (timeIndex + 2 < n) {
            second = (int) fields[timeIndex + 2];
        }
        if (timeIndex + 3 < n) {
            milli = (int) fields[timeIndex + 3];
        }
        if (offsetSeconds != Integer.MIN_VALUE) {
            return ofOffset(year, month, day, hour, minute, second, milli, offsetSeconds);
        }
        return ofLocal(year, month, day, hour, minute, second, milli);
    }

    private static boolean isCompactWidth(int width) {
        return width == 8 || width == 12 || width == 14 || width == 17;
    }

    /**
     * 把 yyyyMMdd / yyyyMMddHHmm / yyyyMMddHHmmss / yyyyMMddHHmmssSSS 拆成
     * [year, month, day, hour, minute, second, milli]。
     */
    private static int[] splitCompact(long value, int width) {
        if (width == 8) {
            return new int[]{(int) (value / 10000), (int) ((value / 100) % 100), (int) (value % 100), 0, 0, 0, 0};
        }
        if (width == 12) {
            return new int[]{(int) (value / 100_000_000L), (int) ((value / 1_000_000L) % 100),
                    (int) ((value / 10_000L) % 100), (int) ((value / 100L) % 100), (int) (value % 100), 0, 0};
        }
        if (width == 14) {
            return new int[]{(int) (value / 10_000_000_000L), (int) ((value / 100_000_000L) % 100),
                    (int) ((value / 1_000_000L) % 100), (int) ((value / 10_000L) % 100),
                    (int) ((value / 100L) % 100), (int) (value % 100), 0};
        }
        if (width == 17) {
            return new int[]{(int) (value / 10_000_000_000_000L), (int) ((value / 100_000_000_000L) % 100),
                    (int) ((value / 1_000_000_000L) % 100), (int) ((value / 10_000_000L) % 100),
                    (int) ((value / 100_000L) % 100), (int) ((value / 1000L) % 100), (int) (value % 1000)};
        }
        return null;
    }

    /**
     * 解析 {@code +HHMM} / {@code +HH:MM} / {@code +HH:MM:SS}，返回绝对值秒数；失败返回 -1。
     */
    private static int parseOffset(String str, int start, int len) {
        int i = start;
        int numStart = i;
        while (i < len && str.charAt(i) >= '0' && str.charAt(i) <= '9') {
            i++;
        }
        int digits = i - numStart;
        if (digits == 0) {
            return -1;
        }
        int hours;
        int minutes = 0;
        int seconds = 0;
        if (digits == 4) {
            int packed = (int) parseLongDigits(str, numStart, i);
            hours = packed / 100;
            minutes = packed % 100;
        } else if (digits == 6) {
            int packed = (int) parseLongDigits(str, numStart, i);
            hours = packed / 10000;
            minutes = (packed / 100) % 100;
            seconds = packed % 100;
        } else {
            hours = (int) parseLongDigits(str, numStart, i);
            if (i < len && str.charAt(i) == ':') {
                i++;
                int minStart = i;
                while (i < len && str.charAt(i) >= '0' && str.charAt(i) <= '9') {
                    i++;
                }
                if (i == minStart) {
                    return -1;
                }
                minutes = (int) parseLongDigits(str, minStart, i);
                if (i < len && str.charAt(i) == ':') {
                    i++;
                    int secStart = i;
                    while (i < len && str.charAt(i) >= '0' && str.charAt(i) <= '9') {
                        i++;
                    }
                    if (i > secStart) {
                        seconds = (int) parseLongDigits(str, secStart, i);
                    }
                }
            }
        }
        return hours * 3600 + minutes * 60 + seconds;
    }

    private static Date parseDateTimeFixed(String text) {
        if (text.charAt(4) != '-' || text.charAt(7) != '-' || text.charAt(10) != ' '
                || text.charAt(13) != ':' || text.charAt(16) != ':') {
            return null;
        }
        return ofLocal(parse4(text, 0), parse2(text, 5), parse2(text, 8),
                parse2(text, 11), parse2(text, 14), parse2(text, 17), 0);
    }

    private static Date parseDateFixed(String text) {
        if (text.charAt(4) != '-' || text.charAt(7) != '-') {
            return null;
        }
        return ofLocal(parse4(text, 0), parse2(text, 5), parse2(text, 8), 0, 0, 0, 0);
    }

    private static Date toDate(TemporalAccessor parsed) {
        if (parsed.isSupported(ChronoField.INSTANT_SECONDS)) {
            return Date.from(Instant.from(parsed));
        }
        ZoneId zone = parsed.query(TemporalQueries.zone());
        if (zone == null) {
            zone = ZoneId.systemDefault();
        }
        LocalDateTime ldt = localDateTimeFrom(parsed);
        if (ldt == null) {
            return Date.from(Instant.from(parsed));
        }
        return Date.from(ldt.atZone(zone).toInstant());
    }

    private static LocalDateTime localDateTimeFrom(TemporalAccessor parsed) {
        LocalDate date = parsed.query(TemporalQueries.localDate());
        if (date == null) {
            return null;
        }
        LocalTime time = parsed.query(TemporalQueries.localTime());
        return time == null ? date.atStartOfDay() : LocalDateTime.of(date, time);
    }

    private static Date ofLocal(int year, int month, int day, int hour, int minute, int second, int milli) {
        try {
            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second, milli * 1_000_000);
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static Date ofOffset(int year, int month, int day, int hour, int minute, int second, int milli,
                                 int offsetSeconds) {
        try {
            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute, second, milli * 1_000_000);
            return Date.from(ldt.toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static boolean hasZonePattern(String pattern) {
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && (ch == 'Z' || ch == 'X' || ch == 'x' || ch == 'z' || ch == 'O' || ch == 'V')) {
                return true;
            }
        }
        return false;
    }

    private static void writeDateTime(char[] buf, LocalDateTime ldt) {
        writeDate(buf, 0, ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth());
        buf[10] = ' ';
        write2(buf, 11, ldt.getHour());
        buf[13] = ':';
        write2(buf, 14, ldt.getMinute());
        buf[16] = ':';
        write2(buf, 17, ldt.getSecond());
    }

    private static void writeDate(char[] buf, int off, int year, int month, int day) {
        write4(buf, off, year);
        buf[off + 4] = '-';
        write2(buf, off + 5, month);
        buf[off + 7] = '-';
        write2(buf, off + 8, day);
    }

    private static void write4(char[] buf, int off, int v) {
        buf[off] = (char) ('0' + (v / 1000) % 10);
        buf[off + 1] = (char) ('0' + (v / 100) % 10);
        buf[off + 2] = (char) ('0' + (v / 10) % 10);
        buf[off + 3] = (char) ('0' + v % 10);
    }

    private static void write2(char[] buf, int off, int v) {
        buf[off] = (char) ('0' + v / 10);
        buf[off + 1] = (char) ('0' + v % 10);
    }

    private static int parse2(String text, int off) {
        return (text.charAt(off) - '0') * 10 + (text.charAt(off + 1) - '0');
    }

    private static int parse4(String text, int off) {
        return (text.charAt(off) - '0') * 1000
                + (text.charAt(off + 1) - '0') * 100
                + (text.charAt(off + 2) - '0') * 10
                + (text.charAt(off + 3) - '0');
    }

    private static long parseLongDigits(String text, int start, int end) {
        long v = 0L;
        for (int i = start; i < end; i++) {
            v = v * 10 + (text.charAt(i) - '0');
        }
        return v;
    }

    private static boolean isAllDigits(String text, int start, int end) {
        if (end <= start) {
            return false;
        }
        for (int i = start; i < end; i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }
}
