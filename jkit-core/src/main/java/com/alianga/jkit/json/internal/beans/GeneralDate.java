package com.alianga.jkit.json.internal.beans;

import com.alianga.jkit.json.internal.utils.EnvUtils;
import com.alianga.jkit.math.NumberUtils;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.lang.reflect.Field;
import java.util.TimeZone;

/**
 * 日期基础类
 *
 * <p> 1582年历法按4年一润，当年删去了10天(10月5日～10月14日) ，1582-？ 按最新历法
 * <p>
 * Asia/Harbin, Asia/Shanghai, Asia/Chongqing, Asia/Urumqi, Asia/Kashgar：
 * <ul>
 * <li> 1986年至1991年，每年四月的第2个星期日早上2点，到九月的第2个星期日早上2点之间。
 * <li> 1986年5月4日至9月14日（1986年因是实行夏令时的第一年，从5月4日开始到9月14日结束）
 * <li> 1987年4月12日至9月13日，
 * <li> 1988年4月10日至9月11日，
 * <li> 1989年4月16日至9月17日，
 * <li> 1990年4月15日至9月16日，
 * <li> 1991年4月14日至9月15日。
 * <li> 1992年起，夏令时暂停实行
 * </ul>
 * <p>
 * 注：[Asia/*]时区下Calendar消失的时间段（不存在的时间,无法通过设置时间域得到对应的时间点）
 * 以Asia/Shanghai为例，时区文件： %JRE_HOME%/lib/zi/Asia/Shanghai
 * 1900[1900-01-01 08:00:00, 1900-01-01 08:05:42) 5分43秒
 * 1940[1940-06-03 00:00:00, 1940-06-03 00:59:59] 1小时
 * 1941[1940-03-16 00:00:00, 1940-03-16 00:59:59] 1小时
 * 以及夏令时每年开始第一个小时:
 * 1986[1986-05-04 00:00:00, 1986-05-04 00:59:59] 一个小时区间
 * 1987[1987-04-12 00:00:00, 1987-04-12 00:59:59] 一个小时区间
 * ...
 * 1991[1991-04-14 00:00:00, 1991-04-14 00:59:59] 一个小时区间
 * <pre>:
 *
 * TimeZone timeZone = TimeZone.getTimeZone("Asia/Shanghai");
 * TimeZone.setDefault(timeZone);
 *
 * Calendar calendar = Calendar.getInstance();
 * calendar.set(1900, 0, 1, 8, 0, 0);
 * calendar.set(Calendar.MILLISECOND, 0);
 *
 * System.out.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(calendar.getTime())); // 1900-01-01 08:05:43
 * System.out.println(calendar.get(Calendar.HOUR_OF_DAY)); // 8
 * System.out.println(calendar.get(Calendar.MINUTE)); // 5
 * System.out.println(calendar.get(Calendar.SECOND)); // 43
 *
 *
 * </pre>
 *
 * @time 2022/8/11 22:53
 */
public class GeneralDate {
    // 最小时间：公元元年时间（0001-01-01 00:00:00.000）
//    public static final GeneralDate MIN_DATE = GeneralDate.of(1, 1, 1, 0, 0, 0, 0);

    /**
     * 日期字段标识：年
     */
    public static final int YEAR = 1;
    /**
     * 日期字段标识：月（1~12）
     */
    public static final int MONTH = 2;
    /**
     * 日期字段标识：日（当月的第几天）
     */
    public static final int DAY_OF_MONTH = 3;
    /**
     * 日期字段标识：小时（24 小时制）
     */
    public static final int HOUR = 4;
    /**
     * 日期字段标识：分钟
     */
    public static final int MINUTE = 5;
    /**
     * 日期字段标识：秒
     */
    public static final int SECOND = 6;
    /**
     * 日期字段标识：毫秒
     */
    public static final int MILLISECOND = 7;

    /**
     * 年
     */
    protected int year;
    /**
     * 月（1~12）
     */
    protected int month;
    /**
     * 当月的第几天
     */
    protected int dayOfMonth;

    /**
     * 小时（24 小时制，0~23）
     */
    protected int hourOfDay;
    /**
     * 分钟（0~59）
     */
    protected int minute;
    /**
     * 秒（0~59）
     */
    protected int second;
    /**
     * 毫秒（0~999）
     */
    protected int millisecond;

    // 是否闰年
    /**
     * 当前年份是否闰年
     */
    protected boolean leapYear;
    // 当年第多少天
    /**
     * 当前日期是当年的第多少天
     */
    protected int daysOfYear;

