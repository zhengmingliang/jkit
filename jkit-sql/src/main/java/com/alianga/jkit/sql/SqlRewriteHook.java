package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

/**
 * 自定义改写规则（SPI）。入参为链上当前语句（可就地修改），返回值继续向后传递：
 * 返回原对象或替换成全新语句均可；返回 {@code null} 视为规则实现错误。
 *
 * <p>配合 {@link SqlRewrites} 组链、{@link SQL#rewrite(SqlStatement, SqlRewrites)} 执行。
 * 排在内建适配器（{@link SqlRewrites#addLimit} / {@link SqlRewrites#setPage} …）之前的规则即
 * "前 hook"，之后的即"后 hook"。规则应无状态（或仅持有不可变配置），
 * 同一 {@link SqlRewrites} 可能被并发复用。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
@FunctionalInterface
public interface SqlRewriteHook {
    /**
     * 改写当前语句。
     *
     * @param statement 当前语句（非 null）
     * @return 继续传递的语句（原对象或替换对象，不得为 null）
     */
    SqlStatement apply(SqlStatement statement);
}
