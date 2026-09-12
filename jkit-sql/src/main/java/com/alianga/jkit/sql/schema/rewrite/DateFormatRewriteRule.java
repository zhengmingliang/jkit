package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;

/**
 * MySQL {@code DATE_FORMAT} → PG/Oracle {@code TO_CHAR}（格式符不完全等价，告警）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class DateFormatRewriteRule implements FunctionRewriteRule {
    /**
     * {@inheritDoc}
     */
    @Override
    public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source, SqlDialectSpec target,
                           ConversionReport.Builder report) {
        SqlDialect family = target == null ? SqlDialect.MYSQL : target.typeFamily();
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE) {
            return fn;
        }
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ORACLE
                || family == SqlDialect.ORACLE12 || family == SqlDialect.DAMENG
                || family == SqlDialect.ANSI) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATE_FORMAT",
                    "DATE_FORMAT 格式符与 TO_CHAR 不完全等价");
            fn.setName(SqlIdentifier.of("TO_CHAR"));
            return fn;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATE_FORMAT",
                target + " 无 DATE_FORMAT 等价函数，已保留原文");
        return fn;
    }
}
