package com.alianga.jkit.html;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量 HTML 解析器，对不规范 HTML 做容错处理并构建 DOM 树。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class HtmlParser {
    private static final Set<String> RAW = new HashSet<String>(Arrays.asList(
            "script", "style", "textarea"));

    private HtmlParser() {
    }

    /**
     * 将 HTML 字符串解析为文档对象。
     *
     * @param html HTML 文本
     * @return 文档对象
     */
    public static Document parse(String html) {
        if (html == null) {
            html = "";
        }
        Document doc = new Document();
        List<Element> stack = new ArrayList<Element>();
        stack.add(doc);
        int i = 0;
        int n = html.length();
        while (i < n) {
            if (html.charAt(i) == '<') {
                if (i + 4 <= n && html.startsWith("<!--", i)) {
                    int end = html.indexOf("-->", i + 4);
                    if (end < 0) {
                        end = n;
                    }
                    top(stack).appendChild(new Comment(html.substring(i + 4, end)));
                    i = end < n ? end + 3 : n;
                } else if (i + 1 < n && html.charAt(i + 1) == '!') {
                    int end = html.indexOf('>', i);
                    if (end < 0) {
                        end = n - 1;
                    }
                    i = end + 1;
                } else if (i + 1 < n && html.charAt(i + 1) == '/') {
                    int end = html.indexOf('>', i);
                    if (end < 0) {
                        end = n;
                    }
                    String name = html.substring(i + 2, end).trim().split("\\s")[0];
                    i = end + 1;
                    closeTag(stack, name);
                } else if (i + 1 < n && Character.isLetter(html.charAt(i + 1))) {
                    StartTag tag = readStartTag(html, i, n);
                    Element el = new Element(tag.name);
                    for (Map.Entry<String, String> e : tag.attrs.entrySet()) {
                        el.setAttr(e.getKey(), e.getValue());
                    }
                    top(stack).appendChild(el);
                    if (!tag.voidElement && !tag.selfClose) {
                        stack.add(el);
                    }
                    i = tag.next;
                    if (RAW.contains(tag.name)) {
                        int closeIdx = findClose(html, tag.name, i, n);
                        String raw = html.substring(i, closeIdx);
                        if ("textarea".equals(tag.name)) {
                            raw = Html.unescape(raw);
                        }
                        el.appendChild(new TextNode(raw));
                        i = closeIdx;
                    }
                } else {
                    int next = html.indexOf('<', i + 1);
                    if (next < 0) {
                        next = n;
                    }
                    top(stack).appendChild(new TextNode(Html.unescape(html.substring(i, next))));
                    i = next;
                }
            } else {
                int next = html.indexOf('<', i);
                if (next < 0) {
                    next = n;
                }
                top(stack).appendChild(new TextNode(Html.unescape(html.substring(i, next))));
                i = next;
            }
        }
        return doc;
    }

    private static Element top(List<Element> stack) {
        return stack.get(stack.size() - 1);
    }

    private static void closeTag(List<Element> stack, String name) {
        for (int k = stack.size() - 1; k >= 1; k--) {
            if (stack.get(k).tagName().equalsIgnoreCase(name)) {
                while (stack.size() > k) {
                    stack.remove(stack.size() - 1);
                }
                return;
            }
        }
    }

    private static int findClose(String html, String tag, int from, int n) {
        String marker = "</" + tag;
        int idx = from;
        while (idx < n) {
            idx = html.toLowerCase().indexOf(marker, idx);
            if (idx < 0) {
                return n;
            }
            int p = idx + marker.length();
            if (p >= n || html.charAt(p) == '>' || Character.isWhitespace(html.charAt(p))) {
                return idx;
            }
            idx = p;
        }
        return n;
    }

    private static StartTag readStartTag(String html, int start, int n) {
        int j = start + 1;
        int ks = j;
        while (j < n && isTagChar(html.charAt(j))) {
            j++;
        }
        String name = html.substring(ks, j).toLowerCase();
        Map<String, String> attrs = new java.util.LinkedHashMap<String, String>();
        boolean selfClose = false;
        boolean voidElement = false;
        while (j < n) {
            while (j < n && Character.isWhitespace(html.charAt(j))) {
                j++;
            }
            if (j >= n) {
                break;
            }
            char c = html.charAt(j);
            if (c == '>') {
                j++;
                break;
            }
            if (c == '/') {
                if (j + 1 < n && html.charAt(j + 1) == '>') {
                    selfClose = true;
                    j += 2;
                } else {
                    j++;
                }
                break;
            }
            int as = j;
            while (j < n && !Character.isWhitespace(html.charAt(j))
                    && html.charAt(j) != '=' && html.charAt(j) != '>'
                    && html.charAt(j) != '/') {
                j++;
            }
            String an = html.substring(as, j).toLowerCase();
            while (j < n && Character.isWhitespace(html.charAt(j))) {
                j++;
            }
            String av = "";
            if (j < n && html.charAt(j) == '=') {
                j++;
                while (j < n && Character.isWhitespace(html.charAt(j))) {
                    j++;
                }
                if (j < n && (html.charAt(j) == '"' || html.charAt(j) == '\'')) {
                    char q = html.charAt(j);
                    j++;
                    int vs = j;
                    while (j < n && html.charAt(j) != q) {
                        j++;
                    }
                    av = html.substring(vs, j);
                    if (j < n) {
                        j++;
                    }
                } else {
                    int vs = j;
                    while (j < n && !Character.isWhitespace(html.charAt(j))
                            && html.charAt(j) != '>' && html.charAt(j) != '/') {
                        j++;
                    }
                    av = html.substring(vs, j);
                }
                av = Html.unescape(av);
            }
            if (!an.isEmpty()) {
                attrs.put(an, av);
            }
        }
        voidElement = isVoid(name);
        return new StartTag(name, attrs, selfClose, voidElement, j);
    }

    private static boolean isTagChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':';
    }

    private static boolean isVoid(String tag) {
        return "area".equals(tag) || "base".equals(tag) || "br".equals(tag)
                || "col".equals(tag) || "embed".equals(tag) || "hr".equals(tag)
                || "img".equals(tag) || "input".equals(tag) || "link".equals(tag)
                || "meta".equals(tag) || "param".equals(tag) || "source".equals(tag)
                || "track".equals(tag) || "wbr".equals(tag);
    }

    private static final class StartTag {
        final String name;
        final Map<String, String> attrs;
        final boolean selfClose;
        final boolean voidElement;
        final int next;

        StartTag(String name, Map<String, String> attrs, boolean selfClose, boolean voidElement, int next) {
            this.name = name;
            this.attrs = attrs;
            this.selfClose = selfClose;
            this.voidElement = voidElement;
            this.next = next;
        }
    }
}
