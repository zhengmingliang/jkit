package com.alianga.jkit.yaml;

/**
 * YAML 文本解析失败时抛出的运行时异常。
 */
public class YamlParseException extends RuntimeException {
    /**
     * 使用错误消息创建异常。
     * @param message 错误消息
     */
    public YamlParseException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和原因创建异常。
     * @param message 错误消息
     * @param cause 原始异常
     */
    public YamlParseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用原始异常创建异常。
     * @param cause 原始异常
     */
    public YamlParseException(Throwable cause) {
        super(cause);
    }
}
