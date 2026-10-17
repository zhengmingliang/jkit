package com.alianga.jkit.storage.engine;

import com.alianga.jkit.storage.FileStorage;
import com.alianga.jkit.storage.StorageConfig;
import com.alianga.jkit.storage.StorageEntry;
import com.alianga.jkit.storage.StorageErrorCode;
import com.alianga.jkit.storage.StorageException;
import com.alianga.jkit.storage.StoragePaths;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * SFTP 引擎：通过<b>反射</b>调用 JSch（com.jcraft.jsch），编译期与 pom 均不引入任何依赖。
 * 运行环境把 com.github.mwiede:jsch（或原版 com.jcraft:jsch）加入 classpath 即可启用；
 * JSch 的 Session / Channel 非线程安全，本引擎在同一实例上对全部操作做串行化
 * （同步引擎的多并发会在此排队，正确性优先）；
 * 缺依赖时构造成功、首次操作抛 {@link StorageErrorCode#UNSUPPORTED} 并给出明确提示。
 *
 * <p>连接：密码与私钥二选一（私钥可带口令）；StrictHostKeyChecking 默认关闭，
 * 开启后按 yes 交给 JSch 校验 known_hosts。Method / Field 反射句柄按名称缓存在
 * HashMap 中，列表条目逐个取 getFilename / getAttrs 时不再做反射查找。</p>
 *
 * <p>并发：单 Session 上维护一个按需扩展的 ChannelSftp 池（{@code channelPool}
 * 配置，默认 4），引擎操作各自借还通道，互不排队；JSch 连接级异常会把当前
 * 通道标记为损坏并弃用（下次借时新建），协议级 SftpException 不影响通道。
 * {@code channelPool=1} 即退化为旧行为（实例级串行）。</p>
 *
 * <p>错误映射：SftpException id 2 → NOT_FOUND，4 / 11 → CONFLICT，
 * permission denied（id 3 / 13）→ AUTH_FAILED，其余 → IO_ERROR；JSchException 按消息
 * 关键字映射（auth → AUTH_FAILED、timeout → TIMEOUT、reject / 其余 → CONNECT_FAILED）。</p>
 *
 * <p>语义：copy 为 download + upload 组合；rename 优先 native rename，目标已存在
 * （id 11 / 4）时先删目标再重命名，覆盖语义与其它引擎一致；目录 copy / rename
 * 走递归（copyDir / renameDir）。delete 只删文件或空目录（rmdir 非空由服务端拒绝）。</p>
 *
 * <p>配置键：host（必填）、port（默认 22）、user（必填）、password、privateKey、
 * privateKeyPassphrase、basePath（起始目录，默认 {@code /} 即服务器根目录；
 * 留空同样从 {@code /} 开始，登录家目录请填 {@code /home/用户名}）、
 * strictHostKeyChecking（默认 false）。存储内相对路径统一映射到该根之下的
 * <b>服务器绝对路径</b>，{@link #absolutePathOf} 可直接取到。</p>
 *
 * @author 郑明亮
 */
public class SftpFileStorage implements FileStorage {
    /** 连接与会话超时毫秒（session.setTimeout / session.connect）。 */
    private static final int CONNECT_TIMEOUT_MS = 15000;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String privateKey;
    private final String privateKeyPassphrase;
    /** 服务器端绝对根目录：basePath 为空 / {@code /} 时为 {@code /}，否则强制绝对化。 */
    private final String root;
    private final boolean strictHostKeyChecking;
    /** SFTP 通道池上限（默认 4；1 = 实例级串行）。 */
    private final int poolSize;
    /** 懒建立的 SFTP 通道：构造不连接，首次操作才建立（缺 jsch 依赖时在此暴露）。 */
    private volatile JschBridge bridge;

    /**
     * 由配置创建（配置键：host / port / user / password / privateKey /
     * privateKeyPassphrase / basePath / strictHostKeyChecking / channelPool）。
     * 缺 host / user 直接抛 {@link StorageErrorCode#INVALID_CONFIG}；
     * 缺 jsch 依赖不会在这里暴露。
     *
     * @param config 连接配置
     */
    public SftpFileStorage(StorageConfig config) {
        this.host = config.require("host");
        this.port = config.intOr("port", 22);
        this.user = config.require("user");
        this.password = blankToNull(config.get("password"));
        this.privateKey = blankToNull(config.get("privateKey"));
        this.privateKeyPassphrase = blankToNull(config.get("privateKeyPassphrase"));
        this.root = rootOf(config.getOr("basePath", "/"));
        this.strictHostKeyChecking = config.boolOr("strictHostKeyChecking", false);
        this.poolSize = Math.max(1, Math.min(16, config.intOr("channelPool", 4)));
    }

    @Override
    public String platform() {
        return "sftp";
    }

    @Override
    public String absolutePathOf(String path) {
        return remoteOf(StoragePaths.normalize(path));
    }

    @Override
    public boolean exists(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return true;
        }
        try {
            bridge().lstat(remoteOf(normalized));
            return true;
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public StorageEntry stat(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            return StorageEntry.dir("", 0L);
        }
        JschBridge.Attrs attrs = bridge().lstat(remoteOf(normalized));
        StorageEntry entry;
        if (attrs.directory) {
            entry = StorageEntry.dir(normalized, attrs.mtimeSeconds * 1000L);
        } else {
            entry = StorageEntry.file(normalized, attrs.size, attrs.mtimeSeconds * 1000L, null);
        }
        // lstat 只有数字 uid/gid；尽量再从父目录列表取 longname 里的名字
        return withOwnershipFromParent(entry, normalized, attrs);
    }

    /** 给 stat 结果补属主 / 组：数字 id 兜底，longname 名字优先；取不到时原样返回。 */
    private StorageEntry withOwnershipFromParent(StorageEntry entry, String normalized,
                                                 JschBridge.Attrs attrs) {
        String numericOwner = attrs.uid != null && attrs.uid >= 0 ? Integer.toString(attrs.uid) : null;
        String numericGroup = attrs.gid != null && attrs.gid >= 0 ? Integer.toString(attrs.gid) : null;
        String parent = StoragePaths.parent(normalized);
        String name = StoragePaths.name(normalized);
        try {
            for (JschBridge.Entry sibling : bridge().list(remoteOf(parent))) {
                if (sibling.name.equals(name)) {
                    return entryWithOwnership(entry, sibling.owner, sibling.group);
                }
            }
        } catch (StorageException ignored) {
            // 属主是展示增强，列表失败不拖垮 stat
        }
        return entryWithOwnership(entry, numericOwner, numericGroup);
    }

    private static StorageEntry entryWithOwnership(StorageEntry entry, String owner, String group) {
        StorageEntry result = entry;
        if (owner != null && !owner.isEmpty()) {
            result = result.withAttribute(StorageEntry.ATTR_OWNER, owner);
        }
        if (group != null && !group.isEmpty()) {
            result = result.withAttribute(StorageEntry.ATTR_GROUP, group);
        }
        return result;
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        for (JschBridge.Entry entry : bridge().list(remoteOf(normalized))) {
            String childPath = StoragePaths.join(normalized, entry.name);
            StorageEntry child = entry.directory
                    ? StorageEntry.dir(childPath, entry.mtimeSeconds * 1000L)
                    : StorageEntry.file(childPath, entry.size, entry.mtimeSeconds * 1000L, null);
            entries.add(entryWithOwnership(child, entry.owner, entry.group));
        }
        return entries;
    }

    @Override
    public void mkdir(String dir) {
        String normalized = StoragePaths.normalize(dir);
        if (normalized.isEmpty()) {
            return;
        }
        if (exists(normalized)) {
            return;
        }
        // SFTP mkdir 不支持多级创建，逐级建立；中间目录已存在（并发或重复）视为成功
        String current = "";
        for (String segment : normalized.split("/")) {
            current = current.isEmpty() ? segment : current + "/" + segment;
            try {
                bridge().mkdir(remoteOf(current));
            } catch (StorageException e) {
                if (e.code() != StorageErrorCode.CONFLICT && e.code() != StorageErrorCode.ALREADY_EXISTS) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void upload(String path, InputStream in, long size) {
        String normalized = requireFile(path);
        String parent = StoragePaths.parent(normalized);
        if (!parent.isEmpty()) {
            mkdir(parent);
        }
        bridge().put(in, remoteOf(normalized));
    }

    @Override
    public void download(String path, OutputStream out) {
        String normalized = requireFile(path);
        bridge().get(remoteOf(normalized), out);
    }

    @Override
    public boolean supportsRange() {
        return true;
    }

    @Override
    public void download(String path, long offset, OutputStream out) {
        String normalized = requireFile(path);
        if (offset <= 0) {
            download(normalized, out);
            return;
        }
        bridge().get(remoteOf(normalized), out, offset);
    }

    @Override
    public void delete(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("拒绝删除存储根目录");
        }
        String remote = remoteOf(normalized);
        JschBridge.Attrs attrs;
        try {
            attrs = bridge().lstat(remote);
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return;
            }
            throw e;
        }
        if (attrs.directory) {
            bridge().rmdir(remote);
        } else {
            bridge().rm(remote);
        }
    }

    @Override
    public void copy(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (isDirectory(source)) {
            copyDir(source, target);
            return;
        }
        copyFile(source, target);
    }

    @Override
    public void rename(String src, String dst) {
        String source = requireFile(src);
        String target = requireFile(dst);
        if (isDirectory(source) && exists(target)) {
            throw StorageException.conflict("目标目录已存在: " + target, target);
        }
        try {
            bridge().rename(remoteOf(source), remoteOf(target));
        } catch (StorageException e) {
            if (e.code() != StorageErrorCode.CONFLICT) {
                throw e;
            }
            // 目标已存在（SftpException id=11 / 4）：先删目标再重命名（覆盖语义）
            bridge().rm(remoteOf(target));
            bridge().rename(remoteOf(source), remoteOf(target));
        }
    }

    @Override
    public synchronized void close() {
        JschBridge current = bridge;
        bridge = null;
        if (current != null) {
            current.close();
        }
    }

    // ------------------------------------------------------------------ 内部操作

    private JschBridge bridge() {
        JschBridge current = bridge;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (bridge == null) {
                bridge = JschBridge.open(host, port, user, password, privateKey,
                        privateKeyPassphrase, strictHostKeyChecking, poolSize);
            }
            return bridge;
        }
    }

    private boolean isDirectory(String normalized) {
        try {
            return bridge().lstat(remoteOf(normalized)).directory;
        } catch (StorageException e) {
            if (e.code() == StorageErrorCode.NOT_FOUND) {
                return !list(normalized).isEmpty();
            }
            throw e;
        }
    }

    private void copyFile(String source, String target) {
        File temp = com.alianga.jkit.storage.internal.TempFiles.create(
                "jkit-storage-sftp-copy", stat(source).size(), source);
        try {
            try (OutputStream out = Files.newOutputStream(temp.toPath())) {
                download(source, out);
            }
            try (InputStream in = Files.newInputStream(temp.toPath())) {
                upload(target, in, temp.length());
            }
        } catch (IOException e) {
            throw StorageException.io("复制失败: " + source + " -> " + target, target, e);
        } finally {
            com.alianga.jkit.storage.internal.TempFiles.deleteQuietly(temp);
        }
    }

    private void copyDir(String source, String target) {
        mkdir(target);
        for (StorageEntry entry : list(source)) {
            if (entry.isDirectory()) {
                copyDir(entry.path(), StoragePaths.join(target, entry.name()));
            } else {
                copy(entry.path(), StoragePaths.join(target, entry.name()));
            }
        }
    }

    /** 存储内相对路径 → 服务器绝对路径；根目录即 {@code /}。 */
    private String remoteOf(String normalized) {
        if (normalized.isEmpty()) {
            return root;
        }
        return root.endsWith("/") ? root + normalized : root + "/" + normalized;
    }

    /**
     * 计算服务器端绝对根：空 / {@code /} → {@code /}；其余值折叠冗余分隔符后
     * 强制补前导 {@code /}（此前裸相对值会被 JSch 解析到家目录之下，语义含糊）。
     */
    private static String rootOf(String configured) {
        String trimmed = configured == null ? "" : configured.trim();
        if (trimmed.isEmpty()) {
            return "/";
        }
        return "/" + StoragePaths.normalize(trimmed);
    }

    private String requireFile(String path) {
        String normalized = StoragePaths.normalize(path);
        if (normalized.isEmpty()) {
            throw StorageException.invalidConfig("路径不能为存储根目录");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------ JSch 反射桥

    /** 等待归还时的轮询间隔：期间可能有通道被作废、名额空出，需要重新尝试新建。 */
    static final long BORROW_POLL_MS = 200L;

    /**
     * 借取通道的空闲超时：所有通道连续无 I/O（也无归还 / 新建 / 作废）超过该时长才抛 TIMEOUT。
     * 正常传大文件时持有者持续收发数据，等待者会一直等到有通道归还，不会误报。
     */
    static final long BORROW_TIMEOUT_MS = 30000L;

    /**
     * 通道池借取：先取空闲；未达上限则新建；否则按 I/O 活跃度等待。
     * 作废（broken）的通道只减计数、不回队列，若用 {@code take()} 死等，池满且全部作废时
     * 等待者会永远挂住。
     *
     * @param idle     空闲队列
     * @param created  已创建计数
     * @param poolSize 上限
     * @param closed   池是否已关闭
     * @param factory  新建通道
     * @param <T>      通道类型
     * @return 通道
     */
    static <T> T borrowFrom(java.util.concurrent.BlockingQueue<T> idle,
                            java.util.concurrent.atomic.AtomicInteger created, int poolSize,
                            java.util.function.BooleanSupplier closed,
                            java.util.function.Supplier<T> factory) {
        return borrowFrom(idle, created, poolSize, closed, factory, BORROW_TIMEOUT_MS, null);
    }

    /**
     * 同 {@link #borrowFrom(java.util.concurrent.BlockingQueue, java.util.concurrent.atomic.AtomicInteger,
     * int, java.util.function.BooleanSupplier, java.util.function.Supplier)}，可指定空闲超时。
     * {@code lastActivityNanos == null} 时退化为从开始等待起的固定截止时间（单测用）。
     *
     * @param idle      空闲队列
     * @param created   已创建计数
     * @param poolSize  上限
     * @param closed    池是否已关闭
     * @param factory   新建通道
     * @param timeoutMs 无 I/O / 无进展的最长等待毫秒
     * @param <T>       通道类型
     * @return 通道
     */
    static <T> T borrowFrom(java.util.concurrent.BlockingQueue<T> idle,
                            java.util.concurrent.atomic.AtomicInteger created, int poolSize,
                            java.util.function.BooleanSupplier closed,
                            java.util.function.Supplier<T> factory, long timeoutMs) {
        return borrowFrom(idle, created, poolSize, closed, factory, timeoutMs, null);
    }

    /**
     * 按 I/O 活跃度借取：只要 {@code lastActivityNanos} 持续被读写 / 归还 / 作废刷新，
     * 等待者不超时；仅当全部通道连续 {@code timeoutMs} 无进展时抛 TIMEOUT。
     *
     * @param idle              空闲队列
     * @param created           已创建计数
     * @param poolSize          上限
     * @param closed            池是否已关闭
     * @param factory           新建通道
     * @param timeoutMs         无进展最长等待毫秒
     * @param lastActivityNanos 最近一次池进展（I/O / 归还 / 作废 / 持通道操作）的纳秒时间戳；null 则固定截止
     * @param <T>               通道类型
     * @return 通道
     */
    static <T> T borrowFrom(java.util.concurrent.BlockingQueue<T> idle,
                            java.util.concurrent.atomic.AtomicInteger created, int poolSize,
                            java.util.function.BooleanSupplier closed,
                            java.util.function.Supplier<T> factory, long timeoutMs,
                            java.util.concurrent.atomic.AtomicLong lastActivityNanos) {
        long waitStarted = System.nanoTime();
        while (true) {
            if (closed.getAsBoolean()) {
                throw StorageException.connectFailed("SFTP 通道池已关闭");
            }
            T channel = idle.poll();
            if (channel != null) {
                return channel;
            }
            if (created.incrementAndGet() <= poolSize) {
                try {
                    return factory.get();
                } catch (RuntimeException e) {
                    created.decrementAndGet();
                    throw e;
                }
            }
            created.decrementAndGet();
            long now = System.nanoTime();
            long anchor = lastActivityNanos != null ? lastActivityNanos.get() : waitStarted;
            if (anchor <= 0L) {
                anchor = waitStarted;
            }
            // 等待开始时若尚无活动记录，以开始等待为静默起点，避免历史时间戳立刻超时
            if (anchor < waitStarted) {
                anchor = waitStarted;
            }
            long idleMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - anchor);
            if (idleMs >= timeoutMs) {
                throw StorageException.timeout("SFTP 通道池已满，且 "
                        + timeoutMs + "ms 内无任何通道 I/O 或归还");
            }
            long slice = Math.min(BORROW_POLL_MS, timeoutMs - idleMs);
            if (slice <= 0L) {
                slice = 1L;
            }
            try {
                channel = idle.poll(slice, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw StorageException.io("SFTP 通道借取被中断", "-", e);
            }
            if (channel != null) {
                return channel;
            }
        }
    }

    /**
     * JSch 反射桥：持有单条 Session 与一个按需扩展的 ChannelSftp 池，
     * 全部方法调用走反射；Method / Field 句柄按「类名#方法名」缓存避免
     * 每条目重复查找。每个操作从池里借一条通道、用完归还；JSch 连接级
     * 异常会把通道标记为损坏并弃用（下次借时新建），SftpException 属于
     * 协议级应答，通道仍可用。
     */
    private static final class JschBridge {
        /** Method 句柄缓存（类名#方法名 → Method），避免列表逐条目重复反射查找。 */
        private static final Map<String, Method> METHODS = new HashMap<String, Method>();
        /** Field 句柄缓存（如 ChannelSftp.OVERWRITE、SftpException.id）。 */
        private static final Map<String, Field> FIELDS = new HashMap<String, Field>();

        private final Object session;
        private final int poolSize;
        private final int overwrite;
        /** 空闲通道队列；借空且未达上限时新建，达上限后阻塞等待归还。 */
        private final java.util.concurrent.BlockingQueue<Object> idle;
        private final java.util.concurrent.atomic.AtomicInteger created =
                new java.util.concurrent.atomic.AtomicInteger();
        /** 最近一次池进展（收发字节 / 归还 / 作废 / 持通道操作进展），供等待者按 I/O 活跃度判断超时。 */
        private final java.util.concurrent.atomic.AtomicLong lastIoNanos =
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        private volatile boolean closed;

        private JschBridge(Object session, int poolSize, int overwrite) {
            this.session = session;
            this.poolSize = poolSize;
            this.overwrite = overwrite;
            this.idle = new java.util.concurrent.LinkedBlockingQueue<Object>();
        }

        /** 刷新最近 I/O / 池进展时间戳（仅收发字节、归还、作废、持通道操作进展；借取入口不刷新）。 */
        private void touchIo() {
            lastIoNanos.set(System.nanoTime());
        }

        /** 从池里借一条通道；借空且未达上限时新建，否则按 I/O 活跃度等待归还或名额空出。
         * 借取本身不刷新活跃时间：持有者已挂死时，外面持续借取不得重置空闲计时。 */
        private Object borrow() {
            return borrowFrom(idle, created, poolSize, () -> closed, () -> {
                try {
                    return openChannel(session);
                } catch (ReflectiveOperationException e) {
                    throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), "-", e);
                }
            }, BORROW_TIMEOUT_MS, lastIoNanos);
        }

        /** 归还通道；broken 时断开丢弃（下次借时新建）。 */
        private void release(Object channel, boolean broken) {
            if (broken || closed) {
                closeChannel(channel);
                created.decrementAndGet();
                touchIo();
                return;
            }
            idle.offer(channel);
            touchIo();
        }

        /** 下载时包装输出流：每写出一块就刷新池进展时间戳。 */
        private OutputStream activityOut(OutputStream out) {
            return new java.io.FilterOutputStream(out) {
                @Override
                public void write(int b) throws java.io.IOException {
                    out.write(b);
                    touchIo();
                }

                @Override
                public void write(byte[] b, int off, int len) throws java.io.IOException {
                    out.write(b, off, len);
                    touchIo();
                }
            };
        }

        /** 上传时包装输入流：每读入一块就刷新池进展时间戳。 */
        private InputStream activityIn(InputStream in) {
            return new java.io.FilterInputStream(in) {
                @Override
                public int read() throws java.io.IOException {
                    int n = in.read();
                    if (n >= 0) {
                        touchIo();
                    }
                    return n;
                }

                @Override
                public int read(byte[] b, int off, int len) throws java.io.IOException {
                    int n = in.read(b, off, len);
                    if (n > 0) {
                        touchIo();
                    }
                    return n;
                }
            };
        }

        private void closeChannel(Object channel) {
            try {
                method(channel.getClass(), "disconnect").invoke(channel);
            } catch (ReflectiveOperationException | RuntimeException ignore) {
                // 通道断开失败不影响整体关闭
            }
        }

        /** 建立 SFTP 连接（单 Session）；classpath 缺 jsch 时抛 UNSUPPORTED。 */
        static JschBridge open(String host, int port, String user, String password,
                               String privateKey, String privateKeyPassphrase,
                               boolean strictHostKeyChecking, int poolSize) {
            Class<?> jschClass;
            try {
                jschClass = Class.forName("com.jcraft.jsch.JSch");
            } catch (ClassNotFoundException e) {
                throw StorageException.unsupported("SFTP 引擎需要 jsch 依赖"
                        + "（com.github.mwiede:jsch 或 com.jcraft:jsch），请将其加入 classpath");
            }
            try {
                Object jsch = jschClass.newInstance();
                if (privateKey != null) {
                    method(jschClass, "addIdentity", String.class, String.class)
                            .invoke(jsch, privateKey, privateKeyPassphrase);
                }
                Object session = method(jschClass, "getSession", String.class, String.class, int.class)
                        .invoke(jsch, user, host, port);
                if (password != null) {
                    method(session.getClass(), "setPassword", String.class).invoke(session, password);
                }
                method(session.getClass(), "setConfig", String.class, String.class)
                        .invoke(session, "StrictHostKeyChecking", strictHostKeyChecking ? "yes" : "no");
                method(session.getClass(), "setTimeout", int.class).invoke(session, CONNECT_TIMEOUT_MS);
                method(session.getClass(), "connect", int.class).invoke(session, CONNECT_TIMEOUT_MS);
                return new JschBridge(session, poolSize, overwriteOf(session));
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), "-", "连接");
            } catch (ReflectiveOperationException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), "-", e);
            }
        }

        /** 借一条临时通道取 OVERWRITE 常量（Put 用），取完即还。 */
        private static int overwriteOf(Object session) throws ReflectiveOperationException,
                InvocationTargetException {
            Object channel = openChannel(session);
            try {
                return field(channel.getClass(), "OVERWRITE").getInt(null);
            } finally {
                method(channel.getClass(), "disconnect").invoke(channel);
            }
        }

        /** 在 Session 上打开并连接一条新的 SFTP 通道。 */
        private static Object openChannel(Object session) throws ReflectiveOperationException,
                InvocationTargetException {
            Object channel = method(session.getClass(), "openChannel", String.class)
                    .invoke(session, "sftp");
            method(channel.getClass(), "connect").invoke(channel);
            return channel;
        }

        /** ls：返回去掉 "." / ".." 之后的条目列表；逐条处理时刷新活跃时间，长 listing 不拖死等待者。 */
        List<Entry> list(String remotePath) {
            Object channel = borrow();
            try {
                touchIo();
                Object vector = method(channel.getClass(), "ls", String.class).invoke(channel, remotePath);
                List<Entry> entries = new ArrayList<Entry>();
                for (Object item : (Vector<?>) vector) {
                    String filename = (String) method(item.getClass(), "getFilename").invoke(item);
                    if (".".equals(filename) || "..".equals(filename)) {
                        continue;
                    }
                    Object attrs = method(item.getClass(), "getAttrs").invoke(item);
                    String longName = (String) method(item.getClass(), "getLongname").invoke(item);
                    Integer uid = (Integer) method(attrs.getClass(), "getUId").invoke(attrs);
                    Integer gid = (Integer) method(attrs.getClass(), "getGId").invoke(attrs);
                    entries.add(new Entry(filename,
                            (Boolean) method(attrs.getClass(), "isDir").invoke(attrs),
                            (Long) method(attrs.getClass(), "getSize").invoke(attrs),
                            (Integer) method(attrs.getClass(), "getMTime").invoke(attrs),
                            ownerOf(longName, uid), groupOf(longName, gid)));
                    touchIo();
                }
                release(channel, false);
                return entries;
            } catch (InvocationTargetException e) {
                release(channel, isConnectionLevel(e.getCause()));
                throw mapThrowable(e.getCause(), remotePath, "列出目录");
            } catch (ReflectiveOperationException | ClassCastException e) {
                release(channel, false);
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            } catch (RuntimeException e) {
                release(channel, true);
                throw e;
            }
        }

        /** 在异常链里找调用方输出流抛出的 StorageException（jsch 会把它包进 SftpException）。 */
        private static StorageException abortedByCaller(Throwable cause) {
            for (Throwable t = cause; t != null; t = t.getCause()) {
                if (t instanceof StorageException) {
                    return (StorageException) t;
                }
                if (t.getCause() == t) {
                    break;
                }
            }
            return null;
        }

        /** 连接级异常（通道可能已坏）才弃用通道；SftpException 属协议应答，通道无恙。 */
        private static boolean isConnectionLevel(Throwable cause) {
            return cause != null && "com.jcraft.jsch.JSchException".equals(cause.getClass().getName());
        }

        /** lstat：目标不存在时抛 NOT_FOUND；持通道期间刷新活跃时间。 */
        Attrs lstat(String remotePath) {
            Object channel = borrow();
            try {
                touchIo();
                Object attrs = method(channel.getClass(), "lstat", String.class)
                        .invoke(channel, remotePath);
                Integer uid = (Integer) method(attrs.getClass(), "getUId").invoke(attrs);
                Integer gid = (Integer) method(attrs.getClass(), "getGId").invoke(attrs);
                touchIo();
                release(channel, false);
                return new Attrs((Boolean) method(attrs.getClass(), "isDir").invoke(attrs),
                        (Long) method(attrs.getClass(), "getSize").invoke(attrs),
                        (Integer) method(attrs.getClass(), "getMTime").invoke(attrs),
                        uid, gid);
            } catch (InvocationTargetException e) {
                release(channel, isConnectionLevel(e.getCause()));
                throw mapThrowable(e.getCause(), remotePath, "读取元数据");
            } catch (ReflectiveOperationException e) {
                release(channel, false);
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            } catch (RuntimeException e) {
                release(channel, true);
                throw e;
            }
        }

        void get(String remotePath, OutputStream out) {
            Object channel = borrow();
            try {
                method(channel.getClass(), "get", String.class, OutputStream.class)
                        .invoke(channel, remotePath, activityOut(out));
                release(channel, false);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                // 调用方输出流中止（如 readBytes 超限）时通道里还有未取完的读请求：作废通道
                StorageException aborted = abortedByCaller(cause);
                release(channel, aborted != null || isConnectionLevel(cause));
                if (aborted != null) {
                    throw aborted;
                }
                throw mapThrowable(cause, remotePath, "下载");
            } catch (ReflectiveOperationException e) {
                release(channel, false);
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            } catch (RuntimeException e) {
                release(channel, true);
                throw e;
            }
        }

        /** 续传下载：jsch get(src, out, monitor, skip)，skip 为起始偏移。 */
        void get(String remotePath, OutputStream out, long offset) {
            Object channel = borrow();
            try {
                Class<?> monitor = Class.forName("com.jcraft.jsch.SftpProgressMonitor");
                method(channel.getClass(), "get", String.class, OutputStream.class,
                        monitor, long.class).invoke(channel, remotePath, activityOut(out), null, offset);
                release(channel, false);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                // 调用方输出流中止（如 readBytes 超限）时通道里还有未取完的读请求：作废通道
                StorageException aborted = abortedByCaller(cause);
                release(channel, aborted != null || isConnectionLevel(cause));
                if (aborted != null) {
                    throw aborted;
                }
                throw mapThrowable(cause, remotePath, "续传下载");
            } catch (ReflectiveOperationException e) {
                release(channel, false);
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            } catch (RuntimeException e) {
                release(channel, true);
                throw e;
            }
        }

        void put(InputStream in, String remotePath) {
            Object channel = borrow();
            try {
                method(channel.getClass(), "put", InputStream.class, String.class, int.class)
                        .invoke(channel, activityIn(in), remotePath, overwrite);
                release(channel, false);
            } catch (InvocationTargetException e) {
                release(channel, isConnectionLevel(e.getCause()));
                throw mapThrowable(e.getCause(), remotePath, "上传");
            } catch (ReflectiveOperationException e) {
                release(channel, false);
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            } catch (RuntimeException e) {
                release(channel, true);
                throw e;
            }
        }

        void rm(String remotePath) {
            invoke("rm", remotePath, "删除");
        }

        void mkdir(String remotePath) {
            invoke("mkdir", remotePath, "创建目录");
        }

        void rmdir(String remotePath) {
            invoke("rmdir", remotePath, "删除目录");
        }

        void rename(String fromPath, String toPath) {
            Object channel = borrow();
            try {
                touchIo();
                method(channel.getClass(), "rename", String.class, String.class)
                        .invoke(channel, fromPath, toPath);
                touchIo();
                release(channel, false);
            } catch (InvocationTargetException e) {
                release(channel, isConnectionLevel(e.getCause()));
                throw mapThrowable(e.getCause(), toPath, "重命名");
            } catch (ReflectiveOperationException e) {
                release(channel, false);
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), toPath, e);
            } catch (RuntimeException e) {
                release(channel, true);
                throw e;
            }
        }

        private void invoke(String name, String remotePath, String action) {
            Object channel = borrow();
            try {
                touchIo();
                method(channel.getClass(), name, String.class).invoke(channel, remotePath);
                touchIo();
                release(channel, false);
            } catch (InvocationTargetException e) {
                release(channel, isConnectionLevel(e.getCause()));
                throw mapThrowable(e.getCause(), remotePath, action);
            } catch (ReflectiveOperationException e) {
                release(channel, false);
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            } catch (RuntimeException e) {
                release(channel, true);
                throw e;
            }
        }

        void close() {
            closed = true;
            for (Object channel : idle) {
                closeChannel(channel);
            }
            idle.clear();
            try {
                method(session.getClass(), "disconnect").invoke(session);
            } catch (ReflectiveOperationException ignore) {
                // 会话关闭失败不影响调用方
            }
        }

        private static Method method(Class<?> type, String name, Class<?>... parameterTypes)
                throws NoSuchMethodException {
            String key = type.getSimpleName() + "#" + name;
            synchronized (METHODS) {
                Method cached = METHODS.get(key);
                if (cached != null) {
                    return cached;
                }
                Method found = type.getMethod(name, parameterTypes);
                METHODS.put(key, found);
                return found;
            }
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            String key = type.getSimpleName() + "#" + name;
            synchronized (FIELDS) {
                Field cached = FIELDS.get(key);
                if (cached != null) {
                    return cached;
                }
                Field found = type.getField(name);
                FIELDS.put(key, found);
                return found;
            }
        }

        private static int intField(Object target, String name) {
            try {
                return field(target.getClass(), name).getInt(target);
            } catch (ReflectiveOperationException e) {
                return -1;
            }
        }

        /** 底层异常 → 统一 StorageException 映射。 */
        private static StorageException mapThrowable(Throwable cause, String path, String action) {
            if (cause == null) {
                return StorageException.io(action + "失败", path, null);
            }
            String className = cause.getClass().getName();
            if ("com.jcraft.jsch.SftpException".equals(className)) {
                int id = intField(cause, "id");
                String detail = action + "失败: " + cause.getMessage() + "（SftpException id=" + id + "）";
                if (id == 2) {
                    return StorageException.notFound(path);
                }
                if (id == 3 || id == 13) {
                    // SFTP permission denied（jsch 实际用 3，13 为部分服务端变体）
                    return StorageException.authFailed(detail);
                }
                if (id == 4 || id == 11) {
                    return StorageException.conflict(detail, path);
                }
                return StorageException.io(detail, path, cause);
            }
            if ("com.jcraft.jsch.JSchException".equals(className)) {
                String message = cause.getMessage() == null ? "" : cause.getMessage();
                String lower = message.toLowerCase();
                if (lower.contains("auth")) {
                    return StorageException.authFailed("SFTP 鉴权失败: " + message);
                }
                if (lower.contains("timeout")) {
                    return StorageException.timeout("SFTP 连接超时: " + message);
                }
                if (lower.contains("reject")) {
                    return StorageException.connectFailed("SFTP 连接被拒绝: " + message);
                }
                return StorageException.connectFailed("SFTP 连接失败: " + message);
            }
            return StorageException.io(action + "失败: " + cause.getMessage()
                    + "（" + cause.getClass().getSimpleName() + "）", path, cause);
        }

        /** ls 的单条结果。 */
        private static final class Entry {
            private final String name;
            private final boolean directory;
            private final long size;
            private final int mtimeSeconds;
            /** 属主（名字优先，拿不到时为数字 uid 字符串），未知为 null。 */
            private final String owner;
            /** 用户组（名字优先，拿不到时为数字 gid 字符串），未知为 null。 */
            private final String group;

            private Entry(String name, boolean directory, long size, int mtimeSeconds,
                          String owner, String group) {
                this.name = name;
                this.directory = directory;
                this.size = size;
                this.mtimeSeconds = mtimeSeconds;
                this.owner = owner;
                this.group = group;
            }
        }

        /** lstat 的属性结果。 */
        private static final class Attrs {
            private final boolean directory;
            private final long size;
            private final int mtimeSeconds;
            private final Integer uid;
            private final Integer gid;

            private Attrs(boolean directory, long size, int mtimeSeconds, Integer uid, Integer gid) {
                this.directory = directory;
                this.size = size;
                this.mtimeSeconds = mtimeSeconds;
                this.uid = uid;
                this.gid = gid;
            }
        }

        /** longname 里的名字优先；没有 / 解析失败时退回数字 id（id 无效返回 null）。 */
        private static String ownerOf(String longName, Integer uid) {
            String[] names = parseLongNameOwner(longName);
            if (names != null && names[0] != null && !names[0].isEmpty()) {
                return names[0];
            }
            return uid != null && uid >= 0 ? Integer.toString(uid) : null;
        }

        private static String groupOf(String longName, Integer gid) {
            String[] names = parseLongNameOwner(longName);
            if (names != null && names[1] != null && !names[1].isEmpty()) {
                return names[1];
            }
            return gid != null && gid >= 0 ? Integer.toString(gid) : null;
        }
    }

    /** SFTP v3 longname 形如 "-rw-r--r-- 1 alice staff 5 Oct ..."：第 3/4 列即属主 / 组。 */
    private static final java.util.regex.Pattern UNIX_LONGNAME =
            java.util.regex.Pattern.compile("^[dbclps-][rwxstST-]{9}\\s");

    /**
     * 从 SFTP 条目的 longname 解析属主 / 组。
     *
     * @param longName 服务器返回的 ls -l 风格长名（可为 null）
     * @return [owner, group]；非 Unix 风格或列数不足返回 null
     */
    static String[] parseLongNameOwner(String longName) {
        if (longName == null || !UNIX_LONGNAME.matcher(longName).find()) {
            return null;
        }
        String[] tokens = longName.split("\\s+", 9);
        if (tokens.length < 8) {
            return null;
        }
        return new String[]{tokens[2], tokens[3]};
    }
}
