package com.alianga.jkit.reflect;

import java.util.Arrays;
import java.util.List;

/**
 * 受信任的内部访问入口基类。
 *
 * <p> 仅允许 {@code TRUSTED_ACCESS_NAME_LIST} 中登记的内部实现类继承，其他实现在构造时会抛出
 * {@link UnsupportedOperationException}，以此限制反射与 unsafe 取值能力的暴露范围。
 */
public abstract class SecureTrustedAccess {
    private final String implTrustedAccessName;
    static final List<String> TRUSTED_ACCESS_NAME_LIST = Arrays.asList(
            "com.alianga.jkit.json.internal.utils.UtilsSecureTrustedAccess",
            "com.alianga.jkit.beans.UtilsSecureTrustedAccess",
            "com.alianga.jkit.expression.ElSecureTrustedAccess",
            "com.alianga.jkit.json.JSONSecureTrustedAccess"
);

    /**
     * 构造受信任访问实例，并校验子类是否在受信任实现名单内
     */
    protected SecureTrustedAccess() {
        String implTrustedAccessName = this.getClass().getName();
        if (!TRUSTED_ACCESS_NAME_LIST.contains(implTrustedAccessName)) {
            throw new UnsupportedOperationException();
        }
        this.implTrustedAccessName = implTrustedAccessName;
    }

    /**
     * 通过 setter 信息为目标对象的属性赋值
     *
     * @param setterInfo 属性写入信息
     * @param target 目标对象
     * @param value 要写入的属性值
     */
    public final void set(SetterInfo setterInfo, Object target, Object value) {
        setterInfo.invokeInternal(target, value);
    }

    /**
     * 通过 getter 信息读取目标对象的属性值
     *
     * @param getterInfo 属性读取信息
     * @param target 目标对象
     * @return 读取到的属性值
     */
    public final Object get(GetterInfo getterInfo, Object target) {
        return getterInfo.invokeInternal(target);
    }

    /**
     * 按字段内存偏移量读取目标对象的引用类型字段值
     *
     * @param target 目标对象
     * @param fieldOffset 字段的内存偏移量
     * @return 该字段当前的引用值，字段值为空时返回 {@code null}
     */
    public final Object getObjectValue(Object target, long fieldOffset) {
        return UnsafeHelper.UNSAFE.getObject(target, fieldOffset);
    }

    /**
     * 按字段内存偏移量读取目标对象的基本类型字段值
     *
     * @param primitiveType 字段的基本类型
     * @param target 目标对象
     * @param fieldOffset 字段的内存偏移量
     * @return 装箱后的基本类型字段值
     */
    public final Object getPrimitiveValue(ReflectConsts.PrimitiveType primitiveType, Object target, long fieldOffset) {
        return primitiveType.getValue(target, fieldOffset);
    }

    /**
     * 读取 setter 对应字段在目标对象上已存在的默认值
     *
     * @param setterInfo 属性写入信息
     * @param target 目标对象
     * @return 字段上已存在的默认值，字段无默认值或读取失败时返回 {@code null}
     */
    public final Object getSetterDefault(SetterInfo setterInfo, Object target) {
        return setterInfo.getDefaultFieldValue(target);
    }

}
