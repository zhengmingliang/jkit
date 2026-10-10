package com.alianga.jkit.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存储连接配置：一组键值对（endpoint / bucket / accessKey / secretKey / basePath 等），
 * 每个平台的必填与可选项由 {@link StoragePlatform} 描述。
 *
 * <p>刻意保持「纯字符串键值对」形态：上层（如图形界面的配置管理）可以直接把它
 * 序列化 / 反序列化，也可以用同一套配置驱动任意平台。</p>
 *
 * @author 郑明亮
 */
public final class StorageConfig {
    private final Map<String, String> values;

    private StorageConfig(Map<String, String> values) {
        this.values = values;
    }

    /**
     * 空配置。
     *
     * @return 空配置
     */
    public static StorageConfig empty() {
        return new StorageConfig(new LinkedHashMap<String, String>());
    }

    /**
     * 由既有键值对构造配置（内部拷贝，保持插入序）。
     *
     * @param values 键值对，null 视为空
     * @return 配置
     */
    public static StorageConfig of(Map<String, String> values) {
        StorageConfig config = empty();
        if (values != null) {
            config.values.putAll(values);
        }
        return config;
    }

    /**
     * 设置一项配置，返回自身以便链式调用。
     *
     * @param key   键
     * @param value 值，null 视为移除
     * @return 自身
     */
    public StorageConfig set(String key, String value) {
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        return this;
    }

    /**
     * 读取一项配置。
     *
     * @param key 键
     * @return 值，未设置返回 null
     */
    public String get(String key) {
        return values.get(key);
    }

    /**
     * 读取一项必填配置。
     *
     * @param key 键
     * @return 值
     * @throws StorageException 未设置或为空白时抛 {@link StorageErrorCode#INVALID_CONFIG}
     */
    public String require(String key) {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw StorageException.invalidConfig("缺少必填配置: " + key);
        }
        return value.trim();
    }

    /**
     * 读取配置或默认值。
     *
     * @param key          键
     * @param defaultValue 未设置时返回的值
     * @return 值或默认值
     */
    public String getOr(String key, String defaultValue) {
        String value = values.get(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    /**
     * 读取布尔配置（true / 1 / yes 视为真）。
     *
     * @param key          键
     * @param defaultValue 未设置时的值
     * @return 布尔值
     */
    public boolean boolOr(String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    /**
     * 读取整型配置。
     *
     * @param key          键
     * @param defaultValue 未设置或非法时的值
     * @return 整型值
     */
    public int intOr(String key, int defaultValue) {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 读取长整型配置。
     *
     * @param key          键
     * @param defaultValue 未设置或非法时的值
     * @return 长整型值
     */
    public long longOr(String key, long defaultValue) {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 把 defaults 中调用方未设置的键补进来，返回新配置（不修改自身）。
     *
     * @param defaults 平台默认值
     * @return 合并后的新配置
     */
    public StorageConfig withDefaults(StorageConfig defaults) {
        StorageConfig merged = of(this.values);
        if (defaults != null) {
            for (Map.Entry<String, String> entry : defaults.asMap().entrySet()) {
                if (!merged.values.containsKey(entry.getKey())) {
                    merged.values.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged;
    }

    /**
     * 只读视图。
     *
     * @return 键值对只读视图
     */
    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
