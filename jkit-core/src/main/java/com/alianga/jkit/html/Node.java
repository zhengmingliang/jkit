package com.alianga.jkit.html;

/**
 * DOM 节点基类，所有 HTML 文档节点的公共抽象。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
public abstract class Node {
    Node parentNode;

    /**
     * 返回父节点，没有则返回 {@code null}。
     *
     * @return 父节点
     */
    public Node parent() {
        return parentNode;
    }

    /**
     * 返回该节点自身的文本内容（递归拼接子节点文本）。
     *
     * @return 文本
     */
    public abstract String nodeText();

    /**
     * 返回该节点的外联 HTML 序列化结果。
     *
     * @return HTML 字符串
     */
    public abstract String outerHtml();

    @Override
    public String toString() {
        return outerHtml();
    }
}
