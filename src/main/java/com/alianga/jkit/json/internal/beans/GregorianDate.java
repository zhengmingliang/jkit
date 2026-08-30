package com.alianga.jkit.json.internal.beans;

import java.util.TimeZone;

/**
 * <p> 公历日期类</p>
 *
 * <pre>
 * - 关于闰年算法
 * 闰年只是为了修正平年（365天）和回归年（太阳转一圈）误差，没有绝对的算法。
 * 地球公转一周 365.24219天（回归年）
 * 什么时候该闰年？ 平年365天，闰年366天
 * 每4年实际天数： 365.24219 * 4 = 365 * 4 + 0.96876，即4个平年少算：0.96876天，所以把这差不多一天少算时间放在第四年作为闰年
 *
 * - 为什么被100整除但不被400整除的年不是闰年原因？
 * 假定0004年是闰年，多算0.03124天（0.03124 = 1 - 0.96876，把实际不够一天的时间当一天算，等于借了未来时间）
 * 按四年一闰则每个闰周期多算0.03124天，经过25个周期（假定100年是闰年）则会多算 25 * 0.03124 = 0.781天（借未来时间）,估算差不多1天
 * 此时将100年定为平年来抵消（除去本来应该定为闰年的一天还上未来时间0.781天，还剩下0.219天），所以100年变成平年了（排除早期闰年算法错误原因）。
 *
 * - 为什么被400整除的又是闰年？
 * 按100年是平年则每100年多出来0.219天（上文剩下来的），经过4个周期（400年），则会多出来0.876天，估算差不多一天，所以又将400年定为闰年。
 * 其实500年误差似乎更小一些，但500年会超过一天，不利于后面的周期运算。
 *
 * - 3200年是否为闰年？
 * 按400年一闰法则来计算，每400年向未来借0.124天（0.124 = 1 - 0.876）经过8个周期则会借0.992天，则把3200年定为平年能正好抵消（还完还剩下0.008天）
 * 所以理论上3200是平年。
 * </pre>
 *
 * <p>
 * 以3200年为一个周期（按3200年为平年）（每个周期剩下0.008天，则经过125个周期后即40万年，整整剩出1天(0.008 * 125)
 * 如按此周期年算，40万年又是闰年 ？？
 * 至此按365.24219的精度来算误差已经没了，即40万年可能是一个完整的闰年周期。
 *
 * <p>注： 目前支持公元元年开始以后的日期（0001-01-01 00:00:00.000+)
 *
 * @see java.util.Calendar
 */
public class GregorianDate extends GeneralDate implements java.io.Serializable, Comparable<GregorianDate> {
    // 星期
    /**
     * 星期，取值 1-7（1表示星期日）
     */
    protected int dayOfWeek;
    // 当月第几个星期
    /**
     * 当月第几个星期，按当月天数每7天进1计算
     */
    protected int weekOfMonth;
    // 当年第几个星期
    /**
     * 当年第几个星期，按当年天数每7天进1计算
     */
    protected int weekOfYear;

//    // 以下2个属性后续放在LunarDate中
//    // 以2019年24节气中时刻（毫秒数）作为参考
//    // 从小寒开始，冬至结束
//    public final static long[] SOLAR_TERMS_2019 = new long[24];

    // 闰年算法： 0~1582之前按每4年一润  1582-？ 按最新算法
    // 删除1582年 10月5日至14日共 10天

    /**
     * 以当前系统时间和默认时区构造日期对象
     */
    public GregorianDate() {
        this(System.currentTimeMillis());
    }

    /**
     * 以指定时间戳和默认时区构造日期对象
     *
     * @param timeMills 时间戳（毫秒）
     */
    public GregorianDate(long timeMills) {
        this(timeMills, null);
    }

    /**
     * 以当前系统时间和指定时区构造日期对象
     *
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     */
    public GregorianDate(TimeZone timeZone) {
        this(System.currentTimeMillis(), timeZone);
    }

    /**
     * 以指定时间戳和时区构造日期对象
     *
     * @param timeMills 时间戳（毫秒）
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     */
    public GregorianDate(long timeMills, TimeZone timeZone) {
        super(timeZone);
        setTime(timeMills);
    }

    /**
     * 以年月日构造日期对象，时分秒毫秒均为 0，使用默认时区
     *
     * @param year 年
     * @param month 月（1-12）
     * @param day 日
     */
    public GregorianDate(int year, int month, int day) {
        this(year, month, day, 0, 0, 0, 0, (TimeZone) null);
    }

