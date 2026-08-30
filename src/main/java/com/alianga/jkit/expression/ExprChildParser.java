package com.alianga.jkit.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 子表达式（方法参数表达式和函数参数表达式，非括号）
 * 变量解析注册到全局解析器（global）中
 * 子表达式执行时入口使用doEvaluate跳过参数初始化；
 * 编译模式下解决预处理变量名称错误问题；
 *
 * @time 2022/11/13 16:03
 */
class ExprChildParser extends ExprParser {
    private final ExprParser global;

    public ExprChildParser(String expr, ExprParser global) {
        this.init(expr);
        this.global = global;
        this.parse();
    }

    @Override
    protected ExprParser global() {
        return global;
    }

    // 注意：不使用 super，非父子关系
    @Override
    protected Map<String, ElVariableInvoker> getInvokes() {
        return global.getInvokes();
    }

    // 注意：不使用 super，非父子关系
    @Override
    protected Map<String, ElVariableInvoker> getTailInvokes() {
        return global.getTailInvokes();
    }

    // 注意：不使用 super，非父子关系
    @Override
    void checkInitializedInvokes() {
        global.checkInitializedInvokes();
    }

    // Override the 默认 behavior and do nothing
    @Override
    protected void compressVariables() {
    }

    @Override
    protected List<String> getLocalVariableKeys() {
        return new ArrayList<String>();
    }
}
