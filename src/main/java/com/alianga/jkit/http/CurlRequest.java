package com.alianga.jkit.http;

import com.alianga.jkit.HttpUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 由 curl 命令解析出的 HTTP 请求，可转 {@link HttpRequest} 或直接执行。
 *
 * @author 郑明亮
 */
public final class CurlRequest {
    private String method = "GET";
    private String url;
    private final Map<String, String> headers = new LinkedHashMap<String, String>();
    private String body;
    private String contentType;
    private boolean insecure;
    private boolean followRedirects = true;
    private String proxyHost;
    private int proxyPort = -1;
    private String user;
    private String password;
    private File uploadFile;
    private String uploadField = "file";

    /**
     * @return HTTP 方法
     */
    public String getMethod() {
        return method;
    }

    /**
     * @param method HTTP 方法
     */
    public void setMethod(String method) {
        this.method = method;
    }

    /**
     * @return URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * @param url URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * @return 请求头
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * @return 请求体
     */
    public String getBody() {
        return body;
    }

    /**
     * @param body 请求体
     */
    public void setBody(String body) {
        this.body = body;
    }

    /**
     * @return Content-Type
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * @param contentType Content-Type
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * @return 是否忽略证书（curl -k）
     */
    public boolean isInsecure() {
        return insecure;
    }

    /**
     * @param insecure 是否忽略证书
     */
    public void setInsecure(boolean insecure) {
        this.insecure = insecure;
    }

    /**
     * @return 是否跟随重定向
     */
    public boolean isFollowRedirects() {
        return followRedirects;
    }

    /**
     * @param followRedirects 是否跟随重定向
     */
    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    void setProxy(String host, int port) {
        this.proxyHost = host;
        this.proxyPort = port;
    }

    void setBasicUser(String user, String password) {
        this.user = user;
        this.password = password;
    }

    void setUpload(File file, String field) {
        this.uploadFile = file;
        if (field != null) {
            this.uploadField = field;
        }
    }

    /**
     * 转为可执行的 {@link HttpRequest}。
     *
     * @return 请求
     */
    public HttpRequest toHttpRequest() {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("curl url is empty");
        }
        HttpRequest request = new HttpRequest(method, url);
        Map<String, String> hdrs = new LinkedHashMap<String, String>(headers);
        if (user != null) {
            hdrs.put("Authorization", HttpUtils.basicAuth(user, password));
        }
        request.headers(hdrs);
        if (contentType != null && request.getHeader("Content-Type") == null) {
            request.contentType(contentType);
        }
        if (body != null) {
            request.body(body.getBytes(StandardCharsets.UTF_8));
        }
        request.followRedirects(followRedirects);
        request.ignoreSsl(insecure);
        if (proxyHost != null && proxyPort > 0) {
            request.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort)));
        }
        if (uploadFile != null && body == null) {
            request.bodyFile(uploadFile);
            if (request.getContentType() == null) {
                request.contentType("application/octet-stream");
            }
        }
        return request;
    }

    /**
     * 将请求转换为可在 POSIX shell 中执行的 curl 命令。
     * <p>
     * 字符串正文和请求头会使用单引号并正确转义；文件正文使用
     * {@code --data-binary @file}，不会把文件内容读入内存。
     * 该方法用于调试、日志和请求复现，不保证还原 curl 的所有高级选项。
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
        if (method != null && !"GET".equalsIgnoreCase(method)) {
            command.append(" -X ").append(shellQuote(method));
        }
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            command.append(" -H ").append(shellQuote(header.getKey() + ": "
                    + (header.getValue() == null ? "" : header.getValue())));
        }
        if (request.getBodyFile() != null) {
            command.append(" --data-binary ").append(shellQuote("@" + request.getBodyFile().getPath()));
        } else if (request.getBody() != null) {
            command.append(" --data-binary ").append(shellQuote(new String(request.getBody(), StandardCharsets.UTF_8)));
        }
        if (request.isIgnoreSsl()) {
            command.append(" -k");
        }
        if (request.isFollowRedirects()) {
            command.append(" -L");
        }
        if (request.getProxy() != null && request.getProxy().address() != null) {
            command.append(" -x ").append(shellQuote(request.getProxy().address().toString()));
        }
        command.append(' ').append(shellQuote(url));
        return command.toString();
    }

    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }

    /**
     * 执行该 curl 对应的 HTTP 请求。
     *
     * @return 响应
     * @throws IOException 网络错误
     */
    public HttpResponse execute() throws IOException {
        if (insecure) {
            HttpUtils.setIgnoreSsl(true);
        }
        if (uploadFile != null && body == null && !"PUT".equalsIgnoreCase(method)) {
            UploadInfo info = new UploadInfo(uploadField, uploadFile.getAbsolutePath());
            return HttpUtils.upload(url, info, Collections.<String, String>emptyMap(), headers);
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
}
