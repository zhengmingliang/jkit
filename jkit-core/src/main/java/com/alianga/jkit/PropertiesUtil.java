package com.alianga.jkit;

import com.alianga.jkit.config.ConfigPropertyResolver;
import com.alianga.jkit.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Created by 郑明亮 on 2017/11/26 20:57.
 *
 * @author 郑明亮 @email 1072307340@qq.com
 * @version 1.0
 * @time 2017/11/26 20:57
 */

/**
 * @author 郑明亮 @email 1072307340@qq.com
 * @version 1.0
 * @time 2017/11/26 20:57
 */

/**
 * @author 郑明亮 email 1072307340@qq.com
 * @version 1.0
 * time 2017/11/16 18:01
 * <p>指定文件名的配置读取 / 回写工具。</p>
 * <p>支持 {@code .properties}、{@code .yml}、{@code .yaml}。YAML 会按 Spring 规则扁平化为
 * {@code a.b[0].c} 形式后再缓存。文件解析已统一委托给 {@link ConfigPropertyResolver#read}。</p>
 * <p>性能上，只使用一次配置文件，比手动读取一次偶尔会多几毫秒，但在项目中读取配置文件往往很频繁，远比手动读取要省时很多</p>
 *
 * @deprecated 请改用 {@link ConfigPropertyResolver}，它按 Spring Boot 的搜索路径、profile 和环境变量优先级
 *     合并配置，功能是本类的超集。注意本类的 getter 读的是「按文件名单独缓存」的数据源，与
 *     {@code ConfigPropertyResolver} 的合并视图不是同一份数据，迁移时需留意这一语义差异；
 *     仅当确实需要 {@link #update} 的「保留注释回写 properties 文件」能力时才继续使用本类。
 */
@Deprecated
public final class PropertiesUtil {
    private static final Log log = Log.get(PropertiesUtil.class);

    private PropertiesUtil() {
        throw new UnsupportedOperationException("you cannot instant me");
    }

    private static final HashMap<String, Properties> propertiesMap = new HashMap<>();

    /**
     * 对加载的配置文件计数
     */
    private static int propertySize;

    /**
     * 通过文件名加载classpath下的配置文件
     *
     * @param fileName 配置文件名称
     * @return 是否加载成功
     */
    public static boolean loadFromClassPath(String fileName) {
        return loadFromClassPath(fileName, true);
    }

    /**
     * 通过文件名加载classpath下的配置文件
     *
     * @param fileName 配置文件名称
     * @param loadOnce 是否只加载一次，默认为true，当设置为false时，每次都会从配置文件中重新读取
     * @return 是否加载成功
     */
    public static boolean loadFromClassPath(String fileName, boolean loadOnce) {
        boolean flag = false;
        Properties properties = propertiesMap.get(fileName);

        if (!loadOnce || properties == null) {
            properties = new Properties();
            try (InputStream resourceAsStream = PropertiesUtil.class.getResourceAsStream("/" + fileName)) {
                if (resourceAsStream == null) {
                    log.warn("未在classpath中找到{}文件", fileName);
                    return false;
                }
                fill(properties, resourceAsStream, fileName);
                flag = true;
                propertiesMap.put(fileName, properties);
                propertySize = propertiesMap.keySet().size();
            } catch (IOException e) {
                log.warn("从classpath下未找到执行配置文件，开始通过绝对路径寻找配置文件", e);
                flag = loadFromPath(fileName);
            }
        } else { //仅加载一次，并且properties不为空
            flag = true;
        }

        return flag;
    }

    /**
     * <p>通过完整的文件路径加载配置文件</p>
     *
     * @param path 配置文件的完整路径
     * @return 是否加载成功
     */
    public static boolean loadFromPath(String path) {
        return loadFromPath(path, true);
    }

    /**
     * <p>通过完整的文件路径加载配置文件</p>
     *
     * @param path 配置文件的完整路径
     * @param loadOnce 是否只加载一次，默认为true，当设置为false时，每次都会从配置文件中重新读取
     * @return 是否加载成功
     */
    public static boolean loadFromPath(String path, boolean loadOnce) {
        boolean flag = false;
        Properties properties = propertiesMap.get(path);

        if (!loadOnce || properties == null) {
            properties = new Properties();
            try (FileInputStream fis = new FileInputStream(path)) {
                fill(properties, fis, path);
                propertiesMap.put(path, properties);
                flag = true;
                propertySize = propertiesMap.size();
            } catch (IOException e) {
                log.warn("通过绝对路径未找到配置文件", e);
            }
        } else { //首次加载
            flag = true;
        }
        return flag;
    }

