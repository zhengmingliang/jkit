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
    private final java.util.List<Option> options;
    private final boolean editableChoice;

    private StorageField(String key, String label, boolean required, boolean secret,
                         String defaultValue, String hint) {
        this(key, label, required, secret, defaultValue, hint, null, false);
    }

    private StorageField(String key, String label, boolean required, boolean secret,
                         String defaultValue, String hint, java.util.List<Option> options,
                         boolean editableChoice) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.secret = secret;
        this.defaultValue = defaultValue;
        this.hint = hint;
        this.options = options == null || options.isEmpty()
                ? java.util.Collections.<Option>emptyList()
                : java.util.Collections.unmodifiableList(options);
        this.editableChoice = editableChoice;
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

    /**
     * 带提示文案的密钥字段。
     *
     * @param key      键
     * @param label    显示名
     * @param required 是否必填
     * @param hint     提示文案
     * @return 字段描述
     */
    public static StorageField secret(String key, String label, boolean required, String hint) {
        return new StorageField(key, label, required, true, null, hint);
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

    /**
     * 枚举选择字段：界面渲染为下拉，选项由引擎/预设声明（界面零硬编码）。
     *
     * @param key          键
     * @param label        显示名
     * @param defaultValue 默认值
     * @param hint         提示文案
     * @param editable     true 表示内置选项只是快捷入口，允许手填其它值
     * @param options      选项（值 + 显示名）
     * @return 字段描述
     */
    public static StorageField choice(String key, String label, String defaultValue, String hint,
                                      boolean editable, Option... options) {
        java.util.List<Option> list = new java.util.ArrayList<Option>();
        for (Option option : options) {
            list.add(option);
        }
        return new StorageField(key, label, false, false, defaultValue, hint, list, editable);
    }

    /** @return true 表示枚举选择字段（有选项清单） */
    public boolean isChoice() {
        return !options.isEmpty();
    }

    /** @return 枚举选项（只读；非枚举字段为空清单） */
    public java.util.List<Option> options() {
        return options;
    }

    /** @return true 表示枚举字段允许手填内置选项之外的值 */
    public boolean isChoiceEditable() {
        return editableChoice;
    }

    /**
     * 枚举选项：写入配置的值 + 界面显示名。
     *
     * @author 郑明亮
     */
    public static final class Option {
        private final String value;
        private final String label;

        /**
         * @param value 写入配置的值
         * @param label 界面显示名（键与代码逐字符一致，由 i18n 层翻译）
         */
        public Option(String value, String label) {
            this.value = value;
            this.label = label;
        }

        /** @return 写入配置的值 */
        public String value() {
            return value;
        }

        /** @return 界面显示名 */
        public String label() {
            return label;
        }
    }
}
