package com.alianga.jkit.html;

/**
 * 文档根节点，解析结果的顶层容器。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class Document extends Element {
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
}
