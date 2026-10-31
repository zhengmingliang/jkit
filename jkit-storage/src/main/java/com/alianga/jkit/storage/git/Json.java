package com.alianga.jkit.storage.git;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.StorageException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Git REST 响应的最小 JSON 读取：只做「取字符串 / 取嵌套对象 / 取数组」三件事。
 *
 * <p>用项目已有的 {@code com.alianga.jkit.json.JSON}（宿主 jkit 自带），不为了 Git 再引一套
 * JSON 框架。四家平台的响应都是对象或数组两种形态，这里统一按 {@code Map} / {@code List} 读，
 * 缺字段返回默认值而不是抛异常——平台字段差异由 Adapter 兜底，读不到就当「未知」。</p>
 *
 * @author 郑明亮
 */
public final class Json {
    private Json() {
    }

    /**
     * 解析响应体为对象。
     *
     * <p><b>空数组按「空对象」处理</b>：Gitee 在文件不存在时返回 HTTP 200 + {@code []}
     * （而不是 404），调用方据此判定「无数据」。</p>
     *
     * @param body 响应体
     * @param path 出错时的对象路径（进异常文案，不含令牌）
     * @return 对象；解析失败抛 IO_ERROR
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String body, String path) {
        Object parsed = parse(body, path);
        if (parsed instanceof Map<?, ?>) {
            return (Map<String, Object>) parsed;
        }
        if (parsed instanceof List<?> && ((List<?>) parsed).isEmpty()) {
            // Gitee 的「不存在」形态：200 + []
            return Collections.emptyMap();
        }
        throw StorageException.io("Git 返回了非对象响应：" + summarize(body), path, null);
    }

    /**
     * 解析响应体为数组。平台在「路径是文件而非目录」时会返回对象而不是数组，
     * 这里按调用方语义直接判定为非法形态并给出可读提示。
     *
     * @param body 响应体
     * @param path 出错时的对象路径
     * @return 数组
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> array(String body, String path) {
        Object parsed = parse(body, path);
        if (parsed instanceof List<?>) {
            return (List<Map<String, Object>>) parsed;
        }
        if (parsed instanceof Map<?, ?>) {
            // 路径指向单个文件：调用方按目录在用，明确报出来
            throw StorageException.notFound(path);
        }
        throw StorageException.io("Git 返回了非数组响应：" + summarize(body), path, null);
    }

    private static Object parse(String body, String path) {
        if (body == null || body.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            // parse（而非 parseObject）：响应体既可能是对象也可能是数组，
            // parseObject 遇到顶层数组会抛 ClassCastException
            return JSON.parse(body);
        } catch (RuntimeException e) {
            throw StorageException.io("Git 响应无法解析：" + summarize(body), path, e);
        }
    }

    /**
     * 取字符串字段（缺失或类型不符返回 null）。
     *
     * @param source 对象
     * @param key    键
     * @return 值；无则 null
     */
    public static String str(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 取嵌套对象。
     *
     * @param source 对象
     * @param key    键
     * @return 嵌套对象；无则 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /**
     * 取长整型字段（缺失或非数字返回默认值）。
     *
     * @param source       对象
     * @param key          键
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    public static long num(Map<String, Object> source, String key, long defaultValue) {
        Object value = source == null ? null : source.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 平台偶尔把数字当字符串返回，解析不了就按默认值
            }
        }
        return defaultValue;
    }

    /** 截断长文本：异常文案里贴整段响应体会把日志和弹窗都撑爆。 */
    private static String summarize(String body) {
        String text = body == null ? "" : body.trim();
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }
}