    /**
     * 以年月日时分秒毫秒构造日期对象，使用默认时区
     *
     * @param year 年
     * @param month 月（1-12）
     * @param day 日
     * @param hour 小时（0-23）
     * @param minute 分钟
     * @param second 秒
     * @param millsecond 毫秒
     */
    public GregorianDate(int year, int month, int day, int hour, int minute, int second,
                         int millsecond) {
        this(year, month, day, hour, minute, second, millsecond, (TimeZone) null);
    }

    /**
     * 以年月日时分秒毫秒和指定时区构造日期对象
     *
     * @param year 年
     * @param month 月（1-12）
     * @param day 日
     * @param hour 小时（0-23）
     * @param minute 分钟
     * @param second 秒
     * @param millisecond 毫秒
     * @param timeZone 时区，为 {@code null} 时使用默认时区
     */
    public GregorianDate(int year, int month, int day, int hour, int minute, int second,
                         int millisecond, TimeZone timeZone) {
        super(timeZone);
        this.set(year, month, day, hour, minute, second, millisecond, timeZone);
    }

    /**
     * 支持格式： {'yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss'}
     * 年月日必须
     *
     * @param dateStr 日期字符串
     * @return 解析得到的日期对象（毫秒固定为 0）
     */
    public static GregorianDate parse(String dateStr) {
        GeneralDate generalDate = parseGeneralDate(dateStr, null);
        return new GregorianDate(generalDate.year, generalDate.month, generalDate.dayOfMonth, generalDate.hourOfDay,
                generalDate.minute, generalDate.second, 0);
    }

    /**
     * 以指定模板解析日期
     * 和format方法逆向处理
     *
     * @param dateStr 日期字符串
     * @param template 日期模板，为 {@code null} 时按默认格式解析
     * @return 解析得到的日期对象
     * @see GregorianDate#format(String)
     */
    public static GregorianDate parse(String dateStr, String template) {
        if (template == null) {
            return parse(dateStr);
        }
        char[] dateBuf = dateStr.toCharArray();
        return parse(dateBuf, 0, dateBuf.length, template);
    }

    /**
     * 提取日期字符转化为日期对象
     *
     * @param buf 字符缓冲区
     * @param offset 日期内容的起始位置
     * @param len 日期内容的长度
     * @param template 日期模板
     * @return 解析得到的日期对象
     */
    public static GregorianDate parse(char[] buf, int offset, int len, String template) {
        DateTemplate dateTemplate = new DateTemplate(template);
        return parse(buf, offset, len, dateTemplate);
    }

    /**
     * 使用已构建的日期模板提取日期字符转化为日期对象
     *
     * @param buf 字符缓冲区
     * @param offset 日期内容的起始位置
     * @param len 日期内容的长度
     * @param dateTemplate 日期模板对象，不允许为 {@code null}
     * @return 解析得到的日期对象
     */
    public static GregorianDate parse(char[] buf, int offset, int len, DateTemplate dateTemplate) {
        dateTemplate.getClass();
        return dateTemplate.parse(buf, offset, len);
    }

    /**
     * 以当前时间为轴左右
     *
     * @param type 时间字段类型，取值见 {@link GeneralDate#YEAR}、{@link GeneralDate#MONTH}、
     *             {@link GeneralDate#DAY_OF_MONTH} 等常量，未识别的类型不做处理
     * @param count 增减的数量，负数表示往前推
     * @return 当前对象，便于链式调用
     */
    public GregorianDate add(int type, int count) {
        switch (type) {
            case YEAR:
                this.year += count;
                this.updateTime();
                break;
            case MONTH:
                this.month += count;
                this.updateTime();
                break;
            case DAY_OF_MONTH: {
                // 如果是添加的天数
                long timeMills = this.timeMills + 24L * 3600 * 1000 * count;
                setTime(timeMills);
                break;
            }
            case HOUR: {
                long timeMills = this.timeMills + 3600L * 1000 * count;
                setTime(timeMills);
                break;
            }
            case MINUTE: {
                long timeMills = this.timeMills + 60L * 1000 * count;
                setTime(timeMills);
                break;
            }
            case SECOND: {
                long timeMills = this.timeMills + 1000L * count;
                setTime(timeMills);
                break;
            }
            case MILLISECOND: {
                long timeMills = this.timeMills + count;
                setTime(timeMills);
                break;
            }
            default:
                break;
        }
        return this;
    }

