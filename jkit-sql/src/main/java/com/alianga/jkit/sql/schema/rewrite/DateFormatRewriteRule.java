package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
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
    public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialect source, SqlDialect target,
                           ConversionReport.Builder report) {
        if (target == SqlDialect.MYSQL || target == SqlDialect.H2 || target == SqlDialect.HIVE) {
            return fn;
        }
        if (target == SqlDialect.POSTGRES || target == SqlDialect.ORACLE
                || target == SqlDialect.ORACLE12 || target == SqlDialect.ANSI) {
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
