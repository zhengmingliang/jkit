package com.alianga.jkit.html;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量 HTML 解析器，对不规范 HTML 做容错处理并构建 DOM 树。
 *
 * <p>参照 HTML5 规范实现了三类最常见的容错：
 * <ul>
 *   <li>隐式插入 {@code html}/{@code head}/{@code body} 骨架，使 {@code body p} 这类选择器始终可用；</li>
 *   <li>省略结束标签时按作用域自动闭合，覆盖 {@code p}、{@code li}、{@code dt}/{@code dd}、
 *       {@code td}/{@code th}/{@code tr}、{@code option}、{@code rt}/{@code rp} 等；</li>
 *   <li>{@code table} 内直接出现 {@code tr}/{@code td} 时隐式插入 {@code tbody}。</li>
 * </ul>
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public final class HtmlParser {
    private static final Set<String> RAW = new HashSet<String>(Arrays.asList(
            "script", "style", "textarea"));

    /** 只在 head 中出现的标签，遇到它们会隐式创建 head。 */
    private static final Set<String> HEAD_ONLY = new HashSet<String>(Arrays.asList(
            "head", "title", "meta", "link", "base", "style", "noscript"));

    /** table 内需要隐式补 tbody 的标签。 */
    private static final Set<String> TABLE_ROW = new HashSet<String>(Arrays.asList(
            "tr", "td", "th"));

    /** key 为新开始的标签，value 为遇到它时需要自动闭合的未关闭标签集合。 */
    private static final Map<String, Set<String>> CLOSERS = new HashMap<String, Set<String>>();

    static {
        Set<String> p = set("p");
        String[] block = {"address", "article", "aside", "blockquote", "center", "details",
                "dialog", "dir", "div", "dl", "fieldset", "figcaption", "figure", "footer",
                "header", "hgroup", "hr", "main", "menu", "nav", "ol", "p", "search",
                "section", "summary", "ul", "pre", "listing", "form", "table", "xmp",
                "plaintext", "li", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6"};
        for (String tag : block) {
            CLOSERS.put(tag, p);
        }
        Set<String> heading = set("p", "h1", "h2", "h3", "h4", "h5", "h6");
        for (String tag : new String[]{"h1", "h2", "h3", "h4", "h5", "h6"}) {
            CLOSERS.put(tag, heading);
        }
        CLOSERS.put("li", set("p", "li"));
        CLOSERS.put("dt", set("p", "dt", "dd"));
        CLOSERS.put("dd", set("p", "dt", "dd"));
        CLOSERS.put("option", set("option"));
        CLOSERS.put("optgroup", set("option", "optgroup"));
        CLOSERS.put("tr", set("tr", "td", "th"));
        CLOSERS.put("td", set("td", "th"));
        CLOSERS.put("th", set("td", "th"));
        CLOSERS.put("thead", set("tr", "td", "th"));
        CLOSERS.put("tbody", set("thead", "tr", "td", "th"));
        CLOSERS.put("tfoot", set("thead", "tbody", "tr", "td", "th"));
        CLOSERS.put("rt", set("rt", "rp"));
        CLOSERS.put("rp", set("rt", "rp"));
        CLOSERS.put("colgroup", set("colgroup"));
    }

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
        Ctx ctx = new Ctx(doc);
        int i = 0;
        int n = html.length();
        while (i < n) {
            if (html.charAt(i) == '<') {
                if (i + 4 <= n && html.startsWith("<!--", i)) {
                    int end = html.indexOf("-->", i + 4);
                    if (end < 0) {
                        end = n;
                    }
                    ctx.container(null).appendChild(new Comment(html.substring(i + 4, end)));
                    i = end < n ? end + 3 : n;
                    continue;
                }
                char nc = i + 1 < n ? html.charAt(i + 1) : ' ';
                if (nc == '!' || nc == '?') {
                    int end = html.indexOf('>', i);
                    i = end < 0 ? n : end + 1;
                    continue;
                }
                if (nc == '/') {
                    int end = html.indexOf('>', i);
                    if (end < 0) {
                        end = n;
                    }
                    int from = Math.min(i + 2, end);
                    String name = html.substring(from, end).trim().split("\\s")[0].toLowerCase();
                    i = end < n ? end + 1 : n;
                    ctx.closeTag(name);
                    continue;
                }
                if (Character.isLetter(nc)) {
                    StartTag tag = readStartTag(html, i, n);
                    i = ctx.startTag(tag, html);
                    continue;
                }
                int next = html.indexOf('<', i + 1);
                if (next < 0) {
                    next = n;
                }
                ctx.text(html.substring(i, next));
                i = next;
                continue;
            }
            int next = html.indexOf('<', i);
            if (next < 0) {
                next = n;
            }
            ctx.text(html.substring(i, next));
            i = next;
        }
        ctx.finish();
        return doc;
    }

    private static Set<String> set(String... items) {
        return new HashSet<String>(Arrays.asList(items));
    }

    private static void applyAttrs(Element el, Map<String, String> attrs) {
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            el.setAttr(e.getKey(), e.getValue());
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
                            && html.charAt(j) != '>' && html.charAt(j) != '<'
                            && html.charAt(j) != '"' && html.charAt(j) != '\''
                            && !endOfTag(html, j)) {
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
        return new StartTag(name, attrs, selfClose, isVoid(name), j);
    }

    /** 判断 {@code j} 处的 {@code /} 是否为自闭合标记（{@code />} 或位于串尾）。 */
    private static boolean endOfTag(String html, int j) {
        return html.charAt(j) == '/' && (j + 1 >= html.length() || html.charAt(j + 1) == '>');
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

    /** 解析期上下文：维护开放元素栈与隐式骨架。 */
    private static final class Ctx {
        private final List<Element> stack = new ArrayList<Element>();
        private final Element htmlEl;
        private Element headEl;
        private Element bodyEl;

        Ctx(Document doc) {
            this.htmlEl = new Element("html");
            doc.appendChild(htmlEl);
            stack.add(doc);
            stack.add(htmlEl);
        }

        Element top() {
            return stack.get(stack.size() - 1);
        }

        boolean inStack(Element el) {
            for (Element e : stack) {
                if (e == el) {
                    return true;
                }
            }
            return false;
        }

        void popTo(Element el) {
            while (stack.size() > 1 && top() != el) {
                stack.remove(stack.size() - 1);
            }
        }

        Element head() {
            if (headEl == null) {
                headEl = new Element("head");
                htmlEl.appendChild(headEl);
            }
            if (!inStack(headEl)) {
                popTo(htmlEl);
                stack.add(headEl);
            }
            return headEl;
        }

        void openBody() {
            head();
            popTo(htmlEl);
            if (bodyEl == null) {
                bodyEl = new Element("body");
                htmlEl.appendChild(bodyEl);
            }
            stack.add(bodyEl);
        }

        Element container(String tag) {
            if (bodyEl != null && inStack(bodyEl)) {
                return top();
            }
            if (tag != null && HEAD_ONLY.contains(tag)) {
                return head();
            }
            if (headEl != null && inStack(headEl)) {
                return top();
            }
            return htmlEl;
        }

        void text(String s) {
            if (s.isEmpty()) {
                return;
            }
            if (bodyEl == null || !inStack(bodyEl)) {
                if (headEl != null && inStack(headEl)) {
                    top().appendChild(new TextNode(Html.unescape(s)));
                    return;
                }
                if (s.trim().isEmpty()) {
                    return;
                }
                openBody();
            }
            top().appendChild(new TextNode(Html.unescape(s)));
        }

        int startTag(StartTag tag, String html) {
            String name = tag.name;
            if ("html".equals(name)) {
                return tag.next;
            }
            if ("head".equals(name)) {
                applyAttrs(head(), tag.attrs);
                return tag.next;
            }
            if ("body".equals(name)) {
                openBody();
                applyAttrs(bodyEl, tag.attrs);
                return tag.next;
            }
            if (bodyEl == null && (HEAD_ONLY.contains(name) || "script".equals(name))) {
                head();
            } else {
                openBodyIfNeeded();
                autoClose(name);
                if (TABLE_ROW.contains(name)) {
                    ensureTableSection(name);
                }
            }
            Element el = new Element(name);
            applyAttrs(el, tag.attrs);
            top().appendChild(el);
            if (!tag.voidElement && !tag.selfClose) {
                stack.add(el);
            }
            if (RAW.contains(name) && !tag.selfClose) {
                int closeIdx = findClose(html, name, tag.next, html.length());
                String raw = html.substring(tag.next, closeIdx);
                if ("textarea".equals(name)) {
                    el.appendChild(new TextNode(Html.unescape(raw)));
                } else {
                    el.appendChild(new DataNode(raw));
                }
                return closeIdx;
            }
            return tag.next;
        }

        /** 解析结束时补齐骨架，保证空输入也有 html/head/body。 */
        void finish() {
            if (bodyEl == null) {
                openBody();
            }
        }

        void openBodyIfNeeded() {
            if (bodyEl == null || !inStack(bodyEl)) {
                openBody();
            }
        }

        /**
         * 按 HTML5 的“关闭到某元素”语义自动闭合未关闭的标签。
         *
         * <p>分两步反复执行：先弹出栈顶所有可闭合标签（处理 {@code <td>1<td>2}、
         * {@code <tr>..<tr>} 这类连续同级），再处理被行内元素埋住的 {@code p}
         * （{@code <p>a<b>c<div>d} 需要连 {@code b} 一起弹出）。
         *
         * @param newTag 即将开始的标签
         */
        void autoClose(String newTag) {
            Set<String> targets = CLOSERS.get(newTag);
            if (targets == null) {
                return;
            }
            int floor = indexOf(bodyEl) + 1;
            boolean changed = true;
            while (changed) {
                changed = false;
                while (stack.size() > floor && targets.contains(top().tagName().toLowerCase())) {
                    stack.remove(stack.size() - 1);
                    changed = true;
                }
                if (targets.contains("p")) {
                    for (int k = stack.size() - 1; k >= floor; k--) {
                        if ("p".equals(stack.get(k).tagName())) {
                            while (stack.size() > k) {
                                stack.remove(stack.size() - 1);
                            }
                            changed = true;
                            break;
                        }
                    }
                }
            }
        }

        int indexOf(Element el) {
            for (int k = 0; k < stack.size(); k++) {
                if (stack.get(k) == el) {
                    return k;
                }
            }
            return -1;
        }

        void ensureTableSection(String name) {
            Element t = top();
            if (!"table".equals(t.tagName())) {
                return;
            }
            Element tbody = new Element("tbody");
            t.appendChild(tbody);
            stack.add(tbody);
            if (!"tr".equals(name)) {
                Element tr = new Element("tr");
                tbody.appendChild(tr);
                stack.add(tr);
            }
        }

        void closeTag(String name) {
            if (name.isEmpty()) {
                return;
            }
            if ("html".equals(name)) {
                popTo(htmlEl);
                return;
            }
            if ("head".equals(name)) {
                head();
                popTo(headEl);
                return;
            }
            if ("body".equals(name)) {
                if (bodyEl != null) {
                    popTo(htmlEl);
                }
                return;
            }
            for (int k = stack.size() - 1; k >= 2; k--) {
                if (stack.get(k).tagName().equalsIgnoreCase(name)) {
                    while (stack.size() > k) {
                        stack.remove(stack.size() - 1);
                    }
                    return;
                }
            }
        }
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
