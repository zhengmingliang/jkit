package com.alianga.jkit.html;

/**
 * 注释节点，对应 {@code <!-- ... -->}。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public class Comment extends Node {
    private String data;

    Comment(String data) {
        this.data = data;
    }

    @Override
    public String nodeText() {
        return "";
    }

    @Override
    public String outerHtml() {
        return "<!--" + data + "-->";
    }
}
