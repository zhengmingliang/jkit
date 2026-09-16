package com.alianga.jkit.sql.corpus;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 复杂 SQL 回归报告：写到模块 {@code target/complex-sql-reports/}。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class ComplexSqlReports {
    private ComplexSqlReports() {
    }

    /**
     * @return 报告目录（会创建）
     */
    public static File dir() {
        File dir = new File("target/complex-sql-reports");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir.getAbsolutePath());
        }
        return dir;
    }

    /**
     * 写 UTF-8 文本文件。
     *
     * @param name 文件名
     * @param content 正文
     * @return 写成的文件
     */
    public static File write(String name, String content) {
        File file = new File(dir(), name);
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8));
            try {
                writer.write(content == null ? "" : content);
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("write " + file, e);
        }
        return file;
    }

    /**
     * TSV 转义：TAB / 换行换成空格。
     *
     * @param value 原文
     * @return 单行
     */
    public static String tsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * 截断。
     *
     * @param value 原文
     * @param max 最大长度
     * @return 截断结果
     */
    public static String trunc(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * 按错误类计数，次数降序。
     *
     * @param classes 每条失败的 errorClass
     * @return 类 → 次数
     */
    public static Map<String, Integer> countClasses(List<String> classes) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        if (classes == null) {
            return counts;
        }
        for (int i = 0; i < classes.size(); i++) {
            String key = classes.get(i);
            if (key == null || key.isEmpty()) {
                key = "UNKNOWN";
            }
            Integer n = counts.get(key);
            counts.put(key, n == null ? 1 : n + 1);
        }
        List<Map.Entry<String, Integer>> entries =
                new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Integer>>() {
            /**
             * {@inheritDoc}
             */
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                int byCount = b.getValue().compareTo(a.getValue());
                if (byCount != 0) {
                    return byCount;
                }
                return a.getKey().compareTo(b.getKey());
            }
        });
        Map<String, Integer> ordered = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < entries.size(); i++) {
            ordered.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
        return ordered;
    }

    /**
     * 「有效」左括号数：先剔除包着单个原子（标识符 / 数字 / 字符串）的冗余括号，
     * 再计数。回写折叠 {@code (p.avg_score)} → {@code p.avg_score} 不改变语义，
     * 但折叠 {@code (a - b) / c} 会改求值顺序，故门禁只认有效括号。
     *
     * @param sql SQL 文本
     * @return 有效左括号个数
     */
    public static int significantParens(String sql) {
        if (sql == null || sql.isEmpty()) {
            return 0;
        }
        int n = 0;
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) != '(') {
                i++;
                continue;
            }
            int end = matchParen(sql, i);
            if (end < 0) {
                i++;
                continue;
            }
            if (isAtomic(sql.substring(i + 1, end))) {
                // 整个原子括号连同内部一起跳过，两侧折叠与否都不计
                i = end + 1;
                continue;
            }
            n++;
            i++;
        }
        return n;
    }

    private static int matchParen(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                i++;
                while (i < s.length()) {
                    if (s.charAt(i) == '\'') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                            i++;
                        } else {
                            break;
                        }
                    }
                    i++;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isAtomic(String inner) {
        String s = inner.trim();
        if (s.isEmpty()) {
            return true;
        }
        if (s.matches("(?s)[A-Za-z_][A-Za-z0-9_$]*(\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_$]*)*")) {
            return true;
        }
        if (s.matches("\\d+(?:\\.\\d+)?") || s.matches("'(?:[^']|'')*'") || "?".equals(s)) {
            return true;
        }
        // 函数调用（含嵌套调用）：f(...) / s.f(...) / COUNT(*)
        int lp = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$') {
                continue;
            }
            lp = c == '(' ? i : -1;
            break;
        }
        if (lp <= 0) {
            return false;
        }
        String name = s.substring(0, lp).trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_$]*(\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_$]*)*")) {
            return false;
        }
        int end = matchParen(s, lp);
        return end == s.length() - 1;
    }

    /**
     * 从解析异常信息归类，便于按类修解析器。
     *
     * @param message 异常信息
     * @return 错误类
     */
    public static String parseErrorClass(String message) {
        if (message == null || message.isEmpty()) {
            return "UNKNOWN";
        }
        String m = message.toUpperCase(Locale.ROOT);
        if (m.contains("RECURSIVE") || m.contains("WITH ITEM")) {
            return "PARSE_RECURSIVE_CTE";
        }
        if (m.contains("FETCH") || m.contains("OFFSET") || m.contains("LIMIT") || m.contains("ROWNUM")) {
            return "PARSE_PAGINATION";
        }
        if (m.contains("LISTAGG") || m.contains("WITHIN GROUP") || m.contains("STRING_AGG")
                || m.contains("GROUP_CONCAT")) {
            return "PARSE_LISTAGG";
        }
        if (m.contains("OVER") || m.contains("WINDOW") || m.contains("ROWS BETWEEN")
                || m.contains("RANGE BETWEEN")) {
            return "PARSE_WINDOW";
        }
        if (m.contains("EXTRACT") || m.contains("DATE_TRUNC") || m.contains("DATEFROMPARTS")
                || m.contains("INTERVAL")) {
            return "PARSE_DATE_FN";
        }
        if (m.contains("UNEXPECTED") || m.contains("TOKEN")) {
            return "PARSE_UNEXPECTED_TOKEN";
        }
        if (m.contains("UNSUPPORTED")) {
            return "PARSE_UNSUPPORTED";
        }
        return "PARSE_OTHER";
    }
}
