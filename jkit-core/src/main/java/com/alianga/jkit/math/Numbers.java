/**
 * Created by 郑明亮 on 2022/2/7 0:27.
 */
package com.alianga.jkit.math;

import com.alianga.jkit.StringUtils;

import java.math.BigDecimal;

/**
 * <p> 数字处理工具类</p>
 *
 * @author 郑明亮
 * @version 1.0.0
 * @time 2022/2/7 0:27
 */
public class Numbers extends NumberUtils {
    private static final Integer ZERO_INT = Integer.valueOf(0);
    private static final Long ZERO_LONG = Long.valueOf(0);

    /**
     * 求和，{@code null} 按 0 处理
     *
     * @param num1 被加数，为 {@code null} 时按 0 处理
     * @param num2 加数列表，为 {@code null} 时直接返回 {@code num1}；其中的 {@code null} 元素会被跳过
     * @return {@code num1} 与 {@code num2} 中所有非空元素累加后的结果
     */
    public static Integer add(Integer num1, Integer... num2) {
        if (num1 == null) {
            num1 = ZERO_INT;
        }
        if (num2 == null) {
            return num1;
        }
        for (Integer num : num2) {
            if (num == null) {
                continue;
            }
            num1 += num;
        }
        return num1;
    }

    /**
     * 求和，{@code null} 按 0 处理
     *
     * @param num1 被加数，为 {@code null} 时按 0 处理
     * @param num2 加数列表，为 {@code null} 时直接返回 {@code num1}；其中的 {@code null} 元素会被跳过
     * @return {@code num1} 与 {@code num2} 中所有非空元素累加后的结果
     */
    public static Long add(Long num1, Long... num2) {
        if (num1 == null) {
            num1 = ZERO_LONG;
        }
        if (num2 == null) {
            return num1;
        }
        for (Long num : num2) {
            if (num == null) {
                continue;
            }
            num1 += num;
        }
        return num1;
    }

    /**
     * 求差，{@code null} 按 0 处理
     *
     * @param num1 被减数，为 {@code null} 时按 0 处理
     * @param num2 减数列表，为 {@code null} 时直接返回 {@code num1}；其中的 {@code null} 元素会被跳过
     * @return {@code num1} 依次减去 {@code num2} 中所有非空元素后的结果
     */
    public static Integer subtract(Integer num1, Integer... num2) {
        if (num1 == null) {
            num1 = ZERO_INT;
        }
        if (num2 == null) {
            return num1;
        }
        for (Integer num : num2) {
            if (num == null) {
                continue;
            }
            num1 -= num;
        }
        return num1;
    }

    /**
     * 求差，{@code null} 按 0 处理
     *
     * @param num1 被减数，为 {@code null} 时按 0 处理
     * @param num2 减数列表，为 {@code null} 时直接返回 {@code num1}；其中的 {@code null} 元素会被跳过
     * @return {@code num1} 依次减去 {@code num2} 中所有非空元素后的结果
     */
    public static Long subtract(Long num1, Long... num2) {
        if (num1 == null) {
            num1 = ZERO_LONG;
        }
        if (num2 == null) {
            return num1;
        }
        for (Long num : num2) {
            if (num == null) {
                continue;
            }
            num1 -= num;
        }
        return num1;
    }

    /**
     * 高精度求和，{@code null} 元素会被跳过
     *
     * <p>返回值类型与入参保持一致（{@link BigDecimal} 入参对应 {@link BigDecimal} 返回），
     * 与 {@link #subtract(BigDecimal, BigDecimal...)} 对称。</p>
     *
     * @param num1 被加数，为 {@code null} 时按 {@link BigDecimal#ZERO} 处理
     * @param num2 加数列表，为 {@code null} 时直接返回 {@code num1}
     * @return {@code num1} 与 {@code num2} 中所有非空元素累加后的 {@link BigDecimal}
     */
    public static BigDecimal add(BigDecimal num1, BigDecimal... num2) {
        if (num1 == null) {
            num1 = BigDecimal.ZERO;
        }
        if (num2 == null) {
            return num1;
        }
        for (BigDecimal num : num2) {
            if (num == null) {
                continue;
            }
            num1 = num1.add(num);
        }
        return num1;
    }

    /**
     * 高精度求差，{@code null} 元素会被跳过
     *
     * <p>返回值类型与入参保持一致（{@link BigDecimal} 入参对应 {@link BigDecimal} 返回），
     * 与 {@link #add(BigDecimal, BigDecimal...)} 对称。</p>
     *
     * @param num1 被减数，为 {@code null} 时按 {@link BigDecimal#ZERO} 处理
     * @param num2 减数列表，为 {@code null} 时直接返回 {@code num1}
     * @return {@code num1} 依次减去 {@code num2} 中所有非空元素后的 {@link BigDecimal}
     */
    public static BigDecimal subtract(BigDecimal num1, BigDecimal... num2) {
        if (num1 == null) {
            num1 = BigDecimal.ZERO;
        }
        if (num2 == null) {
            return num1;
        }
        for (BigDecimal num : num2) {
            if (num == null) {
                continue;
            }
            num1 = num1.subtract(num);
        }
        return num1;
    }

    /**
     * 转换为无符号数字
     *
     * @param number 数字字符串
     * @return 去掉开头正负号后的字符串；入参为空或不以符号开头时原样返回
     */
    public static String toUnsigned(String number) {
        if (StringUtils.isNotBlank(number)) {
            char ch = number.charAt(0);
            if (ch == '+' || ch == '-') {
                return number.substring(1);
            }
        }
        return number;
    }

    /**
     * 转换为无符号数字
     *
     * @param number 数字对象
     * @return 去掉开头正负号后的字符串；入参为 {@code null} 时返回 {@code "0"}
     */
    public static String toUnsigned(java.lang.Number number) {
        if (number != null) {
            return toUnsigned(number.toString());
        }
        return "0";
    }

    /**
     * 从文本中找到数字并返回
     *
     * @param text 待提取的文本
     * @return 文本中所有数字字符（含合法的负号与小数点）拼接成的字符串；
     *         入参为 {@code null} 或不含数字时返回空字符串
     */
    public static String getNumber(String text) {
        return getNumber(text, false);
    }

    /**
     * 得到数字（整数、浮点数均可）
     *
     * @param text 文本
     * @param onlyFirstNumber 只获取第一个数字
     * @return {@link String}
     */
    private static String getNumber(String text, boolean onlyFirstNumber) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i];
            if (NumberUtils.isNumber(ch)) {
                builder.append(ch);
            } else if (ch == '-') {
                // 处理负数
                if (i >= 0 && i < chars.length - 1) {
                    if (NumberUtils.isNumber(chars[i + 1])) {
                        builder.append(ch);
                    }
                }
            } else if (ch == '.') {
                // 处理小数
                if (i > 0 && i < chars.length - 1) {
                    if (NumberUtils.isNumber(chars[i - 1]) && NumberUtils.isNumber(chars[i + 1])) {
                        builder.append(ch);
                    }
                }
            } else {
                if (onlyFirstNumber) {
                    if (builder.length() > 0 && ch != ',') {
                        break;
                    }
                }
            }
        }

        return builder.toString();
    }

    /**
     * 从文本中找到第一个数字（整数或浮点数）并返回
     *
     * @param text 待提取的文本
     * @return 文本中第一段连续数字组成的字符串；入参为 {@code null} 或不含数字时返回空字符串
     */
    public static String getFirstNumber(String text) {
        return getNumber(text, true);
    }

}
