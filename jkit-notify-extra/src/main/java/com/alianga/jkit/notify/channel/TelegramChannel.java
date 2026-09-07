package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

import java.util.Map;

/**
 * Telegram Bot 渠道（sendMessage API）。
 *
 * <p>配置：{@link ChannelConfig#token(String)} 填 Bot Token（{@code BotFather} 发放的
 * {@code 123456:ABC-xxx} 形式）；{@link ChannelConfig#ofToken(String)} 创建即可。
 * {@link ChannelConfig#webhookUrl(String)} 可覆盖自建代理地址
 * （默认 {@code https://api.telegram.org}，国内网络需要自建反代时用它）。
 *
 * <p>聊天目标 {@code chat_id} 从消息 extras 取，键 {@link #EXTRA_CHAT_ID}：
 * 群如 {@code -1001234567890}、频道同群、个人为数字 ID 或 {@code @channelname}。
 * 也支持配置 {@link ChannelConfig#to(String)} 作为缺省 chat_id——消息未带
 * {@code EXTRA_CHAT_ID} 时使用，便于"一个机器人固定发一个群"的常见用法。
 *
 * <p>消息：TEXT / MARKDOWN / HTML。MARKDOWN 先转成 Telegram HTML 支持的标签子集
 * 再发送（{@code parse_mode=HTML}）——Telegram 传统 Markdown 模式只认加粗/斜体/代码/链接，
 * 标题、列表、表格等会原样显示成文本；HTML 按原生 HTML 发送。
 * 静默发送用 {@link #EXTRA_SILENT}；主题群指定话题用
 * {@link #EXTRA_THREAD_ID}。正文按 UTF-8 {@value #MAX_TEXT_BYTES} 字节截断。
 *
 * <p>响应判定：HTTP 2xx 且 JSON {@code ok == true}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class TelegramChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "telegram";

    /**
     * 聊天目标（群/频道/个人 {@code chat_id}），值为数字 ID 或 {@code @channelname}。
     */
    public static final String EXTRA_CHAT_ID = "chatId";

    /**
     * 静默发送（不响铃），值为 Boolean。
     */
    public static final String EXTRA_SILENT = "silent";

    /**
     * 话题群的消息话题 ID（Forum topic）。
     */
    public static final String EXTRA_THREAD_ID = "threadId";

    /**
     * 消息正文 UTF-8 字节上限（Telegram sendMessage 上限 4096 字符，按中文 3 字节保守取 12000）。
     */
    public static final int MAX_TEXT_BYTES = 12000;

    private static final String DEFAULT_ENDPOINT = "https://api.telegram.org";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Telegram Bot";
    }

    @Override
    public boolean supports(MessageType type) {
        return type == MessageType.TEXT || type == MessageType.MARKDOWN || type == MessageType.HTML;
    }

    @Override
    protected String[] usedConfigKeys() {
        return new String[]{"token", "webhook", "to", "timeoutMs"};
    }

    @Override
    protected int contentMaxBytes(Message message) {
        return MAX_TEXT_BYTES;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String token = config.token();
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("telegram bot token is required (ChannelConfig.token)");
        }
        String base = config.webhook();
        if (base == null || base.isEmpty()) {
            base = DEFAULT_ENDPOINT;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/bot" + token + "/sendMessage";
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        String chatId = firstNonEmpty(message.extraString(EXTRA_CHAT_ID), defaultChatId(config));
        if (chatId == null || chatId.isEmpty()) {
            throw new IllegalArgumentException("telegram chat_id is required"
                    + " (TelegramChannel.EXTRA_CHAT_ID or ChannelConfig.to)");
        }
        Map<String, Object> payload = NotifyUtils.map();
        payload.put("chat_id", chatId);
        String title = message.title();
        String parseMode;
        String content;
        switch (message.type()) {
            case MARKDOWN:
                // Telegram 传统 Markdown 模式只支持加粗/斜体/代码/链接，标题、列表、
                // 表格等会原样显示；统一转成 Telegram 支持的 HTML 子集发送
                parseMode = "HTML";
                content = markdownToTelegramHtml(limitedContent(message));
                break;
            case HTML:
                parseMode = "HTML";
                content = limitedContent(message);
                break;
            default:
                parseMode = null;
                content = limitedContent(message);
                break;
        }
        boolean hasTitle = title != null && !title.isEmpty();
        if (hasTitle) {
            // 标题加粗：富文本模式用 <b>x</b>（标题里的 <>& 需转义），纯文本不套语法
            String boldTitle = parseMode == null ? title : "<b>" + escapeHtml(title) + "</b>";
            payload.put("text", boldTitle + "\n" + content);
        } else {
            payload.put("text", content);
        }
        if (parseMode != null) {
            payload.put("parse_mode", parseMode);
        }
        if (message.extraBoolean(EXTRA_SILENT, false)) {
            payload.put("disable_notification", Boolean.TRUE);
        }
        String threadId = message.extraString(EXTRA_THREAD_ID);
        if (threadId != null && !threadId.isEmpty()) {
            payload.put("message_thread_id", threadId);
        }
        return NotifyUtils.toJson(payload);
    }

    /**
     * 把 Markdown 转成 Telegram HTML parse_mode 支持的标签子集。
     *
     * <p>Telegram 的 HTML 模式只认 {@code b/i/u/s/a/code/pre/blockquote}；通用 HTML 里的
     * 标题、段落、列表、表格等标签会被当作纯文本显示。所以先经
     * {@link NotifyUtils#markdownToHtml} 转换，再做标签映射：标题→加粗、段落标签剥掉、
     * 列表转 {@code • } 行、GFM 任务列表 {@code - [x]}/{@code - [ ]} 转为 Unicode 勾选框
     * （{@code ✅}/{@code ⬜}，勾选框本身替代子弹与 {@code [x]}/{@code [ ]}）、表格转竖线分隔行、
     * {@code strong/em/del} 转对应短标签、图片降级为丢弃。
     *
     * @param markdown 原文
     * @return Telegram HTML 片段
     */
    private static String markdownToTelegramHtml(String markdown) {
        String html = NotifyUtils.markdownToHtml(markdown);
        if (html.isEmpty()) {
            return html;
        }
        html = html.replace("<strong>", "<b>").replace("</strong>", "</b>")
                .replace("<em>", "<i>").replace("</em>", "</i>")
                .replace("<del>", "<s>").replace("</del>", "</s>");
        for (int level = 1; level <= 6; level++) {
            html = html.replace("<h" + level + '>', "<b>").replace("</h" + level + '>', "</b>");
        }
        html = html.replace("<p>", "").replace("</p>", "")
                .replace("<br>", "\n")
                .replace("<ul>", "\n").replace("</ul>", "")
                .replace("<ol>", "\n").replace("</ol>", "")
                .replace("<li>", "• ").replace("</li>", "\n");
        // GFM 任务列表：勾选框替代「• + [x]/[ ]」，普通列表仍保留 •
        html = html.replace("• [x] ", "✅ ").replace("• [X] ", "✅ ")
                .replace("• [ ] ", "⬜ ");
        for (String align : new String[]{"left", "center", "right"}) {
            html = html.replace("<th align=\"" + align + "\">", "")
                    .replace("<td align=\"" + align + "\">", "");
        }
        html = html.replace("<th>", "").replace("<td>", "")
                .replace("</th>", " | ").replace("</td>", " | ")
                .replace("<tr>", "").replace("</tr>", "\n")
                .replace("<table>", "").replace("</table>", "")
                .replace("<thead>", "").replace("</thead>", "")
                .replace("<tbody>", "").replace("</tbody>", "")
                .replace("<hr>", "\n-----\n");
        html = stripImgTags(html);
        // 表格行尾与列表末尾的多余分隔符收尾
        html = html.replace(" | \n", "\n");
        // <ul>/<ol> 替换成换行会在纯列表消息头部留下多余 \n，去掉首尾空白
        return trimEdges(html);
    }

    /**
     * @return 去掉 {@code <img ...>} 标签后的文本（Telegram 不支持图片标签）
     */
    private static String stripImgTags(String html) {
        if (!html.contains("<img ")) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (true) {
            int start = html.indexOf("<img ", i);
            if (start < 0) {
                out.append(html, i, html.length());
                break;
            }
            out.append(html, i, start);
            int end = html.indexOf('>', start);
            i = end < 0 ? html.length() : end + 1;
        }
        return out.toString();
    }

    /**
     * @return 去掉首尾换行与空白后的文本（列表适配产生的前导换行对 Telegram 无意义）
     */
    private static String trimEdges(String text) {
        int start = 0;
        int end = text.length();
        while (start < end) {
            char c = text.charAt(start);
            if (c != '\n' && c != ' ') {
                break;
            }
            start++;
        }
        while (end > start) {
            char c = text.charAt(end - 1);
            if (c != '\n' && c != ' ') {
                break;
            }
            end--;
        }
        return text.substring(start, end);
    }

    /**
     * HTML 实体转义（{@code & < >}），Telegram HTML 模式要求标签字符转义。
     *
     * @param text 原文
     * @return 转义后的文本
     */
    private static String escapeHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static String defaultChatId(ChannelConfig config) {
        if (config.to() == null || config.to().isEmpty()) {
            return null;
        }
        return config.to().get(0);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            return a;
        }
        return b;
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        if (httpStatus < 200 || httpStatus >= 300) {
            return false;
        }
        Object parsed = NotifyUtils.parseJson(responseBody);
        if (parsed instanceof Map) {
            Object ok = ((Map<?, ?>) parsed).get("ok");
            return Boolean.TRUE.equals(ok) || "true".equals(String.valueOf(ok));
        }
        // 非 JSON 响应（反代返回 HTML 错误页）按 HTTP 状态兜底
        return true;
    }

    @Override
    protected FailureType classify(int httpStatus, String responseBody) {
        Object parsed = NotifyUtils.parseJson(responseBody);
        if (parsed instanceof Map) {
            Object description = ((Map<?, ?>) parsed).get("description");
            if (description != null) {
                String text = String.valueOf(description);
                if (text.contains("Too Many Requests") || text.contains("flood")) {
                    return FailureType.THROTTLED;
                }
                if (text.contains("chat not found") || text.contains("bot was blocked")
                        || text.contains("unauthorized") || text.contains("token")
                        || text.contains("can't parse entities")) {
                    return FailureType.CONFIG_ERROR;
                }
            }
        }
        return super.classify(httpStatus, responseBody);
    }

    @Override
    protected String errorMessage(int httpStatus, String responseBody) {
        Object parsed = NotifyUtils.parseJson(responseBody);
        if (parsed instanceof Map) {
            Object errorCode = ((Map<?, ?>) parsed).get("error_code");
            Object description = ((Map<?, ?>) parsed).get("description");
            if (description != null) {
                return "telegram"
                        + (errorCode == null ? "" : " " + errorCode)
                        + ": " + description;
            }
        }
        return super.errorMessage(httpStatus, responseBody);
    }
}
