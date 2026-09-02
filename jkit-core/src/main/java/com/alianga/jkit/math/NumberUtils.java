package com.alianga.jkit.math;

import com.alianga.jkit.StringUtils;
import com.alianga.jkit.common.ScientificMantissaZeroTable;
import com.alianga.jkit.convert.ConvertUtils;
import com.alianga.jkit.json.internal.utils.EnvUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by 郑明亮 on 2021/12/4 16:49.
 */

/**
 * 2021/12/4 16:49 <br>
 *
 * @author 郑明亮
 * @version 1.0
 */
public class NumberUtils {
    /**
     * 保留6位小数
     */
    public static final int SYS_DECIMAL_NUMBER_6 = 6;
    /**
     * 保留18位小数
     */
    public static final int SYS_DECIMAL_NUMBER_18 = 18;

    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("^(0|[1-9]\\d{0,11})\\.(\\d\\d)$"); // 不考虑分隔符的正确性
    private static final char[] RMB_NUMS = "零壹贰叁肆伍陆柒捌玖".toCharArray();
    private static final String[] UNITS = {"元", "角", "分", "整"};
    private static final String[] U1 = {"", "拾", "佰", "仟"};
    private static final String[] U2 = {"", "万", "亿"};

    /**
     * 校验
     *
     * @param regex   表达式
     * @param orginal 参数
     * @return
     */
    private static boolean isMatch(String regex, String orginal) {
        if (orginal == null || orginal.trim().equals("")) {
            return false;
        }
        Pattern pattern = Pattern.compile(regex);
        Matcher isNum = pattern.matcher(orginal);
        return isNum.matches();
    }

    /**
     * 非负整数
     *
     * @param orginal 待校验的字符串
     * @return 由 0-9 组成且非空时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isPositiveInteger(String orginal) {
        return isMatch("^\\d+$", orginal);
    }

    /**
     * 非负浮点数
     *
     * @param orginal 待校验的字符串
     * @return 形如 {@code 12.34}（小数点前后均有数字）时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isPositiveDecimal(String orginal) {
        return isMatch("\\d+\\.\\d+", orginal);
    }

    /**
     * 是否是数字
     *
     * @param ch 字符
     * @return 是数字返回true，否则返回false
     */
    public static boolean isNumber(char ch) {
        return ch >= '0' && ch <= '9';
    }

    /**
     * 是否全部由 0-9 的数字字符组成
     *
     * <p>只逐字符判断，<b>不</b>识别正负号、小数点、科学计数法等写法：
     * {@code "-1"}、{@code "1.5"}、{@code "1e10"} 均返回 {@code false}。
     * 需要判断「能否被解析为 Java 数字」请用 {@link #isParsableNumber(String)}。</p>
     *
     * @param str 待判断的字符串，可为 {@code null}
     * @return 非空且每一位都是 0-9 时返回 {@code true}；{@code null} 或空串返回 {@code false}
     */
    public static boolean isNumber(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return isNumber(str.toCharArray());
    }

