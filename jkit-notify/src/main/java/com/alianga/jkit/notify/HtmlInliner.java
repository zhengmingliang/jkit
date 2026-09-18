package com.alianga.jkit.notify;

import java.util.Map;

/**
 * 把主题样式内联到 HTML 片段的每个标签上（零依赖，不做完整 HTML 解析）。
 *
 * <p>部分邮件客户端（Outlook 桌面版、部分企业邮箱）与微信粘贴会剥离 {@code <head><style>}，
 * 只剩裸标签；内联 {@code style} 属性是唯一还生效的样式来源。这里的扫描只认识
 * {@link Markdown} 自己产出的那几个标签，其余原样透传。
 *
 * <p>两个刻意的设计：
 * <ul>
 *   <li>{@code <pre>} 内部整段原样复制——里面可能已经有高亮生成的 {@code <span style=...>}，
 *       再套一层行内代码样式会互相覆盖；</li>
 *   <li>已带 {@code style} 属性的标签不覆盖，避免破坏调用方自己的样式。</li>
 * </ul>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
final class HtmlInliner {
    private HtmlInliner() {
    }

    /**
     * 给片段里的标签补 {@code style} 属性。
     *
     * @param html {@link Markdown} 产出的片段
     * @param style 主题样式
     * @return 内联后的片段；{@code html} 为空时返回空串
     */
    static String apply(String html, MarkdownStyle style) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        Map<String, String> styles = style.inlineStyles();
        StringBuilder out = new StringBuilder(html.length() + 512);
        int i = 0;
        int n = html.length();
        while (i < n) {
            int open = html.indexOf('<', i);
            if (open < 0) {
                out.append(html, i, n);
                break;
            }
            out.append(html, i, open);
            int close = html.indexOf('>', open + 1);
            if (close < 0) {
                out.append(html, open, n);
                break;
            }
            String tag = html.substring(open + 1, close);
            if (tag.charAt(0) == '/') {
                // 结束标签不上色
                out.append(html, open, close + 1);
                i = close + 1;
                continue;
            }
            String name = tagName(tag);
            if ("pre".equals(name)) {
                i = appendPre(out, html, open, close, tag, styles.get("pre"));
                continue;
            }
            String css = styles.get(name);
            if (css != null && tag.indexOf("style=") < 0) {
                int nameEnd = open + 1 + name.length();
                out.append('<').append(name).append(" style=\"").append(css).append('"')
                        .append(html, nameEnd, close).append('>');
            } else {
                out.append(html, open, close + 1);
            }
            i = close + 1;
        }
        return out.toString();
    }

    /**
     * 复制 {@code <pre>} 整块：只给 {@code <pre>} 上色，内部（含高亮 span）原样透传。
     */
    private static int appendPre(StringBuilder out, String html, int open, int close, String tag,
                                 String css) {
        int end = html.indexOf("</pre>", close);
        String name = "pre";
        int nameEnd = open + 1 + name.length();
        if (css != null && tag.indexOf("style=") < 0) {
            out.append('<').append(name).append(" style=\"").append(css).append('"')
                    .append(html, nameEnd, close).append('>');
        } else {
            out.append(html, open, close + 1);
        }
        if (end < 0) {
            // 没有闭合标签时原样透传剩余内容，不臆造 </pre>
            out.append(html, close + 1, html.length());
            return html.length();
        }
        out.append(html, close + 1, end);
        out.append("</pre>");
        return end + 6;
    }

    /**
     * 取标签名（只取连续字母），{@code </p>} 之类的结束标签会拿到 {@code "p"}。
     */
    private static String tagName(String tag) {
        int i = tag.charAt(0) == '/' ? 1 : 0;
        int start = i;
        while (i < tag.length() && isNameChar(tag.charAt(i))) {
            i++;
        }
        return tag.substring(start, i).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isNameChar(char c) {
        return isLetter(c) || (c >= '0' && c <= '9');
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
