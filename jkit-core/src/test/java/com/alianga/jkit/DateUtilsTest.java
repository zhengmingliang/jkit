package com.alianga.jkit;

import org.junit.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DateUtils} 日期格式化/解析的正确性与效率对比。
 */
public class DateUtilsTest {

    private static final long[] SAMPLE_MILLIS = {
            0L,
            1_000L,
            1_638_702_321_000L,
            1_638_702_321_200L,
            1_650_000_000_123L,
            1_704_067_200_000L,
            System.currentTimeMillis()
    };

    private static final String[] PATTERNS = {
            DateUtils.DATE_TIME_PATTERN,
            DateUtils.DATE_PATTERN,
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyyMMdd",
            "yyyyMMddHHmmss",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy年MM月dd日"
    };

    @Test
    public void format_matchesSimpleDateFormatForCommonPatterns() {
        for (String pattern : PATTERNS) {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.getDefault());
            for (long epochMilli : SAMPLE_MILLIS) {
                Date date = new Date(epochMilli);
                assertEquals("pattern=" + pattern + " ts=" + epochMilli,
                        sdf.format(date), DateUtils.getStringByPattern(date, pattern));
            }
        }
    }

    @Test
    public void getDateTimeAndDateString_matchSimpleDateFormat() {
        SimpleDateFormat dateTime = new SimpleDateFormat(DateUtils.DATE_TIME_PATTERN);
        SimpleDateFormat date = new SimpleDateFormat(DateUtils.DATE_PATTERN);
        for (long epochMilli : SAMPLE_MILLIS) {
            Date d = new Date(epochMilli);
            assertEquals(dateTime.format(d), DateUtils.getDateTimeString(d));
            assertEquals(date.format(d), DateUtils.getDateString(d));
        }
    }

    @Test
    public void parse_matchesSimpleDateFormatForFixedPatterns() throws ParseException {
        Date now = new Date();
        for (String pattern : PATTERNS) {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.getDefault());
            String formatted = sdf.format(now);
            Date expected = sdf.parse(formatted);
            Date actual = DateUtils.getDateByGiven(formatted, pattern);
            assertNotNull("pattern=" + pattern + " text=" + formatted, actual);
            assertEquals("pattern=" + pattern + " text=" + formatted, expected, actual);
        }
    }

    @Test
    public void getDateByPattern_truncatesToPatternPrecision() {
        Date withMillis = new Date(1_650_000_000_123L);
        Date truncated = DateUtils.getDateByPattern(withMillis, DateUtils.DATE_TIME_PATTERN);
        assertEquals(0, truncated.getTime() % 1000L);

        Date beginOfDay = DateUtils.getDateByPattern(withMillis, DateUtils.DATE_PATTERN);
        assertEquals(DateUtils.getBeginOfDay(withMillis), beginOfDay);
    }

    @Test
    public void utc2LocalDate_parsesUtcInstant() throws ParseException {
        String utc = "2021-11-28T14:33:31.000Z";
        Date actual = DateUtils.utc2LocalDate(utc);
        assertNotNull(actual);
        assertEquals(Instant.parse("2021-11-28T14:33:31.000Z").toEpochMilli(), actual.getTime());

        String local = DateUtils.utc2Local(utc, DateUtils.DATE_TIME_PATTERN);
        SimpleDateFormat localSdf = new SimpleDateFormat(DateUtils.DATE_TIME_PATTERN);
        assertEquals(localSdf.format(actual), local);
    }

    @Test
    public void utc2Local_customPattern() throws ParseException {
        String utc = "2021-11-28T14:33:31.123Z";
        String local = DateUtils.utc2Local(utc, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd HH:mm:ss.SSS");
        Date expected = Date.from(Instant.parse("2021-11-28T14:33:31.123Z"));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        assertEquals(sdf.format(expected), local);
    }

    @Test
    public void parse_returnsNullForInvalidInput() {
        assertNull(DateUtils.parse("not-a-date"));
        assertNull(DateUtils.parse(""));
        assertNull(DateUtils.parse(null));
        assertNull(DateUtils.getDateByGiven("2021-13-40", DateUtils.DATE_PATTERN));
    }

    @Test
    public void getIntNowDate_yyyyMMdd_matchesFormatter() {
        int today = DateUtils.getIntNowDate("yyyyMMdd");
        String formatted = DateUtils.getNowDateStrByPattern("yyyyMMdd");
        assertEquals(Integer.parseInt(formatted), today);
        assertEquals(8, formatted.length());
    }

    @Test
    public void format_isFasterThanAllocatingSimpleDateFormat() {
        long epochMilli = System.currentTimeMillis();
        Date date = new Date(epochMilli);
        String pattern = DateUtils.DATE_TIME_PATTERN;
        int warmup = 80_000;
        int iters = 200_000;

        for (int i = 0; i < warmup; i++) {
            DateUtils.getDateTimeString(date);
            new SimpleDateFormat(pattern).format(date);
            reusedSdf().format(date);
            reusedDtf().format(Instant.ofEpochMilli(epochMilli));
        }

        double dateUtilsNs = benchFormat(iters, () -> DateUtils.getDateTimeString(date));
        double sdfNewNs = benchFormat(iters, () -> new SimpleDateFormat(pattern).format(date));
        double sdfReuseNs = benchFormat(iters, () -> reusedSdf().format(date));
        double dtfReuseNs = benchFormat(iters, () -> reusedDtf().format(Instant.ofEpochMilli(epochMilli)));

        System.out.println("=== DateUtils 格式化效率对比 yyyy-MM-dd HH:mm:ss ===");
        System.out.printf("DateUtils (秒级缓存+char[]) : %.1f ns/op%n", dateUtilsNs);
        System.out.printf("SimpleDateFormat (每次new)  : %.1f ns/op%n", sdfNewNs);
        System.out.printf("SimpleDateFormat (复用)     : %.1f ns/op%n", sdfReuseNs);
        System.out.printf("DateTimeFormatter (复用)    : %.1f ns/op%n", dtfReuseNs);

        assertTrue("DateUtils 应快于每次 new SimpleDateFormat，实际 "
                        + dateUtilsNs + " vs " + sdfNewNs,
                dateUtilsNs < sdfNewNs);
    }

    @Test
    public void formatMillis_isFasterThanAllocatingSimpleDateFormat() {
        long epochMilli = System.currentTimeMillis();
        Date date = new Date(epochMilli);
        String pattern = "yyyy-MM-dd HH:mm:ss.SSS";
        int warmup = 80_000;
        int iters = 200_000;

        for (int i = 0; i < warmup; i++) {
            DateUtils.getStringByPattern(date, pattern);
            new SimpleDateFormat(pattern).format(date);
        }

        double dateUtilsNs = benchFormat(iters, () -> DateUtils.getStringByPattern(date, pattern));
        double sdfNewNs = benchFormat(iters, () -> new SimpleDateFormat(pattern).format(date));
        double dtfReuseNs = benchFormat(iters,
                () -> reusedMillisDtf().format(Instant.ofEpochMilli(epochMilli)));

        System.out.println("=== DateUtils 格式化效率对比 yyyy-MM-dd HH:mm:ss.SSS ===");
        System.out.printf("DateUtils (char[] 手写)      : %.1f ns/op%n", dateUtilsNs);
        System.out.printf("SimpleDateFormat (每次new)  : %.1f ns/op%n", sdfNewNs);
        System.out.printf("DateTimeFormatter (复用)    : %.1f ns/op%n", dtfReuseNs);

        assertTrue("DateUtils 应快于每次 new SimpleDateFormat，实际 "
                        + dateUtilsNs + " vs " + sdfNewNs,
                dateUtilsNs < sdfNewNs);
    }

    @Test
    public void parse_isFasterThanAllocatingSimpleDateFormat() {
        String text = "2021-11-12 22:45:32";
        String pattern = DateUtils.DATE_TIME_PATTERN;
        int warmup = 40_000;
        int iters = 100_000;

        for (int i = 0; i < warmup; i++) {
            DateUtils.getDateByGiven(text, pattern);
            try {
                new SimpleDateFormat(pattern).parse(text);
            } catch (ParseException e) {
                throw new IllegalStateException(e);
            }
        }

        double dateUtilsNs = benchFormat(iters, () -> DateUtils.getDateByGiven(text, pattern));
        double sdfNewNs = benchFormat(iters, () -> {
            try {
                return new SimpleDateFormat(pattern).parse(text);
            } catch (ParseException e) {
                throw new IllegalStateException(e);
            }
        });

        System.out.println("=== DateUtils 解析效率对比 yyyy-MM-dd HH:mm:ss ===");
        System.out.printf("DateUtils (定点解析)         : %.1f ns/op%n", dateUtilsNs);
        System.out.printf("SimpleDateFormat (每次new)  : %.1f ns/op%n", sdfNewNs);

        assertTrue("DateUtils 解析应快于每次 new SimpleDateFormat，实际 "
                        + dateUtilsNs + " vs " + sdfNewNs,
                dateUtilsNs < sdfNewNs);
    }

    private static final SimpleDateFormat REUSED_SDF = new SimpleDateFormat(DateUtils.DATE_TIME_PATTERN);
    private static final DateTimeFormatter REUSED_DTF = DateTimeFormatter
            .ofPattern(DateUtils.DATE_TIME_PATTERN)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter REUSED_MILLIS_DTF = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static SimpleDateFormat reusedSdf() {
        return REUSED_SDF;
    }

    private static DateTimeFormatter reusedDtf() {
        return REUSED_DTF;
    }

    private static DateTimeFormatter reusedMillisDtf() {
        return REUSED_MILLIS_DTF;
    }

    private interface Thunk {
        Object run();
    }

    private static double benchFormat(int iters, Thunk fn) {
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            fn.run();
        }
        return (System.nanoTime() - t0) / (double) iters;
    }
}