    /**
     * 计算目标日期与当前日期的时间差
     *
     * @param target 目标日期
     * @return 目标时间减当前时间的毫秒差，目标日期在前时为负数
     */
    public long interval(GregorianDate target) {
        return target.getTime() - this.timeMills;
    }

    /**
     * 计算目标日期与当前日期相差的天数
     *
     * @param target 目标日期
     * @return 毫秒差除以一天毫秒数取整后的天数，不足一天按 0 计
     */
    public long intervalDays(GregorianDate target) {
        return (target.getTime() - this.timeMills) / 86400000L;
    }

    /**
     * 计算目标日期与当前日期相差的小时数
     *
     * @param target 目标日期
     * @return 毫秒差除以一小时毫秒数取整后的小时数，不足一小时按 0 计
     */
    public long intervalHours(GregorianDate target) {
        return (target.getTime() - this.timeMills) / 3600000L;
    }

    /**
     * 设置年月日，时分秒毫秒保持不变
     *
     * @param year 年
     * @param month 月（1-12）
     * @param day 日
     * @return 当前对象，便于链式调用
     */
    public GregorianDate set(int year, int month, int day) {
        return this.set(year, month, day, hourOfDay, minute, second, millisecond, null);
    }

    /**
     * 设置年月日时分秒毫秒，时区保持不变
     *
     * @param year 年
     * @param month 月（1-12）
     * @param day 日
     * @param hour 小时（0-23）
     * @param minute 分钟
     * @param second 秒
     * @param millisecond 毫秒
     * @return 当前对象，便于链式调用
     */
    public GregorianDate set(int year, int month, int day, int hour, int minute, int second,
                             int millisecond) {
        return this.set(year, month, day, hour, minute, second, millisecond, null);
    }

    /**
     * 设置年月日时分秒毫秒以及时区，并重新计算时间戳
     *
     * @param year 年
     * @param month 月（1-12）
     * @param day 日
     * @param hour 小时（0-23）
     * @param minute 分钟
     * @param second 秒
     * @param millisecond 毫秒
     * @param timeZone 时区，为 {@code null} 时沿用当前时区
     * @return 当前对象，便于链式调用
     */
    public GregorianDate set(int year, int month, int day, int hour, int minute, int second,
                             int millisecond, TimeZone timeZone) {
        ofTimeZone(timeZone);
        this.year = year;
        this.month = month;
        this.dayOfMonth = day;
        this.hourOfDay = hour;
        this.minute = minute;
        this.second = second;
        this.millisecond = millisecond;
        updateTime();
        return this;
    }

    protected void updateTime() {
        super.updateTime();
        int dayOfWeek = (int) ((RELATIVE_DAY_OF_WEEK + currentDays - RELATIVE_DAYS - 1) % 7 + 1);
        if (dayOfWeek <= 0) {
            dayOfWeek = dayOfWeek + 7;
        }
        // 星期
        this.dayOfWeek = dayOfWeek;
        // 当年第几个星期
        this.weekOfYear = daysOfYear % 7 == 0 ? daysOfYear / 7 : daysOfYear / 7 + 1;
        // 校验日期回设
        if (!validate()) {
            setTime(this.timeMills, true);
        }
        // 当月第几个星期
        this.weekOfMonth = dayOfMonth % 7 == 0 ? dayOfMonth / 7 : dayOfMonth / 7 + 1;
        afterDateChange();
    }

    /**
     * 日期发生变更后的回调，默认空实现，供子类扩展
     */
    protected void afterDateChange() {
    }

    private boolean validate() {
        // 去除year的校验(<1)，支持公元元年之前的日期
        if (/*this.year <= 0
                ||*/ this.month < 1 || this.month > 12
                || this.dayOfMonth < 1 || this.dayOfMonth > 31
                || this.hourOfDay < 0 || this.hourOfDay > 23
                || this.minute < 0 || this.minute > 59
                || this.second < 0 || this.second > 59
                || this.millisecond < 0 || this.millisecond > 999
        ) {
            return false;
        }
        if (this.month == 4 || this.month == 6 || this.month == 9 || this.month == 11) {
            if (dayOfMonth == 31) {
                return false;
            }
        } else if (this.month == 2) {
            if (dayOfMonth > 29) {
                return false;
            }
            if (!leapYear && dayOfMonth == 29) {
                return false;
            }
        }
        return true;
    }

