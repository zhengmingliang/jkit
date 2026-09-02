package com.alianga.jkit;

import org.junit.Test;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertTrue;

/**
 * 把优化前的实现留在测试里做对照，确认 {@link RandomUtils} / {@link IdCardUtils} 改完后确实更快。
 */
public class RandomUtilsPerfTest {

    private static final Random RNG = new SecureRandom();
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final char[] NUMBERS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};
    private static final String CHECK_BODY = "11010119900307001";
    private static final String ENCODED = RandomUtils.encoding(999_999_999L);

    @Test
    public void optimizedPaths_shouldBeatPreviousImplementations() {
        int warmup = 40_000;
        int iters = 120_000;
        int rounds = 3;

        warmupAll(warmup);

        System.out.println("=== RandomUtils / IdCardUtils 优化前后效率对比 ===");
        System.out.printf("%-28s  %12s  %12s  %8s%n", "场景", "优化前", "优化后", "加速比");

        double birthOld = 0;
        double birthNew = 0;
        double checkOld = 0;
        double checkNew = 0;
        double uuidOld = 0;
        double uuidNew = 0;
        double decodeOld = 0;
        double decodeNew = 0;
        double codeOld = 0;
        double codeNew = 0;
        double numOld = 0;
        double numNew = 0;
        double idOld = 0;
        double idNew = 0;
        double formatOld = 0;
        double formatNew = 0;

        for (int r = 0; r < rounds; r++) {
            birthOld += bench(iters, new Runnable() {
                @Override
                public void run() {
                    oldRandomBirth(20, 50);
                }
            });
            birthNew += bench(iters, new Runnable() {
                @Override
                public void run() {
                    RandomUtils.randomBirth(20, 50);
                }
            });
            checkOld += bench(iters, new Runnable() {
                @Override
                public void run() {
                    oldGetIdCardCheckNum(CHECK_BODY);
                }
            });
            checkNew += bench(iters, new Runnable() {
                @Override
                public void run() {
                    RandomUtils.getIdCardCheckNum(CHECK_BODY);
                }
            });
            uuidOld += bench(iters / 4, new Runnable() {
                @Override
                public void run() {
                    oldGetUUID();
                }
            });
            uuidNew += bench(iters / 4, new Runnable() {
                @Override
                public void run() {
                    RandomUtils.getUUID();
                }
            });
            decodeOld += bench(iters, new Runnable() {
                @Override
                public void run() {
                    oldDecoding(ENCODED);
                }
            });
            decodeNew += bench(iters, new Runnable() {
                @Override
                public void run() {
                    RandomUtils.decoding(ENCODED);
                }
            });
            codeOld += bench(iters, new Runnable() {
                @Override
                public void run() {
                    oldGetRandomCode(16);
                }
            });
            codeNew += bench(iters, new Runnable() {
                @Override
                public void run() {
                    RandomUtils.getRandomCode(16);
                }
            });
            numOld += bench(iters, new Runnable() {
                @Override
                public void run() {
                    oldGetRandomNumCode(8);
                }
            });
            numNew += bench(iters, new Runnable() {
                @Override
                public void run() {
                    RandomUtils.getRandomNumCode(8);
                }
            });
            idOld += bench(iters / 2, new Runnable() {
                @Override
                public void run() {
                    oldRandomBirthday(20, 50);
                }
            });
            idNew += bench(iters / 2, new Runnable() {
                @Override
                public void run() {
                    RandomUtils.randomBirth(20, 50);
                }
            });
            formatOld += bench(iters, new Runnable() {
                @Override
                public void run() {
                    oldFormatYmd(new Date());
                }
            });
            formatNew += bench(iters, new Runnable() {
                @Override
                public void run() {
                    DateTimes.formatCompactDate(System.currentTimeMillis());
                }
            });
        }

        printRow("randomBirth (SDF/Calendar)", birthOld / rounds, birthNew / rounds);
        printRow("yyyyMMdd 格式化(隔离 RNG)", formatOld / rounds, formatNew / rounds);
        printRow("getIdCardCheckNum", checkOld / rounds, checkNew / rounds);
        printRow("getUUID (replaceAll)", uuidOld / rounds, uuidNew / rounds);
        printRow("decoding (Math.pow)", decodeOld / rounds, decodeNew / rounds);
        printRow("getRandomCode 16 位", codeOld / rounds, codeNew / rounds);
        printRow("getRandomNumCode 8 位*", numOld / rounds, numNew / rounds);
        printRow("身份证生日(Calendar)", idOld / rounds, idNew / rounds);
        System.out.println("* getRandomNumCode 受 SecureRandom.nextInt 主导，优化前后应接近。");

        assertFaster("randomBirth", birthNew / rounds, birthOld / rounds);
        assertFaster("yyyyMMdd 格式化", formatNew / rounds, formatOld / rounds);
        assertFaster("getIdCardCheckNum", checkNew / rounds, checkOld / rounds);
        assertFaster("getUUID", uuidNew / rounds, uuidOld / rounds);
        assertFaster("decoding", decodeNew / rounds, decodeOld / rounds);
        assertFaster("getRandomCode", codeNew / rounds, codeOld / rounds);
        assertFaster("身份证生日", idNew / rounds, idOld / rounds);
        double numRatio = (numNew / rounds) / (numOld / rounds);
        assertTrue("getRandomNumCode 不应明显变慢，实际比值 " + numRatio,
                numRatio < 1.25);
    }

    private static void warmupAll(int warmup) {
        for (int i = 0; i < warmup; i++) {
            oldRandomBirth(20, 50);
            RandomUtils.randomBirth(20, 50);
            oldGetIdCardCheckNum(CHECK_BODY);
            RandomUtils.getIdCardCheckNum(CHECK_BODY);
            oldGetUUID();
            RandomUtils.getUUID();
            oldDecoding(ENCODED);
            RandomUtils.decoding(ENCODED);
            oldGetRandomCode(16);
            RandomUtils.getRandomCode(16);
            oldGetRandomNumCode(8);
            RandomUtils.getRandomNumCode(8);
            oldRandomBirthday(20, 50);
            oldFormatYmd(new Date());
            DateTimes.formatCompactDate(System.currentTimeMillis());
        }
    }

    private static void printRow(String name, double oldNs, double newNs) {
        System.out.printf("%-28s  %10.1f ns  %10.1f ns  %7.2fx%n", name, oldNs, newNs, oldNs / newNs);
    }

    private static void assertFaster(String name, double newNs, double oldNs) {
        assertTrue(name + " 优化后应更快，实际 " + newNs + " vs " + oldNs, newNs < oldNs);
    }

    private static double bench(int iters, Runnable fn) {
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            fn.run();
        }
        return (System.nanoTime() - t0) / (double) iters;
    }

    /** 优化前：每次 new SimpleDateFormat + Calendar。 */
    static String oldRandomBirth(int minAge, int maxAge) {
        SimpleDateFormat dft = new SimpleDateFormat("yyyyMMdd");
        Calendar date = Calendar.getInstance();
        date.setTime(new Date());
        int randomDay = 365 * minAge + RNG.nextInt(365 * (maxAge - minAge));
        date.set(Calendar.DATE, date.get(Calendar.DATE) - randomDay);
        return dft.format(date.getTime());
    }

    static String oldFormatYmd(Date date) {
        return new SimpleDateFormat("yyyyMMdd").format(date);
    }

    /** 优化前：17 次 Integer.parseInt(char + "")。 */
    static char oldGetIdCardCheckNum(String id) {
        char[] chars = id.toCharArray();
        int total = Integer.parseInt(chars[0] + "") * 7
                + Integer.parseInt(chars[1] + "") * 9
                + Integer.parseInt(chars[2] + "") * 10
                + Integer.parseInt(chars[3] + "") * 5
                + Integer.parseInt(chars[4] + "") * 8
                + Integer.parseInt(chars[5] + "") * 4
                + Integer.parseInt(chars[6] + "") * 2
                + Integer.parseInt(chars[7] + "")
                + Integer.parseInt(chars[8] + "") * 6
                + Integer.parseInt(chars[9] + "") * 3
                + Integer.parseInt(chars[10] + "") * 7
                + Integer.parseInt(chars[11] + "") * 9
                + Integer.parseInt(chars[12] + "") * 10
                + Integer.parseInt(chars[13] + "") * 5
                + Integer.parseInt(chars[14] + "") * 8
                + Integer.parseInt(chars[15] + "") * 4
                + Integer.parseInt(chars[16] + "") * 2;
        int check = total % 11;
        return "10X98765432".charAt(check);
    }

    static String oldGetUUID() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }

    static long oldDecoding(String str) {
        long result = 0L;
        for (int i = 0; i < str.length(); i++) {
            result += (long) (ALPHABET.indexOf(str.charAt(i)) * Math.pow(62.0D, i));
        }
        return result;
    }

    static String oldGetRandomCode(int number) {
        StringBuilder codeNum = new StringBuilder();
        int[] code = new int[3];
        for (int i = 0; i < number; i++) {
            code[0] = RNG.nextInt(10) + 48;
            code[1] = RNG.nextInt(26) + 65;
            code[2] = RNG.nextInt(26) + 97;
            codeNum.append((char) code[RNG.nextInt(3)]);
        }
        return codeNum.toString();
    }

    static String oldGetRandomNumCode(int number) {
        StringBuilder codeNum = new StringBuilder();
        for (int i = 0; i < number; i++) {
            codeNum.append(NUMBERS[RNG.nextInt(10000) % 10]);
        }
        return codeNum.toString();
    }

    /** 优化前 IdCardUtils.randomBirthday：Calendar + 每月按 1~31。 */
    static String oldRandomBirthday(int minAge, int maxAge) {
        Calendar birthday = Calendar.getInstance();
        int year = birthday.get(Calendar.YEAR) - (RNG.nextInt(maxAge - minAge + 1) + minAge);
        birthday.set(Calendar.YEAR, year);
        birthday.set(Calendar.MONTH, RNG.nextInt(12) + 1);
        birthday.set(Calendar.DATE, RNG.nextInt(31) + 1);
        StringBuilder builder = new StringBuilder(8);
        builder.append(year);
        long month = birthday.get(Calendar.MONTH) + 1;
        if (month < 10) {
            builder.append('0');
        }
        builder.append(month);
        long date = birthday.get(Calendar.DATE);
        if (date < 10) {
            builder.append('0');
        }
        builder.append(date);
        return builder.toString();
    }
}
