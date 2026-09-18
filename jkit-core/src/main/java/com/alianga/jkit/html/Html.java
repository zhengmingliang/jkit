package com.alianga.jkit.html;

/**
 * 轻量 HTML 工具门面：解析、实体转义与反转义。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class Html {
    private Html() {
    }

    /**
     * 将 HTML 字符串解析为文档对象。
     *
     * @param html HTML 文本
     * @return 文档对象
     */
    public static Document parse(String html) {
        return HtmlParser.parse(html);
    }

    /**
     * 将文本转义为可在 HTML 文本节点中安全出现的字符串（{@code & < >} 转实体）。
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将文本内容转义（包内使用，等价于 {@link #escape}）。
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    static String escapeText(String text) {
        return escape(text);
    }

    /**
     * 将属性值转义（{@code & < > "} 转实体）。
     *
     * @param value 属性值
     * @return 转义后的属性值
     */
    static String escapeAttr(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') {
                sb.append("&amp;");
            } else if (c == '<') {
                sb.append("&lt;");
            } else if (c == '>') {
                sb.append("&gt;");
            } else if (c == '"') {
                sb.append("&quot;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将 HTML 实体反转义（命名实体与 {@code &#nnn;}/{@code &#xhh;} 数字实体）。
     *
     * @param text 含实体的文本
     * @return 反转义后的文本
     */
    public static String unescape(String text) {
        if (text == null) {
            return "";
        }
        int amp = text.indexOf('&');
        if (amp < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int p = text.indexOf('&', i);
            if (p < 0) {
                sb.append(text.substring(i));
                break;
            }
            sb.append(text.substring(i, p));
            int semi = text.indexOf(';', p);
            if (semi > p && semi - p <= 32) {
                String rep = decodeEntity(text.substring(p + 1, semi));
                if (rep != null) {
                    sb.append(rep);
                    i = semi + 1;
                    continue;
                }
            }
            sb.append('&');
            i = p + 1;
        }
        return sb.toString();
    }

    private static String decodeEntity(String ent) {
        if (ent.isEmpty()) {
            return null;
        }
        switch (ent) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            case "nbsp":
                return " ";
            default:
                break;
        }
        String named = Entities.decode(ent);
        if (named != null) {
            return named;
        }
        if (ent.charAt(0) == '#') {
            try {
                int codePoint;
                if (ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')) {
                    codePoint = Integer.parseInt(ent.substring(2), 16);
                } else {
                    codePoint = Integer.parseInt(ent.substring(1));
                }
                if (codePoint >= 0 && codePoint <= 0x10FFFF) {
                    return String.valueOf((char) codePoint);
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