    /**
     * 是否全部由 0-9 的数字字符组成
     *
     * @param chars 待判断的字符数组，可为 {@code null}
     * @return 非空且每一位都是 0-9 时返回 {@code true}；{@code null} 或空数组返回 {@code false}
     */
    public static boolean isNumber(char[] chars) {
        if (chars == null || chars.length == 0) {
            return false;
        }
        for (char ch : chars) {
            if (isNotNumber(ch)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否不是数字
     *
     * @param ch 字符
     * @return 不是数字返回true，否则返回false
     */
    public static boolean isNotNumber(char ch) {
        return !isNumber(ch);
    }

    /**
     * 是否不全是 0-9 的数字字符
     *
     * @param str 待判断的字符串，可为 {@code null}
     * @return 存在非数字字符、或为 {@code null}、或为空串时返回 {@code true}
     */
    public static boolean isNotNumber(String str) {
        return !isNumber(str);
    }

    /**
     * 是否可被解析为 Java 数字字面量
     *
     * <p>与 {@link #isNumber(String)} 的区别是本方法按 Java 数字写法整体校验，
     * 识别正负号、小数点、科学计数法、十六进制（{@code 0x} / {@code 0X}）、
     * 八进制前导 0，以及 {@code l L f F d D} 类型后缀：</p>
     *
     * <pre>
     * NumberUtils.isParsableNumber("1")     = true
     * NumberUtils.isParsableNumber("-1")    = true
     * NumberUtils.isParsableNumber("1.5")   = true
     * NumberUtils.isParsableNumber("1e10")  = true
     * NumberUtils.isParsableNumber("0x1F")  = true
     * NumberUtils.isParsableNumber("123L")  = true
     * NumberUtils.isParsableNumber("1.2.3") = false
     * NumberUtils.isParsableNumber("")      = false
     * NumberUtils.isParsableNumber(null)    = false
     * </pre>
     *
     * @param str 待判断的字符串，可为 {@code null}
     * @return 可被解析为数字时返回 {@code true}
     */
    public static boolean isParsableNumber(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        final int length = str.length();
        int start = str.charAt(0) == '-' || str.charAt(0) == '+' ? 1 : 0;
        if (start == length) {
            // 只有一个符号位
            return false;
        }
        if (isHexLiteral(str, start, length)) {
            return true;
        }
        // 末位类型后缀单独处理，避免被当成普通字符
        final int lastIndex = length - 1;
        final char last = str.charAt(lastIndex);
        boolean foundDigit = false;
        boolean hasDecimalPoint = false;
        boolean hasExponent = false;
        boolean allowSign = false;
        int i = start;
        for (; i < lastIndex; i++) {
            final char ch = str.charAt(i);
            if (ch >= '0' && ch <= '9') {
                foundDigit = true;
                allowSign = false;
            } else if (ch == '.') {
                if (hasDecimalPoint || hasExponent) {
                    return false;
                }
                hasDecimalPoint = true;
            } else if (ch == 'e' || ch == 'E') {
                if (hasExponent || !foundDigit) {
                    return false;
                }
                hasExponent = true;
                // 指数部分允许紧跟一个符号位
                allowSign = true;
                foundDigit = false;
            } else if (ch == '+' || ch == '-') {
                if (!allowSign) {
                    return false;
                }
                allowSign = false;
                foundDigit = false;
            } else {
                return false;
            }
        }
        return isValidLastChar(last, foundDigit, hasExponent, allowSign);
    }

    /**
     * 判断是否为合法的十六进制字面量
     *
     * @param str 原始字符串
     * @param start 去掉符号位后的起始下标
     * @param length 字符串长度
     * @return 形如 {@code 0x1F} 且十六进制位非空且合法时返回 {@code true}
     */
    private static boolean isHexLiteral(String str, int start, int length) {
        if (length - start <= 2 || str.charAt(start) != '0') {
            return false;
        }
        final char marker = str.charAt(start + 1);
        if (marker != 'x' && marker != 'X') {
            return false;
        }
        for (int i = start + 2; i < length; i++) {
            final char ch = str.charAt(i);
            boolean hexDigit = (ch >= '0' && ch <= '9')
                    || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!hexDigit) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验数字字面量的最后一个字符
     *
     * @param last 最后一个字符
     * @param foundDigit 此前是否已出现数字位
     * @param hasExponent 是否出现过指数标记
     * @param allowSign 当前位置是否允许出现符号
     * @return 末位合法时返回 {@code true}
     */
    private static boolean isValidLastChar(char last, boolean foundDigit, boolean hasExponent, boolean allowSign) {
        if (last >= '0' && last <= '9') {
            return true;
        }
        if (allowSign) {
            // 以 e+ / e- 结尾，缺少指数数值
            return false;
        }
        if (last == '.') {
            // 形如 "1." 合法，"1e5." 不合法
            return foundDigit && !hasExponent;
        }
        if (last == 'l' || last == 'L') {
            // 整型后缀不允许与小数点或指数共存，这里 hasExponent 为真即非法
            return foundDigit && !hasExponent;
        }
        if (last == 'f' || last == 'F' || last == 'd' || last == 'D') {
            return foundDigit;
        }
        if (last == 'e' || last == 'E') {
            // 以指数标记结尾，缺少指数数值
            return false;
        }
        return false;
    }

    /**
     * 验证是否为货币金额：整数或者小数,可为0
     *
     * @param orginal 待校验的金额字符串
     * @return 是非负整数或非负小数时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isCoinAmount(String orginal) {
        return isPositiveInteger(orginal) || isPositiveDecimal(orginal);
    }

    /**
     * 小数截取
     *
     * @param number 数字
     * @param point  保留几位小数
     * @return 直接截断（不进位）到指定小数位后的字符串，末尾多余的 0 会被去掉
     */
    public static String cutByPoint(String number, int point) {
        return cutByPoint(number, point, true);
    }

    /**
     * 小数截取
     *
     * @param number        数字
     * @param point         保留几位小数
     * @param removeEndZero 是否移除末尾的0
     * @return 直接截断（不进位）到指定小数位后的字符串
     */
    public static String cutByPoint(String number, int point, boolean removeEndZero) {
        String result = new BigDecimal(number).setScale(point, RoundingMode.DOWN).toPlainString();
        if (removeEndZero) {
            result = removeEndZero(result);
        }
        return result;
    }

    /**
     * 保留小数（向上取值）
     *
     * @param number        数字
     * @param point         保留几位小数
     * @param removeEndZero 是否移除末尾的0
     * @return 按远离零方向进位到指定小数位后的字符串
     */
    public static String cutByPointUp(String number, int point, boolean removeEndZero) {
        String result = new BigDecimal(number).setScale(point, RoundingMode.UP).toPlainString();
        if (removeEndZero) {
            result = removeEndZero(result);
        }
        return result;
    }

    /**
     * 保留小数（向上取值）
     *
     * @param number 数字
     * @param point  保留几位小数
     * @return 按远离零方向进位到指定小数位后的字符串，末尾多余的 0 会被去掉
     */
    public static String cutByPointUp(String number, int point) {
        return cutByPointUp(number, point, true);
    }

    /**
     * 去除末尾的0
     *
     * @param amount 金额或数字字符串
     * @return 去掉小数部分末尾 0（以及可能剩余的小数点）后的字符串；不含小数点时原样返回
     */
    public static String removeEndZero(String amount) {
        if (!amount.contains(".")) {
            return amount;
        }
        return amount.replaceAll("0+?$", "").replaceAll("[.]$", "");
    }

    /**
     * 扩展
     *
     * @param number       数字
     * @param point        保留几位小数
     * @param roundingMode 舍位模式 {@code BigDecimal.ROUND_HALF_DOWN、BigDecimal.ROUND_UP...}
     * @return 按指定舍位模式保留小数位后的字符串，末尾多余的 0 会被去掉
     */
    public static String cutByPoint(String number, int point, int roundingMode) {
        String resu = new BigDecimal(number).setScale(point, roundingMode).toPlainString();
        resu = removeEndZero(resu);
        return resu;
    }

    /**
     * 扣除手续费率（乘以费率然后做减法）eg: deductFee(100,0.038) 则相当于 100 - 100 * 0.038
     *
     * @param amount  金额（原价）
     * @param feeRate 费率
     * @return 扣除手续费后的金额，默认保留 6 位小数
     */
    public static String deductFee(String amount, int feeRate) {
        return deductFee(amount, feeRate, SYS_DECIMAL_NUMBER_6);
    }

    /**
     * 扣除手续费率（乘以费率然后做减法）eg: deductFee(100,0.038) 则相当于 100 - 100 * 0.038
     *
     * @param amount  金额（原价）
     * @param feeRate 费率
     * @param point   保留几位小数
     * @return 扣除手续费后的金额
     */
    public static String deductFee(String amount, int feeRate, int point) {
        BigDecimal decimal = new BigDecimal(amount);
        //
        BigDecimal result =
                decimal.subtract(decimal.multiply(new BigDecimal(feeRate)));
        return cutByPoint(result.toPlainString(), point);
    }

    /**
     * 两个金额相减
     *
     * @param num1  被减数，为空时按 {@code "0"} 处理
     * @param num2  减数，为空时按 {@code "0"} 处理
     * @param point 保留几位小数
     * @return num1 - num2
     */
    public static String subtract(String num1, String num2, int point) {
        num1 = ConvertUtils.toNoneEmptyString(num1, "0");
        num2 = ConvertUtils.toNoneEmptyString(num2, "0");

        BigDecimal subtract1Big = new BigDecimal(num1);
        BigDecimal subtract2Big = new BigDecimal(num2);
        String result = cutByPoint(subtract1Big.subtract(subtract2Big).toPlainString(), point);
        return result;
    }

    /**
     * 添加
     *
     * @param add1  加数，为空时按 {@code "0"} 处理
     * @param add2  加数，为空时按 {@code "0"} 处理
     * @param point 保留几位小数
     * @return {@code add1 + add2} 的结果，保留指定小数位
     */
    public static String add(String add1, String add2, int point) {
        add1 = ConvertUtils.toNoneEmptyString(add1, "0");
        add2 = ConvertUtils.toNoneEmptyString(add2, "0");
        BigDecimal add1Big = new BigDecimal(add1);
        BigDecimal add2Big = new BigDecimal(add2);
        String result = cutByPoint(add1Big.add(add2Big).toPlainString(), point);
        return result;
    }

    /**
     * 多个数相加
     *
     * @param point 保留的小数点位数
     * @param nums  多个要相加的数组成的数组
     * @return 所有数字之和，保留指定小数位；数组为 {@code null} 或空时返回 {@code "0"}
     */
    public static String add(int point, String... nums) {
        String result;
        if (nums != null && nums.length > 0) {
            BigDecimal addDecimal = new BigDecimal(0);
            for (String num : nums) {
                num = ConvertUtils.toNoneEmptyString(num, "0");
                BigDecimal decimal = new BigDecimal(num);
                addDecimal = addDecimal.add(decimal);
            }

            result = cutByPoint(addDecimal.toPlainString(), point);
        } else {
            result = "0";
        }

        return result;
    }

    /**
     * 乘法
     *
     * @param num1  被乘数
     * @param num2  乘数
     * @param point 保留几位小数
     * @return {@code num1 * num2} 的结果，保留指定小数位
     */
    public static String multiply(String num1, String num2, int point) {
        BigDecimal multiply1Big = new BigDecimal(num1);
        BigDecimal multiply2Big = new BigDecimal(num2);
        String result = cutByPoint(multiply1Big.multiply(multiply2Big).toPlainString(), point);
        return result;
    }

    /**
     * 除法（最后一位四舍五入）
     *
     * @param num1  被除数
     * @param num2  除数，为 0 时抛出 {@link ArithmeticException}
     * @param point 保留几位小数
     * @return {@code num1 / num2} 的结果，先按 18 位四舍五入再截取到指定小数位
     */
    public static String divide(String num1, String num2, int point) {
        BigDecimal divide1Big = new BigDecimal(num1);
        BigDecimal divide2Big = new BigDecimal(num2);
        String result =
                cutByPoint(divide1Big.divide(divide2Big, SYS_DECIMAL_NUMBER_18, RoundingMode.HALF_UP)
                        .toPlainString(), point);
        return result;
    }

    /**
     * 除法（最后一位向上取整）
     *
     * @param num1  被除数
     * @param num2  除数，为 0 时抛出 {@link ArithmeticException}
     * @param point 保留几位小数
     * @return {@code num1 / num2} 的结果，按远离零方向进位到指定小数位
     */
    public static String divideUp(String num1, String num2, int point) {
        BigDecimal divide1Big = new BigDecimal(num1);
        BigDecimal divide2Big = new BigDecimal(num2);
        String result =
                cutByPointUp(divide1Big.divide(divide2Big, SYS_DECIMAL_NUMBER_18, RoundingMode.UP)
                        .toPlainString(), point);
        return result;
    }

    /**
     * 16进制转成10进制
     *
     * @param hexNumber 16进制的数字
     * @return 对应的十进制数字字符串
     */
    public static String hexToDecimal(String hexNumber) {
        return new BigInteger(hexNumber, 16).toString();
    }

    /**
     * 任意进制转换
     *
     * @param number       数字
     * @param sourceDigits 源数字进制
     * @param targetDigits 目标数字进制
     * @return 目标进制的数字字符串；含小数点时整数部分与小数部分分别转换后再拼接
     */
    public static String hexTransfer(String number, int sourceDigits, int targetDigits) {
        String[] ss = null;
        String returnStr = "";
        if (number.indexOf(".") != -1) {
            ss = number.split("\\.");
            String s1 = new BigInteger(ss[0], sourceDigits).toString(targetDigits);
            String s2 = new BigInteger(ss[1], sourceDigits).toString(targetDigits);
            returnStr = s1 + "." + s2;
        } else {
            returnStr = new BigInteger(number, sourceDigits).toString(targetDigits);
        }
        return returnStr;
    }

    /**
     * 比较两个数大小
     *
     * @param number1 数字字符串
     * @param number2 数字字符串
     * @return {@code number1} 大于 {@code number2} 返回 1，小于返回 -1，相等返回 0
     */
    public static int compare(String number1, String number2) {
        BigDecimal decimal1 = new BigDecimal(number1);
        BigDecimal decimal2 = new BigDecimal(number2);
        return decimal1.compareTo(decimal2);
    }

    /**
     * 数字比较 {@code number1} 大于 {@code number2} 返回1，小于返回 -1 ，等于返回 0，其中任一对象为null则返回 -1
     *
     * @param number1 待比较的数字
     * @param number2 待比较的数字
     * @return 大于返回 1，小于返回 -1，等于返回 0；任一入参为 {@code null} 时返回 -1
     */
    public static int compare(java.lang.Number number1, java.lang.Number number2) {
        if (number1 == null || number2 == null) {
            return -1;
        }

        if (number1 instanceof Long) {
            return Long.compare((long) number1, number2.longValue());
        } else if (number1 instanceof Integer) {
            return Integer.compare((int) number1, number2.intValue());
        } else if (number1 instanceof Double) {
            return Double.compare((double) number1, number2.doubleValue());
        } else if (number1 instanceof Float) {
            return Float.compare((float) number1, number2.floatValue());
        } else if (number1 instanceof BigDecimal) {
            return ((BigDecimal) number1).compareTo(ConvertUtils.toBigDecimal(number2));
        } else {
            return compare(number1.toString(), number2.toString());
        }
    }

    /**
     * 比较两个数绝对值的大小
     *
     * @param number1 数字字符串
     * @param number2 数字字符串
     * @return 前者绝对值较大返回 1，较小返回 -1，相等返回 0
     * @author vefuwell
     */
    public static int compareAbs(String number1, String number2) {
        BigDecimal decimal1 = new BigDecimal(number1).abs();
        BigDecimal decimal2 = new BigDecimal(number2).abs();
        return decimal1.compareTo(decimal2);
    }

    /**
     * 给一个数增加一个step点<br/>
     * <ul>
     * <li>number = 23.112383,step=1,return 23.112384</li>
     * <li>number = 24,step=1,return <em>25</em></li>
     * <li>number = 23.112383,step=-3,return 23.112380</li>
     * </ul>
     *
     * @param number 数字
     * @param step   增加步长，可为负数
     * @return 在最低有效位上增加 step 后的数字字符串；入参为空时返回 {@code null}
     */
    public static String increaseStep(String number, int step) {
        if (StringUtils.isBlank(number)) {
            return null;
        }
        int index = number.indexOf(".");
        int length = number.length();
        BigDecimal decimal;
        if (index != -1) {
            int offset = length - index - 1;
            decimal = new BigDecimal(number).movePointRight(offset).add(new BigDecimal(step)).movePointLeft(offset);
        } else {
            decimal = new BigDecimal(number).add(new BigDecimal(step));
        }

        return decimal.toPlainString();
    }

    /**
     * 求平均数
     *
     * @param number1 数字字符串
     * @param number2 数字字符串
     * @param point   保留小数
     * @return 两数的平均值，保留指定小数位；任一入参为空时返回 {@code null}
     */
    public static String average(String number1, String number2, int point) {
        if (StringUtils.isBlank(number1) || StringUtils.isBlank(number2)) {
            return null;
        }
        String result = new BigDecimal(number1).add(new BigDecimal(number2)).divide(new BigDecimal(2)).toPlainString();
        return cutByPoint(result, point);
    }

    /**
     * 获取不大于的随机数
     *
     * @param max 随机数上限
     * @return 不大于 {@code max} 的随机数，取值范围为 {@code [0, 1)}
     */
    public static double getRandomMax(double max) {
        double random = Math.random();
        if (random > max) {
            random = Math.abs(random * (1 - random));
            if (random > max) {
                return getRandomMax(max);
            }
        }
        return random;
    }

    /**
     * 获取精度（超过18位小数后的精度会被截断）
     *
     * @param price 金额数字
     * @return 小数位数，最多 18 位；不含小数点时返回 0
     */
    public static int getAccuracy(String price) {
        price = cutByPoint(price, SYS_DECIMAL_NUMBER_18);
        if (!price.contains(".")) {
            return 0;
        }
        return price.split("\\.")[1].length();
    }

    /**
     * 获取精度（精度不会被截断）
     *
     * @param price 金额数字
     * @return 小数部分的实际位数；不含小数点时返回 0
     */
    public static int getAccuracyAll(String price) {
        if (!price.contains(".")) {
            return 0;
        }
        return price.split("\\.")[1].length();
    }

    /**
     * 需要将数字格式化为1,000,000.00的形式（可调用）
     * 将金额（整数部分等于或少于12位，小数部分2位）转换为中文大写形式.
     *
     * @param amount 金额数字
     * @return 中文大写
     * @throws IllegalArgumentException 金额为零或格式不合法（整数部分超过 12 位、小数部分不是 2 位）时抛出
     * @since 1.3.7
     */
    public static String amount2rmb(String amount) throws IllegalArgumentException {
        // 如果有带逗号的分隔符则去掉分隔符。否则返回原值
        amount = amount.contains(",") ? amount.replace(",", "") : amount;
        //判断是否输入小数点，如果没有输入小数点，则进行格式化
        amount = amount.contains(".") ? amount : (amount + ".00");
        // 如果是小数点以后为1位，则在末尾添加个0，凑齐两位小数
        // String temp = amount.substring(amount.indexOf(".")+1, amount.length())
        // amount = temp.length() == 1?amount+"0":amount;

        // 验证金额正确性
        if (amount.equals("0.00")) {
            throw new IllegalArgumentException("金额不能为零.");
        }
        Matcher matcher = AMOUNT_PATTERN.matcher(amount);
        if (!matcher.find()) {
            throw new IllegalArgumentException("输入金额有误.");
        }

        String integer = matcher.group(1); // 整数部分
        String fraction = matcher.group(2); // 小数部分

        String result = "";
        if (!integer.equals("0")) {
            result += integer2rmb(integer) + UNITS[0]; // 整数部分
        }
        if (fraction.equals("00")) {
            result += UNITS[3]; // 添加[整]
        } else if (fraction.startsWith("0") && integer.equals("0")) {
            result += fraction2rmb(fraction).substring(1); // 去掉分前面的[零]
        } else {
            result += fraction2rmb(fraction); // 小数部分
        }

        return result;
    }

    // 将金额小数部分转换为中文大写
    private static String fraction2rmb(String fraction) {
        char jiao = fraction.charAt(0); // 角
        char fen = fraction.charAt(1); // 分
        return (RMB_NUMS[jiao - '0'] + (jiao > '0' ? UNITS[1] : ""))
                + (fen > '0' ? RMB_NUMS[fen - '0'] + UNITS[2] : "");
    }

    // 将金额整数部分转换为中文大写
    private static String integer2rmb(String integer) {
        StringBuilder buffer = new StringBuilder();
        // 从个位数开始转换
        int i;
        int j;
        for (i = integer.length() - 1, j = 0; i >= 0; i--, j++) {
            char n = integer.charAt(i);
            if (n == '0') {
                // 当n是0且n的右边一位不是0时，插入[零]
                if (i < integer.length() - 1 && integer.charAt(i + 1) != '0') {
                    buffer.append(RMB_NUMS[0]);
                }
                // 插入[万]或者[亿]
                if (j % 4 == 0) {
                    if (i > 0 && integer.charAt(i - 1) != '0'
                            || i > 1 && integer.charAt(i - 2) != '0'
                            || i > 2 && integer.charAt(i - 3) != '0') {
                        buffer.append(U2[j / 4]);
                    }
                }
            } else {
                if (j % 4 == 0) {
                    buffer.append(U2[j / 4]); // 插入[万]或者[亿]
                }
                buffer.append(U1[j % 4]); // 插入[拾]、[佰]或[仟]
                buffer.append(RMB_NUMS[n - '0']); // 插入数字
            }
        }
        return buffer.reverse().toString();
    }

    /**
     * 将数据格式化为 ###,###.00
     *
     * @param decimal 待格式化的数字
     * @return 带千位分隔符、保留两位小数的字符串
     */
    public static String formatNumber(BigDecimal decimal) {
        DecimalFormat formate = new DecimalFormat("###,###.00");
        String num = formate.format(decimal);

        return num;
    }

    /**
     * @param num      要进行四舍五入的数字
     * @param pointNum 要保留几位小数
     * @return 四舍五入后保留 {@code pointNum} 位小数的字符串
     * @author 郑明亮
     * @time 2017年4月3日12:06:45
     * @description <p>四舍五入<br>
     */
    public static String getRoundNum(double num, Integer pointNum) {
        StringBuilder point = new StringBuilder();
        DecimalFormat format = new DecimalFormat("#." + "00");
        if (StringUtils.isBlank(point)) {
            for (int i = 0; i < pointNum; i++) {
                point.append("0");
            }
            format = new DecimalFormat("#." + point);
        }

        return format.format(num);
    }

    /**
     * 四舍五入，默认保留两位小数
     *
     * @param num 数字
     * @return 处理后的数字
     */
    public static String getRoundNum(double num) {
        return getRoundNum(num, 2);
    }

    // ===== Migrated from json.internal.utils.NumberUtils =====

    static final double[] POSITIVE_DECIMAL_POWER = new double[325];
    static final double[] NEGATIVE_DECIMAL_POWER = new double[325];
    static final long[] POW10_LONG_VALUES = new long[]{
            10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000, 1000000000, 10000000000L, 100000000000L,
            1000000000000L, 10000000000000L, 100000000000000L, 1000000000000000L, 10000000000000000L,
            100000000000000000L, 1000000000000000000L, 9223372036854775807L
    };

    //0-9a-f
    static final char[] HEX_DIGITS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

//    final static byte[] HEX_DIGITS_REVERSE = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
//    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0, 0,
//    0, 0, 0, 0, 0, 10, 11, 12, 13, 14, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
//    0, 0, 10, 11, 12, 13, 14, 15};

    static final char[] DigitOnes = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    };

    static final char[] DigitTens = {
            '0', '0', '0', '0', '0', '0', '0', '0', '0', '0',
            '1', '1', '1', '1', '1', '1', '1', '1', '1', '1',
            '2', '2', '2', '2', '2', '2', '2', '2', '2', '2',
            '3', '3', '3', '3', '3', '3', '3', '3', '3', '3',
            '4', '4', '4', '4', '4', '4', '4', '4', '4', '4',
            '5', '5', '5', '5', '5', '5', '5', '5', '5', '5',
            '6', '6', '6', '6', '6', '6', '6', '6', '6', '6',
            '7', '7', '7', '7', '7', '7', '7', '7', '7', '7',
            '8', '8', '8', '8', '8', '8', '8', '8', '8', '8',
            '9', '9', '9', '9', '9', '9', '9', '9', '9', '9',
    };

    static final long[] POW5_LONG_VALUES = new long[27];
//    final static BigInteger[] POW5_BI_VALUES = new BigInteger[343];

    static {
        // e0 ~ e360(e306)
        for (int i = 0, len = POSITIVE_DECIMAL_POWER.length; i < len; ++i) {
            POSITIVE_DECIMAL_POWER[i] = Double.parseDouble("1.0E" + i);
            NEGATIVE_DECIMAL_POWER[i] = Double.parseDouble("1.0E-" + i);
        }
        // 4.9e-324
        NEGATIVE_DECIMAL_POWER[NEGATIVE_DECIMAL_POWER.length - 1] = Double.MIN_VALUE;

        long val = 1;
        for (int i = 0; i < POW5_LONG_VALUES.length; ++i) {
            POW5_LONG_VALUES[i] = val;
            val *= 5;
        }
    }

    static final int MOD_DOUBLE_EXP = (1 << 11) - 1;
    static final int MOD_FLOAT_EXP = (1 << 8) - 1;
    static final long MOD_DOUBLE_MANTISSA = (1L << 52) - 1;
    static final int MOD_FLOAT_MANTISSA = (1 << 23) - 1;

    //    static final long MASK_32_BITS = 0xffffffffL;
    /**
     * 复制个位数字符表，避免外部直接修改内部数组。
     *
     * @return {@code DigitOnes} 表的副本，下标为 0~99，值为该数的个位字符
     */
    public static char[] copyDigitOnes() {
        return Arrays.copyOf(DigitOnes, DigitOnes.length);
    }

    /**
     * 复制十位数字符表，避免外部直接修改内部数组。
     *
     * @return {@code DigitTens} 表的副本，下标为 0~99，值为该数的十位字符
     */
    public static char[] copyDigitTens() {
        return Arrays.copyOf(DigitTens, DigitTens.length);
    }

    /**
     * 获取以10为底数的指定数值（-1 &lt; expValue &gt; 310）指数值,
     *
     * @param expValue ensure expValue &gt;= 0
     * @return {@code 10} 的 {@code expValue} 次幂，超出预置表范围时用 {@link Math#pow} 计算
     */
    public static double getDecimalPowerValue(int expValue) {
        if (expValue < POSITIVE_DECIMAL_POWER.length) {
            return POSITIVE_DECIMAL_POWER[expValue];
        }
        return Math.pow(10, expValue);
    }

    /**
     * 获取long值的字符串长度
     *
     * @param value 待计算的 long 值
     * @return 该值转为十进制字符串的字符个数，负数包含符号位
     */
    public static int stringSize(long value) {
        int i = 1;
        if (value < 0) {
            ++i;
            value = -value;
        }
        for (long val : POW10_LONG_VALUES) {
            if (value < val) {
                return i;
            }
            ++i;
        }
        return 19;
    }

    /**
     * 十进制字符转数字
     *
     * @param ch 字符值（非数字）
     * @return 字符对应的十进制数字（0~9）；非数字字符返回 {@code -1}
     */
    public static int digitDecimal(int ch) {
        switch (ch) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9': {
                return ch - '0';
            }
            default: {
                return -1;
            }
        }
    }

    /**
     * 判断一个字符或者字节是否为数字(48-57)
     *
     * @param c 待判断的字符或字节值
     * @return 是 {@code '0'} ~ {@code '9'} 时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isDigit(int c) {
        switch (c) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                return true;
            default:
                return false;
        }
    }

    /**
     * 字符转数字
     *
     * @param c 待转换的字符或字节值
     * @return 对应的数字（0~9）；非数字字符返回 {@code -1}
     */
    public static int digit(int c) {
        switch (c) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                return c & 0xF;
            default:
                return -1;
        }
    }

