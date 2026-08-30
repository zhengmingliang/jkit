package com.alianga.jkit.json.internal.compiler;

import javax.tools.*;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * @time 2021/9/21 0:06
 */
public class MemoryJavaFileManager extends ForwardingJavaFileManager {
    private Map<String, MemoryJavaFileObject> fileObjectMap = new HashMap<String, MemoryJavaFileObject>();
    private MemoryJavaFileObject lastMemoryJavaFileObject;

    /**
     * 创建 ForwardingJavaFileManager 的新实例。
     *
     * @param fileManager delegate to this file manager
     */
    public MemoryJavaFileManager(JavaFileManager fileManager) {
        super(fileManager);
    }

    /**
     * 创建 ForwardingJavaFileManager 的新实例。
     *
     * @param fileManager delegate to this file manager
     * @param javaSourceObjects 需要编译的源码对象，会按其规范类名预先登记内存输出文件对象
     */
    public MemoryJavaFileManager(JavaFileManager fileManager, JavaSourceObject... javaSourceObjects) {
        super(fileManager);
        for (JavaSourceObject javaSourceObject : javaSourceObjects) {
            fileObjectMap.put(javaSourceObject.canonicalName,
                    new MemoryJavaFileObject(javaSourceObject.canonicalName, JavaFileObject.Kind.SOURCE));
        }
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                               FileObject sibling) throws IOException {
        MemoryJavaFileObject memoryJavaFileObject = fileObjectMap.get(className);
        if (memoryJavaFileObject == null) {
            memoryJavaFileObject = new MemoryJavaFileObject(className, JavaFileObject.Kind.SOURCE);
            fileObjectMap.put(className, memoryJavaFileObject);
        }
        return lastMemoryJavaFileObject = memoryJavaFileObject;
    }

    /**
     * 用源码字符串创建一个内存中的输入文件对象，供编译器读取。
     *
     * @param name 源码对应的类名，用于构造虚拟 URI
     * @param code java 源码内容
     * @return 内存中的源码文件对象
     */
    public JavaFileObject createJavaFileObject(String name, String code) {
        return new MemoryInputJavaFileObject(name, code);
    }

    /**
     * 获取最近一次通过 getJavaFileForOutput 分配的内存输出文件对象。
     *
     * @return 最近一次分配的内存文件对象，尚未分配过时返回 null
     */
    public MemoryJavaFileObject getLastMemoryJavaFileObject() {
        return lastMemoryJavaFileObject;
    }

    /**
     * 获取类名到内存输出文件对象的映射。
     *
     * @return 类名到内存文件对象的映射，返回的是内部 Map 本身
     */
    public Map<String, MemoryJavaFileObject> getFileObjectMap() {
        return fileObjectMap;
    }

    /***
     * 输入
     *
     */
    class MemoryInputJavaFileObject extends SimpleJavaFileObject {
        final String code;

        MemoryInputJavaFileObject(String name, String code) {
            super(URI.create("string:///" + name), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public String getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
