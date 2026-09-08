package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlBinaryExpr;
import com.alianga.jkit.sql.ast.SqlBinaryOp;
import com.alianga.jkit.sql.ast.SqlDelete;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlIdentifier;
import com.alianga.jkit.sql.ast.SqlLimit;
import com.alianga.jkit.sql.ast.SqlLiteral;
import com.alianga.jkit.sql.ast.SqlNode;
import com.alianga.jkit.sql.ast.SqlSelect;
import com.alianga.jkit.sql.ast.SqlStatement;
import com.alianga.jkit.sql.ast.SqlTable;
import com.alianga.jkit.sql.ast.SqlUpdate;
import com.alianga.jkit.sql.visitor.SqlVisitorAdapter;

/**
 * 常见改写：补 LIMIT、AND WHERE、换表名。
 *
 * @author 郑明亮
 * @since 2.1.0
 */
public final class SqlRewriter {
    private SqlRewriter() {
    }

    /**
     * 给 SELECT 补 LIMIT；已有则不改。
     *
     * @param statement 语句
     * @param rowCount 行数
     * @param dialect 方言
     * @return 原对象（就地修改）
     */
    public static SqlStatement addLimit(SqlStatement statement, long rowCount, SqlDialect dialect) {
        if (!(statement instanceof SqlSelect)) {
            return statement;
        }
        SqlSelect select = (SqlSelect) statement;
        if (select.union() != null) {
            addLimit(select.union(), rowCount, dialect);
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (d.supportsTop()) {
            if (select.top() == null) {
                select.setTop(SqlLiteral.of(SqlLiteral.Kind.NUMBER, Long.toString(rowCount)));
            }
            return statement;
        }
        // LIMIT 形态：MySQL/PG/H2/ANSI；Oracle 亦写 LIMIT（解析器可吃），FETCH 留给分页 API
        if (select.limit() == null) {
            SqlLimit limit = new SqlLimit();
            limit.setRowCount(SqlLiteral.of(SqlLiteral.Kind.NUMBER, Long.toString(rowCount)));
            select.setLimit(limit);
        }
        return statement;
    }

    /**
     * 把谓词 AND 到顶层 WHERE。
     *
     * @param statement 语句
     * @param predicate 谓词
     * @return 原对象
     */
    public static SqlStatement andWhere(SqlStatement statement, SqlExpr predicate) {
        if (predicate == null) {
            return statement;
        }
        if (statement instanceof SqlSelect) {
            SqlSelect select = (SqlSelect) statement;
            select.setWhere(and(select.where(), predicate));
        } else if (statement instanceof SqlUpdate) {
            SqlUpdate update = (SqlUpdate) statement;
            update.setWhere(and(update.where(), predicate));
        } else if (statement instanceof SqlDelete) {
            SqlDelete delete = (SqlDelete) statement;
            delete.setWhere(and(delete.where(), predicate));
        }
        return statement;
    }

    /**
     * 替换物理表名（忽略大小写）。
     *
     * @param statement 语句
     * @param from 原表简单名
     * @param to 新表简单名
     * @return 原对象
     */
    public static SqlStatement replaceTable(SqlStatement statement, final String from, final String to) {
        if (statement == null || from == null || to == null) {
            return statement;
        }
        statement.accept(new SqlVisitorAdapter() {
            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlTable) {
                    SqlIdentifier name = ((SqlTable) node).name();
                    if (name != null && from.equalsIgnoreCase(name.simpleName())) {
                        name.names().set(name.names().size() - 1, to);
                    }
                }
                return true;
            }
        });
        return statement;
    }

    /**
     * 替换列名（忽略大小写；多段名改最后一段）。不改表名与表别名（跳过 {@link SqlTable} 子树中的标识符）。
     *
     * @param statement 语句
     * @param from 原列简单名
     * @param to 新列简单名
     * @return 原对象
     */
    public static SqlStatement replaceColumn(SqlStatement statement, final String from, final String to) {
        if (statement == null || from == null || to == null) {
            return statement;
        }
        statement.accept(new SqlVisitorAdapter() {
            private int tableDepth;

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean visit(SqlNode node) {
                if (node instanceof SqlTable) {
                    tableDepth++;
                    return true;
                }
                if (tableDepth == 0 && node instanceof SqlIdentifier) {
                    SqlIdentifier id = (SqlIdentifier) node;
                    if (from.equalsIgnoreCase(id.simpleName()) && !id.names().isEmpty()) {
                        id.names().set(id.names().size() - 1, to);
                    }
                }
                return true;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void endVisit(SqlNode node) {
                if (node instanceof SqlTable && tableDepth > 0) {
                    tableDepth--;
                }
            }
        });
        return statement;
    }

    private static SqlExpr and(SqlExpr left, SqlExpr right) {
        if (left == null) {
            return right;
        }
        return SqlBinaryExpr.of(left, SqlBinaryOp.AND, right);
    }
}