    /**
     * 转化为4位十进制数int数字
     *
     * @param buf       源字符数组
     * @param fromIndex 读取的起始位置
     * @return 由连续 4 个字符组成的 4 位十进制数
     */
    public static int parseInt4(char[] buf, int fromIndex)
            throws NumberFormatException {
        return parseInt4(buf[fromIndex++], buf[fromIndex++], buf[fromIndex++], buf[fromIndex]);
    }

    /**
     * 转化为4位十进制数int数字
     *
     * @param buf    源字节数组
     * @param offset 读取的起始位置
     * @return 4位十进制数
     */
    public static int parseInt4(byte[] buf, int offset)
            throws NumberFormatException {
        return parseInt4(buf[offset++], buf[offset++], buf[offset++], buf[offset]);
    }

    /**
     * 转化为4位十进制数
     *
     * @param c1 千位
     * @param c2 百位
     * @param c3 十位
     * @param c4 个位
     * @return 4 位十进制数；任一字符不是数字时抛出 {@link NumberFormatException}
     */
    public static int parseInt4(int c1, int c2, int c3, int c4) {
        int v1 = digitDecimal(c1);
        int v2 = digitDecimal(c2);
        int v3 = digitDecimal(c3);
        int v4 = digitDecimal(c4);
        if ((v1 | v2 | v3 | v4) == -1) {
            throw new NumberFormatException(
                    "For input string: \"" + new String(new char[]{(char) c1, (char) c2, (char) c3, (char) c4}) + "\"");
        }
        return v1 * 1000 + v2 * 100 + v3 * 10 + v4;
    }

