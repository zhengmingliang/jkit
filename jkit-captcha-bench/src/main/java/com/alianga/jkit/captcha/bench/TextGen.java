package com.alianga.jkit.captcha.bench;

import java.util.Random;

/**
 * 四类验证码的文本与算式生成。
 *
 * <p>纯英文、纯数字用完整字符表。算术题的期望答案与 jkit-captcha 的
 * {@code ArithmeticEvaluator} 同规则：乘除优先、从左到右、整数除法向零截断、
 * 除数不为 0、减法结果不为负。
 */
public final class TextGen {
    /** 纯英文固定长度。 */
    public static final int LETTER_LENGTH = 4;
    /** 纯数字固定长度。 */
    public static final int DIGIT_LENGTH = 4;
    /** 英文数字混合固定长度。 */
    public static final int MIXED_LENGTH = 4;
    /** 大小写都有的完整字母表。 */
    public static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** 完整数字表。 */
    public static final String DIGITS = "0123456789";
    private static final char[] OPERATORS = {'+', '-', '*', '/'};

    private TextGen() {
    }

    /**
     * 生成固定长度的纯英文。序号 0、1 分别保证至少有一个大写、一个小写，
     * 这样很小的样本里类别也同时包含两种大小写。
     *
     * @param random 随机源
     * @param ordinal 该类里的序号，从 0 起
     * @return 只含字母的固定长度串
     */
    public static String letters(Random random, int ordinal) {
        char[] chars = pick(random, LETTERS, LETTER_LENGTH).toCharArray();
        if (ordinal == 0) {
            chars[0] = firstCase(true);
        } else if (ordinal == 1) {
            chars[0] = firstCase(false);
        }
        return new String(chars);
    }

    /**
     * 生成固定长度的纯数字。
     *
     * @param random 随机源
     * @param ordinal 该类里的序号，从 0 起；目前只用来对齐调用形式
     * @return 数字串
     */
    public static String digits(Random random, int ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal");
        }
        return pick(random, DIGITS, DIGIT_LENGTH);
    }

    /**
     * 生成固定长度的英文数字混合串，至少含一个字母和一个数字。
     *
     * @param random 随机源
     * @param ordinal 该类里的序号，从 0 起
     * @return 混合串
     */
    public static String mixed(Random random, int ordinal) {
        char[] chars = new char[MIXED_LENGTH];
        chars[0] = LETTERS.charAt(random.nextInt(LETTERS.length()));
        chars[1] = DIGITS.charAt(random.nextInt(DIGITS.length()));
        for (int i = 2; i < chars.length; i++) {
            String pool = random.nextBoolean() ? LETTERS : DIGITS;
            chars[i] = pool.charAt(random.nextInt(pool.length()));
        }
        for (int i = chars.length - 1; i > 0; i--) {
            int swap = random.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[swap];
            chars[swap] = tmp;
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal");
        }
        return new String(chars);
    }

    /**
     * 生成一道二元算术题。运算符按序号轮转，四则都会出现，除法约占四分之一。
     *
     * @param random 随机源
     * @param ordinal 该类里的序号，从 0 起
     * @return 算式与结果
     */
    public static Expression arithmetic(Random random, int ordinal) {
        char op = OPERATORS[Math.floorMod(ordinal, OPERATORS.length)];
        int left;
        int right;
        if (op == '-') {
            right = 1 + random.nextInt(12);
            left = right + random.nextInt(20);
        } else if (op == '/') {
            right = 1 + random.nextInt(9);
            int quotient = random.nextInt(9);
            int remainder = random.nextInt(right);
            left = quotient * right + remainder;
        } else if (op == '*') {
            left = 2 + random.nextInt(8);
            right = 2 + random.nextInt(8);
        } else {
            left = 2 + random.nextInt(20);
            right = 1 + random.nextInt(20);
        }
        String text = left + "" + op + right;
        Integer value = evaluate(text);
        if (value == null) {
            throw new IllegalStateException(text);
        }
        return new Expression(text, value.intValue());
    }

    /**
     * 按乘除优先、从左到右、向零截断计算整数算式。除数为 0 或结果为负时返回空。
     *
     * @param text 不含等号问号的算式，例如 {@code 8/3}
     * @return 结果；非法算式返回 {@code null}
     */
    public static Integer evaluate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int[] numbers = new int[8];
        char[] operators = new char[8];
        int numberCount = 0;
        int operatorCount = 0;
        int index = 0;
        while (index < text.length()) {
            int start = index;
            while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                index++;
            }
            if (start == index || numberCount >= numbers.length) {
                return null;
            }
            numbers[numberCount] = parseInt(text, start, index);
            if (numbers[numberCount] < 0) {
                return null;
            }
            numberCount++;
            if (index >= text.length()) {
                break;
            }
            char op = text.charAt(index);
            if (op != '+' && op != '-' && op != '*' && op != '/') {
                return null;
            }
            operators[operatorCount] = op;
            operatorCount++;
            index++;
        }
        if (numberCount < 2 || operatorCount != numberCount - 1) {
            return null;
        }
        int[] terms = new int[numberCount];
        char[] loose = new char[operatorCount];
        int termCount = 0;
        int looseCount = 0;
        int current = numbers[0];
        for (int i = 0; i < operatorCount; i++) {
            char op = operators[i];
            int right = numbers[i + 1];
            if (op == '*' || op == '/') {
                Integer applied = applyTight(current, op, right);
                if (applied == null) {
                    return null;
                }
                current = applied.intValue();
            } else {
                terms[termCount] = current;
                termCount++;
                loose[looseCount] = op;
                looseCount++;
                current = right;
            }
        }
        terms[termCount] = current;
        termCount++;
        int value = terms[0];
        for (int i = 0; i < looseCount; i++) {
            if (loose[i] == '+') {
                value = value + terms[i + 1];
            } else {
                value = value - terms[i + 1];
            }
            if (value < 0) {
                return null;
            }
        }
        return Integer.valueOf(value);
    }

    private static Integer applyTight(int left, char op, int right) {
        if (op == '*') {
            return Integer.valueOf(left * right);
        }
        if (right == 0) {
            return null;
        }
        return Integer.valueOf(left / right);
    }

    private static int parseInt(String text, int start, int end) {
        int value = 0;
        for (int i = start; i < end; i++) {
            value = value * 10 + (text.charAt(i) - '0');
        }
        return value;
    }

    private static char firstCase(boolean upper) {
        for (int i = 0; i < LETTERS.length(); i++) {
            char ch = LETTERS.charAt(i);
            boolean isUpper = ch >= 'A' && ch <= 'Z';
            if (isUpper == upper) {
                return ch;
            }
        }
        return LETTERS.charAt(0);
    }

    private static String pick(Random random, String alphabet, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    /** 一道算术题的式子与结果。 */
    public static final class Expression {
        private final String text;
        private final int value;

        Expression(String text, int value) {
            this.text = text;
            this.value = value;
        }

        /**
         * 画面上的式子，不含 {@code =?}。
         *
         * @return 式子
         */
        public String getText() {
            return text;
        }

        /**
         * 式子按整数规则算出的结果。
         *
         * @return 结果
         */
        public int getValue() {
            return value;
        }
    }
}
