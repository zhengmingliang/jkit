package com.alianga.jkit.json.internal.utils;

import com.alianga.jkit.jdk.JdkApiAgent;
import com.alianga.jkit.reflect.UnsafeHelper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@link JdkApiAgent} 在 JDK9 及以上版本的实现，乘法高位直接委托 {@code Math.multiplyHigh}，
 * 并尝试用 {@link MethodHandle} 调用 {@code java.lang.StringCoding#hasNegatives} 做非 ASCII 字节的快速判定，
 * 取不到该内部方法时回退父类的纯 java 实现
 */
public final class JdkApiAgentJdk9Plus extends JdkApiAgent {
    static final MethodHandle HAS_NEGATIVES_HANDLE;

    /**
     * 构造 JDK9+ 的 JDK API 代理实现
     */
    public JdkApiAgentJdk9Plus() {
    }

    public long multiplyHigh(long x, long y) {
        return Math.multiplyHigh(x, y);
    }

    public long multiplyHighKaratsuba(long x, long y) {
        return Math.multiplyHigh(x, y);
    }

    public boolean hasNegatives(byte[] bytes, int offset, int len) {
        if (HAS_NEGATIVES_HANDLE != null) {
            try {
                return (boolean) HAS_NEGATIVES_HANDLE.invoke(bytes, offset, len);
            } catch (Throwable var5) {
            }
        }

        return super.hasNegatives(bytes, offset, len);
    }

    static {
        MethodHandles.Lookup lookup = null;

        try {
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            UnsafeHelper.setAccessible(field);
            lookup = (MethodHandles.Lookup) field.get((Object) null);
        } catch (Throwable var6) {
        }

        if (lookup == null) {
            lookup = MethodHandles.lookup();
        }

        MethodHandle hasNegativesMethodHandle = null;

        try {
            Class<?> scClass = Class.forName("java.lang.StringCoding");
            MethodHandles.Lookup caller = lookup.in(scClass);
            Method hasNegativesMethod = scClass.getMethod("hasNegatives", byte[].class, Integer.TYPE, Integer.TYPE);
            UnsafeHelper.setAccessible(hasNegativesMethod);
            hasNegativesMethodHandle = caller.unreflect(hasNegativesMethod);
        } catch (Throwable var5) {
        }

        HAS_NEGATIVES_HANDLE = hasNegativesMethodHandle;
    }
}
