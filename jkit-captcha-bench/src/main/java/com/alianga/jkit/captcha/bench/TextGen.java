package com.alianga.jkit.captcha.bench;

import java.util.Random;

/**
 * 四类验证码的文本与算式生成。
 *
 * <p>算术题的期望答案是 {@code expression} 的计算结果，与 jkit-captcha 的
 * {@code ArithmeticEvaluator} 同规则：乘除优先、从左到右、整数除法截断、不出负数。
 */
public final class TextGen {
    /** 大小写混合的英文字母表。 */
    public static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** 数字表。 */
    public static final String DIGITS = "0123456789";
    /** 英文数字混合表。 */
    public static final String MIXED = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private static final String OPERATORS = "+-*";

    private TextGen() {
    }

    /**
     * 生成纯英文文本。
     *
     * @param random 随机源
     * @param length 长度
     * @return 大小写混合的英文串
     */
    public static String letters(Random random, int length) {
        return pick(random, LETTERS, length);
    }

    /**
     * 生成纯数字文本。
     *
     * @param random 随机源
     * @param length 长度
     * @return 数字串，首位可以是 0
     */
    public static String digits(Random random, int length) {
        return pick(random, DIGITS, length);
    }

    /**
     * 生成英文数字混合文本。
     *
     * @param random 随机源
     * @param length 长度
     * @return 混合串
     */
    public static String mixed(Random random, int length) {
        return pick(random, MIXED, length);
    }

    /**
     * 生成一道二元算术题。
     *
     * @param random 随机源
     * @return 算式与结果，例如 {@code 14+8} 与 22
     */
    public static Expression arithmetic(Random random) {
        return arithmetic(random, false);
    }

    /**
     * 生成一道二元算术题。
     *
     * @param random 随机源
     * @param threeTerms 为 true 时生成三项算式，例如 {@code 8+6-1}
     * @return 算式与结果
     */
    public static Expression arithmetic(Random random, boolean threeTerms) {
        if (threeTerms) {
            return threeTerms(random);
        }
        char op = OPERATORS.charAt(random.nextInt(OPERATORS.length()));
        // 减法按左大右小生成，保证结果非负：验证码画面上的式子必须和标签一致，
        // 不能先生成再夹紧，否则标签会和图片对不上。
        int left = random.nextInt(40) + 2;
        int right = op == '-' ? random.nextInt(left) + 1 : random.nextInt(30) + 1;
        int value = apply(left, right, op);
        return new Expression(left + "" + op + right, value);
    }

    private static Expression threeTerms(Random random) {
        int a = random.nextInt(20) + 5;
        char first = "+-".charAt(random.nextInt(2));
        int b = first == '-' ? random.nextInt(a - 1) + 1 : random.nextInt(15) + 1;
        int step = apply(a, b, first);
        char second = "+-".charAt(random.nextInt(2));
        int c = second == '-' ? random.nextInt(Math.max(1, step)) + 1 : random.nextInt(10) + 1;
        int value = apply(step, c, second);
        return new Expression(a + "" + first + b + second + c, value);
    }

    private static int apply(int left, int right, char op) {
        switch (op) {
            case '+':
                return left + right;
            case '-':
                return left - right;
            case '*':
                return left * right;
            default:
                return left + right;
        }
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
         * 画面上出现的式子，不含 {@code =?}。
         *
         * @return 式子
         */
        public String getText() {
            return text;
        }

        /**
         * 式子对应的结果。
         *
         * @return 结果
         */
        public int getValue() {
            return value;
        }
    }
}
