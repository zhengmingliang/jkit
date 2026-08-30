package com.alianga.jkit.expression;

import java.util.Map;

/**
 * @time 2022/10/30 11:24
 */
public interface ElInvoker {
    /** 表达式取值使用的安全访问控制器，用于校验类与成员是否允许被访问 */
    ElSecureTrustedAccess SECURE_TRUSTED_ACCESS = new ElSecureTrustedAccess();

    /**
     * 直接invoke不缓存
     *
     * @param context 变量取值的根上下文对象
     * @return 沿变量链逐级取值后的结果
     */
    Object invokeDirect(Object context);

    /**
     * 直接invoke不缓存
     *
     * @param context 变量取值的根上下文 Map
     * @return 沿变量链逐级取值后的结果
     */
    Object invokeDirect(Map context);

    /**
     * 沿变量链逐级取值，并把每级结果缓存到 {@code variableValues} 中，避免同一次求值内重复取值。
     *
     * @param entityContext  变量取值的根上下文对象
     * @param variableValues 变量值缓存数组，按变量下标存放各级取值结果
     * @return 当前变量的值
     */
    Object invoke(Object entityContext, Object[] variableValues);

    /**
     * 沿变量链逐级取值，并把每级结果缓存到 {@code variableValues} 中，避免同一次求值内重复取值。
     *
     * @param mapContext     变量取值的根上下文 Map
     * @param variableValues 变量值缓存数组，按变量下标存放各级取值结果
     * @return 当前变量的值
     */
    Object invoke(Map mapContext, Object[] variableValues);

    /**
     * 仅基于给定的父级上下文取当前层级的值，不再向上递归调用父级调用器，结果写入变量值缓存数组。
     *
     * @param globalContext  全局上下文 Map
     * @param parentContext  当前层级直接取值的父级上下文对象
     * @param variableValues 变量值缓存数组，按变量下标存放各级取值结果
     * @return 当前层级变量的值
     */
    Object invokeCurrent(Map globalContext, Object parentContext, Object[] variableValues);

    /**
     * 仅基于给定的父级上下文取当前层级的值，不再向上递归调用父级调用器，结果写入变量值缓存数组。
     *
     * @param globalContext  全局上下文对象
     * @param parentContext  当前层级直接取值的父级上下文对象
     * @param variableValues 变量值缓存数组，按变量下标存放各级取值结果
     * @return 当前层级变量的值
     */
    Object invokeCurrent(Object globalContext, Object parentContext, Object[] variableValues);

    /**
     * 将变量名放入字符串常量池（{@link String#intern()}），使后续取值可用引用比较加速匹配。
     */
    void internKey();

}
