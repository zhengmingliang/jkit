package com.alianga.jkit.sql.schema.convert;

/**
 * 转换因 {@link SqlSchemaConvertOptions#failOnSeverity(ConversionWarning.Severity)} 阻断。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlSchemaConversionException extends RuntimeException {
    private final ConversionReport report;

    /**
     * @param message 说明
     * @param report 报告
     */
    public SqlSchemaConversionException(String message, ConversionReport report) {
        super(message);
        this.report = report == null ? ConversionReport.empty() : report;
    }

    /**
     * @return 报告
     */
    public ConversionReport report() {
        return report;
    }
}
