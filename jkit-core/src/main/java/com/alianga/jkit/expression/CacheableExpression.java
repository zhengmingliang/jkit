package com.alianga.jkit.expression;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.json.internal.beans.ArrayQueueMap;

/**
 * 支持缓存的表达式API(一般不推荐使用，仅仅与某些带缓存的库进行性能测试比较实用，实际生产没有必要缓存)
 *
 * @time 2024/10/22 20:34
 */
public final class CacheableExpression {
    // 缓存最多 256 个
    static final ArrayQueueMap<String, Expression> ARRAY_QUEUE_MAP = new ArrayQueueMap<String, Expression>(256);
    static int maxExprLength = 1 << 16;

    /**
     * 设置可参与缓存的表达式最大长度，超过该长度的表达式每次都重新解析且不入缓存。
     *
     * @param maxExprLength 表达式最大字符长度，默认为 {@code 1 << 16}
     */
    public static void setMaxExprLength(int maxExprLength) {
        CacheableExpression.maxExprLength = maxExprLength;
    }

    /***
     * 解析表达式并缓存（不推荐使用）
     *
     * @param expr 表达式字符串，解析前会去除首尾空白
     * @return 解析后的表达式对象；长度未超过上限时会复用或写入缓存，超过上限时返回新解析的实例
     */
    public static Expression parse(String expr) {
        expr = expr.trim();
        if (expr.length() > maxExprLength) {
            // not cache
            return new ExprParser(expr);
        }
        Expression expression = ARRAY_QUEUE_MAP.get(expr);
        if (expression == null) {
            synchronized (ARRAY_QUEUE_MAP) {
                expression = ARRAY_QUEUE_MAP.get(expr);
                if (expression == null) {
                    expression = new ExprParser(expr);
                    ARRAY_QUEUE_MAP.put(expr.trim(), expression);
                }
                return expression;
            }
        }
        return expression;
    }

    /**
     * 执行静态表达式
     *
     * @param expr 表达式字符串
     * @return 表达式在无执行环境下的计算结果
     */
    public static Object eval(String expr) {
        return parse(expr).evaluate();
    }

    /***
     * 执行表达式
     *
     * @param expr                表达式字符串
     * @param evaluateEnvironment 执行环境
     * @return 表达式在给定执行环境下的计算结果
     */
    public static Object eval(String expr, EvaluateEnvironment evaluateEnvironment) {
        return parse(expr).evaluate(evaluateEnvironment);
    }

    /***
     * 简化调用
     *
     * @param expr   表达式字符串，其中的变量按顺序对应可变参数
     * @param params 可变参数
     * @return 表达式的计算结果
     */
    public static Object evalParameters(String expr, Object... params) {
        return parse(expr).evaluateParameters(params);
    }

    /***
     * 简化调用
     *
     * @param <T>         目标结果类型
     * @param expr        表达式字符串，其中的变量按顺序对应可变参数
     * @param targetClass 目标结果类型的 Class 对象，计算结果将转换为该类型
     * @param params 可变参数
     * @return 转换为 {@code targetClass} 类型后的计算结果
     */
    public static <T> T evalParameters(String expr, Class<T> targetClass, Object... params) {
        return ObjectUtils.toType(parse(expr).evaluateParameters(params), targetClass);
    }

    /***
     * 执行表达式
     *
     * @param <T>                 目标结果类型
     * @param expr                表达式字符串
     * @param evaluateEnvironment 执行环境
     * @param targetClass         目标结果类型的 Class 对象，计算结果将转换为该类型
     * @return 转换为 {@code targetClass} 类型后的计算结果
     */
    public static <T> T evalResult(String expr, EvaluateEnvironment evaluateEnvironment, Class<T> targetClass) {
        return ObjectUtils.toType(parse(expr).evaluate(evaluateEnvironment), targetClass);
    }

    /**
     * 清空表达式缓存。
     */
    public static void clearCaches() {
        ARRAY_QUEUE_MAP.clear();
    }
}
