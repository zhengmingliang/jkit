package com.alianga.jkit.notify.channel;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.HttpRequest;
import com.alianga.jkit.http.HttpResponse;
import com.alianga.jkit.log.Log;
import com.alianga.jkit.notify.ChannelConfig;
import com.alianga.jkit.notify.FailureType;
import com.alianga.jkit.notify.Message;
import com.alianga.jkit.notify.NotificationChannel;
import com.alianga.jkit.notify.NotifyUtils;
import com.alianga.jkit.notify.SendResult;

import java.io.IOException;
import java.util.Map;

/**
 * "POST JSON 到 webhook" 型渠道的骨架：组 URL、组 payload、发请求、判定成功，
 * 子类只需要填 {@link #buildUrl} / {@link #buildPayload} / {@link #isAccepted} 三个模板点。
 *
 * <p>统一处理超时、自定义请求头与异常兜底（转成 {@link SendResult#fail}）。
 * 非 POST JSON 形态的渠道（如 SMTP）直接实现 {@link NotificationChannel}。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public abstract class AbstractHttpChannel implements NotificationChannel {
    /**
     * JSON 请求体的标准 Content-Type。
     */
    protected static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private final Log log = Log.get(getClass());

    @Override
    public SendResult send(Message message, ChannelConfig config) {
        long start = System.currentTimeMillis();
        config.logUnused(log, id(), usedConfigKeys());
        HttpResponse response = null;
        try {
            String url = buildUrl(message, config);
            String payload = buildPayload(message, config);
            HttpRequest request = HttpRequest.post(url)
                    .body(payload)
                    .contentType(contentType(message, config));
            if (config.timeoutMs() > 0) {
                request.totalTimeoutMs(config.timeoutMs());
            }
            applyHeaders(request, config, payload);
            response = HttpUtils.execute(request);
            long elapsed = System.currentTimeMillis() - start;
            String body = readBody(response);
            int status = response.code();
            if (response.isSuccessful() && isAccepted(status, body)) {
                return SendResult.ok(id(), status, body, elapsed);
            }
            String error = errorMessage(status, body);
            FailureType type = classify(status, body);
            log.warn("[{}] send failed({}): {}", id(), type, error);
            return SendResult.fail(id(), status, body, error, elapsed, type);
        } catch (IllegalArgumentException e) {
            // 配置缺失等编程错误直接暴露给调用方，不吞成发送失败
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[{}] send error: {}", id(), e.getMessage());
            return SendResult.fail(id(), e.getClass().getSimpleName() + ": " + e.getMessage(),
                    elapsed, FailureType.RETRYABLE);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 本渠道会读取的 {@link ChannelConfig} 字段。配了但不在此列的字段会打 debug 日志。
     *
     * @return 字段名
     */
    protected String[] usedConfigKeys() {
        return new String[]{"timeoutMs"};
    }

    /**
     * 构造请求 URL（含加签参数）。
     *
     * @param message 消息
     * @param config 渠道配置
     * @return 完整 URL
     */
    protected abstract String buildUrl(Message message, ChannelConfig config);

    /**
     * 构造请求体。
     *
     * @param message 消息
     * @param config 渠道配置
     * @return JSON / 表单编码等请求体字符串
     */
    protected abstract String buildPayload(Message message, ChannelConfig config);

    /**
     * 请求 Content-Type。默认 JSON（与 {@link #JSON_CONTENT_TYPE} 一致）；
     * 表单编码的渠道（如华为云短信）覆盖为 {@code application/x-www-form-urlencoded}。
     *
     * @param message 消息
     * @param config 渠道配置
     * @return Content-Type 请求头值
     * @since 2.0.1
     */
    protected String contentType(Message message, ChannelConfig config) {
        return JSON_CONTENT_TYPE;
    }

    /**
     * HTTP 2xx 之外的业务级成功判定（默认恒成功，即只看 HTTP 状态码）。
     *
     * @param httpStatus HTTP 状态码
     * @param responseBody 响应正文
     * @return 业务上是否成功
     */
    protected boolean isAccepted(int httpStatus, String responseBody) {
        return true;
    }

    /**
     * 失败时的人类可读原因，默认 {@code http status xxx: body}。
     *
     * @param httpStatus HTTP 状态码
     * @param responseBody 响应正文
     * @return 失败原因
     */
    protected String errorMessage(int httpStatus, String responseBody) {
        return "http status " + httpStatus + ": " + abbreviate(responseBody);
    }

    /**
     * 判定失败类别。默认只按 HTTP 状态码分类（这层语义各平台一致）：
     * 5xx 与 408 为 {@link FailureType#RETRYABLE}，429 为 {@link FailureType#THROTTLED}，
     * 其余 4xx 为 {@link FailureType#CONFIG_ERROR}，HTTP 2xx 但业务码失败为
     * {@link FailureType#PERMANENT}。
     *
     * <p>子类覆盖本方法把平台业务错误码映射进来（如"签名不匹配"→ CONFIG_ERROR、
     * "发送太快"→ THROTTLED），建议先处理自己认识的错误码，剩下的交回
     * {@code super.classify(...)}。
     *
     * @param httpStatus HTTP 状态码
     * @param responseBody 响应正文
     * @return 失败类别
     */
    protected FailureType classify(int httpStatus, String responseBody) {
        if (httpStatus >= 500 || httpStatus == 408) {
            return FailureType.RETRYABLE;
        }
        if (httpStatus == 429) {
            return FailureType.THROTTLED;
        }
        if (httpStatus >= 400) {
            return FailureType.CONFIG_ERROR;
        }
        return FailureType.PERMANENT;
    }

    /**
     * 正文的 UTF-8 字节上限，{@code 0} 表示该渠道不做限制（默认）。
     *
     * <p>返回正数时 {@link #limitedContent(Message)} 会按此上限做字节安全截断。
     *
     * @param message 消息（不同消息类型上限可能不同）
     * @return 字节上限
     */
    protected int contentMaxBytes(Message message) {
        return 0;
    }

    /**
     * 取应用了长度上限的正文，供 {@link #buildPayload} 使用。
     *
     * @param message 消息
     * @return 截断后的正文；未超限或渠道不限长时为原文
     */
    protected String limitedContent(Message message) {
        return NotifyUtils.truncateUtf8(message.content(), contentMaxBytes(message));
    }

    /**
     * 按上限截断，并预留 {@code reservedBytes} 给稍后追加的后缀（如钉钉 @手机号）。
     *
     * @param content 原文
     * @param maxBytes 总上限
     * @param reservedBytes 需要预留的后缀字节数
     * @return 截断后的正文（不含后缀）
     */
    protected String limitedContent(String content, int maxBytes, int reservedBytes) {
        if (maxBytes <= 0) {
            return content;
        }
        int budget = maxBytes - Math.max(0, reservedBytes);
        if (budget < 1) {
            budget = maxBytes;
        }
        return NotifyUtils.truncateUtf8(content, budget);
    }

    /**
     * 应用配置里的自定义请求头（通用 Webhook 等渠道使用），同名键覆盖默认头。
     *
     * @param request 请求
     * @param config 渠道配置
     * @param payload 已构造好的请求体（签名请求等需要基于正文计算请求头的渠道使用）
     * @since 2.0.1
     */
    protected void applyHeaders(HttpRequest request, ChannelConfig config, String payload) {
        Map<String, String> headers = config.headers();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                request.header(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * @return 渠道日志
     */
    protected Log log() {
        return log;
    }

    /**
     * JSON 布尔字段 {@code ok == true}（Slack chat.postMessage / Telegram 等）。
     *
     * <p>非 JSON 响应（如 Incoming Webhook 纯文本 {@code ok}、反代 HTML 错误页）返回
     * {@code true}，交给调用方已通过的 HTTP 状态码判定。
     *
     * @param responseBody 响应正文
     * @return 业务是否成功
     * @since 2.0.1
     */
    protected static boolean jsonOk(String responseBody) {
        Object parsed = NotifyUtils.parseJson(responseBody);
        if (!(parsed instanceof Map)) {
            return true;
        }
        Object ok = ((Map<?, ?>) parsed).get("ok");
        return Boolean.TRUE.equals(ok) || "true".equals(String.valueOf(ok));
    }

    /**
     * JSON 整型字段是否等于期望值；解析失败或缺字段返回 {@code false}。
     *
     * @param responseBody 响应正文
     * @param key 字段名
     * @param expected 期望值
     * @return 是否相等
     * @since 2.0.1
     */
    protected static boolean jsonIntEquals(String responseBody, String key, int expected) {
        Integer value = NotifyUtils.jsonInt(responseBody, key);
        return value != null && value.intValue() == expected;
    }

    /**
     * 按顺序取第一个非 null 的 JSON 整型字段（飞书 {@code code} / {@code StatusCode}）。
     *
     * @param responseBody 响应正文
     * @param keys 字段名，按优先级
     * @return 整型值；全部缺失为 {@code null}
     * @since 2.0.1
     */
    protected static Integer jsonIntField(String responseBody, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            Integer value = NotifyUtils.jsonInt(responseBody, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 按顺序取第一个非 null 的 JSON 字符串字段。
     *
     * @param responseBody 响应正文
     * @param keys 字段名，按优先级
     * @return 字符串；全部缺失为 {@code null}
     * @since 2.0.1
     */
    protected static String jsonStringField(String responseBody, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = NotifyUtils.jsonString(responseBody, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 拼平台错误文案 {@code label + " " + code + ": " + message}；码或文案缺失返回 {@code null}。
     *
     * @param label 前缀，如 {@code bark code}、{@code dingtalk errcode}
     * @param responseBody 响应正文
     * @param codeKey 错误码字段
     * @param messageKeys 错误文案字段（可多备选）
     * @return 文案或 {@code null}
     * @since 2.0.1
     */
    protected static String jsonCodeMessage(String label, String responseBody,
            String codeKey, String... messageKeys) {
        Integer code = NotifyUtils.jsonInt(responseBody, codeKey);
        String message = jsonStringField(responseBody, messageKeys);
        if (code == null || message == null) {
            return null;
        }
        return label + " " + code + ": " + message;
    }

    private static String readBody(HttpResponse response) throws IOException {
        if (response.body() == null) {
            return "";
        }
        String body = response.body().string();
        return body == null ? "" : body;
    }

    /**
     * 截断过长响应正文，避免日志与 {@link SendResult#error()} 膨胀。
     *
     * @param text 原文
     * @return 至多 200 字符的摘要
     */
    protected static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