    // 距离1970.1.1 - 时区标准毫秒数
    /**
     * 按所在时区的字面时间计算出的、距离 1970.1.1 的毫秒数（未做时差校正）
     */
    protected long standardMills;
    // 距离1970.1.1 - 时区校对后的毫秒数
    /**
     * 经时差校正后的时间戳，即距离 1970.1.1 00:00:00 GMT 的毫秒数，未初始化时为 -1
     */
    protected long timeMills = -1;

    // 地球公转一周年毫秒数(计算24节气)
    // 计算来源： 36524219 * 24 * 36
    /**
     * 地球公转一周年的毫秒数，用于计算 24 节气
     */
    public static final long YEAR_TIMEMILLS = 31556925216L;

    // 公元元年（0001）.1.1 ~ 1970.1.1 相对天数
    // 计算来源：1969 * 365 + 1969 / 4 - 1969 / 100 + 1969 / 400 + (1582 / 100 - 1582 / 400 - 10)
    /**
     * 公元元年（0001）.1.1 至 1970.1.1 的相对天数
     */
    public static final long RELATIVE_DAYS = 719164L;

    // 公元元年（0001）.1.1 ~ 1970.1.1  相对毫秒数
    // 计算来源: RELATIVE_DAYS * 24 * 3600 * 1000
    /**
     * 公元元年（0001）.1.1 至 1970.1.1 的相对毫秒数
     */
    public static final long RELATIVE_MILLS = 62135769600000L;
    /**
     * 公元元年（0001）.1.1 至 1970.1.1 的相对秒数
     */
    public static final long RELATIVE_SECONDS = 62135769600L;

    // 以1970.1.1  周四 作为参考
    /**
     * 参考基准日 1970.1.1 对应的星期序号（周四）
     */
    public static final int RELATIVE_DAY_OF_WEEK = 5;

    // 时钟
    /**
     * 当前日期使用的时区，为 {@code null} 时会回退到默认时区
     */
    protected TimeZone timeZone;
    // 可变时差
    /**
     * 当前生效的时差（毫秒），取自时区的原始偏移量
     */
    protected int currentOffset;

    // 虽然是默认时钟，但默认时钟也可以被修改，这里初始化一个时钟，在没有时钟信息时会使用默认时钟
    private static TimeZone defaultTimeZone;
    // 获取实时的默认时钟
    private static final Field defaultTimeZoneField;
    // 公元元年（0001）.1.1 ~ current 相对天数
    /**
     * 公元元年（0001）.1.1 至当前日期的相对天数
     */
    protected long currentDays;

    private static final int[] DAYS_OF_YEAR_OFFSET = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
    private static final int[] DAYS_OF_LEAP_YEAR_OFFSET = {0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335};

    // 一天(24小时)实际毫秒数
    /**
     * 一天（24 小时）的毫秒数
     */
    protected static final long MILLS_DAY = 86400000; // 24 * 3600 * 1000
    // 平年一年(365天)实际毫秒数:
    /**
     * 平年（365 天）的毫秒数
     */
    protected static final long MILLS_365_DAY = 365 * MILLS_DAY;
    // 闰年一年（366天）毫秒数
    /**
     * 闰年（366 天）的毫秒数
     */
    protected static final long MILLS_366_DAY = 366 * MILLS_DAY;
    /**
     * 1991-09-14 23:59:59 相对于公元元年的秒数，夏令时结束时刻的判断边界
     */
    protected static final long SECONDS_1991_09_14_23_59_59 = 62820658799L;
    /**
     * 1900-01-01 07:59:59 相对于公元元年的秒数，早期时区偏移差异的判断边界
     */
    protected static final long SECONDS_1900_01_01_07_59_59 = 59926809599L;

    /**
     * 1900-01-01 08:05:43（Asia 时区下的时间断点）对应的时间戳
     */
    protected static final long TIME_1900_01_01_08_05_43 = -2208988800000L;
    /**
     * 1991-09-15 00:00:00（夏令时结束）对应的时间戳
     */
    protected static final long TIME_1991_09_15_00_00_00 = 684864000000L;

    /**
     * 0 ~ 2099 年的年份元数据缓存（是否闰年与相对天数）
     */
    protected static final YearMeta[] POSITIVE_YEAR_METAS = new YearMeta[2100];
    /**
     * 年份元数据缓存覆盖的最大相对天数，超出该范围需要实时计算
     */
    protected static final long MAX_CACHE_OFFSET_DAYS;
    /**
     * 平年中「当年第几天」到「月、日」的映射缓存，长度 365
     */
    protected static final MonthDayMeta[] MONTH_DAY_OF_YEAR = new MonthDayMeta[365];
    /**
     * 闰年中「当年第几天」到「月、日」的映射缓存，长度 366
     */
    protected static final MonthDayMeta[] MONTH_DAY_OF_LEAP_YEAR = new MonthDayMeta[366];

