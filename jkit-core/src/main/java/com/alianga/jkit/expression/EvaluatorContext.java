package com.alianga.jkit.expression;

import java.util.Map;

/**
 * eval context
 *
 * @time 2022/11/4 16:21
 */
public class EvaluatorContext {
    /** 不含任何变量值的空上下文，可用于无变量表达式的求值 */
    public static final EvaluatorContext EMPTY = new EvaluatorContext();
    Object[] variableValues;
    // use for ExprEvaluatorStackSplitImpl & ExprEvaluatorContextValueHolderImpl
    Object value;

    EvaluatorContext() {
    }

    /**
     * 使用按变量下标排列的变量值数组构建上下文。
     *
     * @param variableValues 变量值数组，下标与变量执行器的 index 一致
     */
    public EvaluatorContext(Object[] variableValues) {
        this.variableValues = variableValues;
    }

    /**
     * 读取指定变量执行器在当前上下文中的值。
     *
     * @param variableInvoker 变量执行器，使用其 index 作为变量值数组下标
     * @return 该变量对应的值
     * @throws ExpressionException 下标越界或变量值数组为 {@code null}，即变量无法从上下文解析时抛出
     */
    public Object getContextValue(ElVariableInvoker variableInvoker) {
        try {
            return variableValues[variableInvoker.index];
        } catch (Throwable throwable) {
            throw new ExpressionException("unresolved property or variable: '" + variableInvoker + "' from context");
        }
    }

    static class TwinsImpl extends EvaluatorContext {
        final ElVariableInvoker one;
        Object oneValue;
        Object otherValue;

        TwinsImpl(final ElVariableInvoker one, Object oneValue, Object otherValue) {
            this.one = one;
            this.oneValue = oneValue;
            this.otherValue = otherValue;
        }

        @Override
        public final Object getContextValue(ElVariableInvoker variableInvoker) {
            return variableInvoker == one ? oneValue : otherValue;
        }
    }

    static final class ParametersImpl extends EvaluatorContext {
        public ParametersImpl(Object[] params) {
            this.variableValues = params;
        }

        @Override
        public Object getContextValue(ElVariableInvoker variableInvoker) {
            try {
                return variableValues[variableInvoker.tailIndex];
            } catch (Throwable throwable) {
                throw new ExpressionException(
                        "unresolved property or variable: '" + variableInvoker + "' from parameters[" +
                                variableInvoker.tailIndex + "]");
            }
        }
    }

//
    static final class MapRootImpl extends EvaluatorContext {
        private final Map context;

        MapRootImpl(Map context) {
            this.context = context;
        }

        public Object getContextValue(ElVariableInvoker variableInvoker) {
            return context.get(variableInvoker.key);
        }
    }

    static final class ObjectRootImpl extends EvaluatorContext {
        private final Object context;

        ObjectRootImpl(Object context) {
            this.context = context;
        }

        public Object getContextValue(ElVariableInvoker variableInvoker) {
            return variableInvoker.invokeValue(context);
        }
    }

    static final class SingleVariableImpl extends EvaluatorContext {
        SingleVariableImpl(Object variableValue) {
            this.variableValue = variableValue;
        }

        final Object variableValue;

        @Override
        public Object getContextValue(ElVariableInvoker variableInvoker) {
            return variableValue;
        }
    }
}
