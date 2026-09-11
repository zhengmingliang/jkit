package com.alianga.jkit.sql.schema.rewrite;

import com.alianga.jkit.sql.SqlDialect;
import com.alianga.jkit.sql.ast.SqlExpr;
import com.alianga.jkit.sql.schema.convert.ConversionReport;

import java.util.List;

/**
 * 函数改写：直接构造目标 AST，禁止运行时字符串拼接再解析。
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public interface FunctionRewriteRule {
    /**
     * @param args 已递归改写过的参数
     * @param target 目标方言
     * @param report 报告
     * @return 新节点；null 表示保持原函数调用
     */
    SqlExpr rewrite(List<SqlExpr> args, SqlDialect target, ConversionReport.Builder report);
}