    static final int OFFSET_DAYS_DIVISOR = 146097;

    /**
     * 构造指定时区的日期对象，各时间字段保持为 0，需要另行设置时间。
     *
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     */
    public GeneralDate(TimeZone timeZone) {
        ofTimeZone(timeZone);
    }

    /**
     * 使用默认时区构造指定时间戳的日期对象。
     *
     * @param time 时间戳，即距离 1970.1.1 00:00:00 GMT 的毫秒数
     */
    public GeneralDate(long time) {
        this(time, defaultTimeZone);
    }

    /**
     * 构造指定时间戳和时区的日期对象。
     *
     * @param time     时间戳，即距离 1970.1.1 00:00:00 GMT 的毫秒数
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     */
    public GeneralDate(long time, TimeZone timeZone) {
        ofTimeZone(timeZone);
        setTime(time, true);
    }

    /**
     * 构造指定时间戳的日期对象，时区由表达式指定。
     *
     * @param time     时间戳，即距离 1970.1.1 00:00:00 GMT 的毫秒数
     * @param timeZone 时区表达式，支持 {@code GMT+8}、{@code +8:00} 等形式，为 {@code null} 时使用默认时区
     */
    public GeneralDate(long time, String timeZone) {
        ofTimeZone(timeZone == null ? defaultTimeZone : getTimeZoneById(timeZone));
        setTime(time, true);
    }

    GeneralDate(int year, int month, int day, int hour, int minute, int second,
                int millisecond, TimeZone timeZone) {
        ofTimeZone(timeZone);
        this.year = year;
        this.month = month;
        this.dayOfMonth = day;
        this.hourOfDay = hour;
        this.minute = minute;
        this.second = second;
        this.millisecond = millisecond;
    }

    /**
     * 使用默认时区按年月日时分秒毫秒创建日期对象，并同步计算出对应的时间戳。
     *
     * @param year        年
     * @param month       月（1~12，超出范围会按溢出规则换算年份）
     * @param day         当月的第几天
     * @param hour        小时（24 小时制）
     * @param minute      分钟
     * @param second      秒
     * @param millisecond 毫秒
     * @return 新创建并已计算好时间戳的日期对象
     */
    public static GeneralDate of(int year, int month, int day, int hour, int minute, int second,
                                 int millisecond) {
        GeneralDate generalDate = new GeneralDate(year, month, day, hour, minute, second, millisecond, (TimeZone) null);
        generalDate.updateTime();
        return generalDate;
    }

    /**
     * 通过 TimeZone对象设置时差
     * 时间戳不变，重置各个系数
     *
     * @param timeZone 目标时区
     * @return 当前对象，便于链式调用
     */
    public GeneralDate setTimeZone(TimeZone timeZone) {
        this.timeZone = timeZone;
        int rawOffset = timeZone.getRawOffset();
        if (this.currentOffset != rawOffset) {
            this.currentOffset = rawOffset;
            setTime(this.timeMills, true);
        }
        return this;
    }

    /**
     * 通过表达式设置GMT时钟
     *
     * @param offsetExpr +-{hour}:{minute}?
     *                   Z
     * @return 当前对象，便于链式调用
     */
    public GeneralDate setTimeZone(String offsetExpr) {
        return setTimeZone(getTimeZoneById(offsetExpr));
    }

    TimeZone getTimeZoneById(String offsetExpr) {
        if (offsetExpr.startsWith("GMT")) {
            return TimeZone.getTimeZone(offsetExpr);
        }
        return TimeZone.getTimeZone("GMT" + offsetExpr);
    }

    /**
     * 设置当前对象使用的时区，并同步刷新时差；时区为 {@code null} 时使用默认时区。
     *
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     */
    protected void ofTimeZone(TimeZone timeZone) {
        this.timeZone = timeZone;
        if (timeZone == null) {
            this.timeZone = getDefaultTimeZone();
        }
        this.currentOffset = this.timeZone.getRawOffset();
    }

    static class YearMeta {
        final int year;
        final boolean leap;
        final long offsetDays;

        YearMeta(int year, boolean leap, long offsetDays) {
            this.year = year;
            this.leap = leap;
            this.offsetDays = offsetDays;
        }
    }

    static class MonthDayMeta {
        final int month;
        final int day;

        public MonthDayMeta(int month, int day) {
            this.month = month;
            this.day = day;
        }
    }

