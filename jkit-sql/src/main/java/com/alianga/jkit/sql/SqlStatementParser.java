package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlStatement;

/**
 * 自定义语句解析器（SPI）。通过 {@link SqlParseOptions#statementParsers(SqlStatementParsers)}
 * 按前导关键字注册后，以内建分派未覆盖的关键字开头的语句将交给本解析器处理——
 * 内建语句（SELECT / INSERT / CREATE 等）不受影响，注册表只是 default 分支的兜底。
 *
 * <pre>{@code
 * SqlParseOptions options = SqlParseOptions.defaults()
 *         .statementParsers(SqlStatementParsers.create()
 *                 .add("BACKUP", ctx -> {
 *                     ctx.next(); // 消费 BACKUP 关键字
 *                     String rest = ctx.consumeRest();
 *                     SqlSimpleStatement stmt = new SqlSimpleStatement();
 *                     stmt.setText("BACKUP" + (rest.isEmpty() ? "" : " " + rest));
 *                     return stmt;
 *                 }));
 * SqlStatement stmt = SQL.parse("BACKUP DATABASE shop TO DISK='/tmp/shop.bak'",
 *         SqlDialect.MYSQL, options);
 * }</pre>
 *
 * 实现约定：
 * <ul>
 *   <li>进入 {@link #parse} 时当前记号即注册关键字本身；实现负责把本条语句消费到
 *       {@link SqlParseContext#atStmtBreak()}（语句终止符留给框架处理）。</li>
 *   <li>返回 {@code null} 视为解析失败，等价于未注册时抛出的不支持错误。</li>
 *   <li>抛出的 {@link SqlParseException} 会按容错模式（{@code parseAll(sql, dialect, options, true)}）
 *       转为失败占位并继续后续语句。</li>
 * </ul>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface SqlStatementParser {
    /**
     * 解析一条语句。
     *
     * @param context 解析上下文（游标与吞尾操作）
     * @return 语句；{@code null} 视为解析失败
     */
    SqlStatement parse(SqlParseContext context);
}
