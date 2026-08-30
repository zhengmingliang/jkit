/**
 * Created by 郑明亮 on 2022/1/29 17:54.
 */
package com.alianga.jkit.common.entity;

import com.alianga.jkit.DateUtils;

import java.util.Calendar;

/**
 * <p> 自定义日期增强类</p>
 *
 * @author 郑明亮
 * @time 2022/1/29 17:54
 * @since 1.3.4
 */
public class Date extends java.util.Date {
    /**
     * 与当前时间戳同步的日历对象，用于拆分年月日时分秒
     */
    Calendar calendar;

    /**
     * 使用当前系统时间创建日期对象
     */
    public Date() {
        this(System.currentTimeMillis());
    }

    /**
     * 使用指定时间戳创建日期对象
     *
     * @param timestamp 距离 1970-01-01 00:00:00 GMT 的毫秒数
     */
    public Date(long timestamp) {
        super(timestamp);
        calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
    }

    /**
     * 转换为 指定模式的时间字符串
     *
     * @param pattern 日期格式化模式，如 {@code yyyy-MM-dd HH:mm:ss}
     * @return 按 {@code pattern} 格式化后的时间字符串
     */
    public String format(String pattern) {
        return DateUtils.getStringByPattern(this, pattern);
    }

    /**
     * @return 转换为 yyyy-MM-dd HH:mm:ss 格式的时间字符串
     */
    public String format() {
        return DateUtils.getStringByPattern(this, DateUtils.DATE_TIME_PATTERN);
    }

    /**
     * 转换为 yyyy-MM-dd格式的日期字符串
     *
     * @return 形如 {@code 2022-01-29} 的日期字符串
     */
    public String toDateString() {
        StringBuilder builder = new StringBuilder(10);
        builder.append(calendar.get(Calendar.YEAR)).append('-');
        sprintf0d(builder, calendar.get(Calendar.MONTH) + 1, 2).append('-');
        sprintf0d(builder, calendar.get(Calendar.DAY_OF_MONTH), 2);
        return builder.toString();
    }

    /**
     * 转换为 yyyy-MM-dd HH:mm:ss 格式的时间字符串
     *
     * @return 形如 {@code 2022-01-29 17:54:00} 的时间字符串
     */
    public String toDateTimeString() {
        StringBuilder builder = new StringBuilder(19);
        builder.append(calendar.get(Calendar.YEAR)).append('-');
        sprintf0d(builder, calendar.get(Calendar.MONTH) + 1, 2).append('-');
        sprintf0d(builder, calendar.get(Calendar.DAY_OF_MONTH), 2).append(' ');
        sprintf0d(builder, calendar.get(Calendar.HOUR_OF_DAY), 2).append(':');
        sprintf0d(builder, calendar.get(Calendar.MINUTE), 2).append(':');
        sprintf0d(builder, calendar.get(Calendar.SECOND), 2);
        return builder.toString();
    }

    private static final StringBuilder sprintf0d(StringBuilder var0, int var1, int var2) {
        long var3 = var1;
        if (var3 < 0L) {
            var0.append('-');
            var3 = -var3;
            --var2;
        }

        int var5 = 10;

        int var6;
        for (var6 = 2; var6 < var2; ++var6) {
            var5 *= 10;
        }

        for (var6 = 1; var6 < var2 && var3 < (long) var5; ++var6) {
            var0.append('0');
            var5 /= 10;
        }

        var0.append(var3);
        return var0;
    }

}
