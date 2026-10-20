package com.alianga.jkit.storage.engine;

import com.alianga.jkit.json.JSON;
import com.alianga.jkit.storage.StorageException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 网页 localStorage {@code ticket:<uin>} 的解码结果（续期凭据，全部是秘密）。
 *
 * <p>浏览器存法（home-web.js saveTicket）：{@code {"uin","sid","ticketFromType","encryptTicket"}}，
 * 其中 {@code encryptTicket = 每个字符 +3( btoa(JSON(ticket)) )}；解码时 -3、base64 解码、
 * 去掉 {@code fingerprinter=...} 尾巴再 JSON 解析，取 {@code pwd_key}、{@code pwd_ticket_key}。</p>
 *
 * <p>为了好贴，也接受：只贴 encryptTicket 那一段，或已解码的 ticket JSON。</p>
 *
 * @author 郑明亮
 */
final class QqMailTicket {
    /** 网页 7607 模块：微信扫码登录。 */
    static final String FROM_WECHAT = "wechat";

    final String pwdKey;
    final String pwdTicketKey;
    final String ticketFromType;

    private QqMailTicket(String pwdKey, String pwdTicketKey, String ticketFromType) {
        this.pwdKey = pwdKey;
        this.pwdTicketKey = pwdTicketKey;
        this.ticketFromType = ticketFromType;
    }

    /** 网页 refreshSid：微信来源 auth_type=1，其它 6。 */
    int authType() {
        return FROM_WECHAT.equals(ticketFromType) ? 1 : 6;
    }

    /**
     * @param raw localStorage 原值（或 encryptTicket / 已解码 JSON）
     * @return 解码结果；raw 为空返回 null
     * @throws StorageException 格式不对（消息不含原值）
     */
    static QqMailTicket parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        // DevTools 复制值有时带外层引号
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            Object unq = tryParse(s);
            if (unq instanceof String) {
                s = ((String) unq).trim();
            }
        }
        String fromType = null;
        Map<?, ?> obj = asMap(tryParse(s));
        if (obj != null && obj.get("pwd_key") == null) {
            fromType = str(obj.get("ticketFromType"));
            Object inner = obj.get("encryptTicket");
            if (inner == null) {
                throw bad("缺少 encryptTicket（网页退出登录后 ticket 会被清掉，请重新扫码再复制）");
            }
            obj = asMap(tryParse(unshift(String.valueOf(inner))));
        } else if (obj == null) {
            obj = asMap(tryParse(unshift(s)));
        }
        if (obj == null) {
            throw bad("无法解码");
        }
        String pwdKey = str(obj.get("pwd_key"));
        String ticketKey = str(obj.get("pwd_ticket_key"));
        if (pwdKey == null || ticketKey == null) {
            throw bad("缺少 pwd_key / pwd_ticket_key");
        }
        return new QqMailTicket(pwdKey, ticketKey, fromType);
    }

    /** encryptTicket → ticket JSON 文本（失败返回 null）。 */
    static String unshift(String encrypted) {
        StringBuilder sb = new StringBuilder(encrypted.length());
        for (int i = 0; i < encrypted.length(); i++) {
            sb.append((char) (encrypted.charAt(i) - 3));
        }
        try {
            byte[] bin = Base64.getDecoder().decode(sb.toString().trim());
            // atob 得到的是 Latin-1 二进制串
            String text = new String(bin, StandardCharsets.ISO_8859_1);
            int fp = text.indexOf("fingerprinter=");
            return fp >= 0 ? text.substring(0, fp) : text;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 测试用：与网页 saveTicket 相同的编码。 */
    static String shift(String ticketJson) {
        String b64 = Base64.getEncoder().encodeToString(ticketJson.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder sb = new StringBuilder(b64.length());
        for (int i = 0; i < b64.length(); i++) {
            sb.append((char) (b64.charAt(i) + 3));
        }
        return sb.toString();
    }

    private static Object tryParse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return JSON.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<?, ?> asMap(Object o) {
        return o instanceof Map ? (Map<?, ?>) o : null;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String v = String.valueOf(o).trim();
        return v.isEmpty() ? null : v;
    }

    private static StorageException bad(String why) {
        return StorageException.invalidConfig("ticket 格式不对：" + why
                + "；请在已登录 wx.mail.qq.com 的浏览器 DevTools → Application → Local Storage → "
                + "https://wx.mail.qq.com 里复制键 ticket:<QQ号> 的值");
    }

    @Override
    public String toString() {
        return "QqMailTicket{redacted}";
    }
}
