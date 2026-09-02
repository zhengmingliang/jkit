package com.alianga.jkit.yaml;

import com.alianga.jkit.reflect.UnsafeHelper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * YAML 解析器的基础工具类。
 *
 * <p>提供平台换行符、字符访问、类型标记和整数解析等内部通用能力。</p>
 */
class YamlGeneral {
    /** 是否 Windows 操作系统。 */
    public static final boolean IS_WINDOW_OS;

    /** YAML 缩进使用的空格字符。 */
    protected static final char SPACE_CHAR = 32;

    /** YAML 键和值之间的分隔符。 */
    protected static final char SPLIT_CHAR = ':';

    /** YAML 类型标签到内部类型编号的映射。 */
    protected static Map<String, Integer> typeValues = new ConcurrentHashMap<String, Integer>();

    static {
        typeValues.put("str", 1);
        typeValues.put("float", 2);
        typeValues.put("int", 3);
        typeValues.put("bool", 4);
        typeValues.put("binary", 5);
        typeValues.put("timestamp", 6);
        typeValues.put("set", 7);
        typeValues.put("omap", 8);
        typeValues.put("pairs", 8);
        typeValues.put("seq", 9);
        typeValues.put("map", 10);

        IS_WINDOW_OS = System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 获取字符串的字符数组表示。
     *
     * <p>JDK 9 及以上等价于 {@link String#toCharArray()}；JDK 8 及以下直接返回字符串内部的 value 数组，不产生拷贝。</p>
     *
     * @param value 待读取的字符串
     * @return 字符串对应的字符数组
     */
    protected static final char[] getChars(String value) {
        return UnsafeHelper.getChars(value);
    }

    /**
     * 按指定进制解析字符数组中的整数。
     *
     * @param buffers 待读取的字符数组
     * @param fromIndex 起始下标
     * @param len 要解析的字符数
     * @param radix 进制
     * @return 解析后的整数
     * @throws NumberFormatException 输入为空、格式非法或发生溢出时抛出
     * @see Integer#parseInt(String, int)
     */
    protected static final int parseInt(char[] buffers, int fromIndex, int len, int radix)
            throws NumberFormatException {
        if (buffers == null) {
            throw new NumberFormatException("null");
        }

        int result = 0;
        boolean negative = false;
        int i = 0;
        int limit = -Integer.MAX_VALUE;
        int multmin;
        int digit;

        if (len > 0) {
            char firstChar = buffers[fromIndex];
            if (firstChar < '0') { // Possible leading "+" or "-"
                if (firstChar == '-') {
                    negative = true;
                    limit = Integer.MIN_VALUE;
                } else if (firstChar != '+') {
                    throw forInputString(buffers, fromIndex, len, radix);
                }
                if (len == 1) {
                    throw forInputString(buffers, fromIndex, len, radix);
                }
                i++;
            }
            multmin = limit / radix;
            while (i < len) {
                // Accumulating negatively avoids surprises near MAX_VALUE
                digit = Character.digit(buffers[fromIndex + i++], radix);
                if (digit < 0) {
                    throw forInputString(buffers, fromIndex, len, radix);
                }
                if (result < multmin) {
                    throw forInputString(buffers, fromIndex, len, radix);
                }
                result *= radix;
                if (result < limit + digit) {
                    throw forInputString(buffers, fromIndex, len, radix);
                }
                result -= digit;
            }
        } else {
            return 0;
        }
        return negative ? result : -result;
    }

    /**
     * 构造整数解析失败时抛出的异常。
     *
     * @param buffers 待读取的字符数组
     * @param fromIndex 起始下标
     * @param len 要解析的字符数
     * @param radix 进制
     * @return 描述非法输入的 {@link NumberFormatException}
     */
    private static NumberFormatException forInputString(char[] buffers, int fromIndex, int len, int radix) {
        return new NumberFormatException(
                "For input string: \"" + new String(buffers, fromIndex, len) + "\" under radix " + radix);
    }
}
