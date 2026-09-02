package com.alianga.jkit.json.internal.compiler;

import com.alianga.jkit.json.internal.utils.EnvUtils;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.util.*;

/**
 * @time 2024/3/16 22:17
 */
public class JDKCompiler {
    private static final JavaCompiler JAVA_COMPILER = ToolProvider.getSystemJavaCompiler();
    private static final StandardJavaFileManager FILE_MANAGER = JAVA_COMPILER.getStandardFileManager(null, null, null);

    /**
     * 在内存中编译单个java源码并加载为类，使用系统类加载器作为父加载器
     *
     * @param sourceObject 待编译的java源码对象
     * @return 编译并加载后的类对象；编译失败时抛出 {@link RuntimeException}
     */
    public static synchronized Class<?> compileJavaSource(JavaSourceObject sourceObject) {
        return compileJavaSource(sourceObject, ClassLoader.getSystemClassLoader());
    }

    /**
     * 在内存中编译单个java源码并加载为类
     *
     * @param sourceObject 待编译的java源码对象
     * @param classLoader 加载编译结果时使用的父类加载器
     * @return 编译并加载后的类对象；编译失败时抛出 {@link RuntimeException}
     */
    public static synchronized Class<?> compileJavaSource(JavaSourceObject sourceObject, ClassLoader classLoader) {
        MemoryJavaFileManager javaFileManager = new MemoryJavaFileManager(FILE_MANAGER);
        try {
            List<String> options = null;
            if (EnvUtils.JDK_16_PLUS) {
                options = Arrays.asList("-encoding", "UTF-8", "-XDuseUnsharedTable", "-Xlint:-options");
            } else {
                options = Arrays.asList("-encoding", "UTF-8", "-XDuseUnsharedTable");
            }
            JavaFileObject javaFileObject =
                    javaFileManager.createJavaFileObject(sourceObject.className + ".java", sourceObject.javaSourceCode);
            JavaCompiler.CompilationTask task = JAVA_COMPILER.getTask(null, javaFileManager, null,
                    options, null,
                    Collections.singletonList(javaFileObject));
            boolean bl = task.call();
            if (bl) {
                MemoryJavaFileObject memoryJavaFileObject = javaFileManager.getLastMemoryJavaFileObject();
                MemoryClassLoader memoryClassLoader = new MemoryClassLoader(memoryJavaFileObject, classLoader);
                return memoryClassLoader.loadClass(sourceObject.packageName + "." + sourceObject.className);
            } else {
                throw new Exception("ERROR");
            }
        } catch (Throwable e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("compile exception :" + e.getMessage(), e);
        } finally {
            try {
                javaFileManager.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 在内存中批量编译java源码并加载为类，使用系统类加载器作为父加载器
     *
     * @param sourceObjects 待编译的java源码对象数组
     * @return 编译并加载后的类对象列表；编译失败时抛出 {@link RuntimeException}
     */
    public static List<Class<?>> compileJavaSources(JavaSourceObject... sourceObjects) {
        return compileJavaSources(ClassLoader.getSystemClassLoader(), sourceObjects);
    }

    /**
     * 在内存中批量编译java源码并加载为类
     *
     * @param classLoader 加载编译结果时使用的父类加载器
     * @param sourceObjects 待编译的java源码对象数组
     * @return 编译并加载后的类对象列表；编译失败时抛出 {@link RuntimeException}
     */
    public static synchronized List<Class<?>> compileJavaSources(ClassLoader classLoader,
                                                                 JavaSourceObject... sourceObjects) {
        MemoryJavaFileManager javaFileManager = new MemoryJavaFileManager(FILE_MANAGER, sourceObjects);
        try {
            List<Class<?>> targetList = new ArrayList<Class<?>>();
            List<JavaFileObject> javaFileObjects = new ArrayList<JavaFileObject>();
            for (JavaSourceObject javaSourceObject : sourceObjects) {
                javaFileObjects.add(javaFileManager.createJavaFileObject(javaSourceObject.className + ".java",
                        javaSourceObject.javaSourceCode));
            }
            JavaCompiler.CompilationTask task = JAVA_COMPILER.getTask(null, javaFileManager, null,
                    Arrays.asList(/*"-d", classPath, */"-encoding", "UTF-8", "-XDuseUnsharedTable"), null,
                    javaFileObjects);
            boolean bl = task.call();
            if (bl) {
                Map<String, MemoryJavaFileObject> memoryJavaFileObject = javaFileManager.getFileObjectMap();
                MemoryClassLoader memoryClassLoader = new MemoryClassLoader(classLoader);
                Set<Map.Entry<String, MemoryJavaFileObject>> entrySet = memoryJavaFileObject.entrySet();
                for (Map.Entry<String, MemoryJavaFileObject> entry : entrySet) {
                    targetList.add(memoryClassLoader.loadClass(entry.getKey(), entry.getValue().getBytes()));
                }
                return targetList;
            } else {
                throw new Exception("ERROR");
            }
        } catch (Throwable e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("compile java fail :" + e.getMessage(), e);
        } finally {
            try {
                javaFileManager.close();
            } catch (IOException ignored) {
            }
        }
    }

}
