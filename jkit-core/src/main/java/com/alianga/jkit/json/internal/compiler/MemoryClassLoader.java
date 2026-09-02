package com.alianga.jkit.json.internal.compiler;

/**
 * @time 2021/9/21 0:17
 */
public class MemoryClassLoader extends ClassLoader {
    private MemoryJavaFileObject javaFileObject;

    /**
     * 以系统类加载器为父加载器，构造加载指定内存字节码文件对象的类加载器。
     *
     * @param javaFileObject 保存编译后字节码的内存文件对象
     */
    public MemoryClassLoader(MemoryJavaFileObject javaFileObject) {
        this(javaFileObject, ClassLoader.getSystemClassLoader());
    }

    /**
     * 构造加载指定内存字节码文件对象的类加载器。
     *
     * @param javaFileObject 保存编译后字节码的内存文件对象
     * @param parentLoader   父类加载器
     */
    public MemoryClassLoader(MemoryJavaFileObject javaFileObject, ClassLoader parentLoader) {
        super(parentLoader);
        this.javaFileObject = javaFileObject;
    }

    /**
     * 构造不绑定内存字节码文件对象的类加载器，只能通过 {@link #loadClass(String, byte[])} 加载类。
     */
    public MemoryClassLoader() {
    }

    /**
     * 构造不绑定内存字节码文件对象、并指定父加载器的类加载器。
     *
     * @param parentLoader 父类加载器
     */
    public MemoryClassLoader(ClassLoader parentLoader) {
        super(parentLoader);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        if (javaFileObject == null) {
            return null;
        }
        byte[] codeBytes = javaFileObject.getBytes();
        return defineClass(name, codeBytes, 0, codeBytes.length);
    }

    /**
     * 直接使用给定的字节码定义并加载类。
     *
     * @param name      类的全限定名
     * @param codeBytes 类的字节码内容
     * @return 定义后得到的 Class 对象
     */
    public Class<?> loadClass(String name, byte[] codeBytes) {
        return defineClass(name, codeBytes, 0, codeBytes.length);
    }
}
