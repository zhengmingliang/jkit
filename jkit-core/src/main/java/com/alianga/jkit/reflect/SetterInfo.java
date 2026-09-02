package com.alianga.jkit.reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * 属性写入器（setter）的元信息，默认通过 unsafe 字段偏移量直接写值。
 *
 * <p> 按宿主字段类型分为普通对象实现、纯反射的 {@code FieldImpl} 与基本类型的 {@code PrimitiveImpl}。
 */
public class SetterInfo {
    // field of class
    Field field;
    // field memory offset
    long fieldOffset = -1;
    private String name;
    private Class<?> parameterType;

    private Class<?> actualTypeArgument;

    // 索引位置, 当宿主类型为record时或者通过构造方法设置对象的属性值时使用
    private int index;

    /**
     * 泛型信息
     * Generic information
     */
    private GenericParameterizedType genericParameterizedType;

    /**
     * 非实例化类型
     * （排除集合或者map后的接口或者抽象类，无法通过new创建实例对象）
     */
    private boolean nonInstanceType;

    // 注解集合
    private Map<Class<? extends Annotation>, Annotation> annotations;

    // 是否存在默认值
    private Boolean existDefault;
    private boolean fieldDisabled;

    /**
     * 根据字段创建对应的属性写入器元信息。
     *
     * @param field 目标字段
     * @return 新创建的写入器；字段为基本类型时返回基本类型实现，否则返回默认实现
     */
    public static SetterInfo fromField(Field field) {
        boolean primitive = field.getType().isPrimitive();
        SetterInfo setterInfo = primitive ? new PrimitiveImpl() : new SetterInfo();
        setterInfo.setField(field);
        return setterInfo;
    }

    /**
     * 判断目标对象是否为该字段声明类的实例。
     *
     * @param target 待判断的目标对象
     * @return 是声明类（或其子类）的实例时返回 {@code true}，否则返回 {@code false}
     */
    public final boolean isInstance(Object target) {
        return field.getDeclaringClass().isInstance(target);
    }

    /**
     * 将值写入目标对象的对应属性，写入前校验目标对象与值的类型。
     *
     * @param target 目标对象，不能为 {@code null}
     * @param value  待写入的值，可为 {@code null}
     */
    public void invoke(Object target, Object value) {
        target.getClass();
        if ((parameterType.isInstance(value) || value == null) && isInstance(target)) {
            invokeInternal(target, value);
        } else {
            throw new SecurityException("invoke error: parameter mismatch");
        }
    }

    void invokeInternal(Object target, Object value) {
        UnsafeHelper.UNSAFE.putObject(target, fieldOffset, value);
    }

