package com.alianga.jkit.reflect;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 默认包（无名包）类的反射包装回归测试。
 *
 * <p>历史实现中 {@link ClassStrucWrap#get(Class)} 在 {@code checkClassStructure} 里直接调用
 * {@code sourceClass.getPackage().getName()}，而默认包/匿名类的 {@link Class#getPackage()} 在部分运行时
 * 下返回 {@code null}，会抛出 {@link NullPointerException}（已通过 JDK 8 探针复现该路径）。本测试确保默认包
 * POJO 经 {@link ClassStrucWrap} 包装时不会抛异常，并能正确识别 getter/setter、通过 {@code invokePublic} 调用。
 */
public class ReflectNullPackageTest {

    @Test
    public void defaultPackageClassWrapMustNotThrow() throws Exception {
        Class<?> beanClass = Class.forName("DefaultPackageBean");

        ClassStrucWrap wrap = ClassStrucWrap.get(beanClass);
        assertNotNull("默认包类的结构包装不应为 null", wrap);
        // 默认包类不应被误判为 JDK 内置模块
        assertFalse(wrap.isJavaBuiltInModule());
        assertNotNull(wrap.getGetterInfo("name"));
        assertNotNull(wrap.getSetterInfo("age"));
        assertTrue("默认包类应能识别出 getter", wrap.getGetterInfos().size() >= 2);
        assertTrue("默认包类应能识别出 name 的 setter", wrap.setterNames().contains("name"));
    }

    @Test
    public void defaultPackageClassInvokePublic() throws Exception {
        Class<?> beanClass = Class.forName("DefaultPackageBean");
        Object instance = beanClass.getDeclaredConstructor().newInstance();
        Method setName = beanClass.getMethod("setName", String.class);
        setName.invoke(instance, "tom");

        ClassStrucWrap wrap = ClassStrucWrap.get(beanClass);
        Object value = wrap.invokePublic(instance, "getName", new Object[]{});
        assertEquals("tom", value);
    }
}
