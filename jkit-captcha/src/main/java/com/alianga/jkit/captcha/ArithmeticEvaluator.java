package com.alianga.jkit.captcha;

/**
 * 从识别串里抽出整数算式并计算。{@code * / x X ×} 先于 {@code + -}，从左到右。
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
        if (cleaned.isEmpty()) {
            return null;
        }
        int[] numbers = new int[cleaned.length()];
        char[] operators = new char[cleaned.length()];
        int numberCount = 0;
        int operatorCount = 0;
        int index = 0;
        while (index < cleaned.length()) {
            int start = index;
            while (index < cleaned.length()) {
                char ch = cleaned.charAt(index);
                if (ch < '0' || ch > '9') {
                    break;
                }
                index++;
            }
            if (start == index) {
                return null;
            }
            Integer number = readInt(cleaned.substring(start, index));
            if (number == null) {
                return null;
            }
            numbers[numberCount] = number.intValue();
            numberCount++;
            if (index >= cleaned.length()) {
                break;
            }
            char operator = cleaned.charAt(index);
            if (!isOperator(operator)) {
                return null;
            }
            operators[operatorCount] = operator;
            operatorCount++;
            index++;
            if (index >= cleaned.length()) {
                return null;
            }
        }
        if (numberCount < 2 || operatorCount != numberCount - 1) {
            return null;
        }
        Integer result = fold(numbers, numberCount, operators, operatorCount);
        if (result == null) {
            return null;
        }
        StringBuilder expression = new StringBuilder(cleaned.length());
        expression.append(numbers[0]);
        for (int i = 0; i < operatorCount; i++) {
            expression.append(operators[i]);
            expression.append(numbers[i + 1]);
        }
        return new Value(expression.toString(), result.intValue());
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

    private static boolean isOperator(char operator) {
        return operator == '+' || operator == '-' || tight(operator);
    }

    private static boolean tight(char operator) {
        return operator == '*' || operator == '/' || operator == 'x' || operator == 'X' || operator == '×';
    }

    /** 先从左到右收掉乘除，再收加减。除零返回空。 */
    private static Integer fold(int[] numbers, int numberCount, char[] operators, int operatorCount) {
        int[] terms = new int[numberCount];
        char[] loose = new char[operatorCount];
        int termCount = 0;
        int looseCount = 0;
        int current = numbers[0];
        for (int i = 0; i < operatorCount; i++) {
            char operator = operators[i];
            int right = numbers[i + 1];
            if (tight(operator)) {
                Integer applied = apply(current, operator, right);
                if (applied == null) {
                    return null;
                }
                current = applied.intValue();
            } else {
                terms[termCount] = current;
                termCount++;
                loose[looseCount] = operator;
                looseCount++;
                current = right;
            }
        }
        terms[termCount] = current;
        termCount++;
        int value = terms[0];
        for (int i = 0; i < looseCount; i++) {
            Integer applied = apply(value, loose[i], terms[i + 1]);
            if (applied == null) {
                return null;
            }
            value = applied.intValue();
        }
        return Integer.valueOf(value);
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
