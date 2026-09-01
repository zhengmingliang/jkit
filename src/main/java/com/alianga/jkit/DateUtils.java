package com.alianga.jkit;

import com.alianga.jkit.convert.ConvertUtils;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;

/**
 * @time 2017年2月1日 下午6:35:24
 * 日期工具类，部分方法来自金融项目
 * <br>
 */
public class DateUtils {
    /**
     * 1 分钟
     */
    public static final int ONE_SECOND = 1000;
    /**
     * 1 分钟
     */
    public static final int ONE_MINUTE = 60 * ONE_SECOND;
    /**
     * 1小时
     */
    public static final int ONE_HOUR = 60 * ONE_MINUTE;
    /**
     * 1天
     */
    public static final long ONE_DAY = 24L * ONE_HOUR;
    /**
     * 1个月（按30天算）
     */
    public static final long ONE_MONTH = 30 * ONE_DAY;
    /**
     * 1年（按365天算）
     */
    public static final long ONE_YEAR = 365 * ONE_DAY;

    /**
     * 中文时间间隔单位模板，依次为年、月、天、小时、分钟、秒、毫秒
     */
    public static final String[] ZH_CN_TIME_PATTERN = {"年", "月", "天", "小时", "分钟", "秒", "毫秒"};
    /**
     * 英文时间间隔单位模板，依次为年、月、天、小时、分钟、秒、毫秒
     */
    public static final String[] EN_US_TIME_PATTERN = {"year", "month", "day", "hour", "minute", "s", "ms"};
    /**
     * 日期时间模式
     */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    /**
     * 日期模式
     */
    public static final String DATE_PATTERN = "yyyy-MM-dd";
    /**
     * utc时间模式
     */
    private static final String UTC_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    private DateUtils() {
        throw new UnsupportedOperationException("不能对我进行实例化哦");
    }

    /**
     * 将当前日期转换为int类型返回;
     *
     * @param pattern 日期格式如yyyyMMdd
     * @return 返回数字，如20170201
     */
    public static int getIntNowDate(String pattern) {
        if (DateTimes.COMPACT_DATE.equals(pattern)) {
            LocalDate today = LocalDate.now();
            return today.getYear() * 10000 + today.getMonthValue() * 100 + today.getDayOfMonth();
        }
        String nowDateString = getNowDateStrByPattern(pattern);
        if (nowDateString != null) {
            return Integer.parseInt(nowDateString);
        }
        return -1;
    }

    /**
     * @param datePattern 日期格式
     * @return getFormatNowDate
         * @time 2017年2月1日 下午8:13:59
     * 当前时间转换成特定形式Date
     * <br>
     * <p>
     * 因为有时在存入到数据库中的date类型带有微秒值，在再次从数据库中读取为String类型时，仍会带有微秒值，
     * 所以为保险起见，经格式化后，再保存就不会出现微秒值
     * <br>
     */
    public static Date getFormatNowDate(String datePattern) {
        return getDateByPattern(new Date(), datePattern);
    }

    /**
     * 当前时间转换成特定形式Date
     *
     * @param datePattern 日期格式
     * @return getNowDateStrByPattern
     */
    public static String getNowDateStrByPattern(String datePattern) {
        return DateTimes.format(System.currentTimeMillis(), datePattern);
    }