    /**
     * 设置年份并重新计算时间戳
     *
     * @param year 年
     * @return 当前对象，便于链式调用
     */
    public GregorianDate setYear(int year) {
        if (this.year == year) {
            return this;
        }
        this.year = year;
        this.updateTime();
        return this;
    }

    /**
     * 设置月份并重新计算时间戳
     *
     * @param month 月（1-12）
     * @return 当前对象，便于链式调用
     */
    public GregorianDate setMonth(int month) {
        if (this.month == month) {
            return this;
        }
        this.month = month;
        updateTime();
        return this;
    }

    /**
     * 设置日并重新计算时间戳
     *
     * @param day 日
     * @return 当前对象，便于链式调用
     */
    public GregorianDate setDay(int day) {
        if (this.dayOfMonth == day) {
            return this;
        }
        this.dayOfMonth = day;
        updateTime();
        return this;
    }

    /**
     * 设置小时并重新计算时间戳
     *
     * @param hourOfDay 小时（0-23）
     * @return 当前对象，便于链式调用
     */
    public GregorianDate setHourOfDay(int hourOfDay) {
        if (this.hourOfDay == hourOfDay) {
            return this;
        }
        this.hourOfDay = hourOfDay;
        updateTime();
        return this;
    }

    /**
     * 设置分钟并重新计算时间戳
     *
     * @param minute 分钟（0-59）
     * @return 当前对象，便于链式调用
     */
    public GregorianDate setMinute(int minute) {
        if (this.minute == minute) {
            return this;
        }
        this.minute = minute;
        updateTime();
        return this;
    }

    /**
     * 设置秒并重新计算时间戳
     *
     * @param second 秒（0-59）
     * @return 当前对象，便于链式调用
     */
    public GregorianDate setSecond(int second) {
        if (this.second == second) {
            return this;
        }
        this.second = second;
        updateTime();
        return this;
    }

    /**
     * 设置毫秒并重新计算时间戳
     *
     * @param millisecond 毫秒（0-999）
     * @return 当前对象，便于链式调用
     */
    public GregorianDate setMillisecond(int millisecond) {
        if (this.millisecond == millisecond) {
            return this;
        }
        this.millisecond = millisecond;
        updateTime();
        return this;
    }

    /**
     * 获取当前日期在当年中的天序号
     *
     * @return 当年的第几天（1 表示 1 月 1 日）
     */
    public int getDaysOfYear() {
        return daysOfYear;
    }

    /**
     * 获取当前日期是星期几
     *
     * @return 星期值，取值 1-7（1 表示星期日）
     */
    public int getDayOfWeek() {
        return dayOfWeek;
    }

    /**
     * 判断当前年份是否闰年
     *
     * @return 是闰年时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isLeapYear() {
        return leapYear;
    }

    public void setTime(long timeMills, boolean reset) {
        // 如果毫秒没有变化不计算（注意-1不做处理）
        if (this.timeMills == timeMills && !reset) {
            return;
        }
        super.setTime(timeMills, reset);

        // 以星期四（5）作为参考，星期六（7） 星期日（1）   -6~0   1-7
        int dayOfWeek = (int) ((RELATIVE_DAY_OF_WEEK + currentDays - RELATIVE_DAYS - 1) % 7 + 1);
        if (dayOfWeek <= 0) {
            dayOfWeek = dayOfWeek + 7;
        }
        this.dayOfWeek = dayOfWeek;
        this.weekOfMonth = dayOfMonth % 7 == 0 ? dayOfMonth / 7 : dayOfMonth / 7 + 1;
        this.weekOfYear = daysOfYear % 7 == 0 ? daysOfYear / 7 : daysOfYear / 7 + 1;

        this.afterDateChange();
    }

    /**
     * 设置时间戳并重新计算各日期字段
     *
     * @param timeMills 时间戳（毫秒）
     */
    public void setTime(long timeMills) {
        setTime(timeMills, false);
    }

