package com.alianga.jkit.storage.internal;

/**
 * URL 编码工具：S3 / OSS / WebDAV 的路径段与查询参数编码规则不同
 * （路径段保留 {@code /}、逐段编码一次），统一收口避免双重编码。
 *
 * @author 郑明亮
 */
public final class Urls {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Urls() {
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }

    private static String encode(String raw, boolean keepSlash) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if (isUnreserved(c)) {
                out.append(c);
            } else if (keepSlash && c == '/') {
                out.append('/');
            } else {
                out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
        }
        return out.toString();
    }

    /**
     * 编码整个路径：按 {@code /} 分段，逐段做一次百分号编码（RFC 3986 保留字）。
     *
     * @param path 已规范化的对象路径
     * @return 编码后的 URL 路径
     */
    public static String encodePath(String path) {
        return encode(path, true);
    }

    /**
     * 编码单个路径段（不保留 {@code /}）。
     *
     * @param segment 路径段（如 key 的一段）
     * @return 编码后的段
     */
    public static String encodeSegment(String segment) {
        return encode(segment, false);
    }

    /**
     * 编码查询参数值。
     *
     * @param value 原始值
     * @return 编码后的值
     */
    public static String encodeQuery(String value) {
        return encode(value, false);
    }

    /**
     * 解码百分号编码（UTF-8）。
     *
     * @param encoded 编码字符串
     * @return 解码后的字符串，非法序列按原样保留
     */
    public static String decode(String encoded) {
        if (encoded == null || (encoded.indexOf('%') < 0 && encoded.indexOf('+') < 0)) {
            return encoded == null ? "" : encoded;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                int hi = Character.digit(encoded.charAt(i + 1), 16);
                int lo = Character.digit(encoded.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 2;
                } else {
                    out.write(c);
                }
            } else if (c == '+') {
                out.write(' ');
            } else {
                out.write(c);
            }
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
