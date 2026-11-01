package com.alianga.jkit.storage.engine.googledrive;

import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.internal.HttpCore;
import com.alianga.jkit.storage.internal.SimpleJson;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google Drive OAuth2 认证协议实现：生成授权链接、授权码换取令牌、刷新令牌与本地轻量回调服务。
 * 纯 JDK 实现，零第三方库依赖。
 *
 * @author 郑明亮
 */
public final class GoogleDriveOAuth {
    private GoogleDriveOAuth() {
    }

    /** 令牌获取与刷新结果。 */
    public static final class TokenResult {
        private final String accessToken;
        private final String refreshToken;
        private final long expiresInSec;
        private final long expiresAtMillis;
        private final String scope;
        private final String tokenType;

        public TokenResult(String accessToken, String refreshToken, long expiresInSec,
                           long expiresAtMillis, String scope, String tokenType) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresInSec = expiresInSec;
            this.expiresAtMillis = expiresAtMillis;
            this.scope = scope;
            this.tokenType = tokenType;
        }

        public String accessToken() {
            return accessToken;
        }

        public String refreshToken() {
            return refreshToken;
        }

        public long expiresInSec() {
            return expiresInSec;
        }

        public long expiresAtMillis() {
            return expiresAtMillis;
        }

        public String scope() {
            return scope;
        }

