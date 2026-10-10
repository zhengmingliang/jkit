package com.alianga.jkit.captcha;

/**
 * 从识别串里抽出一道二元算术题并计算。支持 {@code + - * / x ×}。
 */
final class ArithmeticEvaluator {
    private ArithmeticEvaluator() {
    }

    static final class Value {
        final String expression;
        final int result;

        Value(String expression, int result) {
            this.expression = expression;
            this.result = result;
        }
    }

    static Value evaluate(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String cleaned = stripTail(text);
        int operatorAt = findOperator(cleaned);
        if (operatorAt <= 0 || operatorAt >= cleaned.length() - 1) {
            return null;
        }
        Integer left = readInt(cleaned.substring(0, operatorAt));
        Integer right = readInt(cleaned.substring(operatorAt + 1));
        if (left == null || right == null) {
            return null;
        }
        char operator = cleaned.charAt(operatorAt);
        Integer result = apply(left.intValue(), operator, right.intValue());
        if (result == null) {
            return null;
        }
        return new Value(left + String.valueOf(operator) + right, result.intValue());
    }

    private static String stripTail(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '=' || ch == '?' || ch == '？') {
                continue;
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private static int findOperator(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == 'x' || ch == 'X' || ch == '×') {
                return i;
            }
        }
        return -1;
    }

    private static Integer readInt(String text) {
        if (text.isEmpty()) {
            return null;
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
            value = value * 10 + (ch - '0');
        }
        return Integer.valueOf(value);
    }

    private static Integer apply(int left, char operator, int right) {
        if (operator == '+') {
            return Integer.valueOf(left + right);
        }
        if (operator == '-') {
            return Integer.valueOf(left - right);
        }
        if (operator == '*' || operator == 'x' || operator == 'X' || operator == '×') {
            return Integer.valueOf(left * right);
        }
        if (operator == '/') {
            if (right == 0) {
                return null;
            }
            return Integer.valueOf(left / right);
        }
        return null;
    }
}
