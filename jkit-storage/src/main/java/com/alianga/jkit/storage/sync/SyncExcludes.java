package com.alianga.jkit.storage.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步排除规则的匹配语义：路径段边界匹配。
 *
 * <p>历史 bug：排除项按裸前缀匹配（{@code path.startsWith("tmp")}），
 * 排除 {@code tmp} 会连 {@code tmp2/}、{@code tmpfile.txt} 一起误伤。
 * 正确语义是「路径段边界」：命中自身，或命中以 {@code 前缀/} 开头的子孙；
 * 用户带不带尾随 {@code /} 写法等效。</p>
 *
 * @author 郑明亮
 */
public final class SyncExcludes {
    private SyncExcludes() {
    }

    /**
     * 解析逗号分隔的排除前缀清单（去空白、去空项、剥尾随斜杠）。
     *
     * @param raw 用户输入（如 {@code "logs/, tmp"}）
     * @return 规范化后的前缀清单
     */
    public static List<String> parse(String raw) {
        List<String> prefixes = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) {
            return prefixes;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (!trimmed.isEmpty()) {
                prefixes.add(trimmed);
            }
        }
        return prefixes;
    }

    /**
     * 路径是否命中排除前缀（段边界匹配）。
     *
     * @param path       相对路径（如 {@code tmp2/a.txt}）
     * @param barePrefix 剥过尾随斜杠的前缀（如 {@code tmp}）
     * @return true 表示应排除
     */
    public static boolean matches(String path, String barePrefix) {
        if (path == null || barePrefix == null || barePrefix.isEmpty()) {
            return false;
        }
        return path.equals(barePrefix) || path.startsWith(barePrefix + "/");
    }

    /**
     * 路径是否命中清单里的任意前缀。
     *
     * @param path     相对路径
     * @param prefixes {@link #parse} 的产物
     * @return true 表示应排除
     */
    public static boolean anyMatch(String path, List<String> prefixes) {
        if (prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (matches(path, prefix)) {
                return true;
            }
        }
        return false;
    }
}
