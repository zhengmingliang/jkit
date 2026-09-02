package com.alianga.jkit.expression;

import java.util.Collection;
import java.util.Map;

/**
 * 使用多 Invoke 链式调用替代循环开销
 * 数量有限时，性能优于循环调用；
 *
 * @time 2022/10/29 22:51
 */
public class ElChainVariableInvoker implements ElInvoker {
    final ElVariableInvoker variableInvoke;
    ElChainVariableInvoker next;

    ElChainVariableInvoker(ElVariableInvoker variableInvoke) {
        this.variableInvoke = variableInvoke;
    }

    @Override
    public Object invokeDirect(Object context) {
        throw new UnsupportedOperationException("chain invoker is not supported");
    }

    @Override
    public Object invokeDirect(Map context) {
        throw new UnsupportedOperationException("chain invoker is not supported");
    }

    @Override
    public Object invoke(Object entityContext, Object[] variableValues) {
        try {
            variableInvoke.invoke(entityContext, variableValues);
        } catch (RuntimeException runtimeException) {
            throw new IllegalArgumentException(
                    String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                            runtimeException.getMessage()));
        }
        return next.invoke(entityContext, variableValues);
    }

    @Override
    public Object invoke(Map mapContext, Object[] variableValues) {
        try {
            variableInvoke.invoke(mapContext, variableValues);
        } catch (RuntimeException runtimeException) {
            throw new IllegalArgumentException(
                    String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                            runtimeException.getMessage()));
        }
        return next.invoke(mapContext, variableValues);
    }

    @Override
    public Object invokeCurrent(Map globalContext, Object parentContext, Object[] variableValues) {
        try {
            variableInvoke.invokeCurrent(globalContext, parentContext, variableValues);
        } catch (RuntimeException runtimeException) {
            throw new IllegalArgumentException(
                    String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                            runtimeException.getMessage()));
        }
        return next.invokeCurrent(globalContext, parentContext, variableValues);
    }

    @Override
    public Object invokeCurrent(Object globalContext, Object parentContext, Object[] variableValues) {
        try {
            variableInvoke.invokeCurrent(globalContext, parentContext, variableValues);
        } catch (RuntimeException runtimeException) {
            throw new IllegalArgumentException(
                    String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                            runtimeException.getMessage()));
        }
        return next.invokeCurrent(globalContext, parentContext, variableValues);
    }

    public void internKey() {
        variableInvoke.internKey();
        if (next != null) {
            next.internKey();
        }
    }

    /**
     * tail
     */
    static class TailImpl extends ElChainVariableInvoker {
        TailImpl(ElVariableInvoker variableInvoke) {
            super(variableInvoke);
        }

        @Override
        public Object invoke(Object entityContext, Object[] variableValues) {
            try {
                return variableInvoke.invoke(entityContext, variableValues);
            } catch (RuntimeException runtimeException) {
                throw new IllegalArgumentException(
                        String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                                runtimeException.getMessage()));
            }
        }

        @Override
        public Object invoke(Map mapContext, Object[] variableValues) {
            try {
                return variableInvoke.invoke(mapContext, variableValues);
            } catch (RuntimeException runtimeException) {
                throw new IllegalArgumentException(
                        String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                                runtimeException.getMessage()));
            }
        }

        @Override
        public Object invokeCurrent(Map globalContext, Object parentContext, Object[] variableValues) {
            try {
                return variableInvoke.invokeCurrent(globalContext, parentContext, variableValues);
            } catch (RuntimeException runtimeException) {
                throw new IllegalArgumentException(
                        String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                                runtimeException.getMessage()));
            }
        }

        @Override
        public Object invokeCurrent(Object globalContext, Object parentContext, Object[] variableValues) {
            try {
                return variableInvoke.invokeCurrent(globalContext, parentContext, variableValues);
            } catch (RuntimeException runtimeException) {
                throw new IllegalArgumentException(
                        String.format("Unresolved field '%s', reason: %s", variableInvoke.toString(),
                                runtimeException.getMessage()));
            }
        }
    }

    /**
     * 将变量调用集合构建成链式调用器，不对变量重新编号。
     *
     * @param variableInvokes 变量名到变量调用器的映射
     * @return 链式调用器的头节点；集合只有一个元素时直接返回该变量调用器，集合为空时返回 null
     */
    public static final ElInvoker build(Map<String, ElVariableInvoker> variableInvokes) {
        return build(variableInvokes, false);
    }

    /**
     * 将变量调用集合构建成链式调用器，链上最后一个节点使用返回结果的尾节点实现。
     *
     * @param variableInvokes 变量名到变量调用器的映射
     * @param indexVariable   是否按遍历顺序为每个变量重新分配下标
     * @return 链式调用器的头节点；集合只有一个元素时直接返回该变量调用器，集合为空时返回 null
     */
    public static final ElInvoker build(Map<String, ElVariableInvoker> variableInvokes, boolean indexVariable) {
        Collection<ElVariableInvoker> collection = variableInvokes.values();
        int length = collection.size();
        int index = 0;
        boolean onlyOne = length == 1;
        ElChainVariableInvoker head = null;
        ElChainVariableInvoker prev = null;
        for (ElVariableInvoker variableInvoke : collection) {
            if (indexVariable) {
                variableInvoke.index(index);
            }
            if (onlyOne) {
                return variableInvoke;
            }
            boolean tail = ++index == length;
            ElChainVariableInvoker node =
                    tail ? new TailImpl(variableInvoke) : new ElChainVariableInvoker(variableInvoke);
            if (head == null) {
                head = node;
            } else {
                prev.next = node;
            }
            prev = node;
        }
        return head;
    }

    /**
     * 将尾部变量调用集合构建成链式调用器，与 build 的区别是即使只有一个元素也会包装为链节点。
     *
     * @param tailInvokerMap 变量名到尾部变量调用器的映射
     * @return 链式调用器的头节点，映射为空时返回 null
     */
    public static final ElInvoker buildTailChainInvoker(Map<String, ElVariableInvoker> tailInvokerMap) {
        Collection<ElVariableInvoker> tailInvokerValues = tailInvokerMap.values();
        int length = tailInvokerValues.size();
        int index = 0;
        ElChainVariableInvoker head = null;
        ElChainVariableInvoker prev = null;
        for (ElVariableInvoker variableInvoke : tailInvokerValues) {
            boolean tail = ++index == length;
            ElChainVariableInvoker node =
                    tail ? new TailImpl(variableInvoke) : new ElChainVariableInvoker(variableInvoke);
            if (head == null) {
                head = node;
            } else {
                prev.next = node;
            }
            prev = node;
        }
        return head;
    }
}