    /**
     * 是否同一天（不考虑时辰）,需要加上时差
     *
     * @param sourceTimemills 源时间戳（毫秒）
     * @param targetTimeMills 目标时间戳（毫秒）
     * @return 加上默认时区偏移后属于同一天时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isSameDay(long sourceTimemills, long targetTimeMills) {
        return (sourceTimemills + RELATIVE_MILLS + getDefaultOffset()) / 86400000 ==
                (targetTimeMills + RELATIVE_MILLS + getDefaultOffset()) / 86400000;
    }

    @Override
    public String toString() {
        return format();
    }

    /**
     * 输出未补零的日期时间字符串
     *
     * @return 形如 {@code 2024-3-5 9:8:7.66} 的字符串，各字段不做两位补齐
     */
    public String toDateString() {
        return year + "-" + month + "-" + dayOfMonth + " " + hourOfDay + ":" + minute + ":" + second + "." +
                millisecond;
    }

    /**
     * 格式化
     *
     * @return 形如 {@code yyyy-MM-dd HH:mm:ss} 的字符串，年份为负数时以 {@code -} 开头
     */
    public String format() {
        return format('-', ':');
    }

    /**
     * 格式化
     *
     * @param dateSyntax 年月日分隔符 默认'-'
     * @param timeSyntax 时分秒分隔符 默认':'
     * @return 使用指定分隔符、各字段两位补零的日期时间字符串（不含毫秒）
     */
    public String format(char dateSyntax, char timeSyntax) {
        StringBuilder builder = new StringBuilder(19);
        int year = this.year;
        if (year < 0) {
            builder.append("-");
            year = -year;
        }
        int y1 = year / 100;
        int y2 = year % 100;
        builder.append(DateTemplate.DigitTens[y1]);
        builder.append(DateTemplate.DigitOnes[y1]);
        builder.append(DateTemplate.DigitTens[y2]);
        builder.append(DateTemplate.DigitOnes[y2]);
        builder.append(dateSyntax);
        builder.append(DateTemplate.DigitTens[month]);
        builder.append(DateTemplate.DigitOnes[month]);
        builder.append(dateSyntax);
        builder.append(DateTemplate.DigitTens[dayOfMonth]);
        builder.append(DateTemplate.DigitOnes[dayOfMonth]);
        builder.append(' ');
        builder.append(DateTemplate.DigitTens[hourOfDay]);
        builder.append(DateTemplate.DigitOnes[hourOfDay]);
        builder.append(timeSyntax);
        builder.append(DateTemplate.DigitTens[minute]);
        builder.append(DateTemplate.DigitOnes[minute]);
        builder.append(timeSyntax);
        builder.append(DateTemplate.DigitTens[second]);
        builder.append(DateTemplate.DigitOnes[second]);
        return builder.toString();
    }

    /**
     * <p> Y 4位数年份
     * <p> y 2位年份
     * <p> M 格式化2位月份
     * <p> d 格式化2位天
     * <p> H 格式化24制小时数
     * <p> m 格式化2位分钟
     * <p> s 格式化2位秒
     * <p> S 格式化3位毫秒
     * <p> a 上午/下午
     *
     * @param template 日期模板，为 {@code null} 时使用默认格式
     * @return 按模板格式化后的日期字符串
     */
    public String format(String template) {
        if (template == null) {
            return format();
        }
        StringBuilder writer = new StringBuilder();
        formatTo(template, writer);
        return writer.toString();
    }

    /**
     * 按模板格式化并输出到指定的追加目标
     *
     * @param template 日期模板
     * @param appendable 接收格式化结果的输出目标
     */
    public void formatTo(String template, Appendable appendable) {
        DateTemplate.formatTo(year, month, dayOfMonth, hourOfDay, minute, second, millisecond, dayOfWeek, daysOfYear,
                weekOfMonth, weekOfYear, timeZone, template, appendable);
    }

    /**
     * 按模板格式化并输出到指定的追加目标，可选择对双引号转义
     *
     * @param template 日期模板
     * @param appendable 接收格式化结果的输出目标
     * @param escapeQuot 是否对结果中的双引号进行转义
     */
    public void formatTo(String template, Appendable appendable, boolean escapeQuot) {
        DateTemplate.formatTo(year, month, dayOfMonth, hourOfDay, minute, second, millisecond, dayOfWeek, daysOfYear,
                weekOfMonth, weekOfYear, timeZone, template, appendable, escapeQuot);
    }

    public int compareTo(GregorianDate o) {
        if (timeMills == o.timeMills) {
            return 0;
        }
        return timeMills > o.timeMills ? 1 : -1;
    }
}
