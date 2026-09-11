package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlFunctionTable;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlJoin;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlMerge;
import com.alianga.jkit.sql.ast.SqlMergeWhen;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlOverExpr;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 在已 clone 的 AST 上遍历函数调用并分派给 {@link SqlFunctionRegistry}。
 * 新增/覆盖规则走 {@link com.alianga.jkit.sql.schema.spi.SqlSchemaConverterProvider#registerFunctions}，
 * 不要改本类。SPI 返回 {@code null} 时回落内置规则。
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
    public static void rewrite(SqlStatement stmt, SqlDialectSpec source, SqlDialectSpec target,
                               ConversionReport.Builder report) {
        if (stmt == null || source == null || target == null) {
            return;
        }
        if (source.dialectId() != null && source.dialectId().equals(target.dialectId())
                && source.typeFamily() == target.typeFamily()) {
            return;
        }
        stmt.accept(new Visitor(source, target, report));
    }

    /**
     * 改写单个表达式（列 DEFAULT 等不在语句树上的节点）。
     *
     * @param expr 表达式
     * @param source 源方言
     * @param target 目标方言
     * @param report 报告
     * @return 改写后的节点
     */
    public static SqlExpr rewriteExpr(SqlExpr expr, SqlDialectSpec source, SqlDialectSpec target,
                                      ConversionReport.Builder report) {
        if (expr == null || source == null || target == null) {
            return expr;
        }
        return new Visitor(source, target, report).rewriteExpr(expr);
    }

    private static final class Visitor extends SqlAstVisitor {
        private final SqlDialectSpec source;
        private final SqlDialectSpec target;
        private final ConversionReport.Builder report;
        private final SqlDataTypeRegistry types = SqlDataTypeRegistry.builtins();

        Visitor(SqlDialectSpec source, SqlDialectSpec target, ConversionReport.Builder report) {
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

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitJoin(SqlJoin node) {
            node.setCondition(rewriteExpr(node.condition()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitMerge(SqlMerge node) {
            node.setOn(rewriteExpr(node.on()));
            rewriteExprList(node.output());
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitMergeWhen(SqlMergeWhen node) {
            node.setAndPredicate(rewriteExpr(node.andPredicate()));
            node.setDeleteWhere(rewriteExpr(node.deleteWhere()));
            node.setInsertWhere(rewriteExpr(node.insertWhere()));
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitOverExpr(SqlOverExpr node) {
            rewriteExprList(node.partitionBy());
            if (node.orderBy() != null) {
                List<SqlOrderByItem> items = node.orderBy();
                for (int i = 0; i < items.size(); i++) {
                    items.get(i).setExpr(rewriteExpr(items.get(i).expr()));
                }
            }
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected boolean visitFunctionTable(SqlFunctionTable node) {
            node.setFunction(rewriteExpr(node.function()));
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
                if (b.operator() == SqlBinaryOp.CONCAT && !targetSupportsConcatOperator()) {
                    return concatAsFunction(b);
                }
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
            if (expr instanceof SqlOverExpr) {
                visitOverExpr((SqlOverExpr) expr);
                return expr;
            }
            return expr;
        }

        private SqlExpr rewriteFunction(SqlFunctionExpr fn) {
            List<SqlExpr> args = fn.arguments();
            for (int i = 0; i < args.size(); i++) {
                args.set(i, rewriteExpr(args.get(i)));
            }
            if (fn.hasParameters()) {
                rewriteExprList(fn.parameters());
            }
            if (fn.separator() != null) {
                fn.setSeparator(rewriteExpr(fn.separator()));
            }
            if (fn.filter() != null) {
                fn.setFilter(rewriteExpr(fn.filter()));
            }
            if (fn.against() != null) {
                fn.setAgainst(rewriteExpr(fn.against()));
            }
            if (fn.over() != null) {
                fn.setOver(rewriteExpr(fn.over()));
            }
            rewriteOrderExprs(fn);
            String name = functionName(fn);
            SqlFunctionRegistry registry = SqlFunctionRegistry.builtins();
            FunctionRewriteRule current = registry.find(name);
            SqlExpr rewritten = applyRule(current, fn);
            if (rewritten != null) {
                return rewritten;
            }
            FunctionRewriteRule builtin = registry.findBuiltin(name);
            if (builtin != null && builtin != current) {
                rewritten = applyRule(builtin, fn);
                if (rewritten != null) {
                    return rewritten;
                }
            }
            return fn;
        }

        private SqlExpr applyRule(FunctionRewriteRule rule, SqlFunctionExpr fn) {
            if (rule == null) {
                return null;
            }
            return rule.rewrite(fn, source, target, report);
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

        private void rewriteExprList(List<SqlExpr> list) {
            if (list == null || list.isEmpty()) {
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                list.set(i, rewriteExpr(list.get(i)));
            }
        }

        /**
         * 目标方言是否原生支持 {@code ||} 作为字符串拼接。
         *
         * <p>MySQL 的 {@code ||} 默认是逻辑或（除非开 PIPES_AS_CONCAT），SQL Server 没有 {@code ||}；
         * 其余内置方言（PG / Oracle / DB2 / SQLite / Hive / Presto / H2 / ANSI）都是拼接。</p>
         *
         * @return 支持拼接返回 {@code true}
         */
        private boolean targetSupportsConcatOperator() {
            SqlDialect family = target.typeFamily();
            return family != SqlDialect.MYSQL && family != SqlDialect.SQLSERVER;
        }

        /**
         * 把 {@code a || b} 改写成 {@code CONCAT(a, b)}，用于目标方言没有 {@code ||} 拼接语义的场合。
         *
         * <p>嵌套的 {@code ||}（{@code a || b || c}）会摊平成一个多参 {@code CONCAT(a, b, c)}，
         * 避免产出 {@code CONCAT(CONCAT(a, b), c)}。</p>
         *
         * @param bin 拼接二元表达式
         * @return 改写后的函数调用
         */
        private SqlExpr concatAsFunction(SqlBinaryExpr bin) {
            List<SqlExpr> operands = new ArrayList<SqlExpr>();
            collectConcatOperands(bin, operands);
            SqlFunctionExpr fn = new SqlFunctionExpr();
            fn.setName(SqlIdentifier.of("CONCAT"));
            for (SqlExpr operand : operands) {
                fn.addArgument(rewriteExpr(operand));
            }
            if (target.typeFamily() == SqlDialect.SQLSERVER) {
                report.warn(ConversionWarning.Severity.SEMANTIC_RISK, "CONCAT",
                        "SQL Server 的 CONCAT() 把 NULL 当空串，与 || 的 NULL 传播语义不同，请确认业务是否容忍");
            }
            return fn;
        }

        private void collectConcatOperands(SqlExpr expr, List<SqlExpr> out) {
            if (expr instanceof SqlBinaryExpr
                    && ((SqlBinaryExpr) expr).operator() == SqlBinaryOp.CONCAT) {
                SqlBinaryExpr b = (SqlBinaryExpr) expr;
                collectConcatOperands(b.left(), out);
                collectConcatOperands(b.right(), out);
                return;
            }
            out.add(expr);
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