    /**
     * 获取当前年
     *
     * @return getCurrentYear
     */
    public static Integer getCurrentYear() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR);
    }

    /**
     * 获取当前天，当月的当天
     *
     * @param date 待解析的日期
     * @return getCurrentDay
     */
    public static Integer getCurrentDay(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        return c.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * 获取当前小时,24时制
     *
     * @param date 待解析的日期
     * @return Date
     */
    public static Integer getCurrentHour(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    /**
     * 得到下一个日期
     *
     * @param date 日期
     * @param step 日期位移，位移单位为天
     * @return 位移后的Date日期
     */
    public static Date getNextDate(Date date, int step) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.add(Calendar.DATE, step);
        return c.getTime();
    }

    /**
     * 得到下一个日期 ,默认是传入日期的下一天
     *
     * @param date 日期
     * @return 位移后的Date日期
     */
    public static Date getNextDate(Date date) {
        return getNextDate(date, 1);
    }

    /**
     * 获取下一天的日期（明天）
     *
     * @return 位移后的Date日期
     */
    public static Date getNextDay() {
        return getNextDate(new Date(), 1);
    }

    /**
     * 根据日期和格式得到Date形式日期
     *
     * @param date 日期
     * @param pattern 日期格式
     * @return Date
     */
    public static Date getDateByPattern(Date date, String pattern) {
        return DateTimes.truncate(date, pattern);
    }

    /**
     * @param utcTime utc时间字符串
     * @param localTimePatten 要转换成的本地时间格式
     * @return 被转换后的日期字符串
     * @throws ParseException 解析异常
         * @email zhengmingliang911@gmail.com
     * @time 2017年2月16日 下午4:26:32
     * @description <p>UTC时间转换为本地时间，默认的UTC的格式为yyyy-MM-dd'T'HH:mm:ss.SSS'Z' </P>
     */
    public static String utc2Local(String utcTime, String localTimePatten) throws ParseException {
        String defaultPartern = UTC_TIME_PATTERN;
        return utc2Local(utcTime, defaultPartern, localTimePatten);
    }

    /**
     * @param utcTime utc时间字符串
     * @param utcTimePatten UTC时间符合的pattern
     * @param localTimePatten 要转换成的本地时间格式
     * @return 被转换后的日期字符串
     * @throws ParseException 解析异常
         * @email zhengmingliang911@gmail.com
     * @time 2017年2月16日 下午2:51:57
     * @description UTC时间转换为本地时间
     */
    public static String utc2Local(String utcTime, String utcTimePatten,
                                   String localTimePatten) throws ParseException {
        return DateTimes.utcToLocal(utcTime, utcTimePatten, localTimePatten);
    }

    /**
     * @param utcTime UTC时间字符
     * @return Date类型时间
         * @email zhengmingliang911@gmail.com
     * @time 2017年2月16日 下午6:37:17
     * @description <p>UTC时间字符串转换为Date类型 时间</P>
     */
    public static Date utc2LocalDate(String utcTime) {
        return utc2LocalDate(utcTime, UTC_TIME_PATTERN);
    }

    /**
     * @param utcTime UTC时间字符
     * @param utcTimePatten UTC时间格式
     * @return Date
         * @email zhengmingliang911@gmail.com
     * @time 2017年2月16日 下午6:37:53
     * @description <p> UTC时间字符串转换为Date类型 时间 </P>
     */
    public static Date utc2LocalDate(String utcTime, String utcTimePatten) {
        return DateTimes.parseUtc(utcTime, utcTimePatten);
    }

    /**
     * 根据日期和格式得到String形式日期
     *
     * @param date 日期
     * @param pattern 日期格式
     * @return 得到指定String形式日期
     */
    public static String getStringByPattern(Date date, String pattern) {
        return DateTimes.format(date.getTime(), pattern);
    }

    /**
     * 根据日期和格式得到String形式日期
     *
     * @param date 日期
     * @return 得到指定String形式日期
     */
    public static String getDateTimeString(Date date) {
        return DateTimes.formatDateTime(date.getTime());
    }

    /**
     * 根据日期和格式得到String形式日期
     *
     * @param date 日期
     * @return 得到指定String形式日期
     */
    public static String getDateString(Date date) {
        return DateTimes.formatDate(date.getTime());
    }

    /**
     * 根据给定的字符串日期，和形式，转化成Date
     *
     * @param date 日期
     * @param pattern 日期格式
     * @return 解析后的日期；解析失败时返回 {@code null}
     */
    public static Date getDateByGiven(String date, String pattern) {
        return DateTimes.parse(date, pattern);
    }

    /**
     * 自动识别常见日期字符串并解析为 {@link Date}。
     *
     * <p>支持 10/13 位时间戳，紧凑数字（{@code yyyyMMdd}、{@code yyyyMMddHHmmss} 等），
     * 常见分隔日期（{@code -}/{@code /}/{@code .}、中文、韩文），ISO-8601（含 {@code T}、
     * {@code Z}、{@code +0800}、{@code +08:00}），以及日/月在前的写法。</p>
     *
     * @param date 日期字符串
     * @return 解析得到的日期；无法识别时返回 {@code null}
     * @since 2.0.1
     */
    public static Date parse(String date) {
        if (date == null) {
            return null;
        }
        return DateTimes.parseFlexible(date);
    }

    /**
     * 将 10 位秒级或 13 位毫秒级时间戳转换为 {@link Date}。
     *
     * @param epoch 10 位秒或其它位数的毫秒时间戳
     * @return 对应的日期
     * @since 2.0.1
     */
    public static Date fromEpochNumber(long epoch) {
        return DateTimes.fromEpochNumber(epoch);
    }

    /**
     * 将 {@link TemporalAccessor} 转换为 {@link Date}。
     *
     * @param temporal 时间对象
     * @return 转换后的日期；无法提取日期时间字段时返回 {@code null}
     * @since 2.0.1
     */
    public static Date fromTemporal(TemporalAccessor temporal) {
        if (temporal == null) {
            return null;
        }
        return DateTimes.fromTemporal(temporal);
    }

    /**
     * 计算两个日期相差多少天
     *
     * @param startday 开始日期
     * @param endday 结束日期
     * @return getTimeGapsInDays
     */
    public static int getTimeGapsInDays(Date startday, Date endday) {
        if (startday.after(endday)) {
            Date cal = startday;
            startday = endday;
            endday = cal;
        }
        long sl = startday.getTime();
        long el = endday.getTime();
        long ei = el - sl;
        return (int) (ei / (1000 * 60 * 60 * 24));
    }

    /**
     * @param startday 开始日期
     * @param endday 结束日期
     * @return getTimeGapsInMilliseconds
         * @time 2017年2月1日 下午7:25:35
     * 获取两个时间相差的毫秒数值
     * <br>
     */
    public static long getTimeGapsInMilliseconds(Date startday, Date endday) {
        if (startday.after(endday)) {
            Date cal = startday;
            startday = endday;
            endday = cal;
        }
        long sl = startday.getTime();
        long el = endday.getTime();
        long ei = el - sl;
        return ei;
    }

    /**
     * @param startDay 开始日期
     * @param endDay 结束日期
     * @return getTimeGapsInMilliseconds
         * @time 2022年2月19日 10:38:40
     * 获取两个时间相差的秒数值
     * <br>
     * @since 1.3.6
     */
    public static long getTimeGapsInSeconds(Date startDay, Date endDay) {
        long milliseconds = getTimeGapsInMilliseconds(startDay, endDay);
        return milliseconds / ONE_SECOND;
    }

    /**
     * @param startDay 开始日期
     * @param endDay 结束日期
     * @return 间隔分钟数
         * @time 2022年2月19日 10:39:00
     * 获取两个时间相差的分钟数值
     * <br>
     * @since 1.3.6
     */
    public static long getTimeGapsInMinutes(Date startDay, Date endDay) {
        long milliseconds = getTimeGapsInMilliseconds(startDay, endDay);
        return milliseconds / ONE_MINUTE;
    }

    /**
     * @param startDay 开始日期
     * @param endDay 结束日期
     * @return 间隔小时数
         * @time 2022年2月19日 10:39:46
     * 获取两个时间相差的小时值
     * <br>
     * @since 1.3.6
     */
    public static long getTimeGapsInHours(Date startDay, Date endDay) {
        long milliseconds = getTimeGapsInMilliseconds(startDay, endDay);
        return milliseconds / ONE_HOUR;
    }

    /**
     * @param start 开始日期
     * @param end 结束日期
     * @return getTimeGapsInMonth
         * @time 2017年2月1日 下午8:18:31
     * 获得两个日期之前相差的月份
     * <br>
     */
    public static int getTimeGapsInMonth(Date start, Date end) {
        if (start.after(end)) {
            Date t = start;
            start = end;
            end = t;
        }
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTime(start);
        Calendar endCalendar = Calendar.getInstance();
        endCalendar.setTime(end);
        Calendar temp = Calendar.getInstance();
        temp.setTime(end);
        temp.add(Calendar.DATE, 1);

        int year = endCalendar.get(Calendar.YEAR)
                - startCalendar.get(Calendar.YEAR);
        int month = endCalendar.get(Calendar.MONTH)
                - startCalendar.get(Calendar.MONTH);

        if ((startCalendar.get(Calendar.DATE) == 1)
                && (temp.get(Calendar.DATE) == 1)) {
            return year * 12 + month + 1;
        } else if ((startCalendar.get(Calendar.DATE) != 1)
                && (temp.get(Calendar.DATE) == 1)) {
            return year * 12 + month;
        } else if ((startCalendar.get(Calendar.DATE) == 1)
                && (temp.get(Calendar.DATE) != 1)) {
            return year * 12 + month;
        } else {
            return (year * 12 + month - 1) < 0 ? 0 : (year * 12 + month);
        }
    }

    /**
     * 获得其他月份的日期
     *
     * @param date 当前日期
     * @param month 要跳过的月份数
     * @return month个月之后的date日期
     */
    public static Date getNextDateByMonth(Date date, int month) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.add(Calendar.MONTH, month);
        return c.getTime();
    }

    /**
     * 根据给出的日期获取本年的第一天的日期
     *
     * @param date 基准日期
     * @return getBeginOfYear
     */
    public static Date getBeginOfYear(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.MONTH, 0);
        c.set(Calendar.DATE, 1);
        return c.getTime();
    }

    /**
     * 根据给出的日期获取本年的最后一天的日期
     *
     * @param date 日期
     * @return getEndOfYear
     */
    public static Date getEndOfYear(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.MONTH, 11);
        c.set(Calendar.DATE, 31);
        return c.getTime();
    }

    /**
     * 获取上个月的第一天日期
     *
     * @param date 日期
     * @return getFirstDayOfLastMonth
     */
    public static Date getFirstDayOfLastMonth(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.add(Calendar.MONTH, -1);
        c.set(Calendar.DATE, 1);
        return c.getTime();
    }

    /**
     * 获取上个月的最后一天日期
     *
     * @param date 日期
     * @return getLastDayOfLastMonth
     */
    public static Date getLastDayOfLastMonth(Date date) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.set(Calendar.DATE, 1);
        c.add(Calendar.DATE, -1);
        return c.getTime();
    }

    /**
     * 生成日期格式为： yyyy-MM-dd HH:mm:ss
     *
     * @return getNowDateTime
     */
    public static String getNowDateTime() {
        return DateTimes.formatDateTime(System.currentTimeMillis());
    }

    /**
     * isLeapYear:[判断是否为闰年].
     *
     * @param year 当前年
     * @return ture of false
         */
    public static boolean isLeapYear(int year) {
        boolean flag = false;
        int y1 = year % 100;
        if (y1 == 0) {
            if (year % 400 == 0) {
                flag = true;
            }
        } else {
            if (year % 4 == 0) {
                flag = true;
            }
        }
        return flag;

    }

    /**
     * @param month 1~12
     * @return 根据月份，返回这个月有多少天
     */
    public static int getDaysOfMonths(int month) {
        int day = 30;
        switch (month) {
            case 1:
                day = 31;
                break;
            case 2:
                day = 28;
                break;
            case 3:
                day = 31;
                break;
            case 4:
                day = 30;
                break;
            case 5:
                day = 31;
                break;
            case 6:
                day = 30;
                break;
            case 7:
                day = 31;
                break;
            case 8:
                day = 31;
                break;
            case 9:
                day = 30;
                break;
            case 10:
                day = 31;
                break;
            case 11:
                day = 30;
                break;
            case 12:
                day = 31;
                break;
            default:
                break;
        }

        return day;
    }

    /**
     * 根据传入的month 和day拼接成一个日期字符串 ;如：传入getMonthsAndDays(1,1) 返回0101
     *
     * @param month 月份
     * @param day 日期
     * @return getMonthsAndDays
         */
    public static String getMonthsAndDays(int month, int day) {
        String monthString = Integer.toString(month);
        String dayString = Integer.toString(day);
        int monlen = monthString.length();
        int daylen = dayString.length();
        if (monlen == 1) {
            monthString = 0 + monthString;
        }
        if (daylen == 1) {
            dayString = 0 + dayString;
        }

        return monthString + dayString;
    }

    /**
     * 默认请传入null 获得当前的系统时间，默认格式为 yyyy-MM-dd HH:mm:ss（24小时制） 你也许需要 yyyy-MM-dd
     * hh:mm:ss (12小时制) yyyy年MM月dd日 HH:mm:ss yyyy年MM月dd日 HH时mm分ss秒
     *
     * @param formateString 日期格式串，为 {@code null} 时使用 {@link #DATE_TIME_PATTERN}
     * @return String 指定格式的当前时间
     */
    public static String getSystemTime(String formateString) {
        if (formateString == null) {
            return getNowDateTime();
        }
        return DateTimes.format(System.currentTimeMillis(), formateString);
    }

    /**
     * 将毫秒时间间隔转换为 xx年xx月xx天xx小时xx分钟xx秒
     *
     * @param timeMillisGap 毫秒时间间隔
     * @return 格式化后的时间间隔字符串
     */
    public static String pastTimes(long timeMillisGap) {
        return pastTimes(timeMillisGap, ZH_CN_TIME_PATTERN);
    }

    /**
     * 将毫秒时间间隔转换为 xx年xx月xx天xx小时xx分钟xx秒
     *
     * @param timeMillisGap 毫秒时间间隔
     * @param patterns 年月日时分秒格式，{@link #ZH_CN_TIME_PATTERN} 或 {@link #EN_US_TIME_PATTERN}
     * @return See the method result described above.
     */
    public static String pastTimes(long timeMillisGap, String[] patterns) {
        patterns = ConvertUtils.toNoneNullObject(patterns, ZH_CN_TIME_PATTERN);
        patterns = patterns.length != 7 ? ZH_CN_TIME_PATTERN : patterns;
        StringBuilder builder = new StringBuilder();
        long l = 0L;
        if (timeMillisGap > ONE_YEAR) {
            builder.append(toYear(timeMillisGap)).append(patterns[0]);
            builder.append(toMonth(l = timeMillisGap % ONE_YEAR)).append(patterns[1]);
            builder.append(toDays(l = l % ONE_MONTH)).append(patterns[2]);
            builder.append(toHours(l = l % ONE_DAY)).append(patterns[3]);
            builder.append(toMinutes(l = l % ONE_HOUR)).append(patterns[4]);
            builder.append(toSecond(l = l % ONE_MINUTE)).append(patterns[5]);

        } else if (timeMillisGap > ONE_MONTH) {
            builder.append(timeMillisGap / ONE_MONTH).append(patterns[1]);
            builder.append(toDays(l = timeMillisGap % ONE_MONTH)).append(patterns[2]);
            builder.append(toHours(l = l % ONE_DAY)).append(patterns[3]);
            builder.append(toMinutes(l = l % ONE_HOUR)).append(patterns[4]);
            builder.append(toSecond(l = l % ONE_MINUTE)).append(patterns[5]);
        } else if (timeMillisGap > ONE_DAY) {
            builder.append(timeMillisGap / ONE_DAY).append(patterns[2]);
            builder.append(toHours(l = timeMillisGap % ONE_DAY)).append(patterns[3]);
            builder.append(toMinutes(l = l % ONE_HOUR)).append(patterns[4]);
            builder.append(toSecond(l = l % ONE_MINUTE)).append(patterns[5]);
        } else if (timeMillisGap > ONE_HOUR) {
            builder.append(timeMillisGap / ONE_HOUR).append(patterns[3]);
            builder.append(toMinutes(l = timeMillisGap % ONE_HOUR)).append(patterns[4]);
            builder.append(toSecond(l = l % ONE_MINUTE)).append(patterns[5]);
        } else if (timeMillisGap > ONE_MINUTE) {
            builder.append(timeMillisGap / ONE_MINUTE).append(patterns[4]);
            builder.append(toSecond(l = timeMillisGap % ONE_MINUTE)).append(patterns[5]);
        } else if (timeMillisGap > ONE_SECOND) {
            builder.append(timeMillisGap / ONE_SECOND).append(patterns[5]);
        }

        if ((l = l % ONE_SECOND) > 0) {
            builder.append((l)).append(patterns[6]);
        }
        return builder.toString();
    }

    /**
     * 单位转换：毫秒转年
     *
     * @param timeMillis 毫秒数
     * @return 转换后的年数
     */
    public static long toYear(long timeMillis) {
        return timeMillis / ONE_YEAR;
    }

    /**
     * 单位转换：毫秒转月
     *
     * @param timeMillis 毫秒数
     * @return 转换后的月数
     */
    public static long toMonth(long timeMillis) {
        return timeMillis / ONE_MONTH;
    }

    /**
     * 单位转换：毫秒转天
     *
     * @param timeMillis 毫秒数
     * @return 转换后的天数
     */
    public static long toDays(long timeMillis) {
        return timeMillis / ONE_DAY;
    }

    /**
     * 单位转换：毫秒转小时
     *
     * @param timeMillis 毫秒数
     * @return 转换后的小时数
     */
    public static long toHours(long timeMillis) {
        return timeMillis / ONE_HOUR;
    }

    /**
     * 单位转换：毫秒转分钟
     *
     * @param timeMillis 毫秒数
     * @return 转换后的分钟数
     */
    public static long toMinutes(long timeMillis) {
        return timeMillis / ONE_MINUTE;
    }

    /**
     * 单位转换：毫秒转秒
     *
     * @param timeMillis 毫秒数
     * @return 转换后的秒数
     */
    public static long toSecond(long timeMillis) {
        return timeMillis / ONE_SECOND;
    }

    /**
     * 获取过去的年数
     * @param date 日期
     * @return 过去的年数
     */
    public static long pastYears(Date date) {
        long t = System.currentTimeMillis() - date.getTime();
        return t / ONE_YEAR;
    }

    /**
     * 获取过去的月数
     * @param date 日期
     * @return 过去的月数
     */
    public static long pastMonths(Date date) {
        long t = System.currentTimeMillis() - date.getTime();
        return t / ONE_MONTH;
    }

    /**
     * 获取过去的天数
     * @param date 日期
     * @return 过去的天数
     */
    public static long pastDays(Date date) {
        long t = System.currentTimeMillis() - date.getTime();
        return t / ONE_DAY;
    }

    /**
     * 获取过去的小时
     * @param date 日期
     * @return 过去的小时数
     */
    public static long pastHour(Date date) {
        long t = System.currentTimeMillis() - date.getTime();
        return t / ONE_HOUR;
    }

    /**
     * 获取过去的分钟
     * @param date 日期
     * @return 过去的分钟数
     */
    public static long pastMinutes(Date date) {
        long t = System.currentTimeMillis() - date.getTime();
        return t / ONE_MINUTE;
    }

    /**
     * 转换为时间（天,时:分:秒.毫秒）
     * @param timeMillis 毫秒数
     * @return 格式化后的时间字符串
     */
    public static String formatDateTime(long timeMillis) {
        long day = timeMillis / (24 * 60 * 60 * 1000);
        long hour = (timeMillis / ONE_HOUR - day * 24);
        long min = ((timeMillis / ONE_MINUTE) - day * 24 * 60 - hour * 60);
        long s = (timeMillis / 1000 - day * 24 * 60 * 60 - hour * 60 * 60 - min * 60);
        long sss = (timeMillis - day * 24 * 60 * 60 * 1000 - hour * 60 * 60 * 1000 - min * 60 * 1000 - s * 1000);
        return (day > 0 ? day + "," : "") + hour + ":" + min + ":" + s + "." + sss;
    }

    /**
     * 获取今天的开始时间 即 某天的 00:00:00.000 时间
     *
     * @return 当天开始时间
     */
    public static Date getBeginOfDay() {
        return getBeginOfDay(new Date());
    }

    /**
     * 获取今天的结束时间 即 某天的 23:59:59.999 时间
     *
     * @return 当天结束时间
     */
    public static Date getEndOfDay() {
        return getEndOfDay(new Date());
    }

    /**
     * 获取明天的开始时间 即 某天的 00:00:00.000 时间
     *
     * @return 明天开始时间
     */
    public static Date getBeginOfTomorrow() {
        return getBeginOfDay(getNextDay());
    }

    /**
     * 获取一天中开始的时间 即 {@code date}的 00:00:00.000 时间
     *
     * @param date 日期
     * @return 指定日期当天的开始时间
     */
    public static Date getBeginOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    /**
     * 获取一天中结束的时间 即 {@code date}的 23:59:59.999 时间
     *
     * @param date 日期
     * @return 指定日期当天的结束时间
     */
    public static Date getEndOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    /**
     * 将LocalDateTime转为java.util.Date
     *
     * @param localDateTime 本地日期时间
     * @return 转换后的 {@link Date}
     */
    public static Date toDate(LocalDateTime localDateTime) {
        Instant instant = localDateTime.atZone(ZoneId.systemDefault()).toInstant();
        return Date.from(instant);
    }

    /**
     * 将localDateTime转为 java.util.Date
     * zoneId 时区id：
     * <ul>
     * <li>{@code Z} - for UTC
     * <li>{@code +h}
     * <li>{@code +hh}
     * <li>{@code +hh:mm}
     * <li>{@code -hh:mm}
     * <li>{@code +hhmm}
     * <li>{@code -hhmm}
     * <li>{@code +hh:mm:ss}
     * <li>{@code -hh:mm:ss}
     * <li>{@code +hhmmss}
     * <li>{@code -hhmmss}
     * </ul>
     *
     * @param localDateTime 本地日期时间
     * @param zoneId 时区id（eg: +8 或 -8）{@link ZoneOffset#of(String)}
     * @return 转换后的 {@link Date}
     */
    public static Date toDate(LocalDateTime localDateTime, String zoneId) {
        Instant instant = localDateTime.toInstant(ZoneOffset.of(zoneId));
        return Date.from(instant);
    }

    /**
     * 将java.util.Date 转换为 LocalDateTime（默认取当前环境所在时区）
     *
     * @param date 日期
     * @return 转换后的 {@link LocalDateTime}
     */
    public static LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * 将java.util.Date 转换为 LocalDateTime（指定时区）
     *
     * @param date 日期
     * @param zoneId 时区id（eg: +8 或 -8）{@link ZoneOffset#of(String)}
     * @return 指定时区下的 {@link LocalDateTime}
     */
    public static LocalDateTime toLocalDateTime(Date date, String zoneId) {
        return date.toInstant().atZone(ZoneId.of(zoneId)).toLocalDateTime();
    }

}
