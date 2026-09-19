package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;

import java.util.List;

/**
 * MySQL {@code DATE_FORMAT} → PG/Oracle {@code TO_CHAR} / SQLite {@code strftime}，
 * 常见格式符（{@code %Y-%m-%d %H:%i:%s} 等）会改写；对不上的保留并 {@code SEMANTIC_RISK}。
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
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE
                || family == SqlDialect.PRESTO) {
            return fn;
        }
        List<SqlExpr> args = fn.arguments();
        if (family == SqlDialect.SQLITE) {
            rewriteSqlite(fn, args, report);
            return fn;
        }
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ORACLE
                || family == SqlDialect.ORACLE12 || family == SqlDialect.DAMENG
                || family == SqlDialect.ANSI || family == SqlDialect.DB2) {
            convertLiteralFormat(args, 1, Style.TO_CHAR, report);
            fn.setName(SqlIdentifier.of("TO_CHAR"));
            return fn;
        }
        if (family == SqlDialect.SQLSERVER) {
            convertLiteralFormat(args, 1, Style.NET, report);
            fn.setName(SqlIdentifier.of("FORMAT"));
            return fn;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATE_FORMAT",
                target + " 无 DATE_FORMAT 等价函数，已保留原文");
        return fn;
    }

    private static void rewriteSqlite(SqlFunctionExpr fn, List<SqlExpr> args,
                                      ConversionReport.Builder report) {
        if (args.size() >= 2) {
            SqlExpr date = args.get(0);
            SqlExpr fmt = args.get(1);
            convertLiteralFormat(args, 1, Style.STRFTIME, report);
            fmt = args.get(1);
            args.set(0, fmt);
            args.set(1, date);
        } else {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATE_FORMAT",
                    "DATE_FORMAT 格式符与 strftime 不完全等价");
        }
        fn.setName(SqlIdentifier.of("strftime"));
    }

    private static void convertLiteralFormat(List<SqlExpr> args, int index, Style style,
                                             ConversionReport.Builder report) {
        if (args == null || index >= args.size() || !(args.get(index) instanceof SqlLiteral)) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATE_FORMAT",
                    "DATE_FORMAT 格式符不是字面量，已改函数名但未转换格式符");
            return;
        }
        SqlLiteral lit = (SqlLiteral) args.get(index);
        if (lit.kind() != SqlLiteral.Kind.STRING || lit.value() == null) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATE_FORMAT",
                    "DATE_FORMAT 格式符不是字符串字面量，已改函数名但未转换格式符");
            return;
        }
        FormatRewrite rewritten = rewriteMysqlFormat(lit.value(), style);
        lit.setValue(rewritten.quoted);
        if (!rewritten.complete) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATE_FORMAT",
                    "部分 DATE_FORMAT 格式符无干净等价，已转换能对上的");
        }
    }

    /**
     * 把 MySQL {@code DATE_FORMAT} 格式字面量（含引号）改成目标风格。
     *
     * @param quoted 含引号的 SQL 字符串
     * @param style 目标
     * @return 改写结果
     */
    static FormatRewrite rewriteMysqlFormat(String quoted, Style style) {
        String raw = unquote(quoted);
        StringBuilder out = new StringBuilder(raw.length() + 4);
        boolean complete = true;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%' && i + 1 < raw.length()) {
                char spec = raw.charAt(i + 1);
                String mapped = mapSpecifier(spec, style);
                if (mapped == null) {
                    complete = false;
                    out.append('%');
                    out.append(spec);
                } else {
                    out.append(mapped);
                }
                i += 2;
                continue;
            }
            if (c == '\'' && style == Style.TO_CHAR) {
                out.append("''");
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return new FormatRewrite(quote(out.toString()), complete);
    }

    private static String mapSpecifier(char spec, Style style) {
        if (style == Style.TO_CHAR) {
            switch (spec) {
                case 'Y':
                    return "YYYY";
                case 'y':
                    return "YY";
                case 'm':
                    return "MM";
                case 'c':
                    return "FMMM";
                case 'd':
                    return "DD";
                case 'e':
                    return "FMDD";
                case 'H':
                    return "HH24";
                case 'k':
                    return "FMHH24";
                case 'h':
                case 'I':
                    return "HH12";
                case 'i':
                    return "MI";
                case 's':
                case 'S':
                    return "SS";
                case 'T':
                    return "HH24:MI:SS";
                case 'p':
                    return "AM";
                case 'W':
                    return "Day";
                case 'a':
                    return "Dy";
                case 'M':
                    return "Month";
                case 'b':
                    return "Mon";
                case 'j':
                    return "DDD";
                case 'f':
                    return "US";
                case '%':
                    return "%";
                default:
                    return null;
            }
        }
        if (style == Style.STRFTIME) {
            switch (spec) {
                case 'Y':
                case 'y':
                case 'm':
                case 'd':
                case 'H':
                case 'w':
                case 'j':
                    return "%" + spec;
                case 'i':
                    return "%M";
                case 's':
                case 'S':
                    return "%S";
                case 'T':
                    return "%H:%M:%S";
                case '%':
                    return "%%";
                default:
                    return null;
            }
        }
        // .NET / SQL Server FORMAT
        switch (spec) {
            case 'Y':
                return "yyyy";
            case 'y':
                return "yy";
            case 'm':
                return "MM";
            case 'd':
                return "dd";
            case 'H':
                return "HH";
            case 'h':
            case 'I':
                return "hh";
            case 'i':
                return "mm";
            case 's':
            case 'S':
                return "ss";
            case 'T':
                return "HH:mm:ss";
            case 'p':
                return "tt";
            case 'M':
                return "MMMM";
            case 'b':
                return "MMM";
            case '%':
                return "%";
            default:
                return null;
        }
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value;
        }
        char a = value.charAt(0);
        char b = value.charAt(value.length() - 1);
        if ((a == '\'' && b == '\'') || (a == '"' && b == '"')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String quote(String raw) {
        return "'" + raw + "'";
    }

    enum Style {
        TO_CHAR,
        STRFTIME,
        NET
    }

    static final class FormatRewrite {
        final String quoted;
        final boolean complete;

        FormatRewrite(String quoted, boolean complete) {
            this.quoted = quoted;
            this.complete = complete;
        }
    }
}