    static {
        // 计算 1-1-1 00:00:00 ~ 1970-1-1 00:00:00 之间得 relativeMills
        // 1582年历法按4年一润，当年删去了10天 ，1582-？ 按最新历法
        // 范围： 0001~1969（时间戳从1970年开始）
        // RELATIVE_DAYS = 1969 * 365 + 1969 / 4 - 1969 / 100 + 1969 / 400 + (1582 / 100 - 1582 / 400 - 10);
        // RELATIVE_MILLS = RELATIVE_DAYS * 24 * 3600 * 1000;
        Field timeZoneField = null;
        try {
            timeZoneField = TimeZone.class.getDeclaredField("defaultTimeZone");
            if (!UnsafeHelper.setAccessible(timeZoneField)) {
                timeZoneField.setAccessible(true);
            }
        } catch (Throwable throwable) {
            timeZoneField = null;
        }
        defaultTimeZoneField = timeZoneField;
        getDefaultTimeZone();
        for (int year = 0; year < POSITIVE_YEAR_METAS.length; year++) {
            POSITIVE_YEAR_METAS[year] = createYearMeta(year);
        }
        MAX_CACHE_OFFSET_DAYS = POSITIVE_YEAR_METAS[POSITIVE_YEAR_METAS.length - 1].offsetDays;

        int monthOfYear = 1;
        int monthOfLeapYear = 1;
        int daysOfYearOffset = DAYS_OF_YEAR_OFFSET[monthOfYear - 1];
        int daysOfLeapYearOffset = DAYS_OF_LEAP_YEAR_OFFSET[monthOfLeapYear - 1];
        for (int i = 0; i < 366; i++) {
            if (i < 365) {
                int dayOfMonthAtYear = i - daysOfYearOffset + 1;
                // 4位（0-11） + 5位（0-30）
                MONTH_DAY_OF_YEAR[i] = new MonthDayMeta(monthOfYear, dayOfMonthAtYear);
                if (monthOfYear < 12 && i == DAYS_OF_YEAR_OFFSET[monthOfYear] - 1) {
                    daysOfYearOffset = DAYS_OF_YEAR_OFFSET[monthOfYear++];
                }
            }
            int dayOfMonthAtLeapYear = i - daysOfLeapYearOffset + 1;
            MONTH_DAY_OF_LEAP_YEAR[i] = new MonthDayMeta(monthOfLeapYear, dayOfMonthAtLeapYear);
            if (monthOfLeapYear < 12 && i == DAYS_OF_LEAP_YEAR_OFFSET[monthOfLeapYear] - 1) {
                daysOfLeapYearOffset = DAYS_OF_LEAP_YEAR_OFFSET[monthOfLeapYear++];
            }
        }
    }

    /**
     * 计算指定时区下给定年月日时分秒毫秒对应的时间戳。
     *
     * @param year        年
     * @param month       月（1~12，超出范围会按溢出规则换算年份）
     * @param day         当月的第几天
     * @param hour        小时（24 小时制）
     * @param minute      分钟
     * @param second      秒
     * @param millisecond 毫秒
     * @param timeZone    时区，为 {@code null} 时使用默认时区
     * @return 对应的时间戳，即距离 1970.1.1 00:00:00 GMT 的毫秒数
     */
    public static final long getTime(int year, int month, int day, int hour, int minute, int second,
                                     int millisecond, TimeZone timeZone) {
        GeneralDate generalDate = new GeneralDate(year, month, day, hour, minute, second, millisecond, timeZone);
        generalDate.updateTime();
        return generalDate.timeMills;
    }

    /**
     * 计算给定年月日时分秒对应的 GMT 秒数（不含时差校正），已按 1582 年历法删去 10 天做修正。
     *
     * @param year       年
     * @param month      月（1~12，超出范围会按溢出规则换算年份）
     * @param dayOfMonth 当月的第几天
     * @param hourOfDay  小时（24 小时制）
     * @param minute     分钟
     * @param second     秒
     * @return 距离 1970.1.1 00:00:00 的秒数
     */
    public static final long getSeconds(int year, int month, int dayOfMonth, int hourOfDay, int minute, int second) {
        if (month > 12) {
            int increaseYear = (month - 1) / 12;
            year += increaseYear;
            month = month - increaseYear * 12;
        } else if (month < 1) {
            int increaseYear = month / 12 - 1;
            year += increaseYear;
            month = month - increaseYear * 12;
        }
        YearMeta meta = getYearMeta(year);
        boolean isLeapYear = meta.leap;
        long days = meta.offsetDays;
        int offset = isLeapYear ? DAYS_OF_LEAP_YEAR_OFFSET[month - 1] : DAYS_OF_YEAR_OFFSET[month - 1];
        int daysOfYear = offset + dayOfMonth;
        if (year == 1582 && month > 9) {
            if (month == 10) {
                if (dayOfMonth > 14) {
                    daysOfYear -= 10;
                }
            } else {
                daysOfYear -= 10;
            }
        }
        days += daysOfYear;
        return days * 86400 + hourOfDay * 3600 + minute * 60 + second - RELATIVE_SECONDS;
    }

