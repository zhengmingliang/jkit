package com.alianga.jkit.mock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据模板中的生成规则 {@code 'name|rule'}，语义对齐 Mock.js 的 {@code Parser.parse}。
 *
 * <p>支持的形式：</p>
 * <ul>
 *   <li>{@code 'name|min-max'} —— 重复 / 取值 的随机次数</li>
 *   <li>{@code 'name|count'} —— 固定次数</li>
 *   <li>{@code 'name|+step'} —— 自增或顺序取值</li>
 *   <li>{@code 'name|min-max.dmin-dmax'} —— 数字带小数位</li>
 * </ul>
 *
 * @author 郑明亮
 */
public final class MockRule {
    private static final Pattern RE_KEY = Pattern.compile(
            "(.+)\\|(?:\\+(\\d+)|([\\+\\-]?\\d+(?:\\.\\d+)?-?[\\+\\-]?\\d*)?(?:\\.(\\d+-?\\d*))?)");
    private static final Pattern RE_RANGE =
            Pattern.compile("([\\+\\-]?\\d+(?:\\.\\d+)?)-?([\\+\\-]?\\d+(?:\\.\\d+)?)?");

    private final String name;
    private final boolean ruled;
    private final boolean range;
    private final Integer inc;
    private final Integer min;
    private final Integer max;
    private final Integer count;
    private final Integer dcount;
    private final double minValue;
    private final double maxValue;

    private MockRule(String name, boolean ruled, boolean range, Integer inc, Integer min,
                     Integer max, Integer count, Integer dcount,
                     double minValue, double maxValue) {
        this.name = name;
        this.ruled = ruled;
        this.range = range;
        this.inc = inc;
        this.min = min;
        this.max = max;
        this.count = count;
        this.dcount = dcount;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    /**
     * 解析属性名中的生成规则。
     *
     * @param key    形如 {@code list|1-10} 的键
     * @param random 随机源，用于展开 {@code min-max} 区间
     * @return 解析结果
     */
    public static MockRule parse(String key, MockRandom random) {
        String raw = key == null ? "" : key;
        Matcher m = RE_KEY.matcher(raw);
        if (!m.matches()) {
            return new MockRule(raw, false, false, null, null, null, null, null, 0D, 0D);
        }
        String name = m.group(1);
        Integer inc = m.group(2) == null ? null : Integer.valueOf(m.group(2));
        Integer min = null;
        Integer max = null;
        Integer count = null;
        double minValue = 0D;
        double maxValue = 0D;
        boolean rangeMatched = false;
        String range = m.group(3);
        if (range != null && !range.isEmpty()) {
            Matcher rm = RE_RANGE.matcher(range);
            if (rm.matches()) {
                rangeMatched = true;
                minValue = Double.parseDouble(rm.group(1));
                maxValue = rm.group(2) == null ? minValue : Double.parseDouble(rm.group(2));
                boolean integral = isIntegral(rm.group(1))
                        && (rm.group(2) == null || isIntegral(rm.group(2)));
                if (integral) {
                    min = (int) minValue;
                    max = rm.group(2) == null ? null : (int) maxValue;
                    count = max == null ? min
                            : (int) random.integer(min.intValue(), max.intValue());
                } else {
                    int lo = (int) Math.ceil(Math.min(minValue, maxValue));
                    int hi = (int) Math.floor(Math.max(minValue, maxValue));
                    count = (int) random.integer(lo, Math.max(lo, hi));
                }
            }
        }
        Integer dcount = null;
        String decimal = m.group(4);
        if (decimal != null && !decimal.isEmpty()) {
            Matcher dm = RE_RANGE.matcher(decimal);
            if (dm.matches()) {
                int dmin = Integer.parseInt(dm.group(1));
                Integer dmax = dm.group(2) == null ? null : Integer.valueOf(dm.group(2));
                dcount = dmax == null ? dmin : (int) random.integer(dmin, dmax.intValue());
            }
        }
        boolean ruled = inc != null || count != null || dcount != null;
        boolean hasRange = min != null || minValue != maxValue || rangeMatched;
        return new MockRule(name, ruled, hasRange, inc, min, max, count, dcount, minValue, maxValue);
    }

    private static boolean isIntegral(String text) {
        return text != null && text.indexOf('.') < 0;
    }

    /**
     * 去掉规则后的真实属性名。
     *
     * @return 属性名
     */
    public String getName() {
        return name;
    }

    /**
     * 是否带生成规则（即键中是否含 {@code |}）。
     *
     * @return 带规则返回 true
     */
    public boolean isRuled() {
        return ruled;
    }

    /**
     * 自增 / 顺序步长，如 {@code 'id|+1'} 返回 1。
     *
     * @return 步长，无则 null
     */
    public Integer getInc() {
        return inc;
    }

    /**
     * 区间下界。
     *
     * @return 下界，无则 null
     */
    public Integer getMin() {
        return min;
    }

    /**
     * 区间上界。
     *
     * @return 上界，无则 null
     */
    public Integer getMax() {
        return max;
    }

    /**
     * 展开后的次数：{@code count} 形式即固定值，{@code min-max} 形式为区间内随机值。
     *
     * @return 次数，无区间则 null
     */
    public Integer getCount() {
        return count;
    }

    /**
     * 小数位数，由 {@code .dmin-dmax} 展开得到。
     *
     * @return 小数位数，无则 null
     */
    public Integer getDecimalCount() {
        return dcount;
    }

    /**
     * 键中是否声明了 {@code min-max} 区间（含小数区间，如 {@code 9.9-999}）。
     *
     * @return 有区间返回 true
     */
    public boolean hasRange() {
        return range;
    }

    /**
     * 区间下界的数值形式，小数区间（如 {@code 9.9-999}）也能取到。
     *
     * @return 下界
     */
    public double getMinValue() {
        return minValue;
    }

    /**
     * 区间上界的数值形式，未写上界时等于下界。
     *
     * @return 上界
     */
    public double getMaxValue() {
        return maxValue;
    }

    /**
     * 是否为 {@code '+N'} 形式的自增 / 顺序规则。
     *
     * @return 是返回 true
     */
    public boolean isOrder() {
        return inc != null;
    }

    /**
     * 是否声明了小数位。
     *
     * @return 是返回 true
     */
    public boolean hasDecimal() {
        return dcount != null;
    }

    @Override
    public String toString() {
        return "MockRule{name=" + name + ", inc=" + inc + ", min=" + min + ", max=" + max
                + ", count=" + count + ", dcount=" + dcount
                + ", range=[" + minValue + "," + maxValue + "]}";
    }
}
