package com.alianga.jkit.expression;

import com.alianga.jkit.ReflectionUtils;
import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.reflect.ReflectConsts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 常量、变量、操作执行器
 *
 * @time 2022/10/20 12:37
 */
public class ExprEvaluator {
    /**
     * 执行类型：二元运算
     */
    protected static final int EVAL_TYPE_OPERATOR = 1;
    /**
     * 执行类型：变量取值
     */
    protected static final int EVAL_TYPE_VARIABLE = 2;
    /**
     * 执行类型：函数调用
     */
    protected static final int EVAL_TYPE_FUN = 3;
    /**
     * 执行类型：三元条件运算
     */
    protected static final int EVAL_TYPE_QUESTION = 4;
    /**
     * 执行类型：括号包裹的子表达式
     */
    protected static final int EVAL_TYPE_BRACKET = 5;

    /**
     * 当前执行器的执行类型，取值见 EVAL_TYPE_* 常量；为 0 时直接透传左子节点的执行结果
     */
    protected int evalType;
    /**
     * 当前节点的运算符，默认为原子节点（不做运算）
     */
    protected ElOperator operator = ElOperator.ATOM;

    /**
     * 左子表达式执行器
     */
    protected ExprEvaluator left;
    /**
     * 右子表达式执行器
     */
    protected ExprEvaluator right;

    /**
     * 是否对执行结果取负
     */
    protected boolean negate;
    /**
     * 是否对执行结果做逻辑非运算
     */
    protected boolean logicalNot;
    boolean constant;
    Object result;
    static final int OPTIMIZE_DEPTH_VALUE = 1 << 10;
    static final Object[] EMPTY_ARGS = new Object[0];

    // use by code()
    /**
     * 判断当前表达式是否为常量表达式（左右子树全部为常量）。
     *
     * @return 整棵子树都是常量时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isConstantExpr() {
        if (left == null && right == null) {
            return this.constant;
        }
        if (left == null) {
            return right.isConstantExpr();
        }
        if (right == null) {
            return left.isConstantExpr();
        }
        return left.isConstantExpr() && right.isConstantExpr();
    }

    /**
     * 获取左子表达式执行器。
     *
     * @return 当前的左子表达式执行器，不存在时返回 null
     */
    public final ExprEvaluator getLeft() {
        return left;
    }

    /**
     * 获取右子表达式执行器。
     *
     * @return 当前的右子表达式执行器，不存在时返回 null
     */
    public final ExprEvaluator getRight() {
        return right;
    }

    /**
     * 判断是否对执行结果取负。
     *
     * @return 需要取负时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isNegate() {
        return negate;
    }

    /**
     * 设置是否对执行结果取负。
     *
     * @param negate 为 true 时执行结果取负
     * @return 当前对象，便于链式调用
     */
    public ExprEvaluator negate(boolean negate) {
        this.negate = negate;
        return this;
    }

    /**
     * 设置是否对执行结果做逻辑非运算。
     *
     * @param logicalNot 为 true 时对执行结果取逻辑非
     */
    public void setLogicalNot(boolean logicalNot) {
        this.logicalNot = logicalNot;
    }

    /**
     * 使用默认执行环境且无上下文地执行表达式。
     *
     * @return 表达式的执行结果
     */
    public Object evaluate() {
        return evaluate(null, EvaluateEnvironment.DEFAULT);
    }

