package com.alianga.jkit.http.curl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * POSIX 风格 curl 命令分词。
 *
 * <p>支持拼接引号、{@code $'ansi-c'}、PowerShell 续行、行首 {@code #} 注释、
 * 短选项簇（{@code -kLs}）和粘连值（{@code -XPOST}）。</p>
 *
 * @author 郑明亮
 */
public final class CurlTokenizer {
    private static final Set<Character> VALUE_SHORT = new HashSet<Character>();

    static {
        for (char c : "AbCcdDeEFHKmoOPTuUwxXz".toCharArray()) {
            VALUE_SHORT.add(Character.valueOf(c));
        }
    }

    private CurlTokenizer() {
    }

    /**
     * 去掉 BOM，并把各类续行折叠成空格。
     *
     * @param input 原始 curl 文本
     * @return 规范化后的文本
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\uFEFF", "")
                .replace("\\\r\n", " ")
                .replace("\\\n", " ")
                .replace("^\r\n", " ")
                .replace("^\n", " ")
                .replace("`\r\n", " ")
                .replace("`\n", " ")
                .replace("\r\n", "\n");
    }

    /**
     * 按 POSIX shell 规则拆分参数。
     *
     * @param input 规范化后的命令
     * @return 参数列表
     */
    public static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                flush(tokens, current);
                i++;
                continue;
            }
            if (c == '#' && current.length() == 0) {
                while (i < n && input.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '$' && i + 1 < n && input.charAt(i + 1) == '\'') {
                i += 2;
                while (i < n) {
                    char q = input.charAt(i);
                    if (q == '\'') {
                        i++;
                        break;
                    }
                    if (q == '\\' && i + 1 < n) {
                        current.append(unescapeAnsi(input.charAt(i + 1)));
                        i += 2;
                        continue;
                    }
                    current.append(q);
                    i++;
                }
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char q = input.charAt(i);
                    if (q == '\'') {
                        i++;
                        break;
                    }
                    current.append(q);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n) {
                    char q = input.charAt(i);
                    if (q == '"') {
                        i++;
                        break;
                    }
                    if (q == '\\' && i + 1 < n) {
                        i++;
                        current.append(input.charAt(i));
                        i++;
                        continue;
                    }
                    current.append(q);
                    i++;
                }
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                current.append(input.charAt(i + 1));
                i += 2;
                continue;
            }
            current.append(c);
            i++;
        }
        flush(tokens, current);
        return tokens;
    }

    /**
     * {@code -kLs} → {@code -k -L -s}；{@code -XPOST} → {@code -X POST}。
     *
     * @param args 原始参数
     * @return 展开后的参数
     */
    public static List<String> expandArgs(List<String> args) {
        List<String> out = new ArrayList<String>();
        for (String arg : args) {
            if (arg == null || arg.length() <= 2 || !arg.startsWith("-") || arg.startsWith("--")
                    || "-".equals(arg) || "--".equals(arg)) {
                out.add(arg);
                continue;
            }
            String body = arg.substring(1);
            char first = body.charAt(0);
            if (VALUE_SHORT.contains(Character.valueOf(first))) {
                out.add("-" + first);
                if (body.length() > 1) {
                    out.add(body.substring(1));
                }
                continue;
            }
            for (int i = 0; i < body.length(); i++) {
                char ch = body.charAt(i);
                if (VALUE_SHORT.contains(Character.valueOf(ch))) {
                    out.add("-" + ch);
                    if (i + 1 < body.length()) {
                        out.add(body.substring(i + 1));
                    }
                    break;
                }
                out.add("-" + ch);
            }
        }
        return out;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static char unescapeAnsi(char ch) {
        switch (ch) {
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case '0':
                return '\0';
            case 'a':
                return '\u0007';
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case '\\':
                return '\\';
            case '\'':
                return '\'';
            default:
                return ch;
        }
    }
}
