package com.alianga.jkit.storage.engine;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * IMAP 邮箱名 modified UTF-7 编解码（RFC 3501 5.1.3）。
 * <p>可打印 ASCII（0x20-0x7e）原样输出，{@code &} 写作 {@code &-}；其余字符按 UTF-16BE
 * 做 modified BASE64（{@code ,} 代替 {@code /}，无填充），包在 {@code &...-} 中。</p>
 *
 * @author 郑明亮
 */
final class ImapUtf7 {
    private ImapUtf7() {
    }

    /**
     * Unicode 邮箱名 → 线上 modified UTF-7。
     *
     * @param name 邮箱名
     * @return 编码后名字；null 原样返回
     */
    static String encode(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(name.length() + 8);
        int i = 0;
        int n = name.length();
        while (i < n) {
            char c = name.charAt(i);
            if (c == '&') {
                out.append("&-");
                i++;
            } else if (c >= 0x20 && c <= 0x7e) {
                out.append(c);
                i++;
            } else {
                int start = i;
                while (i < n && (name.charAt(i) < 0x20 || name.charAt(i) > 0x7e)) {
                    i++;
                }
                byte[] utf16 = name.substring(start, i).getBytes(StandardCharsets.UTF_16BE);
                String b64 = Base64.getEncoder().withoutPadding().encodeToString(utf16).replace('/', ',');
                out.append('&').append(b64).append('-');
            }
        }
        return out.toString();
    }

    /**
     * 线上 modified UTF-7 → Unicode；非法片段原样保留。
     *
     * @param wire 线上名字
     * @return 解码后名字；null 原样返回
     */
    static String decode(String wire) {
        if (wire == null || wire.indexOf('&') < 0) {
            return wire;
        }
        StringBuilder out = new StringBuilder(wire.length());
        int i = 0;
        int n = wire.length();
        while (i < n) {
            char c = wire.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            int end = wire.indexOf('-', i + 1);
            if (end < 0) {
                out.append(wire, i, n);
                break;
            }
            if (end == i + 1) {
                out.append('&');
            } else {
                String b64 = wire.substring(i + 1, end).replace(',', '/');
                try {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    out.append(new String(bytes, StandardCharsets.UTF_16BE));
                } catch (IllegalArgumentException e) {
                    out.append(wire, i, end + 1);
                }
            }
            i = end + 1;
        }
        return out.toString();
    }
}