    /**
     * 支持格式： {'yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss'}
     * 年月日必须
     *
     * @param dateStr 日期字符串，长度必须为 10 或 19
     * @return 默认时区下对应的时间戳；格式不符合要求时抛出 {@link UnsupportedOperationException}
     */
    public static final long parseTime(String dateStr) {
        return parseTime(dateStr, null);
    }

    /**
     * 支持格式： {'yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss'}
     * 年月日必须
     *
     * @param dateStr  日期字符串，长度必须为 10 或 19
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     * @return 指定时区下对应的时间戳；格式不符合要求时抛出 {@link UnsupportedOperationException}
     */
    public static final long parseTime(String dateStr, TimeZone timeZone) {
        GeneralDate generalDate = parseGeneralDate(dateStr, timeZone);
        generalDate.updateTime();
        return generalDate.timeMills;
    }

    /**
     * 支持格式： {'yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss'}
     * 年月日必须
     *
     * @param dateStr  日期字符串，长度必须为 10 或 19
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     * @return 解析得到的日期对象（此时尚未计算时间戳）；格式不符合要求时抛出
     *         {@link UnsupportedOperationException}
     */
    public static final GeneralDate parseGeneralDate(String dateStr, TimeZone timeZone) {
        dateStr.getClass();
        int length = dateStr.length();
        int year;
        int month;
        int day;
        int hour = 0;
        int minute = 0;
        int second = 0;
        try {
            if (length == 19) {
                year = NumberUtils.parseInt4(dateStr.charAt(0), dateStr.charAt(1), dateStr.charAt(2),
                        dateStr.charAt(3));
                month = NumberUtils.parseInt2(dateStr.charAt(5), dateStr.charAt(6));
                day = NumberUtils.parseInt2(dateStr.charAt(8), dateStr.charAt(9));
                hour = NumberUtils.parseInt2(dateStr.charAt(11), dateStr.charAt(12));
                minute = NumberUtils.parseInt2(dateStr.charAt(14), dateStr.charAt(15));
                second = NumberUtils.parseInt2(dateStr.charAt(17), dateStr.charAt(18));
            } else if (length == 10) {
                year = NumberUtils.parseInt4(dateStr.charAt(0), dateStr.charAt(1), dateStr.charAt(2),
                        dateStr.charAt(3));
                month = NumberUtils.parseInt2(dateStr.charAt(5), dateStr.charAt(6));
                day = NumberUtils.parseInt2(dateStr.charAt(8), dateStr.charAt(9));
            } else {
                throw new UnsupportedOperationException(
                        " Date Format Error, only supported 'yyyy-MM-dd' or 'yyyy-MM-dd HH:mm:ss'");
            }
            return new GeneralDate(year, month, day, hour, minute, second, 0, timeZone);
        } catch (Throwable throwable) {
            throw new UnsupportedOperationException(
                    "Date Format Error, default parse only supported 'yyyy-MM-dd' or 'yyyy-MM-dd HH:mm:ss'");
        }
    }

    /**
     * 从字符数组中按 19 位标准格式 {@code yyyy?MM?dd?HH:mm:ss} 解析日期（分隔符不做校验）。
     *
     * @param buf      字符数组
     * @param offset   日期起始下标
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     * @return 解析得到的日期对象（毫秒为 0，此时尚未计算时间戳）；格式错误时抛出
     *         {@link UnsupportedOperationException}
     */
    public static final GeneralDate parseGeneralDate_Standard_19(char[] buf, int offset, TimeZone timeZone) {
        int year;
        int month;
        int day;
        int hour = 0;
        int minute = 0;
        int second = 0;
        try {
            year = NumberUtils.parseInt4(buf[offset], buf[offset + 1], buf[offset + 2], buf[offset + 3]);
            month = NumberUtils.parseInt2(buf[offset + 5], buf[offset + 6]);
            day = NumberUtils.parseInt2(buf[offset + 8], buf[offset + 9]);
            hour = NumberUtils.parseInt2(buf[offset + 11], buf[offset + 12]);
            minute = NumberUtils.parseInt2(buf[offset + 14], buf[offset + 15]);
            second = NumberUtils.parseInt2(buf[offset + 17], buf[offset + 18]);
            return new GeneralDate(year, month, day, hour, minute, second, 0, timeZone);
        } catch (Throwable throwable) {
            throw new UnsupportedOperationException(
                    "Date Format Error, parseGeneralDate_Standard_19 only supported 'yyyy?MM?dd?HH:mm:ss'");
        }
    }

