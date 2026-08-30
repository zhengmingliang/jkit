package com.alianga.jkit.http;

import com.alianga.jkit.http.encoding.ContentEncodings;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 模块内部 IO 与头解析辅助。
 */
public final class HttpIo {
    private HttpIo() {
    }

    static byte[] readAll(InputStream in) throws IOException {
        return readAll(in, 0L);
    }

    /**
     * 读取全部字节。{@code maxBytes > 0} 时超出上限抛错。
     *
     * @param in 输入流
     * @param maxBytes 最大字节，{@code <=0} 不限制
     * @return 内容
     * @throws IOException 读取失败或超出上限
     */
    public static byte[] readAll(InputStream in, long maxBytes) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (maxBytes > 0 && total > maxBytes) {
                throw new IOException("response body exceeds maxBufferBytes " + maxBytes);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 流拷贝。
     *
     * @param in 输入
     * @param out 输出
     * @param bufferSize 块大小
     * @return 拷贝的字节数
     * @throws IOException IO 错误
     */
    public static long copy(InputStream in, OutputStream out, int bufferSize) throws IOException {
        if (in == null) {
            return 0L;
        }
        byte[] buf = new byte[Math.max(1024, bufferSize)];
        long total = 0L;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    static long copyFile(File file, OutputStream out, int bufferSize) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return copy(in, out, bufferSize);
        }
    }

    /**
     * 收敛为当前已注册解码器能处理的 {@code Accept-Encoding}（默认 gzip、deflate、br）。
     * 未知算法（如 zstd）会被去掉。
     *
     * @param value 原始头，可为 {@code null}
     * @return 可解码算法列表
     */
    public static String sanitizeAcceptEncoding(String value) {
        return ContentEncodings.sanitizeAcceptEncoding(value);
    }

    /**
     * 将请求的 {@code Accept-Encoding} 收敛到本库可解码的算法。
     *
     * @param request 请求
     */
    public static void applySupportedAcceptEncoding(HttpRequest request) {
        if (request == null) {
            return;
        }
        String sanitized = sanitizeAcceptEncoding(request.getHeader("Accept-Encoding"));
        Iterator<String> it = request.getHeaders().keySet().iterator();
        while (it.hasNext()) {
            if ("Accept-Encoding".equalsIgnoreCase(it.next())) {
                it.remove();
            }
        }
        request.header("Accept-Encoding", sanitized);
    }

    /**
     * 按响应的 {@code Content-Encoding} 解码流。内置 gzip / deflate / br；
     * 未注册的算法原样返回 {@code in}，由调用方自行处理。
     *
     * @param in 原始流
     * @param contentEncoding 编码头，可为 {@code null}
     * @return 解码后的流，或无法识别时的原流
     * @throws IOException 已知算法解码失败
     */
    public static InputStream decodeContentEncoding(InputStream in, String contentEncoding) throws IOException {
        return ContentEncodings.decode(in, contentEncoding);
    }

