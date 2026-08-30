package com.alianga.jkit.json.internal.beans;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

/**
 * {@link DateFormatter} 与 {@link LunarDate} 的回归测试。
 *
 * <p>修复前 {@code DateFormatter.of(dateToken, timeToken, concat, millis)} 忽略 millis 参数，
 * 固定输出毫秒；{@code LunarDate} 的七参构造器把 timeZone 写死成 {@code null}，传入的时区被丢弃。</p>
 */
public class DateBeansRegressionTest {

    @Test
    public void dateFormatterOf_honorsMillisFlag() {
        assertEquals("2024-08-29 10:20:30",
                DateFormatter.of('-', ':', ' ', false).format(2024, 8, 29, 10, 20, 30));
        assertEquals("2024-08-29 10:20:30.000",
                DateFormatter.of('-', ':', ' ', true).format(2024, 8, 29, 10, 20, 30));
        // 三参重载本来就不输出毫秒，行为不变
        assertEquals("2024-08-29 10:20:30",
                DateFormatter.of('-', ':', ' ').format(2024, 8, 29, 10, 20, 30));
    }

    @Test
    public void lunarDate_honorsTimeZone() {
        TimeZone utc = TimeZone.getTimeZone("GMT+00:00");
        TimeZone tokyo = TimeZone.getTimeZone("GMT+09:00");

        assertEquals(expectedMills(utc), new LunarDate(2024, 8, 29, 10, 20, 30, utc).getTime());
        assertEquals(expectedMills(tokyo), new LunarDate(2024, 8, 29, 10, 20, 30, tokyo).getTime());
        // 两个时区的同一挂钟时间应相差 9 小时
        assertEquals(9 * 3600 * 1000L,
                new LunarDate(2024, 8, 29, 10, 20, 30, utc).getTime()
                        - new LunarDate(2024, 8, 29, 10, 20, 30, tokyo).getTime());
    }

    private long expectedMills(TimeZone timeZone) {
        Calendar calendar = new GregorianCalendar(timeZone);
        calendar.clear();
        calendar.set(2024, Calendar.AUGUST, 29, 10, 20, 30);
        return calendar.getTimeInMillis();
    }
}
