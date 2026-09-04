package com.alianga.jkit.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 发送结果：一次 {@link NotificationChannel#send(Message, ChannelConfig)} 的统一返回。
 *
 * <p>网络错误、协议错误、业务错误码都以 {@code fail(...)} 形式返回，渠道实现保证不抛出异常
 * （配置非法等编程错误在 {@link NotificationManager} 层即抛出 {@link IllegalArgumentException}）。
 *
 * <p>失败时带 {@link #failureType()}，调用方据此决定重试策略；简单场景直接用
 * {@link #isRetryable()}：
 * <pre>{@code
 * SendResult r = NotificationManager.send("dingtalk", msg, cfg);
 * if (r.isFailed() && r.isRetryable()) {
 *     // 退避后重试；THROTTLED 建议用更长的退避
 * }
 * }</pre>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SendResult {
    private final String channelId;
    private final boolean success;
    private final int status;
    private final String response;
    private final String error;
    private final long elapsedMs;
    private final FailureType failureType;
    private final List<SendResult> parts;

    private SendResult(String channelId, boolean success, int status, String response,
                       String error, long elapsedMs, FailureType failureType, List<SendResult> parts) {
        this.channelId = channelId;
        this.success = success;
        this.status = status;
        this.response = response;
        this.error = error;
        this.elapsedMs = elapsedMs;
        this.failureType = failureType == null ? FailureType.PERMANENT : failureType;
        this.parts = parts == null ? Collections.<SendResult>emptyList() : parts;
    }

    /**
     * 构造成功结果。
     *
     * @param channelId 渠道 id
     * @param status HTTP 状态码或 0（无 HTTP 语义时）
     * @param response 响应正文
     * @param elapsedMs 耗时毫秒
     * @return 成功结果
     */
    public static SendResult ok(String channelId, int status, String response, long elapsedMs) {
        return new SendResult(channelId, true, status, response, null, elapsedMs, FailureType.NONE, null);
    }

    /**
     * 构造失败结果（未拿到响应，如网络异常），失败类别按 {@link FailureType#RETRYABLE} 处理。
     *
     * @param channelId 渠道 id
     * @param error 失败原因
     * @return 失败结果
     */
    public static SendResult fail(String channelId, String error) {
        return new SendResult(channelId, false, 0, null, error, 0L, FailureType.RETRYABLE, null);
    }

    /**
     * 构造失败结果（未拿到响应），显式指定失败类别。
     *
     * @param channelId 渠道 id
     * @param error 失败原因
     * @param failureType 失败类别
     * @return 失败结果
     */
    public static SendResult fail(String channelId, String error, FailureType failureType) {
        return new SendResult(channelId, false, 0, null, error, 0L, failureType, null);
    }

    /**
     * 构造失败结果（未拿到响应），带耗时。
     *
     * @param channelId 渠道 id
     * @param error 失败原因
     * @param elapsedMs 耗时毫秒
     * @param failureType 失败类别
     * @return 失败结果
     */
    public static SendResult fail(String channelId, String error, long elapsedMs, FailureType failureType) {
        return new SendResult(channelId, false, 0, null, error, elapsedMs, failureType, null);
    }

    /**
     * 构造失败结果（拿到了响应但业务不成功）。
     *
     * @param channelId 渠道 id
     * @param status HTTP 状态码
     * @param response 响应正文
     * @param error 人类可读的失败原因
     * @param elapsedMs 耗时毫秒
     * @param failureType 失败类别
     * @return 失败结果
     */
    public static SendResult fail(String channelId, int status, String response, String error,
                                  long elapsedMs, FailureType failureType) {
        return new SendResult(channelId, false, status, response, error, elapsedMs, failureType, null);
    }

    /**
     * 把多次发送（附件拆包、多渠道 fan-out）聚合成一条结果：全部成功才算成功。
     *
     * @param channelId 渠道 id；多渠道聚合时可传 {@code all}
     * @param parts 各次发送结果，顺序保留
     * @return 聚合结果
     */
    public static SendResult aggregate(String channelId, List<SendResult> parts) {
        return aggregate(channelId, parts, false);
    }

    /**
     * 聚合多次发送。{@code succeedIfAny} 为 true 时（故障转移）有一次成功即整体成功。
     *
     * @param channelId 渠道 id
     * @param parts 子结果
     * @param succeedIfAny 是否“任一成功即成功”
     * @return 聚合结果
     */
    public static SendResult aggregate(String channelId, List<SendResult> parts, boolean succeedIfAny) {
        if (parts == null || parts.isEmpty()) {
            return fail(channelId, "no send results", FailureType.PERMANENT);
        }
        boolean anyOk = false;
        boolean allOk = true;
        long elapsed = 0L;
        FailureType worst = FailureType.NONE;
        StringBuilder errors = new StringBuilder();
        String lastResponse = null;
        int lastStatus = 0;
        for (SendResult part : parts) {
            elapsed += part.elapsedMs();
            lastStatus = part.status();
            lastResponse = part.response();
            if (part.isSuccess()) {
                anyOk = true;
            } else {
                allOk = false;
                worst = worse(worst, part.failureType());
                if (errors.length() > 0) {
                    errors.append("; ");
                }
                errors.append(part.channelId()).append(": ").append(part.error());
            }
        }
        List<SendResult> copy = Collections.unmodifiableList(new ArrayList<SendResult>(parts));
        boolean ok = succeedIfAny ? anyOk : allOk;
        if (ok) {
            return new SendResult(channelId, true, lastStatus, lastResponse,
                    allOk ? null : errors.toString(), elapsed, FailureType.NONE, copy);
        }
        return new SendResult(channelId, false, lastStatus, lastResponse, errors.toString(), elapsed, worst, copy);
    }

    private static FailureType worse(FailureType current, FailureType next) {
        if (rank(next) > rank(current)) {
            return next;
        }
        return current;
    }

    private static int rank(FailureType type) {
        if (type == null || type == FailureType.NONE) {
            return 0;
        }
        if (type == FailureType.RETRYABLE) {
            return 1;
        }
        if (type == FailureType.THROTTLED) {
            return 2;
        }
        if (type == FailureType.CONFIG_ERROR) {
            return 3;
        }
        return 4;
    }

    /**
     * @return 是否成功
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @return 是否失败
     */
    public boolean isFailed() {
        return !success;
    }

    /**
     * @return 渠道 id
     */
    public String channelId() {
        return channelId;
    }

    /**
     * @return HTTP 状态码，无 HTTP 语义时为 0
     */
    public int status() {
        return status;
    }

    /**
     * @return 响应正文，未拿到响应时为 {@code null}
     */
    public String response() {
        return response;
    }

    /**
     * @return 失败原因，成功时为 {@code null}
     */
    public String error() {
        return error;
    }

    /**
     * @return 发送耗时毫秒，未发出请求时为 0
     */
    public long elapsedMs() {
        return elapsedMs;
    }

    /**
     * @return 失败类别；成功时为 {@link FailureType#NONE}
     */
    public FailureType failureType() {
        return failureType;
    }

    /**
     * 拆包或 fan-out 的各次发送结果；单次发送时为空列表。
     *
     * @return 子结果（只读）
     */
    public List<SendResult> parts() {
        return parts;
    }

    /**
     * 是否值得重试（{@link FailureType#RETRYABLE} 或 {@link FailureType#THROTTLED}）。
     *
     * <p>成功结果恒为 {@code false}。{@link FailureType#THROTTLED} 建议用更长的退避间隔。
     *
     * @return 是否值得重试
     */
    public boolean isRetryable() {
        return !success && failureType.isRetryable();
    }

    /**
     * @return 成功 / 失败 + 原因的调试描述
     */
    @Override
    public String toString() {
        if (success) {
            return "SendResult{channel=" + channelId + ", ok, status=" + status + ", elapsedMs=" + elapsedMs + "}";
        }
        return "SendResult{channel=" + channelId + ", FAILED(" + failureType + "), status=" + status
                + (error != null ? ", error=" + error : "")
                + (response != null ? ", response=" + response : "") + "}";
    }
}