    static Charset charsetFromContentType(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        String[] parts = contentType.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            if (!"charset".equalsIgnoreCase(key)) {
                continue;
            }
            String value = part.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            try {
                return Charset.forName(value);
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
    }

    static long parseContentLength(String value) {
        if (value == null || value.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    static String firstHeader(HttpResponse response, String name) {
        return response == null ? null : response.header(name);
    }

    /**
     * 将字符串解析为 URI，无法解析时返回 {@code null}（不抛异常）。
     *
     * @param url URL 字符串，可为 {@code null}
     * @return URI，或 {@code null}
     */
    public static URI toUri(String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URL(url).toURI();
        } catch (Exception e) {
            try {
                return URI.create(url);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /**
     * 解析 {@code Set-Cookie} 响应头为 Cookie 列表，无法解析的条目会被跳过。
     *
     * @param setCookieHeaders 响应头值列表，可为 {@code null}
     * @return Cookie 列表（不会为 {@code null}）
     */
    public static List<HttpCookie> parseCookies(List<String> setCookieHeaders) {
        List<HttpCookie> cookies = new ArrayList<HttpCookie>();
        if (setCookieHeaders == null) {
            return cookies;
        }
        for (String raw : setCookieHeaders) {
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            try {
                cookies.addAll(HttpCookie.parse(raw));
            } catch (Exception e) {
                // 忽略无法解析的 Set-Cookie
            }
        }
        return cookies;
    }

    static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * 只保留 Map 的键名用于日志，避免把密码、令牌等表单值写进日志文件。
     *
     * @param params 表单或参数 Map，可为 {@code null}
     * @return 形如 {@code [username, password]} 的键名列表字符串
     */
    public static String maskedKeys(Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return "[]";
        }
        return params.keySet().toString();
    }

    /**
     * 把 {@code Location} 解析为绝对 URL，支持绝对地址、协议相对（{@code //host/path}）
     * 与相对路径。
     *
     * @param baseUrl 当前请求 URL
     * @param location 响应头中的 Location
     * @return 绝对 URL；无法解析时返回 {@code null}
     */
    public static String resolveRedirect(String baseUrl, String location) {
        if (location == null || location.trim().isEmpty()) {
            return null;
        }
        String target = location.trim();
        try {
            URI base = new URI(baseUrl);
            URI resolved = base.resolve(target);
            String scheme = resolved.getScheme();
            if (scheme == null) {
                return null;
            }
            // 只跟随 http/https，避免被重定向到 file:// 等协议
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            return resolved.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断两个 URL 是否同源（scheme + host + 有效端口一致）。
     *
     * @param a URL 之一
     * @param b URL 之二
     * @return 是否同源；任一无法解析时返回 {@code false}
     */
    public static boolean sameOrigin(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            URI ua = new URI(a);
            URI ub = new URI(b);
            if (ua.getScheme() == null || ub.getScheme() == null
                    || ua.getHost() == null || ub.getHost() == null) {
                return false;
            }
            return ua.getScheme().equalsIgnoreCase(ub.getScheme())
                    && ua.getHost().equalsIgnoreCase(ub.getHost())
                    && effectivePort(ua) == effectivePort(ub);
        } catch (Exception e) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * 取 URL 的主机名（小写，不含端口）。
     *
     * @param url URL
     * @return 主机名；无法解析时返回 {@code null}
     */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取 URL 的 authority（{@code host[:port]}，不含 userinfo）。
     *
     * @param url URL
     * @return authority；无法解析时返回 {@code null}
     */
    public static String authorityOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            int port = uri.getPort();
            return port == -1 ? host : host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 URL 的 origin（scheme + authority）替换为给定 origin，保留路径、查询与片段。
     * <p>
     * 负载均衡改写地址时用：{@code http://orders/api?x=1} + {@code http://10.0.0.7:8080}
     * 得到 {@code http://10.0.0.7:8080/api?x=1}。
     *
     * @param url 原始 URL
     * @param origin 目标 origin，如 {@code http://10.0.0.7:8080}
     * @return 改写后的 URL；无法解析时返回 {@code null}
     */
    public static String replaceOrigin(String url, String origin) {
        if (url == null || origin == null) {
            return null;
        }
        try {
            URI uri = new URI(url);
            StringBuilder sb = new StringBuilder(origin);
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty()) {
                sb.append(path);
            }
            String query = uri.getRawQuery();
            if (query != null) {
                sb.append('?').append(query);
            }
            String fragment = uri.getRawFragment();
            if (fragment != null) {
                sb.append('#').append(fragment);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isRestrictedHttpClientHeader(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if ("host".equals(n)) {
            // JDK 11+ 默认禁止应用设置 Host，但启动参数放开后应当放行，
            // 否则用户加了 -Djdk.httpclient.allowRestrictedHeaders=host 也不会生效
            return !isRestrictedHeaderAllowed("host");
        }
        return "connection".equals(n)
                || "content-length".equals(n)
                || "expect".equals(n)
                || "upgrade".equals(n);
    }

    /**
     * 判断 JVM 是否已通过 {@code jdk.httpclient.allowRestrictedHeaders} 放开某个受限请求头。
     *
     * @param header 头名称（小写）
     * @return 是否已放开
     */
    public static boolean isRestrictedHeaderAllowed(String header) {
        String allowed = System.getProperty("jdk.httpclient.allowRestrictedHeaders");
        if (allowed == null || allowed.isEmpty() || header == null) {
            return false;
        }
        for (String part : allowed.split(",")) {
            if (header.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字符串是否是 IPv4 字面量（点分四段十进制，每段 0–255）。
     *
     * @param value 待判断的主机名
     * @return 是否为 IPv4 字面量
     */
    public static boolean isIpv4Literal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int segments = 0;
        int digits = 0;
        int octet = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (digits == 0) {
                    return false;
                }
                segments++;
                digits = 0;
                octet = 0;
            } else if (c >= '0' && c <= '9') {
                digits++;
                if (digits > 3) {
                    return false;
                }
                octet = octet * 10 + (c - '0');
                if (octet > 255) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return segments == 3 && digits > 0;
    }

    /**
     * 判断字符串是否是 IP 字面量（IPv4，或带/不带方括号的 IPv6）。
     *
     * @param value 待判断的主机名
     * @return 是否为 IP 字面量
     */
    public static boolean isIpLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.charAt(0) == '[' || value.indexOf(':') >= 0) {
            return true;
        }
        return isIpv4Literal(value);
    }
}
