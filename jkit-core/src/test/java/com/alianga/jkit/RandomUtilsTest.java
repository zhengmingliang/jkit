package com.alianga.jkit;

import org.junit.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RandomUtils} 正确性与回归测试，覆盖生日、校验位、编码、随机串等优化路径。
 */
public class RandomUtilsTest {

    private static final DateTimeFormatter BASIC_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    @Test
    public void randomBirth_shouldReturnValidYyyyMMddInAgeWindow() {
        int minAge = 20;
        int maxAge = 50;
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 200; i++) {
            String birth = RandomUtils.randomBirth(minAge, maxAge);
            assertEquals(8, birth.length());
            LocalDate date = LocalDate.parse(birth, BASIC_DATE);
            long days = ChronoUnit.DAYS.between(date, today);
            assertTrue(birth + " days=" + days, days >= 365L * minAge);
            assertTrue(birth + " days=" + days, days < 365L * maxAge);
        }
    }

    @Test
    public void randomBirth_sameAgeDoesNotThrowAndStaysOnWindow() {
        String birth = RandomUtils.randomBirth(30, 30);
        LocalDate date = LocalDate.parse(birth, BASIC_DATE);
        long days = ChronoUnit.DAYS.between(date, LocalDate.now());
        assertEquals(365L * 30, days);
    }

    @Test
    public void randomBirth_swapsInvertedAgeRange() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 50; i++) {
            String birth = RandomUtils.randomBirth(50, 20);
            LocalDate date = LocalDate.parse(birth, BASIC_DATE);
            long days = ChronoUnit.DAYS.between(date, today);
            assertTrue(days >= 365L * 20);
            assertTrue(days < 365L * 50);
        }
    }

    @Test
    public void getIdCardCheckNum_matchesGb11643AndIdCardUtils() {
        String[] bodies = {
                "11010119900307001",
                "11010119900307002",
                "11010519491231002",
                "32031177070600",
        };
        for (String body : bodies) {
            if (body.length() < 17) {
                assertEquals(' ', RandomUtils.getIdCardCheckNum(body));
                continue;
            }
            char expected = IdCardUtils.calcTrailingNumber(body.toCharArray());
            assertEquals(body, expected, RandomUtils.getIdCardCheckNum(body));
        }
        assertEquals(' ', RandomUtils.getIdCardCheckNum(null));
        assertEquals(' ', RandomUtils.getIdCardCheckNum("123"));

        String body = "11010119900307001";
        String known = body + RandomUtils.getIdCardCheckNum(body);
        assertTrue(known, IdCardUtils.isValid(known));
        assertEquals(IdCardUtils.calcTrailingNumber(body.toCharArray()),
                RandomUtils.getIdCardCheckNum(known));
    }

    @Test
    public void getRandomIdCard_isEighteenDigitsWithValidChecksum() {
        for (int i = 0; i < 50; i++) {
            String id = RandomUtils.getRandomIdCard();
            assertEquals(18, id.length());
            assertEquals(id.charAt(17), RandomUtils.getIdCardCheckNum(id.substring(0, 17)));
            LocalDate.parse(id.substring(6, 14), BASIC_DATE);
        }
        for (int i = 0; i < 20; i++) {
            String id = RandomUtils.getRandomIdCard(25, 35);
            assertTrue(id, IdCardUtils.isValid(id));
            int age = IdCardUtils.parse(id).getAge();
            assertTrue(id + " age=" + age, age >= 24 && age <= 36);
        }
    }

    @Test
    public void randomOne_includesLastElement() {
        String[] items = {"a", "b", "c"};
        boolean sawLast = false;
        boolean sawFirst = false;
        for (int i = 0; i < 8000; i++) {
            String one = RandomUtils.randomOne(items);
            if ("a".equals(one)) {
                sawFirst = true;
            }
            if ("c".equals(one)) {
                sawLast = true;
            }
            if (sawFirst && sawLast) {
                break;
            }
        }
        assertTrue("应能取到首元素", sawFirst);
        assertTrue("应能取到末元素（修复前 nextInt(length-1) 永远取不到）", sawLast);

        char[] chars = {'X', 'Y'};
        boolean sawY = false;
        for (int i = 0; i < 4000; i++) {
            if ("Y".equals(RandomUtils.randomOne(chars))) {
                sawY = true;
                break;
            }
        }
        assertTrue(sawY);
    }

    @Test
    public void getNum_isInclusiveAndHandlesEqualOrSwappedBounds() {
        assertEquals(7, RandomUtils.getNum(7, 7));
        for (int i = 0; i < 200; i++) {
            int n = RandomUtils.getNum(3, 5);
            assertTrue(n >= 3 && n <= 5);
            int swapped = RandomUtils.getNum(5, 3);
            assertTrue(swapped >= 3 && swapped <= 5);
        }
        boolean saw3 = false;
        boolean saw5 = false;
        for (int i = 0; i < 3000; i++) {
            int n = RandomUtils.getNum(3, 5);
            if (n == 3) {
                saw3 = true;
            }
            if (n == 5) {
                saw5 = true;
            }
            if (saw3 && saw5) {
                break;
            }
        }
        assertTrue(saw3 && saw5);
    }

    @Test
    public void getDoubleNum_staysInHalfOpenRange() {
        for (int i = 0; i < 200; i++) {
            double v = RandomUtils.getDoubleNum(1, 2);
            assertTrue(v >= 1.0 && v < 2.0);
            double swapped = RandomUtils.getDoubleNum(2.5, 1.5);
            assertTrue(swapped >= 1.5 && swapped < 2.5);
        }
        assertEquals(4.0, RandomUtils.getDoubleNum(4.0, 4.0), 0.0);
        double cut = Double.parseDouble(RandomUtils.getDoubleNum(1.0, 2.0, 2));
        assertTrue(cut >= 1.0 && cut < 2.0);
    }

    @Test
    public void encodingDecoding_roundTripAndRejectsInvalidInput() {
        long[] values = {1L, 61L, 62L, 63L, 12345L, 999_999_999L};
        for (long value : values) {
            String encoded = RandomUtils.encoding(value);
            assertFalse(encoded.isEmpty());
            assertEquals(value, RandomUtils.decoding(encoded));
        }
        try {
            RandomUtils.encoding(0L);
            fail("encoding(0) should throw");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("greater than 0"));
        }
        try {
            RandomUtils.decoding(" ");
            fail("empty decoding should throw");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("empty"));
        }
        try {
            RandomUtils.decoding("abc!");
            fail("invalid char should throw");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("invalid"));
        }
    }

    @Test
    public void getUUID_is32HexWithoutHyphen() {
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < 40; i++) {
            String id = RandomUtils.getUUID();
            assertEquals(32, id.length());
            assertTrue(id, id.matches("[0-9a-f]{32}"));
            assertFalse(id.contains("-"));
            assertTrue(seen.add(id));
        }
    }

    @Test
    public void getRandomCodeAndNumCode_matchAlphabetAndLength() {
        assertEquals("", RandomUtils.getRandomCode(0));
        assertEquals("", RandomUtils.getRandomNumCode(-1));
        String code = RandomUtils.getRandomCode(16);
        assertEquals(16, code.length());
        assertTrue(code, code.matches("[0-9A-Za-z]{16}"));
        String nums = RandomUtils.getRandomNumCode(8);
        assertEquals(8, nums.length());
        assertTrue(nums, nums.matches("[0-9]{8}"));
    }

    @Test
    public void getRandomTelEmailIpNameAndPerson_haveExpectedShape() {
        for (int i = 0; i < 20; i++) {
            String tel = RandomUtils.getRandomTel();
            assertEquals(11, tel.length());
            assertTrue(tel, tel.matches("1[3-9]\\d{9}"));

            String email = RandomUtils.getRandomEmail(6, 9);
            assertTrue(email, email.contains("@"));
            assertTrue(email.indexOf('@') >= 6);

            String ip = RandomUtils.getRandomIp();
            String[] parts = ip.split("\\.");
            assertEquals(ip, 4, parts.length);
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                assertTrue(ip, octet >= 0 && octet <= 255);
            }

            String name = RandomUtils.getChineseName();
            assertTrue(name, name.length() >= 2 && name.length() <= 3);

            String company = RandomUtils.getRandomCompany(2, 4);
            assertTrue(company.startsWith("北京"));
            assertTrue(company.endsWith("科技有限公司"));
        }

        Map<?, ?> person = RandomUtils.getRandomPerson();
        assertNotNull(person.get("name"));
        assertNotNull(person.get("sex"));
        assertNotNull(person.get("idCard"));
        assertTrue(IdCardUtils.isValid(String.valueOf(person.get("idCard"))));
        assertNotEquals(person.get("telephone"), person.get("mailbox"));
    }

    @Test
    public void getRandomUserAgent_returnsBuiltinSample() {
        boolean sawApp = false;
        boolean sawPc = false;
        for (int i = 0; i < 40; i++) {
            String ua = RandomUtils.getRandomUserAgent();
            assertNotNull(ua);
            assertFalse(ua.isEmpty());
            if (ua.contains("Android") || ua.contains("IEMobile") || ua.contains("Windows Phone")) {
                sawApp = true;
            }
            if (ua.contains("Windows NT") || ua.contains("Firefox") || ua.contains("Edge")) {
                sawPc = true;
            }
        }
        assertTrue(sawApp || sawPc);
    }
}
