package com.alianga.jkit;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 时间戳格式化 4 种方式性能对比：
 *  1. DateTimeFormatter（复用实例）
 *  2. SimpleDateFormat（复用实例；注意 SimpleDateFormat 本身非线程安全，
 *     这里单线程基准测试没问题，多线程场景要配合 ThreadLocal 或加锁，
 *     那部分开销不在这个测试范围内）
 *  3. 方案二：LocalDateTime.ofInstant() + 手写 char[] 拼接（不做任何缓存）
 *  4. 方案三：秒级缓存前缀 + 每次只现算毫秒
 *
 * 用法：java TimestampBench.java   （JDK 11+ 支持直接运行源文件，不用先 javac）
 */
public class TimestampBench {

    static final ZoneId ZONE = ZoneId.systemDefault();

    // ---------- 1. DateTimeFormatter（复用） ----------
    static final DateTimeFormatter DTF = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH)
            .withZone(ZONE);

    static String viaDateTimeFormatter(long epochMilli) {
        return DTF.format(Instant.ofEpochMilli(epochMilli));
    }

    // ---------- 2. SimpleDateFormat（复用） ----------
    static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH);

    static String viaSimpleDateFormat(long epochMilli) {
        return SDF.format(new Date(epochMilli));
    }

    // ---------- 3. 方案二：手写字段拼接（无缓存） ----------
    static String viaManualFields(long epochMilli) {
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZONE);
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

    // ---------- 4. 方案三：秒级缓存 ----------
    // 简单的无锁缓存：跨秒时可能被多线程各自重算一次，结果仍然正确，
    // 只是偶尔重复计算，不影响正确性，是生产日志框架里的常见做法。
    static volatile long cachedSecond = Long.MIN_VALUE;
    static volatile char[] cachedPrefix; // "yyyy-MM-dd HH:mm:ss"，19 个字符

    static String viaSecondCache(long epochMilli) {
        long second = Math.floorDiv(epochMilli, 1000L);
        char[] prefix = cachedPrefix;
        if (second != cachedSecond) {
            prefix = buildPrefix(second);
            cachedPrefix = prefix;
            cachedSecond = second;
        }
        char[] buf = new char[23];
        System.arraycopy(prefix, 0, buf, 0, 19);
        buf[19] = '.';
        int ms = (int) Math.floorMod(epochMilli, 1000L);
        buf[20] = (char) ('0' + ms / 100);
        buf[21] = (char) ('0' + ms / 10 % 10);
        buf[22] = (char) ('0' + ms % 10);
        return new String(buf);
    }

    static char[] buildPrefix(long epochSecond) {
        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), ZONE);
        char[] p = new char[19];
        int y = ldt.getYear();
        p[0] = (char) ('0' + y / 1000 % 10);
        p[1] = (char) ('0' + y / 100 % 10);
        p[2] = (char) ('0' + y / 10 % 10);
        p[3] = (char) ('0' + y % 10);
        p[4] = '-';
        write2(p, 5, ldt.getMonthValue());
        p[7] = '-';
        write2(p, 8, ldt.getDayOfMonth());
        p[10] = ' ';
        write2(p, 11, ldt.getHour());
        p[13] = ':';
        write2(p, 14, ldt.getMinute());
        p[16] = ':';
        write2(p, 17, ldt.getSecond());
        return p;
    }

    static void write2(char[] buf, int off, int v) {
        buf[off] = (char) ('0' + v / 10);
        buf[off + 1] = (char) ('0' + v % 10);
    }

    // ---------- 基准测试驱动：交替跑多轮取平均，抵消顺序偏差和 JIT/GC 噪声 ----------
    interface Fn {
        String apply(long epochMilli);
    }

    static double bench(Fn fn, int iters) {
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) fn.apply(System.currentTimeMillis());
        long t1 = System.nanoTime();
        return (t1 - t0) / (double) iters;
    }

    public static void main(String[] args) {
        int warmup = 500_000;
        int iters = 2_000_000;
        int rounds = 5;

        Map<String, Fn> methods = new LinkedHashMap<>();
        methods.put("1.DateTimeFormatter(复用)", TimestampBench::viaDateTimeFormatter);
        methods.put("2.SimpleDateFormat(复用)", TimestampBench::viaSimpleDateFormat);
        methods.put("3.方案二 手写字段拼接(无缓存)", TimestampBench::viaManualFields);
        methods.put("4.方案三 秒级缓存", TimestampBench::viaSecondCache);
        methods.put("5.DateUtils(char[]/秒级缓存)", epochMilli ->
                DateTimes.format(epochMilli, "yyyy-MM-dd HH:mm:ss.SSS"));

        // 正确性检查
        long now = System.currentTimeMillis();
        System.out.println("=== 正确性检查 ===");
        for (Map.Entry<String, Fn> e : methods.entrySet()) {
            System.out.println(e.getKey() + " : " + e.getValue().apply(now));
        }
        System.out.println();

        // 充分预热，让 JIT 把 4 条路径都编译到位
        for (int i = 0; i < warmup; i++) {
            for (Fn fn : methods.values()) fn.apply(System.currentTimeMillis());
        }

        // 交替多轮测试
        Map<String, Double> totals = new LinkedHashMap<>();
        for (String name : methods.keySet()) totals.put(name, 0.0);

        for (int r = 0; r < rounds; r++) {
            System.out.println("--- round " + r + " ---");
            for (Map.Entry<String, Fn> e : methods.entrySet()) {
                double ns = bench(e.getValue(), iters);
                totals.put(e.getKey(), totals.get(e.getKey()) + ns);
                System.out.printf("%-28s : %.1f ns/op%n", e.getKey(), ns);
            }
        }

        System.out.println("\n=== 平均结果 (" + rounds + " 轮) yyyy-MM-dd HH:mm:ss.SSS ===");
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            System.out.printf("%-28s : %.1f ns/op%n", e.getKey(), e.getValue() / rounds);
        }

        benchDateTimePattern(warmup, iters, rounds);
    }

    /**
     * 再对比一次无毫秒的 {@code yyyy-MM-dd HH:mm:ss}，这是 DateUtils 秒级缓存的主路径。
     */
    static void benchDateTimePattern(int warmup, int iters, int rounds) {
        Map<String, Fn> methods = new LinkedHashMap<String, Fn>();
        methods.put("DateUtils.getDateTimeString", epochMilli ->
                DateUtils.getDateTimeString(new Date(epochMilli)));
        methods.put("DateTimes.formatDateTime", DateTimes::formatDateTime);
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        methods.put("SimpleDateFormat(复用)", epochMilli -> sdf.format(new Date(epochMilli)));
        final DateTimeFormatter dtf = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
                .withZone(ZONE);
        methods.put("DateTimeFormatter(复用)", epochMilli ->
                dtf.format(Instant.ofEpochMilli(epochMilli)));

        System.out.println("\n=== yyyy-MM-dd HH:mm:ss 正确性 ===");
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Fn> e : methods.entrySet()) {
            System.out.println(e.getKey() + " : " + e.getValue().apply(now));
        }

        for (int i = 0; i < warmup; i++) {
            for (Fn fn : methods.values()) {
                fn.apply(System.currentTimeMillis());
            }
        }

        Map<String, Double> totals = new LinkedHashMap<String, Double>();
        for (String name : methods.keySet()) {
            totals.put(name, 0.0);
        }
        System.out.println("\n=== yyyy-MM-dd HH:mm:ss 效率 ===");
        for (int r = 0; r < rounds; r++) {
            System.out.println("--- round " + r + " ---");
            for (Map.Entry<String, Fn> e : methods.entrySet()) {
                double ns = bench(e.getValue(), iters);
                totals.put(e.getKey(), totals.get(e.getKey()) + ns);
                System.out.printf("%-28s : %.1f ns/op%n", e.getKey(), ns);
            }
        }
        System.out.println("\n=== 平均结果 (" + rounds + " 轮) yyyy-MM-dd HH:mm:ss ===");
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            System.out.printf("%-28s : %.1f ns/op%n", e.getKey(), e.getValue() / rounds);
        }
    }
}