    /**
     * 在指定上下文与执行环境下执行表达式，按 evalType 分派运算、变量、函数、三元或括号求值。
     *
     * @param context             变量取值上下文，可为 null
     * @param evaluateEnvironment 执行环境，提供函数、精度等配置
     * @return 表达式的执行结果
     */
    public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
        if (this.constant) {
            // 字符串，数字，常量数组等
            return this.result;
        }
        if (evalType == 0) {
            result = left.evaluate(context, evaluateEnvironment);
            this.constant = left.constant;
            return result;
        } else if (evalType == EVAL_TYPE_OPERATOR) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            if (evaluateEnvironment.isAutoParseStringAsDouble()) {
                if (leftValue instanceof String) {
                    leftValue = Double.parseDouble(leftValue.toString());
                }
                if (rightValue instanceof String) {
                    rightValue = Double.parseDouble(rightValue.toString());
                }
            }
            this.constant = left.constant && right.constant;
            // use for debug
            // System.out.println("leftValue: " + leftValue + " rightValue: " + rightValue + " opsType: " + opsType);
            try {
                switch (operator) {
                    case EXP:
                        // **指数，平方/根
                        return result = ExprCalculateUtils.pow(leftValue, rightValue, evaluateEnvironment);
                    case MULTI:
                        // *乘法
                        return result = ExprCalculateUtils.multiply(leftValue, rightValue, evaluateEnvironment);
                    case DIVISION:
                        // /除法
                        return result = ExprCalculateUtils.divide(leftValue, rightValue, evaluateEnvironment);
                    case MOD:
                        // %取余数
                        return result = ExprCalculateUtils.mod(leftValue, rightValue, evaluateEnvironment);
                    case PLUS:
                        // +加法
                        return result = ExprCalculateUtils.plus(leftValue, rightValue, evaluateEnvironment);
                    case MINUS:
                        // -减法（理论上代码不可达） 因为'-'统一转化为了'+'(负)
                        return result = ExprCalculateUtils.subtract(leftValue, rightValue, evaluateEnvironment);
                    case BIT_RIGHT:
                        // >> 位运算右移
                        return result = ((Number) leftValue).longValue() >> ((Number) rightValue).longValue();
                    case BIT_LEFT:
                        // << 位运算左移
                        return result = ((Number) leftValue).longValue() << ((Number) rightValue).longValue();
                    case GT:
                        // >
                        return result = ((Number) leftValue).doubleValue() > ((Number) rightValue).doubleValue();
                    case LT:
                        // <
                        return result = ((Number) leftValue).doubleValue() < ((Number) rightValue).doubleValue();
                    case EQ:
                        // ==
                        if (leftValue instanceof Number && rightValue instanceof Number) {
                            return result = ((Number) leftValue).doubleValue() == ((Number) rightValue).doubleValue();
                        }
                        if (leftValue == rightValue) {
                            return result = true;
                        }
                        return result = leftValue != null && leftValue.equals(rightValue);
                    case GE:
                        // >=
                        return result = ((Number) leftValue).doubleValue() >= ((Number) rightValue).doubleValue();
                    case LE:
                        // <=
                        return result = ((Number) leftValue).doubleValue() <= ((Number) rightValue).doubleValue();
                    case NE:
                        // !=
                        if (leftValue instanceof Number && rightValue instanceof Number) {
                            // 基础类型double直接比较值
                            return result = ((Number) leftValue).doubleValue() != ((Number) rightValue).doubleValue();
                        }
                        if (leftValue == rightValue) {
                            return result = false;
                        }
                        return result = leftValue == null || !leftValue.equals(rightValue);
                    case AND:
                        // &
                        return result = ExprCalculateUtils.and(leftValue, rightValue);
                    case XOR:
                        // ^
                        return result = ExprCalculateUtils.xor(leftValue, rightValue);
                    case OR:
                        // |
                        return result = ExprCalculateUtils.or(leftValue, rightValue);
                    case LOGICAL_AND:
                        // &&
                        return result = (Boolean) leftValue && (Boolean) rightValue;
                    case LOGICAL_OR:
                        // ||
                        return result = (Boolean) leftValue || (Boolean) rightValue;
                    case IN:
                        /// in
                        return result = this.evaluateIn(leftValue, rightValue);
                    case OUT:
                        /// out
                        return result = !this.evaluateIn(leftValue, rightValue);
//                case COLON:
//                    // : 三目运算结果, 不支持单独运算
//                    throw new ExpressionException("cannot use colon operator alone: ':'");
//                case QUESTION:
//                    // ? 三目运算条件
//                    return right.evaluateTernary(context, evaluateEnvironment, (Boolean) leftValue, left.constant);
                    default:
                        break;
                }
            } catch (RuntimeException exception) {
                throwEvalOperatorException(exception, operator, leftValue, rightValue, left, right);
            }
            // 默认返回null
        } else if (evalType == EVAL_TYPE_BRACKET) {
            // Brackets operation calculates right
            Object bracketValue = right.evaluate(context, evaluateEnvironment);
            this.constant = right.constant;
            if (negate) {
                return result = ExprCalculateUtils.negate(bracketValue);
            }
            if (logicalNot) {
                return result = bracketValue == Boolean.FALSE || bracketValue == null;
            }
            return result = bracketValue;
        } else if (evalType == EVAL_TYPE_QUESTION) {
            // 三目运算
            Object conditionValue = left.evaluate(context, evaluateEnvironment);
            Object result =
                    right.evaluateTernary(context, evaluateEnvironment, (Boolean) conditionValue, left.constant);
            this.constant = left.constant && right.constant;
            if (this.constant) {
                this.result = result;
            }
            return result;
        } else {
            // Other unified returns to left
            result = left.evaluate(context, evaluateEnvironment);
            this.constant = left.constant;
            return result;
        }
        return null;
    }

    private void throwEvalOperatorException(RuntimeException exception, ElOperator operator, Object leftValue,
                                            Object rightValue, ExprEvaluator left, ExprEvaluator right) {
        if (exception instanceof NullPointerException) {
            if (leftValue == null) {
                left.throwNotAllowNullException();
            } else if (rightValue == null) {
                right.throwNotAllowNullException();
            }
        } else {
            if (exception instanceof ExpressionException) {
                throw exception;
            } else {
                throw new ExpressionException(
                        "ElRunError: execution of operation symbol '" + operator.symbol + "' failed, left " +
                                leftValue + ", right " + rightValue, exception);
            }
        }
    }

    void throwNotAllowNullException() {
    }

    /**
     * in表达式
     */
    boolean evaluateIn(Object leftValue, Object rightValue) {
        if (leftValue == null || rightValue == null) {
            return false;
        }
        Class<?> rightCls;
        if (rightValue instanceof Collection) {
            Collection collection = (Collection) rightValue;
            for (Object value : collection) {
                boolean isEqual = this.execEquals(value, leftValue);
                if (isEqual) {
                    return true;
                }
            }
        } else if ((rightCls = rightValue.getClass()).isArray()) {
            Class<?> componentType = rightCls.getComponentType();
            if (componentType.isPrimitive()) {
                ReflectConsts.PrimitiveType primitiveType = ReflectConsts.PrimitiveType.typeOf(componentType);
                int length = primitiveType.arrayLength(rightValue);
                for (int i = 0; i < length; ++i) {
                    Object value = primitiveType.elementAt(rightValue, i);
                    boolean isEqual = leftValue == value;
                    if (isEqual) {
                        return true;
                    }
                }
            } else {
                for (Object value : (Object[]) rightValue) {
                    boolean isEqual = this.execEquals(value, leftValue);
                    if (isEqual) {
                        return true;
                    }
                }
            }
        } else {
            if (rightValue instanceof Number || rightValue instanceof CharSequence) {
                return false;
            }
            return ObjectUtils.contains(rightValue, String.valueOf(leftValue));
        }
        return false;
    }

    private boolean execEquals(Object value, Object leftValue) {
        if (value instanceof Number && leftValue instanceof Number) {
            if (((Number) value).doubleValue() == ((Number) leftValue).doubleValue()) {
                return true;
            }
        }
        if (value == leftValue || String.valueOf(leftValue).equals(String.valueOf(value))) {
            return true;
        }
        return false;
    }

    /**
     * 运行三目运算符号
     */
    Object evaluateTernary(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment, Boolean bool,
                           boolean isStatic) {
        if (this.constant) {
            return this.result;
        }
        Object result;
        if (bool == null || !bool) {
            this.constant = isStatic && right.constant;
            result = right.evaluate(context, evaluateEnvironment);
        } else {
            this.constant = isStatic && left.constant;
            result = left.evaluate(context, evaluateEnvironment);
        }
        if (this.constant) {
            this.result = result;
        }
        return result;
    }

    final ExprEvaluator update(ExprEvaluator left, ExprEvaluator right) {
        this.left = left;
        this.right = right;
        return this;
    }

    /**
     * 变量执行器
     */
    static class VariableImpl extends ExprEvaluator {
        // 变量访问模型
        ElVariableInvoker variableInvoker;

        public VariableImpl() {
            this.evalType = EVAL_TYPE_VARIABLE;
        }

        /***
         * 设置变量值？
         *
         * @param variableInvoker
         */
        public void setVariableInvoker(ElVariableInvoker variableInvoker) {
            this.variableInvoker = variableInvoker;
        }

        public VariableImpl normal() {
            return new NormalVariableImpl(variableInvoker);
        }

        @Override
        final void throwNotAllowNullException() {
            throw new ExpressionException("unresolved property or variable '" + variableInvoker + "' from context");
        }

        public final Object getVariableValue(EvaluatorContext evaluatorContext,
                                             EvaluateEnvironment evaluateEnvironment) {
            Object obj = null;
            try {
                obj = evaluatorContext.getContextValue(variableInvoker);
                // context.variableValues[variableInvoke.index];
//                if (obj == null && !evaluateEnvironment.isAllowVariableNull()) {
//                    throw new ExpressionException("unresolved property or variable '" + variableInvoker + "' from
//                    context");
//                }
                if (evaluateEnvironment.isAutoParseStringAsDouble() && obj instanceof String) {
                    obj = Double.parseDouble((String) obj);
                }
            } catch (RuntimeException e) {
                if (obj == null && evaluatorContext == null) {
                    throw new ExpressionException(
                            "unresolved property or variable '" + variableInvoker + "' from context");
                } else {
                    throw e;
                }
            }
            return obj;
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object obj = getVariableValue(context, evaluateEnvironment);
            if (negate) {
                Number number = (Number) obj;
                if (number instanceof Double || number instanceof Float) {
                    return -number.doubleValue();
                }
                if (number instanceof Integer) {
                    return -number.intValue();
                }
                return -number.longValue();
            }
            if (logicalNot) {
                return obj == Boolean.FALSE || obj == null;
            }
            return obj;
        }

        @Override
        public String code() {
            StringBuilder builder = new StringBuilder();
            if (negate) {
                builder.append('-');
            }
            if (logicalNot) {
                builder.append("!(");
            }
            builder.append("_$").append(variableInvoker.getIndex());
            if (logicalNot) {
                builder.append(")");
            }
            return builder.toString();
        }

        // 内联key
        ExprEvaluator internKey() {
            variableInvoker.internKey();
            return this;
        }

        @Override
        public String toString() {
            return "VariableImpl{" + variableInvoker + "}";
        }

        static final class NormalVariableImpl extends VariableImpl {
            NormalVariableImpl(ElVariableInvoker variableInvoke) {
                setVariableInvoker(variableInvoke);
            }

            @Override
            public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
                return getVariableValue(context, evaluateEnvironment);
            }
        }
    }

    /**
     * 常量执行器(字符串,数字,布尔值,null)
     */
    static final class ConstantImpl extends ExprEvaluator {
        ConstantImpl(Object result) {
            this.result = result;
            this.constant = true;
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return result;
        }

        @Override
        public String toString() {
            return String.valueOf(result);
        }

        @Override
        public String code() {
            if (result instanceof String) {
                StringBuilder builder = new StringBuilder();
                String strValue = (String) result;
                if (strValue.indexOf('"') > -1) {
                    strValue = strValue.replace("\"", "\\\"");
                }
                return builder.append("\"").append(strValue).append("\"").toString();
            }
            return String.valueOf(result);
        }
    }

    // 数组
    static final class ListImpl extends ExprEvaluator {
        private final List<Object> list;

        ListImpl(List<Object> list) {
            boolean isConstant = true;
            for (Object obj : list) {
                if (obj instanceof ExprParser) {
                    isConstant = false;
                    break;
                }
            }
            this.constant = isConstant;
            this.list = list;
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            if (constant) {
                return list;
            }
            List<Object> result = new ArrayList<Object>();
            for (Object obj : list) {
                result.add(
                        obj instanceof ExprParser ? ((ExprParser) obj).doEvaluate(context, evaluateEnvironment) : obj);
            }
            return result;
        }

        @Override
        public String toString() {
            return String.valueOf(result);
        }

        @Override
        public String code() {
            StringBuilder builder = new StringBuilder("java.util.Arrays.asList(");
            codeParams(builder, list);
            builder.append(")");
            return builder.toString();
        }
    }

    // Bracket
    static final class BracketImpl extends ExprEvaluator {
        static BracketImpl of(ExprEvaluator evaluator) {
            BracketImpl exprEvaluatorImpl = new BracketImpl();
            exprEvaluatorImpl.right = evaluator.right;
            exprEvaluatorImpl.negate = evaluator.negate;
            exprEvaluatorImpl.logicalNot = evaluator.logicalNot;
            return exprEvaluatorImpl;
        }

        @Override
        public String toString() {
            return "BracketImpl{()}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object bracketValue = right.evaluate(context, evaluateEnvironment);
            if (negate) {
                if (bracketValue instanceof Double) {
                    return -(Double) bracketValue;
                } else if (bracketValue instanceof Long) {
                    return -(Long) bracketValue;
                } else if (bracketValue instanceof Integer) {
                    return -(Integer) bracketValue;
                } else {
                    return -((Number) bracketValue).doubleValue();
                }
            }
            if (logicalNot) {
                return bracketValue == Boolean.FALSE || bracketValue == null;
            }
            return bracketValue;
        }
    }

    /**
     * 加法执行器
     */
    static final class PlusImpl extends ExprEvaluator {
        static PlusImpl of(ExprEvaluator evaluator) {
            PlusImpl evaluatorImpl = new PlusImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "PlusImpl{+}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return ExprCalculateUtils.plus(left.evaluate(context, evaluateEnvironment),
                    right.evaluate(context, evaluateEnvironment), evaluateEnvironment);
        }
    }

    static final class MinusImpl extends ExprEvaluator {
        static MinusImpl of(ExprEvaluator evaluator) {
            MinusImpl evaluatorImpl = new MinusImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "MinusImpl{-}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return ExprCalculateUtils.subtract(left.evaluate(context, evaluateEnvironment),
                    right.evaluate(context, evaluateEnvironment), evaluateEnvironment);
        }
    }

    static final class MultiplyImpl extends ExprEvaluator {
        static MultiplyImpl of(ExprEvaluator evaluator) {
            MultiplyImpl evaluatorImpl = new MultiplyImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "MultiplyImpl{*}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return ExprCalculateUtils.multiply(left.evaluate(context, evaluateEnvironment),
                    right.evaluate(context, evaluateEnvironment), evaluateEnvironment);
        }
    }

    static final class PowerImpl extends ExprEvaluator {
        static PowerImpl of(ExprEvaluator evaluator) {
            PowerImpl evaluatorImpl = new PowerImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "PowerImpl{**}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return ExprCalculateUtils.pow(left.evaluate(context, evaluateEnvironment),
                    right.evaluate(context, evaluateEnvironment), evaluateEnvironment);
        }
    }

    static final class DivisionImpl extends ExprEvaluator {
        static DivisionImpl of(ExprEvaluator evaluator) {
            DivisionImpl evaluatorImpl = new DivisionImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "DivisionImpl{/}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return ExprCalculateUtils.divide(left.evaluate(context, evaluateEnvironment),
                    right.evaluate(context, evaluateEnvironment), evaluateEnvironment);
        }
    }

    /**
     * 取模（求余）执行器modulus
     */
    static final class ModulusImpl extends ExprEvaluator {
        static ModulusImpl of(ExprEvaluator evaluator) {
            ModulusImpl evaluatorImpl = new ModulusImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "ModulusImpl{%}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return ExprCalculateUtils.mod(left.evaluate(context, evaluateEnvironment),
                    right.evaluate(context, evaluateEnvironment), evaluateEnvironment);
        }
    }

    // bit left
    static final class BitLeftImpl extends ExprEvaluator {
        static BitLeftImpl of(ExprEvaluator evaluator) {
            BitLeftImpl evaluatorImpl = new BitLeftImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "BitLeftImpl{<<}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ((Number) leftValue).longValue() << ((Number) rightValue).longValue();
        }
    }

    static final class BitRightImpl extends ExprEvaluator {
        static BitRightImpl of(ExprEvaluator evaluator) {
            BitRightImpl evaluatorImpl = new BitRightImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "BitRightImpl{>>}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ((Number) leftValue).longValue() >> ((Number) rightValue).longValue();
        }
    }

    static final class BitAndImpl extends ExprEvaluator {
        static BitAndImpl of(ExprEvaluator evaluator) {
            BitAndImpl evaluatorImpl = new BitAndImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "BitAndImpl{&}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ExprCalculateUtils.and(leftValue, rightValue);
        }
    }

    static final class BitOrImpl extends ExprEvaluator {
        static BitOrImpl of(ExprEvaluator evaluator) {
            BitOrImpl evaluatorImpl = new BitOrImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "BitOrImpl{|}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ExprCalculateUtils.or(leftValue, rightValue);
        }
    }

    static final class BitXorImpl extends ExprEvaluator {
        static BitXorImpl of(ExprEvaluator evaluator) {
            BitXorImpl evaluatorImpl = new BitXorImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "BitOrImpl{^}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ExprCalculateUtils.xor(leftValue, rightValue);
        }
    }

    static final class EqualImpl extends ExprEvaluator {
        static EqualImpl of(ExprEvaluator evaluator) {
            EqualImpl evaluatorImpl = new EqualImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "EqualImpl{==}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            // == 运算
            if (leftValue instanceof Number && rightValue instanceof Number) {
                return ((Number) leftValue).doubleValue() == ((Number) rightValue).doubleValue();
            }
            if (leftValue == rightValue) {
                return true;
            }
            return leftValue != null && leftValue.equals(rightValue);
        }
    }

    static final class GtImpl extends ExprEvaluator {
        static GtImpl of(ExprEvaluator evaluator) {
            GtImpl evaluatorImpl = new GtImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "GtImpl{>}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ((Number) leftValue).doubleValue() > ((Number) rightValue).doubleValue();
        }
    }

    static final class LtImpl extends ExprEvaluator {
        static LtImpl of(ExprEvaluator evaluator) {
            LtImpl evaluatorImpl = new LtImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "LtImpl{<}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ((Number) leftValue).doubleValue() < ((Number) rightValue).doubleValue();
        }
    }

    static final class GEImpl extends ExprEvaluator {
        static GEImpl of(ExprEvaluator evaluator) {
            GEImpl evaluatorImpl = new GEImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "GEImpl{>=}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ((Number) leftValue).doubleValue() >= ((Number) rightValue).doubleValue();
        }
    }

    static final class LEImpl extends ExprEvaluator {
        static LEImpl of(ExprEvaluator evaluator) {
            LEImpl evaluatorImpl = new LEImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "LEImpl{<=}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            return ((Number) leftValue).doubleValue() <= ((Number) rightValue).doubleValue();
        }
    }

    static final class NEImpl extends ExprEvaluator {
        static NEImpl of(ExprEvaluator evaluator) {
            NEImpl evaluatorImpl = new NEImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "NEImpl{!=}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            if (leftValue instanceof Number && rightValue instanceof Number) {
                return ((Number) leftValue).doubleValue() != ((Number) rightValue).doubleValue();
            }
            if (leftValue == rightValue) {
                return false;
            }
            return leftValue == null || !leftValue.equals(rightValue);
        }
    }

    static final class LogicalAndImpl extends ExprEvaluator {
        static LogicalAndImpl of(ExprEvaluator evaluator) {
            LogicalAndImpl evaluatorImpl = new LogicalAndImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "LogicalAndImpl{&&}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            // &&
            return (Boolean) leftValue && (Boolean) rightValue;
        }
    }

    static final class LogicalOrImpl extends ExprEvaluator {
        static LogicalOrImpl of(ExprEvaluator evaluator) {
            LogicalOrImpl evaluatorImpl = new LogicalOrImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "LogicalOrImpl{||}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            // ||
            return (Boolean) leftValue || (Boolean) rightValue;
        }
    }

    static final class TernaryImpl extends ExprEvaluator {
        private ExprEvaluator condition;
        private ExprEvaluator question;
        private ExprEvaluator colon;

        static TernaryImpl of(ExprEvaluator evaluator) {
            TernaryImpl evaluatorImpl = new TernaryImpl();
            evaluatorImpl.condition = evaluator.left;
            evaluatorImpl.question = evaluator.right.left;
            evaluatorImpl.colon = evaluator.right.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "TernaryImpl{?:}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = condition.evaluate(context, evaluateEnvironment);
            boolean conditionResult = leftValue == null ? false : (Boolean) leftValue;
            return conditionResult ? question.evaluate(context, evaluateEnvironment) : colon.evaluate(context,
                    evaluateEnvironment);
        }
    }

    static final class InImpl extends ExprEvaluator {
        static InImpl of(ExprEvaluator evaluator) {
            InImpl evaluatorImpl = new InImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "InImpl{in}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            // in
            return this.evaluateIn(leftValue, rightValue);
        }
    }

    static final class OutImpl extends ExprEvaluator {
        static OutImpl of(ExprEvaluator evaluator) {
            OutImpl evaluatorImpl = new OutImpl();
            evaluatorImpl.left = evaluator.left;
            evaluatorImpl.right = evaluator.right;
            return evaluatorImpl;
        }

        @Override
        public String toString() {
            return "OutImpl{out}";
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object leftValue = left.evaluate(context, evaluateEnvironment);
            Object rightValue = right.evaluate(context, evaluateEnvironment);
            // out
            return !this.evaluateIn(leftValue, rightValue);
        }
    }

    static class FunctionImpl extends ExprEvaluator {
        // 函数名称
        final String functionName;
        // 参数数组长度
        final int paramLength;
        // 参数表达式数组
        final Object[] methodParams;

        @Override
        public String toString() {
            return "FunctionImpl{@function}";
        }

        public FunctionImpl(String functionName, List<Object> params) {
            this.evalType = EVAL_TYPE_FUN;
            this.functionName = functionName;
            this.methodParams = params == null ? new Object[0] : params.toArray();
            this.paramLength = methodParams.length;
        }

        @Override
        public String code() {
            //template: "%s(%s)"
            StringBuilder builder = new StringBuilder();
            builder.append(functionName)
                    .append("(");
            codeParams(builder, Arrays.asList(methodParams));
            builder.append(")");
            return builder.toString();
        }

        protected final Object[] invokeParams(EvaluatorContext evaluatorContext,
                                              EvaluateEnvironment evaluateEnvironment) {
            if (paramLength == 0) {
                return EMPTY_ARGS;
            }
            Object[] params = new Object[paramLength];
            for (int i = 0; i < paramLength; ++i) {
                Object obj = methodParams[i];
                params[i] = obj instanceof ExprParser ?
                        ((ExprParser) obj).doEvaluate(evaluatorContext, evaluateEnvironment) : obj;
            }
            return params;
        }

        // 执行函数表达式
        Object evaluateFunction(EvaluatorContext evaluatorContext, EvaluateEnvironment evaluateEnvironment) {
            // 函数参数
            Object[] params = invokeParams(evaluatorContext, evaluateEnvironment);
            ExprFunction exprFunction = evaluateEnvironment.getFunction(this.functionName);
            if (exprFunction == null) {
                throw new ExpressionException("function '" + functionName + "' is unregistered!");
            }
            return exprFunction.call(params);
        }

        @Override
        public Object evaluate(EvaluatorContext evaluatorContext, EvaluateEnvironment evaluateEnvironment) {
            // 执行函数
            Object functionValue = this.evaluateFunction(evaluatorContext, evaluateEnvironment);
            if (negate) {
                return ExprCalculateUtils.negate(functionValue);
            }
            if (logicalNot) {
                return functionValue == Boolean.FALSE || functionValue == null;
            }
            return functionValue;
        }
    }

    /**
     * java对象方法调用（解析模式下使用反射调用，编译模式和常规编码一致）
     */
    static final class MethodImpl extends FunctionImpl {
        ElVariableInvoker variableInvoker;

        @Override
        public String toString() {
            return "MethodImpl{@method}";
        }

        public MethodImpl(ElVariableInvoker variableInvoker, String functionName, List<Object> params) {
            super(functionName, params);
            this.variableInvoker = variableInvoker;
        }

        @Override
        public String code() {
            // template: "_$%d.%s(%s)"
            StringBuilder builder = new StringBuilder();
            builder.append("_$")
                    .append(variableInvoker.getIndex())
                    .append(".")
                    .append(functionName)
                    .append("(");
            codeParams(builder, Arrays.asList(methodParams));
            builder.append(")");
            return builder.toString();
        }

        Object evaluateMethod(EvaluatorContext evaluatorContext, EvaluateEnvironment evaluateEnvironment) {
            // 获取方法调用者
            Object invokeObj;
            try {
                invokeObj = evaluatorContext.getContextValue(variableInvoker);
            } catch (RuntimeException e) {
                throw new ExpressionException("unresolved property or variable: '" + variableInvoker + "' from context",
                        e);
            }
            try {
                if (invokeObj == null && evaluateEnvironment.allowVariableNull) {
                    return null;
                }
                return ReflectionUtils.invoke(invokeObj, functionName,
                        invokeParams(evaluatorContext, evaluateEnvironment));
            } catch (Throwable throwable) {
                if (invokeObj == null) {
                    throw new ExpressionException(
                            "unresolved property or variable: '" + variableInvoker + "' from context");
                }
                throw new ExpressionException(
                        String.format("invoke error, %s and methodName '%s', message: %s", invokeObj.getClass(),
                                functionName, throwable.getMessage()));
            }
        }

        @Override
        public Object evaluate(EvaluatorContext evaluatorContext, EvaluateEnvironment evaluateEnvironment) {
            // 执行函数
            Object functionValue = this.evaluateMethod(evaluatorContext, evaluateEnvironment);
            if (negate) {
                return ExprCalculateUtils.negate(functionValue);
            }
            if (logicalNot) {
                return functionValue == Boolean.FALSE || functionValue == null;
            }
            return functionValue;
        }
    }

    /**
     * java成员访问包括属性或者方法
     */
    static final class MemberImpl extends ExprEvaluator {
        final ExprEvaluator host;
        final boolean isMethod;
        final String memberName;
        final List<Object> methodParams;

        @Override
        public String toString() {
            if (isMethod) {
                return "MemberImpl{." + memberName + "()}";
            } else {
                return "MemberImpl{." + memberName + "}";
            }
        }

        public MemberImpl(ExprEvaluator host, boolean isMethod, String memberName, List<Object> methodParams) {
            this.host = host;
            this.isMethod = isMethod;
            this.memberName = memberName;
            this.methodParams = methodParams;
        }

        @Override
        public String code() {
            StringBuilder builder = new StringBuilder(host.code());
            builder.append(".");
            if (isMethod) {
                builder.append(memberName)
                        .append("(");
                codeParams(builder, methodParams);
                builder.append(")");
            } else {
                builder.append(memberName);
            }
            return builder.toString();
        }

        Object evaluateMember(EvaluatorContext evaluatorContext, EvaluateEnvironment evaluateEnvironment) {
            // 获取成员的host
            Object target;
            try {
                target = this.host.evaluate(evaluatorContext, evaluateEnvironment);
            } catch (RuntimeException e) {
                throw new ExpressionException("unresolved member host from context ", e);
            }
            try {
                if (target == null) {
                    return null;
                }
                if (isMethod) {
                    Object[] params = new Object[methodParams.size()];
                    int i = 0;
                    for (Object obj : methodParams) {
                        params[i++] = obj instanceof ExprParser ?
                                ((ExprParser) obj).doEvaluate(evaluatorContext, evaluateEnvironment) : obj;
                    }
                    return ReflectionUtils.invoke(target, memberName, params);
                } else {
                    Object value = ObjectUtils.get(target, memberName);
                    if (value == null) {
                        if (evaluateEnvironment.allowVariableNull) {
                            return null;
                        } else {
                            throw new ExpressionException("unresolved member: '" + memberName + "' from context");
                        }
                    }
                    return value;
                }
            } catch (Throwable throwable) {
                if (!ObjectUtils.contains(target, memberName)) {
                    throw new ExpressionException(
                            "unresolved member '" + memberName + "' not exist in " + target.getClass());
                }
                throw new ExpressionException("unresolved member: '" + memberName + "' from context", throwable);
            }
        }

        @Override
        public Object evaluate(EvaluatorContext evaluatorContext, EvaluateEnvironment evaluateEnvironment) {
            // 执行函数
            Object memberValue = evaluateMember(evaluatorContext, evaluateEnvironment);
            if (negate) {
                return ExprCalculateUtils.negate(memberValue);
            }
            if (logicalNot) {
                return memberValue == Boolean.FALSE || memberValue == null;
            }
            return memberValue;
        }
    }

    /**
     * Stack splitting optimizer to solve the problem of stack depth overflow;
     * It is generally not useful. When the native compiler cannot solve the expression, expression splitting
     * optimization can be enabled;
     * <p>
     * default depth = 2 << 12 - 1
     */
    static class StackSplitImpl extends ExprEvaluator {
        ExprEvaluator front;

        StackSplitImpl(ExprEvaluator front, ExprEvaluator left) {
            this.front = front;
            this.left = left;
        }

        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            Object val = left.evaluate(context, evaluateEnvironment);
            try {
                context.value = val;
                return front.evaluate(context, evaluateEnvironment);
            } finally {
                context.value = null;
            }
        }
    }

    static class ContextValueHolderImpl extends ExprEvaluator {
        @Override
        public Object evaluate(EvaluatorContext context, EvaluateEnvironment evaluateEnvironment) {
            return context.value;
        }
    }

    // Optimize
    /**
     * 反复执行深度优化，直到表达式树不再发生变化。
     *
     * @return 优化后的表达式执行器根节点
     */
    public ExprEvaluator optimize() {
        ExprEvaluator optimized = optimizeDepth();

        // contine call optimizeDepth util next is self
        ExprEvaluator next;
        while (optimized != (next = optimized.optimizeDepth())) {
            optimized = next;
        }

        return optimized;
    }

    // Optimize the depth on the left side, and support the super long expression
    /**
     * 对左侧链路做一轮深度拆分优化，避免超长表达式递归执行时栈溢出。
     *
     * @return 优化后的表达式执行器根节点；无需优化时返回自身
     */
    public ExprEvaluator optimizeDepth() {
        ExprEvaluator optimizedRoot = optimizeDepth(this, 0);
        ExprEvaluator target = optimizedRoot;
        boolean optimizeFlag = target != this;
        while (optimizeFlag) {
            ExprEvaluator oldLeft = target.left;
            ExprEvaluator newLeft = oldLeft.optimizeDepth(oldLeft, 0);
            // if return is not self, continue optimize
            optimizeFlag = newLeft != oldLeft;
            // update left
            target.left = newLeft;
            // next optimize target
            target = newLeft;
        }
        return optimizedRoot;
    }

    /**
     * 优化深度
     *
     * @param target
     * @param depth
     * @return
     */
    ExprEvaluator optimizeDepth(ExprEvaluator target, int depth) {
        if (left == null) {
            return target;
        }
        // 每1024优化一次left
        if (++depth > OPTIMIZE_DEPTH_VALUE) {
            StackSplitImpl stackSplit = new StackSplitImpl(target, left);
            ContextValueHolderImpl valueHolder = new ContextValueHolderImpl();
            // update target left
            left = valueHolder;
            return stackSplit;
        } else {
            return left.optimizeDepth(target, depth);
        }
    }

    /**
     * 返回表达式的代码
     *
     * @return 表达式对应的 java 代码片段
     * @throws UnsupportedOperationException 非编译型执行器不支持该操作
     */
    public String code() {
        throw new UnsupportedOperationException("non compiled executor are not supported code()");
    }

    static final void codeParams(StringBuilder builder, List<Object> params) {
        int i = 0;
        for (Object obj : params) {
            if (i > 0) {
                builder.append(",");
            }
            if (obj instanceof String) {
                String strValue = (String) obj;
                if (strValue.indexOf('"') > -1) {
                    strValue = strValue.replace("\"", "\\\"");
                }
                builder.append("\"").append(strValue).append("\"");
            } else if (obj instanceof ExprParser) {
                builder.append(((ExprParser) obj).getEvaluator().code());
            } else {
                builder.append(obj);
            }
            ++i;
        }
    }
}
