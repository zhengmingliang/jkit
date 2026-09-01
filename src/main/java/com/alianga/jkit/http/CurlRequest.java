package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;
import com.alianga.jkit.http.curl.ParsedCurlRequest;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Auth;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Body;
import com.alianga.jkit.http.curl.ParsedCurlRequest.FormPart;
import com.alianga.jkit.http.curl.ParsedCurlRequest.Header;
import com.alianga.jkit.http.curl.ParsedCurlRequest.ProxySpec;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由 curl 命令解析出的 HTTP 请求，可转 {@link HttpRequest} 或直接执行。
 * <p>
 * 2.0.1 起内部持有不可变 {@link ParsedCurlRequest}：忽略证书只写在单次请求上，
 * {@link #toCurl(HttpRequest)} 的代理输出 {@code host:port}。
 *
 * @author 郑明亮
 */
public final class CurlRequest {
    private final ParsedCurlRequest.Builder builder;

    /**
     * 空请求，主要用于兼容旧解析路径。
     */
    public CurlRequest() {
        this.builder = ParsedCurlRequest.builder();
    }

    private CurlRequest(ParsedCurlRequest.Builder builder) {
        this.builder = builder;
    }

    /**
     * 从不可变模型构造。
     *
     * @param model 解析结果
     * @return 兼容层请求
     * @since 2.0.1
     */
    public static CurlRequest from(ParsedCurlRequest model) {
        if (model == null) {
            throw new IllegalArgumentException("parsed request is required");
        }
        ParsedCurlRequest.Builder b = ParsedCurlRequest.builder()
                .method(model.method())
                .url(model.url())
                .body(model.body())
                .auth(model.auth())
                .proxy(model.proxy())
                .insecure(model.insecure())
                .followRedirects(model.followRedirects())
                .compressed(model.compressed())
                .timeoutSec(model.timeoutSec())
                .connectTimeoutSec(model.connectTimeoutSec());
        for (Header h : model.headers()) {
            b.header(h.name(), h.value());
        }
        for (ParsedCurlRequest.QueryParam q : model.query()) {
            b.query(q.name(), q.value());
        }
        for (String w : model.warnings()) {
            b.warn(w);
        }
        for (String u : model.unknownOptions()) {
            b.unknown(u);
        }
        return new CurlRequest(b);
    }

    /**
     * @return 不可变解析模型
     * @since 2.0.1
     */
    public ParsedCurlRequest model() {
        return builder.build();
    }

    /**
     * @return HTTP 方法
     */
    public String getMethod() {
        return builder.method() == null ? "GET" : builder.method();
    }

    /**
     * @param method HTTP 方法
     */
    public void setMethod(String method) {
        builder.method(method);
    }

    /**
     * @return URL
     */
    public String getUrl() {
        String url = builder.url();
        return url == null || url.isEmpty() ? null : url;
    }

    /**
     * @param url URL
     */
    public void setUrl(String url) {
        builder.url(url);
    }

    /**
     * 兼容旧 API：返回一份可变副本。重复头只保留最后一个。
     *
     * @return 请求头
     */
    public Map<String, String> getHeaders() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (Header h : model().headers()) {
            map.put(h.name(), h.value());
        }
        return map;
    }

    /**
     * @return 请求体文本；multipart 时拼接非文件字段
     */
    public String getBody() {
        Body body = builder.body() == null ? Body.none() : builder.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            StringBuilder sb = new StringBuilder();
            for (FormPart p : body.parts()) {
                if (!p.file()) {
                    if (sb.length() > 0) {
                        sb.append('&');
                    }
                    sb.append(p.name()).append('=').append(p.value() == null ? "" : p.value());
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return body.text();
    }

    /**
     * @param body 请求体
     */
    public void setBody(String body) {
        builder.body(body == null ? Body.none() : Body.raw(body, false));
    }

    /**
     * @return Content-Type
     */
    public String getContentType() {
        String ct = builder.header("Content-Type");
        if (ct != null) {
            return ct;
        }
        Body body = builder.body();
        if (body != null && body.kind() == Body.Kind.URLENCODED) {
            return "application/x-www-form-urlencoded";
        }
        if (body != null && body.kind() == Body.Kind.JSON) {
            return "application/json";
        }
        return null;
    }

    /**
     * @param contentType Content-Type
     */
    public void setContentType(String contentType) {
        builder.upsertHeader("Content-Type", contentType);
    }

    /**
     * @return 是否忽略证书（curl -k）
     */
    public boolean isInsecure() {
        return builder.insecure();
    }

    /**
     * @param insecure 是否忽略证书
     */
    public void setInsecure(boolean insecure) {
        builder.insecure(insecure);
    }

    /**
     * @return 是否跟随重定向
     */
    public boolean isFollowRedirects() {
        return builder.followRedirects();
    }

    /**
     * @param followRedirects 是否跟随重定向
     */
    public void setFollowRedirects(boolean followRedirects) {
        builder.followRedirects(followRedirects);
    }

    /**
     * @return 解析警告
     * @since 2.0.1
     */
    public List<String> getWarnings() {
        return model().warnings();
    }

    void setProxy(String host, int port) {
        builder.proxy(new ProxySpec(host, port, null, null, "http"));
    }

    void setBasicUser(String user, String password) {
        builder.auth(Auth.basic(user, password));
    }

    void setUpload(File file, String field) {
        if (file == null) {
            return;
        }
        builder.body(Body.file(file.getPath(), true));
        if (field != null && !"file".equals(field)) {
            List<FormPart> parts = new ArrayList<FormPart>();
            parts.add(FormPart.file(field, file.getPath(), file.getName(), null));
            builder.body(Body.multipart(parts));
        }
    }

    /**
     * 转为可执行的 {@link HttpRequest}。忽略证书写在 request 上，不再改全局状态。
     *
     * @return 请求
     */
    public HttpRequest toHttpRequest() {
        ParsedCurlRequest model = model();
        String url = model.url();
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("curl url is empty");
        }
        HttpRequest request = new HttpRequest(model.method(), url);
        Map<String, String> hdrs = new LinkedHashMap<String, String>();
        for (Header h : model.headers()) {
            hdrs.put(h.name(), h.value());
        }
        Auth auth = model.auth();
        if (auth != null && "basic".equals(auth.type()) && !containsIgnoreCase(hdrs, "Authorization")) {
            hdrs.put("Authorization", HttpUtils.basicAuth(auth.user(), auth.password()));
        }
        if (auth != null && "bearer".equals(auth.type()) && !containsIgnoreCase(hdrs, "Authorization")) {
            hdrs.put("Authorization", "Bearer " + auth.token());
        }
        request.headers(hdrs);
        String ct = getContentType();
        if (ct != null && request.getHeader("Content-Type") == null) {
            request.contentType(ct);
        }
        Body body = model.body();
        if (body.kind() == Body.Kind.FILE && body.filePath() != null) {
            request.bodyFile(new File(body.filePath()));
            if (request.getContentType() == null) {
                request.contentType("application/octet-stream");
            }
        } else if (body.kind() == Body.Kind.MULTIPART) {
            String encoded = getBody();
            if (encoded != null) {
                request.body(encoded.getBytes(StandardCharsets.UTF_8));
                if (request.getContentType() == null) {
                    request.contentType("application/x-www-form-urlencoded");
                }
            }
        } else if (body.text() != null) {
            request.body(body.text().getBytes(StandardCharsets.UTF_8));
        }
        request.followRedirects(model.followRedirects());
        request.ignoreSsl(model.insecure());
        ProxySpec proxy = model.proxy();
        if (proxy != null && proxy.port() > 0) {
            request.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.host(), proxy.port())));
        }
        if (model.connectTimeoutSec() != null) {
            request.connectTimeoutMs(model.connectTimeoutSec().intValue() * 1000);
        }
        if (model.timeoutSec() != null) {
            request.readTimeoutMs(model.timeoutSec().intValue() * 1000);
        }
        return request;
    }

    /**
     * 执行该 curl 对应的 HTTP 请求。证书校验只作用于这一次请求。
     *
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse execute() throws IOException {
        ParsedCurlRequest model = model();
        Body body = model.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            FormPart file = null;
            Map<String, String> form = new LinkedHashMap<String, String>();
            for (FormPart p : body.parts()) {
                if (p.file() && file == null) {
                    file = p;
                } else if (!p.file()) {
                    form.put(p.name(), p.value() == null ? "" : p.value());
                }
            }
            if (file != null) {
                UploadInfo info = new UploadInfo(file.name(), new File(file.filePath()).getAbsolutePath());
                return HttpUtils.upload(model.url(), info, form, getHeaders());
            }
        }
        return HttpUtils.execute(toHttpRequest());
    }

    /**
     * 执行并返回正文。
     *
     * @return 响应字符串
     * @throws IOException 网络错误
     */
    public String executeString() throws IOException {
        HttpResponse response = execute();
        try {
            return response.body() == null ? "" : response.body().string();
        } finally {
            response.close();
        }
    }

    /**
     * 从解析模型回写 curl。代理使用 {@code host:port}，GET+body 会输出 {@code -X GET}。
     *
     * @return curl 命令
     * @since 2.0.1
     */
    public String toCurl() {
        return toCurl(model());
    }

    /**
     * 将解析模型转为 curl。
     *
     * @param req 模型
     * @return curl 命令
     * @since 2.0.1
     */
    public static String toCurl(ParsedCurlRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request is required");
        }
        StringBuilder command = new StringBuilder("curl");
        boolean hasBody = req.body().isPresent();
        String method = req.method();
        if (method != null && (!"GET".equalsIgnoreCase(method) || hasBody)) {
            command.append(" -X ").append(shellQuote(method));
        }
        if (req.followRedirects()) {
            command.append(" -L");
        }
        if (req.insecure()) {
            command.append(" -k");
        }
        if (req.compressed()) {
            command.append(" --compressed");
        }
        ProxySpec proxy = req.proxy();
        if (proxy != null) {
            String auth = proxy.user() != null
                    ? proxy.user() + ":" + (proxy.password() == null ? "" : proxy.password()) + "@"
                    : "";
            command.append(" -x ").append(shellQuote(auth + proxy.hostPort()));
        }
        Auth auth = req.auth();
        if (auth != null && "basic".equals(auth.type())) {
            command.append(" -u ").append(shellQuote(auth.user() + ":" + auth.password()));
        } else if (auth != null && "bearer".equals(auth.type())) {
            command.append(" --oauth2-bearer ").append(shellQuote(auth.token()));
        }
        for (Header h : req.headers()) {
            if ("content-length".equalsIgnoreCase(h.name())) {
                continue;
            }
            command.append(" -H ").append(shellQuote(h.name() + ": " + h.value()));
        }
        Body body = req.body();
        if (body.kind() == Body.Kind.MULTIPART) {
            for (FormPart p : body.parts()) {
                if (p.file()) {
                    String spec = p.name() + "=@" + (p.filePath() == null ? "" : p.filePath());
                    if (p.filename() != null) {
                        spec += ";filename=" + p.filename();
                    }
                    if (p.contentType() != null) {
                        spec += ";type=" + p.contentType();
                    }
                    command.append(" -F ").append(shellQuote(spec));
                } else {
                    command.append(" -F ").append(shellQuote(p.name() + "="
                            + (p.value() == null ? "" : p.value())));
                }
            }
        } else if (body.kind() == Body.Kind.FILE) {
            command.append(" --data-binary ").append(shellQuote("@" + body.filePath()));
        } else if (body.kind() == Body.Kind.JSON && body.text() != null) {
            command.append(" --json ").append(shellQuote(body.text()));
        } else if (body.text() != null) {
            command.append(" --data-binary ").append(shellQuote(body.text()));
        }
        if (req.url() != null) {
            command.append(' ').append(shellQuote(req.url()));
        }
        return command.toString();
    }

    /**
     * 将 {@link HttpRequest} 转为 curl。代理地址使用 host:port，不再用
     * {@code InetSocketAddress.toString()}（会带前导 {@code /}）。
     *
     * @param request 请求模型
     * @return curl 命令
     */
    public static String toCurl(HttpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String url = request.effectiveUrl();
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("request url is empty");
        }
        StringBuilder command = new StringBuilder("curl");
        String method = request.getMethod();
        boolean hasBody = request.getBodyFile() != null || request.getBody() != null;
        if (method != null && (!"GET".equalsIgnoreCase(method) || hasBody)) {
            command.append(" -X ").append(shellQuote(method));
        }
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            command.append(" -H ").append(shellQuote(header.getKey() + ": "
                    + (header.getValue() == null ? "" : header.getValue())));
        }
        if (request.getBodyFile() != null) {
            command.append(" --data-binary ").append(shellQuote("@" + request.getBodyFile().getPath()));
        } else if (request.getBody() != null) {
            command.append(" --data-binary ").append(shellQuote(
                    new String(request.getBody(), StandardCharsets.UTF_8)));
        }
        if (request.isIgnoreSsl()) {
            command.append(" -k");
        }
        if (request.isFollowRedirects()) {
            command.append(" -L");
        }
        if (request.getProxy() != null && request.getProxy().address() instanceof InetSocketAddress) {
            InetSocketAddress addr = (InetSocketAddress) request.getProxy().address();
            String host = addr.getHostString() != null ? addr.getHostString() : addr.getHostName();
            command.append(" -x ").append(shellQuote(host + ":" + addr.getPort()));
        }
        command.append(' ').append(shellQuote(url));
        return command.toString();
    }

    static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    private static boolean containsIgnoreCase(Map<String, String> map, String key) {
        for (String k : map.keySet()) {
            if (k != null && k.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }
}
