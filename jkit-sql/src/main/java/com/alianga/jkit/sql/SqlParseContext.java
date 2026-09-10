package com.alianga.jkit.sql;

import com.alianga.jkit.sql.ast.SqlIdentifier;

/**
 * 自定义语句解析上下文：{@link SqlStatementParser} 从这里拿到解析内建语句之外
 * 必需的游标操作——读当前记号 / 前进 / 类型与关键字匹配 / 解析标识符 / 原样吞尾 / 构造异常；
 * 不暴露解析器内部全量状态。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlParseContext {
    private final SqlParser parser;

    SqlParseContext(SqlParser parser) {
        this.parser = parser;
    }

    /**
     * @return 当前记号（未消费）
     */
    public SqlToken token() {
        return parser.token;
    }

    /**
     * @return 当前方言
     */
    public SqlDialectSpec dialect() {
        return parser.dialect;
    }

    /**
     * @param type 记号类型
     * @return 当前记号是否为该类型
     */
    public boolean is(SqlTokenType type) {
        return parser.is(type);
    }

    /**
     * @param word 单词
     * @return 当前记号是否为不区分大小写的同名标识符
     */
    public boolean isIdent(String word) {
        return parser.isIdent(word);
    }

    /**
     * 类型匹配则消费。
     *
     * @param type 记号类型
     * @return 是否命中并消费
     */
    public boolean match(SqlTokenType type) {
        return parser.match(type);
    }

    /**
     * 标识符匹配（不区分大小写）则消费。
     *
     * @param word 单词
     * @return 是否命中并消费
     */
    public boolean matchIdent(String word) {
        if (!parser.isIdent(word)) {
            return false;
        }
        parser.next();
        return true;
    }

    /**
     * 前进到下一个记号（保留注释模式下普通注释会自动跳过）。
     */
    public void next() {
        parser.next();
    }

    /**
     * @return 是否到达本条语句终止处（语句终止符 / GO / EOF）
     */
    public boolean atStmtBreak() {
        return parser.atStmtBreak();
    }

    /**
     * 解析一个标识符（支持反引号 / 双引号 / 方括号引用），并消费之。
     *
     * @return 标识符
     */
    public SqlIdentifier name() {
        return parser.parseName();
    }

    /**
     * 把本条语句剩余部分消费到终止符为止（终止符留给框架），返回原文切片——
     * 保留原始间距与大小写，不做 token 归一化。
     *
     * @return 剩余原文（可能为空串，不为 null）
     */
    public String consumeRest() {
        String rest = parser.consumeRawSliceUntilStmtBreak();
        return rest == null ? "" : rest;
    }

    /**
     * 构造带行列与原文片段的解析异常。
     *
     * @param message 错误信息
     * @return 异常（抛出由实现决定）
     */
    public SqlParseException error(String message) {
        return parser.error(message);
    }
}
