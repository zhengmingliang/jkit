package com.alianga.jkit.sql.entity;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 扫描包下的实体类（不依赖 Spring）。
 * 认 {@link SqlTable}、JPA {@code Entity}、MyBatis-Plus {@code TableName}/{@code TableId}。
 *
 * <p>对标 data-set {@code EntityScanner}：给定 basePackage，返回实体 {@code Class} 列表。</p>
 *
 * @author 郑明亮
 * @since 2.0.1
 */
public final class SqlEntityScanner {
    private final List<String> basePackages;
    private final ClassLoader classLoader;

    /**
     * @param basePackage 根包，不可空
     */
    public SqlEntityScanner(String basePackage) {
        this(Collections.singletonList(basePackage), null);
    }

    /**
     * @param basePackages 根包列表
     */
    public SqlEntityScanner(List<String> basePackages) {
        this(basePackages, null);
    }

    /**
     * @param basePackages 根包
     * @param classLoader 类加载器，null 则用上下文 / 本类加载器
     */
    public SqlEntityScanner(List<String> basePackages, ClassLoader classLoader) {
        if (basePackages == null || basePackages.isEmpty()) {
            throw new IllegalArgumentException("basePackage 不能为空");
        }
        this.basePackages = new ArrayList<String>(basePackages);
        this.classLoader = classLoader;
    }

    /**
     * @return 实体类列表（具体类且带实体注解）
     */
    public List<Class<?>> scan() {
        ClassLoader cl = classLoader();
        List<Class<?>> out = new ArrayList<Class<?>>(8);
        for (int i = 0; i < basePackages.size(); i++) {
            String pkg = basePackages.get(i);
            if (pkg == null || pkg.trim().isEmpty()) {
                continue;
            }
            scanPackage(pkg.trim(), cl, out);
        }
        return out;
    }

    private void scanPackage(String pkg, ClassLoader cl, List<Class<?>> out) {
        String path = pkg.replace('.', '/');
        Enumeration<URL> urls;
        try {
            urls = cl.getResources(path);
        } catch (IOException e) {
            throw new IllegalStateException("scan package failed: " + pkg, e);
        }
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String protocol = url.getProtocol();
            if ("file".equals(protocol)) {
                scanDir(toFile(url), pkg, cl, out);
            } else if ("jar".equals(protocol)) {
                scanJar(url, path, cl, out);
            }
        }
    }

    private void scanDir(File dir, String pkg, ClassLoader cl, List<Class<?>> out) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            if (f.isDirectory()) {
                scanDir(f, pkg + "." + f.getName(), cl, out);
            } else if (f.getName().endsWith(".class") && f.getName().indexOf('$') < 0) {
                String simple = f.getName().substring(0, f.getName().length() - 6);
                load(pkg + "." + simple, cl, out);
            }
        }
    }

    private void scanJar(URL url, String pkgPath, ClassLoader cl, List<Class<?>> out) {
        JarFile jar = null;
        try {
            JarURLConnection conn = (JarURLConnection) url.openConnection();
            jar = conn.getJarFile();
            String prefix = pkgPath.endsWith("/") ? pkgPath : pkgPath + "/";
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".class") || name.indexOf('$') >= 0) {
                    continue;
                }
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                load(className, cl, out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("scan jar failed: " + url, e);
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
        }
    }

    private void load(String className, ClassLoader cl, List<Class<?>> out) {
        try {
            Class<?> type = Class.forName(className, false, cl);
            if (SqlEntityMapper.isEntity(type)) {
                out.add(type);
            }
        } catch (ClassNotFoundException ignored) {
            // 跳过无法加载的
        } catch (NoClassDefFoundError ignored) {
            // 跳过依赖缺失
        }
    }

    private ClassLoader classLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl == null ? SqlEntityScanner.class.getClassLoader() : cl;
    }

    private static File toFile(URL url) {
        try {
            return new File(URLDecoder.decode(url.getFile(), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return new File(url.getFile());
        }
    }
}
