package com.alianga.jkit;

import com.alianga.jkit.convert.ConvertUtils;
import org.junit.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ConvertUtilsTest {
    @Test
    public void testBoolean() {
        assertTrue(ConvertUtils.toBoolean("true"));
        assertTrue(ConvertUtils.toBoolean("YES"));
        assertTrue(ConvertUtils.toBoolean(1, 1, "yes"));
    }

    @Test
    public void testNumber() {
        assertEquals(Integer.valueOf(12), ConvertUtils.toInteger("12.9", 0));
        assertEquals(1, ConvertUtils.toInt("yes"));
        assertEquals(1L, ConvertUtils.toLongValue("true"));
        assertEquals(3.14D, ConvertUtils.toDouble("3.14", 0D), 1e-9);
    }

    @Test
    public void testString() {
        assertEquals("", ConvertUtils.toNoneNullString(null));
        assertEquals("N/A", ConvertUtils.toNoneNullString("NULL", "N/A"));
        assertEquals("N/A", ConvertUtils.toNoneEmptyString("", "N/A"));
        assertEquals("value", ConvertUtils.toNoneEmptyString("value", "N/A"));
    }

    @Test
    public void toDate_shouldConvertDateCalendarAndJavaTimeValues() {
        Date original = new Date(1_650_000_000_123L);
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(1_650_000_000_123L);
        LocalDate localDate = LocalDate.of(2024, 2, 4);
        LocalDateTime localDateTime = LocalDateTime.of(2024, 2, 4, 12, 12, 12);
        Instant instant = Instant.ofEpochMilli(1_650_000_000_123L);
        OffsetDateTime offsetDateTime = OffsetDateTime.of(2024, 2, 4, 12, 12, 12, 0, ZoneOffset.ofHours(8));
        ZonedDateTime zonedDateTime = localDateTime.atZone(ZoneId.systemDefault());

        assertSame(original, ConvertUtils.toDate(original));
        assertEquals(calendar.getTime(), ConvertUtils.toDate(calendar));
        assertEquals(Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant()),
                ConvertUtils.toDate(localDate));
        assertEquals(Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant()),
                ConvertUtils.toDate(localDateTime));
        assertEquals(Date.from(instant), ConvertUtils.toDate(instant));
        assertEquals(Date.from(offsetDateTime.toInstant()), ConvertUtils.toDate(offsetDateTime));
        assertEquals(Date.from(zonedDateTime.toInstant()), ConvertUtils.toDate(zonedDateTime));
        assertNull(ConvertUtils.toDate(null));
        assertEquals(original, ConvertUtils.toDate(null, original));
    }

    @Test
    public void toDate_shouldConvertDateStringsAndTimestamps() {
        assertEquals(dateAt(2021, Calendar.NOVEMBER, 12, 0, 0, 0),
                ConvertUtils.toDate("2021-11-12"));
        assertEquals(dateAt(2021, Calendar.NOVEMBER, 12, 22, 45, 32),
                ConvertUtils.toDate("2021-11-12 22:45:32"));
        assertEquals(new Date(1_638_702_321_000L), ConvertUtils.toDate("1638702321"));
        assertEquals(new Date(1_638_702_321_200L), ConvertUtils.toDate("1638702321200"));
        assertEquals(new Date(1_638_702_321_000L), ConvertUtils.toDate(1638702321L));
        assertEquals(new Date(1_638_702_321_200L), ConvertUtils.toDate(1_638_702_321_200L));
    }

    @Test
    public void toDate_shouldConvertCommonDateFormats() {
        Date day = dateAt(2021, Calendar.NOVEMBER, 12, 0, 0, 0);
        Date dateTime = dateAt(2021, Calendar.NOVEMBER, 12, 22, 45, 32);
        Date dateTimeNoSec = dateAt(2021, Calendar.NOVEMBER, 12, 21, 45, 0);

        assertEquals(day, ConvertUtils.toDate("20211112"));
        assertEquals(day, ConvertUtils.toDate("2021-11-12"));
        assertEquals(day, ConvertUtils.toDate("2021/11/12"));
        assertEquals(day, ConvertUtils.toDate("2021年11月12日"));
        assertEquals(day, ConvertUtils.toDate("2021년11월12일"));
        assertEquals(dateAt(2021, Calendar.NOVEMBER, 12, 21, 45, 32),
                ConvertUtils.toDate("20211112214532"));
        assertEquals(dateTime, ConvertUtils.toDate("20211112 22:45:32"));
        assertEquals(dateTime, ConvertUtils.toDate("2021.11.12 22:45:32"));
        assertEquals(dateAt(2021, Calendar.DECEMBER, 21, 23, 10, 33),
                ConvertUtils.toDate("2021-12-21 23:10:33"));
        assertEquals(dateAt(2021, Calendar.DECEMBER, 4, 0, 0, 0), ConvertUtils.toDate("2021/12/4"));
        assertEquals(dateAt(2021, Calendar.MAY, 31, 0, 0, 0), ConvertUtils.toDate("2021/5/31"));
        assertEquals(dateAt(2021, Calendar.MAY, 3, 0, 0, 0), ConvertUtils.toDate("2021/5/3"));
        assertEquals(dateAt(2021, Calendar.MAY, 31, 1, 25, 0), ConvertUtils.toDate("2021/5/31 1:25"));
        assertEquals(dateAt(2021, Calendar.MAY, 31, 1, 2, 0), ConvertUtils.toDate("2021/5/31 1:2"));
        assertEquals(dateAt(2021, Calendar.MAY, 31, 12, 25, 0), ConvertUtils.toDate("2021/5/31 12:25"));
        assertEquals(dateAt(2021, Calendar.MAY, 31, 12, 5, 0), ConvertUtils.toDate("2021/5/31 12:5"));
        assertEquals(dateTimeNoSec, ConvertUtils.toDate("202111122145"));
        assertEquals(dateAt(2022, Calendar.MARCH, 1, 19, 26, 28),
                ConvertUtils.toDate("2022-03-01T19:26:28"));
        assertEquals(dateAt(2021, Calendar.NOVEMBER, 12, 22, 45, 32, 123),
                ConvertUtils.toDate("2021-11-12 22:45:32.123"));
        assertEquals(dateAt(2021, Calendar.NOVEMBER, 5, 0, 0, 0), ConvertUtils.toDate("5/11/2021"));
        assertEquals(dateAt(2021, Calendar.MAY, 12, 0, 0, 0), ConvertUtils.toDate("12-5-2021"));
    }

    @Test
    public void toDate_shouldConvertIsoOffsetDateTimes() {
        assertEquals(Date.from(OffsetDateTime.parse("2021-11-28T22:33:31+08:00").toInstant()),
                ConvertUtils.toDate("2021-11-28T22:33:31+0800"));
        assertEquals(Date.from(OffsetDateTime.parse("2022-03-01T19:26:28+08:00").toInstant()),
                ConvertUtils.toDate("2022-03-01T19:26:28+08:00"));
        assertEquals(Date.from(Instant.parse("2021-11-28T14:33:31.200Z")),
                ConvertUtils.toDate("2021-11-28T14:33:31.200Z"));
    }

    @Test
    public void toDate_isFasterThanSimpleDateFormatAndDateTimeFormatter() throws ParseException {
        String text = "2021-11-12 22:45:32";
        String pattern = "yyyy-MM-dd HH:mm:ss";
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(pattern);
        int warmup = 40_000;
        int iters = 100_000;

        for (int i = 0; i < warmup; i++) {
            ConvertUtils.toDate(text);
            new SimpleDateFormat(pattern).parse(text);
            dtf.parse(text);
        }

        double toDateNs = bench(iters, new Runnable() {
            @Override
            public void run() {
                ConvertUtils.toDate(text);
            }
        });
        double sdfNewNs = bench(iters, new Runnable() {
            @Override
            public void run() {
                try {
                    new SimpleDateFormat(pattern).parse(text);
                } catch (ParseException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        double dtfNs = bench(iters, new Runnable() {
            @Override
            public void run() {
                LocalDateTime.parse(text, dtf);
            }
        });

        System.out.println("=== ConvertUtils.toDate 效率对比 ===");
        System.out.printf("ConvertUtils.toDate (数字抽取) : %.1f ns/op%n", toDateNs);
        System.out.printf("SimpleDateFormat (每次new)    : %.1f ns/op%n", sdfNewNs);
        System.out.printf("DateTimeFormatter (复用)      : %.1f ns/op%n", dtfNs);

        assertTrue("toDate 应快于每次 new SimpleDateFormat，实际 " + toDateNs + " vs " + sdfNewNs,
                toDateNs < sdfNewNs);
    }

    private static Date dateAt(int year, int month, int day, int hour, int minute, int second) {
        return dateAt(year, month, day, hour, minute, second, 0);
    }

    private static Date dateAt(int year, int month, int day, int hour, int minute, int second, int milli) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(year, month, day, hour, minute, second);
        calendar.set(Calendar.MILLISECOND, milli);
        return calendar.getTime();
    }

    private static double bench(int iters, Runnable fn) {
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            fn.run();
        }
        return (System.nanoTime() - t0) / (double) iters;
    }
}
