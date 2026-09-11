package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialectSpec;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.ast.SqlFunctionExpr;
import com.alianga.jkit.sql.schema.convert.ConversionReport;

/**
 * 函数改写：直接构造目标 AST，禁止运行时字符串拼接再解析。
 *
 * <p>参数已在 {@code fn.arguments()} 上递归改写完毕。返回 {@code null} 表示不处理，
 * walker 回落到 {@link SqlFunctionRegistry#findBuiltin}（没有内置则保留原节点）。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface FunctionRewriteRule {
    /**
     * @param fn 函数节点（参数已改写）
     * @param source 源方言
     * @param target 目标方言
     * @param report 报告
     * @return 新节点；null 表示不处理
     */
    SqlExpr rewrite(SqlFunctionExpr fn, SqlDialectSpec source, SqlDialectSpec target,
                    ConversionReport.Builder report);
}
