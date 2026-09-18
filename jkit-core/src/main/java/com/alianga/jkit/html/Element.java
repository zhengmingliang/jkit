package com.alianga.jkit.html;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTML 元素节点，承载标签名、属性与子节点，并提供选择器查询与内容抽取 API。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class Element extends Node {
    private static final Set<String> VOID = new java.util.HashSet<String>(Arrays.asList(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"));

    private final String tagName;
    private final Map<String, String> attributes = new LinkedHashMap<String, String>();
    private final List<Node> childNodes = new ArrayList<Node>();

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
        String v = attributes.get(key.toLowerCase());
        return v == null ? "" : v;
    }

    /**
     * 判断是否存在该属性。
     *
     * @param key 属性名（大小写不敏感）
     * @return 是否存在
     */
    public boolean hasAttr(String key) {
        return attributes.containsKey(key.toLowerCase());
    }

    /**
     * 返回所有属性的不可变拷贝。
     *
     * @return 属性映射
     */
    public Map<String, String> attributes() {
        return new LinkedHashMap<String, String>(attributes);
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
        List<String> names = new ArrayList<String>();
        for (String part : className().split("\\s+")) {
            if (!part.isEmpty()) {
                names.add(part);
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
        for (String name : classNames()) {
            if (name.equals(cls)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回元素自身的纯文本（递归拼接所有后代文本节点）。
     *
     * @return 文本
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        appendText(sb);
        return sb.toString();
    }

    private void appendText(StringBuilder sb) {
        for (Node child : childNodes) {
            if (child instanceof Element) {
                ((Element) child).appendText(sb);
            } else {
                sb.append(child.nodeText());
            }
        }
    }

    @Override
    public String nodeText() {
        return text();
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
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            sb.append(' ').append(e.getKey()).append("=\"").append(Html.escapeAttr(e.getValue())).append('"');
        }
        if (childNodes.isEmpty()) {
            if (VOID.contains(tagName)) {
                return sb.append('>').toString();
            }
            sb.append('>').append("</").append(tagName).append('>');
            return sb.toString();
        }
        sb.append('>');
        for (Node child : childNodes) {
            sb.append(child.outerHtml());
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
        for (Node child : childNodes) {
            sb.append(child.outerHtml());
        }
        return sb.toString();
    }

    /**
     * 返回元素列表形式的直接子元素。
     *
     * @return 子元素列表
     */
    public List<Element> children() {
        List<Element> list = new ArrayList<Element>();
        for (Node child : childNodes) {
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
        return childNodes.size();
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
     * 返回父元素，无则 {@code null}（文档根）。
     *
     * @return 父元素
     */
    public Element parentElement() {
        return parentNode instanceof Element ? (Element) parentNode : null;
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

    /**
     * 追加子节点（解析器内部使用）。
     *
     * @param child 子节点
     */
    void appendChild(Node child) {
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
        attributes.put(key.toLowerCase(), value);
    }
}
