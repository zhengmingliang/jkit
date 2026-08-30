/**
 * Created by 郑明亮 on 2022/2/7 0:28.
 */
package com.alianga.jkit.math;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * <p> this is your description</p>
 *
 * @author 郑明亮
 * @version 1.0.0
 * @time 2022/2/7 0:28
 */
public class Number extends BigDecimal {
    /**
     * 数值 0 的常量实例
     */
    public static final Number ZERO =
            new Number(BigInteger.ZERO, 0, 1);

    /**
     * 根据 {@link BigInteger} 构造数值（小数位数为 0）
     *
     * @param val 整数值
     */
    public Number(BigInteger val) {
        super(val);
    }

    /**
     * 根据字符串构造数值
     *
     * @param val 数值的字符串表示
     */
    public Number(String val) {
        super(val);
    }

    /**
     * 根据无标度值与小数位数构造数值
     *
     * @param unscaledVal 无标度整数值
     * @param scale 小数位数
     */
    public Number(BigInteger unscaledVal, int scale) {
        super(unscaledVal, scale);
    }

    /**
     * 根据 int 值构造数值
     *
     * @param val 整数值
     */
    public Number(int val) {
        super(val);
    }

    /**
     * 根据 long 值构造数值
     *
     * @param val 长整数值
     */
    public Number(long val) {
        super(val);
    }

    /**
     * 根据无标度值、小数位数与精度构造数值
     *
     * @param intVal 无标度整数值
     * @param scale 小数位数
     * @param prec 有效数字位数，用于构造 {@link MathContext}
     */
    public Number(BigInteger intVal, int scale, int prec) {
        super(intVal, scale, new MathContext(prec));
    }

    /**
     * 根据字符串构造数值，并按指定小数位数调整精度
     *
     * @param val 数值的字符串表示
     * @param scale 期望的小数位数，若需要舍入将抛出 {@link ArithmeticException}
     */
    public Number(String val, int scale) {
        super(new BigDecimal(val).setScale(scale).unscaledValue(), scale);
    }

    /**
     * 根据字符串与运算上下文构造数值
     *
     * @param val 数值的字符串表示
     * @param mc 控制精度与舍入方式的运算上下文
     */
    public Number(String val, MathContext mc) {
        super(val, mc);
    }

    /**
     * 将字符串形式的数值与当前值相加
     *
     * @param number 字符串形式的数值
     * @return 相加后的新 {@link BigDecimal} 对象
     */
    public BigDecimal add(String number) {
        return super.add(new BigDecimal(number));
    }

    /**
     * 依次将多个字符串形式的数值与当前值相加（{@link BigDecimal} 不可变，每次相加的结果未被保留）
     *
     * @param numbers 字符串形式的数值数组，允许为 {@code null}
     * @return 当前值与 {@link BigDecimal#ZERO} 相加的结果，数值上等于当前值
     */
    /**
     * 依次将多个字符串形式的数值累加到当前值上
     *
     * @param numbers 字符串形式的数值数组，允许为 {@code null}
     * @return 当前值与全部入参累加后的结果，{@code numbers} 为 {@code null} 或空数组时数值上等于当前值
     */
    public BigDecimal add(String... numbers) {
        BigDecimal result = this;
        if (numbers != null) {
            for (String number : numbers) {
                result = result.add(new BigDecimal(number));
            }
        }

        return result;
    }

    /**
     * 将 {@link BigDecimal} 转换为 {@code Number}
     *
     * @param bigDecimal 源数值对象
     * @return 与入参数值、小数位数、精度一致的新 {@code Number} 实例
     */
    public static Number valueOf(BigDecimal bigDecimal) {
        return new Number(bigDecimal.unscaledValue(), bigDecimal.scale(), bigDecimal.precision());
    }

    /**
     * 输出不带科学计数法的字符串，并按指定小数位数调整精度
     *
     * @param scale 期望的小数位数，若需要舍入将抛出 {@link ArithmeticException}
     * @return 调整小数位数后的普通字符串形式
     */
    public String toScaleString(int scale) {
        return this.setScale(scale).toPlainString();
    }

    /**
     * 输出不带科学计数法的字符串，并按指定小数位数与舍入方式调整精度
     *
     * @param scale 期望的小数位数
     * @param roundingMode 舍入方式
     * @return 调整小数位数后的普通字符串形式
     */
    public String toScaleString(int scale, RoundingMode roundingMode) {
        return this.setScale(scale, roundingMode).toPlainString();
    }
}
