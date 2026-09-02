package com.alianga.jkit.json.internal.utils;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 类扫描与 jar 动态加载工具类（内部使用）。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class ClassUtils {
    /**
     * 把指定目录下的所有 jar 追加到系统类加载器的搜索路径中，加载失败的 jar 仅打印堆栈不中断流程。
     *
     * @param lib jar 所在目录，目录不存在或不是目录时什么都不做
     */
    public static void loadJars(String lib) {
        File jarLib = new File(lib);
        if (jarLib.exists() && jarLib.isDirectory()) {
            for (File file : jarLib.listFiles()) {
                if (!file.isDirectory() && file.getName().toLowerCase().endsWith(".jar")) {
                    try {
                        loadJar(file);
                    } catch (Exception e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * 通过反射调用 {@code URLClassLoader#addURL} 把指定 jar 追加到系统类加载器中。
     *
     * @param jarFile 待加载的 jar 文件，文件不存在时仅输出提示并返回
     * @throws NoSuchMethodException     反射查找 addURL 方法失败时抛出
     * @throws SecurityException         安全管理器禁止反射访问时抛出
     * @throws IllegalAccessException    反射方法不可访问时抛出
     * @throws IllegalArgumentException  反射调用参数不匹配时抛出
     * @throws InvocationTargetException 反射调用目标方法内部抛出异常时抛出
     * @throws MalformedURLException     jar 路径无法转换为 URL 时抛出
     */
    public static void loadJar(File jarFile)
            throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException, MalformedURLException {
        if (!jarFile.exists()) {
            System.out.println(jarFile.getAbsolutePath() + " is not exist ");
            return;
        }
        URLClassLoader loader = (URLClassLoader) ClassLoader.getSystemClassLoader();
        Method _addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
        _addURL.setAccessible(true);
        _addURL.invoke(loader, jarFile.toURI().toURL());
    }

    /**
     * 按文件路径把指定 jar 追加到系统类加载器中。
     *
     * @param jarFileName jar 文件路径
     * @throws NoSuchMethodException     反射查找 addURL 方法失败时抛出
     * @throws SecurityException         安全管理器禁止反射访问时抛出
     * @throws IllegalAccessException    反射方法不可访问时抛出
     * @throws IllegalArgumentException  反射调用参数不匹配时抛出
     * @throws InvocationTargetException 反射调用目标方法内部抛出异常时抛出
     * @throws MalformedURLException     jar 路径无法转换为 URL 时抛出
     */
    public static void loadJar(String jarFileName)
            throws NoSuchMethodException, SecurityException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException, MalformedURLException {
        loadJar(new File(jarFileName));
    }

    /**
     * 加载 jar 并取出其中的全部类。
     *
     * @param jarFile 待扫描的 jar 文件
     * @return jar 中可成功加载的类集合，扫描异常时返回已收集到的部分结果
     */
    public static Set<Class<?>> getClassesFromJar(File jarFile) {
        return getClassesFromJar(jarFile, Object.class);
    }

    /**
     * 加载 jar 并取出其中所有指定父类的子类。
     *
     * @param jarFile     待扫描的 jar 文件
     * @param parentClass 父类或父接口，仅收集其子类型且排除自身
     * @return 满足条件的类集合，扫描异常时返回已收集到的部分结果
     */
    public static Set<Class<?>> getClassesFromJar(File jarFile, Class<?> parentClass) {
        Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        JarFile jar = null;
        try {
            loadJar(jarFile);
            jar = new JarFile(jarFile);
            Enumeration<JarEntry> entry = jar.entries();

            JarEntry jarEntry;
            String jarEntryName;
            String className;
            Class<?> clazz;
            while (entry.hasMoreElements()) {
                jarEntry = entry.nextElement();
                jarEntryName = jarEntry.getName();
                if (jarEntryName.charAt(0) == '/') {
                    jarEntryName = jarEntryName.substring(1);
                }
                if (jarEntry.isDirectory() || !jarEntryName.endsWith(".class")) {
                    continue;
                }
                className = jarEntryName.substring(0, jarEntryName.length() - 6);
                clazz = loadClass(className.replace("/", "."));
                if (clazz != null && parentClass != clazz && parentClass.isAssignableFrom(clazz)) {
                    classes.add(clazz);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return classes;
    }

    /**
     * 按 jar 文件路径加载并取出其中的全部类。
     *
     * @param jarFile jar 文件路径
     * @return jar 中可成功加载的类集合，扫描异常时返回已收集到的部分结果
     */
    public static Set<Class<?>> getClassesFromJar(String jarFile) {
        return getClassesFromJar(new File(jarFile));
    }

    /**
     * 扫描指定包下所有指定父类的子类，默认同时扫描 jar。
     *
     * @param packageName 包名，空字符串表示 classpath 根目录
     * @param parentClass 父类或父接口，仅收集其子类型且排除自身
     * @return 满足条件的类集合，未匹配到时返回空集合
     */
    public static Set<Class<?>> getClasses(String packageName, Class<?> parentClass) {
        return getClasses(packageName, parentClass, true);
    }

    /**
     * 获取packageName下面所有的Class
     *
     * @param packageName 包名，空字符串表示 classpath 根目录
     * @param parentClass 父类或父接口，仅收集其子类型且排除自身
     * @param scanJar     是否扫描jar
     * @return 满足条件的类集合，未匹配到时返回空集合
     */
    public static Set<Class<?>> getClasses(String packageName, Class<?> parentClass, boolean scanJar) {
        Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        String pkgDirName = packageName.replace('.', '/');
        try {
            Enumeration<URL> urls = ClassUtils.class.getClassLoader().getResources(pkgDirName);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    String filePath = URLDecoder.decode(url.getFile(), "UTF-8");
                    findClasses(packageName, filePath, classes, parentClass);
                } else if (scanJar && "jar".equals(protocol)) {
                    JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile();
                    findClasses(packageName, jar, classes, parentClass);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return classes;
    }

    /**
     * 获取packageName下面所有的Class
     *
     * @param packageName       包名，空字符串表示 classpath 根目录
     * @param parentClass       父类或父接口，仅收集其子类型且排除自身
     * @param annotationType    注解类型，不为 {@code null} 时要求类上直接或间接标注该注解
     * @param isFilterInterface 是否接口
     * @param scanJar           是否扫描jar包
     * @return 满足条件的类集合，未匹配到时返回空集合
     */
    public static Set<Class<?>> getClassesOfAnnotationType(String packageName, Class<?> parentClass,
                                                           Class<?> annotationType, boolean isFilterInterface,
                                                           boolean scanJar) {
        Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        String pkgDirName = packageName.replace('.', '/');
        try {
            Enumeration<URL> urls = ClassUtils.class.getClassLoader().getResources(pkgDirName);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    String filePath = URLDecoder.decode(url.getFile(), "UTF-8");
                    findClasses(packageName, filePath, classes, parentClass, annotationType, isFilterInterface);
                } else if (scanJar && "jar".equals(protocol)) {
                    JarFile jar = ((JarURLConnection) url.openConnection()).getJarFile();
                    findClasses(packageName, jar, classes, parentClass, annotationType, isFilterInterface);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return classes;
    }

    /**
     * 获取packageName下面所有的Class
     *
     * @param packageName 包名，空字符串表示 classpath 根目录
     * @return 该包下可成功加载的类集合，未匹配到时返回空集合
     */
    public static Set<Class<?>> getClasses(String packageName) {
        return getClasses(packageName, Object.class);
    }

    /**
     * 获取packageName下面所有的Class
     *
     * @param packageName 包名，空字符串表示 classpath 根目录
     * @param scanJar     是否扫描 jar 中的类
     * @return 该包下可成功加载的类集合，未匹配到时返回空集合
     */
    public static Set<Class<?>> getClasses(String packageName, boolean scanJar) {
        return getClasses(packageName, Object.class, scanJar);
    }

    /**
     * 获取classes资源目录下面的类
     *
     * @param parentCls 父类或父接口，仅收集其子类型且排除自身
     * @return classes 目录下满足条件的类集合，不扫描 jar
     */
    public static Set<Class<?>> getClassesOfClassPath(Class<?> parentCls) {
        return getClasses("", parentCls, false);
    }

    /**
     * 获取资源路径下面的类
     *
     * @param parentCls         父类
     * @param annotationType    注解类
     * @param isFilterInterface 是否为接口
     * @param scanJar           是否扫描jar
     * @return 资源路径下满足条件的类集合，未匹配到时返回空集合
     */
    public static Set<Class<?>> getClassesOfClassPath(Class<?> parentCls, Class<?> annotationType,
                                                      boolean isFilterInterface, boolean scanJar) {
        return getClassesOfAnnotationType("", parentCls, annotationType, isFilterInterface, scanJar);
    }

    /**
     * 扫描指定packages下面满足条件的类集合
     *
     * @param packages          待扫描的包名数组，为空时退化为扫描 classpath 根目录且不扫描 jar
     * @param parentCls         父类或父接口，仅收集其子类型且排除自身
     * @param annotationType    注解类型，不为 {@code null} 时要求类上直接或间接标注该注解
     * @param isFilterInterface 是否只保留接口类型
     * @return 满足条件的类集合，未匹配到时返回空集合
     */
    public static Set<Class<?>> findClasses(String[] packages, Class<?> parentCls,
                                            Class<? extends Annotation> annotationType, boolean isFilterInterface) {
        Set<Class<?>> clsSet = new LinkedHashSet<Class<?>>();
        if (CollectionUtils.isEmpty(packages)) {
            clsSet.addAll(ClassUtils.getClassesOfClassPath(parentCls, annotationType, isFilterInterface, false));
        } else {
            for (String scanPckage : packages) {
                clsSet.addAll(
                        ClassUtils.getClassesOfAnnotationType(scanPckage, parentCls, annotationType, isFilterInterface,
                                true)
                );
            }
        }
        return clsSet;
    }

    private static void findClasses(String packageName, String packagePath, Set<Class<?>> classes, Class<?> parentClass,
                                    Class annotationType, boolean isFilterInterface) {
        File packageDir = new File(packagePath);
        if (!packageDir.exists() || !packageDir.isDirectory()) {
            return;
        }
        final String classSuf = ".class";
        File[] files = packageDir.listFiles(pathname ->
                pathname.isDirectory() || pathname.getName().endsWith(classSuf));

        if (files == null || files.length == 0) {
            return;
        }
        String className;
        for (File file : files) {
            if (file.isDirectory()) {
                String pckName = null;
                if (packageName.equals("")) {
                    pckName = file.getName();
                } else {
                    pckName = packageName + "." + file.getName();
                }
                findClasses(pckName,
                        packagePath.endsWith("/") ? packagePath + file.getName() : packagePath + "/" + file.getName(),
                        classes, parentClass, annotationType, isFilterInterface);
                continue;
            }
            className = file.getName();
            className = className.substring(0, className.length() - classSuf.length());
            Class<?> clazz = loadClass(packageName + "." + className);
            if (clazz != null && parentClass != clazz && parentClass.isAssignableFrom(clazz)) {
//                // 是否有注解
//                if (annotationType != null && clazz.getAnnotation(annotationType) == null) {
//                    continue;
//                }
//                // 是否接口
//                if (isFilterInterface && !clazz.isFilterInterface()) {
//                    continue;
//                }
                boolean validated = validate(clazz, annotationType, isFilterInterface);
                if (validated) {
                    classes.add(clazz);
                }
            }
        }
    }

    private static void findClasses(String packageName, String packagePath, Set<Class<?>> classes,
                                    Class<?> parentClass) {
        findClasses(packageName, packagePath, classes, parentClass, null, false);
    }

    private static void findClasses(String packageName, JarFile jar, Set<Class<?>> classes, Class<?> parentClass,
                                    Class annotationType, boolean isFilterInterface) {
        String packageDir = packageName.replace(".", "/");
        Enumeration<JarEntry> entries = jar.entries();
        JarEntry jarEntry;
        String jarEntryName;
        String className;
        Class<?> clazz;
        while (entries.hasMoreElements()) {
            jarEntry = entries.nextElement();
            jarEntryName = jarEntry.getName();
            if (jarEntryName.charAt(0) == '/') {
                jarEntryName = jarEntryName.substring(1);
            }
            String classSuf = ".class";
            if (jarEntry.isDirectory() || !jarEntryName.startsWith(packageDir) || !jarEntryName.endsWith(classSuf)) {
                continue;
            }
            className = jarEntryName.substring(0, jarEntryName.length() - classSuf.length());
            clazz = loadClass(className.replace("/", "."));
            if (clazz != null && parentClass != clazz && parentClass.isAssignableFrom(clazz)) {
//                // 是否有注解
//                if (annotationType != null && clazz.getAnnotation(annotationType) == null) {
//                    continue;
//                }
//                // 是否接口
//                if (isFilterInterface && !clazz.isFilterInterface()) {
//                    continue;
//                }
                boolean validated = validate(clazz, annotationType, isFilterInterface);
                if (validated) {
                    classes.add(clazz);
                }
            }
        }
    }

    private static boolean validate(Class<?> clazz, Class annotationType, boolean isFilterInterface) {
        try {
            // 是否接口
            if (isFilterInterface && !clazz.isInterface()) {
                return false;
            }
            // 是否有注解
            if (annotationType != null) {
                Annotation annotation = clazz.getAnnotation(annotationType);
                if (annotation != null) {
                    return true;
                }
                Annotation[] annotations = clazz.getDeclaredAnnotations();
                if (annotations != null) {
                    for (Annotation anno : annotations) {
                        if (anno.annotationType().getAnnotation(annotationType) != null) {
                            return true;
                        }
                    }
                }
                return false;
            }
            return true;
        } catch (Throwable throwable) {
        }
        return false;
    }

    private static void findClasses(String packageName, JarFile jar, Set<Class<?>> classes, Class<?> parentClass) {
        findClasses(packageName, jar, classes, parentClass, null, false);
    }

    private static Class<?> loadClass(String className) {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable e) {
//            System.err.println(e.getMessage());
        }
        return null;
    }

}
