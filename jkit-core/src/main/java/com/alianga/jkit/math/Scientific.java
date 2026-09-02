package com.alianga.jkit.math;

/**
 * @time 2024/5/25 10:46
 */
public class Scientific {
    /**
     * 十进制有效数字（科学计数法的尾数去掉小数点后的整数形式）。
     */
    public final long output;
    /**
     * {@link #output} 的十进制位数。
     */
    public final int count;
    /**
     * 十进制指数，即科学计数法中 10 的幂次。
     */
    public final int e10;
    /**
     * 是否为 10 的整数次幂形式（值等于 {@code 1e}{@link #e10}），此时 {@link #output} 与 {@link #count} 均为 0。
     */
    public final boolean b;

    /**
     * 表示无法用科学计数法表示的值（NaN 与正负无穷），输出为 {@code null}。
     */
    public static final Scientific SCIENTIFIC_NULL = new Scientific(0, true);
    /**
     * 正零，输出为 {@code 0.0}。
     */
    public static final Scientific ZERO = new Scientific(0, 3, 0);
    /**
     * 负零，输出为 {@code -0.0}。
     */
    public static final Scientific NEGATIVE_ZERO = new Scientific(0, 3, 0);
    /**
     * {@link Double#MIN_VALUE} 对应的科学计数法表示，即 {@code 4.9E-324}。
     */
    public static final Scientific DOUBLE_MIN = new Scientific(49, 2, -324); // 4.9E-324

    /**
     * 构造普通形式的科学计数法结果，{@link #b} 固定为 {@code false}。
     *
     * @param output 十进制有效数字
     * @param count  有效数字的十进制位数
     * @param e10    十进制指数
     */
    public Scientific(long output, int count, int e10) {
        this.output = output;
        this.count = count;
        this.e10 = e10;
        this.b = false;
    }

    /**
     * 构造 10 的整数次幂形式的科学计数法结果，{@link #output} 与 {@link #count} 均置为 0。
     *
     * @param e10 十进制指数
     * @param b   是否为 10 的整数次幂形式
     */
    public Scientific(int e10, boolean b) {
        this.e10 = e10;
        this.b = b;
        output = 0;
        count = 0;
    }

    @Override
    public String toString() {
        if (this == SCIENTIFIC_NULL) {
            return "null";
        }
        if (this == ZERO) {
            return "0.0";
        }
        if (this == NEGATIVE_ZERO) {
            return "-0.0";
        }
        if (b) {
            return "1e" + e10;
        }
        return output + "|" + e10;
    }
}