    /**
     * 转化为2位十进制数
     *
     * @param buf    源字节数组
     * @param offset 读取的起始位置
     * @return 转换后的数字
     */
    public static int parseInt2(char[] buf, int offset)
            throws NumberFormatException {
        return parseInt2(buf[offset++], buf[offset]);
    }

    /**
     * 转化为2位十进制数
     *
     * @param buf    源字节数组
     * @param offset 读取的起始位置
     * @return 转换后的数字
     */
    public static int parseInt2(byte[] buf, int offset)
            throws NumberFormatException {
        return parseInt2(buf[offset++], buf[offset]);
    }

    /**
     * 转化为2位十进制数
     *
     * @param c1 十位
     * @param c2 个位
     * @return 转换后的数字
     * @throws NumberFormatException 输入的字符串不是数字
     */
    public static int parseInt2(int c1, int c2)
            throws NumberFormatException {
        int v1 = digitDecimal(c1);
        int v2 = digitDecimal(c2);
        if ((v1 | v2) == -1) {
            throw new NumberFormatException(
                    "For input string: \"" + new String(new char[]{(char) c1, (char) c2}) + "\"");
        }
        return v1 * 10 + v2;
    }

    /**
     * 转化为1位int数字
     *
     * @param buf    源字节数组
     * @param offset 读取的起始位置
     * @return 1位数字
     */
    public static int parseInt1(char[] buf, int offset)
            throws NumberFormatException {
        int v1 = digitDecimal(buf[offset]);
        if (v1 == -1) {
            throw new NumberFormatException("For input string: \"" + new String(buf, offset, 1) + "\"");
        }
        return v1;
    }