    /**
     * 从字节数组中按 19 位标准格式 {@code yyyy?MM?dd?HH:mm:ss} 解析日期（分隔符不做校验）。
     *
     * @param buf      字节数组
     * @param offset   日期起始下标
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     * @return 解析得到的日期对象（毫秒为 0，此时尚未计算时间戳）；格式错误时抛出
     *         {@link UnsupportedOperationException}
     */
    public static final GeneralDate parseGeneralDate_Standard_19(byte[] buf, int offset, TimeZone timeZone) {
        int year;
        int month;
        int day;
        int hour = 0;
        int minute = 0;
        int second = 0;
        try {
            year = NumberUtils.parseInt4(buf[offset], buf[offset + 1], buf[offset + 2], buf[offset + 3]);
            month = NumberUtils.parseInt2(buf[offset + 5], buf[offset + 6]);
            day = NumberUtils.parseInt2(buf[offset + 8], buf[offset + 9]);
            hour = NumberUtils.parseInt2(buf[offset + 11], buf[offset + 12]);
            minute = NumberUtils.parseInt2(buf[offset + 14], buf[offset + 15]);
            second = NumberUtils.parseInt2(buf[offset + 17], buf[offset + 18]);
            return new GeneralDate(year, month, day, hour, minute, second, 0, timeZone);
        } catch (Throwable throwable) {
            throw new UnsupportedOperationException(
                    "Date Format Error, parseGeneralDate_Standard_19 only supported 'yyyy?MM?dd?HH:mm:ss'");
        }
    }

    /**
     * 从字符数组中按 10 位标准格式 {@code yyyy?MM?dd} 解析日期（分隔符不做校验）。
     *
     * @param buf      字符数组
     * @param offset   日期起始下标
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     * @return 解析得到的日期对象（时分秒毫秒均为 0，此时尚未计算时间戳）；格式错误时抛出
     *         {@link UnsupportedOperationException}
     */
    public static final GeneralDate parseGeneralDate_Standard_10(char[] buf, int offset, TimeZone timeZone) {
        int year;
        int month;
        int day;
        int hour = 0;
        int minute = 0;
        int second = 0;
        try {
            year = NumberUtils.parseInt4(buf[offset], buf[offset + 1], buf[offset + 2], buf[offset + 3]);
            month = NumberUtils.parseInt2(buf[offset + 5], buf[offset + 6]);
            day = NumberUtils.parseInt2(buf[offset + 8], buf[offset + 9]);
            return new GeneralDate(year, month, day, hour, minute, second, 0, timeZone);
        } catch (Throwable throwable) {
            throw new UnsupportedOperationException(
                    "Date Format Error, parseGeneralDate_Standard_10 only supported 'yyyy?MM?dd'");
        }
    }

    /**
     * 获取实时的默认时区：优先通过反射读取 {@code TimeZone.defaultTimeZone} 字段，
     * 反射不可用或读取失败时回退到 {@link TimeZone#getDefault()}，并缓存结果。
     *
     * @return 当前的默认时区
     */
    public static TimeZone getDefaultTimeZone() {
        if (defaultTimeZoneField != null) {
            try {
                TimeZone defaultTimezone = (TimeZone) defaultTimeZoneField.get(null);
                if (defaultTimezone != null) {
                    return defaultTimeZone = defaultTimezone;
                }
            } catch (IllegalAccessException exception) {
            }
        }
        return defaultTimeZone = TimeZone.getDefault();
    }

    // 溢出处理不必考虑固定量值如日（24h）时（60m）分（60s）秒（1000ms）毫秒（只要给定了值就可直接换算为固定毫秒数）</p>
    // todo 是否存在因为过量溢出导致年份变化（是否闰年）带来的数据误差？
    private void overflow() {
        if (month > 12) {
            int increaseYear = (month - 1) / 12;
            year += increaseYear;
            month = month - increaseYear * 12;
        } else if (month < 1) {
            int increaseYear = month / 12 - 1;
            year += increaseYear;
            month = month - increaseYear * 12;
        }
    }

    private static boolean isLeapYear(int year) {
        boolean remainder4 = (year & 3) == 0;
        return year > 1582 ? remainder4 && (year % 100 != 0 || year % 400 == 0) : remainder4;
    }

    private static long getOffsetDays(long year) {
        if (year > 1582) {
            return (year - 1) * 365 + (year - 1) / 4 - (year - 1) / 100 + (year - 1) / 400 + 2;
        }
        return (year - 1) * 365 + (year - 1) / 4;
    }

