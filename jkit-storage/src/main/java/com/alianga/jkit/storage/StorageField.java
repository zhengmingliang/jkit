package com.alianga.jkit.storage;

/**
 * 平台配置字段描述：一个连接配置项（如 bucket / accessKey）的名字、显示名与元信息。
 * 上层界面据此动态渲染配置表单，无需为每个平台写死界面。
 *
 * @author 郑明亮
 */
public final class StorageField {
    private final String key;
    private final String label;
    private final boolean required;
    private final boolean secret;
    private final String defaultValue;
    private final String hint;

    private StorageField(String key, String label, boolean required, boolean secret,
                         String defaultValue, String hint) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.secret = secret;
        this.defaultValue = defaultValue;
        this.hint = hint;
    }

    /**
     * 必填文本字段。
     *
     * @param key   键
     * @param label 显示名
     * @return 字段描述
     */
    public static StorageField required(String key, String label) {
        return new StorageField(key, label, true, false, null, null);
    }

    /**
     * 必填文本字段（带提示）。
     *
     * @param key   键
     * @param label 显示名
     * @param hint  提示文案
     * @return 字段描述
     */
    public static StorageField required(String key, String label, String hint) {
        return new StorageField(key, label, true, false, null, hint);
    }

    /**
     * 可选文本字段。
     *
     * @param key   键
     * @param label 显示名
     * @return 字段描述
     */
    public static StorageField optional(String key, String label) {
        return new StorageField(key, label, false, false, null, null);
    }

    /**
     * 可选文本字段（带默认值与提示）。
     *
     * @param key          键
     * @param label        显示名
     * @param defaultValue 默认值
     * @param hint         提示文案
     * @return 字段描述
     */
    public static StorageField optional(String key, String label, String defaultValue, String hint) {
        return new StorageField(key, label, false, false, defaultValue, hint);
    }

    /**
     * 密钥字段（界面上按密码框展示）。
     *
     * @param key      键
     * @param label    显示名
     * @param required 是否必填
     * @return 字段描述
     */
    public static StorageField secret(String key, String label, boolean required) {
        return new StorageField(key, label, required, true, null, null);
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isSecret() {
        return secret;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public String hint() {
        return hint;
    }
}
