package com.alianga.jkit.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档根节点，解析结果的顶层容器。
 *
 * <p>DOM 解析完成后不可变（没有 setter，也没有增删子节点的入口），所以索引可以只建一次、
 * 永远不需要失效。索引是**惰性**的：只有真正用到选择器时才构建，只做 {@code parse} + {@code text()}
 * 的场景不会多占一分内存。
 *
 * <p>索引键分三种，前缀不同因而不会相撞：标签名、{@code #id}、{@code .class}。
 * 同一元素按 class token 分别登记，所以 {@code class="a b"} 会同时出现在 {@code .a} 与 {@code .b} 下。
 * 每个键下的元素按文档顺序（深度优先前序）排列，与不建索引时全树遍历的产出顺序完全一致。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class Document extends Element {
    /** 标签名索引键的前缀。 */
    static final String TAG_PREFIX = "t:";
    /** id 索引键的前缀。 */
    static final String ID_PREFIX = "#:";
    /** class 索引键的前缀。 */
    static final String CLASS_PREFIX = ".:";

    private Map<String, List<Element>> index;

    /**
     * 构造文档根，标签名固定为 {@code #document}。
     */
    public Document() {
        super("#document");
    }

    /**
     * 返回 {@code <title>} 的文本，不存在时为空串。
     *
     * @return 标题文本
     */
    public String title() {
        Element t = selectFirst("title");
        return t == null ? "" : t.text();
    }

    /**
     * 返回 {@code <head>} 元素，不存在时 {@code null}。
     *
     * @return head 元素
     */
    public Element head() {
        return selectFirst("head");
    }

    /**
     * 返回 {@code <body>} 元素，不存在时 {@code null}。
     *
     * @return body 元素
     */
    public Element body() {
        return selectFirst("body");
    }

    @Override
    public String outerHtml() {
        return innerHtml();
    }

    /**
     * 返回选择器索引，首次调用时构建。
     *
     * <p>不加同步：并发下最坏是各建一次，内容等价，之后各持一份，不影响正确性
     * （本模块的 DOM 本身就不是线程安全的）。
     *
     * @return 索引键到候选元素的映射，值为按文档顺序排列的元素列表
     */
    Map<String, List<Element>> index() {
        Map<String, List<Element>> idx = index;
        if (idx == null) {
            idx = new HashMap<String, List<Element>>();
            indexInto(idx, this);
            index = idx;
        }
        return idx;
    }

    private static void indexInto(Map<String, List<Element>> idx, Element el) {
        put(idx, TAG_PREFIX + el.tagName(), el);
        String id = el.id();
        if (!id.isEmpty()) {
            put(idx, ID_PREFIX + id, el);
        }
        List<String> names = el.classNames();
        for (int i = 0; i < names.size(); i++) {
            put(idx, CLASS_PREFIX + names.get(i), el);
        }
        int size = el.childCount();
        for (int k = 0; k < size; k++) {
            Node n = el.childAt(k);
            if (n instanceof Element) {
                indexInto(idx, (Element) n);
            }
        }
    }

    private static void put(Map<String, List<Element>> idx, String key, Element el) {
        List<Element> list = idx.get(key);
        if (list == null) {
            list = new ArrayList<Element>(4);
            idx.put(key, list);
        }
        list.add(el);
    }
}
