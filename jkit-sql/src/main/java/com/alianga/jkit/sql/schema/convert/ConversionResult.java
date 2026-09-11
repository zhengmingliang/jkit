package com.alianga.jkit.sql.schema.convert;

/**
 * 转换结果：目标 SQL + {@link ConversionReport}。
 *
 * <p>{@link #toString()} 返回 SQL 文本，便于只要字符串的调用方。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class ConversionResult {
    private final String sql;
    private final ConversionReport report;

    /**
     * @param sql 目标方言 SQL
     * @param report 报告，可空
     */
    public ConversionResult(String sql, ConversionReport report) {
        this.sql = sql == null ? "" : sql;
        this.report = report == null ? ConversionReport.empty() : report;
    }

    /**
     * @return 目标 SQL
     */
    public String sql() {
        return sql;
    }

    /**
     * @return 报告
     */
    public ConversionReport report() {
        return report;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return sql;
    }
}
