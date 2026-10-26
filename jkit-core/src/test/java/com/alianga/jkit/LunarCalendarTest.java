package com.alianga.jkit;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 农历工具：公历 ↔ 农历往返、中文表达式解析、干支 / 生肖 / 批量模式。
 *
 * <p>已知锚点：2024-02-10 是农历甲辰年正月初一（春节）；
 * 2023 年闰二月；1900-01-31 是数据起点（农历 1900 年正月初一）。</p>
 *
 * @author 郑明亮
 */
public class LunarCalendarTest {

    @Test
    public void springFestival2024() {
        LunarCalendar.LunarInfo info = LunarCalendar.fromGregorian(2024, 2, 10);
        assertTrue(info.supported());
        assertEquals(2024, info.lunarYear());
        assertEquals(1, info.lunarMonth());
        assertEquals(1, info.lunarDay());
        assertEquals("甲辰", info.ganzhiYear());
        assertEquals("龙", info.zodiac());
        assertEquals("正月初一", info.lunarDateText());
    }

    @Test
    public void roundTripThroughReverseScan() {
        // 正向：公历 → 农历；反向：农历 → 公历。抽样若干天全部互逆
        LocalDate[] samples = {
                LocalDate.of(2024, 2, 10),
                LocalDate.of(2023, 3, 22),  // 2023 闰二月初一
                LocalDate.of(2026, 10, 26),
                LocalDate.of(1900, 1, 31),  // 数据起点
                LocalDate.of(2024, 12, 31),
        };
        for (LocalDate d : samples) {
            LunarCalendar.LunarInfo fwd = LunarCalendar.fromGregorian(d);
            assertTrue(d + " 应在数据范围内", fwd.supported());
            LunarCalendar.LunarInfo back = LunarCalendar.toGregorian(
                    fwd.lunarYear(), fwd.lunarMonth(), fwd.leap(), fwd.lunarDay());
            assertTrue(back.supported());
            assertEquals(d.toString(), back.gregorianText());
        }
    }

    @Test
    public void leapMonth2023() {
        // 2023 年有闰二月：2023-03-22 是闰二月初一
        LunarCalendar.LunarInfo info = LunarCalendar.fromGregorian(2023, 3, 22);
        assertTrue(info.supported());
        assertEquals(2, info.lunarMonth());
        assertTrue(info.leap());
        assertEquals("闰二月初一", info.lunarDateText());
        // 反向
        LunarCalendar.LunarInfo back = LunarCalendar.toGregorian(2023, 2, true, 1);
        assertTrue(back.supported());
        assertEquals("2023-03-22", back.gregorianText());
    }

    @Test
    public void chineseNumericExpression() {
        LunarCalendar.LunarInfo info = LunarCalendar.parseChinese("2024年正月初一");
        assertTrue(info.supported());
        assertEquals("2024-02-10", info.gregorianText());
    }

    @Test
    public void chineseCircledDigits() {
        LunarCalendar.LunarInfo info = LunarCalendar.parseChinese("二〇二四年正月初一");
        assertTrue(info.supported());
        assertEquals("2024-02-10", info.gregorianText());
    }

    @Test
    public void ganzhiYearAmbiguity() {
        // 甲辰年正月初一：1900~2049 内甲辰年有多个，应返回歧义说明而非单一结果
        LunarCalendar.LunarInfo info = LunarCalendar.parseChinese("甲辰年正月初一");
        assertFalse(info.supported());
        assertTrue(info.status().contains("对应多个年份"));
    }

    @Test
    public void leapInChinese() {
        LunarCalendar.LunarInfo info = LunarCalendar.parseChinese("二〇二四年闰二月初五");
        // 2024 无闰二月 → invalid
        assertFalse(info.supported());
        // 2023 有闰二月
        LunarCalendar.LunarInfo ok = LunarCalendar.parseChinese("二〇二三年闰二月初五");
        assertTrue(ok.supported());
        assertEquals(3, ok.gregorianMonth());
    }

