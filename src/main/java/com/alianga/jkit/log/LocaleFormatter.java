package com.alianga.jkit.log;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.*;

public class LocaleFormatter extends Formatter {

    private final Locale locale;

    public LocaleFormatter(Locale locale) {
        this.locale = locale;
    }

    @Override
    public String format(LogRecord r) {
        String time = format(r.getMillis());
        String source = r.getSourceClassName() != null
                ? r.getSourceClassName() + " " + r.getSourceMethodName()
                : r.getLoggerName();
        // getName() = WARNING/SEVERE；getLocalizedName() 仍跟 JVM 默认 Locale
        String level = r.getLevel().getName();
        String msg = formatMessage(r);

        String thrown = "";
        if (r.getThrown() != null) {
            thrown = System.lineSeparator() + stackTrace(r.getThrown());
        }
        return String.format(locale, "%s %s%n%s: %s%s%n",
                time, source, level, msg, thrown);
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * 把时间毫秒数格式化为字符串（yyyy-MM-dd HH:mm:ss.SSS）
     *
     * @param epochMilli 时代毫
     * @return {@link String }
     */
    static String format(long epochMilli) {
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
        char[] buf = new char[23]; // yyyy-MM-dd HH:mm:ss.SSS
        int y = ldt.getYear();
        buf[0] = (char) ('0' + y / 1000 % 10);
        buf[1] = (char) ('0' + y / 100 % 10);
        buf[2] = (char) ('0' + y / 10 % 10);
        buf[3] = (char) ('0' + y % 10);
        buf[4] = '-';
        write2(buf, 5, ldt.getMonthValue());
        buf[7] = '-';
        write2(buf, 8, ldt.getDayOfMonth());
        buf[10] = ' ';
        write2(buf, 11, ldt.getHour());
        buf[13] = ':';
        write2(buf, 14, ldt.getMinute());
        buf[16] = ':';
        write2(buf, 17, ldt.getSecond());
        buf[19] = '.';
        int ms = ldt.getNano() / 1_000_000;
        buf[20] = (char) ('0' + ms / 100);
        buf[21] = (char) ('0' + ms / 10 % 10);
        buf[22] = (char) ('0' + ms % 10);
        return new String(buf);
    }

    static void write2(char[] buf, int off, int v) {
        buf[off] = (char) ('0' + v / 10);
        buf[off + 1] = (char) ('0' + v % 10);
    }
    public static void main(String[] args) {
        String date = String.format(Locale.ENGLISH, "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL",
                new java.util.Date(System.currentTimeMillis()));
        System.out.println(date);
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH)
                .withZone(ZoneId.systemDefault());
        System.out.println(dateTimeFormatter.format(java.time.Instant.ofEpochMilli(System.currentTimeMillis())));
        System.out.println(format(System.currentTimeMillis()));
    }
}
