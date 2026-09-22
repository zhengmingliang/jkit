package com.alianga.jkit.mock;

import java.util.ArrayList;
import java.util.List;

/**
 * 正则反向生成器：给定一个正则，产出一条能匹配它的随机字符串，对应 Mock.js 模板里
 * 直接用 {@code /regexp/} 作为属性值的能力。
 *
 * <pre>{@code
 * MockRegex.generate("^1[3-9]\\d{9}$");   // => "16114341517"
 * MockRegex.generate("[A-Z]{3}\\d{4}");   // => "NZX3651"
 * }</pre>
 *
 * <p>支持字面量、转义、{@code .}、{@code \d \D \w \W \s \S}、字符集与反向字符集、
 * 区间、分组（含非捕获与前后瞻）、选择、量词 {@code * + ? {m,n}}、反向引用；
 * {@code ^ $ \b} 等锚点在生成时被忽略。</p>
 *
 * @author 郑明亮
 */
public final class MockRegex {
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String SPACES = " \t\n\r\f\u000b";
    private static final String OTHERS = "!@#$%^&*()[]{}<>?/\\|~`'\"+-_=,.;:";

    private MockRegex() {
        throw new UnsupportedOperationException("MockRegex");
    }

    /**
     * 按正则生成随机字符串，使用默认随机源。
     *
     * @param regex 正则表达式
     * @return 随机字符串
     */
    public static String generate(String regex) {
        return generate(regex, new MockRandom());
    }

    /**
     * 按正则生成随机字符串。
     *
     * @param regex  正则表达式
     * @param random 随机源
     * @return 随机字符串
     */
    public static String generate(String regex, MockRandom random) {
        if (regex == null || regex.isEmpty()) {
            return "";
        }
        Node node = new Parser(regex).parse();
        return node.gen(random == null ? new MockRandom() : random, new ArrayList<String>());
    }

    private interface Node {
        String gen(MockRandom random, List<String> cache);
    }

    private static final class Empty implements Node {
        public String gen(MockRandom random, List<String> cache) {
            return "";
        }
    }

    private static final class Literal implements Node {
        private final String text;

        Literal(String text) {
            this.text = text;
        }

        public String gen(MockRandom random, List<String> cache) {
            return text;
        }
    }

    private static final class Cls implements Node {
        private final String kind;

        Cls(String kind) {
            this.kind = kind;
        }

        public String gen(MockRandom random, List<String> cache) {
            String pool;
            if ("digit".equals(kind)) {
                pool = DIGITS;
            } else if ("word".equals(kind)) {
                pool = LOWER + UPPER + DIGITS;
            } else if ("space".equals(kind)) {
                pool = SPACES;
            } else if ("non-space".equals(kind)) {
                pool = LOWER + UPPER + DIGITS;
            } else if ("non-word".equals(kind)) {
                pool = OTHERS.replace("_", "") + " ";
            } else if ("any".equals(kind)) {
                pool = LOWER + UPPER + DIGITS + OTHERS;
            } else {
                pool = LOWER + UPPER + OTHERS + " ";
            }
            return String.valueOf(pool.charAt((int) random.natural(0, pool.length() - 1)));
        }
    }

    private static final class CharSet implements Node {
        private final boolean invert;
        private final List<Node> body;

        CharSet(boolean invert, List<Node> body) {
            this.invert = invert;
            this.body = body;
        }

        public String gen(MockRandom random, List<String> cache) {
            if (invert) {
                StringBuilder pool = new StringBuilder();
                for (char c = ' '; c <= '~'; c++) {
                    pool.append(c);
                }
                for (Node item : body) {
                    String value = item.gen(random, cache);
                    if (item instanceof Range) {
                        char from = value.charAt(0);
                        char to = value.charAt(value.length() - 1);
                        for (char c = from; c <= to; c++) {
                            remove(pool, c);
                        }
                    } else {
                        for (char c : value.toCharArray()) {
                            remove(pool, c);
                        }
                    }
                }
                if (pool.length() == 0) {
                    return "";
                }
                return String.valueOf(pool.charAt((int) random.natural(0, pool.length() - 1)));
            }
            if (body.isEmpty()) {
                return "";
            }
            return pick(random, body).gen(random, cache);
        }

        private static void remove(StringBuilder pool, char c) {
            int idx = pool.indexOf(String.valueOf(c));
            if (idx >= 0) {
                pool.deleteCharAt(idx);
            }
        }
    }

