package com.alianga.jkit.json;

import com.alianga.jkit.json.annotations.JsonProperty;
import com.alianga.jkit.json.options.ReadOption;

import java.util.regex.Pattern;

abstract class JSONSchemaBase {
    /** 校验 {@code format} 为 url 时使用的正则 */
    public static final Pattern PATTERN_URL =
            Pattern.compile("^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]");
    /** 校验 {@code format} 为 email 时使用的正则 */
    public static final Pattern PATTERN_EMAIL = Pattern.compile("^[a-zA-Z0-9_]+@[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$");
    // 解析时支持注释，单引号，双引号，末尾逗号
    static final ReadOption[] PERFECT_READ_OPTIONS = new ReadOption[]
            {
                    ReadOption.AllowComment,
                    ReadOption.AllowUnquotedFieldNames,
                    ReadOption.AllowSingleQuotes,
                    ReadOption.AllowLastEndComma,
            };

    /** JSONSchema root，指向整棵 schema 树的根节点，用于解析 $ref */
    @JsonProperty(deserialize = false, serialize = false)
    protected JSONSchema root;

    @JsonProperty(deserialize = false, serialize = false)
    private Boolean __formatUrl;
    @JsonProperty(deserialize = false, serialize = false)
    private Boolean __formatEmail;
    @JsonProperty(deserialize = false, serialize = false)
    private Boolean __formatDate;

    /**
     * 判断 format 是否为 url，结果会被缓存。
     *
     * @return format 等于 {@code "url"} 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean formatUrl() {
        String format = getFormat();
        if (this.__formatUrl != null) {
            return this.__formatUrl;
        }
        return __formatUrl = "url".equals(format);
    }

    /**
     * 判断 format 是否为 date，结果会被缓存。
     *
     * @return format 等于 {@code "date"} 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean formatDate() {
        String format = getFormat();
        if (this.__formatDate != null) {
            return this.__formatDate;
        }
        return __formatDate = "date".equals(format);
    }

    /**
     * 判断 format 是否为 email，结果会被缓存。
     *
     * @return format 等于 {@code "email"} 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean formatEmail() {
        String format = getFormat();
        if (this.__formatEmail != null) {
            return this.__formatEmail;
        }
        return __formatEmail = "email".equals(format);
    }

    /**
     * 获取当前 schema 声明的数据格式。
     *
     * @return format 值，未声明时为 {@code null}
     */
    public abstract String getFormat();

    /**
     * 获取整棵 schema 树的根节点。
     *
     * @return 根 schema，尚未绑定 root 时为 {@code null}
     */
    public JSONSchema root() {
        return root;
    }
}
