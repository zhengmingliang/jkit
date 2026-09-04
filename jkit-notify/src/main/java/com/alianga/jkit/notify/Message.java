package com.alianga.jkit.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知消息模型：类型 + 标题 + 正文 + 渠道扩展参数 + 附件。
 *
 * <p>用法示例：
 * <pre>{@code
 * Message msg = Message.text("告警", "CPU 使用率 95%")
 *         .extra(Message.EXTRA_AT_MOBILES, "13800000000,13900000000");
 * Message md = Message.markdown("部署通知", "## 发布成功\n- 版本 v1.2.3");
 * Message card = Message.actionCard("发布", "v1.2.3 已上线", "查看详情", "https://ci.example.com/42");
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
     * 企微/钉钉 @指定 userid，值为逗号分隔字符串或字符串集合。
     *
     * <p>企微 TEXT 写入 {@code mentioned_list}；企微 MARKDOWN 会把缺失的
     * {@code <@userid>} 追加到正文。钉钉写入 {@code at.atUserIds}。
     */
    public static final String EXTRA_AT_USERIDS = "atUserIds";

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
     * 通知点击跳转地址（Bark、通用 Webhook、钉钉 actionCard 等渠道使用）。
     */
    public static final String EXTRA_URL = "url";

    /**
     * 钉钉 actionCard 单按钮文案。
     */
    public static final String EXTRA_BTN_TITLE = "btnTitle";

    /**
     * 钉钉 actionCard 按钮排列：{@code true} 竖排（每个按钮单独一行）。
     */
    public static final String EXTRA_BTN_VERTICAL = "btnOrientationVertical";

    /**
     * 图片消息的 picurl / 封面图地址（企微 news、钉钉 link/feedCard）。
     */
    public static final String EXTRA_PIC_URL = "picUrl";

    /**
     * 企微图片消息的 md5（与 {@link #EXTRA_BASE64} 成对）。
     */
    public static final String EXTRA_MD5 = "md5";

    /**
     * 企微图片消息的 base64（不含 data: 前缀）。
     */
    public static final String EXTRA_BASE64 = "base64";

    private final MessageType type;
    private final String title;
    private final String content;
    private final Map<String, Object> extras;
    private final List<Attachment> attachments;
    private Map<String, Object> vars;

    private Message(MessageType type, String title, String content) {
        this.type = type;
        this.title = title;
        this.content = content;
        this.extras = new LinkedHashMap<String, Object>();
        this.attachments = new ArrayList<Attachment>();
        this.vars = null;
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
     * 构造钉钉独立跳转 ActionCard（单按钮）。
     *
     * <p>仅钉钉渠道支持；其它渠道发送前会被 {@link NotificationManager} 拒绝。
     * 多按钮用 {@link #extra(String, Object)} 自行扩展，或多次调用本工厂后改 extras。
     *
     * @param title 卡片标题
     * @param markdown 卡片正文（钉钉 markdown）
     * @param btnTitle 按钮文案
     * @param btnUrl 按钮跳转地址
     * @return 消息
     */
    public static Message actionCard(String title, String markdown, String btnTitle, String btnUrl) {
        return new Message(MessageType.ACTION_CARD, title, markdown)
                .extra(EXTRA_BTN_TITLE, btnTitle)
                .extra(EXTRA_URL, btnUrl);
    }

    /**
     * 构造企微 markdown_v2 消息（比 markdown 支持的语法更多）。
     *
     * <p>仅企微渠道支持。
     *
     * @param title 标题（企微 markdown_v2 本身不单独使用标题，部分场景可忽略）
     * @param content markdown_v2 正文
     * @return 消息
     */
    public static Message markdownV2(String title, String content) {
        return new Message(MessageType.MARKDOWN_V2, title, content);
    }

    /**
     * 构造企微图片消息（base64 + md5）。钉钉图片需走媒体上传拿 media_id，群机器人本身不支持直接发图文件。
     *
     * @param title 标题（部分渠道忽略）
     * @param imageBytes 图片字节
     * @return 消息
     */
    public static Message image(String title, byte[] imageBytes) {
        byte[] data = imageBytes == null ? new byte[0] : imageBytes;
        return new Message(MessageType.IMAGE, title, "image")
                .extra(EXTRA_BASE64, NotifyUtils.base64(data))
                .extra(EXTRA_MD5, NotifyUtils.md5Hex(data));
    }

    /**
     * 构造图文卡片（企微 news / 钉钉 feedCard 单条）。
     *
     * @param title 标题
     * @param description 摘要
     * @param url 跳转地址
     * @param picUrl 封面图地址，可为 {@code null}
     * @return 消息
     */
    public static Message news(String title, String description, String url, String picUrl) {
        String body = description == null || description.trim().isEmpty()
                ? (title == null || title.trim().isEmpty() ? "news" : title) : description;
        return new Message(MessageType.NEWS, title, body)
                .extra(EXTRA_URL, url)
                .extra(EXTRA_PIC_URL, picUrl);
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
     * 追加一个附件（SMTP 使用；其它渠道忽略）。
     *
     * @param attachment 附件
     * @return this
     */
    public Message attachment(Attachment attachment) {
        if (attachment != null) {
            attachments.add(attachment);
        }
        return this;
    }

    /**
     * 追加多个附件（SMTP 使用）。
     *
     * @param items 附件
     * @return this
     */
    public Message attachments(Attachment... items) {
        if (items != null) {
            for (Attachment item : items) {
                attachment(item);
            }
        }
        return this;
    }

    /**
     * 绑定模板变量。标题和正文里的 {@code ${key}} / {@code ${a.b}} 在发送前替换。
     *
     * @param vars 变量，{@code null} 表示不渲染
     * @return this
     */
    public Message vars(Map<String, ?> vars) {
        if (vars == null) {
            this.vars = null;
        } else {
            this.vars = new LinkedHashMap<String, Object>(vars);
        }
        return this;
    }

    /**
     * 追加一个模板变量。
     *
     * @param key 变量名
     * @param value 值
     * @return this
     */
    public Message var(String key, Object value) {
        if (key == null) {
            return this;
        }
        if (this.vars == null) {
            this.vars = new LinkedHashMap<String, Object>();
        }
        this.vars.put(key, value);
        return this;
    }

    /**
     * 用已绑定变量渲染标题和正文，得到新消息（extras / 附件共享同一引用）。
     *
     * <p>未绑定变量时返回 this。发送门面会自动调用，一般不必手调。
     *
     * @return 渲染后的消息
     */
    public Message rendered() {
        if (vars == null || vars.isEmpty()) {
            return this;
        }
        Message copy = new Message(type, NotifyUtils.renderTemplate(title, vars),
                NotifyUtils.renderTemplate(content, vars));
        copy.extras.putAll(this.extras);
        copy.attachments.addAll(this.attachments);
        return copy;
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
     * @return 附件列表（只读视图）
     */
    public List<Attachment> attachments() {
        return Collections.unmodifiableList(attachments);
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
                + (content == null ? 0 : content.length()) + ", extras=" + extras.keySet()
                + ", attachments=" + attachments.size() + "}";
    }
}
