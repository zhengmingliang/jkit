package com.alianga.jkit.html;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTML 元素节点，承载标签名、属性与子节点，并提供选择器查询与内容抽取 API。
 *
 * <p>属性表与子节点表均为懒创建：无属性、无子节点的元素不持有任何容器对象，
 * 大文档下可显著降低常驻内存。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class Element extends Node {
    private static final Set<String> VOID = new java.util.HashSet<String>(Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"));

    /** 行内元素：文本拼接时不额外补空格。 */
    private static final Set<String> INLINE = new java.util.HashSet<String>(Arrays.asList(
            "a", "abbr", "acronym", "b", "bdi", "bdo", "big", "button", "cite", "code",
            "data", "del", "dfn", "em", "font", "i", "img", "input", "ins", "kbd", "label",
            "mark", "meter", "noscript", "output", "picture", "progress", "q", "ruby", "s",
            "samp", "select", "slot", "small", "span", "strike", "strong", "sub", "sup",
            "svg", "time", "tt", "u", "var", "wbr"));

    /** 保留原始空白的元素。 */
    private static final Set<String> PRESERVE = new java.util.HashSet<String>(Arrays.asList(
            "pre", "textarea", "script", "style", "plaintext", "xmp", "listing"));

    /** 内容属于数据而非文本的元素，{@code text()} 不计入其内容。 */
    private static final Set<String> NO_TEXT = new java.util.HashSet<String>(Arrays.asList(
            "script", "style"));

    private final String tagName;
    private String[] attrKeys;
    private String[] attrValues;
    private int attrCount;
    private List<Node> childNodes;

    Element(String tagName) {
        this.tagName = tagName;
    }

    /**
     * 返回标签名（解析时统一小写，HTML 标签名大小写不敏感）。
     *
     * @return 标签名
     */
    public String tagName() {
        return tagName;
    }

    /**
     * 返回属性值，属性不存在时返回空串。
     *
     * @param key 属性名（大小写不敏感）
     * @return 属性值
     */
    public String attr(String key) {
        int i = indexOfAttr(key);
        return i < 0 ? "" : attrValues[i];
    }

    /**
     * 判断是否存在该属性。
     *
     * @param key 属性名（大小写不敏感）
     * @return 是否存在
     */
    public boolean hasAttr(String key) {
        return indexOfAttr(key) >= 0;
    }

    /**
     * 返回所有属性的不可变拷贝。
     *
     * @return 属性映射
     */
    public Map<String, String> attributes() {
        if (attrCount == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<String, String>(attrCount + 1);
        for (int i = 0; i < attrCount; i++) {
            map.put(attrKeys[i], attrValues[i]);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 返回属性值，属性不存在时返回 {@code null}（包内使用，避免{@link #hasAttr} + {@link #attr} 两次扫描）。
     *
     * @param key 属性名（大小写不敏感）
     * @return 属性值，不存在返回 null
     */
    String attrOrNull(String key) {
        int i = indexOfAttr(key);
        return i < 0 ? null : attrValues[i];
    }

    /**
     * 查找属性下标：先按原样精确匹配（绝大多数调用已是小写），再按忽略大小写匹配。
     *
     * @param key 属性名
     * @return 下标，不存在返回 -1
     */
    private int indexOfAttr(String key) {
        for (int i = 0; i < attrCount; i++) {
            if (attrKeys[i].equals(key)) {
                return i;
            }
        }
        for (int i = 0; i < attrCount; i++) {
            if (attrKeys[i].equalsIgnoreCase(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 返回 {@code id} 属性值。
     *
     * @return id
     */
    public String id() {
        return attr("id");
    }

    /**
     * 返回 {@code class} 属性整串。
     *
     * @return class 属性值
     */
    public String className() {
        return attr("class");
    }

    /**
     * 返回 class 拆分成的不含空白的 token 列表。
     *
     * @return 类名集合
     */
    public List<String> classNames() {
        String cn = className();
        if (cn.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<String>(4);
        int i = 0;
        int n = cn.length();
        while (i < n) {
            while (i < n && isSep(cn.charAt(i))) {
                i++;
            }
            int s = i;
            while (i < n && !isSep(cn.charAt(i))) {
                i++;
            }
            if (i > s) {
                names.add(cn.substring(s, i));
            }
        }
        return names;
    }

    /**
     * 判断是否包含指定 class。
     *
     * @param cls 类名
     * @return 是否包含
     */
    public boolean hasClass(String cls) {
        String cn = className();
        if (cn.isEmpty()) {
            return false;
        }
        int i = 0;
        int n = cn.length();
        int len = cls.length();
        while (i < n) {
            while (i < n && isSep(cn.charAt(i))) {
                i++;
            }
            int s = i;
            while (i < n && !isSep(cn.charAt(i))) {
                i++;
            }
            if (i - s == len && cn.regionMatches(s, cls, 0, len)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSep(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /**
     * 返回元素自身的纯文本（递归拼接所有后代文本节点）。
     *
     * <p>块级元素之间补一个空格、多余空白折叠为单个空格并去掉首尾空白，与 Jsoup 行为一致；
     * {@code pre}/{@code textarea}/{@code script}/{@code style} 保留原始空白。
     *
     * @return 文本
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        appendText(sb, false);
        return trim(sb.toString());
    }

    /**
     * 返回元素自身持有的文本（只含直接文本子节点，不含后代元素内的文本）。
     *
     * @return 自身文本
     */
    public String ownText() {
        StringBuilder sb = new StringBuilder();
        int size = childCount();
        for (int k = 0; k < size; k++) {
            Node child = childNodes.get(k);
            if (child instanceof TextNode) {
                sb.append(((TextNode) child).text());
            }
        }
        return sb.toString();
    }

    /**
     * 返回未做空白归一化的原始文本（含 {@code script}/{@code style} 内容），
     * 供需要原文的场景使用；日常抽取请用 {@link #text()}。
     *
     * @return 原始文本
     */
    @Override
    public String nodeText() {
        StringBuilder sb = new StringBuilder();
        int size = childCount();
        for (int k = 0; k < size; k++) {
            sb.append(childNodes.get(k).nodeText());
        }
        return sb.toString();
    }

    private void appendText(StringBuilder sb, boolean preserve) {
        if (NO_TEXT.contains(tagName)) {
            return;
        }
        boolean keep = preserve || PRESERVE.contains(tagName);
        int size = childCount();
        for (int k = 0; k < size; k++) {
            Node child = childNodes.get(k);
            if (child instanceof Element) {
                Element el = (Element) child;
                if (!keep && isBlockish(el) && sb.length() > 0
                        && !isWhitespace(sb.charAt(sb.length() - 1))) {
                    sb.append(' ');
                }
                el.appendText(sb, keep);
            } else {
                String t = child.nodeText();
                sb.append(keep ? t : normalise(t));
            }
        }
    }

    /**
     * 判断元素是否块级（文本拼接时用于补空格），{@code br} 视为块级。
     *
     * @param el 待判断元素
     * @return 是否块级
     */
    private static boolean isBlockish(Element el) {
        return "br".equals(el.tagName) || !INLINE.contains(el.tagName);
    }

    private static String normalise(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean ws = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isWhitespace(c)) {
                if (!ws) {
                    sb.append(' ');
                    ws = true;
                }
            } else {
                sb.append(c);
                ws = false;
            }
        }
        return sb.toString();
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /** 去掉首尾空白，与 Jsoup 一致地把 {@code &nbsp;} 也视为可裁剪的空白。 */
    private static String trim(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && isTrimmable(s.charAt(from))) {
            from++;
        }
        while (to > from && isTrimmable(s.charAt(to - 1))) {
            to--;
        }
        return from == 0 && to == s.length() ? s : s.substring(from, to);
    }

    private static boolean isTrimmable(char c) {
        return isWhitespace(c) || c == ' ';
    }

    /**
     * 返回外联 HTML（含起始与结束标签）。
     *
     * @return HTML 字符串
     */
    @Override
    public String outerHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(tagName);
        for (int i = 0; i < attrCount; i++) {
            sb.append(' ').append(attrKeys[i]).append("=\"").append(Html.escapeAttr(attrValues[i])).append('"');
        }
        int size = childCount();
        if (size == 0) {
            if (VOID.contains(tagName)) {
                return sb.append('>').toString();
            }
            sb.append('>').append("</").append(tagName).append('>');
            return sb.toString();
        }
        sb.append('>');
        for (int k = 0; k < size; k++) {
            sb.append(childNodes.get(k).outerHtml());
        }
        sb.append("</").append(tagName).append('>');
        return sb.toString();
    }

    /**
     * 返回内部 HTML（仅子节点序列化）。
     *
     * @return 内部 HTML
     */
    public String innerHtml() {
        StringBuilder sb = new StringBuilder();
        int size = childCount();
        for (int k = 0; k < size; k++) {
            sb.append(childNodes.get(k).outerHtml());
        }
        return sb.toString();
    }

    /**
     * 返回元素列表形式的直接子元素。
     *
     * @return 子元素列表
     */
    public List<Element> children() {
        int size = childCount();
        if (size == 0) {
            return Collections.emptyList();
        }
        List<Element> list = new ArrayList<Element>(size);
        for (int k = 0; k < size; k++) {
            Node child = childNodes.get(k);
            if (child instanceof Element) {
                list.add((Element) child);
            }
        }
        return list;
    }

    /**
     * 返回直接子节点数量。
     *
     * @return 子节点数
     */
    public int childNodeSize() {
        return childCount();
    }

    /**
     * 返回第 {@code index} 个直接子元素。
     *
     * @param index 下标，从 0 开始
     * @return 子元素
     */
    public Element child(int index) {
        return children().get(index);
    }

    /**
     * 返回第 {@code index} 个直接子节点（含文本与注释节点，包内使用）。
     *
     * @param index 下标，从 0 开始
     * @return 子节点
     */
    Node childNode(int index) {
        return childNodes.get(index);
    }

    /**
     * 返回父元素，无则 {@code null}（文档根）。
     *
     * @return 父元素
     */
    public Element parentElement() {
        return parentNode instanceof Element ? (Element) parentNode : null;
    }

    /**
     * 返回同一父元素下的下一个兄弟元素，没有则 {@code null}。
     *
     * @return 下一个兄弟元素
     */
    public Element nextElementSibling() {
        Element p = parentElement();
        if (p == null) {
            return null;
        }
        boolean seen = false;
        int size = p.childCount();
        for (int k = 0; k < size; k++) {
            Node n = p.childNodes.get(k);
            if (n == this) {
                seen = true;
            } else if (seen && n instanceof Element) {
                return (Element) n;
            }
        }
        return null;
    }

    /**
     * 返回同一父元素下的上一个兄弟元素，没有则 {@code null}。
     *
     * @return 上一个兄弟元素
     */
    public Element previousElementSibling() {
        Element p = parentElement();
        if (p == null) {
            return null;
        }
        Element prev = null;
        int size = p.childCount();
        for (int k = 0; k < size; k++) {
            Node n = p.childNodes.get(k);
            if (n == this) {
                return prev;
            }
            if (n instanceof Element) {
                prev = (Element) n;
            }
        }
        return prev;
    }

    /**
     * 返回在同级元素中的下标，从 0 开始；无父元素时为 0。
     *
     * @return 同级下标
     */
    public int elementSiblingIndex() {
        Element p = parentElement();
        if (p == null) {
            return 0;
        }
        int idx = 0;
        int size = p.childCount();
        for (int k = 0; k < size; k++) {
            Node n = p.childNodes.get(k);
            if (n == this) {
                return idx;
            }
            if (n instanceof Element) {
                idx++;
            }
        }
        return idx;
    }

    /**
     * 表单元素的值：textarea 取文本，其余取 {@code value} 属性。
     *
     * @return 值
     */
    public String val() {
        if ("textarea".equals(tagName)) {
            return text();
        }
        return attr("value");
    }

    /**
     * 按 CSS 选择器查询后代中匹配的元素集合。
     *
     * @param css CSS 选择器
     * @return 匹配元素集合
     */
    public Elements select(String css) {
        return Selector.select(css, this);
    }

    /**
     * 按 CSS 选择器返回第一个匹配元素，无匹配时 {@code null}。
     *
     * @param css CSS 选择器
     * @return 首个匹配元素
     */
    public Element selectFirst(String css) {
        return Selector.selectFirst(css, this);
    }

    /** 直接子节点数量（包内使用，无子节点时不分配容器）。 */
    int childCount() {
        return childNodes == null ? 0 : childNodes.size();
    }

    /** 第 {@code index} 个直接子节点（包内使用）。 */
    Node childAt(int index) {
        return childNodes.get(index);
    }

    /**
     * 追加子节点（解析器内部使用）。
     *
     * @param child 子节点
     */
    void appendChild(Node child) {
        if (childNodes == null) {
            childNodes = new ArrayList<Node>(4);
        }
        child.parentNode = this;
        childNodes.add(child);
    }

    /**
     * 设置属性（解析器内部使用）。
     *
     * @param key 属性名（小写）
     * @param value 属性值
     */
    void setAttr(String key, String value) {
        String k = key.toLowerCase();
        for (int i = 0; i < attrCount; i++) {
            if (attrKeys[i].equals(k)) {
                attrValues[i] = value;
                return;
            }
        }
        if (attrKeys == null) {
            attrKeys = new String[2];
            attrValues = new String[2];
        } else if (attrCount == attrKeys.length) {
            int cap = attrKeys.length * 2;
            String[] nk = new String[cap];
            String[] nv = new String[cap];
            System.arraycopy(attrKeys, 0, nk, 0, attrCount);
            System.arraycopy(attrValues, 0, nv, 0, attrCount);
            attrKeys = nk;
            attrValues = nv;
        }
        attrKeys[attrCount] = k;
        attrValues[attrCount] = value;
        attrCount++;
    }
}
