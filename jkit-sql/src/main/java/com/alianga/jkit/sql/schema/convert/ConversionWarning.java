package com.alianga.jkit.sql.schema.convert;

/**
 * 一次转换中的单条警告。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ConversionWarning {
    /**
     * 严重级别。
     */
    public enum Severity {
        INFO,
        SEMANTIC_RISK,
        MANUAL_ACTION_REQUIRED
    }

    private final Severity severity;
    private final String columnOrLocation;
    private final String message;

    /**
     * @param severity 级别
     * @param columnOrLocation 列名或位置
     * @param message 说明
     */
    public ConversionWarning(Severity severity, String columnOrLocation, String message) {
        this.severity = severity == null ? Severity.INFO : severity;
        this.columnOrLocation = columnOrLocation == null ? "" : columnOrLocation;
        this.message = message == null ? "" : message;
    }

    /**
     * @return 级别
     */
    public Severity severity() {
        return severity;
    }

    /**
     * @return 列名或位置
     */
    public String columnOrLocation() {
        return columnOrLocation;
    }

    /**
     * @return 说明
     */
    public String message() {
        return message;
    }
}
