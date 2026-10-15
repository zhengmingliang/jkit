package com.alianga.jkit.storage.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按域存 Cookie 的极简 CookieJar：只接收、只发送给 {@code qq.com} 及其子域，
 * 重定向到其它域时不带 Cookie，避免泄露。Cookie 值为秘密，不记录。
 *
 * @author 郑明亮
 */
public final class QqMailCookieJar {
    /** Cookie 变化监听：宿主可加密后写回连接配置。 */
    public interface CookieUpdateListener {
        /**
         * @param cookieHeader 合并后的 {@code name=value; ...}（秘密）
         */
        void onCookiesUpdated(String cookieHeader);
    }

    private static final String ROOT = "qq.com";
    /** domain → (name → value)。 */
    private final Map<String, Map<String, String>> byDomain = new LinkedHashMap<String, Map<String, String>>();
    private volatile CookieUpdateListener listener;

    /**
     * @param initial 用户配置的 Cookie 串（作用于 qq.com 全域）
     */
    public QqMailCookieJar(String initial) {
        if (initial != null) {
            for (String pair : initial.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    put(ROOT, pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
        }
    }

    void setListener(CookieUpdateListener listener) {
        this.listener = listener;
    }

    static boolean isQqHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals(ROOT) || h.endsWith("." + ROOT);
    }

    /**
     * @param host 目标主机
     * @return 应发送的 Cookie 头；非 qq.com 返回 null
     */
    synchronized String headerFor(String host) {
        if (!isQqHost(host)) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        Map<String, String> merged = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Map<String, String>> e : byDomain.entrySet()) {
            String d = e.getKey();
            if (h.equals(d) || h.endsWith("." + d)) {
                merged.putAll(e.getValue());
            }
        }
        return merged.isEmpty() ? null : join(merged);
    }

    /**
     * 吸收响应的 Set-Cookie；有变化时通知监听器。
     *
     * @param host       响应主机
     * @param setCookies Set-Cookie 头值
     */
    void absorb(String host, List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty() || !isQqHost(host)) {
            return;
        }
        boolean changed = false;
        synchronized (this) {
            for (String sc : setCookies) {
                if (sc == null) {
                    continue;
                }
                String[] parts = sc.split(";");
                int eq = parts[0].indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = parts[0].substring(0, eq).trim();
                String value = parts[0].substring(eq + 1).trim();
                String domain = host.toLowerCase(Locale.ROOT);
                boolean remove = false;
                for (int i = 1; i < parts.length; i++) {
                    String attr = parts[i].trim();
                    String lower = attr.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("domain=")) {
                        String d = attr.substring(7).trim().toLowerCase(Locale.ROOT);
                        domain = d.startsWith(".") ? d.substring(1) : d;
                    } else if (lower.startsWith("max-age=")) {
                        try {
                            remove = Long.parseLong(attr.substring(8).trim()) <= 0L;
                        } catch (NumberFormatException ignored) {
                            // ignore
                        }
                    }
                }
                if (!isQqHost(domain)) {
                    continue;
                }
                if (remove) {
                    for (Map<String, String> m : byDomain.values()) {
                        changed |= m.remove(name) != null;
                    }
                } else {
                    String old = get(domain, name);
                    if (!value.equals(old)) {
                        // 同名旧值（含初始 qq.com 域）以新值为准
                        for (Map<String, String> m : byDomain.values()) {
                            m.remove(name);
                        }
                        put(domain, name, value);
                        changed = true;
                    }
                }
            }
        }
        CookieUpdateListener l = listener;
        if (changed && l != null) {
            l.onCookiesUpdated(snapshot());
        }
    }

    /** @return 所有 Cookie 合并串（秘密） */
    public synchronized String snapshot() {
        Map<String, String> merged = new LinkedHashMap<String, String>();
        for (Map<String, String> m : byDomain.values()) {
            merged.putAll(m);
        }
        return join(merged);
    }

    private String get(String domain, String name) {
        Map<String, String> m = byDomain.get(domain);
        return m == null ? null : m.get(name);
    }

    private void put(String domain, String name, String value) {
        Map<String, String> m = byDomain.get(domain);
        if (m == null) {
            m = new LinkedHashMap<String, String>();
            byDomain.put(domain, m);
        }
        m.put(name, value);
    }

    private static String join(Map<String, String> m) {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            out.add(e.getKey() + "=" + e.getValue());
        }
        return String.join("; ", out);
    }

    @Override
    public String toString() {
        return "QqMailCookieJar{redacted}";
    }
}
