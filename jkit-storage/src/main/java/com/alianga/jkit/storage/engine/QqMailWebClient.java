package com.alianga.jkit.storage.engine;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.Urls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * wx.mail.qq.com 客户端：表单 POST（JSON）与 GET 字节流（手动跟随 ≤5 次重定向）。
 *
 * <p>安全约定：Cookie 只经 {@link QqMailCookieJar} 发给 *.qq.com，从不手工固定 Cookie 头；
 * sid、重定向地址（target / ftn hex）、大附件 fileid（含 key/code）都视为凭据，
 * 不进日志与异常消息——异常只带接口路径与状态码。</p>
 *
 * @author 郑明亮
 */
final class QqMailWebClient {
    static final String BASE_URL = "https://wx.mail.qq.com";
    static final String REFERER = BASE_URL + "/";
    static final int MAX_REDIRECTS = 5;
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Safari/537.36";

    static final String LANGUAGE = "en";
    private static final Logger LOG = Logger.getLogger(QqMailWebClient.class.getName());

    private final String sid;
    private final QqMailCookieJar jar;
    private final QqMailTransport transport;
    private final LongSupplier clock;

    QqMailWebClient(String sid, QqMailCookieJar jar, QqMailTransport transport) {
        this(sid, jar, transport, null);
    }

    QqMailWebClient(String sid, QqMailCookieJar jar, QqMailTransport transport, LongSupplier clock) {
        this.sid = sid;
        this.jar = jar;
        this.transport = transport;
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    /** 默认传输：HttpCore，单跳不跟随重定向。 */
    static QqMailTransport httpCoreTransport(final StorageConfig config) {
        return (method, url, headers, body) -> {
            HttpCore.Request req = HttpCore.configured(new HttpCore.Request(method, url), config);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.header(h.getKey(), h.getValue());
            }
            if (body != null) {
                req.body(body);
            }
            final HttpCore.Response r = HttpCore.execute(req);
            return new QqMailTransport.Response(r.status(), r.headers(), r.body(), r::close);
        };
    }

    /**
     * POST 表单并解析 JSON 根对象：URL 无查询串，表单按业务字段原序后接 language、r、sid（同网页）。
     */
    Map<String, Object> post(String path, Map<String, String> form) {
        return post(path, form, false);
    }

    /**
     * @param inQuery true 时 language、r、sid 放在 URL 查询串、表单为空（/login/checksid 网页即如此）
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> post(String path, Map<String, String> form, boolean inQuery) {
        Map<String, String> all = new LinkedHashMap<String, String>();
        if (form != null) {
            all.putAll(form);
        }
        all.put("language", LANGUAGE);
        all.put("r", String.valueOf(clock.getAsLong()));
        all.put("sid", sid);
        String url = inQuery ? BASE_URL + path + "?" + encodeForm(all) : BASE_URL + path;
        Map<String, String> headers = baseHeaders(url);
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("Accept", "application/json");
        byte[] body = inQuery ? new byte[0] : encodeForm(all).getBytes(StandardCharsets.UTF_8);
        debug("POST", path, inQuery ? all : null, inQuery ? null : all, headers);
        QqMailTransport.Response resp = transport.exchange("POST", url, headers, body);
        try {
            jar.absorb(host(url), resp.all("Set-Cookie"));
            checkHttp(resp.status, path);
            String text = new String(readAll(resp.body), StandardCharsets.UTF_8);
            Object parsed;
            try {
                parsed = JSON.parse(text);
            } catch (RuntimeException e) {
                throw StorageException.io("QQ 邮箱接口返回非 JSON: " + path, null, null);
            }
            if (!(parsed instanceof Map)) {
                throw StorageException.io("QQ 邮箱接口返回非对象 JSON: " + path, null, null);
            }
            return (Map<String, Object>) parsed;
        } finally {
            resp.close();
        }
    }

    /**
     * GET {@code BASE_URL+path?query&sid}，跟随重定向直到 2xx，返回字节流。
     */
    QqMailAttachStream openStream(String path, Map<String, String> query) {
        Map<String, String> q = new LinkedHashMap<String, String>(query);
        q.put("sid", sid);
        String url = BASE_URL + path + "?" + encodeForm(q);
        for (int hop = 0; ; hop++) {
            Map<String, String> headers = baseHeaders(url);
            debug("GET", hop == 0 ? path : "(redirect " + hop + ")", hop == 0 ? q : null, null, headers);
            QqMailTransport.Response resp = transport.exchange("GET", url, headers, null);
            boolean handedOff = false;
            try {
                jar.absorb(host(url), resp.all("Set-Cookie"));
                int status = resp.status;
                if (status >= 300 && status < 400) {
                    String location = resp.first("Location");
                    if (location == null || location.isEmpty()) {
                        throw StorageException.serverError("重定向缺少 Location（HTTP " + status + " " + path + "）", null);
                    }
                    if (hop >= MAX_REDIRECTS) {
                        throw StorageException.serverError("重定向次数超过 " + MAX_REDIRECTS + "（" + path + "）", null);
                    }
                    url = URI.create(url).resolve(location).toString();
                    continue;
                }
                checkHttp(status, path);
                long len = -1L;
                try {
                    String cl = resp.first("Content-Length");
                    len = cl == null ? -1L : Long.parseLong(cl.trim());
                } catch (NumberFormatException ignored) {
                    len = -1L;
                }
                QqMailAttachStream s = new QqMailAttachStream(resp.body, resp.first("Content-Type"),
                        filenameFromDisposition(resp.first("Content-Disposition")), len, resp);
                handedOff = true;
                return s;
            } finally {
                if (!handedOff) {
                    resp.close();
                }
            }
        }
    }

