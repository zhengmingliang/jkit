package com.alianga.jkit.expression;

/**
 * 提供给使用者注册在表达式中可以通过@调用
 *
 * @time 2021/11/20 14:07
 */
public interface ExprFunction<I, O> {
    /***
     * 函数接口
     *
     * @param params 调用函数时传入的参数数组
     * @return 函数的执行结果
     */
    public O call(I... params);

}
