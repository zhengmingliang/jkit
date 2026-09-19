package com.alianga.jkit.json;

import com.alianga.jkit.json.internal.compiler.JavaSourceObject;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 默认包（无名包）POJO 的序列化回归测试。
 *
 * <p>默认包类的 {@link Class#getPackage()} 与 {@link Class#getCanonicalName()} 均返回 {@code null}。
 * 历史上 {@link JSONPojoSerializerCodeGen} 在生成序列化代码时，
 * {@code pojoClass.getPackage().getName()} 会 NPE，且 {@code pojoClass.getCanonicalName()} 为 null 时
 * 会生成 {@code extends JSONPojoSerializer<null>} 这种非法源码。本测试确保默认包 POJO 的代码生成
 * 不再出现 {@code null} 类型引用与空 {@code package ;} 语句，且能正确序列化为 JSON。
 */
public class DefaultPackageSerializationTest {

    @Test
    public void generatedSourceMustBeValidForDefaultPackageBean() throws Exception {
        Class<?> beanClass = Class.forName("DefaultPackageBean");

        JavaSourceObject src = JSONPojoSerializer.generateJavaCodeSource(beanClass, false);
        assertFalse("默认包类代码生成不应含有 <null> 类型引用", src.javaSourceCode.contains("<null>"));
        assertFalse("默认包类代码生成不应含有空 package 语句", src.javaSourceCode.contains("package ;"));
    }

    @Test
    public void serializeDefaultPackageBean() throws Exception {
        Class<?> beanClass = Class.forName("DefaultPackageBean");
        Object instance = beanClass.getDeclaredConstructor().newInstance();
        beanClass.getMethod("setName", String.class).invoke(instance, "tom");
        beanClass.getMethod("setAge", int.class).invoke(instance, 18);

        String json = JSON.toJsonString(instance);
        assertTrue("序列化结果应包含 name 字段", json.contains("\"name\":\"tom\""));
        assertTrue("序列化结果应包含 age 字段", json.contains("\"age\":18"));
    }
}
