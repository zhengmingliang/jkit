package com.alianga.jkit.storage.git;

import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.HttpCore;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Git REST 传输层：基于 jkit-storage 内部 {@link HttpCore}（JDK HttpURLConnection），
 * 不引入任何第三方 Git / HTTP 库。
 *
 * <p>连接超时 30 秒，请求超时 120 秒——大文件 base64 上传单请求可能较久，
 * 120 秒是「够用但不至于永久挂着」的折中。重定向手动跟随：只有<b>同主机</b>的
 * GET 才跟进且不带鉴权头（自建 Gitea/Gogs 的 baseUrl 经反代或被劫持 DNS 时，
 * 一次重定向就把令牌送到第三方）。</p>
 *
 * <p><b>令牌绝不落日志</b>：异常文案只带状态码与响应体摘要（响应体由平台生成，
 * 不含令牌），{@code Authorization} 头只在发请求时构造，不进任何字符串缓存。</p>
 *
 * @author 郑明亮
 */
public final class GitTransport implements AutoCloseable {
    /** 连接超时毫秒。 */
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    /** 单请求读超时毫秒（含大文件 base64 上传）。 */
    private static final int READ_TIMEOUT_MS = 120_000;
    /** 手动跟随重定向的最大次数（防跳环）。 */
    private static final int MAX_REDIRECTS = 5;

    GitTransport() {
    }

    /**
     * 一次 REST 调用。
     *
     * @param method     HTTP 方法（GET / PUT / POST / DELETE）
     * @param url        完整 URL（含鉴权参数）
     * @param headers    附加请求头（鉴权头由 Adapter 给出）
     * @param jsonBody   JSON 请求体；null 表示无 body
     * @param path       出错时的逻辑路径（进异常文案）
     * @return 响应（含状态码与响应体）
     */
    public Response call(String method, String url, Map<String, String> headers, String jsonBody,
                         String path) {
        String upper = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        try {
            HttpCore.Response response = execute(upper, url, headers, jsonBody, path);
            int redirects = 0;
            // 写操作（PUT/POST/DELETE）不跟随重定向——平台 API 正常不会重定向写请求，
            // 带 body 的 307/308 语义又各家不一，如实上报
            while (isRedirect(response.status()) && "GET".equals(upper)) {
                if (++redirects > MAX_REDIRECTS) {
                    response.close();
                    throw GitErrors.http(response.status(), "重定向次数过多", path);
                }
                String location = response.header("Location");
                if (location == null || location.trim().isEmpty()) {
                    return new Response(response.status(), response.bodyText());
                }
                String next = redirectTarget(url, location, path);
                response.close();
                response = execute("GET", next, null, null, path);
            }
            return new Response(response.status(), response.bodyText());
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw StorageException.io("Git 请求失败：" + rootMessage(e), path, e);
        }
    }

    /**
     * 下载原始字节（走 {@code download_url} 或 raw 接口，不经过 base64）。
     *
     * @param url  地址
     * @param path 出错时的逻辑路径
     * @return 输入流（调用方负责关闭）
     */
    public InputStream openStream(String url, Map<String, String> headers, String path) {
        try {
            HttpCore.Response response = execute("GET", url, headers, null, path);
            int redirects = 0;
            // openRaw/CDN 场景最易被 302 到外链，绝不能把 Authorization 头带过去
            while (isRedirect(response.status())) {
                if (++redirects > MAX_REDIRECTS) {
                    response.close();
                    throw GitErrors.http(response.status(), "重定向次数过多", path);
                }
                String location = response.header("Location");
                response.close();
                if (location == null || location.trim().isEmpty()) {
                    throw GitErrors.http(response.status(), "", path);
                }
                // 重定向请求一律不带鉴权头（Location 指向哪里不受我们控制）
                response = execute("GET", redirectTarget(url, location, path), null, null, path);
            }
            int status = response.status();
            if (status < 200 || status >= 300) {
                response.close();
                throw GitErrors.http(status, "", path);
            }
            return response.body();
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw StorageException.io("Git 下载失败：" + rootMessage(e), path, e);
        }
    }

