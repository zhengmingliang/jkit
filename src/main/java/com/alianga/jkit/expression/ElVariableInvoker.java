package com.alianga.jkit.expression;

import com.alianga.jkit.beans.ObjectUtils;
import com.alianga.jkit.json.internal.utils.CollectionUtils;
import com.alianga.jkit.reflect.ClassStrucWrap;
import com.alianga.jkit.reflect.GetterInfo;
import com.alianga.jkit.reflect.ReflectConsts;

import java.util.Map;

/**
 * 变量调用值
 *
 * <p> invoke过程：
 * <p> 获取变量上下文：
 * <p> 如果当前为root,则将参数直接作为上下文进行invoke，否则调用parent结果作为上下文；
 *
 * @time 2022/10/28 22:30
 */
public class ElVariableInvoker implements ElInvoker {
    String key;
    ValueInvokeHolder invokeHolder = ValueInvokeHolder.Empty;
    // index pos
    int index;
    int tailIndex;
    //    int usages = 1;
    // Parent or Up one level
    ElVariableInvoker parent;
    // is last or not ?
    boolean tail;

    //    boolean hasChildren;
    ElVariableInvoker(String key) {
        this.key = key;
    }

    ElVariableInvoker(String key, ElVariableInvoker parent) {
        parent.getClass();
        this.key = key;
        this.parent = parent;
    }

    @Override
    public String toString() {
        return parent.toString() + "." + key;
    }

    /**
     * 获取上一级（父级）变量调用器
     *
     * @return 父级变量调用器，当前节点为根节点时返回 {@code null}
     */
    public ElVariableInvoker getParent() {
        return parent;
    }

    /**
     * 获取当前层级的变量名（键）
     *
     * @return 当前层级的变量名
     */
    public String getKey() {
        return key;
    }

    @Override
    public Object invokeDirect(Object context) {
        Object target = parent.invokeDirect(context);
        return invokeValue(target);
    }

    @Override
    public Object invokeDirect(Map context) {
        Object target = parent.invokeDirect(context);
        return invokeValue(target);
    }

    @Override
    public Object invoke(Object entityContext, Object[] variableValues) {
        Object value = variableValues[index];
        if (value != null) {
            return value;
        }
        Object target = parent.invoke(entityContext, variableValues);
        return variableValues[index] = invokeValue(target);
    }

    @Override
    public Object invoke(Map mapContext, Object[] variableValues) {
        Object value = variableValues[index];
        if (value != null) {
            return value;
        }
        Object target = parent.invoke(mapContext, variableValues);
        return variableValues[index] = invokeValue(target);
    }

    @Override
    public Object invokeCurrent(Map globalContext, Object parentContext, Object[] variableValues) {
        return variableValues[index] = invokeValue(parentContext);
    }

    @Override
    public Object invokeCurrent(Object globalContext, Object parentContext, Object[] variableValues) {
        return variableValues[index] = invokeValue(parentContext);
    }

    /**
     * 仅基于给定的父级上下文取值，不再向上递归调用父级调用器
     *
     * @param globalContext 全局上下文，当前实现中未使用
     * @param context 直接取值的上下文对象
     * @return 从上下文中取出的当前层级变量值，取不到时抛出 {@link IllegalArgumentException}
     */
    public Object invokeCurrent(Object globalContext, Object context) {
        return invokeValue(context);
    }

    /**
     * 仅基于给定的父级上下文取值，不再向上递归调用父级调用器
     *
     * @param globalContext 全局 Map 上下文，当前实现中未使用
     * @param context 直接取值的上下文对象
     * @return 从上下文中取出的当前层级变量值，取不到时抛出 {@link IllegalArgumentException}
     */
    public Object invokeCurrent(Map globalContext, Object context) {
        return invokeValue(context);
    }

