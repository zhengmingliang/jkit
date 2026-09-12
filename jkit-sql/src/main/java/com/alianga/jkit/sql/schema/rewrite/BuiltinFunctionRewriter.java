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
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
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
        // CURRENT_TIMESTAMP / CURRENT_DATE / CURRENT_TIME 在 PG、Oracle、SQL Server 里是
        // 关键字而非函数，写 CURRENT_TIMESTAMP() 会报语法错。无参时直接回标识符，
        // 带精度参数（MySQL NOW(3) → PG CURRENT_TIMESTAMP(3)）才是合法的函数形式。
        if ("NOW".equals(name) && family != SqlDialect.MYSQL) {
            if (args.isEmpty()) {
                return SqlIdentifier.of("CURRENT_TIMESTAMP");
            }
            fn.setName(SqlIdentifier.of("CURRENT_TIMESTAMP"));
            return fn;
        }
        if ("CURDATE".equals(name) && family != SqlDialect.MYSQL) {
            if (args.isEmpty()) {
                return SqlIdentifier.of("CURRENT_DATE");
            }
            fn.setName(SqlIdentifier.of("CURRENT_DATE"));
            return fn;
        }
        if ("CURTIME".equals(name) && family != SqlDialect.MYSQL) {
            if (args.isEmpty()) {
                return SqlIdentifier.of("CURRENT_TIME");
            }
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
        if ("SUBSTRING".equals(name) || "SUBSTR".equals(name)
                || "MID".equals(name) || "LEFT".equals(name) || "RIGHT".equals(name)) {
            return rewriteSubstr(fn, name, family, report);
        }
        if ("DATE_ADD".equals(name) || "ADDDATE".equals(name)
                || "DATE_SUB".equals(name) || "SUBDATE".equals(name)) {
            return rewriteDateAddSub(fn, name, family, report);
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
        if ("UUID".equals(name) || "RAND".equals(name) || "LAST_INSERT_ID".equals(name)) {
            return rewriteMysqlBuiltin(fn, name, family, report);
        }
        if ("REPEAT".equals(name) || "REPLICATE".equals(name) || "RPAD".equals(name)) {
            return rewriteRepeat(fn, name, family, report);
        }
        if ("TO_CHAR".equals(name)) {
            return rewriteToChar(fn, family);
        }
        return fn;
    }

    /**
     * UUID()/RAND()/LAST_INSERT_ID() 这类 MySQL 内建函数，各目标库有各自的名字
     * （PG 是 gen_random_uuid()/random()，SQL Server 是 NEWID()，Oracle 是 SYS_GUID()），
     * 名字与语义细节都不一致，不替用户猜等价；目标方言没有同名函数时告警并保留原文。
     */
    private static SqlExpr rewriteMysqlBuiltin(SqlFunctionExpr fn, String name, SqlDialect family,
                                                 ConversionReport.Builder report) {
        if (family == SqlDialect.MYSQL) {
            return fn;
        }
        boolean sameName = ("UUID".equals(name) && (family == SqlDialect.HIVE || family == SqlDialect.PRESTO))
                || ("RAND".equals(name) && (family == SqlDialect.SQLSERVER || family == SqlDialect.DB2
                        || family == SqlDialect.H2));
        if (!sameName) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    name + " 在 " + family + " 无同名函数（如 PG 用 gen_random_uuid()/random()），已保留原文");
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
            case DAMENG:
            case SQLITE:
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
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG || family == SqlDialect.SQLITE) {
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
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG || family == SqlDialect.SQLITE) {
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

    private static SqlExpr rewriteSubstr(SqlFunctionExpr fn, String name, SqlDialect family,
                                         ConversionReport.Builder report) {
        List<SqlExpr> args = fn.arguments();
        if ("LEFT".equals(name) || "RIGHT".equals(name)) {
            return rewriteLeftRight(fn, name, family, report);
        }
        if ("MID".equals(name)) {
            name = "SUBSTRING";
        }
        boolean oracleFamily = family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG;
        boolean sqlServer = family == SqlDialect.SQLSERVER;
        boolean pgLike = family == SqlDialect.POSTGRES || family == SqlDialect.ANSI
                || family == SqlDialect.PRESTO;
        if (oracleFamily) {
            fn.setName(SqlIdentifier.of("SUBSTR"));
            return fn;
        }
        if (sqlServer) {
            fn.setName(SqlIdentifier.of("SUBSTRING"));
            if (args.size() == 2) {
                SqlExpr from = args.get(1);
                Integer n = intLiteral(from);
                if (n != null && n.intValue() < 0) {
                    SqlFunctionExpr right = new SqlFunctionExpr();
                    right.setName(SqlIdentifier.of("RIGHT"));
                    right.addArgument(args.get(0));
                    right.addArgument(SqlLiteral.of(SqlLiteral.Kind.NUMBER,
                            String.valueOf(-n.intValue())));
                    return right;
                }
                args.add(lengthMinusFromPlusOne(args.get(0), from));
            }
            return fn;
        }
        if (pgLike) {
            fn.setName(SqlIdentifier.of("SUBSTRING"));
            if (args.size() == 2) {
                Integer n = intLiteral(args.get(1));
                if (n != null && n.intValue() < 0) {
                    SqlFunctionExpr right = new SqlFunctionExpr();
                    right.setName(SqlIdentifier.of("RIGHT"));
                    right.addArgument(args.get(0));
                    right.addArgument(SqlLiteral.of(SqlLiteral.Kind.NUMBER,
                            String.valueOf(-n.intValue())));
                    return right;
                }
            }
            return fn;
        }
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE
                || family == SqlDialect.SQLITE || family == SqlDialect.CLICKHOUSE) {
            fn.setName(SqlIdentifier.of("SUBSTRING"));
            return fn;
        }
        fn.setName(SqlIdentifier.of("SUBSTRING"));
        return fn;
    }

    private static SqlExpr rewriteLeftRight(SqlFunctionExpr fn, String name, SqlDialect family,
                                            ConversionReport.Builder report) {
        if (fn.arguments().size() < 2) {
            return fn;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
            SqlFunctionExpr out = new SqlFunctionExpr();
            out.setName(SqlIdentifier.of("SUBSTR"));
            out.addArgument(fn.arguments().get(0));
            if ("LEFT".equals(name)) {
                out.addArgument(SqlLiteral.of(SqlLiteral.Kind.NUMBER, "1"));
                out.addArgument(fn.arguments().get(1));
            } else {
                SqlExpr n = fn.arguments().get(1);
                Integer v = intLiteral(n);
                if (v != null) {
                    out.addArgument(SqlLiteral.of(SqlLiteral.Kind.NUMBER, String.valueOf(-v.intValue())));
                } else {
                    SqlBinaryExpr neg = SqlBinaryExpr.of(SqlLiteral.of(SqlLiteral.Kind.NUMBER, "0"),
                            SqlBinaryOp.MINUS, n);
                    out.addArgument(neg);
                }
            }
            return out;
        }
        return fn;
    }

    private static SqlExpr lengthMinusFromPlusOne(SqlExpr col, SqlExpr from) {
        SqlFunctionExpr length = new SqlFunctionExpr();
        length.setName(SqlIdentifier.of("LEN"));
        length.addArgument(col);
        SqlBinaryExpr minus = SqlBinaryExpr.of(length, SqlBinaryOp.MINUS, from);
        return SqlBinaryExpr.of(minus, SqlBinaryOp.PLUS, SqlLiteral.of(SqlLiteral.Kind.NUMBER, "1"));
    }

    private static Integer intLiteral(SqlExpr expr) {
        int sign = 1;
        if (expr instanceof SqlUnaryExpr) {
            SqlUnaryExpr unary = (SqlUnaryExpr) expr;
            if (unary.operator() == SqlUnaryExpr.Op.MINUS) {
                sign = -1;
                expr = unary.expr();
            } else if (unary.operator() == SqlUnaryExpr.Op.PLUS) {
                expr = unary.expr();
            } else {
                return null;
            }
        }
        if (!(expr instanceof SqlLiteral)) {
            return null;
        }
        SqlLiteral lit = (SqlLiteral) expr;
        if (lit.kind() != SqlLiteral.Kind.NUMBER || lit.value() == null) {
            return null;
        }
        try {
            return Integer.valueOf(sign * Integer.parseInt(lit.value()));
        } catch (NumberFormatException e) {
            return null;
        }
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
        List<SqlExpr> args = fn.arguments();
        // SQL Server 的 ISNULL、Oracle/DAMENG 的 NVL 都只接受 2 个参数；
        // 多参数（≥3）的 COALESCE/IFNULL/NVL 必须保留 ANSI COALESCE，否则真库报语法错。
        if (args.size() >= 3 && (family == SqlDialect.SQLSERVER
                || family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG)) {
            if (!"COALESCE".equalsIgnoreCase(name)) {
                fn.setName(SqlIdentifier.of("COALESCE"));
            }
            return fn;
        }
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
            case DAMENG:
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
            // 字面量 value 存的是源码原文（含引号），formatter 原样输出不再补引号，
            // 所以默认分隔符必须自带引号，否则会渲染成 STRING_AGG(a, ,)
            sep = SqlLiteral.of(SqlLiteral.Kind.STRING, "','");
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
            case DAMENG:
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
        if ((family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) && args.size() > 2) {
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

    private static SqlExpr rewriteDateAddSub(SqlFunctionExpr fn, String name, SqlDialect family,
                                               ConversionReport.Builder report) {
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE) {
            return fn;
        }
        List<SqlExpr> args = fn.arguments();
        if (args.size() < 2) {
            return fn;
        }
        boolean sub = "DATE_SUB".equals(name) || "SUBDATE".equals(name);
        SqlExpr base = args.get(0);
        String[] parts = intervalParts(args.get(1));
        if (parts == null) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    "无法识别的日期间隔参数，已保留原文");
            return fn;
        }
        String num = parts[0];
        String unit = parts[1];
        // SQL Server / SQLite 没有 INTERVAL 字面量，得换成各自的函数
        if (family == SqlDialect.SQLSERVER) {
            return dateAddFunction("DATEADD", base, num, unit, sub, false);
        }
        if (family == SqlDialect.SQLITE) {
            return dateAddFunction("datetime", base, num, unit, sub, true);
        }
        if (family == SqlDialect.DB2) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    "DB2 没有 INTERVAL 字面量，需改写成 " + num + " " + unit + "S（例如 a + " + num
                            + " " + unit + "S），已保留原文");
            return fn;
        }
        SqlExpr interval = toTargetInterval(num, unit, family, name, report);
        if (interval == null) {
            return fn;
        }
        SqlBinaryExpr bin = new SqlBinaryExpr();
        bin.setOperator(sub ? SqlBinaryOp.MINUS : SqlBinaryOp.PLUS);
        // PG/ANSI 等对裸字符串字面量做 + INTERVAL 时不会隐式转 date，会反过来把左边当
        // interval 解析而报语法错；统一把 base 显式 CAST AS DATE，保证类型正确
        bin.setLeft(castToDate(base));
        bin.setRight(interval);
        // 函数调用是最高优先级，展开成运算符后必须整体套括号：
        // DATE_ADD(a, INTERVAL 1 DAY) * 2 若写成 a + INTERVAL ... * 2 会被乘法抢走优先级
        bin.setParenthesized(true);
        return bin;
    }

    /**
     * 从 {@code INTERVAL 3 DAY} 里取出数量与单位。
     *
     * @param expr 间隔表达式
     * @return {@code [数量, 单位]}，识别不了返回 null
     */
    private static String[] intervalParts(SqlExpr expr) {
        if (!(expr instanceof SqlFunctionExpr)) {
            return null;
        }
        SqlFunctionExpr iv = (SqlFunctionExpr) expr;
        if (!"INTERVAL".equals(functionName(iv))) {
            return null;
        }
        List<SqlExpr> a = iv.arguments();
        if (a.size() >= 2 && a.get(0) instanceof SqlLiteral && a.get(1) instanceof SqlIdentifier) {
            String num = ((SqlLiteral) a.get(0)).value();
            String unit = ((SqlIdentifier) a.get(1)).simpleName();
            if (num != null && unit != null && !num.isEmpty() && !unit.isEmpty()) {
                return new String[] {num, unit};
            }
        }
        return null;
    }

    /**
     * SQL Server 的 {@code DATEADD(day, 3, a)} 与 SQLite 的 {@code datetime(a, '+3 days')}。
     *
     * @param fnName 目标函数名
     * @param base 基准表达式
     * @param num 数量
     * @param unit 单位
     * @param sub 是否相减
     * @param modifier SQLite 用字符串修饰符（{@code '+3 days'}）而非数值参数
     * @return 函数调用
     */
    private static SqlFunctionExpr dateAddFunction(String fnName, SqlExpr base, String num,
                                                   String unit, boolean sub, boolean modifier) {
        String n = num.startsWith("-") ? num.substring(1) : num;
        SqlFunctionExpr out = new SqlFunctionExpr();
        out.setName(SqlIdentifier.of(fnName));
        if (modifier) {
            String sign = sub ? "-" : "+";
            out.addArgument(base);
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING,
                    "'" + sign + n + " " + unit.toLowerCase(Locale.ROOT) + "s'"));
        } else {
            out.addArgument(SqlIdentifier.of(unit.toLowerCase(Locale.ROOT)));
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.NUMBER, sub ? "-" + n : n));
            out.addArgument(base);
        }
        return out;
    }

    /**
     * 按目标方言生成 INTERVAL 字面量。PG 系要带引号的字符串（{@code INTERVAL '3 day'}，
     * 无引号的 {@code INTERVAL 3 day} 会报语法错），Oracle 用标准写法 {@code INTERVAL '3' DAY}。
     *
     * @param num 数量
     * @param unit 单位
     * @param family 目标方言族
     * @param fnName 源函数名（告警用）
     * @param report 报告
     * @return 间隔表达式；目标方言无法表达时返回 null（调用方保留原文）
     */
    private static SqlExpr toTargetInterval(String num, String unit, SqlDialect family,
                                            String fnName, ConversionReport.Builder report) {
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ANSI
                || family == SqlDialect.PRESTO || family == SqlDialect.H2) {
            SqlFunctionExpr out = new SqlFunctionExpr();
            out.setName(SqlIdentifier.of("INTERVAL"));
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING,
                    "'" + num + " " + unit.toLowerCase(Locale.ROOT) + "'"));
            return out;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
            SqlFunctionExpr out = new SqlFunctionExpr();
            out.setName(SqlIdentifier.of("INTERVAL"));
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'" + num + "'"));
            out.addArgument(SqlIdentifier.of(unit.toUpperCase(Locale.ROOT)));
            return out;
        }
        if (family == SqlDialect.CLICKHOUSE) {
            // ClickHouse 支持 INTERVAL n UNIT，原样即可
            return intervalOf(num, unit);
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, fnName,
                family + " 无通用 INTERVAL 字面量，已保留原文");
        return null;
    }

    private static SqlFunctionExpr intervalOf(String num, String unit) {
        SqlFunctionExpr out = new SqlFunctionExpr();
        out.setName(SqlIdentifier.of("INTERVAL"));
        out.addArgument(SqlLiteral.of(SqlLiteral.Kind.NUMBER, num));
        out.addArgument(SqlIdentifier.of(unit.toUpperCase(Locale.ROOT)));
        return out;
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
        // MySQL DATEDIFF(a, b) = a - b (天差，date 部分)。
        // SQL Server DATEDIFF(datepart, startdate, enddate) = enddate - startdate，
        // 且 datepart 必须显式给出（MySQL 隐含 day）；参数顺序也相反。
        if (family == SqlDialect.SQLSERVER && args.size() == 2) {
            SqlFunctionExpr out2 = new SqlFunctionExpr();
            out2.setName(SqlIdentifier.of("DATEDIFF"));
            out2.addArgument(SqlIdentifier.of("day"));
            // 参数反转：MySQL 的 a 变 SQL Server 的 enddate（第二参），b 变 startdate（第二参）
            out2.addArgument(args.get(1));
            out2.addArgument(args.get(0));
            return out2;
        }
        if (args.size() < 2) {
            return fn;
        }
        // MySQL DATEDIFF 只比日期部分、返回天数。直接展开成 a - b 只在两端都是 DATE 时才等价：
        // PG 里 TIMESTAMP 相减得 interval 而非天数，语义不对。故显式截断到 DATE 再减。
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ANSI) {
            SqlBinaryExpr bin = SqlBinaryExpr.of(castToDate(args.get(0)), SqlBinaryOp.MINUS,
                    castToDate(args.get(1)));
            bin.setParenthesized(true);
            return bin;
        }
        // Oracle 的 DATE 自带时间部分，TRUNC 去掉时间后相减即得天数
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
            SqlBinaryExpr bin = SqlBinaryExpr.of(truncToDate(args.get(0)), SqlBinaryOp.MINUS,
                    truncToDate(args.get(1)));
            bin.setParenthesized(true);
            return bin;
        }
        // PRESTO 的 date 相减得 interval，且 date_diff 参数顺序与 DATEDIFF 相反，不做推断
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "DATEDIFF",
                "DATEDIFF 在 " + family + " 无通用映射，已保留原文");
        return fn;
    }

    private static SqlCastExpr castToDate(SqlExpr expr) {
        SqlCastExpr cast = new SqlCastExpr();
        cast.setExpr(expr);
        cast.setDataType("DATE");
        return cast;
    }

    private static SqlExpr truncToDate(SqlExpr expr) {
        // Oracle 的 TRUNC 对字符串字面量会按 TRUNC(number) 解析而报 ORA-01722，
        // 必须先把字符串参数显式转成 DATE：TRUNC(TO_DATE(expr, 'YYYY-MM-DD'))
        SqlExpr base = expr instanceof SqlLiteral
                && ((SqlLiteral) expr).kind() == SqlLiteral.Kind.STRING
                ? toDateLiteral(expr) : expr;
        SqlFunctionExpr trunc = new SqlFunctionExpr();
        trunc.setName(SqlIdentifier.of("TRUNC"));
        trunc.arguments().add(base);
        return trunc;
    }

    private static SqlExpr toDateLiteral(SqlExpr expr) {
        SqlFunctionExpr toDate = new SqlFunctionExpr();
        toDate.setName(SqlIdentifier.of("TO_DATE"));
        toDate.addArgument(expr);
        toDate.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'YYYY-MM-DD'"));
        return toDate;
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
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE) {
            return fn;
        }
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ANSI || family == SqlDialect.PRESTO) {
            fn.setName(SqlIdentifier.of("TO_TIMESTAMP"));
            return fn;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
            SqlFunctionExpr epoch = new SqlFunctionExpr();
            epoch.setName(SqlIdentifier.of("TO_DATE"));
            epoch.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'1970-01-01'"));
            epoch.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'yyyy-MM-dd'"));
            SqlFunctionExpr interval = new SqlFunctionExpr();
            interval.setName(SqlIdentifier.of("NUMTODSINTERVAL"));
            if (!fn.arguments().isEmpty()) {
                interval.addArgument(fn.arguments().get(0));
            }
            interval.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'SECOND'"));
            SqlBinaryExpr bin = SqlBinaryExpr.of(epoch, SqlBinaryOp.PLUS, interval);
            bin.setParenthesized(true);
            return bin;
        }
        if (family == SqlDialect.SQLSERVER) {
            SqlFunctionExpr out = new SqlFunctionExpr();
            out.setName(SqlIdentifier.of("DATEADD"));
            out.addArgument(SqlIdentifier.of("SECOND"));
            if (!fn.arguments().isEmpty()) {
                out.addArgument(fn.arguments().get(0));
            }
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'1970-01-01'"));
            return out;
        }
        if (family == SqlDialect.SQLITE) {
            SqlFunctionExpr out = new SqlFunctionExpr();
            out.setName(SqlIdentifier.of("datetime"));
            if (!fn.arguments().isEmpty()) {
                out.addArgument(fn.arguments().get(0));
            }
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'unixepoch'"));
            return out;
        }
        if (family == SqlDialect.CLICKHOUSE) {
            fn.setName(SqlIdentifier.of("fromUnixTimestamp"));
            return fn;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "FROM_UNIXTIME",
                "FROM_UNIXTIME 在 " + family + " 无通用映射，已保留原文");
        return fn;
    }

    private static SqlExpr rewriteUnixTimestamp(SqlFunctionExpr fn, SqlDialect family,
                                                ConversionReport.Builder report) {
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE) {
            return fn;
        }
        if (family == SqlDialect.POSTGRES || family == SqlDialect.ANSI || family == SqlDialect.PRESTO) {
            if (fn.arguments().isEmpty()) {
                return fn;
            }
            SqlFunctionExpr datePart = new SqlFunctionExpr();
            datePart.setName(SqlIdentifier.of("date_part"));
            datePart.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'epoch'"));
            datePart.addArgument(fn.arguments().get(0));
            return datePart;
        }
        if (family == SqlDialect.SQLSERVER) {
            SqlFunctionExpr out = new SqlFunctionExpr();
            out.setName(SqlIdentifier.of("DATEDIFF"));
            out.addArgument(SqlIdentifier.of("SECOND"));
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'1970-01-01'"));
            if (!fn.arguments().isEmpty()) {
                out.addArgument(fn.arguments().get(0));
            }
            return out;
        }
        if (family == SqlDialect.SQLITE) {
            SqlFunctionExpr out = new SqlFunctionExpr();
            out.setName(SqlIdentifier.of("strftime"));
            out.addArgument(SqlLiteral.of(SqlLiteral.Kind.STRING, "'%s'"));
            if (!fn.arguments().isEmpty()) {
                out.addArgument(fn.arguments().get(0));
            }
            return out;
        }
        if (family == SqlDialect.CLICKHOUSE) {
            fn.setName(SqlIdentifier.of("toUnixTimestamp"));
            return fn;
        }
        report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "UNIX_TIMESTAMP",
                "UNIX_TIMESTAMP 在 " + family + " 无通用映射，已保留原文");
        return fn;
    }

    private static SqlExpr rewriteRepeat(SqlFunctionExpr fn, String name, SqlDialect family,
                                         ConversionReport.Builder report) {
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE
                || family == SqlDialect.POSTGRES || family == SqlDialect.PRESTO
                || family == SqlDialect.CLICKHOUSE) {
            if ("REPEAT".equals(name)) {
                return fn;
            }
            fn.setName(SqlIdentifier.of("REPEAT"));
            return fn;
        }
        if (family == SqlDialect.SQLSERVER) {
            fn.setName(SqlIdentifier.of("REPLICATE"));
            return fn;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
            if (fn.arguments().size() < 2) {
                return fn;
            }
            SqlExpr text = fn.arguments().get(0);
            SqlExpr times = fn.arguments().get(1);
            SqlFunctionExpr length = new SqlFunctionExpr();
            length.setName(SqlIdentifier.of("LENGTH"));
            length.addArgument(text);
            SqlBinaryExpr width = SqlBinaryExpr.of(times, SqlBinaryOp.MUL, length);
            SqlFunctionExpr rpad = new SqlFunctionExpr();
            rpad.setName(SqlIdentifier.of("RPAD"));
            rpad.addArgument(text);
            rpad.addArgument(width);
            rpad.addArgument(text);
            return rpad;
        }
        if (family == SqlDialect.SQLITE) {
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    "SQLite 无 REPEAT/REPLICATE，已保留原文");
            return fn;
        }
        fn.setName(SqlIdentifier.of("REPEAT"));
        return fn;
    }

    private static SqlExpr rewriteToChar(SqlFunctionExpr fn, SqlDialect family) {
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG || family == SqlDialect.POSTGRES
                || family == SqlDialect.ANSI) {
            return fn;
        }
        if (family == SqlDialect.MYSQL || family == SqlDialect.H2 || family == SqlDialect.HIVE) {
            if (fn.arguments().size() >= 2) {
                fn.setName(SqlIdentifier.of("DATE_FORMAT"));
            }
            return fn;
        }
        if (family == SqlDialect.SQLSERVER) {
            fn.setName(SqlIdentifier.of("CONVERT"));
            return fn;
        }
        if (family == SqlDialect.SQLITE) {
            fn.setName(SqlIdentifier.of("strftime"));
            return fn;
        }
        return fn;
    }

    private static SqlExpr rewriteToDate(SqlFunctionExpr fn, String name, SqlDialect family,
                                         ConversionReport.Builder report) {
        if ("STR_TO_DATE".equals(name)) {
            if (family == SqlDialect.MYSQL || family == SqlDialect.H2) {
                return fn;
            }
            if (family == SqlDialect.HIVE) {
                report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                        "STR_TO_DATE 在 Hive 改写为 from_unixtime(unix_timestamp(...))，格式符可能有损");
                SqlFunctionExpr unix = new SqlFunctionExpr();
                unix.setName(SqlIdentifier.of("unix_timestamp"));
                unix.arguments().addAll(fn.arguments());
                SqlFunctionExpr fromUnix = new SqlFunctionExpr();
                fromUnix.setName(SqlIdentifier.of("from_unixtime"));
                fromUnix.addArgument(unix);
                return fromUnix;
            }
            if (family == SqlDialect.POSTGRES || family == SqlDialect.ORACLE
                    || family == SqlDialect.ORACLE12 || family == SqlDialect.DAMENG
                    || family == SqlDialect.ANSI) {
                report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                        "STR_TO_DATE 格式符与 TO_DATE/TO_TIMESTAMP 不完全等价");
                fn.setName(SqlIdentifier.of(family == SqlDialect.POSTGRES ? "TO_TIMESTAMP" : "TO_DATE"));
                return fn;
            }
            report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                    "STR_TO_DATE 无映射，已保留原文");
            return fn;
        }
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
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
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
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
        if (family == SqlDialect.ORACLE || family == SqlDialect.ORACLE12
                || family == SqlDialect.DAMENG) {
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