    /**
     * 根据当前的年月日时分秒毫秒字段重新计算时间戳，同时刷新是否闰年、当年第几天、
     * 相对天数等派生字段；月份越界时会先做溢出换算。
     */
    protected void updateTime() {
        // 溢出处理
        overflow();
        YearMeta meta = getYearMeta(year);
        boolean isLeapYear = meta.leap;
//        // 是否闰年
//        boolean isLeapYear = isLeapYear(year);
//        if (year == 3200) {
//            isLeapYear = false;
//        }

        // 0001.1.1~{year}.1.1的天数
        long days = meta.offsetDays;
        int offset = isLeapYear ? DAYS_OF_LEAP_YEAR_OFFSET[month - 1] : DAYS_OF_YEAR_OFFSET[month - 1];
        int daysOfYear = offset + dayOfMonth;

        // compute days
//        if (year > 1582) {
//            days = (year - 1) * 365 + (year - 1) / 4 - (year - 1) / 100 + (year - 1) / 400 + 2;
//        } else {
//            days = (year - 1) * 365 + (year - 1) / 4;
//        }
        // 1582年只有355天 此年10月5日～10月14日不存在
        if (year == 1582 && month > 9) {
            if (month == 10) {
                if (dayOfMonth > 14) {
                    daysOfYear -= 10;
                } else if (dayOfMonth > 4 && dayOfMonth <= 14) {
                    // 不存在的10天，按java日历的标准 +10
                    dayOfMonth += 10;
                }
            } else {
                daysOfYear -= 10;
            }
        }
        days += daysOfYear;
        long seconds = days * 86400 + hourOfDay * 3600 + minute * 60 + second;
        // 转化ms
        this.timeMills = seconds * 1000 + millisecond - this.currentOffset - RELATIVE_MILLS;

        // 当前时区（raw）下标准毫秒数（距离1970.1.1)
        this.standardMills = this.timeMills;

        // Asia/* 时区下区间offset调整
        if (seconds <= SECONDS_1991_09_14_23_59_59 && seconds > SECONDS_1900_01_01_07_59_59) {
            // 时区调整（Asia/* 夏令时调整）
            int actualOffset = timeZone.getOffset(this.timeMills);
            this.timeMills += this.currentOffset - actualOffset;
        }

        // 是否闰年
        this.leapYear = isLeapYear;
        // 一年第多少天
        this.daysOfYear = daysOfYear;
        // 公元元年（0001）.1.1 ~ current 相对天数
        this.currentDays = days;
    }

    void setTime(long timeMills, boolean reset) {
        this.timeMills = timeMills;
        if (timeMills >= TIME_1900_01_01_08_05_43 && timeMills < TIME_1991_09_15_00_00_00) {
            // actual mills for compute
            int zoneOffset = timeZone.getOffset(timeMills);
            timeMills -= this.currentOffset - zoneOffset;
            this.standardMills = timeMills;
        }
        long offset = RELATIVE_MILLS + currentOffset;
        timeMills += offset;
        long seconds;
        int millisecond;
        int offsetYear = 0;
        if (timeMills > 0) {
            seconds = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(timeMills, 0x4189374bc6a7ef9eL) >>
                    8; // timeMills / 1000;
            millisecond = (int) (timeMills - seconds * 1000);
        } else {
            if (this.timeMills > Long.MAX_VALUE - offset) {
                // overflowing the maximum value of long
                int rem = (int) (this.timeMills % 1000);
                seconds = this.timeMills / 1000 + (offset + rem) / 1000;
                millisecond = (int) (rem + (offset % 1000)) % 1000;
            } else {
                // 如果是公元元年之前先计算年份，然后将timeMills补齐到正数在进行计算
                // 这里只做了初略的转正处理，确保time段(时分秒)解析正确
                do {
                    boolean leap = ((offsetYear + 1) & 3) == 0;
                    timeMills += leap ? MILLS_366_DAY : MILLS_365_DAY;
                    timeMills += MILLS_DAY;
                    offsetYear++;
                } while (timeMills < 0);
                seconds = timeMills / 1000;
                millisecond = (int) (timeMills - seconds * 1000);
            }
        }

        long days = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(seconds, 0x611722833944a55cL) >>
                15; // seconds / 86400L;
        int secondsOfDay = (int) (seconds - days * 86400L);
        int hour = (int) EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(secondsOfDay,
                0x123456789abce0L); // secondsOfDay / 3600;
        secondsOfDay = secondsOfDay - hour * 3600;
        int minute = (int) EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(secondsOfDay,
                0x444444444444445L); // secondsOfDay / 60;
        int second = secondsOfDay - minute * 60;

        //        int year = (int) ((days + 1) * 100000 / 36524219) + 1;
        int year = (int) EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(days * 400, 0x72d60d7991f1L) +
                1; // (int) ((days * 400) / OFFSET_DAYS_DIVISOR + 1);
        YearMeta targetMeta = getYearMeta(year);
        long offsetDays = targetMeta.offsetDays;
        if (days <= offsetDays) {
            targetMeta = getYearMeta(--year);
            offsetDays = targetMeta.offsetDays;
        }
        int daysOfYear = (int) (days - offsetDays);
        boolean isLeapYear = targetMeta.leap;
        MonthDayMeta monthDayMeta;
        try {
            int dayIndex = daysOfYear - 1;
            if (isLeapYear) {
                if (daysOfYear > MONTH_DAY_OF_LEAP_YEAR.length) {
                    daysOfYear -= 366;
                    dayIndex -= 366;
                    ++year;
                    isLeapYear = false;
                }
                monthDayMeta = MONTH_DAY_OF_LEAP_YEAR[dayIndex];
            } else {
                if (daysOfYear > MONTH_DAY_OF_YEAR.length) {
                    daysOfYear -= 365;
                    dayIndex -= 365;
                    ++year;
                    isLeapYear = isLeapYear(year);
                }
                monthDayMeta = MONTH_DAY_OF_YEAR[dayIndex];
            }
            // monthDayMeta = isLeapYear ? Month_Day_Of_LeapYear[daysOfYear - 1] : Month_Day_Of_Year[daysOfYear - 1];
        } catch (Throwable throwable) {
            throw new IllegalArgumentException("error mills for " + this.timeMills);
        }
        int month = monthDayMeta.month;
        int day = monthDayMeta.day;

        // 1582年共355天 不存在的10天(1582.10.05~1582.10.14 对应278～287) 288～355
        if (year == 1582 && daysOfYear >= 278) {
            //  daysOfYear 必然 <= 355 ，12月通过算法得到day最大21
            day += 10;
            // 10～11月溢出处理
            if (month == 10 && day > 31) {
                month += 1;
                day = day - 31;
            } else if (month == 11 && day > 30) {
                month += 1;
                day = day - 30;
            }
        }

        this.year = year - offsetYear;
        this.month = month;
        this.dayOfMonth = day;
        this.hourOfDay = hour;
        this.minute = minute;
        this.second = second;
        this.millisecond = millisecond;

        this.daysOfYear = daysOfYear;
        this.leapYear = isLeapYear;
        this.currentDays = days;
    }