        public String tokenType() {
            return tokenType;
        }
    }

    /**
     * 生成 Google OAuth2 授权网页 URL。
     *
     * @param clientId    Client ID
     * @param redirectUri 回调地址
     * @param scope       权限范围（null 时取默认）
     * @param state       可选状态参数（用于回传防 CSRF）
     * @return 完整的浏览器授权 URL
     */
    public static String buildAuthorizeUrl(String clientId, String redirectUri, String scope, String state) {
        String effectiveScope = scope == null || scope.trim().isEmpty()
                ? GoogleDriveConfig.DEFAULT_SCOPE : scope.trim();
        String effectiveRedirect = redirectUri == null || redirectUri.trim().isEmpty()
                ? GoogleDriveConfig.DEFAULT_REDIRECT_URI : redirectUri.trim();

        StringBuilder url = new StringBuilder(GoogleDriveConfig.DEFAULT_AUTH_URL);
        url.append("?client_id=").append(urlEncode(clientId));
        url.append("&response_type=code");
        url.append("&redirect_uri=").append(urlEncode(effectiveRedirect));
        url.append("&scope=").append(urlEncode(effectiveScope));
        url.append("&access_type=offline");
        url.append("&prompt=consent");
        if (state != null && !state.trim().isEmpty()) {
            url.append("&state=").append(urlEncode(state.trim()));
        }
        return url.toString();
    }

    /**
     * 使用授权码换取访问令牌与刷新令牌。
     *
     * @param clientId     Client ID
     * @param clientSecret Client Secret
     * @param redirectUri  重定向地址（必须与授权请求时完全一致）
     * @param code         授权码
     * @param proxy        可选网络代理
     * @return 令牌结果
     */
    public static TokenResult exchangeCode(String clientId, String clientSecret, String redirectUri,
                                           String code, Proxy proxy) {
        return requestToken(GoogleDriveConfig.DEFAULT_TOKEN_URL, clientId, clientSecret,
                "grant_type=authorization_code"
                        + "&code=" + urlEncode(code)
                        + "&client_id=" + urlEncode(clientId)
                        + "&client_secret=" + urlEncode(clientSecret)
                        + "&redirect_uri=" + urlEncode(redirectUri),
                null, proxy);
    }

    /**
     * 使用刷新令牌获取新的访问令牌。
     *
     * @param clientId     Client ID
     * @param clientSecret Client Secret
     * @param refreshToken 刷新令牌
     * @param proxy        可选网络代理
     * @return 包含新 access_token 的结果
     */
    public static TokenResult refreshToken(String clientId, String clientSecret,
                                           String refreshToken, Proxy proxy) {
        return requestToken(GoogleDriveConfig.DEFAULT_TOKEN_URL, clientId, clientSecret,
                "grant_type=refresh_token"
                        + "&client_id=" + urlEncode(clientId)
                        + "&client_secret=" + urlEncode(clientSecret)
                        + "&refresh_token=" + urlEncode(refreshToken),
                refreshToken, proxy);
    }

    /**
     * 发起令牌请求（换取或刷新）。
     */
    static TokenResult requestToken(String tokenUrl, String clientId, String clientSecret,
                                    String formBody, String originalRefreshToken, Proxy proxy) {
        byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
        HttpCore.Request request = new HttpCore.Request("POST", tokenUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .body(bodyBytes)
                .timeouts(proxy, 15_000, 30_000);

        try (HttpCore.Response response = HttpCore.execute(request)) {
            String bodyText = response.bodyText();
            Map<String, Object> json = SimpleJson.parseObject(bodyText);
            if (response.status() != 200 || json == null) {
                String error = json != null && json.get("error") != null
                        ? String.valueOf(json.get("error")) : "HTTP " + response.status();
                String desc = json != null && json.get("error_description") != null
                        ? String.valueOf(json.get("error_description")) : bodyText;
                if ("invalid_grant".equalsIgnoreCase(error)) {
                    throw StorageException.authFailed(
                            "Google Drive 授权码无效/已过期，或刷新令牌已失效/已撤销（invalid_grant: "
                                    + desc + "），请重新获取授权");
                }
                if ("invalid_client".equalsIgnoreCase(error)) {
                    throw StorageException.authFailed(
                            "Google Drive Client ID 或 Client Secret 错误（invalid_client: "
                                    + desc + "），请核对客户端凭据");
                }
                throw StorageException.authFailed("Google Drive 令牌请求失败: " + error + " - " + desc);
            }

            String accessToken = json.get("access_token") != null
                    ? String.valueOf(json.get("access_token")) : "";
            String newRefreshToken = json.get("refresh_token") != null
                    ? String.valueOf(json.get("refresh_token")) : originalRefreshToken;
            long expiresIn = 3600L;
            Object exp = json.get("expires_in");
            if (exp instanceof Number) {
                expiresIn = ((Number) exp).longValue();
            } else if (exp != null) {
                try {
                    expiresIn = Long.parseLong(String.valueOf(exp));
                } catch (NumberFormatException ignore) {
                    // 默认 3600 秒
                }
            }
            long expiresAt = System.currentTimeMillis() + (expiresIn * 1000L);
            String scope = json.get("scope") != null ? String.valueOf(json.get("scope")) : "";
            String tokenType = json.get("token_type") != null ? String.valueOf(json.get("token_type")) : "Bearer";

            return new TokenResult(accessToken, newRefreshToken, expiresIn, expiresAt, scope, tokenType);
        }
    }

    private static String urlEncode(String val) {
        try {
            return URLEncoder.encode(val == null ? "" : val, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return val == null ? "" : val;
        }
    }

    /**
     * 本地轻量级 OAuth 回调接收器：使用 JDK 自带 {@code HttpServer} 监听指定端口。
     * 用户在浏览器完成授权后回调此服务，自动解析 code 并回显友好页面。
     */
    public static final class LoopbackReceiver implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final int port;
        private final String path;
        private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * 启动本地监听器。
         *
         * @param port 监听端口，如 8080（若传 0 则由系统自动分配空闲端口）
         * @param path 路径，如 "/gd/callback"
         * @throws IOException 绑定端口失败
         */
        public LoopbackReceiver(int port, String path) throws IOException {
            this.path = path.startsWith("/") ? path : "/" + path;
            this.server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 0);
            this.port = this.server.getAddress().getPort();

            this.server.createContext(this.path, exchange -> {
                String query = exchange.getRequestURI().getQuery();
                Map<String, String> params = parseQueryParams(query);
                String code = params.get("code");
                String error = params.get("error");

                String html;
                if (code != null && !code.trim().isEmpty()) {
                    codeFuture.complete(code.trim());
                    html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                            + "<title>Google Drive 授权成功</title>"
                            + "<style>body{font-family:sans-serif;text-align:center;padding:50px;background:#f5f7fa;color:#333;}"
                            + ".card{background:#fff;border-radius:8px;padding:36px;display:inline-block;"
                            + "box-shadow:0 4px 16px rgba(0,0,0,0.08);max-width:520px;}"
                            + "h2{color:#2e7d32;margin-top:0;}p{color:#555;line-height:1.6;}"
                            + "</style></head><body><div class=\"card\">"
                            + "<h2>✓ Google Drive 授权成功</h2>"
                            + "<p>授权码已自动接收并回填至应用。<br/>您可以关闭本页面并返回桌面客户端继续使用。</p>"
                            + "</div></body></html>";
                } else {
                    String errorMsg = error != null ? error : "未能获取到授权码";
                    codeFuture.completeExceptionally(StorageException.authFailed("Google 授权回调失败: " + errorMsg));
                    html = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                            + "<title>授权未完成</title>"
                            + "<style>body{font-family:sans-serif;text-align:center;padding:50px;background:#fdf2f2;color:#333;}"
                            + ".card{background:#fff;border-radius:8px;padding:36px;display:inline-block;"
                            + "box-shadow:0 4px 16px rgba(0,0,0,0.08);max-width:520px;}"
                            + "h2{color:#d32f2f;margin-top:0;}p{color:#666;line-height:1.6;}"
                            + "</style></head><body><div class=\"card\">"
                            + "<h2>✕ 授权未完成</h2>"
                            + "<p>原因: " + errorMsg + "<br/>请关闭页面后在应用内重新尝试。</p>"
                            + "</div></body></html>";
                }

                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            this.server.setExecutor(null);
            this.server.start();
        }

        public int port() {
            return port;
        }

        public String callbackUrl() {
            return "http://localhost:" + port + path;
        }

        /**
         * 阻塞等待授权码返回。
         *
         * @param timeoutSec 超时时间（秒）
         * @return 授权码
         */
        public String waitForCode(long timeoutSec) {
            try {
                return codeFuture.get(timeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw StorageException.authFailed("等待 Google 授权回调超时（" + timeoutSec + "s）或被取消");
            } finally {
                close();
            }
        }

        public CompletableFuture<String> codeFuture() {
            return codeFuture;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // 先结算 future 再停服务：窗口被关闭时后台等待线程应立即返回，
                // 而不是空等满超时
                codeFuture.completeExceptionally(
                        StorageException.authFailed("授权等待已取消（回调窗口被关闭）"));
                try {
                    server.stop(0);
                } catch (Exception ignore) {
                    // 忽略关闭异常
                }
            }
        }

        private static Map<String, String> parseQueryParams(String query) {
            Map<String, String> map = new LinkedHashMap<String, String>();
            if (query == null || query.isEmpty()) {
                return map;
            }
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    try {
                        String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name());
                        String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name());
                        map.put(key, val);
                    } catch (Exception ignore) {
                        // 忽略解码异常
                    }
                }
            }
            return map;
        }
    }
}
