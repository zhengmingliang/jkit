package com.alianga.jkit.notify.channel;

import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.MessageType;
import com.alianga.jkit.notify.NotifyUtils;

/**
 * 通用 Webhook 渠道：任意"POST JSON"风格接口的兜底方案（Slack 风格、企业自建网关等）。
 *
 * <p>配置：
 * <ul>
 * <li>{@link ChannelConfig#webhook(String)}：接口地址，必填；</li>
 * <li>{@link ChannelConfig#payloadTemplate(String)}：请求体模板，占位符
 * {@code ${title}}、{@code ${content}}，缺省 {@code {"text":"${title}\n${content}"}}；
 * 替换值做 JSON 转义，正文里的引号不会破坏结构；</li>
 * <li>{@link ChannelConfig#header(String, String)}：自定义请求头（如
 * {@code Authorization: Bearer xxx}、Slack 的 {@code text} 结构由模板表达）。</li>
 * </ul>
 *
 * <p>消息：TEXT / MARKDOWN / HTML 全支持（正文按模板原样嵌入）。
 * 响应判定：HTTP 2xx。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public class WebhookChannel extends AbstractHttpChannel {
    /**
     * 渠道 id。
     */
    public static final String ID = "webhook";

    private static final String DEFAULT_TEMPLATE = "{\"text\":\"${title}\\n${content}\"}";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "通用 Webhook";
    }

    @Override
    public boolean supports(MessageType type) {
        return true;
    }

    @Override
    protected String buildUrl(Message message, ChannelConfig config) {
        String webhook = config.webhook();
        if (webhook == null || webhook.isEmpty()) {
            throw new IllegalArgumentException("webhook url is required (ChannelConfig.webhook)");
        }
        return webhook;
    }

    @Override
    protected String buildPayload(Message message, ChannelConfig config) {
        String template = config.payloadTemplate();
        if (template == null || template.isEmpty()) {
            template = DEFAULT_TEMPLATE;
        }
        return render(template, message.title("通知"), limitedContent(message));
    }

    /**
     * 把模板里的 {@code ${title}}、{@code ${content}} 占位符替换为消息内容（JSON 转义后）。
     * 只做字面替换，值内若出现 {@code ${...}} 不会被再次展开。
     *
     * @param template 模板
     * @param title 标题
     * @param content 正文
     * @return 渲染结果
     */
    private static String render(String template, String title, String content) {
        StringBuilder out = new StringBuilder(template.length() + content.length());
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("${", i);
            if (start < 0) {
                out.append(template, i, template.length());
                break;
            }
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, start);
            String key = template.substring(start + 2, end).trim();
            if ("title".equals(key)) {
                out.append(NotifyUtils.jsonEscape(title));
            } else if ("content".equals(key)) {
                out.append(NotifyUtils.jsonEscape(content));
            } else {
                out.append(template, start, end + 1);
            }
            i = end + 1;
        }
        return out.toString();
    }

    @Override
    protected boolean isAccepted(int httpStatus, String responseBody) {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
