package com.alianga.jkit.config;

import com.alianga.jkit.ClassUtils;
import com.alianga.jkit.StringUtils;
import com.alianga.jkit.convert.ConvertUtils;
import com.alianga.jkit.log.Log;
import com.alianga.jkit.yaml.YamlDocument;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * 按 Spring Boot 外部化配置优先级读取 YAML / properties。
 *
 * <p>默认搜索（后者覆盖前者）：</p>
 * <ol>
 *     <li>{@code classpath:/} 与 {@code classpath:/config/} 下的 {@code bootstrap}、{@code application}</li>
 *     <li>对应的 {@code {name}-{profile}} 文件</li>
 *     <li>{@code file:./} 与 {@code file:./config/} 下的同名文件</li>
 *     <li>操作系统环境变量（{@code SERVER_PORT} → {@code server.port}）</li>
 *     <li>JVM 系统属性</li>
 * </ol>
 *
 * <p>同一目录内扩展名优先级：{@code .properties} &lt; {@code .yml} &lt; {@code .yaml}。</p>
 *
 * <p>非 Spring 应用可通过 {@link #load(String...)} 指定自己的配置名，
 * 或通过 {@link #loadFile(String...)} 直接读取若干文件。</p>
 */
public final class ConfigPropertyResolver {
    private static final Log log = Log.get(ConfigPropertyResolver.class);

    private static final int LIST_SIZE_LIMIT = 10000;

    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final String FILE_PREFIX = "file:";

    private static volatile ConfigPropertyResolver INSTANCE;

    private final ConfigLoadOptions options;

    private final Map<String, Object> properties = new LinkedHashMap<String, Object>();

    private ConfigPropertyResolver(ConfigLoadOptions options) {
        this.options = options == null ? ConfigLoadOptions.defaults() : options;
    }

    /**
     * 按 Spring Boot 默认规则加载 {@code bootstrap} 与 {@code application}。
     *
     * @return 新的解析器实例（不复用缓存）
     */
    public static ConfigPropertyResolver load() {
        return load(ConfigLoadOptions.defaults());
    }

    /**
     * 使用自定义配置基名加载，搜索路径与 profile 规则与 Spring Boot 相同。
     *
     * <p>例如 {@code load("my-app")} 会查找 {@code my-app.yml}、{@code my-app-{profile}.yml} 等。</p>
     *
     * @param names 配置基名，不含扩展名
     * @return 新的解析器实例
     */
    public static ConfigPropertyResolver load(String... names) {
        return load(ConfigLoadOptions.of(names));
    }

    /**
     * 按指定选项加载。
     *
     * @param options 加载选项
     * @return 新的解析器实例
     */
    public static ConfigPropertyResolver load(ConfigLoadOptions options) {
        ConfigPropertyResolver resolver = new ConfigPropertyResolver(options);
        resolver.loadAll();
        return resolver;
    }

    /**
     * 直接读取一个或多个配置文件，不做 Spring 目录分层和 profile 探测。
     *
     * <p>每个参数先按 classpath 资源查找，找不到再按文件系统路径查找。
     * 后出现的文件覆盖先出现的同名 key。默认仍解析占位符，但不会叠加 env / 系统属性。</p>
     *
     * @param fileNames 文件名或路径，如 {@code jdbc.yml}、{@code /opt/app/redis.properties}
     * @return 新的解析器实例
     */
    public static ConfigPropertyResolver loadFile(String... fileNames) {
        if (fileNames == null || fileNames.length == 0) {
            throw new IllegalArgumentException("fileNames must not be empty");
        }
        ConfigLoadOptions options = ConfigLoadOptions.defaults()
                .enableProfiles(false)
                .enableEnvironment(false)
                .enableSystemProperties(false);
        ConfigPropertyResolver resolver = new ConfigPropertyResolver(options);
        for (String fileName : fileNames) {
            resolver.loadSingleFile(fileName);
        }
        if (options.isResolvePlaceholders()) {
            PlaceholderResolver.resolveAll(resolver.properties);
        }
        return resolver;
    }

    /**
     * 读取单个 YAML / properties 流并扁平化为 Map，是全库配置解析的统一入口。
     *
     * @param in 输入流
     * @param fileName 文件名，用于判断 yml / yaml / properties
     * @return 扁平属性映射
     * @throws IOException 读取失败
     */
    public static Map<String, Object> read(InputStream in, String fileName) throws IOException {
        return ConfigFiles.read(in, fileName);
    }

    /**
     * 进程内缓存的默认加载结果。首次调用等价于 {@link #load()}。
     *
     * @return 单例
     */
    public static ConfigPropertyResolver instance() {
        ConfigPropertyResolver local = INSTANCE;
        if (local == null) {
            synchronized (ConfigPropertyResolver.class) {
                local = INSTANCE;
                if (local == null) {
                    INSTANCE = local = load();
                }
            }
        }
        return local;
    }

    /**
     * 清除 {@link #instance()} 缓存，便于测试或重新加载。
     */
    public static void reset() {
        INSTANCE = null;
    }

    /**
     * 按 key 读取字符串配置。
     *
     * @param key 配置项的扁平化 key，如 {@code spring.datasource.url}
     * @return 对应的配置值字符串，key 不存在时返回 {@code null}
     */
    public String getString(String key) {
        return getString(key, null);
    }

    /**
     * 按 key 读取字符串配置，不存在时返回默认值。
     *
     * @param key          配置项的扁平化 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 对应配置值的字符串形式，key 不存在时返回 {@code defaultValue}
     */
    public String getString(String key, String defaultValue) {
        Object val = properties.get(key);
        return val == null ? defaultValue : val.toString();
    }

    /**
     * 按 key 读取配置的原始值，不做类型转换。
     *
     * @param key 配置项的扁平化 key
     * @return 对应的原始配置值，key 不存在时返回 {@code null}
     */
    public Object get(String key) {
        return get(key, null);
    }

    /**
     * 按 key 读取配置的原始值，不存在时返回默认值。
     *
     * @param key          配置项的扁平化 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 对应的原始配置值，key 不存在时返回 {@code defaultValue}
     */
    public Object get(String key, Object defaultValue) {
        Object value = properties.get(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 解析形如 {@code ${key}} / {@code ${key:default}} 的表达式。
     *
     * @param expression 表达式或普通字符串
     * @return 解析结果
     */
    public String getExp(String expression) {
        return PlaceholderResolver.resolveValue(expression, properties, new HashSet<String>());
    }

    /**
     * 按 key 读取整型配置。
     *
     * @param key 配置项的扁平化 key
     * @return 转换后的整数值，key 不存在时返回 {@code null}；值不是合法整数时抛出
     *         {@link NumberFormatException}
     */
    public Integer getInt(String key) {
        return getInt(key, null);
    }

    /**
     * 按 key 读取整型配置，不存在时返回默认值。
     *
     * @param key          配置项的扁平化 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 转换后的整数值，key 不存在时返回 {@code defaultValue}
     */
    public Integer getInt(String key, Integer defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }

    /**
     * 按 key 读取整型配置，等价于 {@link #getInt(String)}。
     *
     * @param key 配置项的扁平化 key
     * @return 转换后的整数值，key 不存在时返回 {@code null}
     */
    public Integer getInteger(String key) {
        return getInt(key);
    }

    /**
     * 按 key 读取整型配置，等价于 {@link #getInt(String, Integer)}。
     *
     * @param key          配置项的扁平化 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 转换后的整数值，key 不存在时返回 {@code defaultValue}
     */
    public Integer getInteger(String key, Integer defaultValue) {
        return getInt(key, defaultValue);
    }

    /**
     * 按 key 读取长整型配置。
     *
     * @param key 配置项的扁平化 key
     * @return 转换后的长整数值，key 不存在时返回 {@code null}；值不是合法整数时抛出
     *         {@link NumberFormatException}
     */
    public Long getLong(String key) {
        return getLong(key, null);
    }

    /**
     * 按 key 读取长整型配置，不存在时返回默认值。
     *
     * @param key          配置项的扁平化 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 转换后的长整数值，key 不存在时返回 {@code defaultValue}
     */
    public Long getLong(String key, Long defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : Long.parseLong(value);
    }

    /**
     * 按 key 读取双精度浮点配置。
     *
     * @param key 配置项的扁平化 key
     * @return 转换后的浮点数值，key 不存在时返回 {@code null}；值不是合法数字时抛出
     *         {@link NumberFormatException}
     */
    public Double getDouble(String key) {
        return getDouble(key, null);
    }

    /**
     * 按 key 读取双精度浮点配置，不存在时返回默认值。
     *
     * @param key          配置项的扁平化 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 转换后的浮点数值，key 不存在时返回 {@code defaultValue}
     */
    public Double getDouble(String key, Double defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : Double.parseDouble(value);
    }

    /**
     * 按 key 读取布尔配置。
     *
     * @param key 配置项的扁平化 key
     * @return 转换后的布尔值，key 不存在时返回 {@code null}
     */
    public Boolean getBoolean(String key) {
        return getBoolean(key, null);
    }

    /**
     * 按 key 读取布尔配置，不存在时返回默认值。
     *
     * <p>值本身为 {@link Boolean} 时直接返回，否则交由 {@code ConvertUtils.toBoolean} 转换。</p>
     *
     * @param key          配置项的扁平化 key
     * @param defaultValue key 不存在时返回的默认值
     * @return 转换后的布尔值，key 不存在时返回 {@code defaultValue}
     */
    public Boolean getBoolean(String key, Boolean defaultValue) {
        Object value = properties.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return ConvertUtils.toBoolean(value);
    }

    /**
     * 按 key 读取列表配置，元素保持原始类型。
     *
     * @param key 配置项的扁平化 key
     * @return 元素为原始值的列表，无匹配配置时返回空列表
     */
    public List<Object> getList(String key) {
        return getList(key, Object.class);
    }

    /**
     * 按 key 读取列表配置并将元素转换为指定类型。
     *
     * <p>同时兼容两种写法：key 自身为逗号分隔的字符串，以及 {@code key[0]}、{@code key[1]} 形式的
     * 索引化 key；元素转换失败时仅记录日志并跳过该元素，列表长度超过内置上限时截断。</p>
     *
     * @param key  配置项的扁平化 key
     * @param type 目标元素类型，支持 String、包装类型及基本类型
     * @param <T>  目标元素类型
     * @return 转换后的列表，无匹配配置时返回空列表
     */
    public <T> List<T> getList(String key, Class<T> type) {
        List<T> list = new ArrayList<T>();
        Function<Object, T> converter = converterFor(type);
        Object val = properties.get(key);
        if (val != null) {
            addListValue(list, val, converter, type);
        }
        for (int i = 0; ; i++) {
            Object indexed = properties.get(key + "[" + i + "]");
            if (indexed == null) {
                break;
            }
            addConverted(list, indexed, converter, type, i);
            if (list.size() >= LIST_SIZE_LIMIT) {
                log.warn("List size limit ({}) reached for key: {}", LIST_SIZE_LIMIT, key);
                break;
            }
        }
        return list;
    }

    /**
     * 判断是否存在指定 key 的配置项。
     *
     * @param key 配置项的扁平化 key
     * @return 存在该 key 时返回 {@code true}，否则返回 {@code false}
     */
    public boolean contains(String key) {
        return properties.containsKey(key);
    }

    /**
     * 已解析属性的只读视图。
     *
     * @return 属性映射
     */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(properties);
    }

    /**
     * 获取全部配置项的 key 集合。
     *
     * @return 所有扁平化 key 的只读集合
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(properties.keySet());
    }

    /**
     * 将已解析的配置导出为 {@link Properties}。
     *
     * @return 新创建的 {@code Properties}，内容为当前全部配置项的字符串形式
     */
    public Properties toProperties() {
        Properties props = new Properties();
        ConfigFiles.copyToProperties(properties, props);
        return props;
    }

    /**
     * 将指定前缀下的配置还原为嵌套 Map。
     *
     * <p>{@code getMap("spring.data.redis")} 对应 {@code spring.data.redis.host} 等 key。</p>
     *
     * @param prefix 前缀，空或 null 表示整份配置
     * @return 嵌套 Map，无匹配 key 时为空 Map
     */
    public Map<String, Object> getMap(String prefix) {
        return ConfigFiles.unflatten(properties, prefix);
    }

    /**
     * 将相同前缀的配置项绑定到指定对象，转换走 {@link YamlDocument#toEntity(Map, Class)}。
     *
     * <p>YAML 的 {@code -} / {@code _} 会按 setter 名做宽松匹配，例如
     * {@code login_timeout}、{@code login-timeout} 均可对应 {@code setLoginTimeout}。</p>
     *
     * @param prefix 前缀，如 {@code spring.data.redis}
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 绑定后的对象；没有该前缀时返回 null
     */
    public <T> T getObject(String prefix, Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        Map<String, Object> nested = getMap(prefix);
        if (nested.isEmpty()) {
            return null;
        }
        return YamlDocument.toEntity(nested, type);
    }

    private void loadAll() {
        long start = System.currentTimeMillis();
        List<String> profiles = detectActiveProfile();
        for (String base : options.getNames()) {
            loadBySearchLocations(base, null);
            if (profiles.isEmpty() && options.isEnableProfiles()) {
                profiles = detectActiveProfile();
            }
            if (options.isEnableProfiles() && !profiles.isEmpty()) {
                loadBySearchLocations(base, profiles);
            }
        }
        if (options.isEnableEnvironment()) {
            overrideFromEnv();
        }
        if (options.isEnableSystemProperties()) {
            overrideFromSystemProperties();
        }
        if (options.isResolvePlaceholders()) {
            PlaceholderResolver.resolveAll(properties);
        }
        log.info("ConfigPropertyResolver loaded, cost: {} ms", System.currentTimeMillis() - start);
    }

    private void loadBySearchLocations(String baseName, List<String> profiles) {
        for (String location : options.getLocations()) {
            for (String ext : options.getExtensions()) {
                if (profiles == null || profiles.isEmpty()) {
                    loadProfileConfig(baseName, location, ext, null);
                    continue;
                }
                for (String profile : profiles) {
                    loadProfileConfig(baseName, location, ext, profile);
                }
            }
        }
    }

    private void loadProfileConfig(String baseName, String location, String ext, String profile) {
        String fileName = baseName + (profile == null ? "" : "-" + profile.trim()) + ext;
        loadResource(location, fileName);
    }

    private void loadResource(String location, String fileName) {
        InputStream in = null;
        try {
            in = open(location, fileName);
            if (in == null) {
                return;
            }
            properties.putAll(ConfigFiles.read(in, fileName));
        } catch (Exception e) {
            log.warn("Failed to load config resource {} from {}", fileName, location, e);
        } finally {
            closeQuietly(in);
        }
    }

    private void loadSingleFile(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return;
        }
        InputStream in = openClasspath(fileName);
        String sourceName = fileName;
        if (in == null) {
            File file = new File(fileName);
            if (!file.isFile()) {
                log.warn("未找到配置文件：{}", fileName);
                return;
            }
            sourceName = file.getName();
            try {
                in = new FileInputStream(file);
            } catch (FileNotFoundException e) {
                log.warn("未找到配置文件：{}", fileName, e);
                return;
            }
        }
        try {
            properties.putAll(ConfigFiles.read(in, sourceName));
        } catch (Exception e) {
            log.warn("Failed to load config file {}", fileName, e);
        } finally {
            closeQuietly(in);
        }
    }

    private InputStream open(String location, String fileName) throws IOException {
        String normalized = normalizeLocation(location);
        if (normalized.startsWith(CLASSPATH_PREFIX)) {
            String path = normalized.substring(CLASSPATH_PREFIX.length()) + fileName;
            return openClasspath(path);
        }
        String dir = normalized;
        if (dir.startsWith(FILE_PREFIX)) {
            dir = dir.substring(FILE_PREFIX.length());
        }
        File file = new File(dir, fileName);
        return file.isFile() ? new FileInputStream(file) : null;
    }

    private InputStream openClasspath(String path) {
        String resource = path;
        if (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        ClassLoader cl = options.getClassLoader();
        if (cl == null) {
            cl = ClassUtils.getDefaultClassLoader();
        }
        if (cl != null) {
            InputStream in = cl.getResourceAsStream(resource);
            if (in != null) {
                return in;
            }
        }
        return ConfigPropertyResolver.class.getClassLoader().getResourceAsStream(resource);
    }

    private static String normalizeLocation(String location) {
        String value = location == null ? "" : location.trim();
        if (value.isEmpty()) {
            return CLASSPATH_PREFIX + "/";
        }
        if (!value.endsWith("/") && !value.endsWith("\\")) {
            value = value + "/";
        }
        return value;
    }

    private void overrideFromSystemProperties() {
        Properties sys = System.getProperties();
        for (String name : sys.stringPropertyNames()) {
            properties.put(name, sys.getProperty(name));
        }
    }

    private void overrideFromEnv() {
        Map<String, String> env = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            properties.put(toRelaxedKey(entry.getKey()), entry.getValue());
        }
    }

    static String toRelaxedKey(String envKey) {
        if (envKey == null) {
            return "";
        }
        return envKey.toLowerCase().replace('_', '.');
    }

    private List<String> detectActiveProfile() {
        if (options.getActiveProfiles() != null) {
            return trimAll(options.getActiveProfiles());
        }
        String profile = System.getProperty("spring.profiles.active");
        if (StringUtils.isNotBlank(profile)) {
            return splitCsv(profile);
        }
        if (options.isEnableEnvironment()) {
            profile = System.getenv("SPRING_PROFILES_ACTIVE");
            if (StringUtils.isNotBlank(profile)) {
                return splitCsv(profile);
            }
        }
        List<String> profiles = new ArrayList<String>();
        addProfiles(profiles, getList("spring.profiles.active"));
        addProfiles(profiles, getList("spring.profiles.include"));
        return profiles;
    }

    private static void addProfiles(List<String> target, List<Object> source) {
        if (source == null) {
            return;
        }
        for (Object item : source) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.indexOf(',') >= 0) {
                target.addAll(splitCsv(text));
            } else if (!target.contains(text)) {
                target.add(text);
            }
        }
    }

    private static List<String> splitCsv(String value) {
        String[] parts = value.split(",");
        return trimAll(parts);
    }

    private static List<String> trimAll(String[] parts) {
        List<String> result = new ArrayList<String>();
        if (parts == null) {
            return result;
        }
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> Function<Object, T> converterFor(Class<T> type) {
        if (type == null || type == Object.class) {
            return obj -> (T) obj;
        }
        if (type == String.class) {
            return obj -> (T) obj.toString();
        }
        if (type == Integer.class || type == int.class) {
            return obj -> (T) Integer.valueOf(obj.toString());
        }
        if (type == Long.class || type == long.class) {
            return obj -> (T) Long.valueOf(obj.toString());
        }
        if (type == Boolean.class || type == boolean.class) {
            return obj -> (T) Boolean.valueOf(obj.toString());
        }
        if (type == Double.class || type == double.class) {
            return obj -> (T) Double.valueOf(obj.toString());
        }
        if (type == Float.class || type == float.class) {
            return obj -> (T) Float.valueOf(obj.toString());
        }
        if (type == Short.class || type == short.class) {
            return obj -> (T) Short.valueOf(obj.toString());
        }
        if (type == Byte.class || type == byte.class) {
            return obj -> (T) Byte.valueOf(obj.toString());
        }
        if (type == BigDecimal.class) {
            return obj -> (T) new BigDecimal(obj.toString());
        }
        if (type == BigInteger.class) {
            return obj -> (T) new BigInteger(obj.toString());
        }
        return type::cast;
    }

    private <T> void addListValue(List<T> list, Object val, Function<Object, T> converter, Class<T> type) {
        if (val instanceof String) {
            String strVal = ((String) val).trim();
            if (strVal.isEmpty()) {
                return;
            }
            String[] parts = strVal.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    addConverted(list, trimmed, converter, type, -1);
                }
            }
            return;
        }
        addConverted(list, val, converter, type, -1);
    }

    private <T> void addConverted(List<T> list, Object val, Function<Object, T> converter,
                                  Class<T> type, int index) {
        try {
            list.add(converter.apply(val));
        } catch (Exception e) {
            if (index >= 0) {
                log.warn("Failed to convert list element '{}' (index {}) to type {}, skip",
                        val, index, type.getSimpleName(), e);
            } else {
                log.warn("Failed to convert list element '{}' to type {}, skip",
                        val, type.getSimpleName(), e);
            }
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException e) {
            // ignore close errors
        }
    }
}