    private Map<String, String> baseHeaders(String url) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Origin", BASE_URL);
        h.put("Referer", REFERER);
        h.put("Sec-Fetch-Site", "same-origin");
        h.put("Sec-Fetch-Mode", "cors");
        h.put("User-Agent", UA);
        String cookie = jar.headerFor(host(url));
        if (cookie != null) {
            h.put("Cookie", cookie);
        }
        return h;
    }

    /** FINE 调试：只记路径与参数名/头名，绝不记值（含 Cookie、sid）。 */
    private static void debug(String method, String path, Map<String, String> query,
                              Map<String, String> form, Map<String, String> headers) {
        if (!LOG.isLoggable(Level.FINE)) {
            return;
        }
        LOG.fine("qqmail " + method + " " + path
                + " query=" + (query == null ? "[]" : query.keySet().toString())
                + " form=" + (form == null ? "[]" : form.keySet().toString())
                + " headers=" + headers.keySet());
    }

    private static void checkHttp(int status, String path) {
        if (status == 401 || status == 403) {
            throw new StorageException(StorageErrorCode.AUTH_FAILED,
                    "QQ 邮箱网页会话失效（HTTP " + status + " " + path + "），请重新扫码登录", null);
        }
        if (status >= 400) {
            throw StorageException.serverError("QQ 邮箱 HTTP " + status + ": " + path, null);
        }
    }

    static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String encodeForm(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(Urls.encodeQuery(e.getKey())).append('=')
                    .append(Urls.encodeQuery(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    private static final Pattern FN_STAR = Pattern.compile(
            "filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FN_PLAIN = Pattern.compile(
            "filename\\s*=\\s*(\"([^\"]*)\"|([^;]+))", Pattern.CASE_INSENSITIVE);

    /** Content-Disposition 文件名：RFC5987 {@code filename*} 优先，回退 {@code filename}。 */
    static String filenameFromDisposition(String cd) {
        if (cd == null) {
            return null;
        }
        Matcher star = FN_STAR.matcher(cd);
        if (star.find()) {
            String charset = star.group(1).trim().isEmpty() ? "UTF-8" : star.group(1).trim();
            try {
                return URLDecoder.decode(star.group(2).trim().replace("+", "%2B"), charset);
            } catch (Exception ignored) {
                // 回退 filename
            }
        }
        Matcher plain = FN_PLAIN.matcher(cd);
        while (plain.find()) {
            if (plain.start() > 0 && cd.charAt(plain.start() - 1) == '*') {
                continue;
            }
            String v = plain.group(2) != null ? plain.group(2) : plain.group(3).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private static byte[] readAll(InputStream in) {
        if (in == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw StorageException.io("读取 QQ 邮箱响应失败", null, e);
        }
    }

    @Override
    public String toString() {
        return "QqMailWebClient{redacted}";
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
