package com.alianga.jkit.http;

import com.alianga.jkit.json.JSON;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内存 Cookie 仓库（线程安全）。
 * <p>
 * 特性：
 * <ul>
 *     <li>按 Domain / Path / Secure 匹配过滤（RFC 6265 语义）</li>
 *     <li>Max-Age / Expires 过期自动清理</li>
 *     <li>{@link #saveTo(File)} / {@link #loadFrom(File)} JSON 持久化</li>
 *     <li>{@link #exportNetscape(File)} / {@link #importNetscape(File)} Netscape cookies.txt 格式导入导出</li>
 *     <li>按名称 / 名称+域删除</li>
 * </ul>
 *
 * @author 郑明亮
 */
public class CookieJarImpl implements HttpCookieJar {
    /** 默认 Cookie 条数上限，参考浏览器量级。 */
    public static final int DEFAULT_MAX_COOKIES = 3000;

    /**
     * 常见的二级公共后缀首标签。用于拒绝 {@code Domain=.co.uk} 这类跨站投毒，
     * 不追求覆盖完整 PSL。
     */
    private static final java.util.Set<String> SECOND_LEVEL_PUBLIC_SUFFIXES =
            Collections.unmodifiableSet(new java.util.HashSet<String>(java.util.Arrays.asList(
                    "co", "com", "net", "org", "gov", "edu", "ac", "mil", "or", "ne", "go", "info")));

    private final Map<String, StoredCookie> store = new LinkedHashMap<String, StoredCookie>();
    private final CookieManager legacyManager;
    private int maxCookies = DEFAULT_MAX_COOKIES;

    /**
     * 创建空的内存 Cookie 仓库。
     */
    public CookieJarImpl() {
        this(null);
    }

    /**
     * 用既有 JDK {@link CookieManager} 的内容初始化仓库。
     *
     * @param cookieManager JDK Cookie 管理器
     * @deprecated 仓库已改为自有存储，此构造器仅用于迁移旧数据
     */
    @Deprecated
    public CookieJarImpl(CookieManager cookieManager) {
        this.legacyManager = cookieManager;
        if (cookieManager != null) {
            CookieStore cookieStore = cookieManager.getCookieStore();
            if (cookieStore != null) {
                for (HttpCookie cookie : cookieStore.getCookies()) {
                    if (cookie == null) {
                        continue;
                    }
                    String host = cookie.getDomain() == null ? "localhost"
                            : cookie.getDomain().toLowerCase(Locale.ROOT);
                    while (host.startsWith(".")) {
                        host = host.substring(1);
                    }
                    saveFromResponse(URI.create("http://" + host + "/"),
                            Collections.singletonList(cookie));
                }
            }
        }
    }

    /**
     * @return 构造时传入的 JDK Cookie 管理器（可能为 {@code null}）
     * @deprecated 仓库已改为自有存储，不再依赖 JDK {@link CookieManager}
     */
    @Deprecated
    public CookieManager getCookieManager() {
        return legacyManager;
    }

    @Override
    public synchronized List<HttpCookie> loadForRequest(URI uri) {
        if (uri == null) {
            return Collections.emptyList();
        }
        purgeExpired(System.currentTimeMillis());
        String host = hostOf(uri);
        String path = pathOf(uri);
        boolean secureConnection = "https".equalsIgnoreCase(uri.getScheme())
                || "wss".equalsIgnoreCase(uri.getScheme());
        List<HttpCookie> result = new ArrayList<HttpCookie>();
        for (StoredCookie stored : store.values()) {
            HttpCookie cookie = stored.cookie;
            if (cookie.getSecure() && !secureConnection) {
                continue;
            }
            if (!domainMatch(cookie.getDomain(), host)) {
                continue;
            }
            if (!pathMatch(path, cookie.getPath())) {
                continue;
            }
            result.add((HttpCookie) cookie.clone());
        }
        return result;
    }

    @Override
    public synchronized void saveFromResponse(URI uri, List<HttpCookie> cookies) {
        if (uri == null || cookies == null || cookies.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String host = hostOf(uri);
        for (HttpCookie cookie : cookies) {
            if (cookie == null || cookie.getName() == null || cookie.getName().isEmpty()) {
                continue;
            }
            HttpCookie copy = (HttpCookie) cookie.clone();
            if (copy.getDomain() == null || copy.getDomain().isEmpty()) {
                copy.setDomain(host);
            } else if (!acceptableDomain(copy.getDomain(), host)) {
                // 服务端不得给无关域名或公共后缀设置 Cookie
                continue;
            }
            if (copy.getPath() == null || copy.getPath().isEmpty()) {
                copy.setPath(defaultPath(pathOf(uri)));
            }
            long maxAge = copy.getMaxAge();
            if (maxAge == 0) {
                // Max-Age=0 / 过期时间已到：删除同名 Cookie
                store.remove(key(copy.getName(), copy.getDomain(), copy.getPath()));
                continue;
            }
            long expiresAt = maxAge < 0 ? -1L : now + maxAge * 1000L;
            store.put(key(copy.getName(), copy.getDomain(), copy.getPath()),
                    new StoredCookie(copy, expiresAt));
        }
        enforceCapacity();
    }

    /**
     * 校验响应能否为该 domain 设置 Cookie：
     * <ol>
     *   <li>必须 domain-match 请求主机——否则 {@code attacker.test} 可以给
     *       {@code .example.com} 设 Cookie；</li>
     *   <li>不能是公共后缀（{@code .com} / {@code .co.uk} 之类）——否则一个站点
     *       能给整个顶级域下的所有站点投毒。</li>
     * </ol>
     *
     * @param cookieDomain Set-Cookie 中的 Domain
     * @param host 请求主机
     * @return 是否接受
     */
    static boolean acceptableDomain(String cookieDomain, String host) {
        if (cookieDomain == null || cookieDomain.isEmpty()) {
            return true;
        }
        if (!domainMatch(cookieDomain, host)) {
            return false;
        }
        String domain = cookieDomain.toLowerCase(Locale.ROOT);
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        return !isPublicSuffix(domain);
    }

    /**
     * 粗粒度公共后缀判定。没有内置完整 PSL（会引入几万行数据），
     * 采用"标签数 + 常见二级公共后缀"的启发式：单标签一律拒绝；
     * 形如 {@code co.uk} / {@code com.cn} 的两段式公共后缀也拒绝。
     * IP 字面量不参与域 Cookie 判定。
     *
     * @param domain 已去掉前导点的小写域名
     * @return 是否为公共后缀
     */
    static boolean isPublicSuffix(String domain) {
        if (domain == null || domain.isEmpty()) {
            return true;
        }
        if (domain.indexOf(':') >= 0 || HttpIo.isIpv4Literal(domain)) {
            // IP 字面量：由 domainMatch 的精确匹配兜底
            return false;
        }
        String[] labels = domain.split("\\.");
        if (labels.length < 2) {
            // localhost、com 这类单标签不允许作为域 Cookie 的作用域
            return true;
        }
        if (labels.length == 2 && labels[1].length() <= 3
                && SECOND_LEVEL_PUBLIC_SUFFIXES.contains(labels[0])) {
            // co.uk / com.cn / gov.au ...
            return true;
        }
        return false;
    }

    /**
     * 容量上限：超出时按插入顺序淘汰最旧条目（{@code store} 是 LinkedHashMap）。
     * 没有上限时，一个只写不读的 jar 会无限增长。
     */
    private void enforceCapacity() {
        if (maxCookies <= 0 || store.size() <= maxCookies) {
            return;
        }
        Iterator<Map.Entry<String, StoredCookie>> it = store.entrySet().iterator();
        while (it.hasNext() && store.size() > maxCookies) {
            it.next();
            it.remove();
        }
    }

    /**
     * @return Cookie 条数上限
     */
    public int getMaxCookies() {
        return maxCookies;
    }

    /**
     * 设置 Cookie 条数上限，超出后淘汰最旧条目。{@code <=0} 表示不限制（不推荐）。
     *
     * @param maxCookies 上限
     * @return this
     */
    public synchronized CookieJarImpl setMaxCookies(int maxCookies) {
        this.maxCookies = maxCookies;
        enforceCapacity();
        return this;
    }

    /**
     * 删除指定名称的所有 Cookie。
     *
     * @param name Cookie 名
     * @return 是否删除了至少一条
     */
    public synchronized boolean remove(String name) {
        if (name == null) {
            return false;
        }
        boolean removed = false;
        Iterator<StoredCookie> iterator = store.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().cookie.getName().equals(name)) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    /**
     * 删除指定名称和域的 Cookie（域比较忽略大小写与前导点，
     * 即传入 {@code example.com} 也能删除存储为 {@code .example.com} 的 Cookie）。
     *
     * @param name Cookie 名
     * @param domain 域
     * @return 是否删除了至少一条
     */
    public synchronized boolean remove(String name, String domain) {
        if (name == null) {
            return false;
        }
        String normalized = normalizeDomain(domain);
        boolean removed = false;
        for (Iterator<StoredCookie> iterator = store.values().iterator(); iterator.hasNext(); ) {
            HttpCookie cookie = iterator.next().cookie;
            if (cookie.getName().equals(name)
                    && normalized.equals(normalizeDomain(cookie.getDomain()))) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    private static String normalizeDomain(String domain) {
        String normalized = domain == null ? "" : domain.toLowerCase(Locale.ROOT);
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * 清空全部 Cookie。
     */
    public synchronized void clear() {
        store.clear();
    }

    /**
     * @return 当前有效 Cookie 数量（先清理过期项）
     */
    public synchronized int size() {
        purgeExpired(System.currentTimeMillis());
        return store.size();
    }

    /**
     * @return 当前全部有效 Cookie 的拷贝
     */
    public synchronized List<HttpCookie> getCookies() {
        purgeExpired(System.currentTimeMillis());
        List<HttpCookie> result = new ArrayList<HttpCookie>();
        for (StoredCookie stored : store.values()) {
            result.add((HttpCookie) stored.cookie.clone());
        }
        return result;
    }

    /**
     * 持久化为 JSON 文件（可用 {@link #loadFrom(File)} 恢复）。
     *
     * @param file 目标文件
     * @return this
     * @throws IOException 写出失败
     */
    public synchronized CookieJarImpl saveTo(File file) throws IOException {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        purgeExpired(System.currentTimeMillis());
        for (StoredCookie stored : store.values()) {
            HttpCookie cookie = stored.cookie;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", cookie.getName());
            item.put("value", cookie.getValue());
            item.put("domain", cookie.getDomain());
            item.put("path", cookie.getPath());
            item.put("secure", cookie.getSecure());
            item.put("httpOnly", cookie.isHttpOnly());
            item.put("version", cookie.getVersion());
            item.put("expiresAt", stored.expiresAt);
            if (cookie.getComment() != null) {
                item.put("comment", cookie.getComment());
            }
            list.add(item);
        }
        writeText(file, JSON.toJsonString(list));
        return this;
    }

    /**
     * 从 {@link #saveTo(File)} 生成的 JSON 文件恢复。
     *
     * @param file JSON 文件
     * @return this
     * @throws IOException 读取或解析失败
     */
    public synchronized CookieJarImpl loadFrom(File file) throws IOException {
        String json = readText(file);
        if (json == null || json.trim().isEmpty()) {
            return this;
        }
        Object parsed = JSON.parse(json);
        if (!(parsed instanceof List)) {
            throw new IOException("cookie file format error: " + file);
        }
        long now = System.currentTimeMillis();
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;
            String name = str(map.get("name"));
            if (name == null || name.isEmpty()) {
                continue;
            }
            HttpCookie cookie = new HttpCookie(name, str(map.get("value")) == null ? "" : str(map.get("value")));
            String domain = str(map.get("domain"));
            if (domain != null && !domain.isEmpty()) {
                cookie.setDomain(domain);
            }
            String path = str(map.get("path"));
            if (path != null && !path.isEmpty()) {
                cookie.setPath(path);
            }
            if (Boolean.TRUE.equals(map.get("secure"))) {
                cookie.setSecure(true);
            }
            if (Boolean.TRUE.equals(map.get("httpOnly"))) {
                cookie.setHttpOnly(true);
            }
            long expiresAt = map.get("expiresAt") instanceof Number
                    ? ((Number) map.get("expiresAt")).longValue() : -1L;
            if (expiresAt > 0) {
                long remaining = (expiresAt - now) / 1000L;
                cookie.setMaxAge(remaining > 0 ? remaining : 0);
            }
            store.put(key(cookie.getName(), cookie.getDomain(), cookie.getPath()),
                    new StoredCookie(cookie, expiresAt));
        }
        return this;
    }

    /**
     * 导出为 Netscape {@code cookies.txt} 格式（可被浏览器/curl 使用）。
     *
     * @param file 目标文件
     * @return this
     * @throws IOException 写出失败
     */
    public synchronized CookieJarImpl exportNetscape(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("# Netscape HTTP Cookie File\n");
        builder.append("# domain\tincludeSubdomains\tpath\tsecure\texpires\tname\tvalue\n");
        purgeExpired(System.currentTimeMillis());
        for (StoredCookie stored : store.values()) {
            HttpCookie cookie = stored.cookie;
            String domain = cookie.getDomain() == null ? "" : cookie.getDomain();
            boolean includeSubdomains = domain.startsWith(".");
            if (!includeSubdomains && !domain.isEmpty() && !isIp(domain)) {
                domain = "." + domain;
            }
            long expiresSeconds = stored.expiresAt < 0 ? 0L : stored.expiresAt / 1000L;
            builder.append(domain).append('\t')
                    .append(includeSubdomains ? "TRUE" : "FALSE").append('\t')
                    .append(cookie.getPath() == null ? "/" : cookie.getPath()).append('\t')
                    .append(cookie.getSecure() ? "TRUE" : "FALSE").append('\t')
                    .append(expiresSeconds).append('\t')
                    .append(cookie.getName()).append('\t')
                    .append(cookie.getValue() == null ? "" : cookie.getValue())
                    .append('\n');
        }
        writeText(file, builder.toString());
        return this;
    }

    /**
     * 从 Netscape {@code cookies.txt} 格式文件导入。
     *
     * @param file cookies.txt 文件
     * @return this
     * @throws IOException 读取失败
     */
    public synchronized CookieJarImpl importNetscape(File file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            long nowSeconds = System.currentTimeMillis() / 1000L;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 7) {
                    continue;
                }
                String domain = parts[0].trim();
                boolean includeSubdomains = "TRUE".equalsIgnoreCase(parts[1].trim());
                String path = parts[2].trim();
                boolean secure = "TRUE".equalsIgnoreCase(parts[3].trim());
                long expiresSeconds;
                try {
                    expiresSeconds = Long.parseLong(parts[4].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                String name = parts[5].trim();
                String value = parts[6];
                if (name.isEmpty()) {
                    continue;
                }
                HttpCookie cookie = new HttpCookie(name, value);
                if (!domain.isEmpty()) {
                    cookie.setDomain(includeSubdomains && !domain.startsWith(".") && !isIp(domain)
                            ? "." + domain : domain);
                }
                if (!path.isEmpty()) {
                    cookie.setPath(path);
                }
                cookie.setSecure(secure);
                long expiresAt = expiresSeconds <= 0 ? -1L : expiresSeconds * 1000L;
                if (expiresAt > 0) {
                    long remaining = expiresSeconds - nowSeconds;
                    cookie.setMaxAge(remaining > 0 ? remaining : 0);
                    if (remaining <= 0) {
                        continue;
                    }
                }
                store.put(key(cookie.getName(), cookie.getDomain(), cookie.getPath()),
                        new StoredCookie(cookie, expiresAt));
            }
        }
        return this;
    }

    private synchronized void purgeExpired(long now) {
        for (Iterator<StoredCookie> iterator = store.values().iterator(); iterator.hasNext(); ) {
            StoredCookie stored = iterator.next();
            if (stored.expiresAt > 0 && stored.expiresAt <= now) {
                iterator.remove();
            }
        }
    }

    private static String key(String name, String domain, String path) {
        return name + "|" + (domain == null ? "" : domain.toLowerCase(Locale.ROOT))
                + "|" + (path == null ? "/" : path);
    }

    private static String key(HttpCookie cookie) {
        return key(cookie.getName(), cookie.getDomain(), cookie.getPath());
    }

    /**
     * RFC 6265 domain-match：域 Cookie 匹配宿主及其子域，主机 Cookie 只精确匹配。
     */
    static boolean domainMatch(String cookieDomain, String host) {
        if (cookieDomain == null || cookieDomain.isEmpty()) {
            return true;
        }
        if (host == null) {
            return false;
        }
        String domain = cookieDomain.toLowerCase(Locale.ROOT);
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * RFC 6265 path-match。
     */
    static boolean pathMatch(String requestPath, String cookiePath) {
        String req = requestPath == null || requestPath.isEmpty() ? "/" : requestPath;
        String cookie = cookiePath == null || cookiePath.isEmpty() ? "/" : cookiePath;
        if (req.equals(cookie)) {
            return true;
        }
        if (req.startsWith(cookie)) {
            if (cookie.endsWith("/")) {
                return true;
            }
            return req.charAt(cookie.length()) == '/';
        }
        return false;
    }

    private static String defaultPath(String requestPath) {
        if (requestPath == null || requestPath.isEmpty() || !requestPath.startsWith("/")) {
            return "/";
        }
        int lastSlash = requestPath.lastIndexOf('/');
        return lastSlash <= 0 ? "/" : requestPath.substring(0, lastSlash);
    }

    private static String hostOf(URI uri) {
        String host = uri.getHost();
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    private static String pathOf(URI uri) {
        String path = uri.getPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static boolean isIp(String host) {
        return host != null && host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create directory: " + parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class StoredCookie {
        private final HttpCookie cookie;
        private final long expiresAt;

        private StoredCookie(HttpCookie cookie, long expiresAt) {
            this.cookie = cookie;
            this.expiresAt = expiresAt;
        }
    }
}
