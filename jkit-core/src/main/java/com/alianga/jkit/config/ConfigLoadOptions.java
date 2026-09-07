package com.alianga.jkit.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ConfigPropertyResolver} 的加载选项。
 *
 * <p>默认行为对齐 Spring Boot 外部化配置：搜索
 * {@code classpath:/}、{@code classpath:/config/}、{@code file:./}、{@code file:./config/}，
 * 并依次叠加 profile、环境变量、JVM 系统属性。</p>
 */
public final class ConfigLoadOptions {
    static final String[] DEFAULT_NAMES = {"bootstrap", "application"};

    static final String[] DEFAULT_EXTENSIONS = {".properties", ".yml", ".yaml"};

    static final String[] DEFAULT_LOCATIONS = {
            "classpath:/",
            "classpath:/config/",
            "file:./",
            "file:./config/"
    };

    private String[] names = DEFAULT_NAMES.clone();

    private String[] extensions = DEFAULT_EXTENSIONS.clone();

    private List<String> locations;

    private boolean enableProfiles = true;

    private boolean enableSystemProperties = true;

    private boolean enableEnvironment = true;

    private boolean resolvePlaceholders = true;

    private ClassLoader classLoader;

    private String[] activeProfiles;

    private ConfigLoadOptions() {
        this.locations = copyLocations(DEFAULT_LOCATIONS);
    }

    /**
     * Spring Boot 默认选项：{@code bootstrap} + {@code application}，含 profile / env / 系统属性。
     *
     * @return 新的选项实例
     */
    public static ConfigLoadOptions defaults() {
        return new ConfigLoadOptions();
    }

    /**
     * 仅使用指定配置名（不再加载 bootstrap/application），仍走默认搜索路径。
     *
     * <p>适用于非 Spring 应用按自己的文件名读取配置，例如 {@code my-app.yml}。</p>
     *
     * @param names 配置基名，不含扩展名
     * @return 新的选项实例
     */
    public static ConfigLoadOptions of(String... names) {
        return new ConfigLoadOptions().names(names);
    }

    /**
     * 设置配置基名，对应 Spring Boot 的 {@code spring.config.name}。
     *
     * @param names 配置基名，如 {@code application}、{@code my-app}
     * @return this
     */
    public ConfigLoadOptions names(String... names) {
        if (names == null || names.length == 0) {
            throw new IllegalArgumentException("config names must not be empty");
        }
        this.names = names.clone();
        return this;
    }

    /**
     * 设置文件扩展名，按从低到高的优先级排列（后者覆盖前者）。
     *
     * <p>默认 {@code .properties} &lt; {@code .yml} &lt; {@code .yaml}，与 Spring Boot 一致。</p>
     *
     * @param extensions 扩展名，需包含点号
     * @return this
     */
    public ConfigLoadOptions extensions(String... extensions) {
        if (extensions == null || extensions.length == 0) {
            throw new IllegalArgumentException("extensions must not be empty");
        }
        this.extensions = extensions.clone();
        return this;
    }

    /**
     * 替换默认搜索路径。
     *
     * <p>路径以 {@code classpath:} 或 {@code file:} 开头；不带前缀的按文件系统目录处理
     * （内部规范成 {@code file:}）。{@code ~} / {@code ~/...} 展开为 {@code user.home}。
     * 后者优先级更高。
     *
     * @param locations 搜索路径
     * @return this
     */
    public ConfigLoadOptions locations(String... locations) {
        this.locations = copyLocations(locations);
        return this;
    }

    /**
     * 追加搜索路径（不替换默认路径），后者优先级更高。
     *
     * <p>适合在 Spring 默认搜索之上再加本机目录，例如 {@code ~/jkit}。
     * {@code ~} 会展开；空白项跳过；与已有路径重复的不加。
     *
     * @param locations 搜索路径，{@code classpath:} / {@code file:} / 裸文件系统目录
     * @return this
     * @since 2.0.1
     */
    public ConfigLoadOptions addLocation(String... locations) {
        for (String location : copyLocations(locations)) {
            if (!this.locations.contains(location)) {
                this.locations.add(location);
            }
        }
        return this;
    }

