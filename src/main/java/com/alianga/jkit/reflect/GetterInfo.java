package com.alianga.jkit.reflect;

import com.alianga.jkit.exception.InvokeReflectException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * 字段取值器，封装一个字段的反射信息，并在可用时通过 Unsafe 直接按内存偏移量读取字段值。
 */
public class GetterInfo {
    private Field field;
    private long fieldOffset = -1;
    private boolean fieldPrimitive;
    private ReflectConsts.PrimitiveType primitiveType;
    private GenericParameterizedType<?> genericParameterizedType;
    private String name;
    private String underlineName;
    private Map<Class<? extends Annotation>, Annotation> annotations;
    private ReflectConsts.ClassCategory classCategory;
    private boolean record;

    /**
     * 获取字段返回类型所属的类别，首次调用后结果会被缓存。
     *
     * @return 字段返回类型对应的类别
     */
    public ReflectConsts.ClassCategory getClassCategory() {
        if (classCategory != null) {
            return classCategory;
        }
        return classCategory = ReflectConsts.getClassCategory(getReturnType());
    }

    /**
     * 判断给定对象是否为该字段声明类的实例。
     *
     * @param target 待判断的对象
     * @return 是声明类的实例时返回 {@code true}，否则返回 {@code false}
     */
    public final boolean isInstance(Object target) {
        return field.getDeclaringClass().isInstance(target);
    }

    /**
     * 读取目标对象上该字段的值，能使用 Unsafe 时按内存偏移量读取，否则退化为反射读取。
     *
     * @param target 待读取的目标对象
     * @return 字段的当前值，字段为 {@code null} 时返回 {@code null}
     * @throws SecurityException 使用 Unsafe 读取但目标对象不是声明类实例时抛出
     */
    public final Object invoke(Object target) {
        if (fieldOffset > -1) {
            if (isInstance(target)) {
                if (fieldPrimitive) {
                    return primitiveType.get(target, fieldOffset);
                } else {
                    return UnsafeHelper.getObjectValue(target, fieldOffset);
                }
            } else {
                throw new SecurityException("invoke error: parameter mismatch");
            }
        }
        return invokeObjectValue(target);
    }

    /**
     * 通过反射读取目标对象上该字段的值。
     *
     * @param target 待读取的目标对象
     * @return 字段的当前值
     * @throws InvokeReflectException 反射读取失败时抛出
     */
    protected Object invokeObjectValue(Object target) {
        try {
            return field.get(target);
        } catch (Exception e) {
            throw new InvokeReflectException(e);
        }
    }

    Object invokeInternal(Object target) {
        if (fieldOffset > -1) {
            if (fieldPrimitive) {
                return primitiveType.getValue(target, fieldOffset);
            } else {
                return UnsafeHelper.UNSAFE.getObject(target, fieldOffset);
            }
        }
        return invokeObjectValue(target);
    }

    void setField(Field field) {
        this.field = field;
        try {
            this.fieldPrimitive = field.getType().isPrimitive();
            this.fieldOffset = UnsafeHelper.objectFieldOffset(field);
            if (this.fieldPrimitive) {
                this.primitiveType = ReflectConsts.PrimitiveType.typeOf(field.getType());
            }
        } catch (Throwable throwable) {
            this.fieldOffset = -1;
        }
    }

    Field getField() {
        return this.field;
    }

    /**
     * 判断是否已绑定字段。
     *
     * @return 已绑定字段时返回 {@code true}，否则返回 {@code false}
     */
    public boolean existField() {
        return field != null;
    }

    void setName(String name) {
        this.name = name;
    }

    /**
     * 获取字段名称。
     *
     * @return 字段名称，未设置时为 {@code null}
     */
    public String getName() {
        return name;
    }

    /**
     * 获取字段上的注解集合。
     *
     * @return 以注解类型为键的注解集合，未设置时为 {@code null}
     */
    public Map<Class<? extends Annotation>, Annotation> getAnnotations() {
        return annotations;
    }

    void setAnnotations(Map<Class<? extends Annotation>, Annotation> annotations) {
        this.annotations = annotations;
    }

    /**
     * 获取字段上指定类型的注解。
     *
     * @param annotationType 注解类型
     * @return 对应的注解实例，字段未标注该注解时返回 {@code null}
     */
    public Annotation getAnnotation(Class<? extends Annotation> annotationType) {
        return annotations.get(annotationType);
    }

    /**
     * 获取取值的返回类型。
     *
     * @return 字段声明的类型
     */
    public Class<?> getReturnType() {
        return field.getType();
    }

    void setUnderlineName(String underlineName) {
        this.underlineName = underlineName;
    }

    /**
     * 获取字段名的下划线形式。
     *
     * @return 下划线风格的字段名，未设置时为 {@code null}
     */
    public String getUnderlineName() {
        return underlineName;
    }

    // 是否通过getter方法
    /**
     * 判断是否通过 getter 方法取值。
     *
     * @return 字段取值器固定返回 {@code false}，由 getter 方法实现的子类返回 {@code true}
     */
    public boolean isMethod() {
        return false;
    }

    // 是否可访问
    /**
     * 判断字段是否可直接访问。
     *
     * @return 字段非 private 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isAccess() {
        return !Modifier.isPrivate(field.getModifiers());
    }

    // 是否public
    /**
     * 判断字段是否为 public。
     *
     * @return 字段为 public 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isPublic() {
        return Modifier.isPublic(field.getModifiers());
    }

    /**
     * 判断字段类型是否为基本类型。
     *
     * @return 字段为基本类型时返回 {@code true}，否则返回 {@code false}
     */
    public boolean isPrimitive() {
        return fieldPrimitive;
    }

    /**
     * 获取字段的泛型化类型信息。
     *
     * @return 字段的泛型化类型信息，未设置时为 {@code null}
     */
    public GenericParameterizedType<?> getGenericParameterizedType() {
        return genericParameterizedType;
    }

    void setGenericParameterizedType(GenericParameterizedType<?> genericParameterizedType) {
        this.genericParameterizedType = genericParameterizedType;
    }

    /**
     * 获取取值使用的方法名。
     *
     * @return 字段取值器固定返回 {@code null}，由 getter 方法实现的子类返回其方法名
     */
    public String getMethodName() {
        return null;
    }

    /**
     * 判断是否支持通过 Unsafe 按内存偏移量取值。
     *
     * @return 已成功获取字段偏移量时返回 {@code true}，否则返回 {@code false}
     */
    public final boolean isSupportedUnsafe() {
        return fieldOffset > -1;
    }

    /**
     * 获取基本类型的类型描述。
     *
     * @return 基本类型描述，字段非基本类型或未初始化时为 {@code null}
     */
    public final ReflectConsts.PrimitiveType getPrimitiveType() {
        return primitiveType;
    }

    /**
     * 获取字段在对象中的内存偏移量。
     *
     * @return 字段的内存偏移量，不支持 Unsafe 取值时返回 -1
     */
    public final long getFieldOffset() {
        return fieldOffset;
    }

    /**
     * 生成访问该字段的源码片段，用于编译期代码生成。
     *
     * @return record 类型返回 {@code 字段名()} 形式的访问器调用，否则返回字段名
     */
    public String generateCode() {
        if (record) {
            // use by Record access
            return field.getName() + "()";
        }
        return field.getName();
    }

    void setRecord(boolean record) {
        this.record = record;
    }

}