    /**
     * 将 properties / yaml 流填充到目标 Properties。YAML 使用
     * {@link ConfigPropertyResolver#read(InputStream, String)} 扁平化，避免两套解析实现。
     */
    private static void fill(Properties target, InputStream in, String fileName) throws IOException {
        String name = fileName;
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (slash >= 0 && slash < fileName.length() - 1) {
            name = fileName.substring(slash + 1);
        }
        String lower = name.toLowerCase();
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            Map<String, Object> map = ConfigPropertyResolver.read(in, name);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                target.setProperty(entry.getKey(), value == null ? "" : String.valueOf(value));
            }
            return;
        }
        target.load(in);
    }

    /**
     * 依次遍历所有已加载的配置文件，返回首个包含该 key 的字符串值。
     *
     * @param key 配置项名称
     * @return 对应的配置值，所有已加载配置文件中都不存在该 key 时返回 {@code null}
     */
    public static String getString(String key) {
        for (Properties properties : propertiesMap.values()) {
            String property = properties.getProperty(key);
            if (property != null) {
                return property;
            }
        }
        return null;
    }

    /**
     * 当获取的值为null时，会使用defaultValue的值
     *
     * @param key 配置项名称
     * @param defaultValue 取不到配置时使用的默认值
     * @return 对应的配置值，不存在该 key 时返回 {@code defaultValue}
     */
    public static String getString(String key, String defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 依次遍历所有已加载的配置文件，返回首个包含该 key 的原始值对象。
     *
     * @param key 配置项名称
     * @return 对应的配置值对象，所有已加载配置文件中都不存在该 key 时返回 {@code null}
     */
    public static Object get(String key) {
        for (Properties properties : propertiesMap.values()) {
            Object o = properties.get(key);
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    /**
     * 当获取的值为null时，会使用defaultValue的值
     *
     * @param key 配置项名称
     * @param defaultValue 取不到配置时使用的默认值
     * @return 对应的配置值对象，不存在该 key 时返回 {@code defaultValue}
     */
    public static Object get(String key, Object defaultValue) {
        for (Properties properties : propertiesMap.values()) {
            Object o = properties.get(key);
            if (o != null) {
                return o;
            }
        }
        return defaultValue;
    }

    /**
     * 读取配置项并转换为 {@link Integer}。
     *
     * @param key 配置项名称
     * @return 转换后的整数值，配置项不存在时返回 {@code null}
     */
    public static Integer getInteger(String key) {
        String string = getString(key);
        if (string != null) {
            return Integer.valueOf(string);
        }
        return null;
    }

    /**
     * 当获取的值为null时，会使用defaultValue的值
     *
     * @param key 配置项名称
     * @param defaultValue 取不到配置时使用的默认值
     * @return 转换后的整数值，配置项不存在时返回 {@code defaultValue}
     */
    public static Integer getInteger(String key, int defaultValue) {
        Integer integer = getInteger(key);
        return integer == null ? defaultValue : integer;
    }

    /**
     * 读取配置项并转换为 {@link Double}。
     *
     * @param key 配置项名称
     * @return 转换后的浮点值，配置项不存在时返回 {@code null}
     */
    public static Double getDouble(String key) {
        String string = getString(key);
        if (string != null) {
            return Double.valueOf(string);
        }
        return null;
    }

    /**
     * 当获取的值为null时，会使用defaultValue的值
     *
     * @param key 配置项名称
     * @param defaultValue 取不到配置时使用的默认值
     * @return 转换后的浮点值，配置项不存在时返回 {@code defaultValue}
     */
    public static Double getDouble(String key, double defaultValue) {
        Double value = getDouble(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 读取配置项并转换为 {@link Boolean}。
     *
     * @param key 配置项名称
     * @return 配置值为 {@code "true"}（忽略大小写）时返回 {@code true}，配置项不存在或为其它值时返回 {@code false}
     */
    public static Boolean getBoolean(String key) {
        return Boolean.valueOf(getString(key));
    }

    /**
     * 当配置项不存在时，会使用defaultValue的值
     *
     * @param key 配置项名称
     * @param defaultValue 取不到配置时使用的默认值
     * @return 配置项存在时按 {@link Boolean#valueOf(String)} 转换后的结果，配置项不存在时返回 {@code defaultValue}
     */
    public static Boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : Boolean.valueOf(value);
    }

    /**
     * 读取配置项并转换为 {@link Long}。
     *
     * @param key 配置项名称
     * @return 转换后的长整数值，配置项不存在时返回 {@code null}
     */
    public static Long getLong(String key) {
        String string = getString(key);
        if (string != null) {
            return Long.valueOf(string);
        }
        return null;
    }

    /**
     * 当获取的值为null时，会使用defaultValue的值
     *
     * @param key 配置项名称
     * @param defaultValue 取不到配置时使用的默认值
     * @return 转换后的长整数值，配置项不存在时返回 {@code defaultValue}
     */
    public static Long getLong(String key, long defaultValue) {
        Long value = getLong(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 移除指定配置文件对应的整份缓存，使其下次可重新加载。
     *
     * @param fileName 加载时使用的配置文件名称或路径（即缓存的 key）
     * @return 缓存中存在该文件并被成功移除时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean removeKeys(String fileName) {
        Properties remove = propertiesMap.remove(fileName);
        return remove != null;
    }

    /**
     * 更新已加载配置中的某个配置项，可选择同时回写到配置文件（回写时保留原文件的注释信息）。
     *
     * @param key 待更新的配置项名称
     * @param value 新的配置值
     * @param comment 回写文件时写入的注释说明
     * @param updateToFile 是否同时把修改写回配置文件
     * @param path 可选的配置文件路径，传入时取第一个元素作为回写目标文件
     * @return 在已加载的配置中找到该 key 并成功更新旧值时返回 {@code true}，否则返回 {@code false}
     * @throws FileNotFoundException 需要回写文件但目标配置文件不存在时抛出
     */
    public static boolean update(String key, String value, String comment, boolean updateToFile, String... path)
            throws FileNotFoundException {
        boolean flag = false;
        for (Map.Entry<String, Properties> propertiesEntry : propertiesMap.entrySet()) {
            Properties properties = propertiesEntry.getValue();
            if (properties.containsKey(key)) {
                flag = properties.setProperty(key, value) != null;
                if (updateToFile) {
                    updateToFile(key, value, comment, propertiesEntry, path);
                }
                break;
            }
        }

        return flag;
    }

    /**
     * 更新参数到配置文件（保留原有配置文件的注释信息）
     *
     * @param key 待更新的配置项名称
     * @param value 新的配置值
     * @param comment 写入文件时附带的注释说明
     * @param propertiesEntry 缓存中的配置项，其 key 为配置文件名，用于定位目标文件
     * @param filePath 可选的配置文件路径，传入时取第一个元素作为回写目标文件
     * @throws FileNotFoundException 目标配置文件不存在时抛出
     */
    private static void updateToFile(String key,
                                     String value,
                                     String comment,
                                     Map.Entry<String, Properties> propertiesEntry,
                                     String... filePath) throws FileNotFoundException {
        Properties properties = new SafeProperties(); //采用自定义Properties，读取配置文件时，记录注释等信息
        String fileName = propertiesEntry.getKey();
        String path = PropertiesUtil.class.getResource(File.separator + fileName).getPath();
        if (filePath != null && filePath.length > 0) {
            path = filePath[0];
        }

        File file = new File(path);
        if (!file.exists()) {
            file = new File(fileName);
            if (!file.exists()) {
                log.error("配置文件不存在");
                throw new FileNotFoundException("配置文件不存在");
            }
        }

        try (FileInputStream fis = new FileInputStream(file);
             FileOutputStream fos = new FileOutputStream(file)) {
            properties.load(fis);
            properties.setProperty(key, value);
            properties.store(fos, comment);
        } catch (IOException e) {
            log.error("更新配置文件发生异常", e);
        }
    }

    /**
     * <ul>
     * <li>获取所有的已加载配置文件的key值</li>
     * <li>在配置文件较少时，使用该方法更省时，当加载配置文件较多时，采用{@link PropertiesUtil#getKeys(String)}方法会更快</li>
     *</ul>
     * @return 获取所有的key值
     */
    public static Set<String> getAllKeys() {
        Set<String> stringPropertyNames = new HashSet<>();
        for (Properties properties : propertiesMap.values()) {
            if (propertySize == 1) {
                return properties.stringPropertyNames();
            } else {
                stringPropertyNames.addAll(properties.stringPropertyNames());
            }

        }
        return stringPropertyNames;
    }

    /**
     * <ul>
     * <li>通过配置文件名称获取该配置文件的所有key</li>
     * <li>前提是，已使用PropertiesUtil.loadXXX方法加载过该配置文件</li>
     * <li>在配置文件较多时时，使用该方法更省时，当加载配置文件较少时采用{@link PropertiesUtil#getAllKeys()}方法会更快</li>
     *</ul>
     * @param fileName 文件名称
     * @return 获取指定文件中的所有key
     */
    public static Set<String> getKeys(String fileName) {
        Properties properties = propertiesMap.get(fileName);
        if (properties != null) {
            return properties.stringPropertyNames();
        }
        return Collections.emptySet();
    }

}