    /**
     * 转化为1位int数字
     *
     * @param buf    源字节数组
     * @param offset 读取的起始位置
     * @return 1位数字
     */
    public static int parseInt1(byte[] buf, int offset)
            throws NumberFormatException {
        int v1 = digitDecimal(buf[offset]);
        if (v1 == -1) {
            throw new NumberFormatException("For input string: \"" + new String(buf, offset, 1) + "\"");
        }
        return v1;
    }

    /**
     * 将long类型的value转为长度为16的16进制字符串,缺省补字符0
     *
     * @param value long value
     * @return 16进制字符串
     * @see Long#toHexString(long)
     */
    public static String toHexString16(long value) {
        char[] chars = new char[16];
        for (int i = 15; i > -1; --i) {
            int val = (int) (value & 0xf);
            chars[i] = HEX_DIGITS[val];
            value >>= 4L;
        }
        return new String(chars);
    }

    /**
     * <p> Convert to double through 64 bit integer val and precision scale -> val / 10^scale;</p>
     *
     * <p> to simplify the code, code blocks that are considered to have an extremely low probability of occurrence
     * have been temporarily removed (due to the inability to fully test within the double range).</p>
     * <p> if any errors(up to one bit error) occur as a result, please provide the relevant parameters(the val and
     * scale) and contact me(shallxiao@126.com).</p>
     *
     * <p>
     * 注: 之前的版本缓存了一个BigInteger数组（POW5_BI_VALUES）存储5的指数幂，能确保结果100%正确. 考虑到占用内存有点大且只有极小概率可达就删除了. <br/>
     * 简化后的代码通常情况下结果是正确的，如果有发现错误（一个bit的误差）请发送到我的邮箱(shallxiao@126.com)
     * </p>
     *
     * <p> IEEEF Double 64 bits: 1 + 11 + 52 </p>
     * <p> ensure the parameter val is greater than 0, otherwise return 0 </p>
     *
     * @param val   value
     * @param scale precision
     * @return the double value of {@code val / 10^scale}; returns {@code 0.0} if {@code val} is less than 1
     *         (except for {@link Long#MIN_VALUE}, whose absolute value is used)
     */
    public static double scientificToIEEEDouble(long val, int scale) {
        if (val < 1) {
            if (val == Long.MIN_VALUE) {
                val = 9223372036854775807L;
            } else {
                return 0.0D;
            }
        }
        int leadingZeros = Long.numberOfLeadingZeros(val);
        long y;
        long y32;
        long e0;
        if (scale < 1) {
            if (scale == 0) {
                return val;
            }
            if (scale > -23 && val < 9007199254740993L) {
                return val * POSITIVE_DECIMAL_POWER[-scale]; // 1L << 53
            }
            if (scale < -308) {
                return Double.POSITIVE_INFINITY;
            }
            PowerOf5Table ed5 = PowerOf5Table.TABLE[-scale];
            y = ed5.y;
            y32 = ed5.f + 1;
            e0 = 1140 + ed5.dfb;
        } else {
            if (scale > 342) {
                return 0.0D;
            }
            PowerOf5Table ed5 = PowerOf5Table.TABLE[scale];
            y = ed5.oy;
            y32 = ed5.of + 1;
            e0 = 1108 - ed5.ob;
        }
        long x = val << (leadingZeros - 1);
        long h = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(x, y); // h is 61~62 bits
        long l;
        int sr = h > 0x1fffffffffffffffL ? 9 : 8;
        long e2 = e0 - scale - leadingZeros + sr; // e52 + 1075
        if (e2 < 1) {
            if ((sr += 1 - (int) e2) > 61) {
                return 0.0D;
            }
            e2 = 0;
        } else if (e2 > 2046) {
            return Double.POSITIVE_INFINITY;
        }
        long mmask = (1L << sr) - 1;
        long mask = mmask >> 1;
        if ((h & mmask) != mask || (l = x * y) > 0 || !checkLowCarry(l, x, y32)) {
            return longBitsToDouble(h, e2, sr);
        }
        return longBitsToDouble(h + 1, e2, sr);
    }

