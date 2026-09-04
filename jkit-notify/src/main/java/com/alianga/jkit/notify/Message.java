package com.alianga.jkit.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知消息模型：类型 + 标题 + 正文 + 渠道扩展参数。
 *
 * <p>用法示例：
 * <pre>{@code
 * Message msg = Message.text("告警", "CPU 使用率 95%")
 *         .extra(Message.EXTRA_AT_MOBILES, "13800000000,13900000000");
 * Message md = Message.markdown("部署通知", "## 发布成功\n- 版本 v1.2.3");
 * }</pre>
 *
 * <p>{@code extras} 是渠道相关的附加参数（如钉钉/企微的 @手机号、Bark 的铃声），
 * 各渠道只取自己认识的键，互不干扰。内置的常用键见 {@link #EXTRA_AT_MOBILES} 等常量。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class Message {
    /**
     * 钉钉/企微 @指定手机号，值为逗号分隔字符串或字符串集合。
     */
    public static final String EXTRA_AT_MOBILES = "atMobiles";

    /**
     * 钉钉/企微 @所有人，值为 Boolean。
     */
    public static final String EXTRA_AT_ALL = "atAll";

    /**
     * Bark 提示铃声，值为铃声名，如 {@code minuet}。
     */
    public static final String EXTRA_SOUND = "sound";

    /**
     * Bark 消息分组，值为分组名。
     */
    public static final String EXTRA_GROUP = "group";

    /**
     * Bark 时效性：{@code active} / {@code timeSensitive} / {@code passive}。
     */
    public static final String EXTRA_LEVEL = "level";

    /**
     * 通知点击跳转地址（Bark、通用 Webhook 等渠道使用）。
     */
    public static final String EXTRA_URL = "url";

    private final MessageType type;
    private final String title;
    private final String content;
    private final Map<String, Object> extras;

    private Message(MessageType type, String title, String content) {
        this.type = type;
        this.title = title;
        this.content = content;
        this.extras = new LinkedHashMap<String, Object>();
    }

    /**
     * 构造纯文本消息（无标题）。
     *
     * @param content 正文
     * @return 消息
     */
    public static Message text(String content) {
        return text(null, content);
    }

    /**
     * 构造纯文本消息。
     *
     * @param title 标题，可为 {@code null}（不关心标题的渠道会忽略）
     * @param content 正文
     * @return 消息
     */
    public static Message text(String title, String content) {
        return new Message(MessageType.TEXT, title, content);
    }

    /**
     * 构造 Markdown 消息。
     *
     * @param title 标题
     * @param content Markdown 正文
     * @return 消息
     */
    public static Message markdown(String title, String content) {
        return new Message(MessageType.MARKDOWN, title, content);
    }

    /**
     * 构造 HTML 消息。
     *
     * @param title 标题
     * @param content HTML 正文
     * @return 消息
     */
    public static Message html(String title, String content) {
        return new Message(MessageType.HTML, title, content);
    }

    /**
     * 追加一个渠道扩展参数，链式调用。
     *
     * @param key 参数名，常用键见本类常量
     * @param value 参数值
     * @return this
     */
    public Message extra(String key, Object value) {
        if (key != null) {
            extras.put(key, value);
        }
        return this;
    }

    /**
     * @return 消息类型
     */
    public MessageType type() {
        return type;
    }

    /**
     * @return 标题，可能为 {@code null}
     */
    public String title() {
        return title;
    }

    /**
     * @return 标题；为 {@code null} 时返回 {@code fallback}
     */
    public String title(String fallback) {
        return title == null ? fallback : title;
    }

    /**
     * @return 正文
     */
    public String content() {
        return content;
    }

    /**
     * @return 扩展参数（只读视图）
     */
    public Map<String, Object> extras() {
        return Collections.unmodifiableMap(extras);
    }

    /**
     * 读取字符串型扩展参数。
     *
     * @param key 参数名
     * @return 参数值的字符串形式；不存在时返回 {@code null}
     */
    public String extraString(String key) {
        Object value = extras.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 读取布尔型扩展参数。
     *
     * @param key 参数名
     * @param defaultValue 参数不存在或非布尔时的默认值
     * @return 布尔值
     */
    public boolean extraBoolean(String key, boolean defaultValue) {
        Object value = extras.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    /**
     * 读取字符串列表型扩展参数，接受逗号分隔字符串、数组或集合。
     *
     * @param key 参数名
     * @return 字符串列表；参数不存在时返回 {@code null}
     */
    public List<String> extraStrings(String key) {
        Object value = extras.get(key);
        if (value == null) {
            return null;
        }
        List<String> out = new ArrayList<String>();
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else {
            for (String item : String.valueOf(value).split(",")) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    /**
     * @return 标题 + 正文的调试描述
     */
    @Override
    public String toString() {
        return "Message{type=" + type + ", title=" + title + ", contentLength="
                + (content == null ? 0 : content.length()) + ", extras=" + extras.keySet() + "}";
    }
}
