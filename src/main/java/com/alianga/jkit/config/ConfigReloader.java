package com.alianga.jkit.config;

import com.alianga.jkit.ClassUtils;
import com.alianga.jkit.log.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 定时扫描目录中的配置文件，变更后重新加载并合并到目标 Map。
 *
 * <p>替代原 {@code io.AutoLoadProperties}：支持 {@code .properties} / {@code .yml} / {@code .yaml}，
 * 默认匹配 {@code sys*} 前缀。可在测试里把间隔调短，并用 {@link #reload()} 同步扫一次。</p>
 */
public final class ConfigReloader implements Runnable, Closeable {
    private static final Log log = Log.get(ConfigReloader.class);

    /**
     * 默认共享表，对应原先 {@code AutoLoadProperties.SYSTEM_CONFIG}。
     */
    public static final Map<String, String> SHARED = new ConcurrentHashMap<String, String>();

    private static final Pattern DEFAULT_PATTERN =
            Pattern.compile("^sys.*\\.(properties|yml|yaml)$", Pattern.CASE_INSENSITIVE);

    private final File directory;
    private final Pattern pattern;
    private final long intervalMs;
    private final long initialDelayMs;
    private final Map<String, String> target;
    private final boolean daemon;
    private final Map<String, Long> modified = new ConcurrentHashMap<String, Long>();

    private volatile boolean running;
    private Thread worker;

    private ConfigReloader(Builder builder) {
        this.directory = builder.directory == null ? classpathDirectory() : builder.directory;
        this.pattern = builder.pattern == null ? DEFAULT_PATTERN : builder.pattern;
        this.intervalMs = builder.intervalMs <= 0 ? 5000L : builder.intervalMs;
        this.initialDelayMs = Math.max(0L, builder.initialDelayMs);
        this.target = builder.target == null ? SHARED : builder.target;
        this.daemon = builder.daemon;
    }

    /**
     * 创建一个使用默认参数的构建器。
     *
     * @return 新建的构建器实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 监视 classpath 根目录下的 {@code sys*.properties|yml|yaml}。
     *
     * @return 构建器
     */
    public static Builder classpath() {
        return builder().directory(classpathDirectory());
    }

    /**
     * 解析 classpath 根目录对应的本地文件目录。
     *
     * @return classpath 根目录对应的文件对象；无法定位或非 file 协议时返回当前工作目录 {@code new File(".")}
     */
    public static File classpathDirectory() {
        ClassLoader cl = ClassUtils.getDefaultClassLoader();
        URL url = cl == null ? null : cl.getResource("");
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
            return new File(".");
        }
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            return new File(url.getPath());
        }
    }

    /**
     * 后台启动扫描线程。
     *
     * @return this
     */
    public synchronized ConfigReloader start() {
        if (running) {
            return this;
        }
        running = true;
        worker = new Thread(this, "jkit-config-reloader");
        worker.setDaemon(daemon);
        worker.start();
        return this;
    }

    @Override
    public void run() {
        if (initialDelayMs > 0L) {
            sleepQuietly(initialDelayMs);
        }
        while (running) {
            try {
                reload();
            } catch (Exception e) {
                log.warn("reload config failed from {}", directory, e);
            }
            sleepQuietly(intervalMs);
        }
    }

    /**
     * 立即扫描一次，mtime 变化的文件会重新载入。
     *
     * @return 本次实际加载的文件数
     */
    public int reload() {
        if (directory == null || !directory.isDirectory()) {
            return 0;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return 0;
        }
        int loaded = 0;
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            if (!pattern.matcher(file.getName()).matches()) {
                continue;
            }
            String path = file.getAbsolutePath();
            long mtime = file.lastModified();
            Long previous = modified.get(path);
            if (previous != null && previous.longValue() == mtime) {
                continue;
            }
            if (loadFile(file)) {
                modified.put(path, mtime);
                loaded++;
            }
        }
        return loaded;
    }

    boolean loadFile(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Map<String, Object> map = ConfigPropertyResolver.read(in, file.getName());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                target.put(entry.getKey(), value == null ? "" : String.valueOf(value));
            }
            log.info("loaded config file {}", file.getName());
            return true;
        } catch (Exception e) {
            log.warn("failed to load {}", file, e);
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore close errors
                }
            }
        }
    }

    /**
     * 获取承载配置项的目标 Map。
     *
     * @return 当前配置写入的目标 Map，未显式指定时为 {@link #SHARED}
     */
    public Map<String, String> snapshot() {
        return target;
    }

    /**
     * 获取被监视的配置目录。
     *
     * @return 当前监视的目录
     */
    public File getDirectory() {
        return directory;
    }

    @Override
    public synchronized void close() {
        running = false;
        Thread current = worker;
        if (current != null) {
            current.interrupt();
            worker = null;
        }
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0L || !running) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /**
     * {@link ConfigReloader} 的构建器，用于配置扫描目录、文件匹配规则与调度参数。
     */
    public static final class Builder {
        private File directory;
        private Pattern pattern;
        private long intervalMs = 5000L;
        private long initialDelayMs;
        private Map<String, String> target;
        private boolean daemon = true;

        /**
         * 设置待扫描的配置目录。
         *
         * @param directory 配置文件所在目录，为 {@code null} 时使用 classpath 根目录
         * @return 当前对象，便于链式调用
         */
        public Builder directory(File directory) {
            this.directory = directory;
            return this;
        }

        /**
         * 以正则字符串设置文件名匹配规则。
         *
         * @param regex 文件名匹配的正则表达式
         * @return 当前对象，便于链式调用
         */
        public Builder pattern(String regex) {
            this.pattern = Pattern.compile(regex);
            return this;
        }

        /**
         * 以已编译的正则设置文件名匹配规则。
         *
         * @param pattern 文件名匹配的正则，为 {@code null} 时使用默认的 {@code sys*} 规则
         * @return 当前对象，便于链式调用
         */
        public Builder pattern(Pattern pattern) {
            this.pattern = pattern;
            return this;
        }

        /**
         * 设置两次扫描之间的间隔时间。
         *
         * @param intervalMs 扫描间隔毫秒数，小于等于 0 时使用默认的 5000 毫秒
         * @return 当前对象，便于链式调用
         */
        public Builder intervalMillis(long intervalMs) {
            this.intervalMs = intervalMs;
            return this;
        }

        /**
         * 设置线程启动后首次扫描前的延迟时间。
         *
         * @param initialDelayMs 首次扫描延迟毫秒数，负值按 0 处理
         * @return 当前对象，便于链式调用
         */
        public Builder initialDelayMillis(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }

        /**
         * 设置配置项写入的目标 Map。
         *
         * @param target 承载配置项的 Map，为 {@code null} 时使用 {@link #SHARED}
         * @return 当前对象，便于链式调用
         */
        public Builder target(Map<String, String> target) {
            this.target = target;
            return this;
        }

        /**
         * 设置扫描线程是否为守护线程。
         *
         * @param daemon 为 {@code true} 时扫描线程以守护线程运行，默认为 {@code true}
         * @return 当前对象，便于链式调用
         */
        public Builder daemon(boolean daemon) {
            this.daemon = daemon;
            return this;
        }

        /**
         * 根据当前配置创建 {@link ConfigReloader} 实例，创建后不会自动启动扫描线程。
         *
         * @return 新创建的 {@link ConfigReloader} 实例
         */
        public ConfigReloader build() {
            return new ConfigReloader(this);
        }
    }
}
