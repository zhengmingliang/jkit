package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;

import java.util.List;
import java.util.Locale;

/**
 * 内置函数改写，经 {@link SqlFunctionRegistry} 注册；SPI 后注册同名即可覆盖。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class BuiltinFunctionRewriter implements FunctionRewriteRule {
    /** 单例。 */
    public static final BuiltinFunctionRewriter INSTANCE = new BuiltinFunctionRewriter();

    private BuiltinFunctionRewriter() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source, SqlDialectSpec target,
                           ConversionReport.Builder report) {
        SqlDialect family = target == null ? SqlDialect.MYSQL : target.typeFamily();
        List<SqlExpr> args = fn.arguments();
        String name = functionName(fn);
        if (fn.usingCharset()) {
            if (family != SqlDialect.MYSQL) {
                report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                        "CONVERT(expr USING charset) 与 CAST 语义不同，已保留原文");
            }
            return fn;
        }
        if ("IF".equals(name) && args.size() >= 3 && family != SqlDialect.MYSQL) {
            SqlCaseExpr cse = new SqlCaseExpr();
            cse.addWhenThen(args.get(0), args.get(1));
            cse.setElseExpr(args.get(2));
            return cse;
        }
        if ("NOW".equals(name) && family != SqlDialect.MYSQL) {
            fn.setName(SqlIdentifier.of("CURRENT_TIMESTAMP"));
            return fn;
        }
        if ("CURDATE".equals(name) && family != SqlDialect.MYSQL) {
            fn.setName(SqlIdentifier.of("CURRENT_DATE"));
            return fn;
        }
        if ("CURTIME".equals(name) && family != SqlDialect.MYSQL) {
            fn.setName(SqlIdentifier.of("CURRENT_TIME"));
            return fn;
        }
        if (("IFNULL".equals(name) || "NVL".equals(name) || "ISNULL".equals(name)) && args.size() >= 2) {
            return rewriteNullCoalesce(fn, name, family);
        }
        if ("GROUP_CONCAT".equals(name) || "STRING_AGG".equals(name) || "LISTAGG".equals(name)) {
            return rewriteListAgg(fn, name, family, target, report);
        }
        if ("CONCAT".equals(name)) {
            return rewriteConcat(fn, family);
        }
        if ("CONVERT".equals(name) && args.size() >= 2) {
            return rewriteConvertAsCast(fn, source, target);
        }
        if ("LOCATE".equals(name) && args.size() >= 2) {
            return rewriteLocate(fn, family);
        }
        if ("INSTR".equals(name) && args.size() >= 2) {
            return rewriteInstr(fn, family);
        }
        if ("CHARINDEX".equals(name) && args.size() >= 2) {
            return rewriteCharIndex(fn, family);
        }
        if ("LENGTH".equals(name) || "CHAR_LENGTH".equals(name)
                || "CHARACTER_LENGTH".equals(name) || "LEN".equals(name)) {
            return rewriteLength(fn, family);
        }
        if ("SUBSTRING".equals(name) || "SUBSTR".equals(name)) {
            return rewriteSubstr(fn, name, family);
        }
        if ("DATE_ADD".equals(name) || "ADDDATE".equals(name)
                || "DATE_SUB".equals(name) || "SUBDATE".equals(name)) {
            return rewriteDateAddSub(fn, name, family);
        }
        if ("DATEDIFF".equals(name)) {
            return rewriteDateDiff(fn, family, report);
        }
        if ("TIMESTAMPDIFF".equals(name)) {
            return rewriteTimestampDiff(fn, family, report);
        }
        if ("FROM_UNIXTIME".equals(name)) {
            return rewriteFromUnixTime(fn, family, report);
        }
        if ("UNIX_TIMESTAMP".equals(name)) {
            return rewriteUnixTimestamp(fn, family, report);
        }
        if ("STR_TO_DATE".equals(name) || "TO_DATE".equals(name)) {
            return rewriteToDate(fn, name, family, report);
        }
        if ("DECODE".equals(name) && args.size() >= 3) {
            return rewriteDecode(fn, family);
        }
        if ("NVL2".equals(name) && args.size() >= 3) {
            return rewriteNvl2(fn, family);
        }
        if ("SUBSTRING_INDEX".equals(name) || "FIND_IN_SET".equals(name)) {
            if (family != SqlDialect.MYSQL && family != SqlDialect.H2 && family != SqlDialect.HIVE) {
                report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                        name + " 在 " + target.dialectId() + " 无干净等价物，已保留原文");
            }
            return fn;
        }
        return fn;
    }

    static String functionName(SqlFunctionExpr fn) {
        if (fn.name() == null || fn.name().names().isEmpty()) {
            return "";
        }
        List<String> names = fn.name().names();
        return names.get(names.size() - 1).toUpperCase(Locale.ROOT);
    }

    private static SqlExpr rewriteLocate(SqlFunctionExpr fn, SqlDialect family) {
        switch (family) {
            case MYSQL:
            case H2:
            case HIVE:
                return fn;
            case ORACLE:
            case ORACLE12:
                swapFirstTwo(fn);
                fn.setName(SqlIdentifier.of("INSTR"));
                return fn;
            case SQLSERVER:
                fn.setName(SqlIdentifier.of("CHARINDEX"));
                return fn;
            default:
                fn.setName(SqlIdentifier.of("POSITION"));
                return fn;
        }
    }

    private static SqlExpr rewriteInstr(SqlFunctionExpr fn, SqlDialect family) {
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) {
            return fn;
        }
        swapFirstTwo(fn);
        if (family == SqlDialect.SQLSERVER) {
            fn.setName(SqlIdentifier.of("CHARINDEX"));
        } else if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
            fn.setName(SqlIdentifier.of("LOCATE"));
        } else {
            fn.setName(SqlIdentifier.of("POSITION"));
        }
        return fn;
    }

    private static SqlExpr rewriteCharIndex(SqlFunctionExpr fn, SqlDialect family) {
        if (family == SqlDialect.SQLSERVER) {
            return fn;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) {
            swapFirstTwo(fn);
            fn.setName(SqlIdentifier.of("INSTR"));
            return fn;
        }
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
            fn.setName(SqlIdentifier.of("LOCATE"));
            return fn;
        }
        fn.setName(SqlIdentifier.of("POSITION"));
        return fn;
    }

    private static SqlExpr rewriteLength(SqlFunctionExpr fn, SqlDialect family) {
        if (family == SqlDialect.SQLSERVER) {
            fn.setName(SqlIdentifier.of("LEN"));
        } else {
            fn.setName(SqlIdentifier.of("LENGTH"));
        }
        return fn;
    }

    private static SqlExpr rewriteSubstr(SqlFunctionExpr fn, String name, SqlDialect family) {
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) {
            fn.setName(SqlIdentifier.of("SUBSTR"));
        } else if ("SUBSTR".equals(name) && family == SqlDialect.SQLSERVER) {
            fn.setName(SqlIdentifier.of("SUBSTRING"));
        }
        return fn;
    }

    private static void swapFirstTwo(SqlFunctionExpr fn) {
        List<SqlExpr> args = fn.arguments();
        if (args.size() < 2) {
            return;
        }
        SqlExpr a = args.get(0);
        args.set(0, args.get(1));
        args.set(1, a);
    }

    private static SqlExpr rewriteNullCoalesce(SqlFunctionExpr fn, String name, SqlDialect family) {
        String want = coalesceName(family);
        if (want.equals(name)) {
            return fn;
        }
        fn.setName(SqlIdentifier.of(want));
        return fn;
    }

    private static String coalesceName(SqlDialect dialect) {
        switch (dialect) {
            case MYSQL:
            case H2:
            case SQLITE:
                return "IFNULL";
            case ORACLE:
            case ORACLE12:
                return "NVL";
            case SQLSERVER:
                return "ISNULL";
            default:
                return "COALESCE";
        }
    }

    private static SqlExpr rewriteListAgg(SqlFunctionExpr fn, String name, SqlDialect family,
                                          SqlDialectSpec target, ConversionReport.Builder report) {
        SqlExpr sep = fn.separator();
        if (sep == null && fn.arguments().size() >= 2) {
            sep = fn.arguments().get(1);
        }
        if (sep == null) {
            sep = SqlLiteral.of(SqlLiteral.Kind.STRING, ",");
        }
        switch (family) {
            case MYSQL:
            case H2:
                fn.setName(SqlIdentifier.of("GROUP_CONCAT"));
                trimToOneArg(fn);
                fn.setSeparator(sep);
                fn.setWithinGroup(false);
                return fn;
            case SQLITE:
                fn.setName(SqlIdentifier.of("GROUP_CONCAT"));
                setTwoArgs(fn, sep);
                fn.setSeparator(null);
                fn.setWithinGroup(false);
                return fn;
            case POSTGRES:
            case ANSI:
            case PRESTO:
                fn.setName(SqlIdentifier.of("STRING_AGG"));
                setTwoArgs(fn, sep);
                fn.setSeparator(null);
                fn.setWithinGroup(false);
                return fn;
            case SQLSERVER:
            case DB2:
                fn.setName(SqlIdentifier.of("STRING_AGG"));
                setTwoArgs(fn, sep);
                fn.setSeparator(null);
                fn.setWithinGroup(fn.orderBy() != null && !fn.orderBy().isEmpty());
                return fn;
            case ORACLE:
            case ORACLE12:
                fn.setName(SqlIdentifier.of("LISTAGG"));
                setTwoArgs(fn, sep);
                fn.setSeparator(null);
                fn.setWithinGroup(true);
                if (fn.orderBy() == null || fn.orderBy().isEmpty()) {
                    SqlOrderByItem item = new SqlOrderByItem();
                    item.setExpr(fn.arguments().get(0));
                    fn.orderBy().add(item);
                }
                return fn;
            default:
                report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                        target.dialectId() + " 无通用 GROUP_CONCAT/STRING_AGG 等价物，已保留原文");
                return fn;
        }
    }

    private static SqlExpr rewriteConcat(SqlFunctionExpr fn, SqlDialect family) {
        List<SqlExpr> args = fn.arguments();
        if ((family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) && args.size() > 2) {
            SqlExpr acc = args.get(0);
            for (int i = 1; i < args.size(); i++) {
                SqlBinaryExpr bin = new SqlBinaryExpr();
                bin.setOperator(SqlBinaryOp.CONCAT);
                bin.setLeft(acc);
                bin.setRight(args.get(i));
                acc = bin;
            }
            return acc;
        }
        return fn;
    }

    private static SqlExpr rewriteConvertAsCast(SqlFunctionExpr fn, SqlDialectSpec source,
                                                SqlDialectSpec target) {
        List<SqlExpr> args = fn.arguments();
        SqlExpr value;
        String type;
        if (source.typeFamily() == SqlDialect.SQLSERVER) {
            type = typeText(args.get(0));
            value = args.get(1);
        } else {
            value = args.get(0);
            type = typeText(args.get(1));
        }
        if (type == null) {
            return fn;
        }
        SqlCastExpr c = new SqlCastExpr();
        c.setExpr(value);
        c.setDataType(SqlDataTypeRegistry.builtins().convert(type, source, target));
        return c;
    }

    private static void trimToOneArg(SqlFunctionExpr fn) {
        List<SqlExpr> args = fn.arguments();
        while (args.size() > 1) {
            args.remove(args.size() - 1);
        }
    }

    private static void setTwoArgs(SqlFunctionExpr fn, SqlExpr sep) {
        List<SqlExpr> args = fn.arguments();
        if (args.isEmpty()) {
            return;
        }
        SqlExpr col = args.get(0);
        args.clear();
        args.add(col);
        args.add(sep);
    }

    private static SqlExpr rewriteDateAddSub(SqlFunctionExpr fn, String name, SqlDialect family) {
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE) {
            return fn;
        }
        List<SqlExpr> args = fn.arguments();
        if (args.size() < 2) {
            return fn;
        }
        boolean sub = "DATE_SUB".equals(name) || "SUBDATE".equals(name);
        SqlExpr interval = toTargetInterval(args.get(1), family);
        SqlBinaryExpr bin = new SqlBinaryExpr();
        bin.setOperator(sub ? SqlBinaryOp.MINUS : SqlBinaryOp.PLUS);
        bin.setLeft(args.get(0));
        bin.setRight(interval);
        return bin;
    }

    private static SqlExpr toTargetInterval(SqlExpr expr, SqlDialect family) {
        if (!(expr instanceof SqlFunctionExpr)) {
            return expr;
        }
        SqlFunctionExpr iv = (SqlFunctionExpr) expr;
        if (!"INTERVAL".equals(functionName(iv))) {
            return expr;
        }
        if (family != SqlDialect.POSTGRES && family != SqlDialect.ANSI
                && family != SqlDialect.PRESTO && family != SqlDialect.H2) {
            return expr;
        }
        List<SqlExpr> a = iv.arguments();
        if (a.size() >= 2 && a.get(0) instanceof SqlLiteral && a.get(1) instanceof SqlIdentifier) {
            String num = ((SqlLiteral) a.get(0)).value();
            String unit = ((SqlIdentifier) a.get(1)).simpleName();
            if (num != null && unit != null) {
                SqlFunctionExpr out = new SqlFunctionExpr();
                out.setName(SqlIdentifier.of("INTERVAL"));
                out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING,
                        num + " " + unit.toLowerCase(Locale.ROOT)));
                return out;
            }
        }
        return expr;
    }

    private static SqlExpr rewriteDateDiff(SqlFunctionExpr fn, SqlDialect family,
                                           ConversionReport.Builder report) {
        List<SqlExpr> args = fn.arguments();
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
            return fn;
        }
        if (family == SqlDialect.SQLSERVER && args.size() >= 3) {
            return fn;
        }
        if (args.size() < 2) {
            return fn;
        }
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ANSI || family == SqlDialect.PRESTO) {
            SqlBinaryExpr bin = new SqlBinaryExpr();
            bin.setOperator(SqlBinaryOp.MINUS);
            bin.setLeft(args.get(0));
            bin.setRight(args.get(1));
            return bin;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) {
            SqlBinaryExpr bin = new SqlBinaryExpr();
            bin.setOperator(SqlBinaryOp.MINUS);
            bin.setLeft(args.get(0));
            bin.setRight(args.get(1));
            return bin;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATEDIFF",
                "DATEDIFF 在 " + family + " 无通用映射，已保留原文");
        return fn;
    }

    private static SqlExpr rewriteTimestampDiff(SqlFunctionExpr fn, SqlDialect family,
                                                ConversionReport.Builder report) {
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
            return fn;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "TIMESTAMPDIFF",
                "TIMESTAMPDIFF 单位与目标方言不完全等价，已保留原文");
        return fn;
    }

    private static SqlExpr rewriteFromUnixTime(SqlFunctionExpr fn, SqlDialect family,
                                               ConversionReport.Builder report) {
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
            return fn;
        }
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ANSI || family == SqlDialect.PRESTO) {
            fn.setName(SqlIdentifier.of("TO_TIMESTAMP"));
            return fn;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "FROM_UNIXTIME",
                "FROM_UNIXTIME 在 " + family + " 无通用映射，已保留原文");
        return fn;
    }

    private static SqlExpr rewriteUnixTimestamp(SqlFunctionExpr fn, SqlDialect family,
                                                ConversionReport.Builder report) {
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
            return fn;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "UNIX_TIMESTAMP",
                "UNIX_TIMESTAMP 在 " + family + " 无通用映射，已保留原文");
        return fn;
    }

    private static SqlExpr rewriteToDate(SqlFunctionExpr fn, String name, SqlDialect family,
                                         ConversionReport.Builder report) {
        if ("STR_TO_DATE".equals(name)) {
            if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
                return fn;
            }
            if (family == SqlDialect.POSTGRES || family == SqlDialect.ORACLE
                    || family == SqlDialect.ORACLE12 || family == SqlDialect.ANSI) {
                report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                        "STR_TO_DATE 格式符与 TO_DATE/TO_TIMESTAMP 不完全等价");
                fn.setName(SqlIdentifier.of(family == SqlDialect.POSTGRES ? "TO_TIMESTAMP" : "TO_DATE"));
                return fn;
            }
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    "STR_TO_DATE 无映射，已保留原文");
            return fn;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) {
            return fn;
        }
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    "TO_DATE 格式符与 STR_TO_DATE 不完全等价");
            fn.setName(SqlIdentifier.of("STR_TO_DATE"));
            return fn;
        }
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ANSI) {
            fn.setName(SqlIdentifier.of("TO_TIMESTAMP"));
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    "TO_DATE 在 PG 改为 TO_TIMESTAMP，格式符可能有损");
            return fn;
        }
        return fn;
    }

    private static SqlExpr rewriteDecode(SqlFunctionExpr fn, SqlDialect family) {
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) {
            return fn;
        }
        List<SqlExpr> args = fn.arguments();
        SqlCaseExpr cse = new SqlCaseExpr();
        cse.setValue(args.get(0));
        int i = 1;
        while (i + 1 < args.size()) {
            cse.addWhenThen(args.get(i), args.get(i + 1));
            i += 2;
        }
        if (i < args.size()) {
            cse.setElseExpr(args.get(i));
        }
        return cse;
    }

    private static SqlExpr rewriteNvl2(SqlFunctionExpr fn, SqlDialect family) {
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12) {
            return fn;
        }
        List<SqlExpr> args = fn.arguments();
        SqlCaseExpr cse = new SqlCaseExpr();
        cse.addWhenThen(SqlBinaryExpr.of(args.get(0), SqlBinaryOp.IS_NOT, SqlIdentifier.of("NULL")),
                args.get(1));
        cse.setElseExpr(args.get(2));
        return cse;
    }

    private static String typeText(SqlExpr expr) {
        if (expr instanceof SqlIdentifier) {
            return ((SqlIdentifier) expr).qualifiedName();
        }
        if (expr instanceof SqlFunctionExpr) {
            SqlFunctionExpr f = (SqlFunctionExpr) expr;
            String n = functionName(f);
            List<SqlExpr> a = f.arguments();
            if (a == null || a.isEmpty()) {
                return n;
            }
            StringBuilder sb = new StringBuilder(n).append('(');
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                SqlExpr arg = a.get(i);
                if (arg instanceof SqlLiteral) {
                    sb.append(((SqlLiteral) arg).value());
                } else if (arg instanceof SqlIdentifier) {
                    sb.append(((SqlIdentifier) arg).simpleName());
                } else {
                    return n;
                }
            }
            return sb.append(')').toString();
        }
        return null;
    }
}
