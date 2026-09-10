package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

/**
 * {@link SqlWall} 自定义检查规则（SPI）。
 *
 * <p>内置检查（多语句 / 注释绕过在原文层，selectOnly / denyDdl / 无 WHERE / INTO OUTFILE /
 * UNION / AST 扫描在语句层）跑完之后，按 {@link SqlWallConfig#rules(SqlWallRule...)}
 * 的注册顺序追加执行自定义规则。规则只读 AST 与配置，把违规码写进收集器：</p>
 *
 * <pre>{@code
 * SqlWallConfig cfg = SqlWallConfig.defaults().rules((stmt, config, violations) -> {
 *     if (stmt.type() == SqlStatementType.SELECT && stmt.tables().contains("secret")) {
 *         violations.add("secret-table");
 *     }
 * });
 * SqlWallResult r = SQL.wall(sql, SqlDialect.MYSQL, cfg);
 * }</pre>
 *
 * <p>规则实现应无状态（或仅持有不可变配置），同一 {@code SqlWallConfig} 可能被并发复用。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface SqlWallRule {
    /**
     * 检查单条语句（多语句时每条各调一次；注释绕过等原文层检查不在本 SPI 范围）。
     *
     * @param statement 语句（非 null）
     * @param config 规则配置（内置开关可读）
     * @param violations 违规码收集器（{@code add} 自动去重）
     */
    void check(SqlStatement statement, SqlWallConfig config, SqlWallViolations violations);
}
