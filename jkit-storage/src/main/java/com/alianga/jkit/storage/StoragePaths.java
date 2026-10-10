package com.alianga.jkit.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储路径工具：统一约定所有平台的对象路径都用 {@code /} 分隔、不带前导 {@code /}、
 * 目录路径不带尾部 {@code /}（根目录为空串）。适配器在入口处先 {@link #normalize}
 * 再使用，保证跨平台行为一致。
 *
 * @author 郑明亮
 */
public final class StoragePaths {
    private StoragePaths() {
    }

    /**
     * 规范化路径：统一 {@code /} 分隔、去掉前导与尾随 {@code /}、折叠连续 {@code /}、
     * 丢弃 {@code .} 段并回退 {@code ..} 段（防止路径上跳逃逸出存储根）。
     *
     * @param path 原始路径，null 或空白视为根目录
     * @return 规范化后的路径，根目录返回空串
     */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String unified = path.replace('\\', '/');
        List<String> parts = new ArrayList<String>();
        for (String segment : unified.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(segment);
        }
        return String.join("/", parts);
    }

    /**
     * 拼接目录与子路径。
     *
     * @param dir  目录，null 视为根
     * @param name 子路径或文件名
     * @return 拼接后的规范化路径
     */
    public static String join(String dir, String name) {
        String left = normalize(dir);
        String right = normalize(name);
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + "/" + right;
    }

    /**
     * 取父目录。
     *
     * @param path 路径
     * @return 父目录路径，根目录返回空串
     */
    public static String parent(String path) {
        String normalized = normalize(path);
        int at = normalized.lastIndexOf('/');
        return at < 0 ? "" : normalized.substring(0, at);
    }

    /**
     * 取最后一段名称（文件名或目录名）。
     *
     * @param path 路径
     * @return 名称；根目录返回空串
     */
    public static String name(String path) {
        String normalized = normalize(path);
        int at = normalized.lastIndexOf('/');
        return at < 0 ? normalized : normalized.substring(at + 1);
    }

    /**
     * 取小写扩展名（不含点）。
     *
     * @param path 路径
     * @return 扩展名，无扩展名返回空串
     */
    public static String extension(String path) {
        String name = name(path);
        int at = name.lastIndexOf('.');
        if (at <= 0 || at == name.length() - 1) {
            return "";
        }
        return name.substring(at + 1).toLowerCase();
    }

    /**
     * 判断 child 是否位于 dir 之内（或恰好等于 dir）。用于把列表结果归入目录树。
     *
     * @param dir   目录路径
     * @param child 待判断路径
     * @return true 表示在目录内
     */
    public static boolean isUnder(String dir, String child) {
        String parent = normalize(dir);
        String target = normalize(child);
        if (parent.isEmpty()) {
            return true;
        }
        return target.equals(parent) || target.startsWith(parent + "/");
    }

    /**
     * 取 child 相对 dir 的相对路径；child 不在 dir 内时返回 null。
     *
     * @param dir   目录
     * @param child 子路径
     * @return 相对路径；不在目录内返回 null
     */
    public static String relativize(String dir, String child) {
        String parent = normalize(dir);
        String target = normalize(child);
        if (parent.isEmpty()) {
            return target;
        }
        if (!isUnder(parent, target) || target.equals(parent)) {
            return null;
        }
        return target.substring(parent.length() + 1);
    }
}