    // check whether a carry-over may occur in the lower bits
    private static boolean checkLowCarry(long l, long x, long y32) {
        return l + ((EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(x, y32) << 32) + ((x * y32) >>> 32)) > -1;
    }

    static double longBitsToDouble(long l62, long e2, int sr) {
        long mantissa0 = ((l62 >>> sr - 1) + 1) >> 1;
        if (mantissa0 == 1L << 53) {
            mantissa0 = 1L << 52;
            ++e2;
        }
        long bits = (e2 << 52) | (mantissa0 & MOD_DOUBLE_MANTISSA);
        return Double.longBitsToDouble(bits);
    }

    /**
     * Convert to float through 64 bit integer val and precision scale -> val / 10^scale
     *
     * <p> IEEEF Float 32 bits: 1 + 8 + 23 </p>
     * <p> cleverly convert using the double bit structure </p>
     * <p> IEEEF Double: m1 * 2^n1 </p>
     * <p> IEEEF Float : (m1 >> 29) * 2^(n1 - 1023 + 127) </p>
     * <p> ensure the parameter val is greater than 0, otherwise return 0 </p>
     * <pre>{@code
     * long doubleBits = Double.doubleToLongBits(dv);
     * long mantissa0 = (doubleBits & MOD_DOUBLE_MANTISSA);
     * int doubleE2 = (int) (doubleBits >> 52) & MOD_DOUBLE_EXP;
     *
     * int floatE2 = doubleE2 - 1023 + 127;
     * long floatMantissa = mantissa0 >> 29;
     * int floatBits = (int) ((floatE2 << 23) | floatMantissa);
     * float result = Float.intBitsToFloat(floatBits);
     * }</pre>
     *
     * @param val   ensure val > 0, otherwise return 0
     * @param scale the scale of the number
     * @return float
     */
    public static float scientificToIEEEFloat(long val, int scale) {
        if (val < 1) {
            return 0.0f;
        }
        double dv;
        float val0;
        if (scale < 1) {
            if (scale == 0) {
                return (float) val;
            }
            int e10 = -scale;
            if (e10 > 38) {
                return Float.POSITIVE_INFINITY;
            }
            dv = val * NumberUtils.getDecimalPowerValue(e10);
            val0 = (float) dv;
        } else {
            if (scale > 63) {
                return 0.0F;
            }
            dv = val / NumberUtils.getDecimalPowerValue(scale);
            val0 = (float) dv;
        }
        return val0;
    }

    /**
     * multiplyOutput
     *
     * @param x     > 0
     * @param y     > 0
     * @param shift > 0
     */
    static long multiplyHighAndShift(long x, long y, int shift) {
        long H = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(x, y);
        if (shift >= 64) {
            int sr = shift - 64;
            return H >>> sr;
        }
        long L = x * y;
        return H << (64 - shift) | (L >>> shift);
    }

