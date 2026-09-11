package com.alianga.jkit.sql.schema.convert;

import java.util.List;

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
     * 主 SQL 后接附录（CREATE INDEX / SEQUENCE），分号分隔。
     *
     * @return 可一并执行的脚本
     */
    public String sqlWithExtras() {
        List<String> extra = report.extraSql();
        if (extra.isEmpty()) {
            return sql;
        }
        StringBuilder sb = new StringBuilder(sql);
        for (int i = 0; i < extra.size(); i++) {
            if (sb.length() > 0) {
                sb.append(';').append(' ');
            }
            sb.append(extra.get(i));
        }
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return sql;
    }
}
