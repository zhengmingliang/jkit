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
 * 缺依赖时构造成功、首次操作抛 {@link StorageErrorCode#UNSUPPORTED} 并给出明确提示。
 *
 * <p>连接：密码与私钥二选一（私钥可带口令）；StrictHostKeyChecking 默认关闭，
 * 开启后按 yes 交给 JSch 校验 known_hosts。Method / Field 反射句柄按名称缓存在
 * HashMap 中，列表条目逐个取 getFilename / getAttrs 时不再做反射查找。</p>
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
 * privateKeyPassphrase、basePath（默认 {@code /}）、strictHostKeyChecking（默认 false）。</p>
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
    private final String basePath;
    private final boolean strictHostKeyChecking;
    /** 懒建立的 SFTP 通道：构造不连接，首次操作才建立（缺 jsch 依赖时在此暴露）。 */
    private volatile JschBridge bridge;

    /**
     * 由配置创建（配置键：host / port / user / password / privateKey /
     * privateKeyPassphrase / basePath / strictHostKeyChecking）。缺 host / user 直接抛
     * {@link StorageErrorCode#INVALID_CONFIG}；缺 jsch 依赖不会在这里暴露。
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
        this.basePath = StoragePaths.normalize(config.getOr("basePath", "/"));
        this.strictHostKeyChecking = config.boolOr("strictHostKeyChecking", false);
    }

    @Override
    public String platform() {
        return "sftp";
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
        if (attrs.directory) {
            return StorageEntry.dir(normalized, attrs.mtimeSeconds * 1000L);
        }
        return StorageEntry.file(normalized, attrs.size, attrs.mtimeSeconds * 1000L, null);
    }

    @Override
    public List<StorageEntry> list(String dir) {
        String normalized = StoragePaths.normalize(dir);
        List<StorageEntry> entries = new ArrayList<StorageEntry>();
        for (JschBridge.Entry entry : bridge().list(remoteOf(normalized))) {
            String childPath = StoragePaths.join(normalized, entry.name);
            if (entry.directory) {
                entries.add(StorageEntry.dir(childPath, entry.mtimeSeconds * 1000L));
            } else {
                entries.add(StorageEntry.file(childPath, entry.size, entry.mtimeSeconds * 1000L, null));
            }
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
    public void close() {
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
                        privateKeyPassphrase, strictHostKeyChecking);
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
        File temp;
        try {
            temp = File.createTempFile("jkit-storage-sftp-copy", ".tmp");
        } catch (IOException e) {
            throw StorageException.io("创建临时文件失败: " + e.getMessage(), source, e);
        }
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
            try {
                Files.deleteIfExists(temp.toPath());
            } catch (IOException ignore) {
                // 临时文件清理失败不影响结果
            }
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

    private String remoteOf(String normalized) {
        if (basePath.isEmpty()) {
            return normalized.isEmpty() ? "." : normalized;
        }
        return normalized.isEmpty() ? basePath : basePath + "/" + normalized;
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

    /**
     * JSch 反射桥：持有 Session / ChannelSftp 实例，全部方法调用走反射，
     * Method / Field 句柄按「类名#方法名」缓存避免每条目重复查找。
     */
    private static final class JschBridge {
        /** Method 句柄缓存（类名#方法名 → Method），避免列表逐条目重复反射查找。 */
        private static final Map<String, Method> METHODS = new HashMap<String, Method>();
        /** Field 句柄缓存（如 ChannelSftp.OVERWRITE、SftpException.id）。 */
        private static final Map<String, Field> FIELDS = new HashMap<String, Field>();

        private final Object session;
        private final Object channel;
        private final int overwrite;

        private JschBridge(Object session, Object channel, int overwrite) {
            this.session = session;
            this.channel = channel;
            this.overwrite = overwrite;
        }

        /** 建立 SFTP 连接并打开 sftp 通道；classpath 缺 jsch 时抛 UNSUPPORTED。 */
        static JschBridge open(String host, int port, String user, String password,
                               String privateKey, String privateKeyPassphrase,
                               boolean strictHostKeyChecking) {
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
                Object channel = method(session.getClass(), "openChannel", String.class)
                        .invoke(session, "sftp");
                method(channel.getClass(), "connect").invoke(channel);
                return new JschBridge(session, channel, field(channel.getClass(), "OVERWRITE").getInt(null));
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), "-", "连接");
            } catch (ReflectiveOperationException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), "-", e);
            }
        }

        /** ls：返回去掉 "." / ".." 之后的条目列表。 */
        List<Entry> list(String remotePath) {
            try {
                Object vector = method(channel.getClass(), "ls", String.class).invoke(channel, remotePath);
                List<Entry> entries = new ArrayList<Entry>();
                for (Object item : (Vector<?>) vector) {
                    String filename = (String) method(item.getClass(), "getFilename").invoke(item);
                    if (".".equals(filename) || "..".equals(filename)) {
                        continue;
                    }
                    Object attrs = method(item.getClass(), "getAttrs").invoke(item);
                    entries.add(new Entry(filename,
                            (Boolean) method(attrs.getClass(), "isDir").invoke(attrs),
                            (Long) method(attrs.getClass(), "getSize").invoke(attrs),
                            (Integer) method(attrs.getClass(), "getMTime").invoke(attrs)));
                }
                return entries;
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), remotePath, "列出目录");
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            }
        }

        /** lstat：目标不存在时抛 NOT_FOUND。 */
        Attrs lstat(String remotePath) {
            try {
                Object attrs = method(channel.getClass(), "lstat", String.class)
                        .invoke(channel, remotePath);
                return new Attrs((Boolean) method(attrs.getClass(), "isDir").invoke(attrs),
                        (Long) method(attrs.getClass(), "getSize").invoke(attrs),
                        (Integer) method(attrs.getClass(), "getMTime").invoke(attrs));
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), remotePath, "读取元数据");
            } catch (ReflectiveOperationException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            }
        }

        void get(String remotePath, OutputStream out) {
            try {
                method(channel.getClass(), "get", String.class, OutputStream.class)
                        .invoke(channel, remotePath, out);
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), remotePath, "下载");
            } catch (ReflectiveOperationException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            }
        }

        void put(InputStream in, String remotePath) {
            try {
                method(channel.getClass(), "put", InputStream.class, String.class, int.class)
                        .invoke(channel, in, remotePath, overwrite);
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), remotePath, "上传");
            } catch (ReflectiveOperationException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
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
            try {
                method(channel.getClass(), "rename", String.class, String.class)
                        .invoke(channel, fromPath, toPath);
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), toPath, "重命名");
            } catch (ReflectiveOperationException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), toPath, e);
            }
        }

        private void invoke(String name, String remotePath, String action) {
            try {
                method(channel.getClass(), name, String.class).invoke(channel, remotePath);
            } catch (InvocationTargetException e) {
                throw mapThrowable(e.getCause(), remotePath, action);
            } catch (ReflectiveOperationException e) {
                throw StorageException.io("JSch 反射调用失败: " + e.getMessage(), remotePath, e);
            }
        }

        void close() {
            try {
                method(channel.getClass(), "disconnect").invoke(channel);
            } catch (ReflectiveOperationException ignore) {
                // 通道关闭失败不影响会话关闭
            }
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

            private Entry(String name, boolean directory, long size, int mtimeSeconds) {
                this.name = name;
                this.directory = directory;
                this.size = size;
                this.mtimeSeconds = mtimeSeconds;
            }
        }

        /** lstat 的属性结果。 */
        private static final class Attrs {
            private final boolean directory;
            private final long size;
            private final int mtimeSeconds;

            private Attrs(boolean directory, long size, int mtimeSeconds) {
                this.directory = directory;
                this.size = size;
                this.mtimeSeconds = mtimeSeconds;
            }
        }
    }
}