    /**
     * Unsigned algorithm: (H * 2^64 * 2^n + L * 2^n + H1 * 2^64 + L1) / 2^(n+s) = (H * 2^64 + L + (H1 * 2^64 + L1) /
     * 2^n) / 2^s
     * <p>
     * use BigInteger: BigInteger.valueOf(pd.y).shiftLeft(n).add(BigInteger.valueOf(f & MASK_32_BITS)).multiply
     * (BigInteger.valueOf(x)).shiftRight(s + n).longValue()
     *
     * @param x   63bits
     * @param y   63bits
     * @param y32 unsigned int32 f(32bits)
     * @param s   s > 0
     */
    static long multiplyHighAndShift(long x, long y, long y32, int s) {
        // -> BigInteger.valueOf(y).shiftLeft(32).add(BigInteger.valueOf(y32 & MASK_32_BITS)).multiply(BigInteger
        // .valueOf(x)).shiftRight(s + 32)
        int sr = s - 64;
        long H = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(x, y);
        long L = x * y;

        // cal x * f -> H1, L1
        long H1 = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(x, y32);
        long L1 = x * y32;

        // carry = (H1 * 2^64 + L1) / 2^n
        long carry = (H1 << 32) + (L1 >>> 32);
        long L2 = L + carry;
        if ((L | carry) < 0 && ((L & carry) < 0 || L2 >= 0)) {
            ++H;
        }
        L = L2;
        if (sr >= 0) {
            return H >>> sr;
        }
        return H << -sr | (L >>> s);
    }

    /**
     * Conversion of ieee floating point numbers to Scientific notation
     *
     * <p> Using the difference estimation method </p>
     * <p> The output may not be the shortest, but the general result is correct </p>
     *
     * @param doubleValue best to ensure that the doubleValue > 0 before call.
     *                    otherwise the absolute value of Scientific notation will be returned if doubleValue &lt; 0.
     *                    if doubleValue is NaN or POSIFINETY or NEGATIVE-INFINETY, it will directly return
     *                    SCIENTIFIC_NULL
     *                    if doubleValue == 0, return SCIENTIFIC_ZERO or SCIENTIFIC_NEGATIVE_ZERO
     * @return the {@link Scientific} notation (significand, digit length and decimal exponent) of the given double
     */
    public static Scientific doubleToScientific(double doubleValue) {
        if (doubleValue == Double.MIN_VALUE) {
            // Double.MIN_VALUE JDK转化最小double为4.9e-324， 本方法转化为5.0e-324 , 由于此值特殊为了和JDK转化一致
            return Scientific.DOUBLE_MIN;
        }
        long bits = Double.doubleToRawLongBits(doubleValue);
        int e2 = (int) (bits >> 52) & MOD_DOUBLE_EXP;
        long mantissa0 = bits & MOD_DOUBLE_MANTISSA;
        if (mantissa0 == 0) {
            return bits != 0x8000000000000000L ? ScientificMantissaZeroTable.DOUBLE_MANTISSA_ZERO_TABLE[e2] :
                    Scientific.NEGATIVE_ZERO;
        }
        int e52;
        long output;
        long rawOutput; /*d2, */
        long d3;
        long d4;
        int e10;
        int adl;
        if (e2 > 0) {
            // Double.NaN/Double.POSITIVE_INFINITY/Double.NEGATIVE_INFINITY -> NULL
            if (e2 == 2047) {
                return Scientific.SCIENTIFIC_NULL;
            }
            mantissa0 = 1L << 52 | mantissa0;
            e52 = e2 - 1075;
        } else {
            int lz52 = Long.numberOfLeadingZeros(mantissa0) - 11;
            mantissa0 <<= lz52;
            e52 = -1074 - lz52;
        }
        // boolean /*tflag = true,*/ accurate = false;
        if (e52 >= 0) {
            DoubleExponentData d = DoubleExponentData.E2_D_A[e52];
            e10 = d.e10; // e10 > 15
            adl = d.adl;
            // d2 = d.d2;
            d3 = d.d3;
            d4 = d.d4;
            if (d.b && mantissa0 >= d.bv) {
                if (mantissa0 > d.bv) {
                    ++e10;
                    ++adl;
                } else {
                    if (doubleValue == POSITIVE_DECIMAL_POWER[e10 + 1]) {
                        return new Scientific(e10 + 1, true);
                    }
                }
            }
            int o5 = d.o5; // adl + 2 - e10
            int sb = e52 + o5;
            if (o5 < 0) {
                // mantissa0 * 2^(e52 + o5) * 5^o5 -> mantissa0 * 2^sb / 5^(-o5)
                PowerOf5Table d5 = PowerOf5Table.TABLE[-o5];
                int rb = sb - 10 - d5.ob;
                // rawOutput = BigInteger.valueOf(mantissa0).shiftLeft(sb).divide(POW5_BI_VALUES[-o5]).longValue();
                rawOutput = multiplyHighAndShift(mantissa0 << 10, d5.oy, d5.of, 32 - rb);
                // accurate = o5 == -1 && sb < 11;
            } else {
                // o5 > 0 -> sb > 0
                // accurate
                rawOutput = (mantissa0 * POW5_LONG_VALUES[o5]) << sb;
                // accurate = true;
            }
        } else {
            // e52 >= -1074 -> p5 <= 1074
            int e5 = -e52;
            DoubleExponentData d = DoubleExponentData.E5_D_A[e5];
            e10 = d.e10;
            adl = d.adl;
            // d2 = d.d2;
            d3 = d.d3;
            d4 = d.d4;
            if (d.b && mantissa0 >= d.bv) {
                if (mantissa0 > d.bv) {
                    ++e10;
                    ++adl;
                } else {
                    if (e10 >= -1 && doubleValue == POSITIVE_DECIMAL_POWER[e10 + 1]) {
                        return new Scientific(e10 + 1, true);
                    }
                    if (e10 < -1 && doubleValue == NEGATIVE_DECIMAL_POWER[-e10 - 1]) {
                        return new Scientific(e10 + 1, true);
                    }
                }
            }

            int o5 = d.o5; // adl + 2 - e10; // o5 > 0
            int sb = o5 + e52;
            if (sb < 0) {
                if (o5 < POW5_LONG_VALUES.length) {
                    rawOutput = multiplyHighAndShift(mantissa0, POW5_LONG_VALUES[o5], -sb);
                } else if (o5 < POW5_LONG_VALUES.length + 4) {
                    rawOutput = multiplyHighAndShift(mantissa0 * POW5_LONG_VALUES[o5 - POW5_LONG_VALUES.length + 1],
                            POW5_LONG_VALUES[POW5_LONG_VALUES.length - 1], -sb);
                } else {
                    PowerOf5Table ed5 = PowerOf5Table.TABLE[o5];
                    rawOutput = multiplyHighAndShift(mantissa0 << 10, ed5.y, ed5.f, -(ed5.dfb + sb) + 10);
                }
            } else {
                rawOutput = POW5_LONG_VALUES[o5] * mantissa0 << sb;
            }
        }
        // rem <= Actual Rem Value
//        long div = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(rawOutput, 0x4189374bc6a7ef9eL) >> 8; //
//        rawOutput / 1000;
        long div = rawOutput / 1000;
        long rem = rawOutput - div * 1000;
        boolean down;
        if ((down = ((rem + 1) << 1) <= d3) || ((10001 - rem * 10) << 1) <= d4) {
            output = div + (down ? 0 : 1);
            return new Scientific(output, adl, e10);
        } else {
            int scale = -e10 + adl - 1;
            if (scientificToIEEEDouble(output = rem > 500 ? div + 1 : div, scale) == doubleValue) {
                return new Scientific(output, adl, e10);
            }
            output = (rawOutput + 50) / 100;
            return new Scientific(output, adl + 1, e10);
        }
    }