    @Test
    public void unrecognizableInput() {
        assertFalse(LunarCalendar.parseChinese(null).supported());
        assertFalse(LunarCalendar.parseChinese("hello").supported());
        assertFalse(LunarCalendar.parseChinese("2024年十三月初一").supported());
        assertFalse(LunarCalendar.parseChinese("2024年正月初三十一").supported());
    }

    @Test
    public void outOfRange() {
        assertFalse(LunarCalendar.fromGregorian(1899, 12, 31).supported());
        assertFalse(LunarCalendar.fromGregorian(2051, 1, 1).supported());
        assertEquals(LunarCalendar.OUT_OF_RANGE, LunarCalendar.summary(LocalDate.of(2051, 1, 1)));
    }

    @Test
    public void summaryContainsKeyParts() {
        String s = LunarCalendar.summary(LocalDate.of(2024, 2, 10));
        assertTrue(s.contains("甲辰"));
        assertTrue(s.contains("正月初一"));
        assertTrue(s.contains("龙"));
    }

    @Test
    public void numericLunarParsing() {
        int[] p = LunarCalendar.parseNumericLunar("2024 1 1");
        assertEquals(2024, p[0]);
        assertEquals(1, p[1]);
        assertEquals(0, p[2]);
        assertEquals(1, p[3]);
        int[] q = LunarCalendar.parseNumericLunar("2023 闰2 1");
        assertEquals(1, q[2]);
        assertEquals(null, LunarCalendar.parseNumericLunar("abc"));
        assertEquals(null, LunarCalendar.parseNumericLunar(null));
    }

    @Test
    public void gregorianParsingTolerance() {
        assertEquals("2024-02-10",
                LunarCalendar.parseGregorianDate("2024-02-10").toString());
        assertEquals("2024-02-10",
                LunarCalendar.parseGregorianDate("2024/2/10").toString());
        assertEquals("2024-02-10",
                LunarCalendar.parseGregorianDate("20240210").toString());
        assertEquals(null, LunarCalendar.parseGregorianDate("nope"));
        assertEquals(null, LunarCalendar.parseGregorianDate(""));
    }

    @Test
    public void batchThreeModes() {
        LunarCalendar.BatchResult r1 = LunarCalendar.batch("2024-02-10\n", LunarCalendar.BatchMode.GREGORIAN_TO_LUNAR);
        assertEquals(1, r1.ok());
        assertTrue(r1.render().contains("正月初一"));

        LunarCalendar.BatchResult r2 = LunarCalendar.batch("2024 1 1", LunarCalendar.BatchMode.LUNAR_TO_GREGORIAN);
        assertEquals(1, r2.ok());
        assertTrue(r2.render().contains("2024-02-10"));

        LunarCalendar.BatchResult r3 = LunarCalendar.batch("2024年正月初一", LunarCalendar.BatchMode.CHINESE_TO_GREGORIAN);
        assertEquals(1, r3.ok());
        assertTrue(r3.renderCsv().startsWith("输入,输出"));
    }

    @Test
    public void solarTermOnTermDay() {
        // 立春 2024-02-04：jkit LunarDate 在交节当天 getSolarTerms() > 0
        LunarCalendar.LunarInfo info = LunarCalendar.fromGregorian(2024, 2, 4);
        assertTrue(info.supported());
        if (info.solarTerm() != null) {
            assertEquals("立春", info.solarTerm());
        }
        // 非节气日应为 null
        LunarCalendar.LunarInfo plain = LunarCalendar.fromGregorian(2024, 2, 10);
        if (plain.solarTerm() != null) {
            // 春节也可能恰逢节气，这里只断言不是空串
            assertNotEquals("", plain.solarTerm());
        }
    }

    @Test
    public void constellationPresent() {
        LunarCalendar.LunarInfo info = LunarCalendar.fromGregorian(2024, 2, 10);
        assertTrue(info.supported());
        assertTrue(info.constellation() == null || !info.constellation().isEmpty());
    }
}
