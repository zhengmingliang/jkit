package com.alianga.jkit.expression;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.expression.functions.BuiltInFunction;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 表达式模块
 * <p>
 * 1，常量表达式：
 * Expression.eval("1 + 2");
 * 输出：3
 * <p>
 * Expression.eval("1 + 2 - 3 * 4");
 * 输出： -9
 * <p>
 * Expression.eval("1 + (2 - 3) * 4");
 * 输出： -3
 * <p>
 * 2 变量用法
 * Fact fact = new Fact()；
 * fact.setSize(12);
 * Expression.eval("1 + size * 2", fact);
 * 输出： 25
 * <p>
 * Map map = new HashMap()
 * map.put("a", 1);
 * map.put("b", 2);
 * map.put("c", 3);
 * map.put("msg", "hello");
 * Expression.eval("a + b + c + d", fact);
 * 输出： 6hello
 * <p>
 * 3 函数用法
 * <p>
 * - 通过静态类注册：
 * EvaluateEnvironment evaluateEnvironment = EvaluateEnvironment.create(context);
 * evaluateEnvironment.registerStaticMethods(Math.class, String.class);
 *
 * <p> 调用函数使用@{类名大写}.{方法名称}， 类名大写可以看做为命名空间
 * Expression.eval("@Math.max(1,3)", evaluateEnvironment);
 * 输出：3
 * <p>
 * 也可以global模式注册函数，此时调用需要省略类名，直接@方法名称
 * evaluateEnvironment.registerStaticMethods(true, Math.class, String.class);
 * Expression.eval("@max(1,3)", evaluateEnvironment);
 * 输出：3
 * <p>
 * 注：如果以global模式注册函数，如果有同名函数会被覆盖，即只有一个会生效
 * <p>
 * - 自定义函数名称实现
 * <pre>{@code
 * evaluateEnvironment.registerFunction("MAX", new ExprFunction<Object, Number>() {
 * @Override
 * public Number call(Object... params) {
 * Arrays.sort(params);
 * return (Number) params[params.length - 1];
 * }
 * });
 *
 * 以上定义了一个名称为'MAX'的函数；
 * Expression.eval("@MAX(1,3)", evaluateEnvironment);
 *}
 * </pre>
 * 输出：3
 * <p>
 * 创建ExprFunction的子类，然后注册即可，自定义的函数名称统一以global模式生效
 *
 * @time 2021/9/25 22:13
 *
 * <p>常用api:</p>
 * @see Expression#parse(String)
 * @see Expression#eval(String)
 * @see Expression#eval(String, Object)
 * @see Expression#eval(String[], Object)
 * @see Expression#eval(String, EvaluateEnvironment)
 * @see Expression#evalResult(String, Class)
 * @see Expression#evalResult(String, Object, Class)
 * @see Expression#evalResult(String[], Object, Class)
 * @see Expression#evaluate(Object)
 * @see Expression#evaluate(EvaluateEnvironment)
 * @see Expression#evaluateResult(Class)
 * @see Expression#evaluateResult(Object, Class)
 * @see Expression#renderTemplate(String, Object)
 * @see Expression#renderTemplate(String, String, String, Object)
 * <p>
 * 内置函数：
 * @see BuiltInFunction#max(Object...)
 * @see BuiltInFunction#min(Object...)
 * @see BuiltInFunction#avg(Number...)
 * @see BuiltInFunction#sum(Number...)
 * @see BuiltInFunction#abs(Number)
 * @see BuiltInFunction#lower(String)
 * @see BuiltInFunction#upper(String)
 * @see BuiltInFunction#ifNull(Object, Object)
 * @see BuiltInFunction#size(Object)
 */
public abstract class Expression {
    /***
     * 解析表达式 - 字符串解析实现
     *
     * @param expr 表达式内容
     * @return 解析后的表达式对象（解释执行模式）
     */
    public static final Expression parse(String expr) {
        return new ExprParser(expr);
    }

    /***
     * 从指定offset开始提取合法的表达式
     *
     * @param expr 表达式内容
     * @param offset 开始提取的起始位置
     * @return 从指定位置提取出的表达式解析器
     */
    public static final ExprParser find(String expr, int offset) {
        return new ExprParser(expr, offset);
    }

