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
 * 常见改写：补/读/改分页（LIMIT/TOP/FETCH）、AND WHERE、换表名。
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
     * 读取当前行数上限：优先 {@link SqlSelect#limit()} 的 rowCount，否则 {@link SqlSelect#top()}。
     * 非数字字面量时返回 {@code null}。
     *
     * @param statement 语句
     * @return 行数，无则 null
     */
    public static Long getLimit(SqlStatement statement) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return null;
        }
        if (select.limit() != null && select.limit().rowCount() != null) {
            return asLong(select.limit().rowCount());
        }
        if (select.top() != null) {
            return asLong(select.top());
        }
        return null;
    }

    /**
     * 读取当前 OFFSET（仅 {@link SqlLimit#offset()}）。
     *
     * @param statement 语句
     * @return 偏移，无则 null
     */
    public static Long getOffset(SqlStatement statement) {
        SqlSelect select = asSelect(statement);
        if (select == null || select.limit() == null) {
            return null;
        }
        return asLong(select.limit().offset());
    }

    /**
     * 设置/替换行数上限（就地）。按方言写 TOP 或 LIMIT/FETCH；保留已有 OFFSET。
     *
     * @param statement 语句
     * @param rowCount 行数（负值清空分页）
     * @param dialect 方言
     * @return 原对象
     */
    public static SqlStatement setLimit(SqlStatement statement, long rowCount, SqlDialect dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        if (rowCount < 0) {
            clearPagination(select);
            return statement;
        }
        Long offset = getOffset(statement);
        applyPagination(select, offset == null ? 0L : offset, rowCount, d, offset != null);
        return statement;
    }

    /**
     * 设置/替换 OFFSET（就地）。无 LIMIT/TOP 时不同时发明行数。
     *
     * @param statement 语句
     * @param offset 偏移（负值视为 0）
     * @param dialect 方言
     * @return 原对象
     */
    public static SqlStatement setOffset(SqlStatement statement, long offset, SqlDialect dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        long off = offset < 0 ? 0L : offset;
        Long limit = getLimit(statement);
        if (limit == null) {
            // 仅 offset：写入 limit 节点的 offset，rowCount 留空（format 仍可出 OFFSET）
            ensureLimitNode(select, d, true);
            select.limit().setOffset(number(off));
            if (d.supportsTop()) {
                select.setTop(null);
                select.limit().setFetchStyle(true);
            } else if (d.supportsFetchFirst() && !d.supportsLimitOffset()) {
                select.limit().setFetchStyle(true);
            }
            return statement;
        }
        applyPagination(select, off, limit.longValue(), d, true);
        return statement;
    }

    /**
     * 按页码改写分页（就地）。{@code pageNo} 从 1 起；{@code offset=(pageNo-1)*pageSize}。
     *
     * @param statement 语句
     * @param pageNo 页码（&lt;1 视为 1）
     * @param pageSize 页大小（&lt;1 视为 1）
     * @param dialect 方言
     * @return 原对象
     */
    public static SqlStatement setPage(SqlStatement statement, long pageNo, long pageSize,
            SqlDialect dialect) {
        SqlSelect select = asSelect(statement);
        if (select == null) {
            return statement;
        }
        SqlDialect d = dialect == null ? SqlDialect.MYSQL : dialect;
        long pn = pageNo < 1 ? 1L : pageNo;
        long ps = pageSize < 1 ? 1L : pageSize;
        long offset = (pn - 1L) * ps;
        applyPagination(select, offset, ps, d, true);
        return statement;
    }

    private static void applyPagination(SqlSelect select, long offset, long rowCount,
            SqlDialect dialect, boolean withOffset) {
        clearPagination(select);
        // SQL Server 无偏移（或 offset=0）用 TOP
        if (dialect.supportsTop() && offset == 0L) {
            select.setTop(number(rowCount));
            return;
        }
        SqlLimit limit = new SqlLimit();
        limit.setRowCount(number(rowCount));
        if (offset > 0L) {
            limit.setOffset(number(offset));
        }
        if (dialect.supportsTop()
                || (dialect.supportsFetchFirst() && !dialect.supportsLimitOffset())) {
            // SQL Server（有偏移）或 Oracle：OFFSET/FETCH
            limit.setFetchStyle(true);
        } else if (dialect.supportsLimitOffset() && offset > 0L && dialect == SqlDialect.MYSQL) {
            limit.setMysqlCommaStyle(true);
        }
        select.setLimit(limit);
    }

    private static void ensureLimitNode(SqlSelect select, SqlDialect dialect, boolean fetchIfNeeded) {
        if (select.limit() == null) {
            SqlLimit limit = new SqlLimit();
            if (fetchIfNeeded && (dialect.supportsTop()
                    || (dialect.supportsFetchFirst() && !dialect.supportsLimitOffset()))) {
                limit.setFetchStyle(true);
            }
            select.setLimit(limit);
        }
    }

    private static void clearPagination(SqlSelect select) {
        select.setTop(null);
        select.setLimit(null);
    }

    private static SqlSelect asSelect(SqlStatement statement) {
        return statement instanceof SqlSelect ? (SqlSelect) statement : null;
    }

    private static SqlLiteral number(long value) {
        return SqlLiteral.of(SqlLiteral.Kind.NUMBER, Long.toString(value));
    }

    private static Long asLong(SqlExpr expr) {
        if (!(expr instanceof SqlLiteral)) {
            return null;
        }
        SqlLiteral lit = (SqlLiteral) expr;
        if (lit.kind() != SqlLiteral.Kind.NUMBER || lit.value() == null) {
            return null;
        }
        try {
            return Long.valueOf(lit.value());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 把谓词 AND 到顶层 WHERE（就地修改；公开门面 {@link SQL#andWhere} 会先 clone）。
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
     * 替换物理表名（忽略大小写；就地修改；公开门面 {@link SQL#replaceTable} 会先 clone）。
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
     * 替换列名（忽略大小写；多段名改最后一段；就地修改；公开门面 {@link SQL#replaceColumn} 会先 clone）。
     * 不改表名与表别名（跳过 {@link SqlTable} 子树中的标识符）。
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