    private static final class Range implements Node {
        private final Node start;
        private final Node end;

        Range(Node start, Node end) {
            this.start = start;
            this.end = end;
        }

        public String gen(MockRandom random, List<String> cache) {
            String s = start.gen(random, cache);
            String e = end.gen(random, cache);
            if (s.isEmpty() || e.isEmpty()) {
                return "";
            }
            int min = s.charAt(s.length() - 1);
            int max = e.charAt(e.length() - 1);
            if (max < min) {
                int tmp = min;
                min = max;
                max = tmp;
            }
            return String.valueOf((char) random.integer(min, max));
        }
    }

    private static final class Group implements Node {
        private static final int CAPTURE = 0;
        private static final int NON_CAPTURE = 1;
        private static final int LOOKAHEAD = 2;
        private static final int NEGATIVE = 3;

        private final int kind;
        private final Node body;

        Group(int kind, Node body) {
            this.kind = kind;
            this.body = body;
        }

        public String gen(MockRandom random, List<String> cache) {
            if (kind == NEGATIVE) {
                return "";
            }
            String value = body.gen(random, cache);
            if (kind == CAPTURE) {
                cache.add(value);
            }
            return value;
        }
    }

    private static final class BackReference implements Node {
        private final int index;

        BackReference(int index) {
            this.index = index;
        }

        public String gen(MockRandom random, List<String> cache) {
            return index < cache.size() ? cache.get(index) : "";
        }
    }

    private static final class Quantified implements Node {
        private final Node body;
        private final int min;
        private final int max;

        Quantified(Node body, int min, int max) {
            this.body = body;
            this.min = min;
            this.max = max;
        }

