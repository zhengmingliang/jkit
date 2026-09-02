package com.alianga.jkit.jdk;

import com.alianga.jkit.reflect.UnsafeHelper;

/**
 * @time 2024/6/8 14:22
 */
public class JdkApiAgent {
    /**
     * 计算两个 long 相乘（128 位结果）的高 64 位，等价于 JDK9 的 {@code Math.multiplyHigh}。
     *
     * @param x 被乘数
     * @param y 乘数
     * @return 有符号乘积的高 64 位
     */
    public long multiplyHigh(long x, long y) {
        long x1 = x >> 32;
        long x2 = x & 0xFFFFFFFFL;
        long y1 = y >> 32;
        long y2 = y & 0xFFFFFFFFL;

        long z2 = x2 * y2;
        long t = x1 * y2 + (z2 >>> 32);
        long z1 = t & 0xFFFFFFFFL;
        long z0 = t >> 32;
        z1 += x2 * y1;
        return x1 * y1 + z0 + (z1 >> 32);
    }

    /**
     * 计算两个 long 按无符号相乘（128 位结果）的高 64 位，等价于 JDK18 的
     * {@code Math.unsignedMultiplyHigh}。
     *
     * @param x 被乘数，按无符号解释
     * @param y 乘数，按无符号解释
     * @return 无符号乘积的高 64 位
     */
    public long unsignedMultiplyHigh(long x, long y) {
        // Compute via multiplyHigh() to leverage the intrinsic
        long result = multiplyHigh(x, y);
        result += (y & (x >> 63));
        result += (x & (y >> 63));
        return result;
    }

    /**
     * Calculate the high bits of two long values. To be compatible with performance below JDK9, please ensure that x
     * and y are both greater than 0
     *
     * @param x > 0
     * @param y > 0
     * @return the high 64 bits of the 128-bit product of x and y
     */
    public long multiplyHighKaratsuba(long x, long y) {
        long x1 = x >>> 32;
        long x2 = x & 0xffffffffL;
        long y1 = y >>> 32;
        long y2 = y & 0xffffffffL;
        long A = x1 * y1;
        long B = x2 * y2;
        long C = (x1 + x2) * (y1 + y2);
        // karatsuba
        long K = C - A - B;
        long BC = B >>> 32;
        return ((BC + K) >>> 32) + A;
    }

    /**
     * 判断是否包含负字节
     * <p>
     * note: if JDK9, will use JDK's more efficient API reflection implementation
     *
     * @param bytes  待检查的字节数组
     * @param offset 起始下标
     * @param len    检查的字节个数
     * @return 区间内存在负字节（即最高位为 1）时返回 {@code true}，否则返回 {@code false}
     */
    public boolean hasNegatives(byte[] bytes, int offset, int len) {
        return UnsafeHelper.hasNegativesUnsafe(bytes, offset, len);
    }
}