    // invoke current
    /**
     * 从上下文对象中读取当前层级变量的值。
     *
     * <p> 上下文为 {@link Map} 时按键取值，否则通过反射（或 unsafe 字段偏移）读取同名属性，
     * 并缓存上下文类型与取值器以加速后续同类型调用。
     *
     * @param context 取值的上下文对象，为 {@code null} 或不存在对应属性时抛出异常
     * @return 当前层级变量的值
     */
    public final Object invokeValue(Object context) {
        Object target;
        try {
            Class<?> contextClass = context.getClass();
            ValueInvokeHolder localHolder = this.invokeHolder;
            if (contextClass == localHolder.targetClass) {
                target = localHolder.valueInvoke.getValue(context);
            } else {
                // create
                ValueInvoke valueInvoke;
                if (context instanceof Map) {
                    valueInvoke = new MapImpl(key);
                } else {
                    GetterInfo getterInfo = null;
                    try {
                        getterInfo = ClassStrucWrap.get(contextClass).getGetterInfo(key);
                        if (getterInfo.isSupportedUnsafe()) {
                            if (getterInfo.isPrimitive()) {
                                valueInvoke = new ObjectPrimitiveFieldImpl(getterInfo);
                            } else {
                                valueInvoke = new ObjectFieldImpl(getterInfo);
                            }
                        } else {
                            valueInvoke = new ObjectGetterImpl(getterInfo);
                        }
                    } catch (RuntimeException runtimeException) {
                        if (getterInfo == null) {
                            throw new IllegalArgumentException(
                                    String.format("Unresolved field '%s' from %s", key, contextClass.toString()));
                        }
                        throw runtimeException;
                    }
                }
                target = valueInvoke.getValue(context);
                invokeHolder = new ValueInvokeHolder(contextClass, valueInvoke);
            }
            return target;
        } catch (Throwable throwable) {
            if (context == null) {
                throw new IllegalArgumentException(
                        String.format("Unresolved field '%s' for target obj is null or not exist in the context ",
                                key));
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved field '%s' from %s", key, context.getClass()), throwable);
        }
    }

    ElVariableInvoker index(int index) {
        this.index = index;
        return this;
    }

    /**
     * 获取当前变量在变量值缓存数组中的下标
     *
     * @return 变量值缓存数组下标
     */
    public int getIndex() {
        return this.index;
    }

    ElVariableInvoker tailIndex(int tailIndex) {
        this.tailIndex = tailIndex;
        return this;
    }

    /**
     * 获取当前变量所属调用链末级节点的下标
     *
     * @return 末级节点在变量值缓存数组中的下标
     */
    public int getTailIndex() {
        return this.tailIndex;
    }

    public void internKey() {
        this.key = key.intern();
    }

    /**
     * 判断当前节点是否为变量调用链的末级节点
     *
     * @return 是末级节点时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isTail() {
        return tail;
    }

    /**
     * 设置当前节点是否为变量调用链的末级节点
     *
     * @param tail 是否为末级节点
     */
    public void setTail(boolean tail) {
        this.tail = tail;
    }

    /**
     * 判断当前节点是否为子表达式变量（形如 {@code a[b + 1]} 中的中括号部分）
     *
     * @return 普通变量节点固定返回 {@code false}，子表达式变量实现返回 {@code true}
     */
    public boolean isChildEl() {
        return false;
    }

//    @Override
//    public int size() {
//        return 1;
//    }

    /**
     * Variable root node
     */
    static final class RootImpl extends ElVariableInvoker {
        public RootImpl(String key) {
            super(key);
        }

        @Override
        public Object invokeDirect(Object context) {
            return invokeValue(context);
        }

        @Override
        public Object invokeDirect(Map context) {
            return context.get(key);
        }

        @Override
        public Object invoke(Object entityContext, Object[] variableValues) {
            Object value = variableValues[index];
            if (value != null) {
                return value;
            }
            return variableValues[index] = invokeValue(entityContext);
        }

        @Override
        public Object invoke(Map mapContext, Object[] variableValues) {
            Object value = variableValues[index];
            if (value != null) {
                return value;
            }
            return variableValues[index] = mapContext.get(key);
        }

        @Override
        public String toString() {
            return key;
        }
    }

    /**
     * 子表达式变量
     */
    static final class ChildElImpl extends ElVariableInvoker {
        protected Expression el;

        ChildElImpl(String key) {
            super(key);
        }

        ChildElImpl(String key, ElVariableInvoker parent) {
            super(key, parent);
            // lazy is required
            // this.expression = Expression.parse(key);
        }

        private void parseChildEl() {
            if (el == null) {
                synchronized (this) {
                    if (el == null) {
                        el = Expression.parse(key);
                    }
                }
            }
        }

        @Override
        public Object invokeDirect(Object context) {
            parseChildEl();
            Object realKey = el.evaluate(context);
            Object target = parent != null ? parent.invokeDirect(context) : context;
            if (realKey instanceof String) {
                return ObjectUtils.getAttrValue(target, (String) realKey);
            } else if (realKey instanceof Long) {
                // 数组key
                int indexValue = ((Long) realKey).intValue();
                return CollectionUtils.getElement(target, indexValue);
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        @Override
        public Object invokeDirect(Map context) {
            parseChildEl();
            Object realKey = el.evaluate(context);
            if (parent == null) {
                return context.get(String.valueOf(realKey));
            } else {
                Object target = parent.invokeDirect(context);
                if (realKey instanceof String) {
                    return ObjectUtils.getAttrValue(target, (String) realKey);
                } else if (realKey instanceof Long) {
                    // 数组key
                    int indexValue = ((Long) realKey).intValue();
                    return CollectionUtils.getElement(target, indexValue);
                }
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        @Override
        public Object invoke(Object context, Object[] variableValues) {
            Object value = variableValues[index];
            if (value != null) {
                return value;
            }
            parseChildEl();
            Object realKey = el.evaluate(context);
            Object parentContext = parent != null ? parent.invoke(context, variableValues) : context;
            if (realKey instanceof String) {
                return variableValues[index] = ObjectUtils.getAttrValue(parentContext, (String) realKey);
            } else if (realKey instanceof Long) {
                // 数组key
                int indexValue = ((Long) realKey).intValue();
                return variableValues[index] = CollectionUtils.getElement(parentContext, indexValue);
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        @Override
        public Object invoke(Map context, Object[] variableValues) {
            Object value = variableValues[index];
            if (value != null) {
                return value;
            }
            parseChildEl();
            Object realKey = el.evaluate(context);
            Object parentContext = parent != null ? parent.invoke(context, variableValues) : context;
            if (realKey instanceof String) {
                return variableValues[index] = ObjectUtils.getAttrValue(parentContext, (String) realKey);
            } else if (realKey instanceof Long) {
                // 数组key
                int indexValue = ((Long) realKey).intValue();
                return variableValues[index] = CollectionUtils.getElement(parentContext, indexValue);
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        @Override
        public Object invokeCurrent(Map globalContext, Object parentContext, Object[] variableValues) {
            parseChildEl();
            Object realKey = el.evaluate(globalContext);
            if (realKey instanceof String) {
                return variableValues[index] = ObjectUtils.getAttrValue(parentContext, (String) realKey);
            } else if (realKey instanceof Long) {
                // 数组key
                int indexValue = ((Long) realKey).intValue();
                return variableValues[index] = CollectionUtils.getElement(parentContext, indexValue);
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        @Override
        public Object invokeCurrent(Object globalContext, Object parentContext, Object[] variableValues) {
            parseChildEl();
            Object realKey = el.evaluate(globalContext);
            if (realKey instanceof String) {
                return variableValues[index] = ObjectUtils.getAttrValue(parentContext, (String) realKey);
            } else if (realKey instanceof Long) {
                // 数组key
                int indexValue = ((Long) realKey).intValue();
                return variableValues[index] = CollectionUtils.getElement(parentContext, indexValue);
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        @Override
        public Object invokeCurrent(Object globalContext, Object parentContext) {
            parseChildEl();
            Object realKey = el.evaluate(globalContext);
            if (realKey instanceof String) {
                return ObjectUtils.getAttrValue(parentContext, (String) realKey);
            } else if (realKey instanceof Long) {
                // 数组key
                int indexValue = ((Long) realKey).intValue();
                return CollectionUtils.getElement(parentContext, indexValue);
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        @Override
        public Object invokeCurrent(Map globalContext, Object parentContext) {
            parseChildEl();
            Object realKey = el.evaluate(globalContext);
            if (realKey instanceof String) {
                return ObjectUtils.getAttrValue(parentContext, (String) realKey);
            } else if (realKey instanceof Long) {
                // 数组key
                int indexValue = ((Long) realKey).intValue();
                return CollectionUtils.getElement(parentContext, indexValue);
            }
            throw new IllegalArgumentException(
                    String.format("Unresolved child el: '%s', val: %s ", key, String.valueOf(realKey)));
        }

        public boolean isChildEl() {
            return true;
        }

        @Override
        public String toString() {
            return parent.toString() + "[" + key + "]";
        }
    }

    interface ValueInvoke<T> {
        Object getValue(T context);
    }

    static final class MapImpl implements ValueInvoke<Map<String, Object>> {
        private final String key;

        MapImpl(String key) {
            this.key = key;
        }

        @Override
        public Object getValue(Map<String, Object> context) {
            return context.get(key);
        }

        @Override
        public String toString() {
            return "[" + key + "]";
        }
    }

    static final class ObjectGetterImpl implements ValueInvoke {
        private final GetterInfo getterInfo;

        ObjectGetterImpl(GetterInfo getterInfo) {
            getterInfo.getClass();
            this.getterInfo = getterInfo;
        }

        @Override
        public Object getValue(Object context) {
            return SECURE_TRUSTED_ACCESS.get(getterInfo, context); // getterInfo.invoke(context);
        }
    }

    static final class ObjectFieldImpl implements ValueInvoke {
        private final GetterInfo getterInfo;
        private final long fieldOffset;

        ObjectFieldImpl(GetterInfo getterInfo) {
            getterInfo.getClass();
            this.getterInfo = getterInfo;
            this.fieldOffset = getterInfo.getFieldOffset();
        }

        @Override
        public Object getValue(Object context) {
            return SECURE_TRUSTED_ACCESS.getObjectValue(context, fieldOffset);
        }
    }

    static final class ObjectPrimitiveFieldImpl implements ValueInvoke {
        private final ReflectConsts.PrimitiveType primitiveType;
        private final long fieldOffset;

        ObjectPrimitiveFieldImpl(GetterInfo getterInfo) {
            getterInfo.getClass();
            this.primitiveType = getterInfo.getPrimitiveType();
            this.fieldOffset = getterInfo.getFieldOffset();
        }

        @Override
        public Object getValue(Object context) {
            return SECURE_TRUSTED_ACCESS.getPrimitiveValue(primitiveType, context, fieldOffset);
        }
    }

    static class ValueInvokeHolder {
        static final ValueInvokeHolder Empty = new ValueInvokeHolder(null, null);

        final Class targetClass;
        final ValueInvoke valueInvoke;

        public ValueInvokeHolder(Class targetClass, ValueInvoke valueInvoke) {
            this.targetClass = targetClass;
            this.valueInvoke = valueInvoke;
        }
    }

}