    /**
     * 获取默认时区的原始时差。
     *
     * @return 默认时区相对 GMT 的原始偏移毫秒数
     */
    public static final long getDefaultOffset() {
        return getDefaultTimeZone().getRawOffset();
    }

    private static YearMeta createYearMeta(int year) {
        boolean leap = isLeapYear(year);
        // starting from 0001-01-01, days have already represented January 1st of that year
        long offsetDays = getOffsetDays(year) - 1;
        return new YearMeta(year, leap, offsetDays);
    }

    static YearMeta getYearMeta(int year) {
        if (year > -1 && year < POSITIVE_YEAR_METAS.length) {
            return POSITIVE_YEAR_METAS[year];
        }
        return createYearMeta(year);
    }

    /**
     * 获取年份。
     *
     * @return 当前的年份
     */
    public final int getYear() {
        return year;
    }

    /**
     * 获取月份。
     *
     * @return 当前的月份，取值 1~12
     */
    public final int getMonth() {
        return month;
    }

    /**
     * 获取当月的第几天。
     *
     * @return 当前的日，取值 1~31
     */
    public final int getDay() {
        return dayOfMonth;
    }

    /**
     * 获取小时。
     *
     * @return 当前的小时，24 小时制，取值 0~23
     */
    public final int getHourOfDay() {
        return hourOfDay;
    }

    /**
     * 获取分钟。
     *
     * @return 当前的分钟，取值 0~59
     */
    public final int getMinute() {
        return minute;
    }

    /**
     * 获取秒。
     *
     * @return 当前的秒，取值 0~59
     */
    public final int getSecond() {
        return second;
    }

    /**
     * 获取毫秒。
     *
     * @return 当前的毫秒，取值 0~999
     */
    public final int getMillisecond() {
        return millisecond;
    }

    /**
     * 获取时间戳。
     *
     * @return 经时差校正后的时间戳（距离 1970.1.1 00:00:00 GMT 的毫秒数），未初始化时为 -1
     */
    public long getTime() {
        return timeMills;
    }

    /**
     * 获取未做时差校正的标准毫秒数。
     *
     * @return 按所在时区字面时间计算出的、距离 1970.1.1 的毫秒数
     */
    public long getStandardTime() {
        return standardMills;
    }

    /**
     * 获取当前生效的时差。
     *
     * @return 当前时区相对 GMT 的偏移毫秒数
     */
    public int getCurrentOffset() {
        return currentOffset;
    }

    /**
     * 是否上午
     *
     * @return 小时数小于 12 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isAm() {
        return this.hourOfDay < 12;
    }
}