    /**
     * ieee float to Scientific notation.
     * better to ensure that floatValue>0.
     * <p>
     * if floatValue is less than 0, the absolute value of Scientific notation will be return
     *
     * @param floatValue != 0
     * @return the {@link Scientific} notation (significand, digit length and decimal exponent) of the given float
     */
    public static Scientific floatToScientific(float floatValue) {
        final int bits = Float.floatToRawIntBits(floatValue);
        int e2 = (bits >> 23) & MOD_FLOAT_EXP;
        int mantissa0 = bits & MOD_FLOAT_MANTISSA;
        if (mantissa0 == 0) {
            return bits != 0x80000000 ? ScientificMantissaZeroTable.FLOAT_MANTISSA_ZERO_TABLE[e2] :
                    Scientific.NEGATIVE_ZERO;
        }
        int e23;
        long output;
        long rawOutput;
        long d4;
        int e10;
        int adl;
        if (e2 > 0) {
            if (e2 == MOD_FLOAT_EXP) {
                return Scientific.SCIENTIFIC_NULL;
            }
            mantissa0 = 1 << 23 | mantissa0;
            e23 = e2 - 150; // - 127 - 23
        } else {
            int l = Integer.numberOfLeadingZeros(mantissa0) - 8;
            mantissa0 <<= l;
            e23 = -149 - l;
        }
        if (e23 >= 0) {
            DoubleExponentData d = FloatExponentData.E2_F_A[e23];
            e10 = d.e10;
            adl = d.adl;
            d4 = d.d4;
            if (d.b && mantissa0 > d.bv) {
                ++e10;
                ++adl;
            }
            int o5 = d.o5 + 6; // 相对double(adl + 2 - e10)多加6个数字增加命中概率
            int sb = e23 + o5;
            if (o5 < 0) {
                // mantissa0 * 2^(e23 + o5) * 5^o5 -> mantissa0 * 2^sb / 5^(-o5)
                // rawOutput = BigInteger.valueOf(mantissa0).shiftLeft(sb).divide(POW5_BI_VALUES[-o5]).longValue();
                if (sb < 40) {
                    rawOutput = ((long) mantissa0 << sb) / POW5_LONG_VALUES[-o5];
                } else {
                    PowerOf5Table d5 = PowerOf5Table.TABLE[-o5];
                    rawOutput = multiplyHighAndShift((long) mantissa0 << 39, d5.oy, d5.of, 71 + d5.ob - sb);
                }
            } else {
                // o5 > 0 -> sb > 0
                // accurate
                rawOutput = mantissa0 * POW5_LONG_VALUES[o5] << sb;
            }
        } else {
            // e52 >= -149 -> p5 <= 149
            int e5 = -e23;
            DoubleExponentData d = FloatExponentData.E5_F_A[e5];
            e10 = d.e10;
            adl = d.adl;
            d4 = d.d4;
            if (d.b && mantissa0 > d.bv) {
                ++e10;
                ++adl;
            }
            int o5 = d.o5 + 6; // 相对double(adl + 2 - e10)多加6个数字增加命中概率
            int sb = o5 + e23;
            if (sb < 0) {
                // todo To be optimized
                if (o5 < 17) {
                    rawOutput = mantissa0 * POW5_LONG_VALUES[o5] >> -sb;
                } else if (o5 < POW5_LONG_VALUES.length) {
                    rawOutput = multiplyHighAndShift(mantissa0, POW5_LONG_VALUES[o5], -sb);
                } else if (o5 < POW5_LONG_VALUES.length + 4) {
                    rawOutput = multiplyHighAndShift(mantissa0 * POW5_LONG_VALUES[o5 - POW5_LONG_VALUES.length + 1],
                            POW5_LONG_VALUES[POW5_LONG_VALUES.length - 1], -sb);
                } else {
                    PowerOf5Table ed5 = PowerOf5Table.TABLE[o5];
                    rawOutput = multiplyHighAndShift((long) mantissa0 << 39, ed5.y, ed5.f,
                            -(ed5.dfb + sb) + 39); // 39 = 63 - 24
                }
            } else {
                rawOutput = POW5_LONG_VALUES[o5] * mantissa0 << sb;
            }
        }
        if (rawOutput < 1000000000) {
            return new Scientific(
                    EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(rawOutput, 0x6b5fca6af2bd215fL) >> 22, 2,
                    e10); // rawOutput / 10000000
        }
        long div = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(rawOutput, 0x44b82fa09b5a52ccL) >>
                28; // rawOutput / 1000000000;
        long rem = rawOutput - div * 1000000000;
        long remUp = (1000000001 - rem) << 1;
        boolean up = remUp <= d4;
        if (up || ((rem + 1) << 1) <= d4) {
            output = div + (up ? 1 : 0);
            if (up && POW10_LONG_VALUES[adl - 1] == output) {
                return new Scientific(1, 1, e10 + 1);
            }
            return new Scientific(output, adl, e10);
        } else {
            int scale = -e10 + adl - 1;
            if (scientificToIEEEFloat(output = rem > 500000000 ? div + 1 : div, scale) ==
                    floatValue /*|| scientificToIEEEFloat(output = div, scale) == floatValue*/) {
                return new Scientific(output, adl, e10);
            }
            long div0 = EnvUtils.JDK_AGENT_INSTANCE.multiplyHighKaratsuba(rawOutput, 0x55e63b88c230e77fL) >>
                    25; // rawOutput / 100000000
            output = div0 + (rem % 100000000 >= 50000000 ? 1 : 0);
            return new Scientific(output, adl + 1, e10);
        }
    }

    /**
     * 判断 long 值与十进制数字文本是否表示同一个数值。
     *
     * @param val  待比较的 long 值
     * @param text 十进制数字文本，可带前导负号
     * @return 数值相等时返回 {@code true}；文本为 {@code null}、空串或含非数字字符时返回 {@code false}
     */
    public static boolean equals(long val, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (val == Long.MIN_VALUE) {
            return text.equals("-9223372036854775808");
        }
        long result = 0;
        long len = text.length();
        int i = 0;
        if (text.charAt(0) == '-') {
            ++i;
            val = -val;
        }
        for (; i < len; ++i) {
            int d = digitDecimal(text.charAt(i));
            if (d == -1) {
                return false;
            }
            result = result * 10 + d;
        }
        return val == result;
    }
}