    /**
     * 获取属性名。
     *
     * @return 属性名，未设置时为 {@code null}
     */
    public final String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }

    /**
     * 获取属性的声明类型（写入时的参数类型）。
     *
     * @return 属性类型，未设置时为 {@code null}
     */
    public final Class<?> getParameterType() {
        return parameterType;
    }

    void setParameterType(Class<?> parameterType) {
        this.parameterType = parameterType;
    }

    /**
     * 获取属性泛型的实际类型参数，如集合的元素类型。
     *
     * @return 实际类型参数，非泛型属性或未解析时为 {@code null}
     */
    public final Class<?> getActualTypeArgument() {
        return actualTypeArgument;
    }

    void setActualTypeArgument(Class<?> actualTypeArgument) {
        this.actualTypeArgument = actualTypeArgument;
    }

    /**
     * 判断属性类型是否为非实例化类型（排除集合与 map 之后的接口或抽象类，无法直接 new 出实例）。
     *
     * @return 是非实例化类型时返回 {@code true}，否则返回 {@code false}
     */
    public final boolean isNonInstanceType() {
        return nonInstanceType;
    }

    void setNonInstanceType(boolean nonInstanceType) {
        this.nonInstanceType = nonInstanceType;
    }

    void setAnnotations(Map<Class<? extends Annotation>, Annotation> annotations) {
        this.annotations = annotations;
    }

    void setField(Field field) {
        this.field = field;
        try {
            this.fieldOffset = UnsafeHelper.objectFieldOffset(field);
        } catch (Throwable throwable) {
            this.fieldOffset = -1;
        }
    }

    /**
     * 获取属性的泛型信息。
     *
     * @return 泛型信息，未设置时为 {@code null}
     */
    public final GenericParameterizedType getGenericParameterizedType() {
        return genericParameterizedType;
    }

    void setGenericParameterizedType(GenericParameterizedType genericParameterizedType) {
        this.genericParameterizedType = genericParameterizedType;
    }

    Object getDefaultFieldValue(Object instance) {
        try {
            if (existDefault == Boolean.FALSE) {
                return null;
            }
            Object fieldValue = getFieldValue(instance);
            this.existDefault = fieldValue != null;
            if (fieldValue != null) {
                return fieldValue;
            }
        } catch (Exception e) {
            existDefault = Boolean.FALSE;
        }
        return null;
    }

    Object getFieldValue(Object instance) throws IllegalAccessException {
        return UnsafeHelper.getObjectValue(instance, fieldOffset);
    }

    /**
     * 获取属性上指定类型的注解。
     *
     * @param annotationType 注解类型
     * @return 对应的注解实例，属性上不存在该注解时返回 {@code null}
     */
    public final Annotation getAnnotation(Class<? extends Annotation> annotationType) {
        return annotations.get(annotationType);
    }

    /**
     * 判断该写入器是否基于 setter 方法。
     *
     * @return 基于字段写入的实现固定返回 {@code false}，基于方法的子类返回 {@code true}
     */
    public boolean isMethod() {
        return false;
    }

    /**
     * 判断宿主字段是否为 private。
     *
     * @return 字段带有 private 修饰符时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isPrivate() {
        return Modifier.isPrivate(field.getModifiers());
    }

    /**
     * 获取属性的索引位置，宿主类型为 record 或通过构造方法赋值时使用。
     *
     * @return 属性的索引位置，默认为 0
     */
    public final int getIndex() {
        return index;
    }

    void setIndex(int index) {
        this.index = index;
    }

    void setFieldDisabled(boolean disabled) {
        this.fieldDisabled = disabled;
    }

    /**
     * 判断该属性是否被禁用（不参与序列化/反序列化）。
     *
     * @return 已禁用时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isFieldDisabled() {
        return fieldDisabled;
    }

    static final class FieldImpl extends SetterInfo {
        void setField(Field field) {
            this.field = field;
        }

        public void invoke(Object target, Object value) {
            invokeInternal(target, value);
        }

        Object getFieldValue(Object instance) throws IllegalAccessException {
            return field.get(instance);
        }

        void invokeInternal(Object target, Object value) {
            try {
                field.set(target, value);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static final class PrimitiveImpl extends SetterInfo {
        private ReflectConsts.PrimitiveType primitiveType;

        public void invoke(Object target, Object value) {
            if (isInstance(target)) {
                primitiveType.put(target, fieldOffset, value);
            } else {
                throw new SecurityException("invoke error: parameter mismatch");
            }
        }

        @Override
        void invokeInternal(Object target, Object value) {
            primitiveType.putValue(target, fieldOffset, value);
        }

        Object getFieldValue(Object instance) {
            return primitiveType.get(instance, fieldOffset);
        }

        void setField(Field field) {
            super.setField(field);
            this.primitiveType = ReflectConsts.PrimitiveType.typeOf(field.getType());
        }

        public ReflectConsts.PrimitiveType getPrimitiveType() {
            return primitiveType;
        }
    }

    /**
     * 获取宿主字段的内存偏移量。
     *
     * @return 字段的内存偏移量，无法获取（unsafe 不可用等）时为 {@code -1}
     */
    public final long getFieldOffset() {
        return fieldOffset;
    }

    /**
     * 获取属性对应的基本类型。
     *
     * @return 基本类型；非基本类型的实现固定返回 {@code null}
     */
    public ReflectConsts.PrimitiveType getPrimitiveType() {
        return null;
    }
}