    /**
     * 追加一个文件系统目录作为搜索路径。若 {@code path} 是已存在的文件，则用其父目录。
     *
     * @param path 目录，或该目录下某个配置文件
     * @return this
     * @since 2.0.1
     */
    public ConfigLoadOptions addLocation(File path) {
        if (path == null) {
            throw new IllegalArgumentException("locations must not be empty");
        }
        File dir = path.isFile() ? path.getParentFile() : path;
        if (dir == null) {
            throw new IllegalArgumentException("locations must not be empty");
        }
        return addLocation(dir.getAbsolutePath());
    }

    /**
     * 是否加载 {@code {name}-{profile}.yml} 等 profile 专属文件。
     *
     * @param enableProfiles true 启用
     * @return this
     */
    public ConfigLoadOptions enableProfiles(boolean enableProfiles) {
        this.enableProfiles = enableProfiles;
        return this;
    }

    /**
     * 是否用 JVM 系统属性覆盖文件中的同名配置。优先级高于环境变量。
     *
     * @param enableSystemProperties true 启用
     * @return this
     */
    public ConfigLoadOptions enableSystemProperties(boolean enableSystemProperties) {
        this.enableSystemProperties = enableSystemProperties;
        return this;
    }

    /**
     * 是否用操作系统环境变量覆盖文件中的配置。{@code SERVER_PORT} 会映射为 {@code server.port}。
     *
     * @param enableEnvironment true 启用
     * @return this
     */
    public ConfigLoadOptions enableEnvironment(boolean enableEnvironment) {
        this.enableEnvironment = enableEnvironment;
        return this;
    }

    /**
     * 是否解析 {@code ${...}} 占位符。
     *
     * @param resolvePlaceholders true 启用
     * @return this
     */
    public ConfigLoadOptions resolvePlaceholders(boolean resolvePlaceholders) {
        this.resolvePlaceholders = resolvePlaceholders;
        return this;
    }

    /**
     * 指定 classpath 资源使用的 ClassLoader，默认取上下文 ClassLoader。
     *
     * @param classLoader ClassLoader
     * @return this
     */
    public ConfigLoadOptions classLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        return this;
    }

    /**
     * 强制激活指定 profile，不再从 JVM / 环境变量 / 配置文件探测。
     *
     * @param activeProfiles profile 名
     * @return this
     */
    public ConfigLoadOptions activeProfiles(String... activeProfiles) {
        this.activeProfiles = activeProfiles == null ? null : activeProfiles.clone();
        return this;
    }

    String[] getNames() {
        return names;
    }

    String[] getExtensions() {
        return extensions;
    }

    List<String> getLocations() {
        return Collections.unmodifiableList(locations);
    }

    boolean isEnableProfiles() {
        return enableProfiles;
    }

    boolean isEnableSystemProperties() {
        return enableSystemProperties;
    }

    boolean isEnableEnvironment() {
        return enableEnvironment;
    }

    boolean isResolvePlaceholders() {
        return resolvePlaceholders;
    }

    ClassLoader getClassLoader() {
        return classLoader;
    }

    String[] getActiveProfiles() {
        return activeProfiles;
    }

    private static List<String> copyLocations(String... locations) {
        if (locations == null || locations.length == 0) {
            throw new IllegalArgumentException("locations must not be empty");
        }
        List<String> out = new ArrayList<String>(locations.length);
        for (String location : locations) {
            String normalized = normalizeLocation(location);
            if (normalized != null && !out.contains(normalized)) {
                out.add(normalized);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("locations must not be empty");
        }
        return out;
    }

    /**
     * 规范化搜索路径：去空白、展开 {@code ~}、裸路径补 {@code file:} 前缀。
     *
     * @param location 原始路径
     * @return 规范化结果；空白或 {@code null} 返回 {@code null}
     */
    static String normalizeLocation(String location) {
        if (location == null) {
            return null;
        }
        String value = location.trim();
        if (value.isEmpty()) {
            return null;
        }
        boolean classpath = startsWithIgnoreCase(value, "classpath:");
        boolean filePrefix = startsWithIgnoreCase(value, "file:");
        String path = classpath ? value.substring("classpath:".length())
                : (filePrefix ? value.substring("file:".length()) : value);
        path = expandUserHome(path);
        if (classpath) {
            return "classpath:" + path;
        }
        return "file:" + path;
    }

    private static String expandUserHome(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        if ("~".equals(path)) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
