package com.alianga.jkit.html;

/**
 * 文本节点，承载标签之间的纯文本内容。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class TextNode extends Node {
    private String text;

    TextNode(String text) {
        this.text = text;
    }

    /**
     * 返回文本节点的原始文本（实体已在解析阶段解码）。
     *
     * @return 文本
     */
    String text() {
        return text;
    }

    @Override
    public String nodeText() {
        return text;
    }

    @Override
    public String outerHtml() {
        return Html.escapeText(text);
    }
}
