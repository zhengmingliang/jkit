package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlQueryExpr;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
import com.alianga.jkit.sql.schema.registry.SqlDataTypeRegistry;
import com.alianga.jkit.sql.visitor.SqlAstVisitor;

import java.util.List;
import java.util.Locale;

/**
 * 在已 clone 的 AST 上按目标方言改写函数调用（IF→CASE、NOW→CURRENT_TIMESTAMP 等）。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class FunctionAstRewriter {
    private FunctionAstRewriter() {
    }

    /**
     * @param stmt 语句（就地改写）
     * @param source 源方言
     * @param target 目标方言
     * @param report 报告
     */
    public static void rewrite(SqlStatement stmt, SqlDialect source, SqlDialect target,
                               ConversionReport.Builder report) {
        if (stmt == null || source == target) {
            return;
        }
        stmt.accept(new Visitor(source, target, report));
    }

    private static final class Visitor extends SqlAstVisitor {
        private final SqlDialect source;
        private final SqlDialect target;
        private final ConversionReport.Builder report;
        private final SqlDataTypeRegistry types = SqlDataTypeRegistry.builtins();

        Visitor(SqlDialect source, SqlDialect target, ConversionReport.Builder report) {
            this.source = source;
            this.target = target;
            this.report = report;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitSelect(SqlSelect node) {
            node.setWhere(rewriteExpr(node.where()));
            node.setHaving(rewriteExpr(node.having()));
            rewriteExprList(node.groupBy());
            rewriteExprList(node.distinctOn());
            node.setTop(rewriteExpr(node.top()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitSelectItem(SqlSelectItem node) {
            node.setExpr(rewriteExpr(node.expr()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitOrderByItem(SqlOrderByItem node) {
            node.setExpr(rewriteExpr(node.expr()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitUpdate(SqlUpdate node) {
            node.setWhere(rewriteExpr(node.where()));
            rewriteBinaries(node.setList());
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitDelete(SqlDelete node) {
            node.setWhere(rewriteExpr(node.where()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitInsert(SqlInsert node) {
            rewriteBinaries(node.setList());
            rewriteBinaries(node.duplicateUpdates());
            List<List<SqlExpr>> values = node.valuesList();
            for (int i = 0; i < values.size(); i++) {
                rewriteExprList(values.get(i));
            }
            return true;
        }

        private SqlExpr rewriteExpr(SqlExpr expr) {
            if (expr == null) {
                return null;
            }
            if (expr instanceof SqlFunctionExpr) {
                return rewriteFunction((SqlFunctionExpr) expr);
            }
            if (expr instanceof SqlBinaryExpr) {
                SqlBinaryExpr b = (SqlBinaryExpr) expr;
                b.setLeft(rewriteExpr(b.left()));
                b.setRight(rewriteExpr(b.right()));
                return b;
            }
            if (expr instanceof SqlUnaryExpr) {
                SqlUnaryExpr u = (SqlUnaryExpr) expr;
                u.setExpr(rewriteExpr(u.expr()));
                return u;
            }
            if (expr instanceof SqlCaseExpr) {
                SqlCaseExpr c = (SqlCaseExpr) expr;
                c.setValue(rewriteExpr(c.value()));
                rewriteExprList(c.whenList());
                rewriteExprList(c.thenList());
                c.setElseExpr(rewriteExpr(c.elseExpr()));
                return c;
            }
            if (expr instanceof SqlCastExpr) {
                SqlCastExpr c = (SqlCastExpr) expr;
                c.setExpr(rewriteExpr(c.expr()));
                if (c.dataType() != null) {
                    c.setDataType(types.convert(c.dataType(), source, target));
                }
                return c;
            }
            if (expr instanceof SqlBetweenExpr) {
                SqlBetweenExpr b = (SqlBetweenExpr) expr;
                b.setExpr(rewriteExpr(b.expr()));
                b.setBegin(rewriteExpr(b.begin()));
                b.setEnd(rewriteExpr(b.end()));
                return b;
            }
            if (expr instanceof SqlInExpr) {
                SqlInExpr in = (SqlInExpr) expr;
                in.setExpr(rewriteExpr(in.expr()));
                rewriteExprList(in.values());
                return in;
            }
            if (expr instanceof SqlListExpr) {
                rewriteExprList(((SqlListExpr) expr).items());
                return expr;
            }
            if (expr instanceof SqlQueryExpr) {
                SqlStatement q = ((SqlQueryExpr) expr).query();
                if (q != null) {
                    q.accept(this);
                }
                return expr;
            }
            return expr;
        }

        private SqlExpr rewriteFunction(SqlFunctionExpr fn) {
            List<SqlExpr> args = fn.arguments();
            for (int i = 0; i < args.size(); i++) {
                args.set(i, rewriteExpr(args.get(i)));
            }
            if (fn.separator() != null) {
                fn.setSeparator(rewriteExpr(fn.separator()));
            }
            rewriteOrderExprs(fn);
            String name = functionName(fn);
            FunctionRewriteRule rule = SqlFunctionRegistry.builtins().find(name);
            if (rule != null) {
                SqlExpr rewritten = rule.rewrite(fn, source, target, report);
                if (rewritten != null) {
                    return rewritten;
                }
            }
            if (fn.usingCharset()) {
                if (target != SqlDialect.MYSQL) {
                    report.warn(ConversionWarning.Severity.SEMANTIC_RISK, name,
                            "CONVERT(expr USING charset) 与 CAST 语义不同，已保留原文");
                }
                return fn;
            }
            if ("IF".equals(name) && args.size() >= 3 && target != SqlDialect.MYSQL) {
                SqlCaseExpr cse = new SqlCaseExpr();
                cse.addWhenThen(args.get(0), args.get(1));
                cse.setElseExpr(args.get(2));
                return cse;
            }
            if ("NOW".equals(name) && target != SqlDialect.MYSQL) {
                fn.setName(SqlIdentifier.of("CURRENT_TIMESTAMP"));
                return fn;
            }
            if ("CURDATE".equals(name) && target != SqlDialect.MYSQL) {
                fn.setName(SqlIdentifier.of("CURRENT_DATE"));
                return fn;
            }
            if ("CURTIME".equals(name) && target != SqlDialect.MYSQL) {
                fn.setName(SqlIdentifier.of("CURRENT_TIME"));
                return fn;
            }
            if (("IFNULL".equals(name) || "NVL".equals(name) || "ISNULL".equals(name))
                    && args.size() >= 2) {
                return rewriteNullCoalesce(fn, name);
            }
            if ("GROUP_CONCAT".equals(name) || "STRING_AGG".equals(name) || "LISTAGG".equals(name)) {
                return rewriteListAgg(fn, name);
            }
            if ("CONCAT".equals(name)) {
                return rewriteConcat(fn);
            }
            if ("CONVERT".equals(name) && args.size() >= 2) {
                return rewriteConvertAsCast(fn);
            }
            if ("LOCATE".equals(name) && args.size() >= 2) {
                return rewriteLocate(fn);
            }
            if ("INSTR".equals(name) && args.size() >= 2) {
                return rewriteInstr(fn);
            }
            if ("CHARINDEX".equals(name) && args.size() >= 2) {
                return rewriteCharIndex(fn);
            }
            if ("LENGTH".equals(name) || "CHAR_LENGTH".equals(name)
                    || "CHARACTER_LENGTH".equals(name) || "LEN".equals(name)) {
                return rewriteLength(fn);
            }
            if ("SUBSTRING".equals(name) || "SUBSTR".equals(name)) {
                return rewriteSubstr(fn, name);
            }
            return fn;
        }

        private SqlExpr rewriteLocate(SqlFunctionExpr fn) {
            switch (target) {
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

        private SqlExpr rewriteInstr(SqlFunctionExpr fn) {
            if (target == SqlDialect.ORACLE || target == SqlDialect.ORACLE12) {
                return fn;
            }
            swapFirstTwo(fn);
            if (target == SqlDialect.SQLSERVER) {
                fn.setName(SqlIdentifier.of("CHARINDEX"));
            } else if (target == SqlDialect.MYSQL || target == SqlDialect.H2) {
                fn.setName(SqlIdentifier.of("LOCATE"));
            } else {
                fn.setName(SqlIdentifier.of("POSITION"));
            }
            return fn;
        }

        private SqlExpr rewriteCharIndex(SqlFunctionExpr fn) {
            if (target == SqlDialect.SQLSERVER) {
                return fn;
            }
            if (target == SqlDialect.ORACLE || target == SqlDialect.ORACLE12) {
                swapFirstTwo(fn);
                fn.setName(SqlIdentifier.of("INSTR"));
                return fn;
            }
            if (target == SqlDialect.MYSQL || target == SqlDialect.H2) {
                fn.setName(SqlIdentifier.of("LOCATE"));
                return fn;
            }
            fn.setName(SqlIdentifier.of("POSITION"));
            return fn;
        }

        private SqlExpr rewriteLength(SqlFunctionExpr fn) {
            if (target == SqlDialect.SQLSERVER) {
                fn.setName(SqlIdentifier.of("LEN"));
            } else {
                fn.setName(SqlIdentifier.of("LENGTH"));
            }
            return fn;
        }

        private SqlExpr rewriteSubstr(SqlFunctionExpr fn, String name) {
            if (target == SqlDialect.ORACLE || target == SqlDialect.ORACLE12) {
                fn.setName(SqlIdentifier.of("SUBSTR"));
            } else if ("SUBSTR".equals(name) && target == SqlDialect.SQLSERVER) {
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

        private SqlExpr rewriteNullCoalesce(SqlFunctionExpr fn, String name) {
            String want = coalesceName(target);
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

        private SqlExpr rewriteListAgg(SqlFunctionExpr fn, String name) {
            SqlExpr sep = fn.separator();
            if (sep == null && fn.arguments().size() >= 2) {
                sep = fn.arguments().get(1);
            }
            if (sep == null) {
                sep = SqlLiteral.of(SqlLiteral.Kind.STRING, ",");
            }
            switch (target) {
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
                            target + " 无通用 GROUP_CONCAT/STRING_AGG 等价物，已保留原文");
                    return fn;
            }
        }

        private SqlExpr rewriteConcat(SqlFunctionExpr fn) {
            List<SqlExpr> args = fn.arguments();
            if ((target == SqlDialect.ORACLE || target == SqlDialect.ORACLE12) && args.size() > 2) {
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

        private SqlExpr rewriteConvertAsCast(SqlFunctionExpr fn) {
            List<SqlExpr> args = fn.arguments();
            SqlExpr value;
            String type;
            if (source == SqlDialect.SQLSERVER) {
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
            c.setDataType(types.convert(type, source, target));
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

        private void rewriteOrderExprs(SqlFunctionExpr fn) {
            if (fn.orderBy() == null || fn.orderBy().isEmpty()) {
                return;
            }
            List<SqlOrderByItem> items = fn.orderBy();
            for (int i = 0; i < items.size(); i++) {
                items.get(i).setExpr(rewriteExpr(items.get(i).expr()));
            }
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

        private void rewriteExprList(List<SqlExpr> list) {
            if (list == null || list.isEmpty()) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                list.set(i, rewriteExpr(list.get(i)));
            }
        }

        private void rewriteBinaries(List<SqlBinaryExpr> list) {
            if (list == null) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                SqlBinaryExpr b = list.get(i);
                b.setLeft(rewriteExpr(b.left()));
                b.setRight(rewriteExpr(b.right()));
            }
        }

        private static String functionName(SqlFunctionExpr fn) {
            if (fn.name() == null || fn.name().names().isEmpty()) {
                return "";
            }
            List<String> names = fn.name().names();
            return names.get(names.size() - 1).toUpperCase(Locale.ROOT);
        }
    }
}
