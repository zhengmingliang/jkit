package com.alianga.jkit.mock.server;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 响应方案的激活规则：从请求的 query / header / body 取值，与期望值比较。
 *
 * <p>取值约定：{@code QUERY} / {@code HEADER} 按 key 取第一个值；{@code BODY} 的 key
 * 为点分路径（如 {@code user.name}），body 是 JSON 时下钻取值，key 为空时对整个
 * body 文本比较（适合前缀 / 正则场景）。</p>
 *
 * @author 郑明亮
 * @since 2.0.4
 */
public final class MockResponseRule {
    /** 取值来源。 */
    public enum Source {
        /** URL query 参数。 */
        QUERY,
        /** 请求头（忽略大小写）。 */
        HEADER,
        /** 请求体（JSON 点分路径或整段文本）。 */
        BODY,
        /** HTTP 方法（GET/POST/…；key 忽略）。 */
        METHOD
    }

    /** 比较方式。 */
    public enum Op {
        /** 相等。 */
        EQUALS,
        /** 整串正则匹配。 */
        REGEX,
        /** 存在即命中（不看 value）。 */
        EXISTS,
        /** 包含子串。 */
        CONTAINS,
        /** 期望值逗号分隔，实际命中任一即成立（大小写不敏感的逐项精确匹配）。 */
        IN,
        /** 数值大于（双方按 double 解析，解析失败不命中）。 */
        GT,
        /** 数值大于等于。 */
        GTE,
        /** 数值小于。 */
        LT,
        /** 数值小于等于。 */
        LTE
    }

    public final Source source;
    public final Op op;
    public final String key;
    public final String value;

    public MockResponseRule(Source source, Op op, String key, String value) {
        this.source = source == null ? Source.QUERY : source;
        this.op = op == null ? Op.EQUALS : op;
        this.key = key == null ? "" : key.trim();
        this.value = value == null ? "" : value;
    }

    /** 规则是否命中该请求。 */
    public boolean matches(MockRequestContext ctx) {
        String actual;
        switch (source) {
            case HEADER:
                actual = ctx.headerFirst(key);
                break;
            case BODY:
                actual = ctx.bodyPathOrRaw(key);
                break;
            case METHOD:
                actual = ctx.method();
                break;
            case QUERY:
            default:
                actual = ctx.queryFirst(key);
                break;
        }
        switch (op) {
            case EXISTS:
                return actual != null && !actual.isEmpty();
            case REGEX:
                return actual != null && Pattern.compile(value).matcher(actual).matches();
            case CONTAINS:
                return actual != null && actual.contains(value);
            case IN:
                return matchesAny(actual);
            case GT:
            case GTE:
            case LT:
            case LTE:
                return compareNumbers(actual, value, op);
            case EQUALS:
            default:
                return actual != null && actual.equals(value);
        }
    }

    /** IN：期望值按逗号拆分，实际与任一项相等（忽略首尾空白）即命中。 */
    private boolean matchesAny(String actual) {
        if (actual == null || value == null) {
            return false;
        }
        for (String part : value.split(",")) {
            if (actual.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /** 数值比较：双方都能解析为 double 时按指定操作符比较，否则不命中。 */
    private static boolean compareNumbers(String actual, String expected, Op op) {
        Double a = parseDoubleQuietly(actual);
        Double b = parseDoubleQuietly(expected);
        if (a == null || b == null) {
            return false;
        }
        int cmp = Double.compare(a, b);
        switch (op) {
            case GT:
                return cmp > 0;
            case GTE:
                return cmp >= 0;
            case LT:
                return cmp < 0;
            case LTE:
                return cmp <= 0;
            default:
                return false;
        }
    }

    private static Double parseDoubleQuietly(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return source.name().toLowerCase() + ":" + (key.isEmpty() ? "*" : key)
                + " " + opSymbol()
                + (op == Op.EXISTS ? "" : " '" + value + "'");
    }

    /** 比较方式的紧凑符号（弹层摘要用）。 */
    private String opSymbol() {
        switch (op) {
            case REGEX:
                return "=~";
            case EXISTS:
                return "exists";
            case CONTAINS:
                return "contains";
            case IN:
                return "in";
            case GT:
                return ">";
            case GTE:
                return ">=";
            case LT:
                return "<";
            case LTE:
                return "<=";
            case EQUALS:
            default:
                return "=";
        }
    }

    /** 便捷构造：query 相等。 */
    public static MockResponseRule queryEquals(String key, String value) {
        return new MockResponseRule(Source.QUERY, Op.EQUALS, key, value);
    }

    /** 便捷构造：header 相等。 */
    public static MockResponseRule headerEquals(String key, String value) {
        return new MockResponseRule(Source.HEADER, Op.EQUALS, key, value);
    }

    /** 便捷构造：body JSON 点分路径取值相等。 */
    public static MockResponseRule bodyEquals(String path, String value) {
        return new MockResponseRule(Source.BODY, Op.EQUALS, path, value);
    }

    /** 便捷构造：query 命中正则。 */
    public static MockResponseRule queryRegex(String key, String regex) {
        return new MockResponseRule(Source.QUERY, Op.REGEX, key, regex);
    }

    /** 便捷构造：key 存在即命中。 */
    public static MockResponseRule exists(MockResponseRule.Source source, String key) {
        return new MockResponseRule(source, Op.EXISTS, key, "");
    }

    /** Map 形式的简写：key 为 "query:uid" / "header:X-Token" / "body:user.name"。 */
    public static MockResponseRule parse(String spec, String value) {
        String source = "query";
        String key = spec == null ? "" : spec.trim();
        int idx = key.indexOf(':');
        if (idx > 0) {
            source = key.substring(0, idx).trim();
            key = key.substring(idx + 1).trim();
        }
        Source s;
        try {
            s = Source.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException e) {
            s = Source.QUERY;
        }
        return new MockResponseRule(s, Op.EQUALS, key, value);
    }

    /** 供 {@link MockRequestContext} 之外复用的取值工具。 */
    static String firstValue(Map<String, List<String>> values, String key) {
        if (key == null) {
            return null;
        }
        List<String> list = values.get(key);
        if (list == null || list.isEmpty()) {
            // 容忍大小写差异
            for (Map.Entry<String, List<String>> e : values.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                    list = e.getValue();
                    break;
                }
            }
        }
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }
}