    /**
     * 编译表达式（jkit 版不包含 javassist 编译模式，统一走 parse 解释模式）。
     *
     * @param expr 表达式内容
     * @return 解析后的表达式对象，等价于 {@link #parse(String)} 的结果
     */
    public static Expression compile(String expr) {
        return parse(expr);
    }

    /***
     * 执行表达式
     *
     * @param expr 表达式内容
     * @param evaluateEnvironment 执行环境
     * @return 表达式的执行结果
     */
    public static final Object eval(String expr, EvaluateEnvironment evaluateEnvironment) {
        return parse(expr).evaluate(evaluateEnvironment);
    }

    /***
     * 简化调用
     *
     * @param expr 表达式内容
     * @param params 可变参数
     * @return 以可变参数作为上下文执行表达式后的结果
     */
    public static final Object evalParameters(String expr, Object... params) {
        return parse(expr).evaluateParameters(params);
    }

    /***
     * 简化调用
     *
     * @param expr 表达式内容
     * @param targetClass 返回对象类型
     * @param params 可变参数
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public static final <T> T evalParameters(String expr, Class<T> targetClass, Object... params) {
        return ObjectUtils.toType(parse(expr).evaluateParameters(params), targetClass);
    }

    /**
     * 注：只有parse模式支持，compile模式暂时不支持
     *
     * @param params 按顺序作为表达式变量的可变参数
     * @return 表达式的执行结果，子类未实现时抛出 {@link UnsupportedOperationException}
     */
    public Object evaluateParameters(Object... params) {
        throw new UnsupportedOperationException();
    }

    /**
     * 注：只有parse模式支持，compile模式暂时不支持
     *
     * @param evaluateEnvironment 执行环境
     * @param params 按顺序作为表达式变量的可变参数
     * @return 表达式的执行结果，子类未实现时抛出 {@link UnsupportedOperationException}
     */
    public Object evaluateParameters(EvaluateEnvironment evaluateEnvironment, Object... params) {
        throw new UnsupportedOperationException();
    }

    /***
     * 执行表达式
     *
     * @param expr 表达式内容
     * @param evaluateEnvironment 执行环境
     * @param targetClass 返回对象类型
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public static final <T> T evalResult(String expr, EvaluateEnvironment evaluateEnvironment, Class<T> targetClass) {
        return ObjectUtils.toType(parse(expr).evaluate(evaluateEnvironment), targetClass);
    }

    /**
     * 执行静态表达式
     *
     * @param expr 不含变量的常量表达式内容
     * @return 表达式的执行结果
     */
    public static final Object eval(String expr) {
        return parse(expr).evaluate();
    }

    /**
     * 执行静态表达式
     *
     * @param expr 不含变量的常量表达式内容
     * @param targetClass 返回对象类型
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public static final <T> T evalResult(String expr, Class<T> targetClass) {
        return parse(expr).evaluateResult(targetClass);
    }

    /**
     * 执行带上下文表达式
     *
     * @param expr 表达式内容
     * @param context 实体对象或者map作为参数上下文
     * @return 表达式的执行结果
     */
    public static final Object eval(String expr, Object context) {
        return parse(expr).evaluate(context);
    }

    /**
     * 执行带上下文表达式
     *
     * @param expr 表达式内容
     * @param context 显式指定map作为参数上下文
     * @return 表达式的执行结果
     */
    public static final Object eval(String expr, Map context) {
        return parse(expr).evaluate(context);
    }

    /**
     * 执行带上下文表达式
     *
     * @param expr 表达式内容
     * @param context 实体对象或者map作为参数上下文
     * @param evaluateEnvironment 执行环境
     * @return 表达式的执行结果
     */
    public static final Object eval(String expr, Object context, EvaluateEnvironment evaluateEnvironment) {
        return parse(expr).evaluate(context, evaluateEnvironment);
    }

    /**
     * 批量执行带上下文表达式
     *
     * @param exprs 表达式数组
     * @param context 实体对象或者map作为参数上下文
     * @return 与表达式数组一一对应的执行结果数组；{@code exprs} 为 {@code null} 时返回 {@code null}
     */
    public static final Object[] eval(String[] exprs, Object context) {
        if (exprs == null) {
            return null;
        }
        Object[] objects = new Object[exprs.length];
        int i = 0;
        for (String expr : exprs) {
            objects[i++] = parse(expr).evaluate(context);
        }
        return objects;
    }

