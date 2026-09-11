package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlBetweenExpr;
import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlCaseExpr;
import com.alianga.jkit.sql.ast.SqlCastExpr;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlInExpr;
import com.alianga.jkit.sql.ast.SqlInsert;
import com.alianga.jkit.sql.ast.SqlListExpr;
import com.alianga.jkit.sql.ast.SqlOrderByItem;
import com.alianga.jkit.sql.ast.SqlQueryExpr;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlSelectItem;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlUnaryExpr;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.schema.convert.ConversionReport;
import com.alianga.jkit.sql.schema.convert.ConversionWarning;
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
        stmt.accept(new Visitor(target, report));
    }

    private static final class Visitor extends SqlAstVisitor {
        private final SqlDialect target;
        private final ConversionReport.Builder report;

        Visitor(SqlDialect target, ConversionReport.Builder report) {
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
            String name = functionName(fn);
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
            return fn;
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
