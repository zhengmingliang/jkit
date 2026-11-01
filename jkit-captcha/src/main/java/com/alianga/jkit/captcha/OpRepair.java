package com.alianga.jkit.captcha;

/**
 * 算式没能算出值时，去掉误读的 {@code =?} 尾巴，并把夹在数字之间的 {@code t} 收成加号。
 * 已经能算出值的串不动，因此历史样本里的 {@code 7x8} 仍保持乘号 {@code x}。
 */
final class OpRepair {
    private OpRepair() {
    }

    static String repair(String text) {
        if (text == null || text.isEmpty() || ArithmeticEvaluator.evaluate(text) != null) {
            return text == null ? "" : text;
        }
        String stripped = stripTail(text);
        String plus = stripped.replaceAll("(\\d)[tT](\\d)", "$1+$2");
        String star = plus.replaceAll("(\\d)[xX](\\d)", "$1*$2");
        boolean junk = text.length() > stripped.length();
        if (junk && !star.equals(plus) && ArithmeticEvaluator.evaluate(star) != null) {
            return star;
        }
        if (ArithmeticEvaluator.evaluate(plus) != null) {
            return plus;
        }
        return text;
    }

    /** 去掉误读出来的等号问号尾巴，例如 {@code e7}、{@code s2}。 */
    private static String stripTail(String text) {
        String current = text;
        String previous;
        do {
            previous = current;
            current = current.replaceAll("(?i)e7$", "");
            current = current.replaceAll("(?i)s[0-9]$", "");
            current = current.replaceAll("[=?？]+$", "");
        } while (!current.equals(previous) && !current.isEmpty());
        return current;
    }
}