    /**
     * 批量执行带上下文表达式
     *
     * @param exprs 表达式数组
     * @param context 实体对象或者map作为参数上下文
     * @param targetClass 返回对象类型
     * @param <T> 返回值元素类型
     * @return 与表达式数组一一对应的结果列表；{@code exprs} 为 {@code null} 时返回 {@code null}
     */
    public static final <T> List<T> evalResult(String[] exprs, Object context, Class<T> targetClass) {
        if (exprs == null) {
            return null;
        }
        List<T> objects = new ArrayList<T>(exprs.length);
        int i = 0;
        for (String expr : exprs) {
            objects.add(parse(expr).evaluateResult(context, targetClass));
        }
        return objects;
    }

    /**
     * 执行带上下文表达式
     *
     * @param expr 表达式内容
     * @param context 实体对象或者map作为参数上下文
     * @param targetClass 返回对象类型
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public static final <T> T evalResult(String expr, Object context, Class<T> targetClass) {
        return parse(expr).evaluateResult(context, targetClass);
    }

    /**
     * 执行常量运算表达式
     *
     * @return 表达式的执行结果
     */
    public abstract Object evaluate();

    /**
     * 执行变量表达式
     *
     * @param context 显示指定map作为参数上下文
     * @return 表达式的执行结果
     */
    public abstract Object evaluate(Map context);

    /**
     * 执行变量表达式
     *
     * @param context 实体对象或者map
     * @return 表达式的执行结果
     */
    public abstract Object evaluate(Object context);

    /**
     * 执行变量表达式
     *
     * @param context 显示指定map作为参数上下文
     * @param timeout 超时时间单位毫秒（只有编译模式下生效）
     * @return 表达式的执行结果
     */
    public abstract Object evaluate(Map context, long timeout);

    /**
     * 执行变量表达式
     *
     * @param context 实体对象或者map
     * @param timeout 超时时间单位毫秒（只有编译模式下生效）
     * @return 表达式的执行结果
     * @see Object#wait(long)
     */
    public abstract Object evaluate(Object context, long timeout);

    /**
     * 执行变量表达式
     *
     * @param evaluateEnvironment 执行环境
     * @return 表达式的执行结果
     */
    public abstract Object evaluate(EvaluateEnvironment evaluateEnvironment);

    /**
     * 执行变量表达式
     *
     * @param context 参数上下文
     * @param evaluateEnvironment 执行环境
     * @return 表达式的执行结果
     */
    public abstract Object evaluate(Map context, EvaluateEnvironment evaluateEnvironment);

    /**
     * 执行变量表达式
     *
     * @param context 参数上下文
     * @param evaluateEnvironment 执行环境
     * @return 表达式的执行结果
     */
    public abstract Object evaluate(Object context, EvaluateEnvironment evaluateEnvironment);

    /**
     * 执行常量运算表达式
     *
     * @param targetClass 目标类型
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public final <T> T evaluateResult(Class<T> targetClass) {
        return ObjectUtils.toType(evaluate(), targetClass);
    }

    /**
     * 执行变量运算表达式
     *
     * @param context 实体对象或者map作为参数上下文
     * @param targetClass 目标类型
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public final <T> T evaluateResult(Object context, Class<T> targetClass) {
        return ObjectUtils.toType(evaluate(context), targetClass);
    }

    /**
     * 执行变量运算表达式
     *
     * @param context 显式指定map作为参数上下文
     * @param targetClass 目标类型
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public final <T> T evaluateResult(Map context, Class<T> targetClass) {
        return ObjectUtils.toType(evaluate(context), targetClass);
    }

    /**
     * 执行变量运算表达式
     *
     * @param context 实体对象或者map作为参数上下文
     * @param targetClass 目标类型
     * @param <T> 返回值类型
     * @param timeout 超时时间单位毫秒（只有编译模式下生效）
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public final <T> T evaluateResult(Object context, Class<T> targetClass, long timeout) {
        return ObjectUtils.toType(evaluate(context, timeout), targetClass);
    }

    /**
     * 执行变量运算表达式
     *
     * @param context 显式指定map作为参数上下文
     * @param targetClass 目标类型
     * @param <T> 返回值类型
     * @param timeout 超时时间单位毫秒（只有编译模式下生效）
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public final <T> T evaluateResult(Map context, Class<T> targetClass, long timeout) {
        return ObjectUtils.toType(evaluate(context, timeout), targetClass);
    }

    /**
     * 执行可变参数运算表达式
     *
     * @param targetClass 目标类型
     * @param parameters 按顺序作为表达式变量的可变参数
     * @param <T> 返回值类型
     * @return 执行结果转换为 {@code targetClass} 后的对象
     */
    public final <T> T evaluateParametersResult(Class<T> targetClass, Object... parameters) {
        return ObjectUtils.toType(evaluateParameters(parameters), targetClass);
    }