    private static HttpCore.Response execute(String method, String url, Map<String, String> headers,
                                             String jsonBody, String path) {
        HttpCore.Request request = new HttpCore.Request(method, uri(url, path).toString());
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
        }
        if (jsonBody != null) {
            request.header("Content-Type", "application/json;charset=UTF-8");
            request.body(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        request.timeouts(null, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        return HttpCore.execute(request);
    }

    /** 是否为需要手动跟随的重定向状态码。 */
    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * 解析重定向目标：只有<b>同主机</b>才跟随；返回下一跳 URL（后续请求不带鉴权头）。
     *
     * <p>按 host+port 比较：端口不同的 127.0.0.1 是两台不同的服务器，
     * 只比 host 会漏掉「同 IP 不同端口」的外带场景。</p>
     *
     * @param from     原请求 URL
     * @param location Location 头的值（相对或绝对）
     * @param path     出错时的逻辑路径
     * @return 下一跳 URL
     */
    private static String redirectTarget(String from, String location, String path) {
        URI origin = uri(from, path);
        URI target = origin.resolve(location);
        if (target.getHost() == null || origin.getHost() == null
                || !target.getHost().equalsIgnoreCase(origin.getHost())
                || target.getPort() != origin.getPort()) {
            throw new StorageException(StorageErrorCode.INVALID_CONFIG,
                    "下载地址重定向到了另一台主机（" + target.getHost() + ":" + target.getPort()
                            + "），已拒绝跟随以防令牌外泄", path);
        }
        return target.toString();
    }

    /** 异常链里最深的一条消息：包装层数多，直接打印会是一串 ClassName。 */
    private static String rootMessage(Throwable error) {
        String message = null;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }

    /**
     * 解析请求 URI；非法 URL 翻译成不带敏感信息的 {@link StorageException}。
     *
     * <p>{@code URI.create} 抛的是未受检 IAE 且<b>消息内嵌完整 URL</b>——Gitee 的
     * 令牌在查询参数里（平台限制），文件名含空格 / # / % 时这条消息会带着
     * access_token 一路上抛进 UI 弹窗与任务日志，构成令牌泄漏面。这里统一收口：
     * 只报「第几个字符非法」与去掉查询串的地址骨架。</p>
     *
     * @param url  完整 URL（可能含鉴权参数）
     * @param path 出错时的逻辑路径
     * @return 解析好的 URI
     */
    static URI uri(String url, String path) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            // IAE 消息里的「at index N」有诊断价值，保留；URL 本身只留骨架（查询串必砍）
            int at = message.indexOf("at index ");
            String where = at >= 0 ? message.substring(at, Math.min(message.length(), at + 14)) : "";
            throw new StorageException(StorageErrorCode.INVALID_CONFIG,
                    "Git 地址包含非法字符（未编码的空格 / # / % 等）"
                            + (where.isEmpty() ? "" : "，" + where)
                            + "：" + skeleton(url), path, e);
        }
    }

    /** URL 骨架：协议 + 主机 + 路径（查询串整段砍掉，令牌就在里面）。 */
    static String skeleton(String url) {
        if (url == null) {
            return "";
        }
        int query = url.indexOf('?');
        String base = query >= 0 ? url.substring(0, query) : url;
        // 路径里的文件名本身可能触发 IAE 的字符——截断到最后一个 /，只留目录部分
        int lastSlash = base.lastIndexOf('/');
        int schemeEnd = base.indexOf("://");
        if (lastSlash > schemeEnd + 3) {
            return base.substring(0, lastSlash) + "/…";
        }
        return base;
    }

    /**
     * 拼查询串（每段做 UTF-8 百分号编码）。
     *
     * @param params 查询参数（值为 null 的跳过）
     * @return 形如 {@code ?ref=main&path=docs} 的串；无参数返回空串
     */
    public static String query(Map<String, String> params) {
        String body = queryString(params);
        return body.isEmpty() ? "" : "?" + body;
    }

    /**
     * 查询串主体（不带前导 {@code ?}，每段做 UTF-8 百分号编码）。
     *
     * @param params 查询参数（值为 null / 空的跳过）
     * @return 形如 {@code ref=main&path=docs} 的串；无有效参数返回空串
     */
    public static String queryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return out.toString();
    }

    /**
     * 把查询参数追加到可能已带查询串的 URL 上（自动选 {@code ?} / {@code &} 分隔）。
     *
     * <p>历史 bug：{@link #query} 的返回值自带前导 {@code ?}，调用方再按
     * 「URL 里有没有 ?」补一次分隔符就叠出 {@code ??access_token=}——服务端把
     * 参数名解析成 {@code ?access_token}，令牌等于没带。</p>
     *
     * @param url    目标 URL（可以已带查询串）
     * @param params 要追加的参数
     * @return 追加后的 URL；params 为空时原样返回
     */
    public static String appendQuery(String url, Map<String, String> params) {
        String body = queryString(params);
        if (body.isEmpty()) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + body;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 恒可用
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        // HttpCore 每请求一条 HttpURLConnection，keep-alive 由 JDK 全局池管理，
        // 实例无需持有资源；保留方法是为了让调用方（GitStorageProvider.close）语义完整。
    }

    /** 响应：状态码 + 响应体文本。 */
    public static final class Response {
        private final int status;
        private final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        /** @return HTTP 状态码 */
        public int status() {
            return status;
        }

        /** @return 响应体文本（UTF-8） */
        public String body() {
            return body;
        }

        @Override
        public String toString() {
            return "Response{status=" + status + ", body="
                    + (body == null ? -1 : body.length()) + " chars}";
        }
    }
}
