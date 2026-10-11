package com.alianga.jkit.captcha;

/**
 * 把模板识别的大小写套到通用模型的字符上。
 * 两边忽略大小写能对上的位置用模板的大小写；对不上的位置用模型字符，并借用模板这一位的大小写。
 * 模型少掉的字符如果只是模板里相邻的重复字，保留模板整串。
 */
final class CaseAlign {
    private CaseAlign() {
    }

    static String align(String template, String model) {
        if (template == null || template.isEmpty()) {
            return model == null ? "" : model;
        }
        if (model == null || model.isEmpty()) {
            return template;
        }
        int rows = template.length();
        int cols = model.length();
        int[][] cost = new int[rows + 1][cols + 1];
        for (int i = 1; i <= rows; i++) {
            cost[i][0] = i;
        }
        for (int j = 1; j <= cols; j++) {
            cost[0][j] = j;
        }
        for (int i = 1; i <= rows; i++) {
            for (int j = 1; j <= cols; j++) {
                int sub = same(template.charAt(i - 1), model.charAt(j - 1)) ? 0 : 1;
                cost[i][j] = Math.min(Math.min(cost[i - 1][j] + 1, cost[i][j - 1] + 1), cost[i - 1][j - 1] + sub);
            }
        }
        StringBuilder merged = new StringBuilder();
        StringBuilder deleted = new StringBuilder();
        int i = rows;
        int j = cols;
        while (i > 0 || j > 0) {
            boolean same = i > 0 && j > 0 && same(template.charAt(i - 1), model.charAt(j - 1));
            int sub = same ? 0 : 1;
            if (i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + sub) {
                merged.append(pick(template.charAt(i - 1), model.charAt(j - 1), same));
                i--;
                j--;
            } else if (i > 0 && cost[i][j] == cost[i - 1][j] + 1) {
                deleted.append(template.charAt(i - 1));
                i--;
            } else {
                merged.append(model.charAt(j - 1));
                j--;
            }
        }
        merged.reverse();
        if (deleted.length() > 0 && model.length() < template.length() && duplicates(template, deleted)) {
            return template;
        }
        return merged.toString();
    }

    private static char pick(char template, char model, boolean same) {
        if (same) {
            return template;
        }
        if (isLetter(template) && isLetter(model)) {
            if (template >= 'A' && template <= 'Z') {
                return Character.toUpperCase(model);
            }
            return Character.toLowerCase(model);
        }
        return model;
    }

    private static boolean duplicates(String template, StringBuilder deleted) {
        String lower = template.toLowerCase();
        for (int d = 0; d < deleted.length(); d++) {
            char ch = Character.toLowerCase(deleted.charAt(d));
            int at = lower.indexOf(ch);
            boolean neighbor = false;
            while (at >= 0) {
                boolean left = at > 0 && lower.charAt(at - 1) == ch;
                boolean right = at + 1 < lower.length() && lower.charAt(at + 1) == ch;
                if (left || right) {
                    neighbor = true;
                    break;
                }
                at = lower.indexOf(ch, at + 1);
            }
            if (!neighbor) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(char left, char right) {
        return Character.toLowerCase(left) == Character.toLowerCase(right);
    }

    private static boolean isLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }
}
