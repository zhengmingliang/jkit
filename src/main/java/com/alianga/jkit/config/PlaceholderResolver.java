package com.alianga.jkit.config;

import com.alianga.jkit.log.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析配置值中的 {@code ${key}} / {@code ${key:default}} 占位符。
 *
 * <p>支持嵌套占位符，并检测循环引用。</p>
 */
public final class PlaceholderResolver {
    private static final Log log = Log.get(PlaceholderResolver.class);

    private static final Pattern PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private PlaceholderResolver() {
    }

    /**
     * 解析映射中所有字符串值的占位符，结果写回原映射。
     *
     * @param props 属性映射
     */
    public static void resolveAll(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return;
        }
        for (String key : props.keySet()) {
            Object value = props.get(key);
            if (value instanceof String) {
                props.put(key, resolveValue((String) value, props, new HashSet<String>()));
            }
        }
    }

    /**
     * 递归解析单个值中的占位符。
     *
     * @param value 原始值，可为 {@code ${key}} 或普通字符串
     * @param props 属性映射
     * @param visiting 当前解析链上已访问的 key，用于检测循环引用
     * @return 解析后的字符串；{@code value} 为 null 时返回 null
     */
    public static String resolveValue(String value, Map<String, Object> props, Set<String> visiting) {
        if (value == null) {
            return null;
        }
        if (props == null) {
            return value;
        }
        Set<String> visitingKeys = visiting == null ? new HashSet<String>() : visiting;
        Matcher matcher = PATTERN.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String expr = matcher.group(1);
            String key;
            String defaultValue = null;
            int idx = expr.indexOf(':');
            if (idx >= 0) {
                key = expr.substring(0, idx);
                defaultValue = expr.substring(idx + 1);
            } else {
                key = expr;
            }

            if (visitingKeys.contains(key)) {
                throw new IllegalStateException("Detected circular placeholder reference: " + key);
            }
            visitingKeys.add(key);

            Object replacement = props.get(key);
            String resolved;
            if (replacement != null) {
                resolved = resolveValue(replacement.toString(), props, visitingKeys);
            } else if (defaultValue != null) {
                resolved = resolveValue(defaultValue, props, visitingKeys);
            } else {
                log.warn("未解析到属性：{}", key);
                resolved = "";
            }

            visitingKeys.remove(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
