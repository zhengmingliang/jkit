/*
 * Created by 郑明亮 on 2021/5/11 17:39.
 */

//

package com.alianga.jkit;

/**
 * Class处理工具类
 *
 * @author 郑明亮
 * @version 1.0
 * @date 2021/5/11 17:39
 * @email mpro@vip.qq.com
 */
public class ClassUtils {
    /**
     * 返回要使用的默认 ClassLoader：通常是线程上下文 ClassLoader（如果可用）；加载 ClassUtils 类的 ClassLoader 将用作后备。
     * 如果您打算在显然更喜欢非空 ClassLoader 引用的场景中使用线程上下文 ClassLoader，
     * 请调用此方法：例如，用于类路径资源加载（但不一定适用于 Class.forName，它也接受空类加载器引用）
     *
     * @return 默认类加载器（仅当系统类加载器不可访问时为空）
     */
    public static ClassLoader getDefaultClassLoader() {
        ClassLoader cl = null;
        try {
            cl = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ex) {
            // Cannot access thread context ClassLoader - falling back...
        }
        if (cl == null) {
            // No thread context class loader -> use class loader of this class.
            cl = ClassUtils.class.getClassLoader();
            if (cl == null) {
                // getClassLoader() returning null indicates the bootstrap ClassLoader
                try {
                    cl = ClassLoader.getSystemClassLoader();
                } catch (Throwable ex) {
                    // Cannot access system ClassLoader - oh well, maybe the caller can live with null...
                }
            }
        }
        return cl;
    }

    /**
     * 判断指定类在当前运行环境中是否可用，常用于可选依赖探测
     *
     * <p>只做加载与链接检查，<b>不会触发目标类的静态初始化</b>，因此不会产生副作用。
     * 除了类不存在，依赖链缺失导致的 {@link NoClassDefFoundError} 等链接错误也一并视为不可用。</p>
     *
     * @param className 全限定类名，可为 {@code null}
     * @return 类可被加载时返回 {@code true}；为 {@code null}、空串或无法加载时返回 {@code false}
     */
    public static boolean isClassExist(String className) {
        if (className == null || className.trim().isEmpty()) {
            return false;
        }
        try {
            // initialize 传 false，避免触发目标类的静态初始化块
            Class.forName(className, false, getDefaultClassLoader());
            return true;
        } catch (Throwable ignored) {
            // 类不存在、依赖缺失（NoClassDefFoundError）、版本不匹配等都等价于「不可用」
            return false;
        }
    }
}
