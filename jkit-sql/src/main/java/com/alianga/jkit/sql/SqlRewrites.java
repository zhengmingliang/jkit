package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 改写规则链：按 {@link #add} 顺序依次执行 {@link SqlRewriteHook}。
 * 自定义规则排在内建适配器之前即"前 hook"、之后即"后 hook"；
 * 经 {@link SQL#rewrite(SqlStatement, SqlRewrites)} 执行时先深拷贝，原 AST 不受影响。
 *
 * <pre>{@code
 * SqlStatement out = SQL.rewrite(stmt, SqlRewrites.create()
 *         .add(new TenantRule())                        // 前 hook：自定义规则
 *         .add(SqlRewrites.replaceTable("t", "t_2026")) // 内建：换表
 *         .add(SqlRewrites.addLimit(100, SqlDialect.MYSQL)) // 内建：补 LIMIT
 *         .add(SqlRewrites.andWhere(SQL.parseExpr("id > ?")))); // 内建：AND WHERE
 * }</pre>
 *
 * <p>内建适配器等价于直接调用 {@link SqlRewriter} 的对应静态方法（就地作用于链上语句），
 * 需要门面级"先拷贝再改"语义时用 {@link SQL} 的对应方法即可，不必进链。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlRewrites {
    private final List<SqlRewriteHook> hooks = new ArrayList<SqlRewriteHook>(4);

    private SqlRewrites() {
    }

    /**
     * @return 空链（可继续 {@link #add}）
     */
    public static SqlRewrites create() {
        return new SqlRewrites();
    }

    /**
     * @return 空链（同 {@link #create()}）
     */
    public static SqlRewrites none() {
        return new SqlRewrites();
    }

    /**
     * 追加规则到链尾（返回 this 可链式）。
     *
     * @param hook 规则（null 抛 {@link IllegalArgumentException}）
     * @return this
     */
    public SqlRewrites add(SqlRewriteHook hook) {
        if (hook == null) {
            throw new IllegalArgumentException("rewrite hook required");
        }
        hooks.add(hook);
        return this;
    }

    /**
     * @return 规则只读视图（按添加顺序）
     */
    public List<SqlRewriteHook> hooks() {
        return Collections.unmodifiableList(hooks);
    }

    /**
     * 内建适配器：给 SELECT 补 LIMIT（已有则不动），等价 {@link SqlRewriter#addLimit}。
     *
     * @param rowCount 行数
     * @param dialect 方言（null 视为 MYSQL）
     * @return 规则
     */
    public static SqlRewriteHook addLimit(final long rowCount, final SqlDialectSpec dialect) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.addLimit(statement, rowCount, dialect);
            }
        };
    }

    /**
     * 内建适配器：设置/替换行数上限，等价 {@link SqlRewriter#setLimit}。
     *
     * @param rowCount 行数（负值清空 LIMIT/TOP）
     * @param dialect 方言（null 视为 MYSQL）
     * @return 规则
     */
    public static SqlRewriteHook setLimit(final long rowCount, final SqlDialectSpec dialect) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.setLimit(statement, rowCount, dialect);
            }
        };
    }

    /**
     * 内建适配器：设置/替换 OFFSET，等价 {@link SqlRewriter#setOffset}。
     *
     * @param offset 偏移（负值视为 0）
     * @param dialect 方言（null 视为 MYSQL）
     * @return 规则
     */
    public static SqlRewriteHook setOffset(final long offset, final SqlDialectSpec dialect) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.setOffset(statement, offset, dialect);
            }
        };
    }

    /**
     * 内建适配器：按页码改写分页，等价 {@link SqlRewriter#setPage}。
     *
     * @param pageNo 页码（从 1 起）
     * @param pageSize 页大小
     * @param dialect 方言（null 视为 MYSQL）
     * @return 规则
     */
    public static SqlRewriteHook setPage(final long pageNo, final long pageSize,
            final SqlDialectSpec dialect) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.setPage(statement, pageNo, pageSize, dialect);
            }
        };
    }

    /**
     * 内建适配器：把谓词 AND 到顶层 WHERE，等价 {@link SqlRewriter#andWhere}。
     *
     * @param predicate 谓词（如 {@code SQL.parseExpr("tenant_id = ?")}）
     * @return 规则
     */
    public static SqlRewriteHook andWhere(final SqlExpr predicate) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.andWhere(statement, predicate);
            }
        };
    }

    /**
     * 内建适配器：替换物理表名（忽略大小写），等价 {@link SqlRewriter#replaceTable}。
     *
     * @param from 原表简单名
     * @param to 新表简单名
     * @return 规则
     */
    public static SqlRewriteHook replaceTable(final String from, final String to) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.replaceTable(statement, from, to);
            }
        };
    }

    /**
     * 内建适配器：替换列名（忽略大小写，跳过表名子树），等价 {@link SqlRewriter#replaceColumn}。
     *
     * @param from 原列简单名
     * @param to 新列简单名
     * @return 规则
     */
    public static SqlRewriteHook replaceColumn(final String from, final String to) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.replaceColumn(statement, from, to);
            }
        };
    }

    /**
     * 内建适配器：追加 SELECT 列，等价 {@link SqlRewriter#addSelectItem}。
     *
     * @param exprSql 表达式 SQL
     * @return 规则
     * @since 2.0.1
     */
    public static SqlRewriteHook addSelectItem(final String exprSql) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.addSelectItem(statement, SQL.parseExpr(exprSql), null);
            }
        };
    }

    /**
     * 内建适配器：追加 SELECT 列（带别名）。
     *
     * @param expr 表达式
     * @param alias 别名，可空
     * @return 规则
     * @since 2.0.1
     */
    public static SqlRewriteHook addSelectItem(final SqlExpr expr, final String alias) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.addSelectItem(statement, expr, alias);
            }
        };
    }

    /**
     * 内建适配器：按简单列名移除 SELECT 项，等价 {@link SqlRewriter#removeSelectItem}。
     *
     * @param columnSimpleName 列简单名
     * @return 规则
     * @since 2.0.1
     */
    public static SqlRewriteHook removeSelectItem(final String columnSimpleName) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.removeSelectItem(statement, columnSimpleName);
            }
        };
    }

    /**
     * 内建适配器：按目标方言适配分页，等价 {@link SqlRewriter#adaptPagination}。
     *
     * @param dialect 目标方言
     * @return 规则
     * @since 2.0.1
     */
    public static SqlRewriteHook adaptPagination(final SqlDialectSpec dialect) {
        return new SqlRewriteHook() {
            /**
             * {@inheritDoc}
             */
            @Override
            public SqlStatement apply(SqlStatement statement) {
                return SqlRewriter.adaptPagination(statement, dialect);
            }
        };
    }
}
