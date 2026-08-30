package com.alianga.jkit;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ClassUtils} 的类加载探测测试。
 */
public class ClassUtilsTest {

    /** 静态初始化块是否被执行过的标记，用于验证探测不会触发目标类初始化 */
    static volatile boolean initialized = false;

    /**
     * 带静态初始化块的样本类，被加载并初始化时会翻转 {@link #initialized}。
     */
    static class WithStaticInitializer {
        static {
            initialized = true;
        }
    }

    @Test
    public void getDefaultClassLoader_returnsUsableLoader() {
        assertNotNull(ClassUtils.getDefaultClassLoader());
    }

    @Test
    public void isClassExist_detectsPresentClasses() {
        assertTrue(ClassUtils.isClassExist("java.lang.String"));
        assertTrue(ClassUtils.isClassExist("com.alianga.jkit.ClassUtils"));
        assertTrue("嵌套类用 $ 分隔",
                ClassUtils.isClassExist("com.alianga.jkit.ClassUtilsTest$WithStaticInitializer"));
    }

    @Test
    public void isClassExist_returnsFalseForMissingOrIllegalNames() {
        assertFalse(ClassUtils.isClassExist("com.alianga.jkit.AbsolutelyNoSuchClass"));
        assertFalse(ClassUtils.isClassExist(null));
        assertFalse(ClassUtils.isClassExist(""));
        assertFalse(ClassUtils.isClassExist("   "));
        // 非法类名会让 Class.forName 抛非 ClassNotFoundException 的异常，同样应被吞掉
        assertFalse(ClassUtils.isClassExist("not a class name"));
    }

    /**
     * 探测只做加载与链接，不触发静态初始化，因此不会产生副作用。
     */
    @Test
    public void isClassExist_doesNotTriggerStaticInitializer() {
        initialized = false;
        assertTrue(ClassUtils.isClassExist("com.alianga.jkit.ClassUtilsTest$WithStaticInitializer"));
        assertFalse("探测不应执行目标类的静态初始化块", initialized);

        // 真正主动使用该类时才会初始化，以此反证上面的断言有效
        assertNotNull(new WithStaticInitializer());
        assertTrue(initialized);
    }
}
