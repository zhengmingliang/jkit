package com.alianga.jkit.config;

import java.util.ArrayList;
import java.util.Arrays;
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

    private List<String> locations = new ArrayList<String>(Arrays.asList(DEFAULT_LOCATIONS));

    private boolean enableProfiles = true;

    private boolean enableSystemProperties = true;

    private boolean enableEnvironment = true;

    private boolean resolvePlaceholders = true;

    private ClassLoader classLoader;

    private String[] activeProfiles;

    private ConfigLoadOptions() {
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
     * 替换默认搜索路径。路径以 {@code classpath:} 或 {@code file:} 开头。
     *
     * @param locations 搜索路径，后者优先级更高
     * @return this
     */
    public ConfigLoadOptions locations(String... locations) {
        if (locations == null || locations.length == 0) {
            throw new IllegalArgumentException("locations must not be empty");
        }
        this.locations = new ArrayList<String>(Arrays.asList(locations));
        return this;
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
}