    /**
     * 提供静态方法直接渲染模板（no编译）
     *
     * @param template 模板内容，使用 {@code ${}} 包裹表达式
     * @param context 实体对象或者map作为参数上下文
     * @return 渲染后的字符串
     */
    public static final String renderTemplate(String template, Object context) {
        return renderTemplate(template, "${", "}", context);
    }

    /**
     * 提供静态方法直接渲染模板（no编译）
     * 不支持嵌套（表达式通过括号可以代替嵌套）
     *
     * @param template 模板内容
     * @param prefix 模板前缀
     * @param suffix 模板后缀
     * @param context 上下文
     * @return 渲染后的字符串；模板或前后缀为 {@code null} 时返回 {@code null}，前后缀为空串时原样返回模板
     */
    public static final String renderTemplate(String template, String prefix, String suffix, Object context) {
        if (template == null || prefix == null || suffix == null) {
            return null;
        }

        int prefixLen = prefix.length();
        int suffixLen = suffix.length();
        if (prefixLen == 0 || suffixLen == 0) {
            return template;
        }

        StringBuilder builder = new StringBuilder();
        char[] buffers = getChars(template);
        int length = buffers.length;
        int fromIndex = 0;

        // 先找模板后缀，再往前找模板前缀
        // 后缀位置
        int suffixIndex = template.indexOf(suffix);

        // 前缀
        int prefixIndex = -1;
        while (suffixIndex > 0) {
            prefixIndex = template.lastIndexOf(prefix, suffixIndex - 1);
            if (prefixIndex > fromIndex - 1) {
                builder.append(buffers, fromIndex, prefixIndex - fromIndex);
                ExprParser exprParser =
                        new ExprParser(buffers, prefixIndex + prefixLen, suffixIndex - prefixIndex - prefixLen);
                builder.append(exprParser.evaluate(context));

                // continue find next suffix
                fromIndex = suffixIndex + suffixLen;
                suffixIndex = template.indexOf(suffix, fromIndex);
            } else {
                // keep last fromIndex and update suffixIndex
                suffixIndex = template.indexOf(suffix, suffixIndex + suffixLen);
            }
        }

        if (fromIndex < length) {
            builder.append(buffers, fromIndex, length - fromIndex);
        }

        return builder.toString();
    }

    // get chars
    /**
     * 获取字符串内部的字符数组
     *
     * @param value 源字符串
     * @return 字符串对应的字符数组（可能直接引用字符串内部数组，不应修改）
     */
    protected static final char[] getChars(String value) {
        return UnsafeHelper.getChars(value);
    }

    /**
     * 获取表达式中出现的全部变量名称
     *
     * @return 变量名称列表，子类未实现时抛出 {@link UnsupportedOperationException}
     */
    public List<String> getVariables() {
        throw new UnsupportedOperationException();
    }

    /**
     * 获取表达式中出现的根级变量名称
     *
     * @return 根级变量名称列表，子类未实现时抛出 {@link UnsupportedOperationException}
     */
    public List<String> getRootVariables() {
        throw new UnsupportedOperationException();
    }

    /**
     * 获取表达式的源字符串
     *
     * @return 表达式源字符串，子类未实现时抛出 {@link UnsupportedOperationException}
     */
    public String getSource() {
        throw new UnsupportedOperationException();
    }
}
