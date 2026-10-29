package com.alianga.jkit;

import com.alianga.jkit.config.ConfigPropertyResolver;
import com.alianga.jkit.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 已加载配置文件缓存。{@link ConcurrentHashMap} 保证 {@code load*} 与 {@code get*} 并发安全；
     * 读多写少场景下 {@link ConcurrentHashMap} 性能优于 {@code Collections.synchronizedMap}。
     */
    private static final ConcurrentHashMap<String, Properties> propertiesMap = new ConcurrentHashMap<>();

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
                propertiesMap.put(fileName, properties);
                flag = true;
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
     * @throws FileNotFoundException 需要回写文件但目标配置文件不存在，或读取 / 写回
     *         失败时抛出（写失败不再静默返回 true）；对 .yml/.yaml 文件回写抛
     *         {@link IllegalArgumentException}
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
        String fileName = propertiesEntry.getKey();
        String path;
        java.net.URL resourceUrl = PropertiesUtil.class.getResource(File.separator + fileName);
        if (resourceUrl != null) {
            path = resourceUrl.getPath();
        } else {
            // classpath 下找不到时（例如 fileName 是绝对路径），直接尝试按文件名取 File
            path = fileName;
        }
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

        // YAML 不允许回写：rewriteFile 走 SafeProperties 的 properties 格式 store，
        // 会把 yml 文件整体重写成 properties 格式，结构从此损坏
        String lowerName = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".yml") || lowerName.endsWith(".yaml")) {
            throw new IllegalArgumentException(
                    "PropertiesUtil.update 不支持回写 YAML 配置文件（回写会把文件重写为 "
                            + "properties 格式）：仅 updateToFile=false 更新内存，或改用 "
                            + "ConfigPropertyResolver。文件：" + file.getName());
        }

        // 先读完全部内容再写回（读取、修改、落盘在 rewriteFile 内按文件粒度串行）
        rewriteFile(file, key, value, comment);
    }

    /**
     * 按配置文件绝对路径粒度的回写锁：「读文件 → setProperty → 写 tmp → move」
     * 必须对同一文件串行执行，否则并发回写会在同一个 tmp 上交错写坏文件，
     * 或后写方用旧快照覆盖先写方的变更（lost update），磁盘与内存缓存从此不一致。
     */
    private static final ConcurrentHashMap<String, Object> FILE_WRITE_LOCKS =
            new ConcurrentHashMap<String, Object>();

    private static Object lockFor(File file) {
        return FILE_WRITE_LOCKS.computeIfAbsent(file.getAbsolutePath(), new java.util.function.Function<String, Object>() {
            @Override
            public Object apply(String k) {
                return new Object();
            }
        });
    }

    private static void cleanupTmpQuietly(File tmp) {
        try {
            Files.deleteIfExists(tmp.toPath());
        } catch (IOException cleanupFailed) {
            // 清理失败保留 tmp，便于人工恢复
            log.warn("清理临时文件失败：{}", tmp.getAbsolutePath());
        }
    }

    /**
     * 写回失败统一抛 {@link FileNotFoundException}（本类对外声明的唯一受检异常，
     * 是 {@link java.io.IOException} 子类，既有按 IOException 处理的调用方不受影响），
     * 避免「update 返回 true 但落盘失败」的静默数据丢失。
     */
    private static FileNotFoundException writeFailure(String message, IOException cause) {
        FileNotFoundException fnfe = new FileNotFoundException(message);
        fnfe.initCause(cause);
        return fnfe;
    }

    /**
     * 串行化地重写配置文件：读原文件、套用本次修改、经同目录唯一命名临时文件原子替换。
     *
     * @param file    目标配置文件（必须已存在）
     * @param key     本次更新的配置项名称
     * @param value   新值
     * @param comment 写入文件时附带的注释说明
     * @throws FileNotFoundException 读取或写回配置文件失败时抛出
     */
    private static void rewriteFile(File file, String key, String value, String comment)
            throws FileNotFoundException {
        synchronized (lockFor(file)) {
            // 采用自定义 SafeProperties，读取配置文件时记录注释信息
            Properties properties = new SafeProperties();
            // 先读完全部内容再写回，避免在同一 try 块里打开同一文件的 fis+fos（FileOutputStream
            // 构造时会立即把文件截断为 0 字节，导致 properties.load(fis) 读到空数据后丢失原有配置）。
            try (FileInputStream fis = new FileInputStream(file)) {
                properties.load(fis);
            } catch (IOException e) {
                log.error("读取配置文件发生异常", e);
                throw new FileNotFoundException("读取配置文件发生异常：" + e.getMessage());
            }
            properties.setProperty(key, value);

            // 原子写回：先写同目录临时文件，再 move 覆盖目标，避免进程崩溃时原文件被写坏
            // 造成数据丢失。tmp 名带 nanoTime：即使锁失效场景（如多进程）也不会互踩同一个 tmp。
            File tmp = new File(file.getParentFile(),
                    file.getName() + "." + System.nanoTime() + ".jkit-tmp");
            try (OutputStream out = Files.newOutputStream(tmp.toPath())) {
                properties.store(out, comment);
            } catch (IOException e) {
                log.error("写回配置文件发生异常", e);
                cleanupTmpQuietly(tmp);
                throw writeFailure("写回配置文件失败：" + e.getMessage(), e);
            }
            try {
                Files.move(tmp.toPath(), file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                // ATOMIC_MOVE 在某些文件系统（如 NFS）不支持，回退到普通 move
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    log.error("写回配置文件发生异常", e);
                    cleanupTmpQuietly(tmp);
                    throw writeFailure("写回配置文件失败：" + e.getMessage(), e);
                }
            } catch (RuntimeException e) {
                log.error("写回配置文件发生未知异常", e);
                cleanupTmpQuietly(tmp);
                throw e;
            }
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
            stringPropertyNames.addAll(properties.stringPropertyNames());
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