        public String gen(MockRandom random, List<String> cache) {
            int upper = max < 0 ? min + (int) random.integer(3, 7) : max;
            int count = (int) random.integer(min, Math.max(upper, min));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                sb.append(body.gen(random, cache));
            }
            return sb.toString();
        }
    }

    private static final class Match implements Node {
        private final List<Node> body = new ArrayList<Node>();

        public String gen(MockRandom random, List<String> cache) {
            StringBuilder sb = new StringBuilder();
            for (Node node : body) {
                sb.append(node.gen(random, cache));
            }
            return sb.toString();
        }
    }

    private static final class Alternate implements Node {
        private final List<Node> options = new ArrayList<Node>();

        public String gen(MockRandom random, List<String> cache) {
            if (options.isEmpty()) {
                return "";
            }
            return pick(random, options).gen(random, cache);
        }
    }

    private static <T> T pick(MockRandom random, List<T> list) {
        return list.get((int) random.natural(0, list.size() - 1));
    }

    /** 递归下降解析器：alternation → concat → quantified → atom。 */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        Node parse() {
            return alternation();
        }

        private Node alternation() {
            List<Node> options = new ArrayList<Node>();
            options.add(concat());
            while (pos < src.length() && src.charAt(pos) == '|') {
                pos++;
                options.add(concat());
            }
            if (options.size() == 1) {
                return options.get(0);
            }
            Alternate alt = new Alternate();
            alt.options.addAll(options);
            return alt;
        }

        private Node concat() {
            Match match = new Match();
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '|' || c == ')') {
                    break;
                }
                match.body.add(quantified());
            }
            if (match.body.size() == 1) {
                return match.body.get(0);
            }
            return match;
        }

        private Node quantified() {
            Node atom = atom();
            while (true) {
                if (pos >= src.length()) {
                    return atom;
                }
                char c = src.charAt(pos);
                if (c == '*') {
                    pos++;
                    skipLazy();
                    atom = new Quantified(atom, 0, -1);
                } else if (c == '+') {
                    pos++;
                    skipLazy();
                    atom = new Quantified(atom, 1, -1);
                } else if (c == '?') {
                    pos++;
                    skipLazy();
                    atom = new Quantified(atom, 0, 1);
                } else if (c == '{') {
                    int[] bound = brace();
                    if (bound == null) {
                        return atom;
                    }
                    skipLazy();
                    atom = new Quantified(atom, bound[0], bound[1]);
                } else {
                    return atom;
                }
            }
        }

        private void skipLazy() {
            if (pos < src.length() && src.charAt(pos) == '?') {
                pos++;
            }
        }

        private int[] brace() {
            int end = src.indexOf('}', pos);
            if (end < 0) {
                return null;
            }
            String inner = src.substring(pos + 1, end);
            pos = end + 1;
            int comma = inner.indexOf(',');
            try {
                if (comma < 0) {
                    int n = Integer.parseInt(inner.trim());
                    return new int[]{n, n};
                }
                int min = Integer.parseInt(inner.substring(0, comma).trim());
                String rest = inner.substring(comma + 1).trim();
                int max = rest.isEmpty() ? -1 : Integer.parseInt(rest);
                return new int[]{min, max};
            } catch (NumberFormatException e) {
                return new int[]{0, -1};
            }
        }

        private Node atom() {
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                int kind = Group.CAPTURE;
                if (pos + 1 < src.length() && src.charAt(pos) == '?') {
                    char n = src.charAt(pos + 1);
                    if (n == ':') {
                        kind = Group.NON_CAPTURE;
                        pos += 2;
                    } else if (n == '=') {
                        kind = Group.LOOKAHEAD;
                        pos += 2;
                    } else if (n == '!') {
                        kind = Group.NEGATIVE;
                        pos += 2;
                    }
                }
                Node body = alternation();
                if (pos < src.length() && src.charAt(pos) == ')') {
                    pos++;
                }
                return new Group(kind, body);
            }
            if (c == '[') {
                return charset();
            }
            if (c == '^' || c == '$') {
                pos++;
                return new Empty();
            }
            if (c == '.') {
                pos++;
                return new Cls("any");
            }
            if (c == '\\') {
                return escape();
            }
            pos++;
            return new Literal(String.valueOf(c));
        }

        private Node escape() {
            pos++;
            if (pos >= src.length()) {
                return new Literal("\\");
            }
            char c = src.charAt(pos);
            pos++;
            switch (c) {
                case 'd':
                    return new Cls("digit");
                case 'D':
                    return new Cls("non-digit");
                case 'w':
                    return new Cls("word");
                case 'W':
                    return new Cls("non-word");
                case 's':
                    return new Cls("space");
                case 'S':
                    return new Cls("non-space");
                case 'b':
                case 'B':
                    return new Empty();
                case 'n':
                    return new Literal("\n");
                case 'r':
                    return new Literal("\r");
                case 't':
                    return new Literal("\t");
                case 'f':
                    return new Literal("\f");
                case 'v':
                    return new Literal("\u000b");
                case '0':
                    return new Literal("\u0000");
                case 'u':
                    return code(4, 16);
                case 'x':
                    return code(2, 16);
                default:
                    if (c >= '1' && c <= '9') {
                        return new BackReference(c - '0' - 1);
                    }
                    return new Literal(String.valueOf(c));
            }
        }

        private Node code(int len, int radix) {
            if (pos + len > src.length()) {
                return new Empty();
            }
            String hex = src.substring(pos, pos + len);
            pos += len;
            try {
                return new Literal(String.valueOf((char) Integer.parseInt(hex, radix)));
            } catch (NumberFormatException e) {
                return new Empty();
            }
        }

        private Node charset() {
            pos++;
            boolean invert = false;
            if (pos < src.length() && src.charAt(pos) == '^') {
                invert = true;
                pos++;
            }
            List<Node> body = new ArrayList<Node>();
            while (pos < src.length() && src.charAt(pos) != ']') {
                if (src.charAt(pos) == '\\') {
                    Node item = escape();
                    if (pos < src.length() && src.charAt(pos) == '-'
                            && pos + 1 < src.length() && src.charAt(pos + 1) != ']') {
                        pos++;
                        Node end = src.charAt(pos) == '\\' ? escape() : single();
                        body.add(new Range(item, end));
                    } else {
                        body.add(item);
                    }
                    continue;
                }
                Node start = single();
                if (pos < src.length() && src.charAt(pos) == '-'
                        && pos + 1 < src.length() && src.charAt(pos + 1) != ']') {
                    pos++;
                    Node end = src.charAt(pos) == '\\' ? escape() : single();
                    body.add(new Range(start, end));
                } else {
                    body.add(start);
                }
            }
            if (pos < src.length()) {
                pos++;
            }
            return new CharSet(invert, body);
        }

        private Node single() {
            char c = src.charAt(pos);
            pos++;
            return new Literal(String.valueOf(c));
        }
    }
}
