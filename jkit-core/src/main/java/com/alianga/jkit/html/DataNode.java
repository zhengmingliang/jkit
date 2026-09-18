package com.alianga.jkit.html;

/**
 * {@code script}/{@code style} 的原始内容节点，序列化时不做实体转义。
 *
 * @author 郑明亮
 * @since 2.0.2
 */
class DataNode extends Node {
    private final String data;

    DataNode(String data) {
        this.data = data;
    }

    /**
     * 返回原始内容。
     *
     * @return 原始内容
     */
    String data() {
        return data;
    }

    @Override
    public String nodeText() {
        return data;
    }

    @Override
    public String outerHtml() {
        return data;
    }
}
